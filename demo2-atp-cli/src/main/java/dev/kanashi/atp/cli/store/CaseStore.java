package dev.kanashi.atp.cli.store;

import dev.kanashi.atp.cli.model.CaseDraft;
import dev.kanashi.atp.cli.model.CaseRow;
import dev.kanashi.atp.cli.model.ExitCode;
import dev.kanashi.atp.cli.model.StoreResult;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * ⭐ 全项目<b>唯一</b>持有 SQL 的类。并发正确性全部落在这一个文件里，
 * 面试时翻这一个文件就能把设计讲完。
 *
 * <p>三条不变式：
 * <ol>
 *   <li><b>主键唯一约束就是幂等约束</b> —— UUID 由 CLI 本地生成，重试复用同一个，
 *       撞唯一键说明上次其实成功了，读回来当成功返回。</li>
 *   <li><b>检查和写入必须在同一条 UPDATE 里</b> —— 状态和版本都写进 WHERE。
 *       拆成"先 SELECT 判断再 UPDATE"会有 TOCTOU 窗口：检查通过之后、写入之前，
 *       内容可能已经被改掉了。</li>
 *   <li><b>{@code affectedRows == 0} 不能直接抛错</b> —— 必须读回来分情况，
 *       否则幂等重放永远过不去，agent 会无限重试。</li>
 * </ol>
 *
 * <p>数据库是 PostgreSQL。错误判定一律走 <b>SQLSTATE</b> 而不是厂商 errorCode ——
 * SQLSTATE 是 SQL 标准的一部分，这样这段逻辑换库也不用改。
 */
public final class CaseStore {

    /** SQL 标准：唯一约束冲突。 */
    private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";
    /** SQL 标准：CHECK 约束冲突。 */
    private static final String SQLSTATE_CHECK_VIOLATION = "23514";

    private static final String SELECT_COLS =
            "case_id, case_type, status, version, title, draft_json";

    private final ConnectionFactory connections;

    public CaseStore(ConnectionFactory connections) {
        this.connections = connections;
    }

    // ------------------------------------------------------------------ draft

    /**
     * 建草稿行。{@code caseId} 由<b>调用方</b>生成并在重试时复用 —— 这是幂等的全部来源。
     *
     * <p>如果 UUID 改由数据库生成，那么"INSERT 成功但响应丢失 → 重试"会产生
     * 两条各自合法的草稿，而版本号救不了它们（是两行不同的记录）。
     * 把生成动作挪到客户端，这个洞就免费消失了。
     */
    public StoreResult draft(String caseId, String caseType, String title, String createdBy) {
        try (Connection conn = connections.open()) {
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO tc_case (case_id, case_type, status, version,
                                         title, created_by, created_at, updated_at)
                    VALUES (?, ?::tc_case_type, 'AI_DRAFT', 0, ?, ?, now(), now())
                    """)) {
                ps.setString(1, caseId);
                ps.setString(2, caseType);
                ps.setString(3, title);
                ps.setString(4, createdBy);
                ps.executeUpdate();

            } catch (SQLException e) {
                if (!isUniqueViolation(e)) {
                    throw e;
                }
                // ⭐ 唯一约束是并发的最后防线，也是幂等的入口：
                //    把"并发/重试的失败者"转换成"幂等的成功者"。
                CaseRow existing = findById(conn, caseId).orElse(null);
                if (existing == null) {
                    return StoreResult.fail(ExitCode.INFRA_ERROR,
                            "唯一约束冲突但读不回该行，请检查隔离级别与连接是否同库");
                }
                if (!existing.isAiDraft()) {
                    // 不是我们的编写态行 —— 要么撞上了一条既有案例，
                    // 要么这个草稿已经提交过了，两种都不该当成重放。
                    return StoreResult.fail(ExitCode.STATE_CONFLICT,
                            "case_id 已被占用且不处于编写态（当前 status=%s）：%s"
                                    .formatted(existing.status(), caseId));
                }
                return StoreResult.replayed(existing);
            }

            return findById(conn, caseId)
                    .map(StoreResult::ok)
                    .orElseGet(() -> StoreResult.fail(ExitCode.INFRA_ERROR, "插入成功但读不回"));

        } catch (SQLException e) {
            return infra(e);
        }
    }

    // ----------------------------------------------------------------- update

    /** 写内容。CAS：状态必须仍是 AI_DRAFT，且版本号必须与调用方手上的一致。 */
    public StoreResult update(String caseId, int expectedVersion, CaseDraft draft) {
        try (Connection conn = connections.open()) {
            int affected;
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE tc_case
                       SET draft_json = ?::jsonb, case_code = ?, title = ?, module_id = ?,
                           priority = ?::tc_priority, author = ?, precondition = ?,
                           version = version + 1, updated_at = now()
                     WHERE case_id = ? AND status = 'AI_DRAFT' AND version = ?
                    """)) {
                ps.setString(1, draft.rawJson());
                ps.setString(2, draft.caseCode());
                ps.setString(3, draft.title());
                ps.setString(4, draft.moduleId());
                ps.setString(5, draft.priority());
                ps.setString(6, draft.author());
                ps.setString(7, draft.precondition());
                ps.setString(8, caseId);
                ps.setInt(9, expectedVersion);
                affected = ps.executeUpdate();
            }

