package com.atp.platform.vo;

import java.util.List;

/**
 * 规范校验结果 —— 前端案例详情页的「规范校验」区块。
 *
 * @param passed       能不能保存。只有 ERROR 拦人
 * @param violatedCodes 违反的规范编号（STD chips）
 * @param findings     逐条明细，带步骤序号，前端可以高亮到具体那一行
 */
public record ValidationVO(
        boolean passed,
        long errorCount,
        long warnCount,
        long infoCount,
        List<String> violatedCodes,
        List<FindingVO> findings
) {

    /** @param seq 出问题的步骤序号；案例级问题（如编号不合规）为 null */
    public record FindingVO(String std, String severity, Integer seq, String message) {
    }

    /**
     * 从领域结果转成 VO。
     *
     * <p>⚠️ 不能把 {@code ValidationResult} 直接丢给 Jackson —— 它是 record，
     * Jackson 只序列化 record components（{@code findings}），
     * 而 {@code passed()} / {@code violatedCodes()} / {@code count()} 这些是额外方法，
     * **会被静默丢掉**。前端拿到的 JSON 里没有 passed 字段，却不会有任何报错。
     */
    public static ValidationVO from(com.atp.common.validation.ValidationResult r) {
        return new ValidationVO(
                r.passed(),
                r.count(com.atp.common.enums.StdCode.Severity.ERROR),
                r.count(com.atp.common.enums.StdCode.Severity.WARN),
                r.count(com.atp.common.enums.StdCode.Severity.INFO),
                r.violatedCodes(),
                r.findings().stream()
                        .map(f -> new FindingVO(f.std().display(), f.severity().name(), f.seq(), f.message()))
                        .toList());
    }
}
