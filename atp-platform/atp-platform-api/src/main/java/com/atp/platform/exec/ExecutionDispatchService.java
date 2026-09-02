package com.atp.platform.exec;

import com.atp.common.enums.Browser;
import com.atp.common.enums.RunStatus;
import com.atp.common.enums.TaskStatus;
import com.atp.common.enums.TriggerSource;
import com.atp.platform.entity.ExecRun;
import com.atp.platform.entity.ExecTask;
import com.atp.platform.entity.TcCase;
import com.atp.platform.mapper.ExecRunMapper;
import com.atp.platform.mapper.ExecTaskMapper;
import com.atp.platform.mapper.TcCaseMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 派发执行。
 *
 * <p>产生一条 {@code exec_run} 和 N 条 {@code exec_task}（PENDING），然后把任务号推进队列。
 *
 * <p>⚠️ 落库与入队**不在同一个事务里能保证的范围内** —— Redis 不参与数据库事务。
 * 顺序是先落库、后入队：这样最坏情况是「任务在 PG 里挂着 PENDING 但没进队列」，
 * 补一次扫描就能捞回来；反过来先入队的话，节点可能拿到一个还没落库的任务号，
 * 那是查不到、也补不回来的。
 */
@Slf4j
@Service
public class ExecutionDispatchService {

    private static final DateTimeFormatter RUN_CODE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Autowired
    private ExecRunMapper runMapper;
    @Autowired
    private ExecTaskMapper taskMapper;
    @Autowired
    private TcCaseMapper caseMapper;
    @Autowired
    private ExecutionQueue queue;
    @Autowired
    private JdbcTemplate jdbc;

    /**
     * @param caseIds  要跑的案例。空表示跑该项目下全部 ACTIVE 案例
     * @param trigger  人工 / agent / 定时 —— 看板可以按它分组，对比两条 AI 路线跑出来的结果
     */
    @Transactional
    public ExecRun dispatch(String projectId, List<String> caseIds, Browser browser,
                            String suiteName, TriggerSource trigger, String createdBy) {
        List<TcCase> cases = resolveCases(projectId, caseIds);
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("没有可执行的案例：projectId=" + projectId);
        }

        String runId = UUID.randomUUID().toString();
        ExecRun run = new ExecRun();
        run.setRunId(runId);
        run.setRunCode(nextRunCode());
        run.setProjectId(projectId);
        run.setSuiteName(suiteName);
        run.setBrowser(browser == null ? Browser.CHROME : browser);
        run.setStatus(RunStatus.RUNNING);
        run.setTotalCount(cases.size());
        run.setPassedCount(0);
        run.setFailedCount(0);
        run.setSkippedCount(0);
        run.setRunningCount(0);
        run.setTriggerSource(trigger == null ? TriggerSource.MANUAL : trigger);
        run.setCreatedBy(createdBy);
        run.setStartedAt(OffsetDateTime.now());
        run.setCreatedAt(OffsetDateTime.now());
        runMapper.insert(run);

        // ⚠️ 批量插入，不要逐条 insert。
        //
        //    逐条写在同机部署时看不出问题（一次往返零点几毫秒），但这个项目的
        //    平台在云服务器、PG 在家里的台式机，一次往返 35ms —— 109 条案例
        //    就是 109 次跨地域往返，加上每条的事务提交，实测派发一个批次要几十秒。
        //
        //    而这几十秒里看板显示的是 `0 / 109` 且 Elapsed 已经在涨，
        //    和「任务卡住了」长得一模一样。**慢和卡在界面上是同一个样子。**
        OffsetDateTime queuedAt = OffsetDateTime.now();
        List<String> taskIds = new ArrayList<>(cases.size());
        List<Object[]> rows = new ArrayList<>(cases.size());
        for (TcCase c : cases) {
            String taskId = UUID.randomUUID().toString();
            taskIds.add(taskId);
            rows.add(new Object[]{
                    taskId, runId, c.getCaseId(),
                    // 快照：案例后来改名或删除，历史执行记录仍要显示当时跑的是什么
                    c.getCaseCode(), c.getTitle(),
                    run.getBrowser().code(), TaskStatus.PENDING.code(),
                    (short) 0, queuedAt});
        }
        jdbc.batchUpdate("""
                INSERT INTO exec_task
                  (task_id, run_id, case_id, case_code, case_title, browser, status, retry_count, queued_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, rows);

        queue.push(taskIds);
        log.info("派发批次 {}：{} 条案例，触发来源 {}", run.getRunCode(), cases.size(), run.getTriggerSource());
        return run;
    }

    private List<TcCase> resolveCases(String projectId, List<String> caseIds) {
        if (caseIds != null && !caseIds.isEmpty()) {
            return caseMapper.selectBatchIds(caseIds);
        }
        // 不指定案例时跑该项目下全部 —— 通过模块反查，因为 tc_case 上只有 module_id
        return caseMapper.selectList(new LambdaQueryWrapper<TcCase>()
                .inSql(TcCase::getModuleId,
                        "SELECT module_id FROM tc_module WHERE project_id = '" + sanitize(projectId) + "'")
                .orderByAsc(TcCase::getCaseCode));
    }

    /** projectId 来自路径参数，拼进 SQL 前先掐掉非法字符 —— 字典 id 只可能是字母数字 */
    private String sanitize(String projectId) {
        if (projectId == null || !projectId.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("非法的 projectId：" + projectId);
        }
        return projectId;
    }

    /** RUN-20260830-0001。同一天内自增 */
    private String nextRunCode() {
        String date = OffsetDateTime.now().format(RUN_CODE_DATE);
        Long todayCount = runMapper.selectCount(new LambdaQueryWrapper<ExecRun>()
                .likeRight(ExecRun::getRunCode, "RUN-" + date));
        return "RUN-%s-%04d".formatted(date, (todayCount == null ? 0 : todayCount) + 1);
    }
}
