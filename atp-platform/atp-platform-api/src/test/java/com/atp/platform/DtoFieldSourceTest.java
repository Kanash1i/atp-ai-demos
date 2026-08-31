package com.atp.platform;

import com.atp.common.enums.CaseStatus;
import com.atp.common.enums.CaseType;
import com.atp.platform.entity.TcCase;
import com.atp.platform.entity.TcStep;
import com.atp.platform.mapper.TcCaseMapper;
import com.atp.platform.mapper.TcStepMapper;
import com.atp.platform.service.CaseQueryService;
import com.atp.platform.service.CaseWriteService;
import com.atp.platform.vo.CaseDetailVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.atp.common.util.DisplayTime;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 每个 DTO 字段是不是取自它该取的那张表。
 *
 * <h3>为什么需要机械保障，而不是「注释里写清楚」</h3>
 *
 * {@code tc_case} 与 {@code tc_step} 有三个同名列（{@code status} / {@code version} /
 * {@code updated_at}），每一个在两张表上含义都不同。取错表**不会报错**，
 * 只会让下游拿到一个看起来合理的错值 —— 这类缺陷已经发生过四次：
 *
 * <ol>
 *   <li>{@code DraftView.status} 取了 tc_case，而 CLI 侧语义是 tc_step</li>
 *   <li>CLI 的 {@code PlatformVersion} 在平台侧无处对应</li>
 *   <li>{@code CaseDetailVO.version} 取 tc_case，写侧要 tc_step ——
 *       前端拿去 PUT 必撞 409，而 409 说「内容被别人改过」，把人指向完全错误的方向</li>
 *   <li>编辑草稿后 {@code tc_case.updated_at} 不动，详情页「最后修改」纹丝不动</li>
 * </ol>
 *
 * 前三次都是人核对或联调撞出来的，第四次是穷举出来的。
 * **注释拦不住第五次，因为注释不会在 CI 里失败。**
 *
 * <h3>为什么不是「扫 SQL 强制加别名」</h3>
 *
 * CLI 那侧的失效点在 SQL（一条 JOIN 里四个同名列），所以它用
 * {@code AS draft_status} 消歧，扫 SQL 就能守住。
 *
 * <p>但平台这侧**一次跨表 JOIN 都没有** —— 用的是 MyBatis-Plus 按主键分别查，
 * 失效点全部在 Java 层：两个实体的同名 getter 被赋给同一个 DTO 字段。
 * 扫 SQL 在这里一个问题都挡不住。**同一个病根，两侧的防线位置不同。**
 */
@SpringBootTest(classes = PlatformTestApp.class)
class DtoFieldSourceTest {

    @Autowired
    private CaseWriteService writeService;

    @Autowired
    private CaseQueryService queryService;

    @Autowired
    private TcCaseMapper caseMapper;

    @Autowired
    private TcStepMapper stepMapper;

