package dev.kanashi.atp.mcp.profile.atp;

import dev.kanashi.atp.mcp.profile.AliasDictionary;
import dev.kanashi.atp.mcp.profile.LenientNames;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * ATP 的字段别名字典。
 * <p>
 * 别名覆盖中日英三语，因为 ATP 的实际使用场景就是中日英混杂的文档环境
 * （新加坡人在日本公司，规范中日英并存）。这不是炫技，是这些字段名真的会出现。
 * <p>
 * <b>收录原则：只收无歧义的别名。</b> 例如 {@code id} 不映射到 {@code case_code} ——
 * 它同样可能指 {@code case_id}（平台生成的雪花 ID）。猜错一个字段名，
 * 后果是把 A 字段的值填进了 B 字段，而两边类型恰好都是字符串时校验还发现不了。
 * 宁可识别不出交给诊断，也不猜。
 */
class AtpAliasDictionary implements AliasDictionary {

    private static final Map<String, String> CASE_FIELDS = caseFields();
    private static final Map<String, String> STEP_FIELDS = stepFields();

    private static final Set<String> STEPS_CONTAINERS = keysOf(
            "steps", "stepList", "testSteps", "actions", "operations",
            "步骤", "操作步骤", "测试步骤",
            "ステップ", "手順", "テスト手順", "操作手順");

    /**
     * 字段名直接点明了定位方式的情形（如 {@code "xpath": "//div"}），比看值的形状去推断更可靠。
     * <p>
     * ⚠️ 这里<b>不收</b> {@code id} 与 {@code name}：它们在案例里同样可能指
     * {@code case_id} / {@code step_id} 或步骤名称。歧义别名一旦收录，
     * 后果是把 A 字段的值填进 B 字段，而两边都是字符串时校验还发现不了。
     */
    private static final Map<String, String> LOCATOR_TYPE_HINTS = Map.of(
            "xpath", "XPATH",
            "css", "CSS",
            "cssselector", "CSS",
            "linktext", "LINK_TEXT");

    @Override
    public Optional<String> canonicalCaseField(String rawName) {
        return Optional.ofNullable(CASE_FIELDS.get(LenientNames.key(rawName)));
    }

    @Override
    public Optional<String> canonicalStepField(String rawName) {
        return Optional.ofNullable(STEP_FIELDS.get(LenientNames.key(rawName)));
    }

    @Override
    public boolean isStepsContainer(String rawName) {
        return STEPS_CONTAINERS.contains(LenientNames.key(rawName));
    }

    @Override
    public Optional<String> locatorTypeHintFromFieldName(String rawName) {
        return Optional.ofNullable(LOCATOR_TYPE_HINTS.get(LenientNames.key(rawName)));
    }

    private static Map<String, String> caseFields() {
        Map<String, String> m = new HashMap<>();
        put(m, "title",        "title", "name", "caseName", "testName", "caseTitle",
                               "标题", "名称", "用例名", "案例名",
                               "タイトル", "ケース名", "名前", "テスト名");
        put(m, "module_id",    "moduleId", "module", "moduleCode",
                               "模块", "模块ID", "所属模块",
                               "モジュール", "機能", "画面");
        put(m, "priority",     "priority", "prio", "severity", "level",
                               "优先级", "优先度",
                               "優先度", "重要度");
        put(m, "author",       "author", "owner", "creator", "createdBy",
                               "作者", "创建者", "负责人",
                               "作成者", "担当者", "作成");
        put(m, "precondition", "precondition", "preconditions", "pre", "setup", "given",
                               "前置条件", "前提条件", "预置条件",
                               "事前条件", "前提");
        put(m, "status",       "status", "state",
                               "状态", "ステータス", "状態");
        put(m, "browser",      "browser",
                               "浏览器", "ブラウザ");
        put(m, "timeout_sec",  "timeoutSec", "timeout", "caseTimeout",
                               "超时", "超时时间",
                               "タイムアウト");
        put(m, "case_code",    "caseCode", "code", "caseNo",
                               "编号", "用例编号", "案例编号",
                               "ケースコード", "番号");
        return Map.copyOf(m);
    }

    private static Map<String, String> stepFields() {
        Map<String, String> m = new HashMap<>();
        put(m, "seq",              "seq", "index", "order", "no", "num", "stepNo", "stepIndex",
                                   "序号", "顺序", "步骤号",
                                   "番号", "順番", "手順番号");
        put(m, "action",           "action", "type", "op", "operation", "actionType", "command",
                                   "操作", "动作", "操作类型",
                                   "アクション", "操作種別", "動作");
        put(m, "locator_type",     "locatorType", "by", "selectorType", "findBy",
                                   "定位方式", "定位类型",
                                   "ロケータ種別", "検索方法");
        put(m, "locator_value",    "locatorValue", "locator", "selector", "target", "element",
                                   "定位", "定位器", "元素", "目标元素",
                                   "セレクタ", "ロケータ", "要素", "対象要素");
        put(m, "input_data",       "inputData", "input", "value", "data", "text", "keys",
                                   "输入", "输入值", "输入数据",
                                   "入力", "入力値", "データ");
        put(m, "expected",         "expected", "expect", "expectedText", "expectedValue",
                                   "assertion", "then",
                                   "期望", "预期", "期望值", "预期结果",
                                   "期待値", "期待結果", "想定結果");
        put(m, "wait_strategy",    "waitStrategy", "wait", "waitType", "waitCondition",
                                   "等待", "等待策略",
                                   "待機", "待機条件");
        put(m, "wait_timeout_sec", "waitTimeoutSec", "waitTimeout", "timeoutSec",
                                   "等待超时", "等待时间",
                                   "待機タイムアウト");
        put(m, "on_failure",       "onFailure", "onError", "failureAction", "errorHandling",
                                   "失败处理", "失败时",
                                   "失敗時", "エラー時");
        put(m, "description",      "description", "desc", "comment", "note", "remark", "memo",
                                   "说明", "描述", "备注",
                                   "説明", "備考", "コメント");
        return Map.copyOf(m);
    }

    private static void put(Map<String, String> target, String canonical, String... aliases) {
        target.put(LenientNames.key(canonical), canonical);
        for (String alias : aliases) {
            String key = LenientNames.key(alias);
            String previous = target.put(key, canonical);
            if (previous != null && !previous.equals(canonical)) {
                // 同一个别名映射到两个不同的标准字段 —— 这会让 L0 的行为取决于字典的构造顺序，
                // 是必须在启动期就暴露的错误，不能等到某条案例被静默填错才发现。
                throw new IllegalStateException(
                        "别名冲突：'" + alias + "' 同时映射到 " + previous + " 与 " + canonical);
            }
        }
    }

    private static Set<String> keysOf(String... names) {
        return java.util.Arrays.stream(names).map(LenientNames::key)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
