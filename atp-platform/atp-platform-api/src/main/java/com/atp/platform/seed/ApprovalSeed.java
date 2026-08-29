package com.atp.platform.seed;

import com.atp.common.enums.ApprovalStatus;
import com.atp.common.enums.ApprovalType;
import com.atp.platform.entity.TcApproval;
import com.atp.platform.entity.TcCase;
import com.atp.platform.mapper.TcApprovalMapper;
import com.atp.platform.mapper.TcCaseMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 审批中心的演示数据。
 *
 * <p>⭐ 每一条都**挂在真实存在的案例上**，而且挑的是校验器真会报违规的那几条 ——
 * 演示时点开审批卡片里的案例，详情页的「规范校验」区块确实标着 STD-004，
 * 而不是一句对不上号的文案。前端设计稿里那条 {@code ATP-CART-0011} 在语料里并不存在，
 * 已换成真的有 SLEEP 的 {@code ATP-PAYMENT-0001}。
 *
 * <p>⚠️ 审批**必须有存量数据**：SLA 倒计时、超时标红、「已完成 128」这些
 * 靠现场操作攒不出来，但它们正是这一屏要展示的东西。
 */
@Slf4j
@Service
public class ApprovalSeed {

    @Autowired
    private TcApprovalMapper approvalMapper;
    @Autowired
    private TcCaseMapper caseMapper;

    @Transactional
    public int importApprovals() {
        if (approvalMapper.selectCount(null) > 0) {
            log.info("审批种子已存在，跳过");
            return 0;
        }

        OffsetDateTime now = OffsetDateTime.now();
        List<TcApproval> rows = new ArrayList<>();

        // ① 规范例外 —— 已超时 14 小时，列表里排最前并标红
        rows.add(pending(ApprovalType.RULE_EXCEPTION,
                caseIdOf("ATP-PAYMENT-0001"),
                "ATP-PAYMENT-0001 支付网关回调等待 —— 申请暂留 SLEEP",
                "支付 mock 的响应是异步的，做不出确定的等待条件",
                """
                {"violated_std":"STD-004","step_seq":2,
                 "reason":"支付 mock 的响应是异步的，做不出确定的等待条件",
                 "expire_at":"2026-09-30"}""",
                "tanaka", now.minusDays(4).minusHours(2), now.minusHours(14), "kaneshiro"));

        // ② 案例变更 —— 整包 before/after 快照，前端据此算 diff
        rows.add(pending(ApprovalType.CASE_CHANGE,
                caseIdOf("ATP-LOGIN-0004"),
                "ATP-LOGIN-0004 密码错误锁定 —— 新增 3 个断言步骤",
                "补充锁定后的提示文案与跳转校验",
                """
                {"diff_summary":["+3 steps","priority P1 → P0"],
                 "standards_check":"PASS",
                 "before":{"priority":"P1","step_count":5},
                 "after":{"priority":"P0","step_count":8}}""",
                "sato", now.minusDays(3), now.plusHours(22), "kaneshiro"));

        // ③ 数据集发布 —— 评估没跑，建议挂起而不是批准
        rows.add(pending(ApprovalType.DATASET_RELEASE,
                null,
                "atp-runbook-ops 语料集发布到生产索引",
                "评估集尚未整备，建议先挂起",
                """
                {"corpus_name":"atp-runbook-ops","docs_count":23,
                 "index_progress":68,"evaluated":false}""",
                "kaneshiro", now.minusDays(3).minusHours(6), now.plusDays(2), "sato"));

        // ④ 案例变更 —— 编号不合规（这条案例真的违反 STD-007）
        rows.add(pending(ApprovalType.CASE_CHANGE,
                caseIdOf("ATP-ADMIN-0011-V2"),
                "ATP-ADMIN-0011-V2 —— 编号改为合规的 ATP-ADMIN-0012",
                "现编号带 -V2 后缀，不符合 STD-007",
                """
                {"diff_summary":["case_code ATP-ADMIN-0011-V2 → ATP-ADMIN-0012"],
                 "standards_check":"FAIL","violated":["STD-007"],
                 "before":{"case_code":"ATP-ADMIN-0011-V2"},
                 "after":{"case_code":"ATP-ADMIN-0012"}}""",
                "tanaka", now.minusDays(2), now.plusDays(3), "kaneshiro"));

        // ⑤⑥ 已决策的两条，让「已完成」那个计数不是 0
        rows.add(decided(ApprovalType.CASE_CHANGE, caseIdOf("ATP-CART-0002"),
                "ATP-CART-0002 —— 补充库存不足时的断言",
                ApprovalStatus.APPROVED, "sato", now.minusDays(9), now.minusDays(8),
                "断言补得对，合并"));
        rows.add(decided(ApprovalType.RULE_EXCEPTION, caseIdOf("ATP-SEARCH-0001"),
                "ATP-SEARCH-0001 —— 申请保留绝对路径 XPath",
                ApprovalStatus.REJECTED, "tanaka", now.minusDays(12), now.minusDays(11),
                "搜索结果页有 data-testid，没有破例的理由"));

        rows.forEach(approvalMapper::insert);
        log.info("审批种子导入完成：新增 {} 条", rows.size());
        return rows.size();
    }

    /** 按 case_code 找 case_id。找不到返回 null —— 审批可以不挂案例（如数据集发布） */
    private String caseIdOf(String caseCode) {
        TcCase c = caseMapper.selectOne(
                new LambdaQueryWrapper<TcCase>().eq(TcCase::getCaseCode, caseCode));
        if (c == null) {
            log.warn("审批种子引用了不存在的案例 {}，该条 target_id 置空", caseCode);
            return null;
        }
        return c.getCaseId();
    }

    private TcApproval pending(ApprovalType type, String targetId, String title, String summary,
                               String payload, String submitter, OffsetDateTime submittedAt,
                               OffsetDateTime slaDueAt, String assignee) {
        TcApproval a = new TcApproval();
        a.setRequestId(java.util.UUID.randomUUID().toString());
        a.setType(type);
        a.setTargetId(targetId);
        a.setTitle(title);
        a.setSummary(summary);
        a.setPayloadJson(payload);
        a.setStatus(ApprovalStatus.PENDING);
        a.setSubmitter(submitter);
        a.setSubmittedAt(submittedAt);
        a.setSlaDueAt(slaDueAt);
        a.setAssignee(assignee);
        return a;
    }

    private TcApproval decided(ApprovalType type, String targetId, String title,
                               ApprovalStatus status, String decidedBy,
                               OffsetDateTime submittedAt, OffsetDateTime decidedAt, String note) {
        TcApproval a = pending(type, targetId, title, null, "{}", "kaneshiro", submittedAt,
                submittedAt.plusDays(3), decidedBy);
        a.setStatus(status);
        a.setDecidedBy(decidedBy);
        a.setDecidedAt(decidedAt);
        a.setDecisionNote(note);
        return a;
    }
}
