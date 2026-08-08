package dev.kanashi.atp.mcp.domain;

/** 步骤失败后的处理方式（tc_step.on_failure，默认 ABORT）。 */
public enum OnFailure {

    /** 中止整条案例。默认值 —— 前置步骤失败后继续跑，后续断言的结果没有意义。 */
    ABORT,

    /** 记录失败但继续执行后续步骤。 */
    CONTINUE,

    /** 重试该步骤。 */
    RETRY
}
