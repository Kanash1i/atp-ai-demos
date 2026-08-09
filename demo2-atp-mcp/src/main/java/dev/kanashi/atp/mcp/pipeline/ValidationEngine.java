package dev.kanashi.atp.mcp.pipeline;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import dev.kanashi.atp.mcp.domain.Action;
import dev.kanashi.atp.mcp.domain.Diagnostic;
import dev.kanashi.atp.mcp.domain.FieldRequirement;
import dev.kanashi.atp.mcp.domain.LocatorType;
import dev.kanashi.atp.mcp.lint.LocatorLinter;
import dev.kanashi.atp.mcp.profile.PlatformProfile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * <b>L4 校验</b> —— 本服务的守门人，也是安全不变式的执行者。
 * <p>
 * <b>输入是 JSON 而不是领域对象，这是刻意的。</b> 它保证了两件事：
 * <ol>
 *   <li>{@code atp_validate_case} 校验的东西，和 normalize 最终输出的东西，
 *       是<b>同一份字节</b> —— 契约测试（normalize 的每条 ACCEPTED 都能通过 validate）
 *       才有意义。若这里校验领域对象、而输出的是序列化结果，两者之间就留了一道缝。</li>
 *   <li>平台方可以拿任意来源的 JSON 来校验，不必先构造我们的对象。</li>
 * </ol>
 *
 * <h2>五类校验，缺一不可</h2>
 * <ol>
 *   <li><b>JSON Schema</b>：类型 / 枚举 / 长度 / 必填 / 范围。<b>永不跳过</b></li>
 *   <li><b>外键</b>：module_id 必须在字典中真实存在 —— schema 拦不住编造的 M009</li>
 *   <li><b>Action 契约</b>：哪个 action 必须带 locator / input_data / expected</li>
 *   <li><b>跨步骤约束</b>：seq 连续、至少一个断言 —— schema 表达不了</li>
 *   <li><b>定位器规范</b>：STD-001/002/003</li>
 * </ol>
 */
@Component
public class ValidationEngine {

    private final PlatformProfile profile;
    private final LocatorLinter linter;
    private final Schema schema;

    public ValidationEngine(PlatformProfile profile, LocatorLinter linter) {
        this.profile = profile;
        this.linter = linter;
        // 启动期编译 schema：schema 本身写错了要立刻炸，
        // 而不是等到第一条案例进来时才发现校验器根本没生效。
        this.schema = SchemaRegistry
                .withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(profile.targetSchema());
    }

    public ValidationReport validate(JsonNode caseJson) {
        List<Diagnostic> diagnostics = new ArrayList<>();

        if (caseJson == null || !caseJson.isObject()) {
            diagnostics.add(Diagnostic.error(DiagnosticCodes.ENVELOPE_NOT_OBJECT, null,
                    "待校验的内容不是一个 JSON 对象。", null));
            return ValidationReport.from(diagnostics);
        }

        validateAgainstSchema(caseJson, diagnostics);
        validateForeignKeys(caseJson, diagnostics);
        validateSteps(caseJson, diagnostics);

        return ValidationReport.from(diagnostics);
    }

    // ── 1. JSON Schema ──────────────────────────────────────────────────────────

    private void validateAgainstSchema(JsonNode caseJson, List<Diagnostic> diagnostics) {
        // ⚠️ 用全限定名：com.networknt.schema.Error 会遮蔽 java.lang.Error，
        // import 进来虽然能编译，但读代码的人看到 catch/throw 附近的 Error 会瞬间误解。
        Set<com.networknt.schema.Error> errors = new LinkedHashSet<>(schema.validate(caseJson));
        for (com.networknt.schema.Error error : errors) {
            diagnostics.add(Diagnostic.error(DiagnosticCodes.SCHEMA_VIOLATION,
                    schemaErrorPath(error), error.getMessage(), null));
        }
    }

