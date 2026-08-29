package com.atp.web.controller;

import com.atp.common.enums.ApprovalStatus;
import com.atp.platform.service.ApprovalService;
import com.atp.platform.vo.ApprovalStatsVO;
import com.atp.platform.vo.ApprovalVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 审批中心。
 *
 * <p>⚠️ 当前用户目前由请求参数带进来（{@code ?user=}）。
 * Sa-Token 的登录链路在 M1 后半段接，接完之后这里换成 {@code StpUtil.getLoginIdAsString()}，
 * 参数保留给演示时切换身份用 —— 面试现场要能一键从提交人视角切到审批人视角。
 */
@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {

    @Autowired
    private ApprovalService approvalService;

    /** 顶部四个计数：待我审批 / 我提交的 / 已完成 / SLA 超时 */
    @GetMapping("/stats")
    public ApprovalStatsVO stats(@RequestParam(defaultValue = "kaneshiro") String user) {
        return approvalService.stats(user);
    }

    /** 待审列表。不传 assignee 就是全部待审 */
    @GetMapping("/pending")
    public List<ApprovalVO> pending(@RequestParam(required = false) String assignee) {
        return approvalService.pending(assignee);
    }

    /** 我提交的 */
    @GetMapping("/mine")
    public List<ApprovalVO> mine(@RequestParam(defaultValue = "kaneshiro") String user) {
        return approvalService.submittedBy(user);
    }

    @GetMapping("/{requestId}")
    public ApprovalVO get(@PathVariable String requestId) {
        return approvalService.get(requestId);
    }

    /**
     * 批准 / 退回 / 挂起。
     *
     * <p>并发下只有一个人能改走 PENDING，后到的会拿到 409 并被告知是谁先处理的。
     */
    @PostMapping("/{requestId}/decision")
    public ApprovalVO decide(@PathVariable String requestId, @RequestBody DecisionRequest body) {
        return approvalService.decide(
                requestId,
                ApprovalStatus.valueOf(body.decision()),
                body.decidedBy(),
                body.note());
    }

    /**
     * @param decision APPROVED / REJECTED / HOLD
     * @param note     决策理由。退回时尤其要写 —— 提交人只看得到这一句
     */
    public record DecisionRequest(String decision, String decidedBy, String note) {
    }
}
