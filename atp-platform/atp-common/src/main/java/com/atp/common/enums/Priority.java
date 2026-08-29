package com.atp.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

/** 优先级。⚠️ 码值就是 P 后面那个数字，所以 P0 的码是 0 而不是 1。 */
public enum Priority implements CodedEnum {

    P0((short) 0),
    P1((short) 1),
    P2((short) 2),
    P3((short) 3);

    @EnumValue
    private final short code;

    Priority(short code) {
        this.code = code;
    }

    @Override
    public short code() {
        return code;
    }
}
