package com.atp.platform.service;

import com.atp.common.enums.StdCode;
import com.atp.common.util.DisplayTime;
import com.atp.common.model.Step;
import com.atp.common.model.StepJson;
import com.atp.common.model.TestCase;
import com.atp.common.validation.StandardsValidator;
import com.atp.common.validation.ValidationResult;
import com.atp.platform.entity.TcCase;
import com.atp.platform.entity.TcModule;
import com.atp.platform.entity.TcProject;
import com.atp.platform.entity.TcStep;
import com.atp.platform.mapper.TcCaseMapper;
import com.atp.platform.mapper.TcModuleMapper;
import com.atp.platform.mapper.TcProjectMapper;
import com.atp.platform.mapper.TcStepMapper;
import com.atp.platform.vo.CaseDetailVO;
import com.atp.platform.vo.CaseSummaryVO;
import com.atp.platform.vo.ModuleNodeVO;
import com.atp.platform.vo.ProjectVO;
import com.atp.platform.vo.ValidationVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 案例中心的读侧 —— 前端「案例中心」面板的全部数据都从这里出。
 */
@Slf4j
@Service
public class CaseQueryService {

    @Autowired
    private TcProjectMapper projectMapper;
    @Autowired
    private TcModuleMapper moduleMapper;
    @Autowired
    private TcCaseMapper caseMapper;
    @Autowired
    private TcStepMapper stepMapper;

    private final ObjectMapper json = new ObjectMapper();
    private final StandardsValidator validator = new StandardsValidator();

    public List<ProjectVO> projects() {
        return projectMapper.selectList(null).stream()
                .sorted(Comparator.comparing(TcProject::getProjectId))
                .map(p -> new ProjectVO(p.getProjectId(), p.getProjectCode(), p.getProjectName()))
                .toList();
    }

    /**
     * 一个项目下的完整案例树：模块 → 案例。
     *
     * <p>⚠️ 一次查两条 SQL 全取回来，在内存里分组，**不是每个模块查一次** ——
     * 8 个模块就是 8 次往返，而库在另一台机器上（台式机），每次往返都是实打实的网络延迟。
     * 案例总量 80 条这个量级，全取回来远比 N+1 划算。
     *
     * <p>将来案例上千了要改成分页，那时 {@code caseCount} 仍然由数据库算 —— 见 ModuleNodeVO 的注释。
     */
    public List<ModuleNodeVO> tree(String projectId) {
        List<TcModule> modules = moduleMapper.selectList(
                new LambdaQueryWrapper<TcModule>()
                        .eq(TcModule::getProjectId, projectId)
                        .orderByAsc(TcModule::getModuleId));
        if (modules.isEmpty()) {
            return List.of();
        }

        List<String> moduleIds = modules.stream().map(TcModule::getModuleId).toList();
        Map<String, List<TcCase>> byModule = caseMapper.selectList(
                        new LambdaQueryWrapper<TcCase>()
                                .in(TcCase::getModuleId, moduleIds)
                                .orderByAsc(TcCase::getCaseCode))
                .stream()
                .collect(Collectors.groupingBy(TcCase::getModuleId));

        List<ModuleNodeVO> tree = new ArrayList<>(modules.size());
        for (TcModule m : modules) {
            List<TcCase> cases = byModule.getOrDefault(m.getModuleId(), List.of());
            tree.add(new ModuleNodeVO(
                    m.getModuleId(), m.getModuleCode(), m.getModuleName(),
                    cases.size(),
                    cases.stream().map(this::toSummary).toList()));
        }
        return tree;
    }

