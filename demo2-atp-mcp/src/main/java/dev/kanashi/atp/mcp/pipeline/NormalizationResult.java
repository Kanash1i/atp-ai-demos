package dev.kanashi.atp.mcp.pipeline;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.kanashi.atp.mcp.domain.Diagnostic;
import dev.kanashi.atp.mcp.domain.FieldProvenance;
import dev.kanashi.atp.mcp.domain.NormalizationStatus;
import dev.kanashi.atp.mcp.domain.NormalizedCase;

import java.util.List;
import java.util.Map;

/**
 * {@code atp_normalize_case} 的返回体（L5 组装结果）。
 *
 * @param normalizedCase 规范化结果。<b>即使 REJECTED 也会给出</b> ——
 *                       规则已经完成了大部分工作，全盘丢弃是浪费；
 *                       平台方可以选择存为草稿等人工补全
 * @param provenance     每个字段的来源，平台方据此决定信任级别
 * @param gaps           未能填上的空，以及各自该找谁补
 * @param modelCalls     ⭐ 本次实际调用模型的次数。<b>把"零模型路径"变成可观测的事实，
 *                       而不是一句宣传</b>
 * @param requiresPlatformAssignment 需要平台在入库时赋值的字段（如 case_code 的序号）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NormalizationResult(
        @JsonProperty("status") NormalizationStatus status,
        @JsonProperty("normalized_case") NormalizedCase normalizedCase,
        @JsonProperty("diagnostics") List<Diagnostic> diagnostics,
        @JsonProperty("provenance") Map<String, FieldProvenance> provenance,
        @JsonProperty("gaps") List<FieldGap> gaps,
        @JsonProperty("model_calls") int modelCalls,
        @JsonProperty("zero_model_path") boolean zeroModelPath,
        @JsonProperty("requires_platform_assignment") List<String> requiresPlatformAssignment,
        @JsonProperty("note") String note) {
}
