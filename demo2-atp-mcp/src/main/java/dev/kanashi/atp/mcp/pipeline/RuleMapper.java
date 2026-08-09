package dev.kanashi.atp.mcp.pipeline;

import dev.kanashi.atp.mcp.domain.Action;
import dev.kanashi.atp.mcp.domain.Browser;
import dev.kanashi.atp.mcp.domain.CaseStatus;
import dev.kanashi.atp.mcp.domain.Diagnostic;
import dev.kanashi.atp.mcp.domain.FieldProvenance;
import dev.kanashi.atp.mcp.domain.LocatorType;
import dev.kanashi.atp.mcp.domain.NormalizedCase;
import dev.kanashi.atp.mcp.domain.OnFailure;
import dev.kanashi.atp.mcp.domain.Priority;
import dev.kanashi.atp.mcp.domain.TestStep;
import dev.kanashi.atp.mcp.domain.WaitStrategy;
import dev.kanashi.atp.mcp.lint.LocatorLinter;
import dev.kanashi.atp.mcp.profile.ModuleEntry;
import dev.kanashi.atp.mcp.profile.PlatformProfile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * <b>L1 确定性映射</b> —— 把规则能算出唯一答案的字段全部算出来。
 * <p>
 * 这一层是「让模型少做事」的主战场：走完 L1 之后还是 null 的字段，
 * 才是 L2 要交给模型的填空题。L1 做得越扎实，模型的活儿越少、越便宜、越可控。
 * <p>
 * <b>三条自我约束</b>：
 * <ol>
 *   <li><b>不猜。</b> 推断不出来就留 null 交给 L2，绝不填一个"看起来合理"的值 ——
 *       规则猜错和模型猜错一样有害，而且更隐蔽（规则的错会稳定地重复出现）。</li>
 *   <li><b>不截断。</b> 超长字段原样保留并判 ERROR。截断能让 schema 校验通过，
 *       但那是把语义损坏藏了起来 —— 一条被截断的断言文本会让用例静默地测错东西。</li>
 *   <li><b>纠正要出声。</b> 请求方给的值与规范强制值冲突时纠正它，并产出 WARN。
 *       静默纠正等于让请求方永远学不会正确写法。</li>
 * </ol>
 */
@Component
public class RuleMapper {

    private final PlatformProfile profile;

    public RuleMapper(PlatformProfile profile) {
        this.profile = profile;
    }

    public MappedCase map(ParsedEnvelope envelope) {
        Ctx ctx = new Ctx();
        Map<String, JsonNode> f = envelope.caseFields();

        String title = stringField(f, "title", "title",
                NormalizedCase.MAX_TITLE_LENGTH, ctx);
        String author = stringField(f, "author", "author",
                NormalizedCase.MAX_AUTHOR_LENGTH, ctx);
        String precondition = stringField(f, "precondition", "precondition", null, ctx);

        String moduleId = resolveModuleId(f, ctx);
        Priority priority = enumField(f, "priority", Priority.class, "priority", null, ctx);
        CaseStatus status = enumField(f, "status", CaseStatus.class, "status",
                CaseStatus.DRAFT, ctx);
        Browser browser = enumField(f, "browser", Browser.class, "browser",
                Browser.CHROME, ctx);
        Integer timeoutSec = intField(f, "timeout_sec", "timeout_sec",
                NormalizedCase.DEFAULT_TIMEOUT_SEC,
                NormalizedCase.MIN_TIMEOUT_SEC, NormalizedCase.MAX_TIMEOUT_SEC, ctx);

        String caseCode = resolveCaseCode(f, moduleId, ctx);

        List<TestStep> steps = new ArrayList<>();
        List<Map<String, JsonNode>> rawSteps = envelope.steps();
        for (int i = 0; i < rawSteps.size(); i++) {
            steps.add(mapStep(rawSteps.get(i), i, ctx));
        }

        NormalizedCase mapped = new NormalizedCase(
                caseCode, title, moduleId, priority, author, precondition,
                status, browser, timeoutSec, List.copyOf(steps));

        // ⚠️ 必须把 L0 的诊断一并带下来。漏掉这一步不会有任何报错 ——
        // 服务照常返回结果，只是"字段名拼错了""同一字段给了两遍"这类提示凭空消失，
        // 请求方以为一切正常。诊断丢失本身就是一种静默失败，所以这里显式合并。
        List<Diagnostic> all = new ArrayList<>(envelope.diagnostics());
        all.addAll(ctx.diagnostics);

        return new MappedCase(mapped, Map.copyOf(ctx.provenance), List.copyOf(all));
    }

