package dev.kanashi.atp.mcp.pipeline;

import dev.kanashi.atp.mcp.domain.Diagnostic;
import dev.kanashi.atp.mcp.profile.AliasDictionary;
import dev.kanashi.atp.mcp.profile.PlatformProfile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
// ⚠️ Jackson 3 把 TextNode 改名为 StringNode（与 asText→asString 是同一次系统性改名）
import tools.jackson.databind.node.StringNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * <b>L0 输入规整</b> —— 把任意形状的 JSON 整理成字段名统一的中间形态。
 * <p>
 * 请求方是任意团队的任意 agent，我们无权要求它们先学会我们的字段名。
 * 这一层做的全部事情都是查表和搬运，<b>没有一处需要模型参与</b> ——
 * 这正是"能用规则做的绝不给模型"里最廉价、也最容易被忽略的一段：
 * 很多"AI 服务"会把整坨 JSON 连同 schema 一起丢给模型让它"转换成正确格式"，
 * 那不仅贵，而且模型会顺手改动它本不该碰的字段。
 * <p>
 * 本类<b>不认识 ATP</b>，所有平台知识都来自 {@link PlatformProfile#aliases()}。
 */
@Component
public class EnvelopeParser {

    /** 信封解包的最大层数，防御性上限，避免畸形输入导致的深递归。 */
    private static final int MAX_UNWRAP_DEPTH = 3;

    private final PlatformProfile profile;

    public EnvelopeParser(PlatformProfile profile) {
        this.profile = profile;
    }

    public ParsedEnvelope parse(JsonNode input) {
        List<Diagnostic> diagnostics = new ArrayList<>();

        JsonNode root = unwrap(input);
        if (root == null || !root.isObject()) {
            diagnostics.add(Diagnostic.error(DiagnosticCodes.ENVELOPE_NOT_OBJECT, null,
                    "输入不是一个 JSON 对象，无法作为测试案例解析。", null));
            return ParsedEnvelope.failed(diagnostics);
        }

        AliasDictionary aliases = profile.aliases();
        Map<String, JsonNode> caseFields = new LinkedHashMap<>();
        List<Map<String, JsonNode>> steps = new ArrayList<>();
        boolean stepsContainerSeen = false;

        for (Map.Entry<String, JsonNode> entry : root.properties()) {
            String rawName = entry.getKey();
            JsonNode value = entry.getValue();

            if (aliases.isStepsContainer(rawName)) {
                stepsContainerSeen = true;
                steps.addAll(parseSteps(value, aliases, diagnostics));
                continue;
            }

            Optional<String> canonical = aliases.canonicalCaseField(rawName);
            if (canonical.isPresent()) {
                putOnce(caseFields, canonical.get(), rawName, value, null, diagnostics);
            } else {
                diagnostics.add(Diagnostic.info(DiagnosticCodes.ENVELOPE_UNKNOWN_FIELD, rawName,
                        "字段 '" + rawName + "' 无法对应到任何已知案例字段，已忽略。"
                      + "若这是必填字段的笔误，后续会以必填缺失的形式报出。", null));
            }
        }

        if (!stepsContainerSeen) {
            diagnostics.add(Diagnostic.error(DiagnosticCodes.ENVELOPE_STEPS_MISSING, "steps",
                    "找不到步骤数组。可用的字段名包括 steps / actions / 操作步骤 / 手順 等。", null));
        }

        return new ParsedEnvelope(caseFields, steps, diagnostics, false);
    }

    /**
     * 剥掉外层信封，例如 {@code {"testCase": {...}}} 或 {@code {"data": {...}}}。
     * <p>
     * 判据是结构而非词表：<b>顶层只有一个字段、其值是对象、且这个字段名不是我们认识的任何东西</b>
     * —— 那它就只可能是个包装层。用结构判据而不是维护一份 {@code case/data/payload/案例/…}
     * 的词表，是因为词表永远列不全，而这个结构特征对所有包装形式都成立。
     */
    private JsonNode unwrap(JsonNode input) {
        JsonNode current = input;
        for (int depth = 0; depth < MAX_UNWRAP_DEPTH; depth++) {
            if (current == null || !current.isObject() || current.size() != 1) {
                return current;
            }
            Map.Entry<String, JsonNode> only = current.properties().iterator().next();
            String name = only.getKey();
            JsonNode value = only.getValue();

            boolean recognized = profile.aliases().canonicalCaseField(name).isPresent()
                    || profile.aliases().isStepsContainer(name);
            if (recognized || !value.isObject()) {
                return current;
            }
            current = value;
        }
        return current;
    }

    private List<Map<String, JsonNode>> parseSteps(JsonNode container,
                                                   AliasDictionary aliases,
                                                   List<Diagnostic> diagnostics) {
        List<Map<String, JsonNode>> parsed = new ArrayList<>();

        // 只有一个步骤时请求方常直接给对象而非数组，一并容忍
        List<JsonNode> elements = new ArrayList<>();
        if (container.isArray()) {
            container.forEach(elements::add);
        } else if (container.isObject()) {
            elements.add(container);
        } else {
            diagnostics.add(Diagnostic.error(DiagnosticCodes.ENVELOPE_STEP_NOT_OBJECT, "steps",
                    "步骤字段既不是数组也不是对象。", null));
            return parsed;
        }

        for (int i = 0; i < elements.size(); i++) {
            JsonNode element = elements.get(i);
            String path = "steps[" + i + "]";

            if (!element.isObject()) {
                diagnostics.add(Diagnostic.error(DiagnosticCodes.ENVELOPE_STEP_NOT_OBJECT, path,
                        "步骤必须是 JSON 对象。", null));
                continue;
            }
            parsed.add(parseStep(element, aliases, path, diagnostics));
        }
        return parsed;
    }

    private Map<String, JsonNode> parseStep(JsonNode element,
                                            AliasDictionary aliases,
                                            String path,
                                            List<Diagnostic> diagnostics) {
        Map<String, JsonNode> fields = new LinkedHashMap<>();

        for (Map.Entry<String, JsonNode> entry : element.properties()) {
            String rawName = entry.getKey();
            JsonNode value = entry.getValue();

            Optional<String> canonical = aliases.canonicalStepField(rawName);
            if (canonical.isPresent()) {
                putOnce(fields, canonical.get(), rawName, value, path, diagnostics);
                continue;
            }

            // 形如 {"xpath": "//div"} —— 字段名本身就说明了定位方式。
            // 这比看值的形状去推断可靠得多：这是调用方明说的，不是我们猜的。
            Optional<String> hint = aliases.locatorTypeHintFromFieldName(rawName);
            if (hint.isPresent()) {
                putOnce(fields, "locator_value", rawName, value, path, diagnostics);
                fields.putIfAbsent("locator_type", StringNode.valueOf(hint.get()));
                continue;
            }

            diagnostics.add(Diagnostic.info(DiagnosticCodes.ENVELOPE_UNKNOWN_FIELD,
                    path + "." + rawName,
                    "步骤字段 '" + rawName + "' 无法对应到任何已知字段，已忽略。", null));
        }
        return fields;
    }

    /**
     * 写入字段，并在同一个标准字段被多个别名重复提供时报出来。
     * <p>
     * 例如请求方同时给了 {@code title} 和 {@code 标题} 且内容不同 —— 静默取其中一个
     * 意味着结果取决于 JSON 的字段顺序，这种不确定性必须暴露给请求方，
     * 而不是由我们替它选一个。
     */
    private void putOnce(Map<String, JsonNode> target,
                         String canonical,
                         String rawName,
                         JsonNode value,
                         String pathPrefix,
                         List<Diagnostic> diagnostics) {
        JsonNode existing = target.get(canonical);
        if (existing != null && !existing.equals(value)) {
            String path = pathPrefix == null ? canonical : pathPrefix + "." + canonical;
            diagnostics.add(Diagnostic.warn(DiagnosticCodes.ENVELOPE_DUPLICATE_FIELD, path,
                    "字段 '" + canonical + "' 被多个别名重复提供且取值不同（本次来自 '" + rawName
                  + "'），已保留先出现的值。请只提供一个。", null));
            return;
        }
        target.put(canonical, value);
    }
}
