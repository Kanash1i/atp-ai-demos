package dev.kanashi.atp.cli.store;

import dev.kanashi.atp.cli.model.ExitCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 表结构本身承担的不变式 —— 这些不靠应用层代码保证，靠约束保证。 */
@DisplayName("表结构约束")
class SchemaShapeTest extends PgTestBase {

    @Test
    @DisplayName("多条未编号的草稿可以并存 —— UNIQUE 索引允许多个 NULL")
    void multipleDraftsWithNullCaseCodeCoexist() throws SQLException {
        for (int i = 0; i < 5; i++) {
            assertThat(store.draft(UUID.randomUUID().toString(), PC_WEB, "草稿 " + i, "agent-a").code())
                    .isEqualTo(ExitCode.OK);
        }
        assertThat(count("SELECT COUNT(*) FROM tc_case WHERE case_code IS NULL")).isEqualTo(5);
    }

    @Test
    @DisplayName("⚠️ 无外键 = 无级联：只删父表会留下孤儿步骤，清理任务必须自己删两次")
    void deletingCaseLeavesOrphanSteps() throws SQLException {
        String caseId = UUID.randomUUID().toString();
        store.draft(caseId, PC_WEB, "购物车结算", "agent-a");
        // draft 已经建好 tc_step 那一行

        try (var c = connections.open(); Statement st = c.createStatement()) {
            st.executeUpdate("DELETE FROM tc_case WHERE case_id = '" + caseId + "'");
        }

        // 这不是 bug，是"不建外键"的必然代价 —— 这个断言存在的意义就是把代价钉死，
        // 免得 M5 写清理任务时想当然以为有级联。
        // tc_step 一比一，所以孤儿是 1 行（draft 时建的那一行）。
        assertThat(count("SELECT COUNT(*) FROM tc_step WHERE case_id = '" + caseId + "'"))
                .as("父表没了，步骤那行还在 —— 孤儿行")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("清理任务的正确删法：先子后父（顺序反了就找不到要删的步骤了）")
    void cleanupMustDeleteChildrenFirst() throws SQLException {
        String caseId = UUID.randomUUID().toString();
        store.draft(caseId, PC_WEB, "购物车结算", "agent-a");

        try (var c = connections.open(); Statement st = c.createStatement()) {
            // ① 先按条件选出这一批的 case_id（真实清理任务里带 LIMIT 分批）
            //    ② 再删子表 —— 必须在删父表之前，否则条件就查不到了
            // PG 没有 MySQL 那条"不能在子查询里引用正在删的表"的限制，可以直写
            st.executeUpdate("""
                    DELETE FROM tc_step WHERE case_id IN (
                        SELECT case_id FROM tc_case WHERE status = 4 /* AI_DRAFT */)
                    """);
            // ③ 最后删父表
            st.executeUpdate("DELETE FROM tc_case WHERE status = 4 /* AI_DRAFT */");
        }

        assertThat(count("SELECT COUNT(*) FROM tc_step")).isZero();
        assertThat(count("SELECT COUNT(*) FROM tc_case")).isZero();
    }


    @Test
    @DisplayName("⚠️ 数据库不挡编造的 module_id —— 引用完整性是写入方的责任")
    void fabricatedModuleIdIsAcceptedByDb() throws SQLException {
        String caseId = UUID.randomUUID().toString();
        store.draft(caseId, PC_WEB, "购物车结算", "agent-a");
        // module_id 编辑期只活在 step_json 里，commit 那一刻才投影进 tc_case 的列
        store.update(caseId, 0, completeDraft("购物车结算").replace("M003", "M999"));

        assertThat(store.commit(caseId, 1).code())
                .as("不建外键，M999 照样落得进去")
                .isEqualTo(ExitCode.OK);

        // 这个断言不是在庆祝，是在钉死一条责任转移：
        // 「防模型编造 module_id」从数据库挪到了 atp validate（M3），那里必须对着 tc_module 查。
        assertThat(count("""
                SELECT COUNT(*) FROM tc_case c
                 WHERE c.module_id IS NOT NULL
                   AND c.module_id NOT IN (SELECT module_id FROM tc_module)
                """))
                .as("库里已经存在一条引用了不存在模块的案例")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("项目 → 模块 → 案例 的定位链路通")
    void projectModuleCaseChain() throws SQLException {
        String caseId = UUID.randomUUID().toString();
        store.draft(caseId, PC_WEB, "购物车结算", "agent-a");
        store.update(caseId, 0, completeDraft("购物车结算"));
        store.commit(caseId, 1);   // 表头（含 module_id）在这一刻才进 tc_case

        assertThat(count("""
                SELECT COUNT(*) FROM tc_case c
                  JOIN tc_module m ON m.module_id  = c.module_id
                  JOIN tc_project p ON p.project_id = m.project_id
                 WHERE p.project_code = 'ECSHOP' AND m.module_code = 'CART'
                """)).isEqualTo(1);
    }

    /** tc_step 是一比一 —— draft 时就已经建好那一行了，这里只用来造孤儿场景。 */
    private static void insertOrphanStep(String caseId) throws SQLException {
        try (var c = connections.open(); Statement st = c.createStatement()) {
            st.executeUpdate("""
                    INSERT INTO tc_step (step_id, case_id, step_json, status, version)
                    VALUES ('%s', '%s', '{"steps":[]}'::jsonb, 4, 0)
                    """.formatted(UUID.randomUUID(), caseId));
        }
    }

    private static int count(String sql) throws SQLException {
        try (var c = connections.open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
