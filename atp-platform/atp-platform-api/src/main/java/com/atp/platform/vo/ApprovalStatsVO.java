package com.atp.platform.vo;

/** 审批中心顶部的四个计数。 */
public record ApprovalStatsVO(long awaitingMe, long submittedByMe, long completed, long overdue) {
}
