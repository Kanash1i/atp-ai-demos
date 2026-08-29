package com.atp.web;

import com.atp.platform.exec.ExecutionQueue;
import com.atp.platform.exec.ZombieTaskReaper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时回收掉线节点手上的任务。
 *
 * <p>⚠️ 跑在**主应用**而不是执行节点上：节点自己掉线了，不可能由它来回收自己的任务。
 * 这类清理逻辑必须放在一个不会跟着一起挂的地方。
 */
@Slf4j
@Component
public class ZombieReaperTask {

    @Autowired
    private ZombieTaskReaper reaper;

    @Autowired
    private ExecutionQueue queue;

    @Scheduled(fixedDelayString = "${atp.exec.reap-interval-seconds:30}000")
    public void reap() {
        try {
            // ⭐ 两步走：reap() 在事务里改状态，提交之后这里才入队。
            //    合成一步的话，节点会在数据库改动可见之前就取到任务号，认领必然失败。
            java.util.List<String> requeue = reaper.reap();
            if (!requeue.isEmpty()) {
                queue.push(requeue);
                log.info("回收了 {} 条掉线节点上的任务，已重新入队", requeue.size());
            }
        } catch (Exception e) {
            log.error("回收僵尸任务失败", e);
        }
    }
}
