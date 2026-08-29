package com.atp.platform.vo;

/**
 * 执行中的批次 —— 看板中间那张带进度条的卡片。
 *
 * <p>⚠️ 只有真的有批次在跑时才有值。历史种子里的批次全是 DONE ——
 * 「正在跑」这件事必须是真的，摆一个不会动的假进度条，演示时一刷新就露馅。
 *
 * @param etaSec 预计剩余秒数。按已完成任务的平均耗时推，任务数少时会很不准，
 *               所以前端要显示成「≈」而不是精确值
 */
public record RunningRunVO(
        String runId,
        String runCode,
        String projectName,
        String suiteName,
        String browser,
        String triggerSource,
        int doneCount,
        int totalCount,
        int passedCount,
        int failedCount,
        int skippedCount,
        int runningCount,
        long elapsedSec,
        Long etaSec
) {
}
