package com.atp.common.enums;

/**
 * 等待策略。
 *
 * <p>⚠️ 这个枚举**存字符串不存码** —— 它住在 {@code tc_step.step_json} 里，
 * 那是给人看也给执行器读的 JSON，存数字会让整份步骤失去可读性。
 * 只有关系列才存码。
 *
 * <p>执行器把它翻译成 Playwright 的等待条件，见 {@code atp-runner}。
 */
public enum WaitStrategy {

    /** 不等 */
    NONE,
    /** 元素挂上 DOM 即可 */
    PRESENCE,
    /** 元素可见 */
    VISIBLE,
    /**
     * 可见且可点击。
     *
     * <p>STD-005 规定 CLICK 必须用它：元素在 DOM 里存在不代表能点 ——
     * 遮罩层还没消失、动画还在跑的时候点击会被吃掉，只判可见性不够。
     */
    CLICKABLE
}
