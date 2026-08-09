package dev.kanashi.atp.mcp.domain;

/**
 * 单个字段的来源说明。
 *
 * @param source     必填
 * @param rule       {@link ProvenanceSource#RULE} 时的规范编号，如 {@code STD-005}
 * @param confidence {@link ProvenanceSource#MODEL} 时的置信度 0..1
 * @param reason     {@link ProvenanceSource#MODEL} 时模型给出的推断理由，供人工复核时判断
 */
@com.fasterxml.jackson.annotation.JsonInclude(
        com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record FieldProvenance(
        @com.fasterxml.jackson.annotation.JsonProperty("source") ProvenanceSource source,
        @com.fasterxml.jackson.annotation.JsonProperty("rule") String rule,
        @com.fasterxml.jackson.annotation.JsonProperty("confidence") Double confidence,
        @com.fasterxml.jackson.annotation.JsonProperty("reason") String reason) {

    public static FieldProvenance fromInput() {
        return new FieldProvenance(ProvenanceSource.INPUT, null, null, null);
    }

    public static FieldProvenance fromRule(String standardRef) {
        return new FieldProvenance(ProvenanceSource.RULE, standardRef, null, null);
    }

    public static FieldProvenance fromDefault() {
        return new FieldProvenance(ProvenanceSource.DEFAULT, null, null, null);
    }

    public static FieldProvenance fromModel(double confidence, String reason) {
        return new FieldProvenance(ProvenanceSource.MODEL, null, confidence, reason);
    }
}
