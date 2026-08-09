package dev.kanashi.atp.mcp.tool;

import dev.kanashi.atp.mcp.McpProtocolTestSupport;
import dev.kanashi.atp.mcp.domain.Action;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M1 的两个描述类 tool 的协议级测试。
 */
class AtpSchemaToolsProtocolTest extends McpProtocolTestSupport {

    // ── tool annotations ────────────────────────────────────────────────────────
    // 这组断言防的是 M0-D4：MCP 规范对 annotations 的默认值是
    // readOnlyHint=false / destructiveHint=true / idempotentHint=false / openWorldHint=true。
    // 忘记显式声明就会吃到这组默认值，把一个毫秒级的纯读取 tool 对外描述成破坏性操作 ——
    // 服务功能完全正常，只是调用方 agent 会因此要求用户确认甚至回避调用。
    // 这种错误没有任何报错，只能靠断言下发到线上的元数据来防。

    @ParameterizedTest(name = "{0} 必须对外声明为只读、幂等、无副作用")
    @ValueSource(strings = {
            "atp_describe_schema", "atp_list_modules", "atp_echo",
            // M2 新增的三个。本服务不碰 DB，所以它们全都是只读且无副作用的 ——
            // 包括 normalize：它只是把输入算成另一种形态返回，不改变任何外部状态。
            "atp_lint_locator", "atp_validate_case", "atp_normalize_case"})
    @DisplayName("所有 tool 的 annotations 不得吃 MCP 默认值")
    void readOnlyToolsDeclareCorrectAnnotations(String toolName) {
        JsonNode annotations = findTool(toolName).path("annotations");

        assertThat(annotations.isMissingNode())
                .as("%s 应当下发 annotations", toolName).isFalse();
        assertThat(annotations.path("readOnlyHint").asBoolean())
                .as("%s 不修改任何状态", toolName).isTrue();
        assertThat(annotations.path("destructiveHint").asBoolean())
                .as("%s 无破坏性 —— 这是默认值最容易咬人的一项", toolName).isFalse();
        assertThat(annotations.path("idempotentHint").asBoolean())
                .as("%s 重复调用无额外影响", toolName).isTrue();
        assertThat(annotations.path("openWorldHint").asBoolean())
                .as("%s 数据全部来自本进程，不查 DB 不出网", toolName).isFalse();
    }

    // ── atp_list_modules ────────────────────────────────────────────────────────

    @Test
    @DisplayName("list_modules 返回完整的 8 条模块字典，字段名为 snake_case")
    void listModulesReturnsFullDictionary() {
        JsonNode payload = callTool("atp_list_modules", "{}");

        assertThat(payload.path("count").asInt()).isEqualTo(8);
        JsonNode modules = payload.path("modules");
        assertThat(modules.size()).isEqualTo(8);

        JsonNode cart = null;
        for (JsonNode m : modules) {
            if ("M003".equals(m.path("module_id").asString())) {
                cart = m;
            }
        }
        assertThat(cart).as("M003 应存在").isNotNull();
        // 字段名必须与 DB 列名/schema 一致，调用方不该在两种命名风格间做翻译
        assertThat(cart.path("module_code").asString()).isEqualTo("CART");
        assertThat(cart.path("module_name").asString()).contains("カート");
    }

    @Test
    @DisplayName("list_modules 明确告知这是全集 —— 防模型按命名规律推断新 module_id")
    void listModulesStatesItIsExhaustive() {
        JsonNode payload = callTool("atp_list_modules", "{}");
        assertThat(payload.path("note").asString()).isNotBlank();
    }

    // ── atp_describe_schema ─────────────────────────────────────────────────────

    @Test
    @DisplayName("describe_schema 返回可用的目标 schema 本体")
    void describeSchemaReturnsTargetSchema() {
        JsonNode payload = callTool("atp_describe_schema", "{}");

        assertThat(payload.path("platform").asString()).isEqualTo("atp");
        JsonNode schema = payload.path("target_schema");
        assertThat(schema.path("$id").asString()).isEqualTo("atp://schema/tc_case");
        assertThat(schema.path("required").isArray()).isTrue();
        assertThat(schema.path("$defs").path("step").isObject())
                .as("步骤定义必须随 schema 一起给出").isTrue();
    }

