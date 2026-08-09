package dev.kanashi.atp.mcp.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 规范化后的测试步骤（tc_step 一行）。
 *
 * @param stepId        平台生成，本服务不填（不碰 DB，见交接文档 §1 的边界）
 * @param seq           从 1 开始连续无跳号，L1 负责重排
 * @param action        决定了 locatorValue / inputData / expected 三个字段的契约，见 {@link Action}
 * @param locatorType   L1 可按 locatorValue 的形状推断
 * @param waitStrategy  NOT NULL，由 {@link Action#mandatedWaitStrategy()} 确定性填充
 * @param waitTimeoutSec 范围 1..120，默认 10
 * @param onFailure     默认 {@link OnFailure#ABORT}
 */
/*
 * NON_NULL 不只是为了输出好看，它让序列化结果与 schema 语义对齐：
 *  - step_id 恒为 null（平台生成），排除后就不会撞上 schema 的 additionalProperties:false
 *  - 必填字段若为 null，字段直接消失 → schema 报 required 缺失，正是我们想要的判定
 * 若改成输出 null，前者会变成"多了个不认识的字段"，后者会变成"类型不对"，
 * 两种诊断都指不到真正的问题上。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TestStep(
        @JsonProperty("step_id") String stepId,
        @JsonProperty("seq") Integer seq,
        @JsonProperty("action") Action action,
        @JsonProperty("locator_type") LocatorType locatorType,
        @JsonProperty("locator_value") String locatorValue,
        @JsonProperty("input_data") String inputData,
        @JsonProperty("expected") String expected,
        @JsonProperty("wait_strategy") WaitStrategy waitStrategy,
        @JsonProperty("wait_timeout_sec") Integer waitTimeoutSec,
        @JsonProperty("on_failure") OnFailure onFailure,
        @JsonProperty("description") String description) {

    /** tc_step.wait_timeout_sec 的 schema 默认值。 */
    public static final int DEFAULT_WAIT_TIMEOUT_SEC = 10;

    public static final int MIN_WAIT_TIMEOUT_SEC = 1;
    public static final int MAX_WAIT_TIMEOUT_SEC = 120;
}
