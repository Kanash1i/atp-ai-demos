package com.atp.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

/** 案例状态。码值与 Go 侧 {@code CaseStatus} 一致。 */
public enum CaseStatus implements CodedEnum {

    /** 已写好、尚未启用。执行器与列表页认这个状态，也是 commit 的落地目标 */
    DRAFT((short) 1),
    ACTIVE((short) 2),
    DEPRECATED((short) 3),
    /**
     * AI 编写中。
     *
     * <p>⚠️ 刻意不复用 {@link #DRAFT} —— 编写中的行内容还是空的，
     * 混进 DRAFT 会被既有流程当成可用案例。
     */
    AI_DRAFT((short) 4);

    @EnumValue
    private final short code;

    CaseStatus(short code) {
        this.code = code;
    }

    @Override
    public short code() {
        return code;
    }

    public static CaseStatus of(short code) {
        for (CaseStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知的案例状态码 " + code);
    }
}
