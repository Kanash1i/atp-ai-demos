package dev.kanashi.atp.mcp.pipeline;

import dev.kanashi.atp.mcp.domain.Action;
import dev.kanashi.atp.mcp.domain.FieldRequirement;
import dev.kanashi.atp.mcp.domain.NormalizedCase;
import dev.kanashi.atp.mcp.domain.TestStep;
import dev.kanashi.atp.mcp.profile.PlatformProfile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>L2 缺口分析</b> —— 走完规则之后，还剩什么没填，以及<b>该找谁填</b>。
 * <p>
 * 这一层决定了 L3 的工作量。它输出的不是"整条案例 + 请你补全"，
 * 而是一份<b>精确到字段的填空题清单</b>，外加一份只含这些字段的子 schema。
 * 模型看不到也无法改动其它字段 —— 这是"模型只填空，不重写"在数据结构层面的保证，
 * 而不是靠 prompt 里写一句"请不要修改其他字段"。
 */
@Component
public class GapAnalyzer {

    private final PlatformProfile profile;
    private final ObjectMapper objectMapper;

    public GapAnalyzer(PlatformProfile profile, ObjectMapper objectMapper) {
        this.profile = profile;
        this.objectMapper = objectMapper;
    }

    public GapAnalysis analyze(NormalizedCase c) {
        List<FieldGap> gaps = new ArrayList<>();

        analyzeCaseLevel(c, gaps);
        analyzeSteps(c, gaps);

        return new GapAnalysis(List.copyOf(gaps), buildModelGapSchema(gaps));
    }

    private void analyzeCaseLevel(NormalizedCase c, List<FieldGap> gaps) {
        if (isBlank(c.title())) {
            gaps.add(new FieldGap("title", GapFillability.MODEL,
                    "根据步骤内容概括一句简明的用例标题，不超过 200 字符。"));
        }
        if (isBlank(c.moduleId())) {
            gaps.add(new FieldGap("module_id", GapFillability.MODEL,
                    "从模块字典中选出语义最匹配的一项，只能使用字典中真实存在的 module_id。"
                  + "字典可由 atp_list_modules 获取。"));
        }
        if (c.priority() == null) {
            gaps.add(new FieldGap("priority", GapFillability.MODEL,
                    "按业务重要性推断优先级 P0~P3。核心交易主流程通常 P0/P1，"
                  + "边缘校验通常 P2/P3。若无明确线索，请如实给出较低的 confidence。"));
        }
        if (isBlank(c.author())) {
            // ⚠️ 不给模型 —— 让它补 author 只会得到一个编造的人名，
            // 而这个字段将来是要用来追责和分派的。
            gaps.add(new FieldGap("author", GapFillability.REQUESTER,
                    "author 必须由请求方提供：它标识案例的责任人，任何推断出来的值都是假的。"));
        }
        if (isBlank(c.caseCode())) {
            gaps.add(new FieldGap("case_code", GapFillability.PLATFORM,
                    "case_code 需要 module_id 才能拼出形状，其 4 位序号须由平台分配。"));
        }
    }

    private void analyzeSteps(NormalizedCase c, List<FieldGap> gaps) {
        List<TestStep> steps = c.steps();
        if (steps == null || steps.isEmpty()) {
            gaps.add(new FieldGap("steps", GapFillability.REQUESTER,
                    "案例必须至少包含一个步骤，本服务不会凭空生成测试步骤。"));
            return;
        }

        for (int i = 0; i < steps.size(); i++) {
            TestStep step = steps.get(i);
            String base = "steps[" + i + "]";

            if (step.action() == null) {
                gaps.add(new FieldGap(base + ".action", GapFillability.REQUESTER,
                        "无法识别该步骤要执行的操作。请使用 atp_describe_schema 返回的 action 取值。"));
                continue;   // action 未知，下面的契约无从判断
            }
            Action action = step.action();

            requesterGapIfMissing(gaps, base + ".locator_value", action.locator(),
                    step.locatorValue(),
                    action + " 需要 locator_value。**本服务不会替你生成定位器** —— "
                  + "模型没有见过被测页面，编出来的选择器语法正确却指向不存在的元素，"
                  + "会一路通过校验直到执行时才报“元素未找到”。");

            requesterGapIfMissing(gaps, base + ".input_data", action.inputData(),
                    step.inputData(),
                    action + " 需要 input_data（如 URL、输入文本、文件路径）。"
                  + "这类值只有请求方知道，推断出来的都是假的。");

            requesterGapIfMissing(gaps, base + ".expected", action.expected(),
                    step.expected(),
                    action + " 需要 expected。断言的期望值必须由请求方给出 —— "
                  + "模型并不知道被测系统的正确输出是什么。");

            // locator_type 是少数可以放心交给模型的：答案就在 locator_value 里，
            // 只是形状规则覆盖不到 ID / NAME / LINK_TEXT 这三种。
            if (step.locatorType() == null && !isBlank(step.locatorValue())) {
                gaps.add(new FieldGap(base + ".locator_type", GapFillability.MODEL,
                        "判断 '" + step.locatorValue() + "' 属于哪种定位方式"
                      + "（XPATH / CSS / ID / NAME / LINK_TEXT）。只做判断，不要改写该表达式。"));
            }
        }
    }

    private void requesterGapIfMissing(List<FieldGap> gaps, String path,
                                       FieldRequirement requirement, String value, String hint) {
        if (requirement == FieldRequirement.REQUIRED && isBlank(value)) {
            gaps.add(new FieldGap(path, GapFillability.REQUESTER, hint));
        }
    }

    /**
     * 构造只含 MODEL 类缺口的子 schema，字段定义直接<b>从目标 schema 里摘</b>。
     * <p>
     * 不另写一份的理由和别处一样：两份定义迟早漂移。这里摘出来的片段
     * 与 L4 校验用的是同一份约束，所以模型按它填出来的值，L4 天然认得。
     * <p>
     * property 的 key 直接用字段路径（{@code steps[2].locator_type}）——
     * JSON 允许任意字符串作 key，这样 L3 拿到回答后可以原样按路径回填，
     * 不需要再维护一套路径与 key 的映射。
     */
    private JsonNode buildModelGapSchema(List<FieldGap> gaps) {
        List<FieldGap> modelGaps = gaps.stream().filter(FieldGap::modelFillable).toList();
        if (modelGaps.isEmpty()) {
            return null;
        }

        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");

        for (FieldGap gap : modelGaps) {
            JsonNode fieldSchema = lookupFieldSchema(gap.path());
            ObjectNode entry = properties.putObject(gap.path());
            if (fieldSchema != null && fieldSchema.isObject()) {
                entry.setAll((ObjectNode) fieldSchema.deepCopy());
            }
            entry.put("description", gap.hint());
            required.add(gap.path());
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    /** 按路径从目标 schema 中取出该字段的定义。 */
    private JsonNode lookupFieldSchema(String path) {
        JsonNode target = profile.targetSchema();
        int dot = path.indexOf('.');
        if (path.startsWith("steps[") && dot > 0) {
            String field = path.substring(dot + 1);
            return target.path("$defs").path("step").path("properties").path(field);
        }
        return target.path("properties").path(path);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
