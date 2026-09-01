package com.atp.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.atp.platform.entity.SysUser;
import com.atp.platform.mapper.SysUserMapper;
import com.atp.web.auth.StpInterfaceImpl;
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

    @Autowired
    private SysUserMapper userMapper;

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
    /**
     * 决策审批。
     *
     * <h3>⚠️ {@code decidedBy} 从 token 取，不信任请求体</h3>
     *
     * 早先它来自 {@code DecisionRequest.decidedBy} —— 那意味着任何人都能以任何人的
     * 名义批准一条审批，而审批记录上会留下被冒充者的名字。
     * 一个「谁批的」记不准的审批系统，比没有审批系统更糟：它给出了虚假的可追溯性。
     *
     * <p>请求体里的 {@code decidedBy} 现在被忽略（保留字段是为了不破坏前端已有的调用），
     * 落库的一定是 token 持有者。
     *
     * <p>⚠️ 需要 {@code approval:decide} 权限 —— **只有人拿得到**。
     * agent 能写案例、能自验，但「这条变更该不该放行」是人的判断。
     */
    @PostMapping("/{requestId}/decision")
    public ApprovalVO decide(@PathVariable String requestId, @RequestBody DecisionRequest body) {
        String decidedBy = currentUsername();
        return approvalService.decide(
                requestId,
                ApprovalStatus.valueOf(body.decision()),
                decidedBy,
                body.note());
    }

    /**
     * 从 token 解出当前用户名。
     *
     * <p>loginId 形如 {@code user:U001}，落库要的是 {@code username}（审批表里存的是它）。
     */
    private String currentUsername() {
        String loginId = String.valueOf(StpUtil.getLoginId());
        if (!loginId.startsWith(StpInterfaceImpl.USER_PREFIX)) {
            // 机器主体拿不到 approval:decide，正常走不到这里；走到了说明权限配置漏了
            throw new IllegalStateException("只有人能决策审批，当前主体是 " + loginId);
        }
        SysUser u = userMapper.selectById(loginId.substring(StpInterfaceImpl.USER_PREFIX.length()));
        if (u == null) {
            throw new IllegalStateException("token 里的用户已不存在：" + loginId);
        }
        return u.getUsername();
    }

    /**
     * @param decision APPROVED / REJECTED / HOLD
     * @param note     决策理由。退回时尤其要写 —— 提交人只看得到这一句
     */
    /** @param decidedBy ⚠️ **已忽略** —— 决策人从 token 取，保留字段只为不破坏已有调用 */
    public record DecisionRequest(String decision, String decidedBy, String note) {
    }
}
