package dev.kanashi.atp.mcp.pipeline;

import dev.kanashi.atp.mcp.domain.Diagnostic;
import dev.kanashi.atp.mcp.domain.FieldProvenance;
import dev.kanashi.atp.mcp.domain.NormalizedCase;

import java.util.List;
import java.util.Map;

/**
 * L1 的产物：已按规则填充的案例，<b>字段可能仍为 null</b>（那些就是 L2 要算的缺口）。
 *
 * @param caseData    部分填充的案例；null 字段表示规则无法确定其值
 * @param provenance  字段路径 → 来源。路径形如 {@code title} / {@code steps[0].wait_strategy}
 * @param diagnostics L1 期间产生的诊断
 *
 * <h2>为什么 L1 就开始记 provenance</h2>
 * provenance 的完整输出是 M4 的事，但<b>产生它的时机只能是这里</b> ——
 * 只有正在填充字段的那一行代码知道这个值是请求方给的、规则推的、还是 schema 默认的。
 * 等到 L5 组装时再回头推断来源，就只能靠猜（"它等于默认值，所以大概是默认来的？"），
 * 而那恰恰是 provenance 想要消灭的东西。
 */
public record MappedCase(
        NormalizedCase caseData,
        Map<String, FieldProvenance> provenance,
        List<Diagnostic> diagnostics) {
}