    /**
     * 给 schema 诊断算一个尽量精确的字段路径。
     * <p>
     * 必填缺失类的错误，instanceLocation 指向的是<b>父对象</b>（根就是 {@code $}），
     * 缺失的字段名只在 {@code getProperty()} 里。只取 instanceLocation 的话，
     * 一堆诊断的 path 全是 null，agent 拿到"某处缺了必填字段"根本没法定位去改。
     */
    private static String schemaErrorPath(com.networknt.schema.Error error) {
        String location = normalizePath(error.getInstanceLocation().toString());
        String property = error.getProperty();
        if (property == null || property.isBlank()) {
            return location;
        }
        return location == null ? property : location + "." + property;
    }

    /** networknt 的路径是 {@code $.steps[0].action} 形式，去掉 {@code $.} 前缀以对齐我们其它诊断。 */
    private static String normalizePath(String instanceLocation) {
        if (instanceLocation == null || instanceLocation.isBlank() || "$".equals(instanceLocation)) {
            return null;
        }
        return instanceLocation.startsWith("$.")
                ? instanceLocation.substring(2)
                : instanceLocation;
    }

    // ── 2. 外键 ─────────────────────────────────────────────────────────────────

    /**
     * ⭐ 防模型编造的关键一条。
     * <p>
     * {@code "M009"} 能通过 schema（是字符串、长度也对），格式看起来也完全合理，
     * 入库时若没有外键约束就会悄悄进去。执行器读到它找不到模块配置，
     * 报出的错误与真正的原因（几天前模型编了一个 ID）已经隔了十万八千里。
     */
    private void validateForeignKeys(JsonNode caseJson, List<Diagnostic> diagnostics) {
        JsonNode moduleId = caseJson.path("module_id");
        if (!moduleId.isString()) {
            return;   // 缺失或类型不对，schema 已经报过，不重复
        }
        String value = moduleId.asString();
        if (profile.isKnownModuleId(value)) {
            return;
        }
        diagnostics.add(Diagnostic.error(DiagnosticCodes.FK_MODULE_NOT_FOUND, "module_id",
                "module_id '" + value + "' 不在模块字典中。请调用 atp_list_modules 获取全集后重选；"
              + "本服务不会替你挑一个最接近的模块。", null));
    }

    // ── 3~5. 步骤级校验 ─────────────────────────────────────────────────────────

    private void validateSteps(JsonNode caseJson, List<Diagnostic> diagnostics) {
        JsonNode steps = caseJson.path("steps");
        if (!steps.isArray()) {
            return;   // schema 已经报过
        }

        boolean hasAssertion = false;
        List<Integer> sequences = new ArrayList<>();

        for (int i = 0; i < steps.size(); i++) {
            JsonNode step = steps.get(i);
            String path = "steps[" + i + "]";
            if (!step.isObject()) {
                continue;
            }

            if (step.path("seq").isIntegralNumber()) {
                sequences.add(step.path("seq").asInt());
            }

            Optional<Action> action = strictAction(step.path("action"));
            if (action.isEmpty()) {
                continue;   // action 非法，schema 的 enum 已经报过；契约无从谈起
            }
            Action a = action.get();
            hasAssertion |= a.isAssertion();

            if (a.isForbiddenByStandard()) {
                diagnostics.add(Diagnostic.error(DiagnosticCodes.STD_004_SLEEP_FORBIDDEN,
                        path + ".action",
                        "禁止使用 SLEEP 硬等待。请改用具体的 action 并通过 wait_strategy "
                      + "声明等待条件（如 WAIT_FOR + VISIBLE）—— 硬等待既拖慢用例，"
                      + "又在慢环境下依然会偶发失败。",
                        "STD-004"));
            }

            checkContract(step, a, path, diagnostics);
            checkLocator(step, path, diagnostics);
        }

        checkSeqContinuity(sequences, diagnostics);

        if (steps.size() > 0 && !hasAssertion) {
            diagnostics.add(Diagnostic.error(DiagnosticCodes.STD_008_NO_ASSERTION, "steps",
                    "案例中没有任何断言步骤（ASSERT_TEXT / ASSERT_VISIBLE / ASSERT_NOT_EXIST）。"
                  + "没有断言的用例无论如何都会“通过”，不构成测试。",
                    "STD-008"));
        }
    }

