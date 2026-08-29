package com.atp.platform.service;

import com.atp.common.enums.CaseStatus;
import com.atp.common.enums.CaseType;
import com.atp.common.enums.Priority;
import com.atp.common.model.Step;
import com.atp.common.model.StepJson;
import com.atp.common.model.TestCase;
import com.atp.common.validation.StandardsValidator;
import com.atp.common.validation.ValidationResult;
import com.atp.platform.entity.TcCase;
import com.atp.platform.entity.TcStep;
import com.atp.platform.mapper.CaseWriteMapper;
import com.atp.platform.mapper.TcCaseMapper;
import com.atp.platform.mapper.TcStepMapper;
import com.atp.platform.vo.ValidationVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 案例编辑期的写侧。
 *
 * <h3>这条路径由两个调用方共用</h3>
 *
 * <ul>
 *   <li>人在 UI 上编辑案例</li>
 *   <li>agent 用自然语言生成案例（M3 的 CaseAuthoringAgent）</li>
 * </ul>
 *
 * 它们走**同一条路径**：草稿 → 改内容 → STD 校验 → commit 落地。
 * 先为 UI 做一遍、再为 agent 做一遍的话，几乎必然出现两套语义 ——
 * 而这条路径上已经有 demo2 CLI 的一套实现了，第三套只会让「哪个才是对的」更难回答。
 *
 * <h3>状态机</h3>
 *
 * <pre>
 *   draft()          → tc_case(AI_DRAFT) + tc_step(AI_DRAFT, version=0)
 *   update(v)        → tc_step CAS，version+1，tc_case 一动不动
 *   commit(v)        → tc_step 翻 DRAFT + step_json 规整成数组
 *                      → 表头投影进 tc_case 的正式列
 *                      → ck_case_complete 在这一刻守门
 * </pre>
 *
 * ⭐ 编辑期的高频写全部落在 tc_step 一张表一行上（单表单行 CAS），不跨表。
 */
@Slf4j
@Service
public class CaseWriteService {

    @Autowired
    private CaseWriteMapper writeMapper;
    @Autowired
    private TcCaseMapper caseMapper;
    @Autowired
    private TcStepMapper stepMapper;

    private final StandardsValidator validator = new StandardsValidator();

    /**
     * 建草稿。
     *
     * @param caseId 由**调用方**生成。重试时复用同一个 id 即可幂等 ——
     *               这是幂等的全部来源，交给数据库生成的话，
     *               「写成功但响应丢失 → 重试」会产生两条各自合法的草稿
     */
    @Transactional
    public DraftView draft(String caseId, String title, String createdBy) {
        ObjectNode header = StepJson.mapper().createObjectNode();
        if (title != null) {
            header.put("title", title);
        }
        String initial = StepJson.toDraftObject(header, List.of());

        int caseRows = writeMapper.insertDraftCase(caseId, CaseType.PC_WEB.code(), createdBy);
        writeMapper.insertDraftStep(UUID.randomUUID().toString(), caseId, initial);

        if (caseRows == 0) {
            // ⭐ ON CONFLICT DO NOTHING 把「并发/重试的失败者」转换成「幂等的成功者」：
            //    同一个 caseId 再来一次，拿到的是已经存在的那条草稿，不报错也不重复建
            log.info("草稿 {} 已存在，按幂等返回既有内容", caseId);
        }
        return view(caseId);
    }

    /** 更新草稿内容。{@code expectedVersion} 是上次 preview/show 拿到的版本 */
    @Transactional
    public DraftView update(String caseId, String draftJson, int expectedVersion) {
        // ⚠️ 先在应用层确认这是合法 JSON。
        //    不校验的话，坏内容会一路走到 `?::jsonb` 那一步由 PostgreSQL 报错，
        //    出来是 DataIntegrityViolationException → 500，而这明明是**客户端传错了**（400）。
        //    更糟的是错误信息里带着一大段 SQL 和参数，对调用方毫无用处 ——
        //    agent 拿到这种 500 只会重试，而重试一百次结果一样。
        requireValidJson(draftJson);
        int affected = writeMapper.updateDraft(caseId, draftJson, expectedVersion);
        if (affected != 1) {
            throw diagnose(caseId, expectedVersion, "更新");
        }
        return view(caseId);
    }

