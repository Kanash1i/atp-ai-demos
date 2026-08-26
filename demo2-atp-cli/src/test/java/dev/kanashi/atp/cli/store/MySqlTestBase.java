package dev.kanashi.atp.cli.store;

import dev.kanashi.atp.cli.model.CaseDraft;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;

/**
 * 起一个真 MySQL，按 V0 → V1 的顺序跑迁移。
 *
 * <p>⭐ 测试<b>先建老表、再跑改造脚本</b>，而不是直接建改造后的表 ——
 * 这样 V1 是不是真能在老平台的形状上执行得下去，本身就被测到了。
 */
@Testcontainers
abstract class MySqlTestBase {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("atp")
            .withUsername("atp")
            .withPassword("atp");

    static ConnectionFactory connections;
    static CaseStore store;

    @BeforeAll
    static void migrate() throws Exception {
        connections = ConnectionFactory.of(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        store = new CaseStore(connections);
        runScript("db/migration/V0__baseline_legacy.sql");
        runScript("db/migration/V1__ai_draft_state.sql");
    }

    @BeforeEach
    void truncate() throws SQLException {
        try (Connection c = connections.open(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM tc_case");
        }
    }

    private static void runScript(String resource) throws IOException, SQLException {
        try (InputStream in = MySqlTestBase.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("找不到迁移脚本: " + resource);
            }
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            try (Connection c = connections.open(); Statement st = c.createStatement()) {
                for (String stmt : splitStatements(sql)) {
                    st.execute(stmt);
                }
            }
        }
    }

    /** 去掉整行 {@code --} 注释后按分号切分。脚本里没有字符串内分号，够用。 */
    private static List<String> splitStatements(String sql) {
        String stripped = Arrays.stream(sql.split("\n"))
                .map(line -> {
                    int idx = line.indexOf("--");
                    return idx >= 0 ? line.substring(0, idx) : line;
                })
                .reduce("", (a, b) -> a + "\n" + b);
        return Arrays.stream(stripped.split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** 执行平台，老平台原有概念（IOS / ANDROID / PC_WEB）。 */
    static final String PC_WEB = "PC_WEB";

    /** 一份能通过 ck_case_complete 的完整草稿。module_id 必须是 tc_module 里真实存在的值。 */
    static CaseDraft completeDraft(String title) {
        return new CaseDraft(
                "ATP-CART-0001", title, "M003", "P1", "qa.kanashi",
                "已登录且购物车非空",
                "{\"title\":\"" + title + "\",\"steps\":[]}");
    }
}
