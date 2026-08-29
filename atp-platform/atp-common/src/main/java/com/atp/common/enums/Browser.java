package com.atp.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

/** 浏览器。tc_case 与 exec_run / exec_task 共用同一套码。 */
public enum Browser implements CodedEnum {

    CHROME((short) 1),
    FIREFOX((short) 2),
    EDGE((short) 3);

    @EnumValue
    private final short code;

    Browser(short code) {
        this.code = code;
    }

    @Override
    public short code() {
        return code;
    }
}
