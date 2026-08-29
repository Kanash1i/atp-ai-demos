package com.atp.web.controller;

import com.atp.platform.service.CaseQueryService;
import com.atp.platform.vo.CaseDetailVO;
import com.atp.platform.vo.ModuleNodeVO;
import com.atp.platform.vo.ProjectVO;
import com.atp.platform.vo.ValidationVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
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
}
