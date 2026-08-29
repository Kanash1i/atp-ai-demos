package com.atp.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

/** 执行批次状态。 */
public enum RunStatus implements CodedEnum {

    PENDING((short) 1),
    RUNNING((short) 2),
    DONE((short) 3),
    ABORTED((short) 4);

    @EnumValue
    private final short code;

    RunStatus(short code) {
        this.code = code;
    }

    @Override
    public short code() {
        return code;
    }
}