    @Test
    @DisplayName("枚举字典覆盖全部枚举，且与 Java 枚举一致（防手写字典漂移）")
    void enumDictionaryMatchesJavaEnums() {
        JsonNode enums = callTool("atp_describe_schema", "{}").path("enums");

        assertThat(enums.path("action").size())
                .as("action 枚举个数应与 Action 枚举一致")
                .isEqualTo(Action.values().length);
        assertThat(enums.propertyNames())
                .contains("priority", "status", "browser", "action",
                        "locator_type", "wait_strategy", "on_failure");
    }

    @Test
    @DisplayName("action_contracts 完整暴露契约表 —— 调用方据此在生成阶段就对齐")
    void actionContractsExposeTheContractTable() {
        JsonNode contracts = callTool("atp_describe_schema", "{}").path("action_contracts");
        assertThat(contracts.size()).isEqualTo(Action.values().length);

        JsonNode click = contractOf(contracts, "CLICK");
        assertThat(click.path("locator").asString()).isEqualTo("REQUIRED");
        assertThat(click.path("input_data").asString()).isEqualTo("FORBIDDEN");
        assertThat(click.path("wait_strategy").asString())
                .as("STD-005：CLICK 必须 CLICKABLE").isEqualTo("CLICKABLE");

        JsonNode assertText = contractOf(contracts, "ASSERT_TEXT");
        assertThat(assertText.path("expected").asString()).isEqualTo("REQUIRED");
        assertThat(assertText.path("is_assertion").asBoolean()).isTrue();

        JsonNode sleep = contractOf(contracts, "SLEEP");
        assertThat(sleep.path("forbidden").asBoolean())
                .as("STD-004：SLEEP 被明令禁止").isTrue();
    }

    @Test
    @DisplayName("ASSERT_NOT_EXIST 与 STD-006 的语义冲突被显式暴露，而非悄悄处理")
    void assertNotExistDeviationIsDisclosed() {
        JsonNode contracts = callTool("atp_describe_schema", "{}").path("action_contracts");

        JsonNode notExist = contractOf(contracts, "ASSERT_NOT_EXIST");
        assertThat(notExist.path("wait_strategy").asString())
                .as("等一个不该出现的元素变可见必然超时，故取 NONE")
                .isEqualTo("NONE");
        assertThat(notExist.path("deviation_note").asString())
                .as("偏离规范字面要求时必须说明理由，不能静默")
                .isNotBlank();

        // 且这是唯一一处偏离 —— 偏离越多，这个字段的警示作用越被稀释
        long deviations = 0;
        for (JsonNode c : contracts) {
            if (!c.path("deviation_note").asString("").isBlank()) {
                deviations++;
            }
        }
        assertThat(deviations).as("目前应当只有 ASSERT_NOT_EXIST 一处偏离").isEqualTo(1);
    }

    @Test
    @DisplayName("规范摘要与平台生成字段一并返回，调用方才知道哪些字段不该自己填")
    void describeSchemaExposesStandardsAndPlatformAssignedFields() {
        JsonNode payload = callTool("atp_describe_schema", "{}");

        assertThat(payload.path("standards").size()).isEqualTo(8);
        assertThat(payload.path("platform_assigned_fields").toString())
                .contains("case_id").contains("created_at");
        assertThat(payload.path("guidance").size()).isGreaterThan(0);
    }

    @Test
    @DisplayName("两个 tool 都是幂等的：同样的调用返回完全相同的结果")
    void toolsAreIdempotent() {
        assertThat(callTool("atp_list_modules", "{}"))
                .isEqualTo(callTool("atp_list_modules", "{}"));
        assertThat(callTool("atp_describe_schema", "{}"))
                .isEqualTo(callTool("atp_describe_schema", "{}"));
    }

    private static JsonNode contractOf(JsonNode contracts, String action) {
        for (JsonNode c : contracts) {
            if (action.equals(c.path("action").asString())) {
                return c;
            }
        }
        throw new AssertionError("action_contracts 中缺少 " + action);
    }
}
