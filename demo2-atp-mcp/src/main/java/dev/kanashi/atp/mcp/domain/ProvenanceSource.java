package dev.kanashi.atp.mcp.domain;

/**
 * 字段值的来源。交接文档 §4 —— <b>这是让平台方敢用本服务的关键</b>。
 * <p>
 * 没有 provenance，平台方只能"全信"或"全不信"，这个服务就没法在生产用。
 * 有了它，平台方可以定出这样的策略：
 * <i>"MODEL 来源且 confidence &lt; 0.8 的字段，案例入库为 DRAFT 并进人工复核队列"</i>。
 */
public enum ProvenanceSource {

    /** 请求方原样提供，未被改动。直接信任。 */
    INPUT,

    /** 规则推导，附规则编号。可信且**可审计** —— 规则是明文的，能复查。 */
    RULE,

    /** schema 默认值。可信。 */
    DEFAULT,

    /** 模型推断，附 confidence 与 reason。⚠️ 平台方可配置是否需人工确认。 */
    MODEL
}