            if (affected == 1) {
                return findById(conn, caseId)
                        .map(StoreResult::ok)
                        .orElseGet(() -> StoreResult.fail(ExitCode.INFRA_ERROR, "更新成功但读不回"));
            }
            return diagnoseMiss(conn, caseId, expectedVersion, "写入");

        } catch (SQLException e) {
            return infra(e);
        }
    }

    // ----------------------------------------------------------------- commit

    /**
     * 提交：{@code AI_DRAFT → DRAFT}。
     *
     * <p><b>不携带任何内容</b>，只有 id 和 version —— 它是一次纯状态迁移。
     * 用户 preview 的和最终落库的，物理上就是同一行，
     * 从结构上消灭了内容漂移，而不是靠提示词约束 agent 别乱改。
     *
     * <p>落地为普通的 {@code DRAFT}，执行器和既有列表页完全无感知。
     */
    public StoreResult commit(String caseId, int expectedVersion) {
        try (Connection conn = connections.open()) {
            int affected;
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE tc_case
                       SET status = 'DRAFT', version = version + 1, updated_at = now()
                     WHERE case_id = ? AND status = 'AI_DRAFT' AND version = ?
                    """)) {
                ps.setString(1, caseId);
                ps.setInt(2, expectedVersion);
                affected = ps.executeUpdate();

            } catch (SQLException e) {
                if (isCheckViolation(e)) {
                    // ck_case_complete：约束随状态而变 —— 残缺的案例迁不出 AI_DRAFT。
                    // 数据库直接守门，应用层不必再写一遍"提交前检查必填"。
                    return StoreResult.fail(ExitCode.VALIDATION_FAILED,
                            "案例必填字段不完整（case_code / title / module_id / priority / author），"
                                    + "被约束 ck_case_complete 拦下");
                }
                throw e;
            }

            if (affected == 1) {
                return findById(conn, caseId)
                        .map(StoreResult::ok)
                        .orElseGet(() -> StoreResult.fail(ExitCode.INFRA_ERROR, "提交成功但读不回"));
            }
            return diagnoseMiss(conn, caseId, expectedVersion, "提交");

        } catch (SQLException e) {
            return infra(e);
        }
    }

    // ------------------------------------------------------------------ 查询

    public StoreResult show(String caseId) {
        try (Connection conn = connections.open()) {
            return findById(conn, caseId)
                    .map(StoreResult::ok)
                    .orElseGet(() -> StoreResult.fail(ExitCode.NOT_FOUND, "案例不存在：" + caseId));
        } catch (SQLException e) {
            return infra(e);
        }
    }

    // ------------------------------------------------------ affectedRows == 0

    /**
     * ⭐ {@code affectedRows == 0} 之后必须读回来分情况，绝不能直接抛错 ——
     * 否则幂等重放永远过不去，agent 会一直重试到超时。
     */
    private StoreResult diagnoseMiss(Connection conn, String caseId, int expectedVersion, String op)
            throws SQLException {

        CaseRow row = findById(conn, caseId).orElse(null);

        if (row == null) {
            return StoreResult.fail(ExitCode.NOT_FOUND,
                    "案例不存在，或草稿已被每月清理任务回收：" + caseId);
        }

        if (!row.isAiDraft()) {
            // 已经离开编写态。区分"干净的重放"和"提交后又被改过"。
            if (row.version() == expectedVersion + 1) {
                return StoreResult.replayed(row);   // ← 退出码 0
            }
            return StoreResult.fail(ExitCode.STATE_CONFLICT,
                    "案例已提交（当前状态 %s，version=%d），此后又被修改过，本次%s不予执行"
                            .formatted(row.status(), row.version(), op));
        }

        return StoreResult.fail(ExitCode.VERSION_CONFLICT,
                "版本不一致：库中 version=%d，你手上是 %d。内容在你确认之后被改过，请重新 show/preview 再确认"
                        .formatted(row.version(), expectedVersion));
    }

    // -------------------------------------------------------------- 内部工具

    private Optional<CaseRow> findById(Connection conn, String caseId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + SELECT_COLS + " FROM tc_case WHERE case_id = ?")) {
            ps.setString(1, caseId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new CaseRow(
                        rs.getString("case_id"),
                        rs.getString("case_type"),
                        rs.getString("status"),
                        rs.getInt("version"),
                        rs.getString("title"),
                        rs.getString("draft_json")));
            }
        }
    }

    private static boolean isUniqueViolation(SQLException e) {
        return hasSqlState(e, SQLSTATE_UNIQUE_VIOLATION);
    }

    private static boolean isCheckViolation(SQLException e) {
        return hasSqlState(e, SQLSTATE_CHECK_VIOLATION)
                || messageContains(e, "ck_case_complete");
    }

    private static boolean hasSqlState(SQLException e, String sqlState) {
        for (SQLException cur = e; cur != null; cur = cur.getNextException()) {
            if (sqlState.equals(cur.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private static boolean messageContains(SQLException e, String needle) {
        for (SQLException cur = e; cur != null; cur = cur.getNextException()) {
            String msg = cur.getMessage();
            if (msg != null && msg.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static StoreResult infra(SQLException e) {
        return StoreResult.fail(ExitCode.INFRA_ERROR,
                "数据库操作失败: [SQLSTATE %s] %s".formatted(e.getSQLState(), e.getMessage()));
    }
}
