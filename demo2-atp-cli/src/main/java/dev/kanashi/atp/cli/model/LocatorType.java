package dev.kanashi.atp.cli.model;

/**
 * 元素定位方式（tc_step.locator_type）。
 * <p>
 * L1 会按 locator_value 的形状推断这个字段（{@code /} 或 {@code //} 开头 → XPATH，
 * {@code #} 或 {@code .} 开头 → CSS），推断不出来才交给模型。
 */
public enum LocatorType {
    XPATH,
    CSS,
    ID,
    NAME,
    LINK_TEXT
}
