package dev.kanashi.atp.cli.model;

/**
 * tc_step 的一行。
 *
 * <p>{@code stepJson} 是步骤的完整内容（action / locator / expected / wait_strategy …）。
 * <b>这是步骤在库里唯一的存放处</b> —— 老平台的人工案例和 AI 写的案例走同一条路，
 * 父表不再另存一份整包 JSON。
 */
public record StepRow(
        String stepId,
        int seq,
        String stepJson
) {}
