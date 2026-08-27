package dev.kanashi.atp.cli.store;

import dev.kanashi.atp.cli.model.CaseDraft;
import dev.kanashi.atp.cli.model.CaseStatus;
import dev.kanashi.atp.cli.model.ExitCode;
import dev.kanashi.atp.cli.model.Priority;
import dev.kanashi.atp.cli.model.StepRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 步骤只住在 {@code tc_step}，父表不再存整包 JSON。
 *
 * <p>由此产生的两条要求：
 * <ol>
 *   <li>{@code update} 跨两张表，<b>必须一个事务</b> ——
 *       否则会留下"表头新、步骤旧"的半截状态，而且不报错。</li>
 *   <li>{@code commit} 回归<b>纯状态迁移</b> —— 步骤在 update 时就已经落好了，
 *       它无事可做，也就不需要搬运任何数据。</li>
 * </ol>
 */
@DisplayName("步骤的存放与事务边界")
class StepStorageTest extends PgTestBase {

    @Test
    @DisplayName("update 把步骤写进 tc_step，seq 保持不变")
    void updateWritesStepsIntoChildTable() throws SQLException {
        String id = UUID.randomUUID().toString();
        store.draft(id, PC_WEB, "购物车结算", "agent-a");

        assertThat(store.update(id, 0, completeDraft("购物车结算", 3)).code()).isEqualTo(ExitCode.OK);

        assertThat(seqsOf(id)).containsExactly(1, 2, 3);
        assertThat(store.show(id).row().steps()).hasSize(3);
    }

    @Test
    @DisplayName("再次 update 是全量替换 —— 旧步骤不残留")
    void updateReplacesStepsWholesale() throws SQLException {
        String id = UUID.randomUUID().toString();
        store.draft(id, PC_WEB, "购物车结算", "agent-a");
        store.update(id, 0, completeDraft("购物车结算", 5));
        assertThat(countSteps(id)).isEqualTo(5);

        store.update(id, 1, completeDraft("购物车结算（精简）", 2));

        assertThat(countSteps(id))
                .as("库里的步骤永远等于最后一次 update 传进来的那一份")
                .isEqualTo(2);
        assertThat(seqsOf(id)).containsExactly(1, 2);
    }

    @Test
    @DisplayName("⭐ CAS 失败时步骤一步都不能动")
    void failedCasLeavesStepsUntouched() throws SQLException {
        String id = UUID.randomUUID().toString();
        store.draft(id, PC_WEB, "购物车结算", "agent-a");
        store.update(id, 0, completeDraft("购物车结算", 4));

        // 拿过期的 version 再写一次 —— CAS 必须挡住，而且不能顺手把步骤删了
        assertThat(store.update(id, 0, completeDraft("购物车结算", 1)).code())
                .isEqualTo(ExitCode.VERSION_CONFLICT);

        assertThat(countSteps(id)).as("旧步骤原封不动").isEqualTo(4);
    }

    @Test
    @DisplayName("⭐ 步骤写入失败必须连表头一起回滚 —— 不留半截状态")
    void stepFailureRollsBackHeader() throws SQLException {
        String id = UUID.randomUUID().toString();
        store.draft(id, PC_WEB, "原标题", "agent-a");
        store.update(id, 0, completeDraft("原标题", 2));

        // seq 重复，会撞 uk_step_case_seq
        List<StepRow> dup = List.of(
                new StepRow(UUID.randomUUID().toString(), 1, "{\"seq\":1,\"action\":\"CLICK\"}"),
                new StepRow(UUID.randomUUID().toString(), 1, "{\"seq\":1,\"action\":\"INPUT\"}"));
        CaseDraft bad = new CaseDraft("ATP-CART-0001", "改过的标题", "M003",
                Priority.P1, "qa.kanashi", null, dup);

        var r = store.update(id, 1, bad);

        assertThat(r.code()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(r.message()).contains("回滚");

        var row = store.show(id).row();
        assertThat(row.title()).as("表头必须回滚").isEqualTo("原标题");
        assertThat(row.version()).as("version 也不能跳").isEqualTo(1);
        assertThat(countSteps(id)).as("旧步骤仍在").isEqualTo(2);
    }

    @Test
    @DisplayName("commit 是纯状态迁移 —— 一步都不碰 tc_step")
    void commitDoesNotTouchSteps() throws SQLException {
        String id = UUID.randomUUID().toString();
        store.draft(id, PC_WEB, "购物车结算", "agent-a");
        store.update(id, 0, completeDraft("购物车结算", 3));

        assertThat(store.commit(id, 1).row().status()).isEqualTo(CaseStatus.DRAFT);

        assertThat(countSteps(id)).as("步骤在 update 时就落好了，commit 无事可做").isEqualTo(3);
        assertThat(seqsOf(id)).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("show 能把库里那行还原成可编辑的草稿（表头 + 步骤都在）")
    void showRoundTripsTheDraft() {
        String id = UUID.randomUUID().toString();
        store.draft(id, PC_WEB, "购物车结算", "agent-a");
        store.update(id, 0, completeDraft("购物车结算", 3));

        var row = store.show(id).row();

        assertThat(row.caseCode()).isEqualTo("ATP-CART-0001");
        assertThat(row.moduleId()).isEqualTo("M003");
        assertThat(row.priority()).isEqualTo(Priority.P1);
        assertThat(row.author()).isEqualTo("qa.kanashi");
        assertThat(row.precondition()).isEqualTo("已登录且购物车非空");
        assertThat(row.steps()).hasSize(3);
    }

    // ------------------------------------------------------------------ 夹具

    private static int countSteps(String caseId) throws SQLException {
        try (var c = connections.open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM tc_step WHERE case_id = '" + caseId + "'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static List<Integer> seqsOf(String caseId) throws SQLException {
        try (var c = connections.open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT seq FROM tc_step WHERE case_id = '" + caseId + "' ORDER BY seq")) {
            var out = new java.util.ArrayList<Integer>();
            while (rs.next()) {
                out.add(rs.getInt(1));
            }
            return out;
        }
    }
}
