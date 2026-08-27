package dev.kanashi.atp.cli.store;

import dev.kanashi.atp.cli.model.CaseType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 起一个真 PostgreSQL，按 V0 → V1 的顺序跑迁移。
 *
 * <p>⭐ 测试<b>先建老表、再跑改造脚本</b>，而不是直接建改造后的表 ——
 * 这样 V1 是不是真能在老平台的形状上执行得下去，本身就被测到了。
 */
@Testcontainers
abstract class PgTestBase {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("atp")
            .withUsername("atp")
            .withPassword("atp");

    static ConnectionFactory connections;
    static CaseStore store;

    @BeforeAll
    static void migrate() throws Exception {
        connections = ConnectionFactory.of(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        store = new CaseStore(connections);
        runScript("db/migration/V0__baseline_legacy.sql");
        runScript("db/migration/V1__ai_draft_state.sql");
    }

    @BeforeEach
    void truncate() throws SQLException {
        try (Connection c = connections.open(); Statement st = c.createStatement()) {
            // ⚠️ 顺序不能反，也不能只删父表。
            //    本库不建外键、没有 ON DELETE CASCADE，只删 tc_case 会把孤儿步骤
            //    漏给下一个用例 —— 这个坑在写 SchemaShapeTest 时真的踩到了。
            //    M5 的清理任务面对的是同一个约束。
            st.execute("DELETE FROM tc_step");
            st.execute("DELETE FROM tc_case");
        }
    }

    private static void runScript(String resource) throws IOException, SQLException {
        try (InputStream in = PgTestBase.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("找不到迁移脚本: " + resource);
            }
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            // ⭐ 整份脚本一次执行，不按分号切：V1 用 BEGIN/COMMIT 包成一个原子事务，
            //    切开就等于把原子性拆掉了 —— 那样测的就不是要部署的那个东西。
            //    pgjdbc 的 Statement.execute 走简单查询协议，支持多语句串。
            try (Connection c = connections.open(); Statement st = c.createStatement()) {
                st.execute(sql);
            }
        }
    }


    /** 执行平台，老平台原有概念。 */
    static final CaseType PC_WEB = CaseType.PC_WEB;

    /** 一份能通过 ck_case_complete 的完整草稿（原始 JSON —— 整份写进 tc_step.step_json）。 */
    static String completeDraft(String title) {
        return completeDraft(title, 2);
    }

    static String completeDraft(String title, int stepCount) {
        StringBuilder steps = new StringBuilder("[");
        for (int i = 1; i <= stepCount; i++) {
            steps.append(i > 1 ? "," : "")
                 .append("{\"seq\":%d,\"action\":\"CLICK\",\"wait_strategy\":\"VISIBLE\"}".formatted(i));
        }
        steps.append("]");
        return """
                {"case_code":"ATP-CART-0001","title":"%s","module_id":"M003","priority":"P1",
                 "author":"qa.kanashi","precondition":"已登录且购物车非空","steps":%s}
                """.formatted(title, steps);
    }

}
