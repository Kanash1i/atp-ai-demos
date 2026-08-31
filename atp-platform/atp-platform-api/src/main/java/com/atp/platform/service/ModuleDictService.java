package com.atp.platform.service;

import com.atp.platform.entity.TcCase;
import com.atp.platform.entity.TcModule;
import com.atp.platform.entity.TcProject;
import com.atp.platform.mapper.TcCaseMapper;
import com.atp.platform.mapper.TcModuleMapper;
import com.atp.platform.mapper.TcProjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 模块字典 —— 跨全部项目的平铺清单。
 *
 * <h3>为什么不复用案例树</h3>
 *
 * {@code /projects/{id}/tree} 形状不对：它是单个项目、带案例、给前端展开树用的。
 * 而这里要回答的是「{@code module_id} 能填哪些值」——
 * agent 手上没有 projectId 清单，用树的话得先拉项目、再逐个项目请求、
 * 还要把几十条案例一起拖回来再全部丢掉。**为了拿一份字典去遍历一棵带叶子的树，方向反了。**
 *
 * <h3>它为什么重要到值得单开一个接口</h3>
 *
 * {@code tc_case.module_id} 没有外键约束，「防模型编造 module_id」全靠先查这份字典。
 * 更要紧的是：**只要还有一个 CLI 命令直连 PG，CLI 就仍然需要数据库账号密码，
 * agent 那一层就仍然读得到。** 凭证边界是二元的 —— 漏一个口就是没关上。
 */
@Service
public class ModuleDictService {

    @Autowired
    private TcModuleMapper moduleMapper;

    @Autowired
    private TcProjectMapper projectMapper;

    @Autowired
    private TcCaseMapper caseMapper;

    /**
     * 全部模块，按 projectCode → moduleCode 排序。
     *
     * <p>不分页：模块是字典数据，量级以十计。给它加分页反而让调用方多写一圈循环，
     * 而 agent 每多一轮交互就多一次出错的机会。
     */
    public List<ModuleEntry> all() {
        Map<String, TcProject> projects = projectMapper.selectList(null).stream()
                .collect(Collectors.toMap(TcProject::getProjectId, Function.identity()));

        return moduleMapper.selectList(new LambdaQueryWrapper<>()).stream()
                .map(m -> toEntry(m, projects.get(m.getProjectId())))
                .sorted(Comparator.comparing(ModuleEntry::projectCode, Comparator.nullsLast(String::compareTo))
                        .thenComparing(ModuleEntry::moduleCode, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    /**
     * 该模块的下一个可用编号，形如 {@code ATP-CART-0015}（STD-007）。
     *
     * <h3>⚠️ 取「最大序号 + 1」，不是「条数 + 1」</h3>
     *
     * 用条数的话，只要中间删过一条，新号就会和现存的某个号撞上 ——
     * 而 {@code uk_case_code} 会把它拦下，表现为「新建案例莫名其妙失败」。
     * 编号是单调的，计数不是。
     *
     * <h3>并发下仍可能撞号，这是有意接受的</h3>
     *
     * 两个人同时要号会拿到同一个，但 {@code uk_case_code} 会让后写的那个失败 ——
     * **不会写坏数据，只会有一次重试**。真实平台该用序列或号段，
     * 那是为了避免重试，不是为了正确性。演示环境下不值得引入。
     */
    public String nextCaseCode(String moduleId) {
        TcModule module = moduleMapper.selectById(moduleId);
        if (module == null) {
            throw new IllegalArgumentException("模块不存在：" + moduleId);
        }
        String prefix = "ATP-" + module.getModuleCode() + "-";

        int max = caseMapper.selectList(new LambdaQueryWrapper<TcCase>()
                        .select(TcCase::getCaseCode)
                        .likeRight(TcCase::getCaseCode, prefix)).stream()
                .map(TcCase::getCaseCode)
                .filter(c -> c != null && c.length() > prefix.length())
                .map(c -> c.substring(prefix.length()))
                .filter(tail -> tail.matches("\\d+"))
                .mapToInt(Integer::parseInt)
                .max().orElse(0);

        return "%s%04d".formatted(prefix, max + 1);
    }

    private ModuleEntry toEntry(TcModule m, TcProject p) {
        return new ModuleEntry(
                m.getProjectId(),
                p == null ? null : p.getProjectCode(),
                p == null ? null : p.getProjectName(),
                m.getModuleId(), m.getModuleCode(), m.getModuleName());
    }

    /** 字段名与 CLI 的 {@code model.ModuleEntry} 对齐 —— 它那边零转换 */
    public record ModuleEntry(
            String projectId,
            String projectCode,
            String projectName,
            String moduleId,
            String moduleCode,
            String moduleName) {
    }
}