    // ── 案例级 ──────────────────────────────────────────────────────────────────

    /**
     * module_id 支持用 module_code 提供（{@code "module": "CART"} → {@code M003}）。
     * <p>
     * 这是典型的"规则能确定性完成、就绝不该劳烦模型"的例子：语义匹配交给模型是有意义的
     * （标题含「カート」→ 购物车模块），但 code→id 是一次查表，交给模型只是徒增编造风险。
     */
    private String resolveModuleId(Map<String, JsonNode> f, Ctx ctx) {
        Optional<String> raw = text(f.get("module_id"));
        if (raw.isEmpty()) {
            return null;
        }
        Optional<ModuleEntry> resolved = profile.resolveModule(raw.get());
        if (resolved.isEmpty()) {
            // 不认识就原样留着，交给 L4 的外键校验去拒 —— L1 不负责判定合法性，
            // 但也绝不把一个不认识的值悄悄换成某个"最接近"的模块。
            ctx.input("module_id");
            return raw.get();
        }
        ModuleEntry module = resolved.get();
        if (!module.moduleId().equals(raw.get())) {
            ctx.diagnostics.add(Diagnostic.info(DiagnosticCodes.RULE_MODULE_CODE_RESOLVED,
                    "module_id",
                    "'" + raw.get() + "' 被解析为 module_id " + module.moduleId()
                  + "（" + module.moduleName() + "）。", null));
            ctx.rule("module_id", null);
        } else {
            ctx.input("module_id");
        }
        return module.moduleId();
    }

    /**
     * case_code：形状由规则保证，<b>序号由平台分配</b>。
     * <p>
     * 4 位序号要求全局唯一，而全局唯一必须有状态 —— 本服务不碰 DB，所以给不了真序号。
     * 这里产出 {@code ATP-{MODULE}-0000} 占位并附诊断说明，
     * 由平台方在入库时替换（交接文档 §11 Q7）。
     * <p>
     * 用 {@code 0000} 而不是留空，是为了让案例仍能通过 schema 校验走完整条链路 ——
     * 否则每条没带 case_code 的案例都会卡在 L4，而这本来不是它的错。
     */
    private String resolveCaseCode(Map<String, JsonNode> f, String moduleId, Ctx ctx) {
        Optional<String> raw = text(f.get("case_code"));
        if (raw.isPresent()) {
            String value = raw.get();
            if (!value.matches(NormalizedCase.CASE_CODE_PATTERN)) {
                ctx.diagnostics.add(Diagnostic.error(DiagnosticCodes.STD_007_CASE_CODE_FORMAT,
                        "case_code",
                        "case_code '" + value + "' 不符合 ATP-{MODULE}-{4位序号} 的形状。",
                        "STD-007"));
            }
            ctx.input("case_code");
            return value;
        }
        if (moduleId == null) {
            return null;   // 连模块都不知道，形状也拼不出来，留给 L2
        }
        Optional<ModuleEntry> module = profile.resolveModule(moduleId);
        if (module.isEmpty()) {
            return null;
        }
        String template = "ATP-" + module.get().moduleCode() + "-0000";
        ctx.diagnostics.add(Diagnostic.info(DiagnosticCodes.STD_007_CASE_CODE_FORMAT, "case_code",
                "已生成 case_code 模板 " + template
              + "，其中 4 位序号需由平台在入库时分配（本服务无状态，无法保证全局唯一）。",
                "STD-007"));
        ctx.rule("case_code", "STD-007");
        return template;
    }

    // ── 步骤级 ──────────────────────────────────────────────────────────────────

