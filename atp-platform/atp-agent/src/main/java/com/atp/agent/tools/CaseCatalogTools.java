package com.atp.agent.tools;

import com.atp.platform.entity.TcCase;
import com.atp.platform.entity.TcModule;
import com.atp.platform.entity.TcProject;
import com.atp.platform.mapper.TcCaseMapper;
import com.atp.platform.mapper.TcModuleMapper;
import com.atp.platform.mapper.TcProjectMapper;
import com.atp.platform.service.CaseQueryService;
import com.atp.platform.service.ModuleDictService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 案例与字典的查询工具。
 *
 * <p>⭐ {@code list_modules} 是写案例的**硬前置**：{@code module_id} 是外键，
 * 编一个不存在的值会在 commit 时被数据库拦下（ck_case_complete + 外键语义），
 * 而那时 agent 已经绕了一大圈。让它先查，比让它错了再改省得多。
 */
@Slf4j
@Component
public class CaseCatalogTools {

    @Autowired
    private TcProjectMapper projectMapper;
    @Autowired
    private TcModuleMapper moduleMapper;
    @Autowired
    private TcCaseMapper caseMapper;
    @Autowired
    private CaseQueryService caseQueryService;

    @Autowired
    private ModuleDictService moduleDictService;

    @Tool(name = "list_modules",
            description = "列出全部项目与模块字典。写案例前必须先查，module_id 只能从这里取，不能自己编。")
    public String listModules() {
        Map<String, String> projects = projectMapper.selectList(null).stream()
                .collect(Collectors.toMap(TcProject::getProjectId, TcProject::getProjectName, (a, b) -> a));

        StringBuilder sb = new StringBuilder("项目与模块字典：\n");
        for (TcModule m : moduleMapper.selectList(new LambdaQueryWrapper<TcModule>()
                .orderByAsc(TcModule::getModuleId))) {
            sb.append("  module_id=").append(m.getModuleId())
              .append("  code=").append(m.getModuleCode())
              .append("  ").append(m.getModuleName())
              .append("  （项目 ").append(projects.getOrDefault(m.getProjectId(), m.getProjectId())).append("）\n");
        }
        sb.append("\n⚠️ case_code 的规范是 ATP-{module_code}-{4位序号}（STD-007）。");
        return sb.toString();
    }

    @Tool(name = "find_similar_cases",
            description = "按模块查找已有案例，用来参考写法与编号。"
                    + "⚠️ 存量案例里有一部分是规范建立前写的，可能违反现行规范 —— "
                    + "参考它们的结构，但不要照抄不合规的写法。")
    public String findSimilarCases(
            @ToolParam(name = "module_id", description = "模块 id，如 M003") String moduleId,
            @ToolParam(name = "limit", description = "返回条数，默认 5") Integer limit) {

        int n = limit == null ? 5 : Math.min(Math.max(limit, 1), 10);
        List<TcCase> cases = caseMapper.selectList(new LambdaQueryWrapper<TcCase>()
                .eq(TcCase::getModuleId, moduleId)
                .isNotNull(TcCase::getCaseCode)
                .orderByAsc(TcCase::getCaseCode)
                .last("LIMIT " + n));

        if (cases.isEmpty()) {
            return "模块 " + moduleId + " 下还没有案例。";
        }

        StringBuilder sb = new StringBuilder("模块 ").append(moduleId).append(" 下的已有案例：\n");
        for (TcCase c : cases) {
            var detail = caseQueryService.detail(c.getCaseId());
            sb.append("\n").append(c.getCaseCode()).append("  ").append(c.getTitle())
              .append("  [").append(detail.priority()).append("]\n");
            // ⭐ 把校验结果一并给出去 —— agent 要知道"这条能参考，但它违反了 STD-004，别照抄"
            var v = detail.validation();
            if (!v.violatedCodes().isEmpty()) {
                sb.append("  ⚠️ 该案例违反：").append(String.join("、", v.violatedCodes())).append('\n');
            }
            detail.steps().forEach(s -> sb.append("    ").append(s.seq()).append(". ")
                    .append(s.action())
                    .append(s.locatorValue() == null ? "" : "  " + s.locatorValue())
                    .append(s.waitStrategy() == null ? "" : "  wait=" + s.waitStrategy())
                    .append('\n'));
        }
        log.info("[TOOL][find_similar_cases] module={} → {} 条", moduleId, cases.size());
        return sb.toString();
    }

    @Tool(name = "next_case_code",
            description = "为指定模块生成下一个可用的 case_code，符合 ATP-{MODULE}-{4位序号} 规范。"
                    + "不要自己拼编号，用这个工具拿。")
    public String nextCaseCode(
            @ToolParam(name = "module_id", description = "模块 id，如 M003") String moduleId) {
        TcModule module = moduleMapper.selectById(moduleId);
        if (module == null) {
            return "模块 " + moduleId + " 不存在，先用 list_modules 查一下。";
        }
        // ⚠️ 改调 ModuleDictService —— 前端也要这个能力（GET /api/modules/{id}/next-case-code），
        //    两份实现迟早会漂。原先这里用「条数 + 1」，中间删过一条就会撞号
        return moduleDictService.nextCaseCode(moduleId);
    }
}
