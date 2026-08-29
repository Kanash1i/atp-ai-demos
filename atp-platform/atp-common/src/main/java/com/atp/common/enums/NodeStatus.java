package com.atp.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 执行节点状态。
 *
 * <p>⚠️ 判定「在线」看 {@code heartbeat_at}，不看这一列 ——
 * 执行节点是独立进程，进程崩了不会回来把自己改成 OFFLINE。
 */
public enum NodeStatus implements CodedEnum {

    IDLE((short) 1),
    BUSY((short) 2),
    OFFLINE((short) 3);

    @EnumValue
    private final short code;

    NodeStatus(short code) {
        this.code = code;
    }

    @Override
    public short code() {
        return code;
    }
}
