package com.atp.web.controller;

import com.atp.platform.service.CaseQueryService;
import com.atp.platform.service.CaseWriteService;
import com.atp.platform.vo.CaseDetailVO;
import com.atp.platform.vo.ModuleNodeVO;
import com.atp.platform.vo.ProjectVO;
import com.atp.platform.vo.ValidationVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 案例中心 —— 前端左侧那棵树加右侧详情页。
 */
@RestController
@RequestMapping("/api")
public class CaseController {

    @Autowired
    private CaseQueryService caseQueryService;

    @Autowired
    private CaseWriteService caseWriteService;

    /** 项目 pill */
    @GetMapping("/projects")
    public List<ProjectVO> projects() {
        return caseQueryService.projects();
    }

    /**
     * 一个项目下的完整案例树。
     *
     * <p>一次返回模块与它们的案例 —— 前端展开/收起是纯客户端行为，不再回来请求。
     * 案例总量 80 条，这样比每展开一个模块打一次请求省得多。
     */
    @GetMapping("/projects/{projectId}/tree")
    public List<ModuleNodeVO> tree(@PathVariable String projectId) {
        return caseQueryService.tree(projectId);
    }

    /** 案例详情：表头 + 步骤表 + 规范校验结果 */
    @GetMapping("/cases/{caseId}")
    public CaseDetailVO detail(@PathVariable String caseId) {
        return caseQueryService.detail(caseId);
    }

    /** 只要校验结果。前端"重新校验"按钮与 agent 的 validate_case 工具都走这个 */
    @GetMapping("/cases/{caseId}/validation")
    public ValidationVO validation(@PathVariable String caseId) {
        return caseQueryService.validate(caseId);
    }

    // ── 写侧 ──────────────────────────────────────────────────
    // ⚠️ 人在 UI 上编辑与 agent 生成案例走的是**同一条路径**，下面这三个端点两边共用。

    /**
     * 建草稿。
     *
     * <p>⚠️ {@code caseId} 由**调用方**生成（前端用 crypto.randomUUID()，agent 用自己的 uuid），
     * 重试时复用同一个即可幂等。交给服务端生成的话，
     * 「请求成功但响应丢失 → 前端重试」会建出两条各自合法的草稿。
     */
    @PostMapping("/cases/draft")
    public CaseWriteService.DraftView draft(@RequestBody DraftRequest body) {
        return caseWriteService.draft(body.caseId(), body.title(), body.createdBy());
    }

    /**
     * 更新草稿内容。
     *
     * <p>{@code version} 必须是上次拿到的那个 —— 中间被别人改过就会 409，
     * 这是编辑期唯一的并发仲裁点。
     */
    @PutMapping("/cases/{caseId}/draft")
    public CaseWriteService.DraftView update(@PathVariable String caseId,
                                             @RequestBody UpdateRequest body) {
        return caseWriteService.update(caseId, body.draftJson(), body.version());
    }

    /**
     * 提交：草稿落地为老平台原生的 DRAFT 案例。
     *
     * <p>⚠️ 只带 version，**不带内容** —— 提交的是库里那份你已经确认过的快照。
     * 允许带内容的话，「确认的」和「提交的」就可能不是同一份东西。
     *
     * <p>规范校验 ERROR 会拦下（422），并返回完整的违反明细。
     */
    @PostMapping("/cases/{caseId}/commit")
    public CaseWriteService.DraftView commit(@PathVariable String caseId,
                                             @RequestBody CommitRequest body) {
        return caseWriteService.commit(caseId, body.version());
    }

    /** @param caseId 调用方生成的 UUID */
    public record DraftRequest(String caseId, String title, String createdBy) {
    }

    /** @param draftJson 完整的草稿对象：表头字段 + steps 数组 */
    public record UpdateRequest(String draftJson, int version) {
    }

    public record CommitRequest(int version) {
    }
}
