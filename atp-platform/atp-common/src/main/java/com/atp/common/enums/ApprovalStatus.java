package com.atp.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

/** 审批状态。 */
public enum ApprovalStatus implements CodedEnum {

    PENDING((short) 1),
    APPROVED((short) 2),
    REJECTED((short) 3),
    /** 挂起：条件不具备（如数据集评估还没跑），既不批也不退 */
    HOLD((short) 4);

    @EnumValue
    private final short code;

    ApprovalStatus(short code) {
        this.code = code;
    }

    @Override
    public short code() {
        return code;
    }
}
