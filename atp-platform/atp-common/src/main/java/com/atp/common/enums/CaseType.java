package com.atp.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

/** 执行平台。老平台本来就按 iOS / Android / Web 区分案例。 */
public enum CaseType implements CodedEnum {

    IOS((short) 1),
    ANDROID((short) 2),
    PC_WEB((short) 3);

    @EnumValue
    private final short code;

    CaseType(short code) {
        this.code = code;
    }

    @Override
    public short code() {
        return code;
    }
}
