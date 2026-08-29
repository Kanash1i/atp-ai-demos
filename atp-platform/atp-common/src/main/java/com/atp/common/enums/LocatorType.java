package com.atp.common.enums;

/**
 * 定位器类型。住在 step_json 里，存字符串。
 *
 * <p>STD-003 的优先级：{@code data-testid} > {@code name} > {@code class} > 文本，
 * 所以新案例应该优先用 {@link #CSS} 配 {@code [data-testid=...]}。
 * 存量案例里 XPath 居多，其中还有刻意留下的绝对路径（违反 STD-001）。
 */
public enum LocatorType {

    XPATH,
    CSS,
    ID,
    NAME,
    LINK_TEXT
}
