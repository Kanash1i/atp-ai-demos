package com.atp.platform.service;

import com.atp.common.enums.CaseStatus;
import com.atp.common.enums.CaseType;
import com.atp.common.enums.Priority;
import com.atp.common.model.Step;
import com.atp.common.model.StepJson;
import com.atp.common.util.DisplayTime;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.ArrayList;
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
    public DraftView draft(String caseId, String title, CaseType caseType, String createdBy) {
        ObjectNode header = StepJson.mapper().createObjectNode();
        if (title != null) {
            header.put("title", title);
        }
        String initial = StepJson.toDraftObject(header, List.of());

        // ⚠️ 之前这里硬编码 PC_WEB —— CLI 的 `atp draft -p IOS` 传进来会被静默丢掉，
        //    落库仍是 PC_WEB，而且没有任何报错。这类「参数收了但不用」的 bug
        //    只有对着两边的字段逐个核对才会发现
        int caseRows = writeMapper.insertDraftCase(caseId,
                (caseType == null ? CaseType.PC_WEB : caseType).code(), createdBy);
        writeMapper.insertDraftStep(UUID.randomUUID().toString(), caseId, initial);

        // ⭐ ON CONFLICT DO NOTHING 把「并发/重试的失败者」转换成「幂等的成功者」：
        //    同一个 caseId 再来一次，拿到的是已经存在的那条草稿，不报错也不重复建。
        //    ⚠️ 但「是新建还是重放」必须告诉调用方 —— 两者都返回 200 + 同样的视图的话，
        //    调用方永远看不出区别，而它可能需要据此决定要不要覆盖本地状态
        boolean replayed = caseRows == 0;
        if (replayed) {
            log.info("草稿 {} 已存在，按幂等返回既有内容", caseId);
        }
        return view(caseId, replayed);
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
            // ⭐ 先看是不是重放 —— 「写成功但响应丢了，调用方重试」是正常路径，不是错误。
            //    当成冲突返回的话，agent 会停下来问人，问的却是一个已经写成功的东西
            if (isUpdateReplay(caseId, draftJson, expectedVersion)) {
                log.info("草稿 {} 的更新是重放（version 已是 {}，内容一致）", caseId, expectedVersion + 1);
                return view(caseId, true);
            }
            throw diagnose(caseId, expectedVersion, "更新");
        }
        return view(caseId, false);
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

        // ⭐ 重放检查必须**最先**做，在任何校验之前。
        //    因为提交成功之后 step_json 已经规整成纯步骤数组、表头投影进了 tc_case ——
        //    此时再跑表头校验，会因为「草稿里没有 case_code」而报缺字段，
        //    而那是一次已经成功的提交。实测正是这么挂的：
        //    重试 commit 拿到的是 422 缺字段，而不是「这次是重放」。
        if (isCommitReplay(step, expectedVersion)) {
            log.info("案例 {} 的提交是重放（已落地，version={}）", caseId, expectedVersion + 1);
            return view(caseId, true);
        }

        // ⚠️ 状态检查也要在内容校验之前。已落地的案例 step_json 是纯数组、
        //    表头早投影走了 —— 先跑表头校验的话，得到的是「缺 case_code」，
        //    而调用方按这个提示去补字段是**补了也没用**：真正的原因是这条案例已经提交过了。
        //    错误信息把人指向哪里，比错误本身准不准更影响排查时间。
        if (step.getStatus() != null && step.getStatus() != CaseStatus.AI_DRAFT.code()) {
            throw diagnose(caseId, expectedVersion, "提交");
        }

        // 提交前跑一遍规范校验 —— ERROR 一律拦下。
        // ⭐ 这是「规则是硬的，LLM 只在规则之内自由」的落点：agent 也走这条路径，绕不过去
        ValidationResult result = validate(caseId, step.getStepJson());
        if (!result.passed()) {
            throw new CaseValidationException(result);
        }

        ObjectNode header = StepJson.parseHeader(step.getStepJson());
        requireCompleteHeader(header);
        String legacy = StepJson.toLegacyArray(step.getStepJson());

        int affected = writeMapper.commitStep(caseId, legacy, expectedVersion);
        if (affected != 1) {
            throw diagnose(caseId, expectedVersion, "提交");
        }

        writeMapper.projectHeader(caseId,
                text(header, "case_code"), text(header, "title"), text(header, "module_id"),
                priority(header), text(header, "author"), text(header, "precondition"));

        log.info("案例 {} 已提交，落地为 DRAFT", caseId);
        return view(caseId, false);
    }

    /**
     * 提交前确认表头六个必填字段都在。
     *
     * <p>数据库上的 {@code ck_case_complete} 本来就会拦住，但它抛的是
     * {@code DataIntegrityViolationException}，兜底成 500 ——
     * **500 的意思是「服务端出错了」，而实际上是调用方少填了字段**。
     * 在这里显式检查，是为了把它变成一条说得清楚的 422。
     *
     * <p>字段名是 {@code draftJson} 顶层的 snake_case 键，与 CLI 的 schema 一致；
     * camelCase 不认 —— 一个字段只有一种写法，两种写法迟早有一处会漏掉转换。
     */
    private void requireCompleteHeader(ObjectNode header) {
        List<String> missing = new ArrayList<>();
        for (String key : REQUIRED_HEADER_KEYS) {
            if (!header.hasNonNull(key) || header.get(key).asText().isBlank()) {
                missing.add(key);
            }
        }
        if (!missing.isEmpty()) {
            throw new CaseHeaderIncompleteException(missing);
        }
    }

    /**
     * 提交时必须齐的六个字段。
     *
     * <p>⚠️ {@code precondition} 不在其中 —— 它允许为空（不是所有案例都有前置条件），
     * 数据库那条约束里也没有它。
     */
    private static final List<String> REQUIRED_HEADER_KEYS =
            List.of("case_code", "title", "module_id", "priority", "author");

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
    /**
     * 这次 commit 是不是一次重放（上次写成功了，响应丢在路上，调用方重试）。
     *
     * <h3>判据：状态离开编写态 + 版本恰好前进一格</h3>
     *
     * 版本恰好是 {@code expectedVersion + 1}，说明**前进这一格的那次写入，
     * 用的就是我手上这个版本号** —— 也就是我上次那一次。
     * 如果是别人改的，版本会比这个大（他也得先前进一格，我再来就对不上了）。
     *
     * <p>commit 不带内容，所以状态 + 版本就够判。{@code update} 不行，见
     * {@link #isUpdateReplay}。
     *
     * <p>⚠️ 调用位置在**所有校验之前** —— 提交成功后 step_json 已规整成纯数组、
     * 表头投影进了 tc_case，此时再跑表头校验必然报缺字段。
     *
     * <h3>⚠️ 为什么这条不能省</h3>
     *
     * 不判重放的话，重试会拿到「状态冲突」——而按契约那意味着「停下，问人」。
     * 于是 agent 会停下来找人，**问一个其实已经提交成功的案例**。
     * 重放在语义上是成功，这是幂等的第一条规则。
     */
    private boolean isCommitReplay(TcStep step, int expectedVersion) {
        return step != null
                && step.getStatus() != null && step.getStatus() != CaseStatus.AI_DRAFT.code()
                && step.getVersion() != null && step.getVersion() == expectedVersion + 1;
    }

    /**
     * 这次 update 是不是一次重放。
     *
     * <h3>⚠️ 比 commit 多一个条件：内容必须一致</h3>
     *
     * 只看「版本前进一格」是不够的，因为 update **带内容**：
     *
     * <pre>
     *   A: update(v=3, 内容 X) → 成功，v=4
     *   B: update(v=3, 内容 Y) → 失败
     * </pre>
     *
     * B 这次的版本条件同样满足（库里 v=4 == 3+1），但它**不是重放** ——
     * 它想写的 Y 一个字都没进去。当成重放返回成功的话，B 会以为自己写成功了。
     *
     * <p>所以要比内容：库里那份就是我想写的那份，才是重放。
     * 顺带这也让「两个人恰好写了同样的内容」正确地被当成成功 —— 写不写都一样。
     *
     * <p>比的是 **JSON 语义**不是字符串：两侧的键顺序、空白都可能不同，
     * 按字符串比会把真正的重放误判成冲突。
     */
    private boolean isUpdateReplay(String caseId, String draftJson, int expectedVersion) {
        TcStep step = loadStep(caseId);
        if (step == null || step.getVersion() == null || step.getVersion() != expectedVersion + 1) {
            return false;
        }
        try {
            JsonNode stored = StepJson.mapper().readTree(step.getStepJson());
            JsonNode incoming = StepJson.mapper().readTree(draftJson);
            return stored.equals(incoming);
        } catch (IOException e) {
            // 解析不了就当不是重放 —— 宁可报冲突让调用方重来，也不要把失败说成成功
            return false;
        }
    }

    private RuntimeException diagnose(String caseId, int expectedVersion, String action) {
        TcStep step = loadStep(caseId);
        if (step == null) {
            return new CaseNotFoundException(caseId);
        }
        // ⚠️ 两个分支必须给出**机器可分辨**的区别，不能只靠文案不同：
        //    状态不对是「别再试了」，版本不对是「重来一遍」—— 处置完全相反
        if (step.getStatus() != null && step.getStatus() != CaseStatus.AI_DRAFT.code()) {
            return new CaseConflictException(CaseConflictException.Kind.STATE,
                    "案例已经提交过了（当前状态码 %d，version=%d），本次%s不予执行"
                            .formatted(step.getStatus(), step.getVersion(), action));
        }
        return new CaseConflictException(CaseConflictException.Kind.VERSION,
                "版本不一致：库中 version=%d，你手上是 %d。内容在你确认之后被改过，请重新拉取再确认"
                        .formatted(step.getVersion(), expectedVersion));
    }

    private TcStep loadStep(String caseId) {
        return stepMapper.selectOne(new LambdaQueryWrapper<TcStep>().eq(TcStep::getCaseId, caseId));
    }

    /**
     * 读当前草稿。{@code atp show} / {@code atp preview} 走这条。
     *
     * <p>⚠️ 刻意不返回 {@code tc_case.version} —— 编辑期它全程不动
     * （编辑只写 {@code tc_step}），调用方拿到也没有用。
     * 让调用方为了自测而要求平台暴露一个业务上用不到的字段，
     * 是把测试需求泄漏进 API 形状；那条不变量该由平台自己的测试守住。
     */
    public DraftView view(String caseId) {
        // 单独读一次不涉及写入，自然不是重放
        return view(caseId, false);
    }

    private DraftView view(String caseId, boolean replayed) {
        TcStep step = loadStep(caseId);
        TcCase c = caseMapper.selectById(caseId);
        if (step == null || c == null) {
            throw new CaseNotFoundException(caseId);
        }
        // ⚠️ status 是 **tc_step 的编辑期状态**，platformStatus 才是 tc_case 的状态。
        //    两者不是一回事：草稿在编辑期反复改，tc_case 那行还一直是 AI_DRAFT。
        //    早先这里把 tc_case.status 当成 status 返回，与 CLI 的语义正好错位，
        //    迁移之后编辑期状态机会静默丢失 —— 对着 CLI 的 model.CaseRow 逐字段核才发现
        return new DraftView(caseId, step.getStepJson(), step.getVersion(),
                replayed,
                nameOf(step.getStatus()),
                c.getCaseType() == null ? null : c.getCaseType().name(),
                c.getStatus() == null ? null : c.getStatus().name(),
                DisplayTime.toMinute(step.getUpdatedAt()),
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
                            /**
                             * 这次调用是不是一次**幂等重放**（上次写成功了，响应丢在路上）。
                             *
                             * <p>⚠️ 重放在语义上是**成功**，HTTP 上同样是 200 ——
                             * 它和「新写入」的区别只有这个字段能表达。
                             * 没有它的话，调用方无法分辨，而它可能需要据此决定
                             * 要不要覆盖本地状态、要不要提示用户「刚才那次其实成功了」。
                             */
                            boolean replayed,
                            /** tc_step 的编辑期状态（AI_DRAFT / DRAFT …） */
                            String status,
                            /** 执行平台 PC_WEB / IOS / ANDROID */
                            String caseType,
                            /** tc_case 的状态 —— 与 status 不是一回事 */
                            String platformStatus,
                            /**
                             * {@code tc_step.updated_at} —— 草稿的最后保存时间。
                             *
                             * <p>编辑器只读这个接口，不该为一个时间戳再打一次详情接口 ——
                             * 何况详情接口在 AI_DRAFT 下正是编辑器唯一会用到的状态。
                             */
                            String editUpdatedAt,
                            ValidationVO validation) {
    }

    /** tc_step.status 存的是码值，转成名字给调用方 —— 码值出了这个进程就没人认得 */
    private static String nameOf(Short code) {
        if (code == null) {
            return null;
        }
        for (CaseStatus st : CaseStatus.values()) {
            if (st.code() == code) {
                return st.name();
            }
        }
        return null;
    }
}
