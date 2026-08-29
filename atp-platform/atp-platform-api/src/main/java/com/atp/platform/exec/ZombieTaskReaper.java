package com.atp.platform.exec;

import com.atp.common.enums.TaskStatus;
import com.atp.platform.entity.ExecNode;
import com.atp.platform.entity.ExecTask;
import com.atp.platform.mapper.ExecNodeMapper;
import com.atp.platform.mapper.ExecTaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 回收掉线节点手上的任务。
 *
 * <h3>要解决的问题</h3>
 *
 * 执行节点是独立进程。被 kill、机器断电、Tailscale 断线时，
 * 它已经认领（{@code status = RUNNING}）的任务<b>永远不会被收尾</b> ——
 * 没有任何人有责任去改那一行。表现是批次进度条停在 19/20，一直等下去，
 * <b>而且不报任何错</b>。这类「少一条」的故障是分布式执行里最典型的一种。
 *
 * <h3>怎么判定「掉线」</h3>
 *
 * 两个条件同时成立才回收：
 * <ol>
 *   <li>任务处于 RUNNING 且已经跑了超过阈值 —— 排除正常的长任务</li>
 *   <li>它所在节点的<b>心跳过期</b> —— 节点还活着就说明任务真在跑，不该抢</li>
 * </ol>
 *
 * <p>只看时间不看心跳的话，一条正常的慢案例会被误判、被重复执行；
 * 只看心跳不看时间的话，节点重启的瞬间会把刚认领的任务抢走。两个条件缺一不可。
 *
 * <h3>为什么要重试上限</h3>
 *
 * 如果某条任务本身会让节点崩溃（触发了浏览器的某个 bug、案例里有超大文件上传），
 * 无限重试就是无限崩溃 —— 一条毒任务能把整个执行池拖垮。
 * 超过上限直接判失败，把问题暴露在看板上，而不是让它悄悄消耗所有节点。
 */
@Slf4j
@Component
public class ZombieTaskReaper {

    @Autowired
    private ExecTaskMapper taskMapper;
    @Autowired
    private ExecNodeMapper nodeMapper;
    @Autowired
    private ExecutionResultWriter resultWriter;

    /** 任务跑多久算可疑。要大于最慢的正常案例，否则会误伤 */
    @Value("${atp.exec.zombie-threshold-seconds:90}")
    private int zombieThresholdSeconds;

    /** 节点心跳多久算掉线。要大于心跳间隔的两三倍，否则一次网络抖动就误判 */
    @Value("${atp.exec.node-offline-seconds:90}")
    private int nodeOfflineSeconds;

    @Value("${atp.exec.max-retry:2}")
    private int maxRetry;

    /** PENDING 多久没被认领就当它掉队了。要大于队列积压时的正常等待时间 */
    @Value("${atp.exec.orphan-threshold-seconds:60}")
    private int orphanThresholdSeconds;

    /**
     * 回收掉线节点手上的任务。
     *
     * <h3>⚠️ 这个方法**不入队** —— 它只返回要重投的任务号</h3>
     *
     * 曾经在这里面直接推 Redis，结果任务被回收了却没人接管。根因是事务边界：
     * <pre>
     *   PG:    UPDATE ... SET status = PENDING   ← 事务未提交，别人查到的还是 RUNNING
     *   Redis: LPUSH taskId                      ← 立刻可见，节点马上取走
     *   节点:  claim(taskId) → 看到 RUNNING → 认领失败 → 丢弃
     * </pre>
     * <b>消息比数据的可见性更早到达。</b> 这类 bug 不报任何错 ——
     * 日志里明明写着「已放回队列」，任务却永远躺在那儿没人动。
     *
     * <p>所以入队必须发生在事务提交之后，由调用方负责。
     *
     * @return 需要重新入队的任务号（调用方在事务外 push）
     */
    @Transactional
    public List<String> reap() {
        List<String> requeue = new ArrayList<>(reapRunning());
        requeue.addAll(reapOrphanPending());
        return requeue;
    }

