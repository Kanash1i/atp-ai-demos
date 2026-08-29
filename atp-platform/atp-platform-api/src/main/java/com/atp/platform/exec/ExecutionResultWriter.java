package com.atp.platform.exec;

import com.atp.common.enums.StepStatus;
import com.atp.common.enums.TaskStatus;
import com.atp.platform.entity.ExecStepResult;
import com.atp.platform.entity.ExecTask;
import com.atp.platform.mapper.ExecCounterMapper;
import com.atp.platform.mapper.ExecStepResultMapper;
import com.atp.platform.mapper.ExecTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 执行节点把结果写回来。
 *
 * <p>一次收尾要动三张表：任务本身、步骤明细、批次计数。放在一个事务里 ——
 * 计数加了但任务没落地（或反过来）的话，看板上的数字和列表就对不上，
 * 而这种不一致没有任何办法事后修复。
 */
@Slf4j
@Service
public class ExecutionResultWriter {

    @Autowired
    private ExecTaskMapper taskMapper;
    @Autowired
    private ExecStepResultMapper stepResultMapper;
    @Autowired
    private ExecCounterMapper counterMapper;

    @Transactional
    public void finish(String taskId, TaskStatus status, long durationMs, Integer failedSeq,
                       String errorMsg, String videoUrl, String screenshotUrl,
                       List<StepRecord> steps) {
        ExecTask task = taskMapper.selectById(taskId);
        if (task == null) {
            log.warn("要回写的任务不存在：{}", taskId);
            return;
        }

        ExecTask update = new ExecTask();
        update.setTaskId(taskId);
        update.setStatus(status);
        update.setDurationMs((int) durationMs);
        update.setFailedSeq(failedSeq);
        update.setErrorMsg(errorMsg);
        update.setVideoUrl(videoUrl);
        update.setScreenshotUrl(screenshotUrl);
        update.setFinishedAt(OffsetDateTime.now());
        taskMapper.updateById(update);

        for (StepRecord s : steps) {
            ExecStepResult row = new ExecStepResult();
            row.setResultId(UUID.randomUUID().toString());
            row.setTaskId(taskId);
            row.setSeq(s.seq());
            row.setAction(s.action());
            row.setStatus(s.status());
            row.setDurationMs((int) s.durationMs());
            row.setErrorMsg(s.errorMsg());
            row.setScreenshotUrl(s.screenshotUrl());
            stepResultMapper.insert(row);
        }

        counterMapper.increment(task.getRunId(), counterColumn(status));
        // 全部跑完就收尾批次。判断在 SQL 里做，避免最后两条同时收尾时谁都不收尾
        counterMapper.finishIfComplete(task.getRunId());
    }

    /** ⚠️ 列名只能从这里来，不接受外部输入 —— 它是拼进 SQL 的 */
    private String counterColumn(TaskStatus status) {
        return switch (status) {
            case PASSED -> "passed_count";
            case FAILED -> "failed_count";
            case SKIPPED, ABORTED -> "skipped_count";
            default -> throw new IllegalArgumentException("任务不该以 " + status + " 收尾");
        };
    }

    /** 步骤结果的传输对象。runner 不依赖平台的实体类，只传这个 */
    public record StepRecord(int seq, String action, StepStatus status, long durationMs,
                             String errorMsg, String screenshotUrl) {
    }
}
