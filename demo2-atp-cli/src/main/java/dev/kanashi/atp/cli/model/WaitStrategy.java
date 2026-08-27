package dev.kanashi.atp.cli.model;

/**
 * 步骤执行前的显式等待策略（tc_step.wait_strategy，NOT NULL）。
 * <p>
 * 它的存在本身就是 STD-004 的落地：禁止 {@code SLEEP} 硬等，
 * 必须声明"等什么条件成立"，由执行器去轮询。
 */
public enum WaitStrategy {

    /** 不等待。只适用于不操作页面元素的 action（OPEN_URL / SWITCH_WINDOW）。 */
    NONE,

    /** 等元素出现在 DOM 中即可，不要求可见。 */
    PRESENCE,

    /** 等元素可见（在 DOM 中且未被隐藏）。 */
    VISIBLE,

    /** 等元素可点击（可见 + 未被禁用 + 未被遮挡）。 */
    CLICKABLE
}