    /**
     * 提交：草稿落地为老平台原生的 DRAFT 案例。
     *
     * <p>⚠️ 只收 caseId 和 version，**不接受任何外部内容** ——
     * 投影的输入是库里那一行，也就是用户已经确认过的那份快照。
     * 允许调用方在 commit 时带内容的话，「确认的」和「提交的」就可能不是同一份东西。
     */
    @Transactional
    public DraftView commit(String caseId, int expectedVersion) {
        TcStep step = loadStep(caseId);
        if (step == null) {
            throw new CaseNotFoundException(caseId);
        }

        // 提交前跑一遍规范校验 —— ERROR 一律拦下。
        // ⭐ 这是「规则是硬的，LLM 只在规则之内自由」的落点：agent 也走这条路径，绕不过去
        ValidationResult result = validate(caseId, step.getStepJson());
        if (!result.passed()) {
            throw new CaseValidationException(result);
        }

        ObjectNode header = StepJson.parseHeader(step.getStepJson());
        String legacy = StepJson.toLegacyArray(step.getStepJson());

        int affected = writeMapper.commitStep(caseId, legacy, expectedVersion);
        if (affected != 1) {
            throw diagnose(caseId, expectedVersion, "提交");
        }

        writeMapper.projectHeader(caseId,
                text(header, "case_code"), text(header, "title"), text(header, "module_id"),
                priority(header), text(header, "author"), text(header, "precondition"));

        log.info("案例 {} 已提交，落地为 DRAFT", caseId);
        return view(caseId);
    }

    /** 校验草稿（不落库）。agent 的 validate_case 工具与前端的「校验」按钮都走这里 */
    public ValidationResult validate(String caseId, String draftJson) {
        ObjectNode header = StepJson.parseHeader(draftJson);
        List<Step> steps = StepJson.parseSteps(draftJson);
        TestCase domain = new TestCase(
                caseId, text(header, "case_code"), text(header, "title"),
                text(header, "module_id"), null,
                parsePriority(text(header, "priority")),
                text(header, "author"), text(header, "precondition"),
                CaseStatus.AI_DRAFT, null, null, null, null,
                steps, null, null, null);
        return validator.validate(domain);
    }

    private void requireValidJson(String draftJson) {
        if (draftJson == null || draftJson.isBlank()) {
            throw new IllegalArgumentException("草稿内容不能为空");
        }
        try {
            var node = StepJson.mapper().readTree(draftJson);
            if (!node.isObject()) {
                throw new IllegalArgumentException(
                        "草稿内容必须是对象（表头字段 + steps 数组），当前是 " + node.getNodeType());
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // 把解析器的位置信息带上 —— agent 要靠它定位自己写坏了哪里
            throw new IllegalArgumentException("草稿不是合法 JSON：" + e.getOriginalMessage());
        }
    }

    // ── 内部 ──────────────────────────────────────────────────

    /**
     * 受影响行数为 0 时诊断到底出了什么事。
     *
     * <p>⚠️ 不能只说「操作失败」：状态不对和版本不对是两种完全不同的处置 ——
     * 前者要看看是不是已经提交过了，后者要重新拉一遍再确认。
     */
    private RuntimeException diagnose(String caseId, int expectedVersion, String action) {
        TcStep step = loadStep(caseId);
        if (step == null) {
            return new CaseNotFoundException(caseId);
        }
        if (step.getStatus() != null && step.getStatus() != CaseStatus.AI_DRAFT.code()) {
            return new CaseConflictException(
                    "案例已经提交过了（当前状态码 %d，version=%d），本次%s不予执行"
                            .formatted(step.getStatus(), step.getVersion(), action));
        }
        return new CaseConflictException(
                "版本不一致：库中 version=%d，你手上是 %d。内容在你确认之后被改过，请重新拉取再确认"
                        .formatted(step.getVersion(), expectedVersion));
    }

    private TcStep loadStep(String caseId) {
        return stepMapper.selectOne(new LambdaQueryWrapper<TcStep>().eq(TcStep::getCaseId, caseId));
    }

    private DraftView view(String caseId) {
        TcStep step = loadStep(caseId);
        TcCase c = caseMapper.selectById(caseId);
        if (step == null || c == null) {
            throw new CaseNotFoundException(caseId);
        }
        return new DraftView(caseId, step.getStepJson(), step.getVersion(),
                c.getStatus() == null ? null : c.getStatus().name(),
                ValidationVO.from(validate(caseId, step.getStepJson())));
    }

    private String text(ObjectNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private Short priority(ObjectNode header) {
        Priority p = parsePriority(text(header, "priority"));
        return p == null ? null : p.code();
    }

    private Priority parsePriority(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Priority.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            // 草稿期允许写错，校验器会报 —— 这里不抛，否则连预览都打不开
            return null;
        }
    }

    /**
     * @param version 下次 update/commit 要带回来的版本号
     */
    public record DraftView(String caseId, String draftJson, int version,
                            String status, ValidationVO validation) {
    }
}
