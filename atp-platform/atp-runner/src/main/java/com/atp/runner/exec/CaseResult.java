package com.atp.runner.exec;

import com.atp.common.enums.TaskStatus;

import java.util.List;

/**
 * 一条案例跑完的结果。
 *
 * @param failedSeq 失败落在第几步。前端失败详情页靠它直接定位，不用扫全部步骤
 * @param videoPath 录像文件路径。⚠️ Playwright 的录像要等 context 关闭后才写完整，
 *                  所以这个值只有在 {@code close()} 之后才可用
 */
public record CaseResult(TaskStatus status, long durationMs, Integer failedSeq,
                         String errorMsg, String videoPath, String screenshotPath,
                         List<StepResult> steps) {
}
