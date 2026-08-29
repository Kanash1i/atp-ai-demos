package com.atp.platform.service;

import com.atp.common.enums.ApprovalStatus;
import com.atp.common.util.DisplayTime;
import com.atp.platform.entity.TcApproval;
import com.atp.platform.mapper.TcApprovalMapper;
import com.atp.platform.vo.ApprovalStatsVO;
import com.atp.platform.vo.ApprovalVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 审批中心。
 *
 * <p>三类审批（规范例外 / 案例变更 / 数据集发布）共用一张表与一套流程 ——
 * 它们的生命周期完全相同（提交 → SLA 倒计时 → 批准/退回/挂起），
 * 差异只在展示数据上，那部分收在 {@code payload_json} 里。
 */
@Slf4j
@Service
public class ApprovalService {

    @Autowired
    private TcApprovalMapper approvalMapper;

    private final ObjectMapper json = new ObjectMapper();

    /** 待审列表，按 SLA 紧迫程度排 —— 快超时的排前面，已超时的最前 */
    public List<ApprovalVO> pending(String assignee) {
        LambdaQueryWrapper<TcApproval> q = new LambdaQueryWrapper<TcApproval>()
                .eq(TcApproval::getStatus, ApprovalStatus.PENDING)
                .orderByAsc(TcApproval::getSlaDueAt);
        if (assignee != null && !assignee.isBlank()) {
            q.eq(TcApproval::getAssignee, assignee);
        }
        return approvalMapper.selectList(q).stream().map(this::toVO).toList();
    }

    public List<ApprovalVO> submittedBy(String submitter) {
        return approvalMapper.selectList(new LambdaQueryWrapper<TcApproval>()
                        .eq(TcApproval::getSubmitter, submitter)
                        .orderByDesc(TcApproval::getSubmittedAt))
                .stream().map(this::toVO).toList();
    }

    public ApprovalVO get(String requestId) {
        TcApproval entity = approvalMapper.selectById(requestId);
        if (entity == null) {
            throw new ApprovalNotFoundException(requestId);
        }
        return toVO(entity);
    }

    public ApprovalStatsVO stats(String currentUser) {
        long awaiting = approvalMapper.selectCount(new LambdaQueryWrapper<TcApproval>()
                .eq(TcApproval::getStatus, ApprovalStatus.PENDING)
                .eq(TcApproval::getAssignee, currentUser));
        long submitted = approvalMapper.selectCount(new LambdaQueryWrapper<TcApproval>()
                .eq(TcApproval::getSubmitter, currentUser)
                .eq(TcApproval::getStatus, ApprovalStatus.PENDING));
        long completed = approvalMapper.selectCount(new LambdaQueryWrapper<TcApproval>()
                .in(TcApproval::getStatus, ApprovalStatus.APPROVED, ApprovalStatus.REJECTED));
        // 超时：还挂着 PENDING 且已经过了 sla_due_at。查询时算，不存状态
        long overdue = approvalMapper.selectCount(new LambdaQueryWrapper<TcApproval>()
                .eq(TcApproval::getStatus, ApprovalStatus.PENDING)
                .lt(TcApproval::getSlaDueAt, OffsetDateTime.now()));
        return new ApprovalStatsVO(awaiting, submitted, completed, overdue);
    }

    /**
     * 做决策。
     *
     * <p>⚠️ 用「先读后写 + 状态断言」而不是无脑 UPDATE：两个审批人同时点批准/退回时，
     * 后点的那个必须失败并看到原因，而不是悄悄覆盖前一个人的决定。
     * 这里的仲裁点是 {@code status = PENDING} 这个条件本身 ——
     * 更新语句带上它，受影响行数为 0 就说明已经被别人处理过了。
     */
    @Transactional
    public ApprovalVO decide(String requestId, ApprovalStatus decision, String decidedBy, String note) {
        if (decision == ApprovalStatus.PENDING) {
            throw new IllegalArgumentException("决策结果不能是 PENDING");
        }
        TcApproval current = approvalMapper.selectById(requestId);
        if (current == null) {
            throw new ApprovalNotFoundException(requestId);
        }
        if (current.getStatus() != ApprovalStatus.PENDING) {
            throw new ApprovalAlreadyDecidedException(requestId, current.getStatus(), current.getDecidedBy());
        }

        TcApproval update = new TcApproval();
        update.setStatus(decision);
        update.setDecidedBy(decidedBy);
        update.setDecidedAt(OffsetDateTime.now());
        update.setDecisionNote(note);

        int affected = approvalMapper.update(update, new LambdaQueryWrapper<TcApproval>()
                .eq(TcApproval::getRequestId, requestId)
                // ⭐ 仲裁点就在这一行：并发下只有一个人能把 PENDING 改走
                .eq(TcApproval::getStatus, ApprovalStatus.PENDING));
        if (affected == 0) {
            TcApproval latest = approvalMapper.selectById(requestId);
            throw new ApprovalAlreadyDecidedException(requestId, latest.getStatus(), latest.getDecidedBy());
        }
        log.info("审批 {} 被 {} 决策为 {}", requestId, decidedBy, decision);
        return get(requestId);
    }

    // ── 内部 ──────────────────────────────────────────────────

    private ApprovalVO toVO(TcApproval e) {
        boolean overdue = e.getStatus() == ApprovalStatus.PENDING
                && e.getSlaDueAt() != null
                && e.getSlaDueAt().isBefore(OffsetDateTime.now());
        return new ApprovalVO(
                e.getRequestId(),
                e.getType() == null ? null : e.getType().name(),
                e.getTargetId(),
                e.getTitle(),
                e.getSummary(),
                e.getStatus() == null ? null : e.getStatus().name(),
                e.getSubmitter(),
                DisplayTime.toMinute(e.getSubmittedAt()),
                slaText(e),
                overdue,
                e.getAssignee(),
                e.getDecidedBy(),
                DisplayTime.toMinute(e.getDecidedAt()),
                e.getDecisionNote(),
                parsePayload(e));
    }

    /** 「剩余 22h」/「SLA 超时 +14h」。已决策的不再显示倒计时 */
    private String slaText(TcApproval e) {
        if (e.getSlaDueAt() == null || e.getStatus() != ApprovalStatus.PENDING) {
            return null;
        }
        Duration d = Duration.between(OffsetDateTime.now(), e.getSlaDueAt());
        if (d.isNegative()) {
            return "SLA 超时 +" + humanize(d.abs());
        }
        return "剩余 " + humanize(d);
    }

    private String humanize(Duration d) {
        long days = d.toDays();
        if (days >= 1) {
            return days + "d";
        }
        long hours = d.toHours();
        if (hours >= 1) {
            return hours + "h";
        }
        return Math.max(d.toMinutes(), 1) + "m";
    }

    private JsonNode parsePayload(TcApproval e) {
        if (e.getPayloadJson() == null || e.getPayloadJson().isBlank()) {
            return json.createObjectNode();
        }
        try {
            return json.readTree(e.getPayloadJson());
        } catch (Exception ex) {
            // payload 只是展示数据，坏了不该让整个列表打不开 —— 记一笔，返回空对象
            log.warn("审批 {} 的 payload_json 解析失败", e.getRequestId(), ex);
            return json.createObjectNode();
        }
    }
}
