package dev.kanashi.atp.mcp.pipeline;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.kanashi.atp.mcp.domain.Diagnostic;
import dev.kanashi.atp.mcp.domain.NormalizationStatus;
import dev.kanashi.atp.mcp.domain.Severity;

import java.util.List;

/**
 * L4 的校验结论。
 * <p>
 * status <b>完全由诊断推导</b>，不接受外部指定 —— 这正是安全不变式的实现方式：
 * 只要存在 ERROR 就一定是 REJECTED，没有任何代码路径能绕过它给出 ACCEPTED。
 */
public record ValidationReport(
        @JsonProperty("status") NormalizationStatus status,
        @JsonProperty("diagnostics") List<Diagnostic> diagnostics,
        @JsonProperty("error_count") long errorCount,
        @JsonProperty("warn_count") long warnCount,
        @JsonProperty("info_count") long infoCount) {

    public static ValidationReport from(List<Diagnostic> diagnostics) {
        long errors = count(diagnostics, Severity.ERROR);
        long warns = count(diagnostics, Severity.WARN);
        long infos = count(diagnostics, Severity.INFO);

        NormalizationStatus status;
        if (errors > 0) {
            status = NormalizationStatus.REJECTED;
        } else if (warns > 0) {
            status = NormalizationStatus.ACCEPTED_WITH_WARNINGS;
        } else {
            status = NormalizationStatus.ACCEPTED;
        }
        return new ValidationReport(status, List.copyOf(diagnostics), errors, warns, infos);
    }

    private static long count(List<Diagnostic> diagnostics, Severity severity) {
        return diagnostics.stream().filter(d -> d.severity() == severity).count();
    }

    public boolean rejected() {
        return status == NormalizationStatus.REJECTED;
    }
}
