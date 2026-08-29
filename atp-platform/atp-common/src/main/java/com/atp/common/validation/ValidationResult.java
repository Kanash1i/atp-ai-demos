package com.atp.common.validation;

import com.atp.common.enums.StdCode;

import java.util.List;

/**
 * 校验结果。
 *
 * <p>前端案例详情页的「规范校验」区块直接渲染它：STD chips + 「ERROR 0 / WARN 0」那一行。
 */
public record ValidationResult(List<Finding> findings) {

    public static ValidationResult empty() {
        return new ValidationResult(List.of());
    }

    public List<Finding> of(StdCode.Severity severity) {
        return findings.stream().filter(f -> f.severity() == severity).toList();
    }

    public long count(StdCode.Severity severity) {
        return findings.stream().filter(f -> f.severity() == severity).count();
    }

    /**
     * 能不能保存。
     *
     * <p>⚠️ 只有 ERROR 拦人。WARN 和 INFO 是建议 —— 存量案例里有大量 WARN，
     * 如果 WARN 也拦，历史数据就一条都改不动了。
     */
    public boolean passed() {
        return count(StdCode.Severity.ERROR) == 0;
    }

    /** 违反的规范编号，去重后按枚举顺序。前端的 STD chips 用它 */
    public List<String> violatedCodes() {
        return findings.stream()
                .map(Finding::std)
                .distinct()
                .sorted()
                .map(StdCode::display)
                .toList();
    }
}
