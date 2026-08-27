package dev.kanashi.atp.cli.model;

/**
 * 诊断分级。分级的意义在于<b>把"能不能入库"和"写得好不好"分开</b> ——
 * 全判 ERROR 会让服务过于挑剔而没人用，全判 WARN 则等于没有守门。
 */
public enum Severity {

    /** 违反硬约束，案例被 REJECTED。执行器读到这种案例会在运行时炸。 */
    ERROR,

    /** 可以入库，但有问题需要知情。对应 ACCEPTED_WITH_WARNINGS。 */
    WARN,

    /** 写法建议（如 STD-003 的定位器优先级），不影响入库也不影响执行。 */
    INFO
}
