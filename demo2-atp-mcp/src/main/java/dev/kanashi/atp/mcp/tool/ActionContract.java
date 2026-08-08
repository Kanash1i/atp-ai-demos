package dev.kanashi.atp.mcp.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.kanashi.atp.mcp.domain.Action;

/**
 * 单个 action 的字段契约，随 {@code atp_describe_schema} 暴露给调用方 agent。
 * <p>
 * <b>这是"左移"的具体形态</b>：调用方在<i>生成阶段</i>就知道 CLICK 必须带 locator、
 * ASSERT_TEXT 必须带 expected，而不是先乱生成一版再由 normalize 收拾。
 * 数据直接从 {@link Action} 枚举导出，所以不存在"文档写的和服务执行的不一致"。
 *
 * @param waitStrategy   本服务会自动填充的值 —— 调用方<b>不需要</b>提供这个字段
 * @param deviationNote  该 wait_strategy 偏离规范字面要求时的说明，否则 null
 * @param forbidden      该 action 是否被规范禁止（SLEEP）
 */
public record ActionContract(
        @JsonProperty("action") String action,
        @JsonProperty("locator") String locator,
        @JsonProperty("input_data") String inputData,
        @JsonProperty("expected") String expected,
        @JsonProperty("wait_strategy") String waitStrategy,
        @JsonProperty("is_assertion") boolean isAssertion,
        @JsonProperty("forbidden") boolean forbidden,
        @JsonProperty("standard_ref") String standardRef,
        @JsonProperty("deviation_note") String deviationNote) {

    public static ActionContract of(Action action) {
        return new ActionContract(
                action.name(),
                action.locator().name(),
                action.inputData().name(),
                action.expected().name(),
                action.mandatedWaitStrategy().name(),
                action.isAssertion(),
                action.isForbiddenByStandard(),
                action.standardRef(),
                action.waitStrategyDeviation());
    }
}
