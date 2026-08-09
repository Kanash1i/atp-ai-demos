package dev.kanashi.atp.mcp.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.kanashi.atp.mcp.domain.Diagnostic;

import java.util.List;

/**
 * {@code atp_lint_locator} 的返回体。
 *
 * @param locatorType 实际用于检查的定位方式；请求方未指定时为推断结果，推断不出为 null
 * @param compliant   是否无 ERROR 级问题。<b>注意 WARN 与 INFO 不影响这个值</b> ——
 *                    它回答的是"能不能用"，不是"写得好不好"
 */
public record LintResult(
        @JsonProperty("locator_value") String locatorValue,
        @JsonProperty("locator_type") String locatorType,
        @JsonProperty("locator_type_inferred") boolean locatorTypeInferred,
        @JsonProperty("compliant") boolean compliant,
        @JsonProperty("findings") List<Diagnostic> findings,
        @JsonProperty("note") String note) {
}
