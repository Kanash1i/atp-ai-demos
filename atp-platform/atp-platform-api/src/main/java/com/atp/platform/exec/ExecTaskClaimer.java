package com.atp.platform.exec;

import com.atp.common.enums.TaskStatus;
import com.atp.platform.entity.ExecTask;
import com.atp.platform.mapper.ExecTaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * 任务认领 —— 并发下只有一个节点能拿到同一条任务。
 *
 * <h3>⭐ 仲裁点就是那句 WHERE</h3>
 *
 * <pre>
 *   UPDATE exec_task SET status = RUNNING, node_name = ?
 *   WHERE task_id = ? AND status = PENDING
 *                       ^^^^^^^^^^^^^^^^^^ 仲裁点
 * </pre>
 *
 * 受影响行数为 1 = 认领成功；为 0 = 已经被别的节点拿走了，本节点直接跳过。
 * <b>不需要分布式锁</b> —— 数据库的行锁本来就提供了这个语义，
 * 再加一层 Redis 锁只是多一个会失效、会过期、会脑裂的组件。
 *
 * <p>这是本仓库里同一个思路的第三次应用：
 * demo2 CLI 的案例落库（幂等键做主键 + CAS）、审批的并发决策、以及这里。
 * 三处的共同点是<b>把仲裁交给已经存在的唯一性约束</b>，而不是新引入一个协调者。
 */
@Slf4j
@Component
public class ExecTaskClaimer {

    @Autowired
    private ExecTaskMapper taskMapper;

    /**
     * 认领任务。
     *
     * @return 认领成功返回任务，被别人抢先或任务不存在返回 {@code null}
     */
    public ExecTask claim(String taskId, String nodeName) {
        ExecTask update = new ExecTask();
        update.setStatus(TaskStatus.RUNNING);
        update.setNodeName(nodeName);
        update.setStartedAt(OffsetDateTime.now());

        int affected = taskMapper.update(update, new LambdaQueryWrapper<ExecTask>()
                .eq(ExecTask::getTaskId, taskId)
                .eq(ExecTask::getStatus, TaskStatus.PENDING));

        if (affected == 0) {
            // 正常现象，不是错误：Redis 重复投递、或者中止后残留的任务号
            log.debug("任务 {} 认领失败（已被取走或已中止）", taskId);
            return null;
        }
        return taskMapper.selectById(taskId);
    }
}
