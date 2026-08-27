package dev.kanashi.atp.cli.model;

/**
 * 某个字段在特定 {@link Action} 下的约束强度。
 * <p>
 * 共享契约 §1.3 的表格里只有 ✓ / ✗ 两种标记，所以这里只有两个值 ——
 * 刻意不预留 {@code OPTIONAL}：契约表没有"可选"这一档，
 * 凭空加一档会让校验器多出一条永远走不到的分支。真需要时再加。
 */
public enum FieldRequirement {

    /** 契约表标 ✓：缺失即 ERROR，案例被拒。 */
    REQUIRED,

    /**
     * 契约表标 ✗：该 action 用不到这个字段。
     * <p>
     * 填了不会让执行器崩（多余字段会被忽略），所以校验时判 WARN 而非 ERROR ——
     * 但仍要报出来，因为它通常意味着上游 agent 误解了 action 语义，
     * 比如给 CLICK 填了 input_data，往往说明它其实想要的是 INPUT。
     */
    FORBIDDEN
}
