package dev.kanashi.atp.mcp.pipeline;

import dev.kanashi.atp.mcp.domain.NormalizationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 黄金用例集 + 契约测试 + 零模型路径。
 * <p>
 * 用例覆盖交接文档 §7.1 列出的场景：完整输入、三语枚举归一、别名与信封、
 * locator 推断、seq 乱序、违反 STD-001/004、无断言、超长字段、以及救不回来的垃圾输入。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class GoldenCasesTest {

    /** 一条完全完整的输入 —— 走零模型路径的基准。 */
    static final String COMPLETE = """
            {
              "title": "カートに商品を追加できる",
              "module": "CART",
              "priority": "P1",
              "author": "yamada",
              "steps": [
                {"action":"open","value":"https://example.test/cart"},
                {"action":"click","xpath":"//*[@data-testid='add-to-cart']"},
                {"action":"assertText","css":"[data-testid='cart-count']","expected":"1"}
              ]
            }
            """;

    record GoldenCase(String name, String input, NormalizationStatus expected) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<GoldenCase> goldenCases() {
        return Stream.of(
                new GoldenCase("完整输入 —— 应零模型且通过", COMPLETE,
                        NormalizationStatus.ACCEPTED),

                new GoldenCase("中英日别名 + 外层信封", """
                        {"testCase":{"タイトル":"ログイン成功","モジュール":"LOGIN",
                         "優先度":"P0","担当者":"tanaka","手順":[
                          {"操作":"打开","入力値":"https://example.test/login"},
                          {"操作":"输入","セレクタ":"//*[@name='user']","value":"u1"},
                          {"操作":"クリック","xpath":"//*[@data-testid='submit']"},
                          {"操作":"断言可见","css":"[data-testid='home']"}]}}
                        """, NormalizationStatus.ACCEPTED),

                new GoldenCase("seq 乱序跳号 —— 重排后仍应通过", """
                        {"title":"t","module":"M003","priority":"P2","author":"a","steps":[
                          {"seq":9,"action":"open","value":"https://x.test"},
                          {"seq":3,"action":"assertVisible","locator":"//*[@data-testid='ok']"}]}
                        """, NormalizationStatus.ACCEPTED),

                new GoldenCase("缺 module_id —— 需模型推断，M2 阶段拒绝", """
                        {"title":"t","priority":"P1","author":"a","steps":[
                          {"action":"assertVisible","locator":"//*[@data-testid='ok']"}]}
                        """, NormalizationStatus.REJECTED),

                new GoldenCase("缺 author —— 模型不得编造人名", """
                        {"title":"t","module":"M003","priority":"P1","steps":[
                          {"action":"assertVisible","locator":"//*[@data-testid='ok']"}]}
                        """, NormalizationStatus.REJECTED),

                new GoldenCase("⭐ 缺 locator_value —— 模型没见过页面，不得代填", """
                        {"title":"t","module":"M003","priority":"P1","author":"a","steps":[
                          {"action":"click"},
                          {"action":"assertVisible","locator":"//*[@data-testid='ok']"}]}
                        """, NormalizationStatus.REJECTED),

                new GoldenCase("STD-004 使用了 SLEEP", """
                        {"title":"t","module":"M003","priority":"P1","author":"a","steps":[
                          {"action":"sleep","value":"3"},
                          {"action":"assertVisible","locator":"//*[@data-testid='ok']"}]}
                        """, NormalizationStatus.REJECTED),

                new GoldenCase("STD-008 没有任何断言步骤", """
                        {"title":"t","module":"M003","priority":"P1","author":"a","steps":[
                          {"action":"click","locator":"//*[@data-testid='btn']"}]}
                        """, NormalizationStatus.REJECTED),

                new GoldenCase("STD-001 绝对路径 XPath", """
                        {"title":"t","module":"M003","priority":"P1","author":"a","steps":[
                          {"action":"assertVisible","locator":"/html/body/div[3]/span"}]}
                        """, NormalizationStatus.REJECTED),

                new GoldenCase("STD-002 动态 id —— 可入库但带警告", """
                        {"title":"t","module":"M003","priority":"P1","author":"a","steps":[
                          {"action":"assertVisible","locator":"//*[@id='ext-gen1234']"}]}
                        """, NormalizationStatus.ACCEPTED_WITH_WARNINGS),

                new GoldenCase("字段超长 —— 拒绝而非截断", """
                        {"title":"%s","module":"M003","priority":"P1","author":"a","steps":[
                          {"action":"assertVisible","locator":"//*[@data-testid='ok']"}]}
                        """.formatted("あ".repeat(250)), NormalizationStatus.REJECTED),

                new GoldenCase("无法识别的 action", """
                        {"title":"t","module":"M003","priority":"P1","author":"a","steps":[
                          {"action":"teleport","locator":"//*[@data-testid='ok']"}]}
                        """, NormalizationStatus.REJECTED),

                new GoldenCase("编造的 module_id", """
                        {"title":"t","module":"M999","priority":"P1","author":"a","steps":[
                          {"action":"assertVisible","locator":"//*[@data-testid='ok']"}]}
                        """, NormalizationStatus.REJECTED),

                new GoldenCase("完全救不回来的垃圾输入", "[1,2,3]",
                        NormalizationStatus.REJECTED),

                new GoldenCase("空对象", "{}", NormalizationStatus.REJECTED)
        );
    }

    @Autowired
    NormalizationPipeline pipeline;

    @Autowired
    ValidationEngine validationEngine;

    @Autowired
    ObjectMapper objectMapper;

    private NormalizationResult normalize(String json) {
        return pipeline.normalize(objectMapper.readTree(json));
    }

    // ── 黄金用例 ────────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @MethodSource("goldenCases")
    @DisplayName("黄金用例集：每条输入的最终状态符合预期")
    void goldenCaseProducesExpectedStatus(GoldenCase testCase) {
        NormalizationResult result = normalize(testCase.input());

        assertThat(result.status())
                .as("诊断：%s", result.diagnostics())
                .isEqualTo(testCase.expected());
    }

    // ── ⭐ 契约测试：这是安全不变式的直接体现 ──────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @MethodSource("goldenCases")
    @DisplayName("⭐ 契约：normalize 判定为通过的结果，必须能独立通过 validate_case")
    void acceptedResultsAlwaysPassIndependentValidation(GoldenCase testCase) {
        NormalizationResult result = normalize(testCase.input());

        if (result.status() == NormalizationStatus.REJECTED) {
            return;   // 被拒的案例不承诺可通过校验
        }

        // 把 normalize 的产物拿去过独立的 validate —— 平台方入库前做的正是这件事
        ValidationReport independent =
                validationEngine.validate(objectMapper.valueToTree(result.normalizedCase()));

        assertThat(independent.rejected())
                .as("normalize 说通过、validate 说不通过 —— 这正是本服务承诺不会发生的事。诊断：%s",
                        independent.diagnostics())
                .isFalse();
    }

    @Test
    @DisplayName("⭐ 安全不变式：不存在「ACCEPTED 但含 ERROR 诊断」的输出")
    void acceptedNeverCarriesErrors() {
        goldenCases().forEach(testCase -> {
            NormalizationResult result = normalize(testCase.input());
            if (result.status() != NormalizationStatus.REJECTED) {
                assertThat(result.diagnostics())
                        .as("用例「%s」被判为 %s，却带有 ERROR 诊断", testCase.name(), result.status())
                        .noneMatch(d -> d.severity() == dev.kanashi.atp.mcp.domain.Severity.ERROR);
            }
        });
    }

    // ── ⭐ 零模型路径 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("⭐ 输入足够完整时，模型调用次数为 0")
    void completeInputRequiresNoModelCall() {
        NormalizationResult result = normalize(COMPLETE);

        assertThat(result.status()).isEqualTo(NormalizationStatus.ACCEPTED);
        assertThat(result.modelCalls())
                .as("完整输入不该触发任何模型调用")
                .isZero();
        assertThat(result.zeroModelPath()).isTrue();
        assertThat(result.gaps())
                .as("不该有需要模型填的空")
                .noneMatch(FieldGap::modelFillable);
    }

    @Test
    @DisplayName("黄金用例集中走零模型路径的比例 —— README 里给出的数字来自这里")
    void reportsZeroModelPathRatio() {
        long total = goldenCases().count();
        long zeroModel = goldenCases()
                .map(c -> normalize(c.input()))
                .filter(NormalizationResult::zeroModelPath)
                .count();

        // 断言只保证"确实存在完全不调模型的路径"，具体比例随用例集演进，
        // 打印出来供 README 引用，而不是把一个会变的数字焊死在断言里。
        System.out.printf("零模型路径：%d/%d 条黄金用例无需任何模型参与%n", zeroModel, total);
        assertThat(zeroModel).isPositive();
    }

    // ── 降级与幂等 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("被拒绝时仍返回规则已完成的部分 —— 规则做的 80% 不该被丢弃")
    void rejectedResultStillCarriesPartialWork() {
        NormalizationResult result = normalize("""
                {"title":"未完成的用例","module":"CART","priority":"P1","steps":[
                  {"action":"click","xpath":"//*[@data-testid='x']"}]}
                """);

        assertThat(result.status()).isEqualTo(NormalizationStatus.REJECTED);
        assertThat(result.normalizedCase()).isNotNull();
        assertThat(result.normalizedCase().moduleId())
                .as("规则已经解析出来的东西要留给平台方，不能因为拒绝就全丢")
                .isEqualTo("M003");
        assertThat(result.normalizedCase().steps().get(0).waitStrategy())
                .isEqualTo(dev.kanashi.atp.mcp.domain.WaitStrategy.CLICKABLE);
    }

    @Test
    @DisplayName("幂等：同一输入连跑三次，结果完全一致")
    void normalizationIsIdempotent() {
        // valueToTree 是 <T extends JsonNode> T，直接塞进 assertThat 会让重载解析歧义，
        // 显式声明成 JsonNode 即可
        JsonNode first = objectMapper.valueToTree(normalize(COMPLETE));
        JsonNode second = objectMapper.valueToTree(normalize(COMPLETE));
        JsonNode third = objectMapper.valueToTree(normalize(COMPLETE));

        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
    }

    @Test
    @DisplayName("REQUESTER 类缺口必须明确指出该由请求方补，而不是笼统地说校验失败")
    void requesterGapsAreExplicit() {
        NormalizationResult result = normalize("""
                {"title":"t","module":"M003","priority":"P1","author":"a","steps":[
                  {"action":"click"},
                  {"action":"assertVisible","locator":"//*[@data-testid='ok']"}]}
                """);

        assertThat(result.gaps())
                .anyMatch(g -> g.path().equals("steps[0].locator_value")
                        && g.fillability() == GapFillability.REQUESTER);
        assertThat(result.gaps().stream()
                .filter(g -> g.fillability() == GapFillability.REQUESTER)
                .findFirst().orElseThrow().hint())
                .as("要说清为什么本服务不代填，而不是只说缺字段")
                .contains("被测页面");
    }
}
