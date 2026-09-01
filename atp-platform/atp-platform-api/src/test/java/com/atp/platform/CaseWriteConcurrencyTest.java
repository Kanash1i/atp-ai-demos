package com.atp.platform;

import com.atp.common.enums.CaseType;
import com.atp.platform.entity.TcCase;
import com.atp.platform.entity.TcStep;
import com.atp.platform.mapper.TcCaseMapper;
import com.atp.platform.mapper.TcStepMapper;
import com.atp.platform.service.CaseConflictException;
import com.atp.platform.service.CaseWriteService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 写侧的并发仲裁 —— 对着真 PostgreSQL 压。
 *
 * <h3>为什么这组测试必须存在</h3>
 *
 * {@code CaseWriteMapper} 的正确性**全在 SQL 的 WHERE 子句里**（幂等键做主键 +
 * ON CONFLICT DO NOTHING、把状态与版本压进同一条 UPDATE 的 WHERE）。
 * 这种正确性读代码看不出来 —— 它只在真并发下才表现出来，而且失败的方式是
 * **脏写**：两个调用都返回成功，数据却只剩一份、或者互相覆盖。
 *
 * <p>⚠️ 不能用 H2 或 mock：验的正是 PG 的 {@code ON CONFLICT} 与 CAS UPDATE
 * 在真并发下的行为。换个数据库、或者把 SQL 换成 mock，验的就不是同一件事了。
 *
 * <h3>与 demo2 CLI 那 19 个测试的关系</h3>
 *
 * CLI 侧 {@code internal/store} 有一组同形状的测试（10 个 goroutine 打同一个 key）。
 * 等 CLI 迁移到调平台 API 之后，幂等与 CAS 的实现就搬到这一侧了 ——
 * **这组测试是那次迁移的前置条件**：先有等价证据，再迁，否则整个仓库里
 * 唯一能拿数字说话的并发验证会净减少一份。
 */
@SpringBootTest(classes = PlatformTestApp.class)
class CaseWriteConcurrencyTest {

    /** 并发度。10 个线程同时打同一行，足以稳定复现仲裁行为 */
    private static final int THREADS = 10;

    @Autowired
    private CaseWriteService writeService;

    @Autowired
    private TcCaseMapper caseMapper;

    @Autowired
    private TcStepMapper stepMapper;

