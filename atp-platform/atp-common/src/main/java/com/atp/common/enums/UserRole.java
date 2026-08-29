package com.atp.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

/** 用户角色。REVIEWER 及以上才能在审批中心做决策。 */
public enum UserRole implements CodedEnum {

    QA_ENGINEER((short) 1),
    REVIEWER((short) 2),
    ADMIN((short) 3);

    @EnumValue
    private final short code;

    UserRole(short code) {
        this.code = code;
    }

    @Override
    public short code() {
        return code;
    }

    public boolean canApprove() {
        return this != QA_ENGINEER;
    }
}
