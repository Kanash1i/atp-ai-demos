package dev.kanashi.atp.cli.store;

import dev.kanashi.atp.cli.model.CaseStatus;
import dev.kanashi.atp.cli.model.ExitCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ 编辑期的写入全部落在 {@code tc_step} 一张表一行上，{@code tc_case} 只在 commit 那一刻被写一次。
 *
 * <p>这条设计的收益：<b>最高频的路径（反复改草稿）不跨表</b>，
 * 也就没有跨表事务、没有加锁顺序问题。跨表只发生在 commit，一份草稿一次。
 */
@DisplayName("编辑期写入的隔离与 commit 的投影")
class StepStorageTest extends PgTestBase {

    @Test
    @DisplayName("update 只写 tc_step —— tc_case 的 version 与表头一动不动")
    void updateDoesNotTouchCaseTable() throws SQLException {
        String id = UUID.randomUUID().toString();
        store.draft(id, PC_WEB, "购物车结算", "agent-a");

        store.update(id, 0, completeDraft("购物车结算", 3));
        store.update(id, 1, completeDraft("购物车结算（二稿）", 5));

        var row = store.show(id).row();
        assertThat(row.version()).as("tc_step 的 version 跟着编辑走").isEqualTo(2);
        assertThat(row.platformVersion()).as("tc_case 的 version 编辑期不动").isZero();
        assertThat(one("SELECT case_code FROM tc_case WHERE case_id='" + id + "'"))
                .as("表头此刻还只活在 step_json 里").isNull();
        assertThat(row.draftJson()).contains("二稿");
    }

    @Test
    @DisplayName("tc_step 是一比一 —— 反复 update 也只有一行")
    void oneStepRowPerCase() throws SQLException {
        String id = UUID.randomUUID().toString();
        store.draft(id, PC_WEB, "购物车结算", "agent-a");
        for (int v = 0; v < 4; v++) {
            store.update(id, v, completeDraft("第 " + v + " 稿", v + 1));
        }
        assertThat(countStepRows(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("⭐ commit 把表头从冻结快照投影进 tc_case 的正式列")
    void commitProjectsHeader() throws SQLException {
        String id = UUID.randomUUID().toString();
        store.draft(id, PC_WEB, "购物车结算", "agent-a");
        store.update(id, 0, completeDraft("购物车结算", 3));

        assertThat(store.commit(id, 1).code()).isEqualTo(ExitCode.OK);

        assertThat(one("SELECT case_code FROM tc_case WHERE case_id='" + id + "'"))
                .isEqualTo("ATP-CART-0001");
        assertThat(one("SELECT title     FROM tc_case WHERE case_id='" + id + "'"))
                .isEqualTo("购物车结算");
        assertThat(one("SELECT module_id FROM tc_case WHERE case_id='" + id + "'"))
                .isEqualTo("M003");
        assertThat(one("SELECT status::text FROM tc_case WHERE case_id='" + id + "'"))
                .as("落地为老平台原生的 DRAFT(1)，执行器无感知").isEqualTo("1");
    }

    @Test
    @DisplayName("提交后 step_json 仍在 —— 库里留着用户确认过的那一份快照")
    void snapshotSurvivesCommit() throws SQLException {
        String id = UUID.randomUUID().toString();
        store.draft(id, PC_WEB, "购物车结算", "agent-a");
        store.update(id, 0, completeDraft("购物车结算", 3));
        store.commit(id, 1);

        var row = store.show(id).row();
        assertThat(row.draftJson()).contains("\"steps\"").contains("ATP-CART-0001");
        assertThat(row.status()).isEqualTo(CaseStatus.DRAFT);
        assertThat(row.platformStatus()).isEqualTo(CaseStatus.DRAFT);
    }

    @Test
    @DisplayName("⭐ 表头残缺时 CHECK 拦下 commit，tc_step 的状态翻转必须一起回滚")
    void checkViolationRollsBackBothTables() throws SQLException {
        String id = UUID.randomUUID().toString();
        store.draft(id, PC_WEB, "只有标题", "agent-a");
        store.update(id, 0, "{\"title\":\"只有标题\",\"steps\":[{\"seq\":1,\"action\":\"CLICK\"}]}");

        var r = store.commit(id, 1);

        assertThat(r.code()).isEqualTo(ExitCode.VALIDATION_FAILED);
        var row = store.show(id).row();
        assertThat(row.status())
                .as("tc_step 必须还停在编写态 —— 否则就是提交了一条 tc_case 里没表头的案例")
                .isEqualTo(CaseStatus.AI_DRAFT);
        assertThat(row.version()).as("version 也不能跳").isEqualTo(1);
        assertThat(one("SELECT status::text FROM tc_case WHERE case_id='" + id + "'"))
                .as("tc_case 也没被翻状态").isEqualTo("4");
    }

    @Test
    @DisplayName("草稿 JSON 非法时提交回滚，不留半截状态")
    void malformedDraftRollsBack() throws SQLException {
        String id = UUID.randomUUID().toString();
        store.draft(id, PC_WEB, "坏 JSON", "agent-a");
        // 合法 JSON 但 priority 不是枚举值 —— DraftHeader.parse 会抛
        store.update(id, 0, completeDraft("坏 JSON").replace("\"P1\"", "\"P9\""));

        var r = store.commit(id, 1);

        assertThat(r.code()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(r.message()).contains("回滚");
        assertThat(store.show(id).row().status()).isEqualTo(CaseStatus.AI_DRAFT);
    }

    // ------------------------------------------------------------------ 夹具

    private static int countStepRows(String caseId) throws SQLException {
        return Integer.parseInt(one(
                "SELECT COUNT(*)::text FROM tc_step WHERE case_id='" + caseId + "'"));
    }

    private static String one(String sql) throws SQLException {
        try (var c = connections.open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
