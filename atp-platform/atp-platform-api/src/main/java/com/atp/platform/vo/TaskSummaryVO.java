package com.atp.platform.vo;

/** 「最近执行结果」表里的一行。 */
public record TaskSummaryVO(
        String taskId,
        String caseCode,
        String caseTitle,
        String browser,
        String nodeName,
        String status,
        String duration,
        String finishedAt,
        boolean hasVideo
) {
}
