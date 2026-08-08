package dev.kanashi.atp.mcp.domain;

/**
 * 规范化结果的总状态。
 * <p>
 * <b>这三个值承载了本 demo 的核心安全不变式</b>（交接文档 §7.3）：
 * <blockquote>
 * 要么 ACCEPTED 且完全通过校验，要么 REJECTED 且带诊断。
 * <b>永远不存在「ACCEPTED 但违反 schema」的输出。</b>
 * </blockquote>
 * 这就是"执行器对规范化过程完全无感知"的形式化表述，M5 用属性测试来证明它。
 */
public enum NormalizationStatus {

    /** 完全通过校验，无 ERROR 无 WARN。 */
    ACCEPTED,

    /** 通过校验但有 WARN，可入库，建议知情后处理。 */
    ACCEPTED_WITH_WARNINGS,

    /**
     * 存在 ERROR，拒绝。
     * <p>
     * ⚠️ 宁可拒绝，不可错入 —— <b>绝不猜一个值蒙混过去</b>。
     * 一个编造的 module_id 混进库，故障会在几天后从执行器那边冒出来，
     * 那时排查成本是现在直接拒绝的几十倍。
     */
    REJECTED
}
