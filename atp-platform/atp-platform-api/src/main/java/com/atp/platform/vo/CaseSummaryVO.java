package com.atp.platform.vo;

/**
 * 案例摘要 —— 树里的一行。
 *
 * @param seqNo case_code 的后四位。设计稿的树里只显示这四位（模块名已经在父节点上了），
 *              但完整的 caseCode 也要给 —— 前端详情页标题、执行记录、审批都用完整编号
 */
public record CaseSummaryVO(
        String caseId,
        String caseCode,
        String seqNo,
        String title,
        String priority,
        String status
) {
}
