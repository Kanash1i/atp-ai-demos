package dev.kanashi.atp.cli.store;

import dev.kanashi.atp.cli.model.ExitCode;
import dev.kanashi.atp.cli.model.StoreResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("commit：一条 CAS UPDATE 扛住并发与重放")
class ConcurrentCommitTest extends MySqlTestBase {

    @Test
    @DisplayName("10 个线程并发提交同一个 id+version → 1 个真提交 + 9 个幂等重放，全部退出码 0")
    void concurrentCommitSameKey() throws Exception {
        String caseId = UUID.randomUUID().toString();
        store.draft(caseId, PC_WEB, "购物车结算", "agent-a");
        StoreResult updated = store.update(caseId, 0, completeDraft("购物车结算"));
        assertThat(updated.row().version()).isEqualTo(1);

        int threads = 10;
        CyclicBarrier gate = new CyclicBarrier(threads);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Callable<StoreResult>> tasks = IntStream.range(0, threads)
                    .<Callable<StoreResult>>mapToObj(i -> () -> {
                        gate.await();
                        return store.commit(caseId, 1);
                    })
                    .toList();

            List<StoreResult> results = pool.invokeAll(tasks).stream()
                    .map(ConcurrentCommitTest::get)
                    .toList();

            assertThat(results).allSatisfy(r -> {
                assertThat(r.code())
                        .as("重放在语义上是成功，返回非 0 会让 agent 无限重试")
                        .isEqualTo(ExitCode.OK);
                assertThat(r.row().status()).isEqualTo("DRAFT");
                assertThat(r.row().version()).isEqualTo(2);
            });
            assertThat(results.stream().filter(r -> !r.replayed()).count()).isEqualTo(1);
            assertThat(results.stream().filter(StoreResult::replayed).count()).isEqualTo(threads - 1L);
        }
    }

    @Test
    @DisplayName("提交成功但响应丢失 → 重试返回同一行、replayed=true、退出码 0")
    void lostResponseThenRetry() {
        String caseId = UUID.randomUUID().toString();
        store.draft(caseId, PC_WEB, "登录成功", "agent-a");
        store.update(caseId, 0, completeDraft("登录成功"));

        StoreResult first = store.commit(caseId, 1);
        StoreResult retry = store.commit(caseId, 1);

        assertThat(first.replayed()).isFalse();
        assertThat(first.row().status()).isEqualTo("DRAFT");

        assertThat(retry.code()).isEqualTo(ExitCode.OK);
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.row().caseId()).isEqualTo(first.row().caseId());
        assertThat(retry.row().version()).isEqualTo(first.row().version());
    }

    @Test
    @DisplayName("落地状态是普通 DRAFT，不是新造的状态 —— 执行器与既有列表页无感知")
    void commitsIntoOrdinaryDraft() {
        String caseId = UUID.randomUUID().toString();
        store.draft(caseId, PC_WEB, "搜索结果排序", "agent-a");
        store.update(caseId, 0, completeDraft("搜索结果排序"));

        assertThat(store.commit(caseId, 1).row().status()).isEqualTo("DRAFT");
    }

    private static StoreResult get(Future<StoreResult> f) {
        try {
            return f.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
