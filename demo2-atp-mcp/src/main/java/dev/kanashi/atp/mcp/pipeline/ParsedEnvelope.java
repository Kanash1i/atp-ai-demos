package dev.kanashi.atp.mcp.pipeline;

import dev.kanashi.atp.mcp.domain.Diagnostic;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * L0 的产物：字段名已归一、但值尚未归一化的中间形态。
 * <p>
 * 刻意<b>不</b>在这一层就转成 {@code NormalizedCase} —— L0 只负责"把话听懂"，
 * 值的合法性判断是 L1/L4 的事。过早转成强类型会逼着 L0 去做类型转换，
 * 而类型转换失败时它没有足够上下文给出好的诊断。
 *
 * @param caseFields  标准字段名 → 原始值
 * @param steps       每个步骤的（标准字段名 → 原始值）
 * @param diagnostics L0 期间产生的诊断（未识别字段、重复字段等）
 * @param fatal       输入根本无法解析（不是 JSON 对象），后续层无需继续
 */
public record ParsedEnvelope(
        Map<String, JsonNode> caseFields,
        List<Map<String, JsonNode>> steps,
        List<Diagnostic> diagnostics,
        boolean fatal) {

    public static ParsedEnvelope failed(List<Diagnostic> diagnostics) {
        return new ParsedEnvelope(Map.of(), List.of(), List.copyOf(diagnostics), true);
    }
}