    private TestStep mapStep(Map<String, JsonNode> f, int index, Ctx ctx) {
        String path = "steps[" + index + "]";

        Action action = resolveAction(f, path, ctx);

        String locatorValue = stringField(f, "locator_value", path + ".locator_value", 512, ctx);
        LocatorType locatorType = resolveLocatorType(f, locatorValue, path, ctx);
        String inputData = stringField(f, "input_data", path + ".input_data", 1024, ctx);
        String expected = stringField(f, "expected", path + ".expected", 1024, ctx);
        String description = stringField(f, "description", path + ".description", 500, ctx);

        WaitStrategy waitStrategy = resolveWaitStrategy(f, action, path, ctx);
        Integer waitTimeout = intField(f, "wait_timeout_sec", path + ".wait_timeout_sec",
                TestStep.DEFAULT_WAIT_TIMEOUT_SEC,
                TestStep.MIN_WAIT_TIMEOUT_SEC, TestStep.MAX_WAIT_TIMEOUT_SEC, ctx);
        OnFailure onFailure = enumField(f, "on_failure", OnFailure.class, path + ".on_failure",
                OnFailure.ABORT, ctx);

        // seq 一律按数组下标重排为 1..n。请求方给的 seq 只用于判断"是否需要提醒它乱序了"，
        // 不作为最终值 —— 数组顺序才是请求方真正表达的执行顺序。
        int seq = index + 1;
        Optional<Integer> providedSeq = integer(f.get("seq"));
        if (providedSeq.isPresent() && providedSeq.get() != seq) {
            ctx.diagnostics.add(Diagnostic.info(DiagnosticCodes.RULE_SEQ_RESEQUENCED,
                    path + ".seq",
                    "seq 由 " + providedSeq.get() + " 重排为 " + seq + "（按步骤在数组中的顺序）。",
                    null));
        }
        ctx.rule(path + ".seq", null);

        return new TestStep(null, seq, action, locatorType, locatorValue, inputData,
                expected, waitStrategy, waitTimeout, onFailure, description);
    }

    private Action resolveAction(Map<String, JsonNode> f, String path, Ctx ctx) {
        Optional<String> raw = text(f.get("action"));
        if (raw.isEmpty()) {
            return null;   // 缺 action，L2 会算成缺口；但它其实无从补起，L4 会拒
        }
        Optional<Action> action = profile.enumNormalizer().action(raw.get());
        if (action.isEmpty()) {
            ctx.diagnostics.add(Diagnostic.error(DiagnosticCodes.RULE_UNKNOWN_ACTION,
                    path + ".action",
                    "无法识别的操作 '" + raw.get() + "'。请使用 atp_describe_schema 返回的 action 取值。",
                    null));
            return null;
        }
        ctx.input(path + ".action");
        return action.get();
    }

    /**
     * locator_type：请求方声明的优先，否则按形状推断（推断规则见
     * {@link LocatorLinter#inferType(String)}，与 {@code atp_lint_locator} 共用同一份）。
     * 推断不出就留 null 交给 L2 —— 宁可让模型判，也不按"像不像"去猜。
     */
    private LocatorType resolveLocatorType(Map<String, JsonNode> f, String locatorValue,
                                           String path, Ctx ctx) {
        Optional<LocatorType> declared = text(f.get("locator_type"))
                .flatMap(raw -> profile.enumNormalizer().byName(LocatorType.class, raw));
        if (declared.isPresent()) {
            ctx.input(path + ".locator_type");
            return declared.get();
        }

        Optional<LocatorType> inferred = LocatorLinter.inferType(locatorValue);
        if (inferred.isEmpty()) {
            return null;
        }
        ctx.diagnostics.add(Diagnostic.info(DiagnosticCodes.RULE_LOCATOR_TYPE_INFERRED,
                path + ".locator_type",
                "由 locator_value 的形状推断 locator_type = " + inferred.get() + "。", null));
        ctx.rule(path + ".locator_type", null);
        return inferred.get();
    }

    /**
     * wait_strategy 完全由 action 决定（STD-005/006），<b>不接受请求方覆盖</b>。
     * <p>
     * 请求方给了不同的值时纠正并 WARN。让规范可被覆盖，规范就变成了建议。
     */
    private WaitStrategy resolveWaitStrategy(Map<String, JsonNode> f, Action action,
                                             String path, Ctx ctx) {
        if (action == null) {
            return null;   // action 都不知道，无从决定等待策略
        }
        WaitStrategy mandated = action.mandatedWaitStrategy();
        String fieldPath = path + ".wait_strategy";

        text(f.get("wait_strategy"))
                .flatMap(raw -> profile.enumNormalizer().byName(WaitStrategy.class, raw))
                .filter(provided -> provided != mandated)
                .ifPresent(provided -> ctx.diagnostics.add(
                        Diagnostic.warn(DiagnosticCodes.RULE_WAIT_STRATEGY_CORRECTED, fieldPath,
                                "wait_strategy 由 " + provided + " 纠正为 " + mandated
                              + "（" + action + " 的等待策略由规范强制，不可覆盖）。",
                                action.standardRef())));

        // M1-D2：ASSERT_NOT_EXIST 偏离 STD-006 字面要求，必须显式报出而非静默执行
        if (action.waitStrategyDeviation() != null) {
            ctx.diagnostics.add(Diagnostic.info(DiagnosticCodes.RULE_WAIT_STRATEGY_DEVIATION,
                    fieldPath, action.waitStrategyDeviation(), action.standardRef()));
        }

        ctx.rule(fieldPath, action.standardRef());
        return mandated;
    }

