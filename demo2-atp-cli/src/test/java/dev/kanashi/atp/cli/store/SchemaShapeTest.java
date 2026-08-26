package dev.kanashi.atp.cli.store;

import dev.kanashi.atp.cli.model.ExitCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 表结构本身承担的不变式 —— 这些不靠应用层代码保证，靠约束保证。 */
@DisplayName("表结构约束")
class SchemaShapeTest extends MySqlTestBase {

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
    @DisplayName("删案例级联删步骤 —— 每月清理任务不会留下孤儿步骤行")
    void deletingCaseCascadesToSteps() throws SQLException {
        String caseId = UUID.randomUUID().toString();
        store.draft(caseId, PC_WEB, "购物车结算", "agent-a");
        insertStep(caseId, 1);
        insertStep(caseId, 2);
        assertThat(count("SELECT COUNT(*) FROM tc_step WHERE case_id = '" + caseId + "'")).isEqualTo(2);

        try (var c = connections.open(); Statement st = c.createStatement()) {
            st.executeUpdate("DELETE FROM tc_case WHERE case_id = '" + caseId + "'");
        }

        assertThat(count("SELECT COUNT(*) FROM tc_step WHERE case_id = '" + caseId + "'")).isZero();
    }

    @Test
    @DisplayName("同一案例内 seq 不可重复 —— 靠唯一键，不靠应用层自觉")
    void duplicateSeqRejected() throws SQLException {
        String caseId = UUID.randomUUID().toString();
        store.draft(caseId, PC_WEB, "购物车结算", "agent-a");
        insertStep(caseId, 1);

        assertThatThrownBy(() -> insertStep(caseId, 1))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uk_step_case_seq");
    }

    @Test
    @DisplayName("module_id 必须存在于 tc_module —— 外键挡住模型编造的模块")
    void fabricatedModuleIdRejectedByForeignKey() {
        String caseId = UUID.randomUUID().toString();
        store.draft(caseId, PC_WEB, "购物车结算", "agent-a");

        var fabricated = new dev.kanashi.atp.cli.model.CaseDraft(
                "ATP-CART-0002", "购物车结算", "M999", "P1", "qa.kanashi", null, "{}");

        assertThat(store.update(caseId, 0, fabricated).code()).isEqualTo(ExitCode.INFRA_ERROR);
    }

    @Test
    @DisplayName("项目 → 模块 → 案例 的定位链路通")
    void projectModuleCaseChain() throws SQLException {
        String caseId = UUID.randomUUID().toString();
        store.draft(caseId, PC_WEB, "购物车结算", "agent-a");
        store.update(caseId, 0, completeDraft("购物车结算"));

        assertThat(count("""
                SELECT COUNT(*) FROM tc_case c
                  JOIN tc_module m ON m.module_id  = c.module_id
                  JOIN tc_project p ON p.project_id = m.project_id
                 WHERE p.project_code = 'ECSHOP' AND m.module_code = 'CART'
                """)).isEqualTo(1);
    }

    private static void insertStep(String caseId, int seq) throws SQLException {
        try (var c = connections.open(); Statement st = c.createStatement()) {
            st.executeUpdate("""
                    INSERT INTO tc_step (step_id, case_id, seq, step_json)
                    VALUES ('%s', '%s', %d, '{"action":"CLICK"}')
                    """.formatted(UUID.randomUUID(), caseId, seq));
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
