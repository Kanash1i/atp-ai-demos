package dev.kanashi.atp.cli.model;

/**
 * 案例状态。<b>DB 里存 SMALLINT，语义由这里持有</b>（见 DECISIONS D-112）。
 *
 * <p>这样加一个新状态不需要任何 DDL —— 只是应用层多认一个码。
 * 代价是 {@code SELECT *} 出来是数字，靠 {@code COMMENT ON COLUMN} 补偿可读性。
 */
public enum CaseStatus {

    /** 案例已写好、尚未启用。执行器与列表页认这个状态。也是 commit 的落地目标。 */
    DRAFT(1),
    ACTIVE(2),
    DEPRECATED(3),
    /**
     * AI 编写中。<b>刻意不复用 {@link #DRAFT}</b> ——
     * 编写中的行内容还是空的，混进 DRAFT 会被既有流程当成可用案例。
     */
    AI_DRAFT(4);

    private final int code;

    CaseStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static CaseStatus fromCode(int code) {
        for (CaseStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知的 status 码: " + code);
    }
}
