package com.atp.platform.vo;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 审批卡片 —— 前端审批中心列表里的一张。
 *
 * @param slaRemaining 人类可读的剩余时间：{@code "剩余 22h"} / {@code "SLA 超时 +14h"}。
 *                     ⭐ 在后端算，不是把 {@code slaDueAt} 丢给前端自己减 ——
 *                     前端算就得处理时区，而浏览器时区和服务器时区不一致时，
 *                     「超时」这种带情绪的字眼算错会直接误导审批人
 * @param overdue      是否已超时，前端据此标红
 * @param payload      三类审批各自的差异数据，原样透出（整包 before/after 快照）
 */
public record ApprovalVO(
        String requestId,
        String type,
        String targetId,
        String title,
        String summary,
        String status,
        String submitter,
        String submittedAt,
        String slaRemaining,
        boolean overdue,
        String assignee,
        String decidedBy,
        String decidedAt,
        String decisionNote,
        JsonNode payload
) {
}
