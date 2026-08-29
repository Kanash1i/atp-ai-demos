package com.atp.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 执行批次的触发来源。
 *
 * <p>⭐ 两条 AI 赋能路线在这一列上分叉：人工派发与 agent 派发跑的是**同一套执行器**，
 * 看板可以直接按它分组，当场对比两条路线跑出来的通过率。
 */
public enum TriggerSource implements CodedEnum {

    MANUAL((short) 1),
    AGENT((short) 2),
    SCHEDULED((short) 3);

    @EnumValue
    private final short code;

    TriggerSource(short code) {
        this.code = code;
    }

    @Override
    public short code() {
        return code;
    }
}
