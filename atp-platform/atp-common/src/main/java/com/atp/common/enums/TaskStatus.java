package com.atp.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

/** 单条案例的执行状态。前端「最近执行结果」表里显示的就是它。 */
public enum TaskStatus implements CodedEnum {

    PENDING((short) 1),
    RUNNING((short) 2),
    PASSED((short) 3),
    FAILED((short) 4),
    SKIPPED((short) 5),
    ABORTED((short) 6);

    @EnumValue
    private final short code;

    TaskStatus(short code) {
        this.code = code;
    }

    @Override
    public short code() {
        return code;
    }

    /** 已结束（不会再变）。执行器据此决定要不要回写 finished_at */
    public boolean isTerminal() {
        return this == PASSED || this == FAILED || this == SKIPPED || this == ABORTED;
    }
}
