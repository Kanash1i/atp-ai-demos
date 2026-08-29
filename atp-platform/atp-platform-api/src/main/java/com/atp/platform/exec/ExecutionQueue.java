package com.atp.platform.exec;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 执行任务队列。
 *
 * <h3>⭐ Redis 里只放 taskId，不放任务内容</h3>
 *
 * 任务的**真相在 PG**（{@code exec_task} 那一行），Redis 只承担「有活儿了，谁来干」的通知。
 * 这样安排是因为两者的失败模式完全不同：
 *
 * <ul>
 *   <li><b>Redis 丢消息</b>（重启、内存淘汰）→ 任务还在 PG 里挂着 PENDING，
 *       补一次扫描重新入队即可，不丢活</li>
 *   <li><b>Redis 重复投递</b>（网络抖动导致 ack 丢失）→ 两个节点同时拿到同一个 taskId，
 *       但只有一个能把 {@code status} 从 PENDING 改成 RUNNING（见 {@code ExecTaskClaimer}），
 *       另一个拿到 0 行更新，直接跳过</li>
 * </ul>
 *
 * <p>如果把任务内容也放进 Redis，就变成两处都存状态，一旦不一致谁也说不清哪个对。
 *
 * <p>⚠️ 这跟 demo2 CLI 那条「幂等键做成主键 + CAS UPDATE 当仲裁点」是同一个思路的第三次应用
 * （另外两处：审批的并发决策、CLI 的案例落库）。
 */
@Slf4j
@Component
public class ExecutionQueue {

    /** 待执行任务。⚠️ 用 List 而不是 Stream：不需要消费组和回溯，BRPOP 的阻塞语义正好够用 */
    public static final String QUEUE_KEY = "atp:exec:pending";

    @Autowired
    private StringRedisTemplate redis;

    /** 派发：把任务号推进队列。左进右出，先派的先跑 */
    public void push(List<String> taskIds) {
        if (taskIds.isEmpty()) {
            return;
        }
        redis.opsForList().leftPushAll(QUEUE_KEY, taskIds);
        log.info("已入队 {} 条执行任务", taskIds.size());
    }

    /**
     * 节点取任务，阻塞等待。
     *
     * <p>⚠️ 用阻塞式 BRPOP 而不是轮询：空闲时不产生任何请求，来活儿时毫秒级响应。
     * 轮询要在「延迟」和「空转开销」之间选，而这里不必选。
     *
     * @param timeoutSec 阻塞上限。到点返回 null，让调用方有机会检查中断标志、更新心跳
     */
    public String take(int timeoutSec) {
        return redis.opsForList().rightPop(QUEUE_KEY, timeoutSec, TimeUnit.SECONDS);
    }

    public long depth() {
        Long size = redis.opsForList().size(QUEUE_KEY);
        return size == null ? 0 : size;
    }

    /** 中止批次时把残留的任务号清掉 —— 已经被节点取走的那些由节点自己检查中止标志 */
    public void clear() {
        redis.delete(QUEUE_KEY);
    }
}