    /**
     * 捞回「状态是 PENDING、但队列里没有它的号」的孤儿任务。
     *
     * <h3>为什么会有孤儿</h3>
     *
     * Redis 只做「有活儿了」的通知，任务的真相在 PG。两者不在同一个事务里，
     * 所以总会有不一致的窗口：Redis 重启丢消息、内存淘汰、或者某次代码 bug
     * 让消息发早了被丢弃 —— 任务就会永远躺在 PENDING 上，队列里却没有它。
     * <b>这不报错，只是那条案例永远不跑，批次永远收不了尾。</b>
     *
     * <p>补偿办法就是这个扫描：超过阈值还没被认领的 PENDING，重新入队一次。
     * 重复入队是安全的 —— 认领时的 CAS 保证只有一个节点能拿到。
     *
     * <p>⚠️ 阈值要大于「队列积压时任务的正常等待时间」，否则排队中的任务会被反复重投。
     * 重投本身无害，但会让队列长度虚高，看着像积压。
     */
    private List<String> reapOrphanPending() {
        OffsetDateTime deadline = OffsetDateTime.now().minusSeconds(orphanThresholdSeconds);
        List<ExecTask> orphans = taskMapper.selectList(new LambdaQueryWrapper<ExecTask>()
                .eq(ExecTask::getStatus, TaskStatus.PENDING)
                .lt(ExecTask::getQueuedAt, deadline)
                .last("LIMIT 200"));
        if (orphans.isEmpty()) {
            return List.of();
        }

        List<String> ids = new ArrayList<>();
        for (ExecTask task : orphans) {
            int retried = task.getRetryCount() == null ? 0 : task.getRetryCount();
            if (retried >= maxRetry) {
                // ⚠️ 重投也要有上限。没有节点在线时，任务会一轮一轮被扫到，
                //    如果只投不记账，队列长度会一直涨（实测 30 秒一轮涨到 60 条），
                //    而看板上还是「没人干活」—— 队列积压掩盖了真正的问题：没有可用节点。
                resultWriter.finish(task.getTaskId(), TaskStatus.FAILED, 0, null,
                        "重投 %d 次仍无节点认领，可能没有可用的执行节点".formatted(retried),
                        null, null, List.of());
                log.warn("任务 {} 重投 {} 次仍无人认领，判失败", task.getCaseCode(), retried);
                continue;
            }
            // ⭐ 关键：重投时把 queued_at 推到现在，并累加重试次数。
            //    不更新的话，下一轮扫描会再次命中同一批任务 —— 每 30 秒重复投递一次，
            //    队列里堆的全是同一批任务号。它们最终都会被 CAS 挡下，不产生错误，
            //    但队列深度会一直涨，看起来像积压。
            taskMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<ExecTask>()
                    .eq("task_id", task.getTaskId())
                    .eq("status", TaskStatus.PENDING.code())
                    .set("queued_at", OffsetDateTime.now())
                    .set("retry_count", retried + 1));
            ids.add(task.getTaskId());
        }
        if (!ids.isEmpty()) {
            log.warn("发现 {} 条 PENDING 却不在队列里的任务，重新入队", ids.size());
        }
        return ids;
    }

    private List<String> reapRunning() {
        OffsetDateTime taskDeadline = OffsetDateTime.now().minusSeconds(zombieThresholdSeconds);
        List<ExecTask> suspects = taskMapper.selectList(new LambdaQueryWrapper<ExecTask>()
                .eq(ExecTask::getStatus, TaskStatus.RUNNING)
                .lt(ExecTask::getStartedAt, taskDeadline));
        if (suspects.isEmpty()) {
            return List.of();
        }

        Map<String, ExecNode> nodes = nodeMapper.selectList(null).stream()
                .collect(Collectors.toMap(ExecNode::getNodeName, Function.identity(), (a, b) -> a));
        OffsetDateTime nodeDeadline = OffsetDateTime.now().minusSeconds(nodeOfflineSeconds);

        List<String> requeue = new ArrayList<>();

        for (ExecTask task : suspects) {
            ExecNode node = nodes.get(task.getNodeName());
            boolean nodeAlive = node != null && node.getHeartbeatAt() != null
                    && node.getHeartbeatAt().isAfter(nodeDeadline);
            if (nodeAlive) {
                // 节点还在心跳 —— 任务是真的在跑（只是慢），不能抢
                continue;
            }

            int retried = task.getRetryCount() == null ? 0 : task.getRetryCount();
            if (retried >= maxRetry) {
                resultWriter.finish(task.getTaskId(), TaskStatus.FAILED, 0, null,
                        "节点 %s 掉线，且已重试 %d 次仍未完成".formatted(task.getNodeName(), retried),
                        null, null, List.of());
                log.warn("任务 {} 重试 {} 次后判失败", task.getCaseCode(), retried);
            } else {
                // ⚠️ 用 UpdateWrapper 显式 set null：MyBatis-Plus 的 updateById 会**跳过 null 字段**，
                //    直接 setNodeName(null) 是不会生效的 —— 任务重投后还挂着旧节点名，
                //    看板上会显示它属于一个已经掉线的节点。
                taskMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<ExecTask>()
                        .eq("task_id", task.getTaskId())
                        .set("status", TaskStatus.PENDING.code())
                        .set("node_name", null)
                        .set("started_at", null)
                        .set("retry_count", retried + 1));
                requeue.add(task.getTaskId());
                log.warn("任务 {} 所在节点 {} 已掉线，放回队列（第 {} 次）",
                        task.getCaseCode(), task.getNodeName(), retried + 1);
            }
        }
        return requeue;
    }
}