    @Test
    @DisplayName("draft 幂等：同一个 caseId 并发建 10 次，只产生一条案例，10 次调用全部成功")
    void draftIsIdempotentUnderConcurrency() throws Exception {
        String caseId = UUID.randomUUID().toString();
        AtomicInteger ok = new AtomicInteger();
        ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();

        runConcurrently(() -> {
            try {
                writeService.draft(caseId, "并发幂等测试", CaseType.PC_WEB, "test");
                ok.incrementAndGet();
            } catch (Exception e) {
                errors.add(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        });

        // ⭐ 幂等的定义是「重复调用不产生额外副作用」，**不是**「重复调用会失败」。
        //    所以这里断言的是 10 次全成功 —— 失败一次都不行：
        //    CLI 那边把「写成功但响应丢失 → 重试」当成正常路径，
        //    重试要是报错，agent 就会以为没写成功而无限重试
        assertEquals(THREADS, ok.get(), "10 次并发 draft 应全部成功，实际失败：" + errors);

        List<TcCase> cases = caseMapper.selectList(
                new LambdaQueryWrapper<TcCase>().eq(TcCase::getCaseId, caseId));
        assertEquals(1, cases.size(), "同一个 caseId 只应产生一条案例");

        long steps = stepMapper.selectCount(
                new LambdaQueryWrapper<TcStep>().eq(TcStep::getCaseId, caseId));
        assertEquals(1, steps, "步骤行同样只应有一条");

        cleanup(caseId);
    }

    @Test
    @DisplayName("update CAS：10 个线程拿同一个 version 并发写，恰好 1 个成功，其余全部拿到版本冲突")
    void updateIsSerializedByCas() throws Exception {
        String caseId = UUID.randomUUID().toString();
        int baseVersion = writeService.draft(caseId, "并发 CAS 测试", CaseType.PC_WEB, "test").version();

        AtomicInteger won = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();
        ConcurrentLinkedQueue<String> unexpected = new ConcurrentLinkedQueue<>();

        java.util.concurrent.atomic.AtomicInteger seq = new java.util.concurrent.atomic.AtomicInteger();
        runConcurrently(() -> {
            try {
                // 10 个线程都拿着同一个 baseVersion —— 这正是「用户 A 与用户 B
                // 同时打开同一条草稿编辑」的形状
                //
                // ⚠️ 每个线程写**不同的内容**，这一点在加入幂等重放判定之后变成必须的：
                //    内容相同的话，CAS 落空的那几个会被正确识别为「你要写的东西已经在库里了」
                //    → 幂等成功。那是对的行为，但它测不出「恰好一个赢」——
                //    因为大家想要的结果本来就一样，谁写进去的都无所谓。
                //    **真正的竞争写入必须内容不同**，否则测的是幂等不是仲裁。
                writeService.update(caseId, minimalDraft("线程 " + seq.incrementAndGet()), baseVersion);
                won.incrementAndGet();
            } catch (CaseConflictException e) {
                conflicted.incrementAndGet();
            } catch (Exception e) {
                unexpected.add(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        });

        assertTrue(unexpected.isEmpty(), "不应出现版本冲突以外的异常：" + unexpected);
        // ⭐ 这一条是整组测试的核心：**恰好一个赢**。
        //    赢家多于一个 = 脏写（后写的把先写的覆盖了，而两边都以为自己成功了）；
        //    赢家为零 = 活锁（谁也写不进去）。两种都是静默的，只有压出来才看得见
        assertEquals(1, won.get(), "恰好一个线程应当写入成功");
        assertEquals(THREADS - 1, conflicted.get(), "其余线程都应拿到版本冲突，而不是静默覆盖");

        // 赢家把版本推进了一格 —— 版本必须是「写成功才涨」，否则乐观锁就失效了
        assertEquals(baseVersion + 1, currentVersion(caseId),
                "版本应当且只应当被推进一格");

        // ⭐ 「编辑期只写 tc_step」这个核心设计的证据：tc_case.version 全程不动。
        //    这条不变量原先由 CLI 侧的测试守着（它能直接读 tc_case.version）；
        //    迁移后 CLI 看不见这个字段了 —— **看得见反而说明边界没划干净**，
        //    所以断言搬到这一侧。迁移会搬走证据，得确保它在新地方落了地而不是路上掉了。
        assertEquals(0, caseMapper.selectById(caseId).getVersion(),
                "编辑期只写 tc_step，tc_case.version 不该被动过");

        cleanup(caseId);
    }

    @Test
    @DisplayName("update 串行推进：连续 5 次带上一次拿到的 version，每次都应成功且版本逐格递增")
    void updateAdvancesVersionSerially() {
        String caseId = UUID.randomUUID().toString();
        int version = writeService.draft(caseId, "串行推进测试", CaseType.PC_WEB, "test").version();

        // ⚠️ 有了并发那条还要这条：CAS 写得过于严格（比如永远返回冲突）
        //    同样能让并发测试通过（恰好 0 个赢不了、1 个赢……）却让正常流程完全不可用。
        //    「拒绝所有人」和「只让一个人过」在并发断言下长得很像
        for (int i = 1; i <= 5; i++) {
            version = writeService.update(caseId, minimalDraft("第 " + i + " 次"), version).version();
            assertEquals(i, version, "第 " + i + " 次更新后版本应为 " + i);
        }
        cleanup(caseId);
    }

    /** 直接查库拿版本 —— 不经服务层，避免用被测对象来验证被测对象 */
    private int currentVersion(String caseId) {
        return stepMapper.selectOne(new LambdaQueryWrapper<TcStep>()
                .eq(TcStep::getCaseId, caseId)).getVersion();
    }

    // ── 工具 ────────────────────────────────────────────────────

    /**
     * 让 N 个线程**尽可能同时**开始。
     *
     * <p>⚠️ 不用 CountDownLatch 卡起跑线的话，第一个线程往往在最后一个线程
     * 还没启动时就跑完了 —— 那测的是串行，不是并发，而且会稳定通过。
     */
    private void runConcurrently(Runnable body) throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);

        for (int i = 0; i < THREADS; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    body.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发任务未在 60 秒内全部结束");
    }

    /**
     * 一份形状合法的最小草稿。
     *
     * @param mark 写进 title 的标记 —— 让每次调用产出**不同的内容**。
     *             并发用例依赖这一点：内容相同的话，落空的那几个会被幂等重放判定
     *             识别成成功，于是测不出「恰好一个赢」
     */
    private String minimalDraft(String mark) {
        return """
                {"case_code":"ATP-CART-9999","title":"并发测试 %s","module_id":"M003",
                 "priority":"P2","author":"test","precondition":null,
                 "steps":[{"seq":1,"action":"OPEN_URL","input_data":"${base_url}/cart",
                           "wait_strategy":"NONE","wait_timeout_sec":10,"on_failure":"ABORT"},
                          {"seq":2,"action":"ASSERT_VISIBLE","locator_type":"XPATH",
                           "locator_value":"//div[@data-testid='cart-page']",
                           "wait_strategy":"VISIBLE","wait_timeout_sec":10,"on_failure":"ABORT"}]}
                """.formatted(mark);
    }

    private void cleanup(String caseId) {
        stepMapper.delete(new LambdaQueryWrapper<TcStep>().eq(TcStep::getCaseId, caseId));
        caseMapper.deleteById(caseId);
    }
}