    @Test
    @DisplayName("三个同名字段在两个 DTO 里都取自正确的表")
    void everyAmbiguousFieldComesFromTheRightTable() throws Exception {
        String caseId = UUID.randomUUID().toString();

        // ── 造出「两表的三个字段全都不同」的状态 ──────────────────
        // draft → update → commit 之后：tc_case.version=1，tc_step.version=2
        // 这个差值不是构造出来的巧合，是两张表各自计数的必然结果
        writeService.draft(caseId, "字段来源核对", CaseType.PC_WEB, "test");
        // ⚠️ case_code 要唯一：uk_case_code 会让第二次跑直接撞 DuplicateKey，
        //    而那个错误跟本测试要验的东西毫无关系，只会浪费诊断时间
        int v = writeService.update(caseId, completeDraft(caseId), 0).version();
        writeService.commit(caseId, v);

        // 模拟存量案例：78 条 ACTIVE 案例正是这个状态 —— tc_case=ACTIVE 而 tc_step 仍是 DRAFT
        TcCase c = caseMapper.selectById(caseId);
        c.setStatus(CaseStatus.ACTIVE);
        // ⚠️ 把 tc_case 的时间推到一天前，否则两张表的 updated_at 会是同一分钟 ——
        //    那样即使取错表，断言也照样通过。**构造不出差异的断言等于没断言**
        c.setUpdatedAt(OffsetDateTime.now().minusDays(1));
        caseMapper.updateById(c);

        int caseVersion = caseMapper.selectById(caseId).getVersion();
        TcStep step = stepMapper.selectOne(new LambdaQueryWrapper<TcStep>().eq(TcStep::getCaseId, caseId));
        int stepVersion = step.getVersion();

        // 前提没成立的话，后面的断言都是假绿 —— 两个值相等时，取错表也能通过
        assertNotEquals(caseVersion, stepVersion,
                "构造失败：两表的 version 必须不同，否则这组断言分辨不出取错表");

        // ── CaseDetailVO（读侧详情）────────────────────────────
        CaseDetailVO detail = queryService.detail(caseId);

        assertEquals(caseVersion, detail.version(),
                "CaseDetailVO.version 必须来自 tc_case —— 它是展示用的平台版本");
        assertEquals(stepVersion, detail.editVersion(),
                "CaseDetailVO.editVersion 必须来自 tc_step —— 写侧三个接口用的是它，取错前端 PUT 必撞 409");
        assertEquals(CaseStatus.ACTIVE.name(), detail.status(),
                "CaseDetailVO.status 必须来自 tc_case —— 用户在案例中心看到的就是这个");
        assertEquals(DisplayTime.toMinute(caseMapper.selectById(caseId).getUpdatedAt()), detail.updatedAt(),
                "CaseDetailVO.updatedAt 必须来自 tc_case");
        assertEquals(DisplayTime.toMinute(step.getUpdatedAt()), detail.editUpdatedAt(),
                "CaseDetailVO.editUpdatedAt 必须来自 tc_step —— 编辑草稿只动这一个，"
                        + "取错的话用户改完保存会看到「最后修改」纹丝不动");
        assertNotEquals(detail.updatedAt(), detail.editUpdatedAt(),
                "构造失败：两个时间必须不同，否则这组断言分辨不出取错表");

        // ── DraftView（写侧）──────────────────────────────────
        var draft = writeService.view(caseId);

        assertEquals(stepVersion, draft.version(),
                "DraftView.version 必须来自 tc_step —— 它是 CAS 的比较值");
        assertEquals(CaseStatus.DRAFT.name(), draft.status(),
                "DraftView.status 必须来自 tc_step 的编辑期状态机；"
                        + "取 tc_case 的话这里会是 ACTIVE —— 而 tc_step 从不进入 ACTIVE");
        assertEquals(CaseStatus.ACTIVE.name(), draft.platformStatus(),
                "DraftView.platformStatus 必须来自 tc_case");

        // ── 两个 DTO 之间的交叉一致性 ──────────────────────────
        // 前端可能从任一接口拿版本号去写。两边对同一个概念必须给同一个值，
        // 否则「从哪个接口读的」会变成一个隐藏的分支
        assertEquals(detail.editVersion(), draft.version(),
                "CaseDetailVO.editVersion 与 DraftView.version 是同一个东西，必须相等");
        assertEquals(detail.status(), draft.platformStatus(),
                "CaseDetailVO.status 与 DraftView.platformStatus 是同一个东西，必须相等");

        cleanup(caseId);
    }

    /** 一份能通过 STD 校验、且表头六个字段齐全的草稿 —— commit 需要它完整 */
    private String completeDraft(String caseId) {
        // ⚠️ 序号必须是 4 位数字（STD-007 的 ^ATP-[A-Z]+-[0-9]{4}$），
        //    不能用 caseId 的十六进制片段 —— 那会带字母，校验直接不过
        int seq = 9000 + Math.abs(caseId.hashCode() % 1000);
        return """
                {"case_code":"ATP-CART-%04d","title":"字段来源核对","module_id":"M003",
                 "priority":"P2","author":"test","precondition":null,
                 "steps":[{"seq":1,"action":"OPEN_URL","input_data":"${base_url}/cart",
                           "wait_strategy":"NONE","wait_timeout_sec":10,"on_failure":"ABORT"},
                          {"seq":2,"action":"ASSERT_VISIBLE","locator_type":"XPATH",
                           "locator_value":"//div[@data-testid='cart-page']",
                           "wait_strategy":"VISIBLE","wait_timeout_sec":10,"on_failure":"ABORT"}]}
                """.formatted(seq);
    }

    private void cleanup(String caseId) {
        stepMapper.delete(new LambdaQueryWrapper<TcStep>().eq(TcStep::getCaseId, caseId));
        caseMapper.deleteById(caseId);
    }
}