    public CaseDetailVO detail(String caseId) {
        TcCase entity = caseMapper.selectById(caseId);
        if (entity == null) {
            throw new CaseNotFoundException(caseId);
        }
        TcModule module = entity.getModuleId() == null ? null : moduleMapper.selectById(entity.getModuleId());
        List<Step> steps = loadSteps(caseId);

        // 详情页每次都重新跑一遍校验，不缓存也不存库。
        // 理由：规则会改（STD 是我们自己维护的），存下来的结论会过期；
        // 而 80 条案例、每条 5 步的校验本身是微秒级的，没有缓存的必要。
        ValidationResult result = validator.validate(toDomain(entity, module, steps));

        return new CaseDetailVO(
                entity.getCaseId(),
                entity.getCaseCode(),
                entity.getTitle(),
                entity.getModuleId(),
                module == null ? null : module.getModuleCode(),
                module == null ? null : module.getModuleName(),
                module == null ? null : module.getProjectId(),
                entity.getPriority() == null ? null : entity.getPriority().name(),
                entity.getStatus() == null ? null : entity.getStatus().name(),
                entity.getCaseType() == null ? null : entity.getCaseType().name(),
                entity.getAuthor(),
                entity.getPrecondition(),
                DisplayTime.toMinute(entity.getUpdatedAt()),
                entity.getVersion() == null ? 0 : entity.getVersion(),
                steps,
                toVO(result));
    }

    /**
     * 取领域模型 —— 执行节点要的是这个，不是给前端看的 VO。
     *
     * <p>⚠️ 执行器只认 {@link TestCase}：它不知道 MyBatis、不知道 VO，
     * 这样同一套 Action 翻译逻辑既能跑库里的案例，也能跑测试里手写的案例。
     */
    public TestCase loadDomain(String caseId) {
        TcCase entity = caseMapper.selectById(caseId);
        if (entity == null) {
            throw new CaseNotFoundException(caseId);
        }
        TcModule module = entity.getModuleId() == null ? null : moduleMapper.selectById(entity.getModuleId());
        return toDomain(entity, module, loadSteps(caseId));
    }

    /** 单独暴露校验，给 agent 的 {@code validate_case} 工具和保存前的 gate 用 */
    public ValidationVO validate(String caseId) {
        return detail(caseId).validation();
    }

    // ── 内部 ──────────────────────────────────────────────────

    private CaseSummaryVO toSummary(TcCase c) {
        return new CaseSummaryVO(
                c.getCaseId(),
                c.getCaseCode(),
                seqNo(c.getCaseCode()),
                c.getTitle(),
                c.getPriority() == null ? null : c.getPriority().name(),
                c.getStatus() == null ? null : c.getStatus().name());
    }

    /** ATP-LOGIN-0002 → 0002。⚠️ 编号不合规的案例（存量里有 3 条）取不出来，原样返回 */
    private String seqNo(String caseCode) {
        if (caseCode == null) {
            return null;
        }
        int i = caseCode.lastIndexOf('-');
        return i < 0 ? caseCode : caseCode.substring(i + 1);
    }

    private List<Step> loadSteps(String caseId) {
        TcStep step = stepMapper.selectOne(
                new LambdaQueryWrapper<TcStep>().eq(TcStep::getCaseId, caseId));
        if (step == null || step.getStepJson() == null || step.getStepJson().isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(step.getStepJson(), new TypeReference<List<Step>>() {
            });
        } catch (IOException e) {
            // ⚠️ 不吞掉：step_json 解析不了意味着这条案例根本没法执行，
            //    静默返回空列表会让它在 UI 上显示成「一步都没有的案例」，比报错难查得多
            throw new UncheckedIOException("案例 " + caseId + " 的 step_json 解析失败", e);
        }
    }

    /** 实体 → 领域模型，喂给校验器。校验器只认领域模型，不认持久层实体 */
    private TestCase toDomain(TcCase entity, TcModule module, List<Step> steps) {
        return new TestCase(
                entity.getCaseId(), entity.getCaseCode(), entity.getTitle(),
                entity.getModuleId(), module == null ? null : module.getModuleCode(),
                entity.getPriority(), entity.getAuthor(), entity.getPrecondition(),
                entity.getStatus(),
                null, null,   // browser / timeoutSec：不是案例属性，见 TcCase 的注释
                null, null,   // createdAt / updatedAt：校验用不到
                steps,
                null, null, null);
    }

    private ValidationVO toVO(ValidationResult result) {
        return ValidationVO.from(result);
    }
}
