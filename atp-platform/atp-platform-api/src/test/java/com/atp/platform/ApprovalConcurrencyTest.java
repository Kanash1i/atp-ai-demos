package com.atp.platform;

import com.atp.common.enums.ApprovalStatus;
import com.atp.common.enums.ApprovalType;
import com.atp.platform.entity.TcApproval;
import com.atp.platform.mapper.TcApprovalMapper;
import com.atp.platform.service.ApprovalAlreadyDecidedException;
import com.atp.platform.service.ApprovalService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审批的并发决策 —— 同一个仲裁思路在这个仓库里的第二处应用。
 *
 * <h3>这里防的是什么</h3>
 *
 * 两个审批人同时打开同一张单子，一个点批准、一个点驳回。
 * 没有仲裁的话，两次 UPDATE 都会成功，**后写的覆盖先写的** ——
 * 而两个人的界面都显示"操作成功"。最后单子是批准还是驳回，取决于毫秒级的先后。
 *
 * <p>仲裁点是 {@code ApprovalService.decide} 里那一行
 * {@code .eq(TcApproval::getStatus, ApprovalStatus.PENDING)}：
 * 把「当前必须还是待审」压进 UPDATE 自己的 WHERE。
 *
 * <h3>为什么先 select 检查一遍还不够</h3>
 *
 * {@code decide} 里确实先 select 判断了状态，但那只是**为了给出更好的错误信息**。
 * 真正的正确性全在 UPDATE 的 WHERE 里 —— select 与 update 之间存在时间窗，
 * 这正是 TOCTOU（check 完到 use 之间状态变了）。
 * 只靠 select 的话，10 个线程会全部通过检查，然后全部写入。
 */
@SpringBootTest(classes = PlatformTestApp.class)
class ApprovalConcurrencyTest {

    private static final int THREADS = 10;

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private TcApprovalMapper approvalMapper;

    @Test
    @DisplayName("并发决策：10 个人同时处理同一张待审单，恰好 1 个成功，其余全部被告知已决策")
    void onlyOneDeciderWins() throws Exception {
        String requestId = seedPendingApproval();

        AtomicInteger won = new AtomicInteger();
        AtomicInteger alreadyDecided = new AtomicInteger();
        ConcurrentLinkedQueue<String> unexpected = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);

        for (int i = 0; i < THREADS; i++) {
            // 一半批准一半驳回 —— 如果发生脏写，最终状态会是随机的那一个，
            // 而不是"恰好一个赢"。这比全都批准更能暴露覆盖问题
            ApprovalStatus decision = i % 2 == 0 ? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED;
            String who = "reviewer-" + i;
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    approvalService.decide(requestId, decision, who, "并发测试");
                    won.incrementAndGet();
                } catch (ApprovalAlreadyDecidedException e) {
                    alreadyDecided.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    unexpected.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发任务未在 60 秒内结束");

        assertTrue(unexpected.isEmpty(), "不应出现「已决策」以外的异常：" + unexpected);
        assertEquals(1, won.get(), "恰好一个审批人应当成功");
        assertEquals(THREADS - 1, alreadyDecided.get(), "其余都应被告知已被处理，而不是静默覆盖");

        // ⭐ 最关键的一条：落库的决策人必须是**赢的那一个**，且状态不再是 PENDING。
        //    脏写的典型表现是「状态是 A 的，decided_by 却是 B」—— 两次 UPDATE 交错了
        TcApproval finalState = approvalMapper.selectById(requestId);
        assertTrue(finalState.getStatus() == ApprovalStatus.APPROVED
                        || finalState.getStatus() == ApprovalStatus.REJECTED,
                "最终状态应当是一个确定的决策");
        assertTrue(finalState.getDecidedBy() != null && finalState.getDecidedBy().startsWith("reviewer-"),
                "决策人必须被记录下来");
        assertEquals(finalState.getStatus() == ApprovalStatus.APPROVED,
                Integer.parseInt(finalState.getDecidedBy().substring("reviewer-".length())) % 2 == 0,
                "状态与决策人必须来自同一次写入 —— 对不上就是两次 UPDATE 交错了");

        approvalMapper.deleteById(requestId);
    }

    private String seedPendingApproval() {
        TcApproval a = new TcApproval();
        a.setRequestId(UUID.randomUUID().toString());
        a.setType(ApprovalType.CASE_CHANGE);
        a.setTitle("并发决策测试");
        a.setSummary("10 个审批人同时处理");
        a.setStatus(ApprovalStatus.PENDING);
        a.setSubmitter("test");
        approvalMapper.insert(a);
        return a.getRequestId();
    }
}
