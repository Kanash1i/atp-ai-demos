package com.atp.platform.entity;

import com.atp.common.enums.NodeStatus;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 执行节点 —— 前端顶栏那个「ENGINE 6/8」。
 *
 * <p>⚠️ 判定在线看 {@link #heartbeatAt}，不看 {@link #status}：
 * 节点是独立进程，进程崩了不会回来把自己改成 OFFLINE。
 */
@Data
@TableName("exec_node")
public class ExecNode {

    @TableId
    private String nodeId;
    private String nodeName;
    private NodeStatus status;
    private Integer capacity;
    private String currentTaskId;
    private OffsetDateTime heartbeatAt;
    private OffsetDateTime registeredAt;
}
