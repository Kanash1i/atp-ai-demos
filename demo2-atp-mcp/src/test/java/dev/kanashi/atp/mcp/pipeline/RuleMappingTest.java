package dev.kanashi.atp.mcp.pipeline;

import dev.kanashi.atp.mcp.domain.Action;
import dev.kanashi.atp.mcp.domain.Browser;
import dev.kanashi.atp.mcp.domain.CaseStatus;
import dev.kanashi.atp.mcp.domain.Diagnostic;
import dev.kanashi.atp.mcp.domain.LocatorType;
import dev.kanashi.atp.mcp.domain.NormalizedCase;
import dev.kanashi.atp.mcp.domain.Severity;
import dev.kanashi.atp.mcp.domain.TestStep;
import dev.kanashi.atp.mcp.domain.WaitStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L0 + L1 的行为验证：把一坨"形状不对但意思清楚"的输入，规整成确定性的结果。
 * <p>
 * 用 {@code webEnvironment = NONE} —— 这两层是纯计算，不需要起 web 容器。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RuleMappingTest {

    @Autowired
    EnvelopeParser envelopeParser;

    @Autowired
    RuleMapper ruleMapper;

    @Autowired
    ObjectMapper objectMapper;

    private MappedCase run(String rawJson) {
        return ruleMapper.map(envelopeParser.parse(objectMapper.readTree(rawJson)));
    }

    private static boolean hasCode(MappedCase mapped, String code) {
        return mapped.diagnostics().stream().anyMatch(d -> d.code().equals(code));
    }

    @Test
    @DisplayName("中日英混杂的字段名与动作名都能归一 —— 这一整段没有任何模型参与")
    void normalizesMixedLanguageInput() {
        MappedCase mapped = run("""
                {
                  "テストケース": {
                    "タイトル": "カート追加の確認",
                    "モジュール": "CART",
                    "優先度": "p1",
                    "担当者": "yamada",
                    "手順": [
                      {"操作": "打开", "value": "https://example.test/cart"},
                      {"action": "クリック", "xpath": "//button[@data-testid='add']"},
                      {"type": "assertText", "セレクタ": "#cart-count", "期待値": "1"}
                    ]
                  }
                }
                """);

        NormalizedCase c = mapped.caseData();
        assertThat(c.title()).isEqualTo("カート追加の確認");
        assertThat(c.moduleId()).as("module_code CART 应被解析为 M003").isEqualTo("M003");
        assertThat(c.author()).isEqualTo("yamada");

        assertThat(c.steps()).hasSize(3);
        assertThat(c.steps().get(0).action()).isEqualTo(Action.OPEN_URL);
        assertThat(c.steps().get(1).action()).isEqualTo(Action.CLICK);
        assertThat(c.steps().get(2).action()).isEqualTo(Action.ASSERT_TEXT);
        assertThat(c.steps().get(2).expected()).isEqualTo("1");
    }

    @Test
    @DisplayName("外层信封按结构特征剥掉，不依赖 case/data/payload 之类的词表")
    void unwrapsArbitraryEnvelope() {
        MappedCase mapped = run("""
                {"payload": {"wrapper": {
                   "name": "登录成功",
                   "steps": [{"op": "click", "locator": "#login"}]}}}
                """);
        assertThat(mapped.caseData().title()).isEqualTo("登录成功");
        assertThat(mapped.caseData().steps()).hasSize(1);
    }

    @Test
    @DisplayName("wait_strategy 由 action 强制决定，请求方给错会被纠正并 WARN")
    void waitStrategyIsMandatedNotSuggested() {
        MappedCase mapped = run("""
                {"title":"t","steps":[
                  {"action":"click","locator":"#a","wait":"PRESENCE"},
                  {"action":"assertVisible","locator":"#b"}
                ]}
                """);

        assertThat(mapped.caseData().steps().get(0).waitStrategy())
                .as("STD-005：CLICK 必须 CLICKABLE，不接受覆盖")
                .isEqualTo(WaitStrategy.CLICKABLE);
        assertThat(mapped.caseData().steps().get(1).waitStrategy())
                .isEqualTo(WaitStrategy.VISIBLE);

        assertThat(hasCode(mapped, DiagnosticCodes.RULE_WAIT_STRATEGY_CORRECTED))
                .as("纠正必须出声，静默纠正等于让请求方永远学不会")
                .isTrue();
    }

    @Test
    @DisplayName("ASSERT_NOT_EXIST 的规范偏离被显式报出（M1-D2）")
    void assertNotExistDeviationIsReported() {
        MappedCase mapped = run("""
                {"title":"t","steps":[{"action":"assertNotExist","locator":"#gone"}]}
                """);

        assertThat(mapped.caseData().steps().get(0).waitStrategy()).isEqualTo(WaitStrategy.NONE);
        assertThat(hasCode(mapped, DiagnosticCodes.RULE_WAIT_STRATEGY_DEVIATION)).isTrue();
    }

    @Test
    @DisplayName("locator_type 只按无歧义的形状推断，推不出就留空交给 L2")
    void infersLocatorTypeConservatively() {
        MappedCase mapped = run("""
                {"title":"t","steps":[
                  {"action":"click","locator":"//div[@id='a']"},
                  {"action":"click","locator":"#submit"},
                  {"action":"click","locator":"username"}
                ]}
                """);

        assertThat(mapped.caseData().steps().get(0).locatorType()).isEqualTo(LocatorType.XPATH);
        assertThat(mapped.caseData().steps().get(1).locatorType()).isEqualTo(LocatorType.CSS);
        assertThat(mapped.caseData().steps().get(2).locatorType())
                .as("ID/NAME/LINK_TEXT 的值都是普通字符串，没有形状能区分 —— 不猜")
                .isNull();
    }

    @Test
    @DisplayName("seq 按数组顺序重排为 1..n，乱序输入会被提示")
    void resequencesSteps() {
        MappedCase mapped = run("""
                {"title":"t","steps":[
                  {"seq":5,"action":"click","locator":"#a"},
                  {"seq":2,"action":"click","locator":"#b"}
                ]}
                """);

        assertThat(mapped.caseData().steps()).extracting(TestStep::seq).containsExactly(1, 2);
        assertThat(hasCode(mapped, DiagnosticCodes.RULE_SEQ_RESEQUENCED)).isTrue();
    }

    @Test
    @DisplayName("默认值来自 schema，且来源被记为 DEFAULT")
    void appliesSchemaDefaults() {
        MappedCase mapped = run("""
                {"title":"t","steps":[{"action":"click","locator":"#a"}]}
                """);

        NormalizedCase c = mapped.caseData();
        assertThat(c.status()).isEqualTo(CaseStatus.DRAFT);
        assertThat(c.browser()).isEqualTo(Browser.CHROME);
        assertThat(c.timeoutSec()).isEqualTo(NormalizedCase.DEFAULT_TIMEOUT_SEC);
        assertThat(c.steps().get(0).waitTimeoutSec()).isEqualTo(TestStep.DEFAULT_WAIT_TIMEOUT_SEC);

        assertThat(mapped.provenance().get("browser").source().name()).isEqualTo("DEFAULT");
        assertThat(mapped.provenance().get("title").source().name()).isEqualTo("INPUT");
    }

    @Test
    @DisplayName("超长字段判 ERROR 且**不截断** —— 截断会静默损坏语义")
    void tooLongValuesAreRejectedNotTruncated() {
        String longTitle = "あ".repeat(NormalizedCase.MAX_TITLE_LENGTH + 10);
        MappedCase mapped = run("""
                {"title":"%s","steps":[{"action":"click","locator":"#a"}]}
                """.formatted(longTitle));

        assertThat(mapped.caseData().title())
                .as("原值必须原样保留，让请求方看到自己写了什么")
                .hasSize(longTitle.length());
        assertThat(mapped.diagnostics())
                .anyMatch(d -> d.code().equals(DiagnosticCodes.RULE_VALUE_TOO_LONG)
                        && d.severity() == Severity.ERROR);
    }

    @Test
    @DisplayName("SLEEP 必须能被识别 —— 识别得出来才能给出指导性诊断")
    void sleepIsRecognizedSoItCanBeDiagnosed() {
        MappedCase mapped = run("""
                {"title":"t","steps":[{"action":"sleep","value":"3"}]}
                """);

        assertThat(mapped.caseData().steps().get(0).action())
                .as("若识别不出，只能报『无法识别的 action』，把规范问题降级成无用信息")
                .isEqualTo(Action.SLEEP);
    }

    @Test
    @DisplayName("不认识的 module 值原样保留，交给 L4 外键校验拒绝 —— L1 不替它挑一个最接近的")
    void unknownModuleIsKeptForForeignKeyCheck() {
        MappedCase mapped = run("""
                {"title":"t","module":"M999","steps":[{"action":"click","locator":"#a"}]}
                """);
        assertThat(mapped.caseData().moduleId()).isEqualTo("M999");
    }

    @Test
    @DisplayName("同一字段被多个别名重复提供且取值不同时，必须报出而不是静默取一个")
    void duplicateAliasesAreReported() {
        MappedCase mapped = run("""
                {"title":"A","タイトル":"B","steps":[{"action":"click","locator":"#a"}]}
                """);
        assertThat(mapped.diagnostics().stream()
                .anyMatch(d -> d.code().equals(DiagnosticCodes.ENVELOPE_DUPLICATE_FIELD)))
                .isTrue();
    }

    @Test
    @DisplayName("未识别的字段被忽略但会报出，不静默吞掉")
    void unknownFieldsAreReported() {
        MappedCase mapped = run("""
                {"title":"t","titel":"typo","steps":[{"action":"click","locator":"#a"}]}
                """);
        assertThat(mapped.diagnostics().stream()
                .anyMatch(d -> d.code().equals(DiagnosticCodes.ENVELOPE_UNKNOWN_FIELD)
                        && "titel".equals(d.path())))
                .isTrue();
    }
}
