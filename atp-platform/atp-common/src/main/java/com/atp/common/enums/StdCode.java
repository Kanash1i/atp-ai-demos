package com.atp.common.enums;

/**
 * ATP 公司规范。**同一套规则有两处实现**：
 * 保守路线在 {@code demo2-atp-cli} 的校验器里，激进路线在本平台的 {@code StandardsValidator} 里。
 *
 * <p>⚠️ 两处必须同码同义。它们校验的是同一张表里的同一批数据 ——
 * 一边放行、另一边拦下，是最难解释的那类不一致。
 */
public enum StdCode {

    STD_001("STD-001", Severity.ERROR, "XPath 禁止使用绝对路径"),
    STD_002("STD-002", Severity.WARN, "XPath 禁止依赖自动生成的动态 id"),
    STD_003("STD-003", Severity.INFO, "定位器优先级：data-testid > name > class > 文本"),
    STD_004("STD-004", Severity.ERROR, "禁止 SLEEP，必须用 wait_strategy 显式等待"),
    STD_005("STD-005", Severity.ERROR, "CLICK 的 wait_strategy 必须是 CLICKABLE"),
    STD_006("STD-006", Severity.ERROR, "ASSERT_* 的 wait_strategy 必须是 VISIBLE"),
    STD_007("STD-007", Severity.ERROR, "case_code 必须符合 ATP-{MODULE}-{4位序号}"),
    STD_008("STD-008", Severity.ERROR, "每条案例至少 1 个断言步骤");

    /** 校验结果的三档。ERROR 会拦下保存，WARN 与 INFO 只提示 */
    public enum Severity {
        ERROR, WARN, INFO
    }

    private final String display;
    private final Severity severity;
    private final String description;

    StdCode(String display, Severity severity, String description) {
        this.display = display;
        this.severity = severity;
        this.description = description;
    }

    /** 展示用编号，形如 {@code STD-004} —— 枚举名用下划线是 Java 的限制，对外一律用这个 */
    public String display() {
        return display;
    }

    public Severity severity() {
        return severity;
    }

    public String description() {
        return description;
    }
}
