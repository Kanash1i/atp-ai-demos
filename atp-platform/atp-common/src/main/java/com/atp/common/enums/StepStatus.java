package com.atp.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

/** 步骤级结果。 */
public enum StepStatus implements CodedEnum {

    PASSED((short) 1),
    FAILED((short) 2),
    SKIPPED((short) 3);

    @EnumValue
    private final short code;

    StepStatus(short code) {
        this.code = code;
    }

    @Override
    public short code() {
        return code;
    }
}
