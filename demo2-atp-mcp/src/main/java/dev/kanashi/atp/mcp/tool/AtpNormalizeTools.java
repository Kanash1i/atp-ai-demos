package dev.kanashi.atp.mcp.tool;

import dev.kanashi.atp.mcp.pipeline.NormalizationPipeline;
import dev.kanashi.atp.mcp.pipeline.NormalizationResult;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * 规范化主入口。薄层，只做参数转换 —— 业务全在 {@link NormalizationPipeline}。
 */
@Component
public class AtpNormalizeTools {

    private final NormalizationPipeline pipeline;
    private final ObjectMapper objectMapper;

    public AtpNormalizeTools(NormalizationPipeline pipeline, ObjectMapper objectMapper) {
        this.pipeline = pipeline;
        this.objectMapper = objectMapper;
    }

    @McpTool(
            name = "atp_normalize_case",
            title = "规范化测试案例",
            description = """
                    把任意形状的测试案例转换成 ATP 平台可入库的规范形态，并给出分级诊断、\
                    每个字段的来源(provenance)，以及未能填上的空(gaps)。

                    输入可以很随意：字段名支持中日英别名（title/标题/タイトル、steps/操作步骤/手順 等），\
                    动作名同样支持多语言（click/点击/クリック → CLICK），外层可以包一层信封。

                    返回中的 model_calls 表示本次实际调用模型的次数 —— 输入足够完整时为 0，\
                    整个过程纯规则、毫秒级、结果完全确定。

                    ⚠️ 有些字段本服务**不会**替你推断：locator_value（模型没见过被测页面，\
                    编出来的选择器会指向不存在的元素）、expected（模型不知道正确结果）、\
                    input_data、author。这些缺失会直接导致 REJECTED 并在 gaps 中标注 REQUESTER。

                    被拒绝时 normalized_case 仍会返回规则已完成的部分，可补齐后重新提交。""",
            annotations = @McpTool.McpAnnotations(
                    title = "规范化测试案例",
                    // 不碰 DB、不修改任何外部状态
                    readOnlyHint = true,
                    destructiveHint = false,
                    // 无副作用，重复调用安全
                    idempotentHint = true,
                    // M2 为纯规则、全本地。M3 接入 LLM 后这一项应改为 true —— 届时会真的出网
                    openWorldHint = false))
    public NormalizationResult normalizeCase(
            @McpToolParam(description = "待规范化的测试案例，形状不限", required = true)
            Map<String, Object> testCase) {

        return pipeline.normalize(objectMapper.valueToTree(testCase));
    }
}
