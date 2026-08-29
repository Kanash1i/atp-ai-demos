package com.atp.platform.vo;

/**
 * 执行看板顶部的四张卡片。
 *
 * <p>⚠️ 全部**从表里算**，不是配置里的常量 —— 被问「94.2% 怎么来的」时要答得出。
 *
 * @param passRate          通过率（%），分母是 PASSED + FAILED，不含 SKIPPED ——
 *                          跳过的用例没跑，算进分母会把通过率压低，那是误导
 * @param totalDeltaPercent 与昨日相比的百分比变化，正数表示今天跑得更多
 * @param avgDurationDelta  与昨日相比的平均耗时变化（秒）
 */
public record ExecStatsVO(
        long todayTotal,
        double passRate,
        int avgDurationSec,
        long failedCount,
        long failedP0Count,
        Double totalDeltaPercent,
        Integer avgDurationDelta
) {
}
