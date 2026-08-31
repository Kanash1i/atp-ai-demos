package com.atp.agent.intent;

/**
 * 路由结论。
 *
 * <p>{@code layer} 与 {@code reason} 不是给用户看的，是**给我自己排查用的** ——
 * 路由错了的时候，"哪一层判的、凭什么判的"决定了该去改规则表、调阈值，还是改分类 prompt。
 * 不记这两个字段，路由就成了黑盒，只能靠重跑碰运气。
 *
 * @param layer L1 规则 / L2 向量 / L3 模型
 * @param score L2 的余弦相似度；L1 与 L3 没有分数，记 -1
 */
public record RouteResult(
        IntentCategory intent,
        String layer,
        double score,
        String reason) {

    public static RouteResult l1(IntentCategory intent, String hit) {
        return new RouteResult(intent, "L1", -1, "命中规则：" + hit);
    }

    public static RouteResult l2(IntentCategory intent, double score, String sample) {
        return new RouteResult(intent, "L2", score, "近似样例：" + sample);
    }

    public static RouteResult l3(IntentCategory intent, String raw) {
        return new RouteResult(intent, "L3", -1, "模型判定：" + raw);
    }
}
