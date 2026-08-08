package dev.kanashi.atp.mcp.profile;

/**
 * 一条规范在流水线里的执行方式。
 * <p>
 * 这个区分直接体现了本 demo 的核心主张「让模型少做事」：
 * 标 {@link #AUTO_FILL} 的规范根本不会走到模型那里 —— 规则能算出唯一答案的字段，
 * 交给模型只是徒增不确定性和成本。
 */
public enum Enforcement {

    /** 校验器判 ERROR，案例被拒。 */
    ERROR,

    /** 校验器判 WARN，可入库但标记。 */
    WARN,

    /** 校验器判 INFO，仅作写法建议。 */
    INFO,

    /** 由 L1 规则确定性填充，无需模型参与，也无需请求方提供。 */
    AUTO_FILL
}