    // ── 通用字段处理 ────────────────────────────────────────────────────────────

    private String stringField(Map<String, JsonNode> f, String key, String path,
                               Integer maxLength, Ctx ctx) {
        Optional<String> value = text(f.get(key));
        if (value.isEmpty()) {
            return null;
        }
        String s = value.get();
        if (maxLength != null && s.length() > maxLength) {
            // 不截断 —— 截断会让 schema 校验通过，同时悄悄损坏语义
            ctx.diagnostics.add(Diagnostic.error(DiagnosticCodes.RULE_VALUE_TOO_LONG, path,
                    "长度 " + s.length() + " 超过上限 " + maxLength
                  + "。本服务不会截断（截断会改变语义），请缩短后重试。", null));
        }
        ctx.input(path);
        return s;
    }

    private <E extends Enum<E>> E enumField(Map<String, JsonNode> f, String key, Class<E> type,
                                            String path, E fallback, Ctx ctx) {
        Optional<String> raw = text(f.get(key));
        if (raw.isEmpty()) {
            if (fallback != null) {
                ctx.def(path);
            }
            return fallback;
        }
        Optional<E> parsed = profile.enumNormalizer().byName(type, raw.get());
        if (parsed.isEmpty()) {
            ctx.diagnostics.add(Diagnostic.error(DiagnosticCodes.RULE_UNKNOWN_ENUM, path,
                    "无法识别的取值 '" + raw.get() + "'，合法取值见 atp_describe_schema 的 enums。",
                    null));
            return null;   // 不回落到默认值：请求方明确给了一个值，用默认值悄悄替换它是错的
        }
        ctx.input(path);
        return parsed.get();
    }

    private Integer intField(Map<String, JsonNode> f, String key, String path,
                             int fallback, int min, int max, Ctx ctx) {
        JsonNode node = f.get(key);
        if (node == null || node.isNull() || node.isMissingNode()) {
            ctx.def(path);
            return fallback;
        }
        Optional<Integer> parsed = integer(node);
        if (parsed.isEmpty()) {
            ctx.diagnostics.add(Diagnostic.error(DiagnosticCodes.RULE_TYPE_MISMATCH, path,
                    "期望整数，实际是 " + node.getNodeType() + "。", null));
            return null;
        }
        int value = parsed.get();
        if (value < min || value > max) {
            ctx.diagnostics.add(Diagnostic.error(DiagnosticCodes.RULE_VALUE_OUT_OF_RANGE, path,
                    "取值 " + value + " 超出允许范围 " + min + ".." + max + "。", null));
        }
        ctx.input(path);
        return value;
    }

    // ── JSON 取值 ───────────────────────────────────────────────────────────────

    /** 取字符串值；对象/数组/空白一律视为"没给"。 */
    private static Optional<String> text(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode() || !node.isValueNode()) {
            return Optional.empty();
        }
        String s = node.asString().trim();
        return s.isEmpty() ? Optional.empty() : Optional.of(s);
    }

    /** 取整数；容忍字符串形式的数字（{@code "30"}），这是 JSON 里极常见的写法。 */
    private static Optional<Integer> integer(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return Optional.empty();
        }
        if (node.isIntegralNumber()) {
            return Optional.of(node.asInt());
        }
        if (node.isString()) {
            try {
                return Optional.of(Integer.parseInt(node.asString().trim()));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** 收集 provenance 与诊断，避免在每个方法签名里传两个可变集合。 */
    private static final class Ctx {
        final Map<String, FieldProvenance> provenance = new LinkedHashMap<>();
        final List<Diagnostic> diagnostics = new ArrayList<>();

        void input(String path) {
            provenance.put(path, FieldProvenance.fromInput());
        }

        void rule(String path, String standardRef) {
            provenance.put(path, FieldProvenance.fromRule(standardRef));
        }

        void def(String path) {
            provenance.put(path, FieldProvenance.fromDefault());
        }
    }
}