    /** Action 与三个字段的契约，数据来自 {@link Action} 枚举 —— 与 L1 的填充读同一份声明。 */
    private void checkContract(JsonNode step, Action action, String path,
                               List<Diagnostic> diagnostics) {
        checkField(step, path, "locator_value", action.locator(), action,
                DiagnosticCodes.CONTRACT_LOCATOR_REQUIRED, diagnostics);
        checkField(step, path, "input_data", action.inputData(), action,
                DiagnosticCodes.CONTRACT_INPUT_DATA_REQUIRED, diagnostics);
        checkField(step, path, "expected", action.expected(), action,
                DiagnosticCodes.CONTRACT_EXPECTED_REQUIRED, diagnostics);
    }

    private void checkField(JsonNode step, String path, String field,
                            FieldRequirement requirement, Action action,
                            String missingCode, List<Diagnostic> diagnostics) {
        JsonNode node = step.path(field);
        boolean present = node.isString() && !node.asString().isBlank();

        if (requirement == FieldRequirement.REQUIRED && !present) {
            diagnostics.add(Diagnostic.error(missingCode, path + "." + field,
                    action + " 要求提供 " + field + "。", null));
        } else if (requirement == FieldRequirement.FORBIDDEN && present) {
            // 判 WARN 而非 ERROR：多余字段不会让执行器崩，但它通常意味着上游误解了 action 语义
            // —— 给 CLICK 填了 input_data，往往说明它其实想要的是 INPUT。
            diagnostics.add(Diagnostic.warn(DiagnosticCodes.CONTRACT_FIELD_FORBIDDEN,
                    path + "." + field,
                    action + " 用不到 " + field + "，该值将被执行器忽略。"
                  + "若你本意是输入或断言，请确认 action 是否选错。", null));
        }
    }

    private void checkLocator(JsonNode step, String path, List<Diagnostic> diagnostics) {
        JsonNode value = step.path("locator_value");
        if (!value.isString() || value.asString().isBlank()) {
            return;
        }
        LocatorType type = strictEnum(LocatorType.class, step.path("locator_type")).orElse(null);
        diagnostics.addAll(linter.lint(type, value.asString(), path + ".locator_value"));
    }

    /**
     * seq 必须是 1..n 连续无跳号。
     * <p>
     * 执行器按 seq 排序执行，跳号本身不致命，但<b>跳号往往是"漏了一步"的痕迹</b> ——
     * 编写者删掉了第 3 步却没重排，而被删的可能正是一次关键的等待或断言。
     */
    private void checkSeqContinuity(List<Integer> sequences, List<Diagnostic> diagnostics) {
        for (int i = 0; i < sequences.size(); i++) {
            int expected = i + 1;
            if (sequences.get(i) != expected) {
                diagnostics.add(Diagnostic.error(DiagnosticCodes.SEQ_NOT_CONTIGUOUS,
                        "steps[" + i + "].seq",
                        "seq 必须从 1 开始连续无跳号，此处应为 " + expected
                      + "，实际为 " + sequences.get(i) + "。", null));
                return;   // 报第一处即可，逐条报出来只会淹没其它诊断
            }
        }
    }

    // ── 严格枚举解析 ────────────────────────────────────────────────────────────

    /**
     * L4 只接受标准枚举名，<b>不做任何同义词归一</b>。
     * <p>
     * 归一是 L1 的职责。守门这一步再去做善意解释，等于承认"输出可以不规范"，
     * 那 normalize 与 validate 就会对同一份数据给出不同结论。
     */
    private static Optional<Action> strictAction(JsonNode node) {
        return strictEnum(Action.class, node);
    }

    private static <E extends Enum<E>> Optional<E> strictEnum(Class<E> type, JsonNode node) {
        if (!node.isString()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Enum.valueOf(type, node.asString()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
