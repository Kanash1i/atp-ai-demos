package com.atp.platform.service;

import com.atp.common.enums.NodeStatus;
import com.atp.common.enums.RunStatus;
import com.atp.common.enums.TaskStatus;
import com.atp.common.util.DisplayTime;
import com.atp.platform.entity.ExecNode;
import com.atp.platform.entity.ExecRun;
import com.atp.platform.entity.ExecStepResult;
import com.atp.platform.entity.ExecTask;
import com.atp.platform.mapper.ExecNodeMapper;
import com.atp.platform.mapper.ExecRunMapper;
import com.atp.platform.mapper.ExecStatsMapper;
import com.atp.platform.mapper.ExecStepResultMapper;
import com.atp.platform.mapper.ExecTaskMapper;
import com.atp.platform.mapper.TcProjectMapper;
import com.atp.platform.vo.ExecStatsVO;
import com.atp.platform.vo.NodeVO;
import com.atp.platform.vo.RunningRunVO;
import com.atp.platform.vo.TaskDetailVO;
import com.atp.platform.vo.TaskSummaryVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 执行看板的读侧。
 *
 * <p>历史数据来自 {@code ExecutionSeed}，正在跑的批次来自真实执行（M2 的 atp-runner）。
 */
@Slf4j
@Service
public class ExecutionQueryService {

    /** 心跳超过这个时长就当节点掉线了 */
    private static final Duration HEARTBEAT_TIMEOUT = Duration.ofMinutes(2);

    @Autowired
    private ExecRunMapper runMapper;
    @Autowired
    private ExecTaskMapper taskMapper;
    @Autowired
    private ExecStepResultMapper stepResultMapper;
    @Autowired
    private ExecNodeMapper nodeMapper;
    @Autowired
    private ExecStatsMapper statsMapper;
    @Autowired
    private TcProjectMapper projectMapper;

    /** 今日统计，带与昨日的环比 */
    public ExecStatsVO stats() {
        OffsetDateTime todayStart = OffsetDateTime.now(DisplayTime.ZONE)
                .withHour(0).withMinute(0).withSecond(0).withNano(0);
        OffsetDateTime tomorrow = todayStart.plusDays(1);
        OffsetDateTime yesterday = todayStart.minusDays(1);

        Map<String, Object> today = statsMapper.statsBetween(todayStart, tomorrow);
        Map<String, Object> prev = statsMapper.statsBetween(yesterday, todayStart);

        long total = num(today.get("total")).longValue();
        long passed = num(today.get("passed")).longValue();
        long failed = num(today.get("failed")).longValue();
        int avgSec = toSeconds(today.get("avg_duration_ms"));

        // 分母是 PASSED + FAILED —— SKIPPED 没跑，算进去会把通过率压低
        long ran = passed + failed;
        double passRate = ran == 0 ? 0 : Math.round(passed * 1000.0 / ran) / 10.0;

        long prevTotal = num(prev.get("total")).longValue();
        Double delta = prevTotal == 0 ? null
                : Math.round((total - prevTotal) * 1000.0 / prevTotal) / 10.0;
        int prevAvg = toSeconds(prev.get("avg_duration_ms"));
        Integer avgDelta = prevAvg == 0 ? null : avgSec - prevAvg;

        return new ExecStatsVO(total, passRate, avgSec, failed,
                statsMapper.failedP0Between(todayStart, tomorrow), delta, avgDelta);
    }

    /**
     * 正在跑的批次。没有就返回 null。
     *
     * <p>⚠️ 返回 null 而不是造一个假的 —— 摆个不动的进度条，演示时一刷新就露馅。
     * 前端在这里显示「当前无执行中的批次」，现场派发一批它就活了。
     */
    public RunningRunVO running() {
        ExecRun run = runMapper.selectOne(new LambdaQueryWrapper<ExecRun>()
                .eq(ExecRun::getStatus, RunStatus.RUNNING)
                .orderByDesc(ExecRun::getStartedAt)
                .last("LIMIT 1"));
        if (run == null) {
            return null;
        }
        int done = nz(run.getPassedCount()) + nz(run.getFailedCount()) + nz(run.getSkippedCount());
        long elapsed = run.getStartedAt() == null ? 0
                : Duration.between(run.getStartedAt(), OffsetDateTime.now()).toSeconds();

        // 剩余时间按「已跑完的平均耗时 × 剩余条数」推。任务少时很不准，前端要显示成 ≈
        Long eta = null;
        int remaining = nz(run.getTotalCount()) - done;
        if (done > 0 && remaining > 0) {
            eta = (long) ((double) elapsed / done * remaining);
        }

        var project = run.getProjectId() == null ? null : projectMapper.selectById(run.getProjectId());
        return new RunningRunVO(
                run.getRunId(), run.getRunCode(),
                project == null ? null : project.getProjectName(),
                run.getSuiteName(),
                run.getBrowser() == null ? null : run.getBrowser().name(),
                run.getTriggerSource() == null ? null : run.getTriggerSource().name(),
                done, nz(run.getTotalCount()),
                nz(run.getPassedCount()), nz(run.getFailedCount()),
                nz(run.getSkippedCount()), nz(run.getRunningCount()),
                elapsed, eta);
    }

