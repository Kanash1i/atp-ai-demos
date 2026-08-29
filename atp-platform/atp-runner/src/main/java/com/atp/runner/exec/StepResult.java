package com.atp.runner.exec;

import com.atp.common.enums.StepStatus;

/**
 * 一步的执行结果。
 *
 * @param detail 给人看的一句话，形如 {@code INPUT //input[@data-testid="login-password"] ← ***}。
 *               ⚠️ 凭据在这里已经是 {@code ***} —— 它会进日志、进 SSE、进失败详情页
 */
public record StepResult(int seq, String action, StepStatus status, long durationMs,
                         String detail, String errorMsg) {

    public boolean failed() {
        return status == StepStatus.FAILED;
    }
}
