package com.atp.common.enums;

/**
 * 步骤动作。**共享契约** —— 见 {@code 00-SHARED-CONTEXT.md} §1.3，两条路线必须一致。
 *
 * <p>每个动作带着「哪些字段必填」的元信息。这不是文档注释，是校验器真正读的规则：
 * {@code OPEN_URL} 没有定位器、{@code CLICK} 没有输入数据、只有 {@code ASSERT_TEXT} 有期望值。
 * 把它放在枚举上，校验器就不需要一长串 switch。
 */
public enum ActionType {

    OPEN_URL(false, true, false),
    CLICK(true, false, false),
    INPUT(true, true, false),
    SELECT(true, true, false),
    ASSERT_TEXT(true, false, true),
    ASSERT_VISIBLE(true, false, false),
    ASSERT_NOT_EXIST(true, false, false),
    WAIT_FOR(true, false, false),
    SCROLL_TO(true, false, false),
    SWITCH_FRAME(true, false, false),
    SWITCH_WINDOW(false, true, false),
    UPLOAD(true, true, false),

    /**
     * ⚠️ **STD-004 全面禁止**，仅历史案例中存在。
     *
     * <p>执行器仍然实现它（存量案例里有 3 条在用），但校验器对新案例一律以 ERROR 拦下。
     * 这个反差本身就是演示点：规则是硬的，历史是软的。
     */
    SLEEP(false, true, false);

    private final boolean requiresLocator;
    private final boolean requiresInput;
    private final boolean requiresExpected;

    ActionType(boolean requiresLocator, boolean requiresInput, boolean requiresExpected) {
        this.requiresLocator = requiresLocator;
        this.requiresInput = requiresInput;
        this.requiresExpected = requiresExpected;
    }

    public boolean requiresLocator() {
        return requiresLocator;
    }

    public boolean requiresInput() {
        return requiresInput;
    }

    public boolean requiresExpected() {
        return requiresExpected;
    }

    /** 断言类动作。STD-006 要求它们的 wait_strategy 必须是 VISIBLE；STD-008 要求每条案例至少有一个 */
    public boolean isAssertion() {
        return name().startsWith("ASSERT_");
    }

    /** 规范是否禁止使用 */
    public boolean isBanned() {
        return this == SLEEP;
    }
}