    /** 最近执行结果。默认 200 条，与设计稿一致 */
    public List<TaskSummaryVO> recent(int limit) {
        return taskMapper.selectList(new LambdaQueryWrapper<ExecTask>()
                        .isNotNull(ExecTask::getFinishedAt)
                        .orderByDesc(ExecTask::getFinishedAt)
                        .last("LIMIT " + Math.min(Math.max(limit, 1), 500)))
                .stream()
                .map(t -> new TaskSummaryVO(
                        t.getTaskId(), t.getCaseCode(), t.getCaseTitle(),
                        t.getBrowser() == null ? null : t.getBrowser().name(),
                        t.getNodeName(),
                        t.getStatus() == null ? null : t.getStatus().name(),
                        durationText(t.getDurationMs()),
                        DisplayTime.toSecond(t.getFinishedAt()),
                        t.getVideoUrl() != null))
                .toList();
    }

    /** 失败详情：任务 + 步骤级结果 + 产物 */
    public TaskDetailVO taskDetail(String taskId) {
        ExecTask t = taskMapper.selectById(taskId);
        if (t == null) {
            throw new TaskNotFoundException(taskId);
        }
        ExecRun run = t.getRunId() == null ? null : runMapper.selectById(t.getRunId());
        List<ExecStepResult> steps = stepResultMapper.selectList(
                new LambdaQueryWrapper<ExecStepResult>()
                        .eq(ExecStepResult::getTaskId, taskId)
                        .orderByAsc(ExecStepResult::getSeq));

        return new TaskDetailVO(
                t.getTaskId(),
                run == null ? null : run.getRunCode(),
                t.getCaseId(), t.getCaseCode(), t.getCaseTitle(),
                t.getBrowser() == null ? null : t.getBrowser().name(),
                t.getNodeName(),
                t.getStatus() == null ? null : t.getStatus().name(),
                durationText(t.getDurationMs()),
                DisplayTime.toSecond(t.getStartedAt()),
                DisplayTime.toSecond(t.getFinishedAt()),
                t.getFailedSeq(), t.getErrorMsg(), t.getVideoUrl(), t.getScreenshotUrl(),
                steps.stream().map(s -> new TaskDetailVO.StepResultVO(
                        s.getSeq(), s.getAction(),
                        s.getStatus() == null ? null : s.getStatus().name(),
                        durationText(s.getDurationMs()),
                        s.getErrorMsg(), s.getScreenshotUrl())).toList());
    }

    /** 节点池。在线与否按心跳算 */
    public List<NodeVO> nodes() {
        OffsetDateTime deadline = OffsetDateTime.now().minus(HEARTBEAT_TIMEOUT);
        return nodeMapper.selectList(new LambdaQueryWrapper<ExecNode>()
                        .orderByAsc(ExecNode::getNodeName))
                .stream()
                .map(n -> new NodeVO(
                        n.getNodeName(),
                        n.getStatus() == null ? NodeStatus.OFFLINE.name() : n.getStatus().name(),
                        n.getHeartbeatAt() != null && n.getHeartbeatAt().isAfter(deadline),
                        n.getCurrentTaskId(),
                        DisplayTime.toSecond(n.getHeartbeatAt())))
                .toList();
    }

    // ── 内部 ──────────────────────────────────────────────────

    /** 「38.2s」/「1m 12s」。SKIPPED 没有耗时 */
    private String durationText(Integer ms) {
        if (ms == null) {
            return null;
        }
        if (ms < 60_000) {
            return "%.1fs".formatted(ms / 1000.0);
        }
        return "%dm %ds".formatted(ms / 60_000, (ms % 60_000) / 1000);
    }

    private int toSeconds(Object avgMs) {
        Number n = num(avgMs);
        return n == null ? 0 : (int) Math.round(n.doubleValue() / 1000.0);
    }

    /** PG 的 count 回 Long、avg 回 BigDecimal，统一收口 */
    private Number num(Object v) {
        if (v == null) {
            return 0;
        }
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        return (Number) v;
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
