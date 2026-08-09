package dev.kanashi.atp.mcp.tool;

import dev.kanashi.atp.mcp.pipeline.ValidationEngine;
import dev.kanashi.atp.mcp.pipeline.ValidationReport;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * 校验 tool。
 *
 * <h2>为什么它必须独立于 normalize 暴露</h2>
 * 平台方在入库前应当<b>再校验一次</b>（信任但验证）。把校验藏在 normalize 内部，
 * 平台方就只能选择"全信"或"自己再写一份挡板" —— 而自己再写一份，
 * 就意味着同一个规范存在两份实现，它们迟早会漂移。
 * <p>
 * 这里暴露的校验与 normalize 内部用的<b>是同一个 {@link ValidationEngine} 实例</b>，
 * 校验的也是同一种输入（规范化后的 JSON）。所以"normalize 说 ACCEPTED、
 * validate 说 REJECTED"这种情况在结构上就不可能发生，M2 的契约测试会持续证明这一点。
 * <p>
 * 另外，调用方不必是 agent —— CI 流水线、批量回填脚本、平台后端都能调它，
 * 它是纯规则、毫秒级、完全幂等的函数。
 */
@Component
public class AtpValidationTools {

    private final ValidationEngine validationEngine;
    private final ObjectMapper objectMapper;

    public AtpValidationTools(ValidationEngine validationEngine, ObjectMapper objectMapper) {
        this.validationEngine = validationEngine;
        this.objectMapper = objectMapper;
    }

    @McpTool(
            name = "atp_validate_case",
            title = "校验已规范化的测试案例",
            description = """
                    对一条**已经是 ATP 规范形态**的测试案例做完整校验，返回分级诊断与最终状态。\
                    校验项：JSON Schema（类型/枚举/长度/必填/范围）、module_id 外键、\
                    action 与 locator/input_data/expected 的契约、seq 连续性、\
                    至少一个断言步骤、以及定位器规范 STD-001/002/003。\
                    纯规则、毫秒级、完全幂等，不调用模型，也不修改任何内容。

                    字段名须为 snake_case（case_code / module_id / locator_value …）。\
                    如果你手上的案例形状还不确定，请先调用 atp_normalize_case 做规范化；\
                    本 tool 只负责判定，不做任何纠正。

                    平台方在入库前应当再调用一次本 tool 作最终守门。""",
            annotations = @McpTool.McpAnnotations(
                    title = "校验已规范化的测试案例",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false))
    public ValidationReport validateCase(
            @McpToolParam(description = "待校验的测试案例对象（ATP 规范形态，snake_case 字段名）",
                          required = true)
            Map<String, Object> testCase) {

        return validationEngine.validate(objectMapper.valueToTree(testCase));
    }
}
