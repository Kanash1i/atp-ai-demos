package com.atp.platform.service;

import com.atp.common.enums.Browser;
import com.atp.common.enums.TriggerSource;
import com.atp.platform.entity.ExecRun;
import com.atp.platform.entity.ExecTask;
import com.atp.platform.exec.ExecutionDispatchService;
import com.atp.platform.mapper.ExecTaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 跑一次，等结果 —— 给 agent 用的「写完自己验一遍」。
 *
 * <h3>⭐ 为什么只跑一次，不做「失败就改、改完再跑」的闭环</h3>
 *
 * 自动重试听起来很美，但有两个问题，任何一个都足以否掉它：
 *
 * <ol>
 *   <li><b>执行失败 ≠ 案例写错了。</b> 被测系统本身有 bug 时，
 *       自动改案例会把这个 bug「改没」—— 而发现 bug 正是测试的目的。</li>
 *   <li><b>改到能跑通 ≠ 改对了。</b> 让 agent 以「跑通」为目标，
 *       它最省力的路径是**削弱断言**：断言不了就删掉，等不到就放宽。
 *       测试变绿了，但什么也不保证了。</li>
 * </ol>
 *
 * <p>所以这里的语义是**跑一次、如实报告、人决定**。
 * agent 拿到失败结果的正确动作是把现象讲清楚，而不是自己动手修。
 *
 * <h3>「案例失败」不是接口错误</h3>
 *
 * 案例跑挂了，这次调用仍然是成功的 —— 它成功地告诉了你案例跑挂了。
 * 真正的错误是「没有执行机」「等超时了」这类**拿不到结论**的情况。
 * 两者混在一起的话，agent 分不清"案例有问题"和"环境有问题"。
 */
@Slf4j
@Service
public class RunOnceService {

    /** 轮询间隔。执行一条案例通常几百毫秒到几秒，这个粒度足够 */
    private static final long POLL_INTERVAL_MS = 500;

    @Autowired
    private ExecutionDispatchService dispatchService;

    @Autowired
    private ExecTaskMapper taskMapper;

    /**
     * @param timeoutSec 等结果的上限。到点没跑完返回 TIMEOUT ——
     *                   注意这跟「案例执行超时」不是一回事：这里是**我们不等了**，
     *                   任务可能还在跑，结果后面会正常写回库里
     */
    public RunOnceResult runOnce(String projectId, String caseId, int timeoutSec) {
        ExecRun run = dispatchService.dispatch(projectId, List.of(caseId), Browser.CHROME,
                "agent 自验", TriggerSource.AGENT, "agent");

        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        ExecTask task = null;

        while (System.currentTimeMillis() < deadline) {
            task = taskMapper.selectOne(new LambdaQueryWrapper<ExecTask>()
                    .eq(ExecTask::getRunId, run.getRunId())
                    .eq(ExecTask::getCaseId, caseId)
                    .last("LIMIT 1"));

            if (task != null && task.getStatus() != null && task.getStatus().isTerminal()) {
                log.info("[RUN_ONCE] {} → {} ({}ms)", caseId, task.getStatus(), task.getDurationMs());
                return RunOnceResult.of(run.getRunCode(), task);
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return RunOnceResult.timeout(run.getRunCode(), "等待被中断");
            }
        }

        // ⚠️ 超时不代表案例失败 —— 可能是没有执行机在线，也可能是这条案例本来就慢。
        //    如实说「没等到结果」，不要替它下结论
        String detail = task == null || task.getStatus() == null
                ? "任务还没被任何执行机认领 —— 检查是否有节点在线"
                : "任务仍在 " + task.getStatus() + " 状态";
        log.warn("[RUN_ONCE] {} 等待 {} 秒未出结果：{}", caseId, timeoutSec, detail);
        return RunOnceResult.timeout(run.getRunCode(), detail);
    }

    /**
     * @param terminal 是否拿到了结论。false 表示没等到，此时 status 不可信
     */
    public record RunOnceResult(
            boolean terminal,
            String runCode,
            String taskId,
            String status,
            Integer durationMs,
            Integer failedSeq,
            String errorMsg,
            String videoUrl,
            String note) {

        static RunOnceResult of(String runCode, ExecTask t) {
            return new RunOnceResult(true, runCode, t.getTaskId(),
                    t.getStatus().name(), t.getDurationMs(), t.getFailedSeq(),
                    t.getErrorMsg(), t.getVideoUrl(), null);
        }

        static RunOnceResult timeout(String runCode, String note) {
            return new RunOnceResult(false, runCode, null, "TIMEOUT",
                    null, null, null, null, note);
        }
    }
}
