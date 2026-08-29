package com.atp.platform.service;

import com.atp.common.enums.ApprovalStatus;

/**
 * 审批已被别人处理过。
 *
 * <p>⚠️ 这是**并发冲突**，不是参数错误 —— web 层翻成 409 Conflict。
 * 报错要带上「谁、什么时候、处理成了什么」，否则审批人只会看到一句「操作失败」然后再点一次。
 */
public class ApprovalAlreadyDecidedException extends RuntimeException {

    public ApprovalAlreadyDecidedException(String requestId, ApprovalStatus current, String decidedBy) {
        super("审批 %s 已经被 %s 处理为 %s，不能重复决策"
                .formatted(requestId, decidedBy == null ? "他人" : decidedBy, current));
    }
}
