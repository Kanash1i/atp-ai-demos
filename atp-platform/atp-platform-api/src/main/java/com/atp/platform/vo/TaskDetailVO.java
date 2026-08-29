package com.atp.platform.vo;

import java.util.List;

/**
 * 失败详情 —— 点「FAIL」进来看的那一屏。
 *
 * @param failedSeq 失败落在第几步。前端直接高亮到这一行，不用人从头找
 */
public record TaskDetailVO(
        String taskId,
        String runCode,
        String caseId,
        String caseCode,
        String caseTitle,
        String browser,
        String nodeName,
        String status,
        String duration,
        String startedAt,
        String finishedAt,
        Integer failedSeq,
        String errorMsg,
        String videoUrl,
        String screenshotUrl,
        List<StepResultVO> steps
) {

    public record StepResultVO(int seq, String action, String status, String duration,
                               String errorMsg, String screenshotUrl) {
    }
}
