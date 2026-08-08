package dev.kanashi.atp.mcp.profile;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 一条平台规范的摘要，随 {@code atp_describe_schema} 返回给调用方。
 * <p>
 * <b>为什么要把规范暴露给调用方 agent</b>：让它在<i>生成阶段</i>就知道该产出什么，
 * 从源头降低错误率 —— 这是"左移"。只提供 normalize 等于放任上游乱生成、下游收拾。
 *
 * @param id          规范编号，如 {@code STD-001}
 * @param statement   规范内容
 * @param enforcement 本服务如何执行它，见 {@link Enforcement}
 */
public record StandardRule(
        @JsonProperty("id") String id,
        @JsonProperty("statement") String statement,
        @JsonProperty("enforcement") Enforcement enforcement) {
}
