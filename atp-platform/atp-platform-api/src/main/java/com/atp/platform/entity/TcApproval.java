package com.atp.platform.entity;

import com.atp.common.enums.ApprovalStatus;
import com.atp.common.enums.ApprovalType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 审批请求。三类审批（规范例外 / 案例变更 / 数据集发布）共用一张表，
 * 差异全在 {@link #payloadJson} 里 —— 那部分只给人看，不参与过滤和排序。
 */
@Data
@TableName("tc_approval")
public class TcApproval {

    @TableId
    private String requestId;
    private ApprovalType type;
    private String targetId;
    private String title;
    private String summary;
    /** ⚠️ 存整包 before/after 快照。只存变更字段的话，待审期间案例又被改了，diff 就对不上了 */
    private String payloadJson;
    private ApprovalStatus status;
    private String submitter;
    private OffsetDateTime submittedAt;
    /** SLA 截止时刻。「超时」是查询时算出来的，不存状态 —— 存状态就要有人定时去翻它 */
    private OffsetDateTime slaDueAt;
    private String assignee;
    private String decidedBy;
    private OffsetDateTime decidedAt;
    private String decisionNote;
}
