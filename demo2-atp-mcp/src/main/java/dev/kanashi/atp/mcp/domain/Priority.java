package dev.kanashi.atp.mcp.domain;

/**
 * 案例优先级（tc_case.priority，NOT NULL）。
 * <p>
 * ⚠️ 这是最容易被模型"合理地猜错"的字段之一：它没有可从案例内容推导的确定性规则，
 * 模型只能按语义猜。所以 L3 补出来的 priority 一律带 confidence，
 * 由平台方决定 confidence 低于阈值时是否进人工复核队列（交接文档 §4）。
 */
public enum Priority {
    P0,
    P1,
    P2,
    P3
}
