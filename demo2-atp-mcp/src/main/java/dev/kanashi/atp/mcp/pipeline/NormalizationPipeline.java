package dev.kanashi.atp.mcp.pipeline;

import dev.kanashi.atp.mcp.domain.Diagnostic;
import dev.kanashi.atp.mcp.domain.FieldProvenance;
import dev.kanashi.atp.mcp.domain.NormalizedCase;
import dev.kanashi.atp.mcp.domain.ProvenanceSource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 流水线编排：L0 → L1 → L2 →（L3）→ L4 → L5。
 * <p>
 * 本类<b>不认识 ATP</b>，平台知识全在 {@code PlatformProfile} 里；
 * 也<b>不认识 MCP</b>，tool 只是它上面薄薄的一层。
 *
 * <h2>M2 阶段的状态</h2>
 * L3（模型补全）尚未接入。所以当前的行为是：
 * <ul>
 *   <li>无缺口 → 走完全程，<b>模型调用 0 次</b>，这是终态而非临时方案</li>
 *   <li>有 MODEL 类缺口 → REJECTED，并标注 {@code GAP_COMPLETION_UNAVAILABLE}</li>
 *   <li>有 REQUESTER 类缺口 → REJECTED，<b>这一条在 M3 之后依然如此</b> ——
 *       那些字段本来就不该由模型补</li>
 * </ul>
 * 中间那条会在 M3 变成"调模型填空"，而第一条和第三条是最终形态。
 */
@Component
public class NormalizationPipeline {

    private final EnvelopeParser envelopeParser;
    private final RuleMapper ruleMapper;
    private final GapAnalyzer gapAnalyzer;
    private final ValidationEngine validationEngine;
    private final ObjectMapper objectMapper;

    public NormalizationPipeline(EnvelopeParser envelopeParser,
                                 RuleMapper ruleMapper,
                                 GapAnalyzer gapAnalyzer,
                                 ValidationEngine validationEngine,
                                 ObjectMapper objectMapper) {
        this.envelopeParser = envelopeParser;
        this.ruleMapper = ruleMapper;
        this.gapAnalyzer = gapAnalyzer;
        this.validationEngine = validationEngine;
        this.objectMapper = objectMapper;
    }

    public NormalizationResult normalize(JsonNode input) {
        // ── L0 输入规整 ──────────────────────────────────────────────────────
        ParsedEnvelope envelope = envelopeParser.parse(input);
        if (envelope.fatal()) {
            ValidationReport report = ValidationReport.from(envelope.diagnostics());
            return new NormalizationResult(report.status(), null, report.diagnostics(),
                    Map.of(), List.of(), 0, true, List.of(),
                    "输入无法解析为测试案例，规范化未开始。");
        }

        // ── L1 确定性映射 ────────────────────────────────────────────────────
        MappedCase mapped = ruleMapper.map(envelope);
        NormalizedCase caseData = mapped.caseData();

        // ── L2 缺口分析 ──────────────────────────────────────────────────────
        GapAnalysis gapAnalysis = gapAnalyzer.analyze(caseData);

        List<Diagnostic> diagnostics = new ArrayList<>(mapped.diagnostics());
        int modelCalls = 0;

        // 只能由请求方补的缺口 —— 模型补即为编造，直接拒绝。
        // 这不是 M2 的临时行为，M3 接入模型后依然如此。
        for (FieldGap gap : gapAnalysis.requesterGaps()) {
            diagnostics.add(Diagnostic.error(DiagnosticCodes.GAP_MISSING_REQUIRED,
                    gap.path(), gap.hint(), null));
        }

        // ── L3 模型补全（M3 接入）───────────────────────────────────────────
        if (gapAnalysis.requiresModel()) {
            diagnostics.add(Diagnostic.error(DiagnosticCodes.GAP_COMPLETION_UNAVAILABLE, null,
                    "存在需要模型推断的字段（" + pathsOf(gapAnalysis) + "），"
                  + "但模型补全环节当前不可用。规则已完成的部分见 normalized_case，"
                  + "可由请求方补齐这些字段后重新提交。", null));
        }

        // ── L4 校验（永不跳过）──────────────────────────────────────────────
        // 校验的是序列化之后的 JSON，与 atp_validate_case 的输入完全同构 ——
        // 这样"normalize 说 ACCEPTED 但 validate 说 REJECTED"在结构上不可能发生。
        JsonNode asJson = objectMapper.valueToTree(caseData);
        ValidationReport validation = validationEngine.validate(asJson);
        diagnostics.addAll(validation.diagnostics());

        // ── L5 组装 ─────────────────────────────────────────────────────────
        ValidationReport finalReport = ValidationReport.from(diagnostics);
        return new NormalizationResult(
                finalReport.status(),
                caseData,
                finalReport.diagnostics(),
                mapped.provenance(),
                gapAnalysis.gaps(),
                modelCalls,
                gapAnalysis.zeroModelPath(),
                platformAssignedFields(mapped.provenance()),
                buildNote(finalReport, gapAnalysis, modelCalls));
    }

    private static String pathsOf(GapAnalysis analysis) {
        return String.join(", ", analysis.modelGaps().stream().map(FieldGap::path).toList());
    }

    /**
     * 哪些字段是本服务给了形状、但需要平台在入库时赋真值的。
     * <p>
     * 判据取自 provenance 而不是"值长得像占位符"—— 后者是在猜，前者是记录。
     */
    private static List<String> platformAssignedFields(Map<String, FieldProvenance> provenance) {
        FieldProvenance caseCode = provenance.get("case_code");
        if (caseCode != null && caseCode.source() == ProvenanceSource.RULE
                && "STD-007".equals(caseCode.rule())) {
            return List.of("case_code");
        }
        return List.of();
    }

    /**
     * ⚠️ 这里区分了两件容易混为一谈的事：<b>「没调模型」不等于「输入完整」</b>。
     * <p>
     * 一条缺了 locator_value 的案例同样是 model_calls=0，但那是因为
     * 那个字段本来就不该由模型补，而不是因为输入没问题。早先这里把两者混在一起，
     * 结果 REJECTED 的响应里写着"输入足够完整" —— 自相矛盾的说明比没有说明更糟。
     */
    private static String buildNote(ValidationReport report, GapAnalysis gaps, int modelCalls) {
        StringBuilder note = new StringBuilder();

        if (gaps.gaps().isEmpty()) {
            note.append("输入完整，全部字段均由规则确定，未调用任何模型（model_calls=")
                .append(modelCalls).append("）。");
        } else {
            if (gaps.requiresModel()) {
                note.append("存在需要模型推断的字段：")
                    .append(pathsOf(gaps)).append("。");
            } else {
                note.append("未调用任何模型（model_calls=").append(modelCalls).append("）。");
            }
            if (gaps.blockedByRequester()) {
                note.append(" 另有字段只能由请求方提供，本服务不会代为推断"
                          + "（详见 gaps 中 fillability=REQUESTER 的条目）。");
            }
        }

        if (report.rejected()) {
            note.append(" 案例被拒绝，但 normalized_case 中仍保留了规则已完成的部分，"
                      + "可在补齐后重新提交。");
        }
        return note.toString();
    }
}
