package dev.kanashi.atp.mcp.domain;

import static dev.kanashi.atp.mcp.domain.FieldRequirement.FORBIDDEN;
import static dev.kanashi.atp.mcp.domain.FieldRequirement.REQUIRED;

/**
 * 步骤动作（tc_step.action），共享契约 §1.3。
 * <p>
 * <b>这个枚举是本 demo 的一个关键设计：把契约表编码进类型，而不是散在校验器的 if-else 里。</b>
 * L1（规则填充）和 L4（校验）读的是同一份声明 —— 想让两边对不上都做不到。
 * 契约表改一行，两层同时跟着变；而 if-else 版本永远会漏掉一处。
 * <p>
 * 每个常量声明的四列依次对应契约表的：locator / input_data / expected / 强制等待策略。
 */
public enum Action {

    //                locator     input_data  expected    wait 策略                依据       偏离说明
    OPEN_URL         (FORBIDDEN,  REQUIRED,   FORBIDDEN,  WaitStrategy.NONE,      null,      null),
    CLICK            (REQUIRED,   FORBIDDEN,  FORBIDDEN,  WaitStrategy.CLICKABLE, "STD-005", null),
    // INPUT→VISIBLE 出自 L1 的填充规则，而非 STD-006（STD-006 的原文只约束 ASSERT_*）。
    // standardRef 留空是刻意的：诊断信息里引错规范编号，比不引更糟。
    INPUT            (REQUIRED,   REQUIRED,   FORBIDDEN,  WaitStrategy.VISIBLE,   null,      null),
    SELECT           (REQUIRED,   REQUIRED,   FORBIDDEN,  WaitStrategy.VISIBLE,   null,      null),
    ASSERT_TEXT      (REQUIRED,   FORBIDDEN,  REQUIRED,   WaitStrategy.VISIBLE,   "STD-006", null),
    ASSERT_VISIBLE   (REQUIRED,   FORBIDDEN,  FORBIDDEN,  WaitStrategy.VISIBLE,   "STD-006", null),

    /**
     * ⚠️ 规范与语义冲突的唯一一处，见 {@link #waitStrategyDeviation()}。
     */
    ASSERT_NOT_EXIST (REQUIRED,   FORBIDDEN,  FORBIDDEN,  WaitStrategy.NONE,      "STD-006",
            "STD-006 要求 ASSERT_* 一律用 VISIBLE，但本 action 断言的是元素【不存在】："
          + "等待一个不该出现的元素变为可见，必然空耗到 wait_timeout_sec 超时。"
          + "故取 NONE，并在诊断中显式标注该偏离，由平台方裁定。"),

    /** 显式等待。它的 wait_strategy 就是这个 action 的全部意义，不该被降级为 PRESENCE。 */
    WAIT_FOR         (REQUIRED,   FORBIDDEN,  FORBIDDEN,  WaitStrategy.VISIBLE,   null,      null),

    /** 滚动的目的正是让元素可见，若要求 VISIBLE 才肯滚动就本末倒置了。 */
    SCROLL_TO        (REQUIRED,   FORBIDDEN,  FORBIDDEN,  WaitStrategy.PRESENCE,  null,      null),

    /** iframe 元素本身通常不具备"可见"语义，等到 PRESENCE 即可切入。 */
    SWITCH_FRAME     (REQUIRED,   FORBIDDEN,  FORBIDDEN,  WaitStrategy.PRESENCE,  null,      null),

    SWITCH_WINDOW    (FORBIDDEN,  REQUIRED,   FORBIDDEN,  WaitStrategy.NONE,      null,      null),

    /** {@code <input type="file">} 常被 CSS 隐藏，要求 VISIBLE 会让正常的上传用例失败。 */
    UPLOAD           (REQUIRED,   REQUIRED,   FORBIDDEN,  WaitStrategy.PRESENCE,  null,      null),

    /** ⛔ STD-004 明令禁止，仅存量历史案例中存在。见 {@link #isForbiddenByStandard()}。 */
    SLEEP            (FORBIDDEN,  REQUIRED,   FORBIDDEN,  WaitStrategy.NONE,      "STD-004", null);

    private final FieldRequirement locator;
    private final FieldRequirement inputData;
    private final FieldRequirement expected;
    private final WaitStrategy mandatedWaitStrategy;
    private final String standardRef;
    private final String waitStrategyDeviation;

    Action(FieldRequirement locator,
           FieldRequirement inputData,
           FieldRequirement expected,
           WaitStrategy mandatedWaitStrategy,
           String standardRef,
           String waitStrategyDeviation) {
        this.locator = locator;
        this.inputData = inputData;
        this.expected = expected;
        this.mandatedWaitStrategy = mandatedWaitStrategy;
        this.standardRef = standardRef;
        this.waitStrategyDeviation = waitStrategyDeviation;
    }

    /** locator_type / locator_value 是否必填。 */
    public FieldRequirement locator() {
        return locator;
    }

    public FieldRequirement inputData() {
        return inputData;
    }

    public FieldRequirement expected() {
        return expected;
    }

    /**
     * L1 应当为该 action 填入的 wait_strategy。
     * <p>
     * 注意这里没有"默认值"与"强制值"之分 —— 对确定性优先的流水线来说，
     * 只要规则能算出唯一答案，它就该是强制的；留一个可被随意覆盖的"默认"
     * 只会让 STD-005/006 变成建议。请求方给了不同的值时判 WARN 并纠正，
     * 而不是沉默接受。
     */
    public WaitStrategy mandatedWaitStrategy() {
        return mandatedWaitStrategy;
    }

    /** 相关规范编号（如 {@code STD-005}），无对应规范时为 {@code null}。用于诊断信息里注明依据。 */
    public String standardRef() {
        return standardRef;
    }

    /**
     * 该 action 的 wait_strategy 偏离了 {@link #standardRef()} 字面要求时的说明，否则 {@code null}。
     * <p>
     * 目前只有 {@link #ASSERT_NOT_EXIST} 命中。**偏离必须被显式报告，不能悄悄执行** ——
     * 这正是"失败不静默"在规范冲突场景下的形态：
     * 我们不假装规范没有漏洞，也不擅自替平台方改规范，而是产出一条诊断把选择摊开。
     */
    public String waitStrategyDeviation() {
        return waitStrategyDeviation;
    }

    /** 是否为断言步骤。STD-008 要求每条案例至少有一个。 */
    public boolean isAssertion() {
        return switch (this) {
            case ASSERT_TEXT, ASSERT_VISIBLE, ASSERT_NOT_EXIST -> true;
            default -> false;
        };
    }

    /** 是否被规范明令禁止（STD-004 禁用 SLEEP）。命中即 ERROR，案例被拒。 */
    public boolean isForbiddenByStandard() {
        return this == SLEEP;
    }

    /** 该 action 是否需要 locator —— 便于 L1 判断要不要推断 locator_type。 */
    public boolean requiresLocator() {
        return locator == REQUIRED;
    }
}
