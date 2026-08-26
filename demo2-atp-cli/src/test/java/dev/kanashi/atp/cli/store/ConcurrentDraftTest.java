package dev.kanashi.atp.cli.store;

import dev.kanashi.atp.cli.model.ExitCode;
import dev.kanashi.atp.cli.model.StoreResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("draft：主键唯一约束即幂等约束")
class ConcurrentDraftTest extends PgTestBase {

    @Test
    @DisplayName("10 个线程用同一个 UUID 建草稿 → 只插入 1 行，其余 9 个是幂等重放且退出码 0")
    void concurrentDraftSameId() throws Exception {
        String caseId = UUID.randomUUID().toString();
        int threads = 10;

        CyclicBarrier gate = new CyclicBarrier(threads);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Callable<StoreResult>> tasks = java.util.stream.IntStream.range(0, threads)
                    .<Callable<StoreResult>>mapToObj(i -> () -> {
                        gate.await();   // 尽量压到同一瞬间
                        return store.draft(caseId, PC_WEB, "购物车结算", "agent-" + i);
                    })
                    .toList();

            List<StoreResult> results = pool.invokeAll(tasks).stream()
                    .map(ConcurrentDraftTest::get)
                    .toList();

            assertThat(results).allSatisfy(r -> {
                assertThat(r.code()).isEqualTo(ExitCode.OK);          // 重放也必须是 0
                assertThat(r.row().caseId()).isEqualTo(caseId);
                assertThat(r.row().status()).isEqualTo("AI_DRAFT");
            });
            assertThat(results.stream().filter(r -> !r.replayed()).count())
                    .as("只有一个线程真正插入成功").isEqualTo(1);
            assertThat(results.stream().filter(StoreResult::replayed).count())
                    .as("其余全部走幂等重放").isEqualTo(threads - 1L);
            assertThat(countRows(caseId)).as("库里只有一行").isEqualTo(1);
        }
    }

    @Test
    @DisplayName("同一个 UUID 顺序重试（模拟响应丢失后重发）→ 第二次是重放，不产生新行")
    void retrySameIdIsReplay() {
        String caseId = UUID.randomUUID().toString();

        StoreResult first = store.draft(caseId, PC_WEB, "登录成功", "agent-a");
        StoreResult second = store.draft(caseId, PC_WEB, "登录成功", "agent-a");

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.code()).isEqualTo(ExitCode.OK);
        assertThat(second.row().version()).isEqualTo(0);
    }

    private static int countRows(String caseId) throws Exception {
        try (Connection c = connections.open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM tc_case WHERE case_id = '" + caseId + "'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static StoreResult get(Future<StoreResult> f) {
        try {
            return f.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
