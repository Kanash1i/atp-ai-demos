package com.atp.platform;

import com.atp.common.enums.CaseType;
import com.atp.platform.entity.TcStep;
import com.atp.platform.mapper.TcCaseMapper;
import com.atp.platform.mapper.TcStepMapper;
import com.atp.platform.service.CaseConflictException;
import com.atp.platform.service.CaseWriteService;
import com.atp.platform.service.CaseWriteService.DraftView;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 幂等重放：写成功了但响应丢在路上，调用方重试。
 *
 * <h3>为什么这组测试关乎正确性而不只是体验</h3>
 *
 * 「重放在语义上是成功」是幂等的第一条规则。不认它的话：
 *
 * <ul>
 *   <li>重放被当成**状态冲突** → 按契约那意味着「停下，问人」→
 *       agent 会停下来找人，**问一个其实已经提交成功的案例**</li>
 *   <li>或者被当成**版本冲突** → 调用方重新拉取再提交 → 提交一条已经提交过的东西</li>
 * </ul>
 *
 * <p>两种都是把一次成功报告成失败，而调用方据此做的补救动作全是错的。
 */
@SpringBootTest(classes = PlatformTestApp.class)
class IdempotentReplayTest {

    @Autowired
    private CaseWriteService writeService;

    @Autowired
    private TcCaseMapper caseMapper;

    @Autowired
    private TcStepMapper stepMapper;

    @Test
    @DisplayName("draft 重放：同一个 caseId 再建一次，replayed=true 且不产生第二条")
    void draftReplayIsMarked() {
        String caseId = UUID.randomUUID().toString();

        DraftView first = writeService.draft(caseId, "重放核对", CaseType.PC_WEB, "test");
        assertFalse(first.replayed(), "第一次是新建，不该标成重放");

        DraftView again = writeService.draft(caseId, "重放核对", CaseType.PC_WEB, "test");
        assertTrue(again.replayed(), "同一个 caseId 再来一次是重放 —— 这是幂等键存在的意义");
        assertEquals(first.version(), again.version(), "重放不该推进版本");

        cleanup(caseId);
    }

    @Test
    @DisplayName("update 重放：响应丢失后用同一个 version 重发同样内容，是成功不是冲突")
    void updateReplayIsSuccessNotConflict() {
        String caseId = UUID.randomUUID().toString();
        writeService.draft(caseId, "重放核对", CaseType.PC_WEB, "test");
        String content = draftJson(caseId, "原始内容");

        DraftView ok = writeService.update(caseId, content, 0);
        assertFalse(ok.replayed());
        assertEquals(1, ok.version());

        // 模拟「上一次其实成功了，但响应丢在路上」：调用方拿着旧 version 原样重发
        DraftView replay = writeService.update(caseId, content, 0);
        assertTrue(replay.replayed(), "同一个 version + 同样内容 = 重放，必须当成功");
        assertEquals(1, replay.version(), "重放不该再推进一格 —— 那会让后续的 CAS 全部错位");

        cleanup(caseId);
    }

    @Test
    @DisplayName("⭐ update 的重放判据必须比对内容：同一个 version 但不同内容是真冲突")
    void updateWithSameVersionButDifferentContentIsConflict() {
        String caseId = UUID.randomUUID().toString();
        writeService.draft(caseId, "重放核对", CaseType.PC_WEB, "test");

        // A 写成功，版本推进到 1
        writeService.update(caseId, draftJson(caseId, "A 写的内容"), 0);

        // B 手上还是 version=0，但它想写的是**另一份内容**
        //
        // ⚠️ 这正是「只看版本号前进了一格」不够的地方：B 的版本条件同样满足
        //    （库里 version=1 == 0+1），但它不是重放 —— 它想写的东西一个字都没进去。
        //    当成重放返回成功的话，B 会以为自己写成功了，而实际上被 A 覆盖了。
        CaseConflictException e = assertThrows(CaseConflictException.class,
                () -> writeService.update(caseId, draftJson(caseId, "B 写的内容"), 0),
                "同一个 version 但内容不同，必须是冲突而不是重放");
        assertEquals(CaseConflictException.Kind.VERSION, e.kind(),
                "这是版本冲突（重来一遍），不是状态冲突（别再试了）");

        cleanup(caseId);
    }

    @Test
    @DisplayName("commit 重放：已落地后用同一个 version 再提交，是成功不是「已提交过」")
    void commitReplayIsSuccessNotStateConflict() {
        String caseId = UUID.randomUUID().toString();
        writeService.draft(caseId, "重放核对", CaseType.PC_WEB, "test");
        int v = writeService.update(caseId, draftJson(caseId, "内容"), 0).version();

        DraftView committed = writeService.commit(caseId, v);
        assertFalse(committed.replayed());
        assertEquals("DRAFT", committed.status());

        // 模拟提交成功但响应丢失，调用方原样重试
        DraftView replay = writeService.commit(caseId, v);
        assertTrue(replay.replayed(),
                "提交的重放必须当成功 —— 报「已经提交过了」的话，"
                        + "按契约那是「停下问人」，于是有人会来问一个已经成功的案例");
        assertEquals(committed.version(), replay.version(), "重放不该推进版本");

        cleanup(caseId);
    }

    @Test
    @DisplayName("真正的状态冲突仍要报出来：版本对不上且已落地")
    void genuineStateConflictStillThrows() {
        String caseId = UUID.randomUUID().toString();
        writeService.draft(caseId, "重放核对", CaseType.PC_WEB, "test");
        int v = writeService.update(caseId, draftJson(caseId, "内容"), 0).version();
        writeService.commit(caseId, v);

        // ⚠️ 拿一个**对不上**的版本去提交已落地的案例 —— 这不是重放，是真的状态冲突。
        //    重放的判据是「版本恰好前进一格」，差一格以上说明中间还发生过别的事
        CaseConflictException e = assertThrows(CaseConflictException.class,
                () -> writeService.commit(caseId, v + 5));
        assertEquals(CaseConflictException.Kind.STATE, e.kind());

        cleanup(caseId);
    }

    /** case_code 用 caseId 派生，保证重复跑不撞 uk_case_code */
    private String draftJson(String caseId, String title) {
        int seq = 9000 + Math.abs(caseId.hashCode() % 1000);
        return """
                {"case_code":"ATP-CART-%04d","title":"%s","module_id":"M003",
                 "priority":"P2","author":"test","precondition":null,
                 "steps":[{"seq":1,"action":"OPEN_URL","input_data":"${base_url}/cart",
                           "wait_strategy":"NONE","wait_timeout_sec":10,"on_failure":"ABORT"},
                          {"seq":2,"action":"ASSERT_VISIBLE","locator_type":"XPATH",
                           "locator_value":"//div[@data-testid='cart-page']",
                           "wait_strategy":"VISIBLE","wait_timeout_sec":10,"on_failure":"ABORT"}]}
                """.formatted(seq, title);
    }

    private void cleanup(String caseId) {
        stepMapper.delete(new LambdaQueryWrapper<TcStep>().eq(TcStep::getCaseId, caseId));
        caseMapper.deleteById(caseId);
    }
}
