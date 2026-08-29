package com.atp.runner.node;

import com.atp.common.enums.NodeStatus;
import com.atp.platform.entity.ExecNode;
import com.atp.platform.mapper.ExecNodeMapper;
import com.atp.runner.RunnerProperties;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 节点注册与心跳。
 *
 * <h3>⚠️ 为什么在线要靠心跳判定，不靠 status 列</h3>
 *
 * 节点是独立进程。进程被 kill、机器断电、Tailscale 断线时，
 * <b>它没有机会回来把自己改成 OFFLINE</b> —— status 会永远停在 IDLE 或 BUSY。
 * 所以看板判定在线看的是 {@code heartbeat_at} 是否够新（2 分钟内），
 * status 只表示「最后一次心跳时它在干什么」。
 *
 * <p>这也正是演示「kill 一个节点」时看板能反应过来的原因。
 */
@Slf4j
@Component
public class NodeRegistrar {

    @Autowired
    private ExecNodeMapper nodeMapper;
    @Autowired
    private RunnerProperties props;

    private volatile NodeStatus current = NodeStatus.IDLE;
    private volatile String currentTaskId;

    @PostConstruct
    public void register() {
        String name = props.getNodeName();
        ExecNode existing = nodeMapper.selectOne(
                new LambdaQueryWrapper<ExecNode>().eq(ExecNode::getNodeName, name));

        if (existing == null) {
            ExecNode node = new ExecNode();
            node.setNodeId(UUID.randomUUID().toString());
            node.setNodeName(name);
            node.setStatus(NodeStatus.IDLE);
            node.setCapacity(1);
            node.setHeartbeatAt(OffsetDateTime.now());
            node.setRegisteredAt(OffsetDateTime.now());
            nodeMapper.insert(node);
            log.info("节点 {} 已注册", name);
        } else {
            // 重启后复用同一行 —— 节点名是稳定标识，不该每次重启换个身份
            beat();
            log.info("节点 {} 已上线（复用既有注册）", name);
        }
    }

    @Scheduled(fixedDelayString = "${atp.runner.heartbeat-seconds:30}000")
    public void beat() {
        ExecNode update = new ExecNode();
        update.setStatus(current);
        update.setCurrentTaskId(currentTaskId);
        update.setHeartbeatAt(OffsetDateTime.now());
        nodeMapper.update(update, new LambdaQueryWrapper<ExecNode>()
                .eq(ExecNode::getNodeName, props.getNodeName()));
    }

    /** 开始跑一条任务 */
    public void busy(String taskId) {
        current = NodeStatus.BUSY;
        currentTaskId = taskId;
        beat();
    }

    /** 跑完了 */
    public void idle() {
        current = NodeStatus.IDLE;
        currentTaskId = null;
        beat();
    }
}
