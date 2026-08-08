package com.atp.rag.corpus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 语料自检。
 *
 * <p>80 条案例是脚本生成的，但<b>脚本正确不等于语料正确</b> ——
 * 生成器的 bug 会安静地产出 80 个格式完美、内容错误的文件。
 * 这里把语料该满足的约束写成断言，让「语料坏了」在 M2 入库之前就暴露。
 *
 * <p>最关键的是 {@link #violationLabelsMustMatchReality()}：它<b>不信任</b>生成器写下的
 * {@code violation_codes}，而是重新独立判定一遍再对照。
 * 这个标注会在 M2 变成 Qdrant 的 payload，是「这条案例可以参考，但它违反了 STD-004，
 * 别照抄」这类回答的唯一依据 —— 标注错了，助手就会理直气壮地推荐一条违规案例。
 */
class CorpusIntegrityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int EXPECTED_CASE_COUNT = 80;
    private static final int EXPECTED_PER_MODULE = 10;

    private static final Set<String> MODULES = new TreeSet<String>(Arrays.asList(
            "LOGIN", "SEARCH", "CART", "ORDER", "USER", "PAYMENT", "REPORT", "ADMIN"));

    /** 共享契约 §1.3 的 13 个 action，封闭集合。 */
    private static final Set<String> ACTIONS = new TreeSet<String>(Arrays.asList(
            "OPEN_URL", "CLICK", "INPUT", "SELECT", "ASSERT_TEXT", "ASSERT_VISIBLE",
            "ASSERT_NOT_EXIST", "WAIT_FOR", "SCROLL_TO", "SWITCH_FRAME", "SWITCH_WINDOW",
            "UPLOAD", "SLEEP"));

    private static final Set<String> LOCATOR_TYPES = new HashSet<String>(Arrays.asList(
            "XPATH", "CSS", "ID", "NAME", "LINK_TEXT"));
    private static final Set<String> WAIT_STRATEGIES = new HashSet<String>(Arrays.asList(
            "NONE", "PRESENCE", "VISIBLE", "CLICKABLE"));
    private static final Set<String> ON_FAILURES = new HashSet<String>(Arrays.asList(
            "ABORT", "CONTINUE", "RETRY"));
    private static final Set<String> PRIORITIES = new HashSet<String>(Arrays.asList(
            "P0", "P1", "P2", "P3"));
    private static final Set<String> STATUSES = new HashSet<String>(Arrays.asList(
            "DRAFT", "ACTIVE", "DEPRECATED"));
    private static final Set<String> BROWSERS = new HashSet<String>(Arrays.asList(
            "CHROME", "FIREFOX", "EDGE"));

    /** 交接文档 §4.2 设计的脏数据分布。数量对不上就说明语料被改动过而没同步设计。 */
    private static final Map<String, Integer> EXPECTED_VIOLATIONS = new TreeMap<String, Integer>();

    static {
        EXPECTED_VIOLATIONS.put("STD-001", 4);   // 绝对路径 XPath
        EXPECTED_VIOLATIONS.put("STD-002", 3);   // 依赖动态 id
        EXPECTED_VIOLATIONS.put("STD-004", 3);   // 使用 SLEEP
        EXPECTED_VIOLATIONS.put("STD-007", 3);   // case_code 命名违规
        EXPECTED_VIOLATIONS.put("STD-008", 2);   // 没有断言步骤
    }

    private static final List<JsonNode> CASES = loadCases();

    // ── 规模与分布 ────────────────────────────────────────────

    @Test
    @DisplayName("案例总数与模块分布符合设计")
    void caseCountAndModuleDistribution() {
        assertEquals(EXPECTED_CASE_COUNT, CASES.size(), "案例总数");

        Map<String, Integer> perModule = new TreeMap<String, Integer>();
        for (JsonNode c : CASES) {
            String module = c.path("module_code").asText();
            perModule.put(module, perModule.getOrDefault(module, 0) + 1);
        }
        assertEquals(MODULES, perModule.keySet(), "模块集合");
        for (Map.Entry<String, Integer> e : perModule.entrySet()) {
            assertEquals(EXPECTED_PER_MODULE, e.getValue().intValue(), "模块 " + e.getKey() + " 的案例数");
        }
    }

    @Test
    @DisplayName("case_code 唯一，且模块内序号从 1 连续")
    void caseCodesAreUniqueAndSequential() {
        Set<String> seen = new HashSet<String>();
        Map<String, Set<Integer>> seqByModule = new TreeMap<String, Set<Integer>>();

        for (JsonNode c : CASES) {
            String code = c.path("case_code").asText();
            assertTrue(seen.add(code), "case_code 重复：" + code);

            // 命名违规的那 3 条本来就不符合格式，序号连续性对它们不适用
            if (c.path("violation_codes").toString().contains("STD-007")) {
                continue;
            }
            String module = c.path("module_code").asText();
            String[] parts = code.split("-");
            if (!seqByModule.containsKey(module)) {
                seqByModule.put(module, new TreeSet<Integer>());
            }
            seqByModule.get(module).add(Integer.parseInt(parts[2]));
        }

        for (Map.Entry<String, Set<Integer>> e : seqByModule.entrySet()) {
            int expected = 1;
            for (int actual : e.getValue()) {
                assertEquals(expected, actual,
                        "模块 " + e.getKey() + " 的 case_code 序号跳号，期望 " + expected);
                expected++;
            }
        }
    }

    @Test
    @DisplayName("13 个 action 全部至少被一条案例覆盖")
    void allActionsAreCovered() {
        Set<String> used = new TreeSet<String>();
        for (JsonNode c : CASES) {
            for (JsonNode step : c.path("steps")) {
                used.add(step.path("action").asText());
            }
        }
        Set<String> missing = new TreeSet<String>(ACTIONS);
        missing.removeAll(used);
        // 未被覆盖的 action 意味着评估集里问到它就必然召回不到 ——
        // §5.1 的 B 类用例明确举例了「有没有涉及文件上传的案例」
        assertTrue(missing.isEmpty(), "以下 action 在语料中没有任何案例覆盖：" + missing);
    }

    // ── 字段与枚举 ────────────────────────────────────────────

    @Test
    @DisplayName("案例字段完整且枚举取值合法")
    void caseFieldsAreValid() {
        List<String> errors = new ArrayList<String>();
        for (JsonNode c : CASES) {
            String code = c.path("case_code").asText();
            requireText(c, "case_id", code, errors);
            requireText(c, "title", code, errors);
            requireText(c, "module_id", code, errors);
            requireText(c, "author", code, errors);
            requireText(c, "created_at", code, errors);
            requireText(c, "updated_at", code, errors);

            requireEnum(c, "priority", PRIORITIES, code, errors);
            requireEnum(c, "status", STATUSES, code, errors);
            requireEnum(c, "browser", BROWSERS, code, errors);

            int timeout = c.path("timeout_sec").asInt();
            if (timeout < 5 || timeout > 300) {
                errors.add(code + ": timeout_sec 超出 5..300，实际 " + timeout);
            }
            if (c.path("steps").size() == 0) {
                errors.add(code + ": 没有任何步骤");
            }
        }
        assertNoErrors(errors);
    }

    @Test
    @DisplayName("步骤 seq 从 1 开始连续无跳号")
    void stepSequenceIsContiguous() {
        List<String> errors = new ArrayList<String>();
        for (JsonNode c : CASES) {
            String code = c.path("case_code").asText();
            int expected = 1;
            for (JsonNode step : c.path("steps")) {
                int seq = step.path("seq").asInt();
                if (seq != expected) {
                    errors.add(code + ": seq 应为 " + expected + "，实际 " + seq);
                }
                expected++;
            }
        }
        assertNoErrors(errors);
    }

    @Test
    @DisplayName("步骤枚举合法，等待超时在范围内")
    void stepEnumsAreValid() {
        List<String> errors = new ArrayList<String>();
        for (JsonNode c : CASES) {
            String code = c.path("case_code").asText();
            for (JsonNode step : c.path("steps")) {
                String where = code + " step" + step.path("seq").asInt();
                String action = step.path("action").asText();
                if (!ACTIONS.contains(action)) {
                    errors.add(where + ": 未知 action " + action);
                }
                requireEnum(step, "wait_strategy", WAIT_STRATEGIES, where, errors);
                requireEnum(step, "on_failure", ON_FAILURES, where, errors);

                JsonNode locatorType = step.path("locator_type");
                if (!locatorType.isNull() && !LOCATOR_TYPES.contains(locatorType.asText())) {
                    errors.add(where + ": 未知 locator_type " + locatorType.asText());
                }
                int waitTimeout = step.path("wait_timeout_sec").asInt();
                if (waitTimeout < 1 || waitTimeout > 120) {
                    errors.add(where + ": wait_timeout_sec 超出 1..120，实际 " + waitTimeout);
                }
            }
        }
        assertNoErrors(errors);
    }

    @Test
    @DisplayName("action 与 locator / input_data / expected 的必填关系符合共享契约 §1.3")
    void actionFieldContractIsRespected() {
        List<String> errors = new ArrayList<String>();
        for (JsonNode c : CASES) {
            String code = c.path("case_code").asText();
            for (JsonNode step : c.path("steps")) {
                String where = code + " step" + step.path("seq").asInt();
                String action = step.path("action").asText();

                boolean needsLocator = !Arrays.asList("OPEN_URL", "SWITCH_WINDOW", "SLEEP").contains(action);
                boolean needsInput = Arrays.asList(
                        "OPEN_URL", "INPUT", "SELECT", "SWITCH_WINDOW", "UPLOAD", "SLEEP").contains(action);
                boolean needsExpected = "ASSERT_TEXT".equals(action);

                checkPresence(step, "locator_value", needsLocator, where, action, errors);
                checkPresence(step, "input_data", needsInput, where, action, errors);
                checkPresence(step, "expected", needsExpected, where, action, errors);
            }
        }
        assertNoErrors(errors);
    }

    // ── 规范内建（STD-005 / STD-006 是平台自动补全的，语料必须已经是补全后的样子）──

    @Test
    @DisplayName("STD-005：CLICK 的 wait_strategy 一律为 CLICKABLE")
    void clickAlwaysUsesClickable() {
        List<String> errors = new ArrayList<String>();
        for (JsonNode c : CASES) {
            for (JsonNode step : c.path("steps")) {
                if ("CLICK".equals(step.path("action").asText())
                        && !"CLICKABLE".equals(step.path("wait_strategy").asText())) {
                    errors.add(c.path("case_code").asText() + " step" + step.path("seq").asInt()
                            + ": CLICK 的 wait_strategy 是 " + step.path("wait_strategy").asText());
                }
            }
        }
        assertNoErrors(errors);
    }

    @Test
    @DisplayName("STD-006：ASSERT_* 的 wait_strategy 一律为 VISIBLE")
    void assertionsAlwaysUseVisible() {
        List<String> errors = new ArrayList<String>();
        for (JsonNode c : CASES) {
            for (JsonNode step : c.path("steps")) {
                String action = step.path("action").asText();
                if (action.startsWith("ASSERT_")
                        && !"VISIBLE".equals(step.path("wait_strategy").asText())) {
                    errors.add(c.path("case_code").asText() + " step" + step.path("seq").asInt()
                            + ": " + action + " 的 wait_strategy 是 " + step.path("wait_strategy").asText());
                }
            }
        }
        assertNoErrors(errors);
    }

    // ── 脏数据 ────────────────────────────────────────────────

    @Test
    @DisplayName("脏数据分布与 §4.2 的设计一致")
    void violationDistributionMatchesDesign() {
        Map<String, Integer> actual = new TreeMap<String, Integer>();
        int dirtyCases = 0;
        for (JsonNode c : CASES) {
            if (c.path("has_violation").asBoolean()) {
                dirtyCases++;
            }
            for (JsonNode code : c.path("violation_codes")) {
                actual.put(code.asText(), actual.getOrDefault(code.asText(), 0) + 1);
            }
        }
        assertEquals(EXPECTED_VIOLATIONS, actual, "各违规类型的案例数");
        assertEquals(15, dirtyCases, "含违规的案例总数");
    }

    @Test
    @DisplayName("violation_codes 标注与案例实际内容一致（双向核对）")
    void violationLabelsMustMatchReality() {
        List<String> errors = new ArrayList<String>();

        for (JsonNode c : CASES) {
            String code = c.path("case_code").asText();
            Set<String> labeled = new TreeSet<String>();
            for (JsonNode v : c.path("violation_codes")) {
                labeled.add(v.asText());
            }

            // 不看标注，独立重新判定一遍
            Set<String> detected = detectViolations(c);

            if (!labeled.equals(detected)) {
                errors.add(code + ": 标注为 " + labeled + "，独立判定为 " + detected);
            }
            // has_violation 是 M2 做 metadata 过滤的开关，必须和 codes 一致
            if (c.path("has_violation").asBoolean() != !detected.isEmpty()) {
                errors.add(code + ": has_violation=" + c.path("has_violation").asBoolean()
                        + " 与实际违规情况不符");
            }
        }
        assertNoErrors(errors);
    }

    /** 完全依据案例内容判定违规，不参考任何已有标注 —— 这是这份自检的价值所在。 */
    private static Set<String> detectViolations(JsonNode c) {
        Set<String> found = new TreeSet<String>();

        if (!c.path("case_code").asText().matches("^ATP-[A-Z]+-\\d{4}$")) {
            found.add("STD-007");
        }

        boolean hasAssertion = false;
        for (JsonNode step : c.path("steps")) {
            String action = step.path("action").asText();
            if (action.startsWith("ASSERT_")) {
                hasAssertion = true;
            }
            if ("SLEEP".equals(action)) {
                found.add("STD-004");
            }
            JsonNode locator = step.path("locator_value");
            if (locator.isNull()) {
                continue;
            }
            String value = locator.asText();
            if (value.startsWith("/html")) {
                found.add("STD-001");
            }
            if (value.matches(".*(ext-gen|ext-comp|mat-input-|cdk-overlay-|el-id-|uid-|auto-)\\w*.*")) {
                found.add("STD-002");
            }
        }
        if (!hasAssertion) {
            found.add("STD-008");
        }
        return found;
    }

    @Test
    @DisplayName("合规案例里不得混入违规写法")
    void cleanCasesAreActuallyClean() {
        List<String> errors = new ArrayList<String>();
        for (JsonNode c : CASES) {
            if (c.path("has_violation").asBoolean()) {
                continue;
            }
            Set<String> found = detectViolations(c);
            if (!found.isEmpty()) {
                errors.add(c.path("case_code").asText() + " 被当作合规案例，但实际违反了 " + found);
            }
        }
        assertNoErrors(errors);
    }

    // ── 文档语料 ──────────────────────────────────────────────

    @Test
    @DisplayName("文档语料齐全，且每篇都有可用于标题路径前缀的层级标题")
    void documentCorpusIsComplete() {
        Path manual = Paths.get("corpus/docs/manual");
        Path standards = Paths.get("corpus/docs/standards");

        List<Path> docs = new ArrayList<Path>();
        docs.addAll(listMarkdown(manual));
        docs.addAll(listMarkdown(standards));

        assertEquals(8, listMarkdown(manual).size(), "手册篇数");
        assertEquals(7, listMarkdown(standards).size(), "规范篇数");

        List<String> errors = new ArrayList<String>();
        for (Path doc : docs) {
            String text = read(doc);
            String name = doc.getFileName().toString();
            if (!text.startsWith("# ")) {
                errors.add(name + ": 缺少一级标题");
            }
            // §3.3(a) 的标题路径前缀优化需要真实的层级结构才有得可测。
            // 只有一级标题的文档，加不加前缀没有区别，那一行消融就没有意义了。
            if (!text.contains("\n## ")) {
                errors.add(name + ": 没有二级标题");
            }
            if (!text.contains("\n### ")) {
                errors.add(name + ": 没有三级标题");
            }
        }
        assertNoErrors(errors);
    }

    @Test
    @DisplayName("语料含日文文档，跨语言检索用例才有得可测")
    void japaneseDocumentsExist() {
        int japaneseDocs = 0;
        for (Path doc : allDocs()) {
            if (countJapaneseKana(read(doc)) > 200) {
                japaneseDocs++;
            }
        }
        // §5.1 的 C 类有 8 条跨语言用例（中文问 → 日文文档）。
        // 日文语料不足的话，那 8 条会退化成普通的同语言检索，测不出 bge-m3 的价值
        assertTrue(japaneseDocs >= 3,
                "以假名密度判定的日文文档只有 " + japaneseDocs + " 篇，跨语言用例会失去意义");
    }

    // ── 工具 ──────────────────────────────────────────────────

    private static void checkPresence(JsonNode step, String field, boolean required,
                                      String where, String action, List<String> errors) {
        JsonNode node = step.path(field);
        boolean present = !node.isNull() && !node.asText("").isEmpty();
        if (required && !present) {
            errors.add(where + ": " + action + " 缺少必填的 " + field);
        } else if (!required && present) {
            errors.add(where + ": " + action + " 不应有 " + field + "，实际是 " + node.asText());
        }
    }

    private static void requireText(JsonNode node, String field, String where, List<String> errors) {
        if (node.path(field).asText("").isEmpty()) {
            errors.add(where + ": " + field + " 为空");
        }
    }

    private static void requireEnum(JsonNode node, String field, Set<String> allowed,
                                    String where, List<String> errors) {
        String value = node.path(field).asText("");
        if (!allowed.contains(value)) {
            errors.add(where + ": " + field + " 取值非法「" + value + "」，允许 " + allowed);
        }
    }

    private static void assertNoErrors(List<String> errors) {
        if (!errors.isEmpty()) {
            StringBuilder sb = new StringBuilder("发现 " + errors.size() + " 处问题：");
            for (String e : errors) {
                sb.append("\n  - ").append(e);
            }
            fail(sb.toString());
        }
    }

    private static List<JsonNode> loadCases() {
        File dir = Paths.get("corpus/cases").toFile();
        File[] files = dir.listFiles(new java.io.FilenameFilter() {
            public boolean accept(File d, String name) {
                return name.endsWith(".json");
            }
        });
        if (files == null) {
            throw new IllegalStateException("找不到 corpus/cases，当前工作目录是 "
                    + Paths.get("").toAbsolutePath() + "。测试需要在模块根目录下运行");
        }
        Arrays.sort(files);
        List<JsonNode> cases = new ArrayList<JsonNode>();
        for (File f : files) {
            try {
                cases.add(MAPPER.readTree(f));
            } catch (IOException e) {
                throw new UncheckedIOException("解析 " + f + " 失败", e);
            }
        }
        return Collections.unmodifiableList(cases);
    }

    private static List<Path> allDocs() {
        List<Path> docs = new ArrayList<Path>();
        docs.addAll(listMarkdown(Paths.get("corpus/docs/manual")));
        docs.addAll(listMarkdown(Paths.get("corpus/docs/standards")));
        return docs;
    }

    private static List<Path> listMarkdown(Path dir) {
        File[] files = dir.toFile().listFiles(new java.io.FilenameFilter() {
            public boolean accept(File d, String name) {
                return name.endsWith(".md");
            }
        });
        if (files == null) {
            throw new IllegalStateException("找不到目录 " + dir.toAbsolutePath());
        }
        Arrays.sort(files);
        List<Path> paths = new ArrayList<Path>();
        for (File f : files) {
            paths.add(f.toPath());
        }
        return paths;
    }

    private static String read(Path path) {
        try {
            return new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 " + path + " 失败", e);
        }
    }

    /** 数假名（不含汉字）—— 汉字中日共用，只有假名能区分日文和中文。 */
    private static int countJapaneseKana(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if ((ch >= 0x3040 && ch <= 0x309F) || (ch >= 0x30A0 && ch <= 0x30FF)) {
                count++;
            }
        }
        return count;
    }
}
