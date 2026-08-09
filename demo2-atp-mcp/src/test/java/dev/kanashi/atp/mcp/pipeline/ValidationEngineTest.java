package dev.kanashi.atp.mcp.pipeline;

import dev.kanashi.atp.mcp.domain.NormalizationStatus;
import dev.kanashi.atp.mcp.domain.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L4 校验的行为验证。
 * <p>
 * 每个用例都从「一条完全合法的案例」出发，只破坏一处 —— 这样断言失败时，
 * 能确定问题就出在被破坏的那一处，而不是别的地方顺带引发的。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ValidationEngineTest {

    /** 一条完全合规的案例：定位器都用 data-testid，seq 连续，有断言，模块真实存在。 */
    private static final String VALID_CASE = """
            {
              "case_code": "ATP-CART-0001",
              "title": "カートに商品を追加できる",
              "module_id": "M003",
              "priority": "P1",
              "author": "yamada",
              "status": "DRAFT",
              "browser": "CHROME",
              "timeout_sec": 30,
              "steps": [
                {"seq":1,"action":"OPEN_URL","input_data":"https://example.test/cart",
                 "wait_strategy":"NONE","wait_timeout_sec":10,"on_failure":"ABORT"},
                {"seq":2,"action":"CLICK","locator_type":"XPATH",
                 "locator_value":"//*[@data-testid='add-to-cart']",
                 "wait_strategy":"CLICKABLE","wait_timeout_sec":10,"on_failure":"ABORT"},
                {"seq":3,"action":"ASSERT_TEXT","locator_type":"CSS",
                 "locator_value":"[data-testid='cart-count']","expected":"1",
                 "wait_strategy":"VISIBLE","wait_timeout_sec":10,"on_failure":"ABORT"}
              ]
            }
            """;

    @Autowired
    ValidationEngine engine;

    @Autowired
    ObjectMapper objectMapper;

    private ValidationReport validate(String json) {
        return engine.validate(objectMapper.readTree(json));
    }

    private static boolean has(ValidationReport r, String code) {
        return r.diagnostics().stream().anyMatch(d -> d.code().equals(code));
    }

    @Test
    @DisplayName("完全合规的案例：ACCEPTED，零诊断")
    void validCaseIsAccepted() {
        ValidationReport report = validate(VALID_CASE);

        assertThat(report.status())
                .as("诊断详情：%s", report.diagnostics())
                .isEqualTo(NormalizationStatus.ACCEPTED);
        assertThat(report.diagnostics()).isEmpty();
    }

    @Test
    @DisplayName("⭐ 编造的 module_id 能过 schema，但过不了外键 —— 这是防模型编造的关键一条")
    void fabricatedModuleIdIsRejected() {
        ValidationReport report = validate(VALID_CASE.replace("\"M003\"", "\"M009\""));

        assertThat(has(report, DiagnosticCodes.FK_MODULE_NOT_FOUND)).isTrue();
        assertThat(report.status()).isEqualTo(NormalizationStatus.REJECTED);

        // M009 是字符串、长度合规、格式也像模像样 —— schema 层面挑不出任何毛病
        assertThat(has(report, DiagnosticCodes.SCHEMA_VIOLATION))
                .as("schema 对 M009 无话可说，只有对照字典才拦得住")
                .isFalse();
    }

    @Test
    @DisplayName("action 契约：CLICK 缺 locator_value 被拒")
    void missingRequiredLocatorIsRejected() {
        String broken = VALID_CASE.replace(
                "\"locator_value\":\"//*[@data-testid='add-to-cart']\",", "");
        ValidationReport report = validate(broken);

        assertThat(has(report, DiagnosticCodes.CONTRACT_LOCATOR_REQUIRED)).isTrue();
        assertThat(report.rejected()).isTrue();
    }

    @Test
    @DisplayName("action 契约：给 CLICK 填了 input_data 判 WARN 而非 ERROR，但必须报出")
    void forbiddenFieldIsWarnedNotRejected() {
        String odd = VALID_CASE.replace(
                "{\"seq\":2,\"action\":\"CLICK\"",
                "{\"seq\":2,\"action\":\"CLICK\",\"input_data\":\"abc\"");
        ValidationReport report = validate(odd);

        assertThat(has(report, DiagnosticCodes.CONTRACT_FIELD_FORBIDDEN)).isTrue();
        assertThat(report.status())
                .as("多余字段不会让执行器崩，但往往说明 action 选错了")
                .isEqualTo(NormalizationStatus.ACCEPTED_WITH_WARNINGS);
    }

    @Test
    @DisplayName("STD-004：SLEEP 被拒")
    void sleepIsRejected() {
        String withSleep = VALID_CASE.replace(
                "{\"seq\":1,\"action\":\"OPEN_URL\",\"input_data\":\"https://example.test/cart\",",
                "{\"seq\":1,\"action\":\"SLEEP\",\"input_data\":\"3\",");
        ValidationReport report = validate(withSleep);

        assertThat(has(report, DiagnosticCodes.STD_004_SLEEP_FORBIDDEN)).isTrue();
        assertThat(report.rejected()).isTrue();
    }

    @Test
    @DisplayName("STD-008：没有任何断言步骤的案例被拒")
    void caseWithoutAssertionIsRejected() {
        String noAssertion = VALID_CASE.replace("ASSERT_TEXT", "WAIT_FOR")
                .replace("\"expected\":\"1\",", "");
        ValidationReport report = validate(noAssertion);

        assertThat(has(report, DiagnosticCodes.STD_008_NO_ASSERTION)).isTrue();
        assertThat(report.rejected()).isTrue();
    }

    @Test
    @DisplayName("STD-001：绝对路径 XPath 被拒")
    void absoluteXPathIsRejected() {
        String absolute = VALID_CASE.replace(
                "//*[@data-testid='add-to-cart']", "/html/body/div[3]/button");
        ValidationReport report = validate(absolute);

        assertThat(has(report, DiagnosticCodes.STD_001_ABSOLUTE_XPATH)).isTrue();
        assertThat(report.rejected()).isTrue();
    }

    @Test
    @DisplayName("STD-002：依赖框架自动生成的 id 判 WARN，可入库但需知情")
    void dynamicIdIsWarned() {
        String dynamic = VALID_CASE.replace(
                "//*[@data-testid='add-to-cart']", "//*[@id='ext-gen1234']");
        ValidationReport report = validate(dynamic);

        assertThat(has(report, DiagnosticCodes.STD_002_DYNAMIC_ID)).isTrue();
        assertThat(report.errorCount()).isZero();
    }

    @Test
    @DisplayName("seq 跳号被拒 —— 跳号往往是漏了一步的痕迹")
    void nonContiguousSeqIsRejected() {
        ValidationReport report = validate(VALID_CASE.replace("\"seq\":3", "\"seq\":5"));

        assertThat(has(report, DiagnosticCodes.SEQ_NOT_CONTIGUOUS)).isTrue();
        assertThat(report.rejected()).isTrue();
    }

    @Test
    @DisplayName("必填字段缺失由 JSON Schema 拦下")
    void missingRequiredFieldIsRejected() {
        String noTitle = VALID_CASE.replace("\"title\": \"カートに商品を追加できる\",", "");
        ValidationReport report = validate(noTitle);

        assertThat(has(report, DiagnosticCodes.SCHEMA_VIOLATION)).isTrue();
        assertThat(report.rejected()).isTrue();
    }

    @Test
    @DisplayName("非法枚举值由 JSON Schema 拦下")
    void invalidEnumIsRejected() {
        ValidationReport report = validate(VALID_CASE.replace("\"P1\"", "\"URGENT\""));

        assertThat(has(report, DiagnosticCodes.SCHEMA_VIOLATION)).isTrue();
        assertThat(report.rejected()).isTrue();
    }

    @Test
    @DisplayName("超出范围的数值由 JSON Schema 拦下")
    void outOfRangeValueIsRejected() {
        ValidationReport report = validate(VALID_CASE.replace("\"timeout_sec\": 30", "\"timeout_sec\": 9999"));

        assertThat(has(report, DiagnosticCodes.SCHEMA_VIOLATION)).isTrue();
        assertThat(report.rejected()).isTrue();
    }

    @Test
    @DisplayName("status 完全由诊断推导：有 ERROR 必然 REJECTED，没有代码路径能绕过")
    void statusIsDerivedFromDiagnostics() {
        ValidationReport report = validate(VALID_CASE.replace("\"M003\"", "\"M999\""));

        assertThat(report.errorCount()).isPositive();
        assertThat(report.status()).isEqualTo(NormalizationStatus.REJECTED);
        assertThat(report.diagnostics())
                .anyMatch(d -> d.severity() == Severity.ERROR);
    }

    @Test
    @DisplayName("非对象输入不会抛异常，而是返回带诊断的 REJECTED")
    void nonObjectInputIsRejectedGracefully() {
        ValidationReport report = validate("[1,2,3]");

        assertThat(report.rejected()).isTrue();
        assertThat(report.diagnostics()).isNotEmpty();
    }
}
