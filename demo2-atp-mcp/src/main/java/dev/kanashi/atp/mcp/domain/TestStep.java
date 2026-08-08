package dev.kanashi.atp.mcp.domain;

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
public record TestStep(
        String stepId,
        Integer seq,
        Action action,
        LocatorType locatorType,
        String locatorValue,
        String inputData,
        String expected,
        WaitStrategy waitStrategy,
        Integer waitTimeoutSec,
        OnFailure onFailure,
        String description) {

    /** tc_step.wait_timeout_sec 的 schema 默认值。 */
    public static final int DEFAULT_WAIT_TIMEOUT_SEC = 10;

    public static final int MIN_WAIT_TIMEOUT_SEC = 1;
    public static final int MAX_WAIT_TIMEOUT_SEC = 120;
}
