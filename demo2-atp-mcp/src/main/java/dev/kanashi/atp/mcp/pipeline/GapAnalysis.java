package dev.kanashi.atp.mcp.pipeline;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * L2 的产物：还缺什么、该找谁补。
 *
 * @param gaps           全部缺口
 * @param modelGapSchema <b>只含 MODEL 类缺口</b>的子 JSON Schema，供 L3 约束模型输出。
 *                       无 MODEL 缺口时为 null
 *
 * <h2>⭐ zeroModelPath 是本 demo 的一个卖点，也是可被测试断言的事实</h2>
 * 请求方给的案例足够完整时，这里返回 true，整条链路直接跳到 L4 ——
 * <b>模型调用次数为 0</b>，毫秒级、零成本、结果完全确定且可复现。
 * 多数"AI 服务"是无脑每次都调模型；能说清"我的服务在 X% 的输入上根本不调 LLM"，
 * 体现的是工程判断力，而不是省钱技巧。
 */
public record GapAnalysis(
        @JsonProperty("gaps") List<FieldGap> gaps,
        @JsonProperty("model_gap_schema") JsonNode modelGapSchema) {

    /** 有缺口只能由请求方补 —— 模型补即为编造，直接拒绝。 */
    public boolean blockedByRequester() {
        return gaps.stream().anyMatch(g -> g.fillability() == GapFillability.REQUESTER);
    }

    /** 存在需要模型填的空。 */
    public boolean requiresModel() {
        return gaps.stream().anyMatch(FieldGap::modelFillable);
    }

    /** ⭐ 无需任何模型参与即可完成规范化。 */
    public boolean zeroModelPath() {
        return !requiresModel();
    }

    public List<FieldGap> modelGaps() {
        return gaps.stream().filter(FieldGap::modelFillable).toList();
    }

    public List<FieldGap> requesterGaps() {
        return gaps.stream()
                .filter(g -> g.fillability() == GapFillability.REQUESTER)
                .toList();
    }
}
