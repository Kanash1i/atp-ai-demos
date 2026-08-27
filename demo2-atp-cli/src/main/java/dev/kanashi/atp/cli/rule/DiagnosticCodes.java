package dev.kanashi.atp.cli.rule;

/**
 * 诊断码常量。
 * <p>
 * 做成稳定的机器可判定标识，而不是靠匹配错误文案 —— 调用方是 agent，
 * 它需要靠 code 分支决策（比如"遇到 {@code FK_MODULE_NOT_FOUND} 就去调 atp_list_modules 重选"）。
 * 文案会改、会翻译，code 不会。
 * <p>
 * 前缀标明产生环节，便于定位是哪一层判的：
 * {@code ENVELOPE_}(L0) / {@code RULE_}(L1) / {@code GAP_}(L2) / {@code SCHEMA_}{@code FK_}{@code CONTRACT_}{@code STD_}(L4)。
 */
public final class DiagnosticCodes {

    private DiagnosticCodes() {
    }

    // ── L0 输入规整 ─────────────────────────────────────────────────────────────
    /** 输入根本不是一个 JSON 对象，无法当作案例解析。 */
    public static final String ENVELOPE_NOT_OBJECT = "ENVELOPE_NOT_OBJECT";
    /** 字段名无法对应到任何已知字段，已忽略。 */
    public static final String ENVELOPE_UNKNOWN_FIELD = "ENVELOPE_UNKNOWN_FIELD";
    /** steps 数组里混入了非对象元素。 */
    public static final String ENVELOPE_STEP_NOT_OBJECT = "ENVELOPE_STEP_NOT_OBJECT";
    /** 找不到步骤数组。 */
    public static final String ENVELOPE_STEPS_MISSING = "ENVELOPE_STEPS_MISSING";
    /** 同一个标准字段被多个别名重复提供，且取值不同。 */
    public static final String ENVELOPE_DUPLICATE_FIELD = "ENVELOPE_DUPLICATE_FIELD";

    // ── L1 确定性映射 ───────────────────────────────────────────────────────────
    /** action 值无法归一化到已知枚举。 */
    public static final String RULE_UNKNOWN_ACTION = "RULE_UNKNOWN_ACTION";
    /** 枚举值无法识别（priority / browser / status 等）。 */
    public static final String RULE_UNKNOWN_ENUM = "RULE_UNKNOWN_ENUM";
    /** 字段超长。**不截断** —— 截断会改变语义，把问题藏起来。 */
    public static final String RULE_VALUE_TOO_LONG = "RULE_VALUE_TOO_LONG";
    /** 数值超出 schema 允许范围。 */
    public static final String RULE_VALUE_OUT_OF_RANGE = "RULE_VALUE_OUT_OF_RANGE";
    /** 值的类型不对（如 seq 给了非数字）。 */
    public static final String RULE_TYPE_MISMATCH = "RULE_TYPE_MISMATCH";
    /** seq 被重排为 1..n 连续。 */
    public static final String RULE_SEQ_RESEQUENCED = "RULE_SEQ_RESEQUENCED";
    /** locator_type 由 locator_value 的形状推断而来。 */
    public static final String RULE_LOCATOR_TYPE_INFERRED = "RULE_LOCATOR_TYPE_INFERRED";
    /** 请求方给的 wait_strategy 与规范强制值不符，已按规范纠正。 */
    public static final String RULE_WAIT_STRATEGY_CORRECTED = "RULE_WAIT_STRATEGY_CORRECTED";
    /** 该 action 的 wait_strategy 偏离规范字面要求（目前只有 ASSERT_NOT_EXIST）。 */
    public static final String RULE_WAIT_STRATEGY_DEVIATION = "RULE_WAIT_STRATEGY_DEVIATION";
    /** module_code 被解析成了 module_id。 */
    public static final String RULE_MODULE_CODE_RESOLVED = "RULE_MODULE_CODE_RESOLVED";
    /** 该 action 用不到这个字段，值已丢弃。 */
    public static final String RULE_FIELD_NOT_APPLICABLE = "RULE_FIELD_NOT_APPLICABLE";

    // ── L2 缺口分析 ─────────────────────────────────────────────────────────────
    /** 必填字段缺失，且规则无法推导，需要模型补全。 */
    public static final String GAP_MISSING_REQUIRED = "GAP_MISSING_REQUIRED";
    /** 存在缺口但模型补全环节不可用（M2 阶段 L3 尚未接入 / LLM 故障降级）。 */
    public static final String GAP_COMPLETION_UNAVAILABLE = "GAP_COMPLETION_UNAVAILABLE";

    // ── L4 校验 ─────────────────────────────────────────────────────────────────
    /** JSON Schema 校验未通过。 */
    public static final String SCHEMA_VIOLATION = "SCHEMA_VIOLATION";
    /** ⭐ module_id 不在字典中 —— 防模型编造的关键一条。 */
    public static final String FK_MODULE_NOT_FOUND = "FK_MODULE_NOT_FOUND";
    /** 该 action 要求 locator，但未提供。 */
    public static final String CONTRACT_LOCATOR_REQUIRED = "CONTRACT_LOCATOR_REQUIRED";
    /** 该 action 要求 input_data，但未提供。 */
    public static final String CONTRACT_INPUT_DATA_REQUIRED = "CONTRACT_INPUT_DATA_REQUIRED";
    /** 该 action 要求 expected，但未提供。 */
    public static final String CONTRACT_EXPECTED_REQUIRED = "CONTRACT_EXPECTED_REQUIRED";
    /** 该 action 用不到这个字段，却提供了值。 */
    public static final String CONTRACT_FIELD_FORBIDDEN = "CONTRACT_FIELD_FORBIDDEN";
    /** seq 不是 1..n 连续。 */
    public static final String SEQ_NOT_CONTIGUOUS = "SEQ_NOT_CONTIGUOUS";

    // ── 业务规范 STD-001 ~ STD-008 ──────────────────────────────────────────────
    public static final String STD_001_ABSOLUTE_XPATH = "STD_001_ABSOLUTE_XPATH";
    public static final String STD_002_DYNAMIC_ID = "STD_002_DYNAMIC_ID";
    public static final String STD_003_UNSTABLE_LOCATOR = "STD_003_UNSTABLE_LOCATOR";
    public static final String STD_004_SLEEP_FORBIDDEN = "STD_004_SLEEP_FORBIDDEN";
    public static final String STD_007_CASE_CODE_FORMAT = "STD_007_CASE_CODE_FORMAT";
    public static final String STD_008_NO_ASSERTION = "STD_008_NO_ASSERTION";
}
