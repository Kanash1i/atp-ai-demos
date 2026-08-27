package dev.kanashi.atp.cli.store;

import dev.kanashi.atp.cli.model.CaseHeader;
import dev.kanashi.atp.cli.model.CaseRow;
import dev.kanashi.atp.cli.model.CaseStatus;
import dev.kanashi.atp.cli.model.CaseType;
import dev.kanashi.atp.cli.model.ExitCode;
import dev.kanashi.atp.cli.model.StoreResult;
import dev.kanashi.atp.cli.rule.DraftHeader;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * ⭐ 全项目<b>唯一</b>持有 SQL 的包（{@code SqlContainmentTest} 机械守住这条）。
 *
 * <p><b>数据落点</b>：
 * <pre>
 * tc_case   表头 + 平台侧状态。编辑期只有骨架，commit 那一刻才被填齐
 * tc_step   一比一。step_json 是完整草稿，编辑期的状态机与乐观锁也在这
 * </pre>
 *
 * <p><b>因此三条路径的形状完全不同</b>：
 * <ul>
 *   <li>{@code draft}  —— 两条 INSERT（都是新行，无争用）</li>
 *   <li>{@code update} —— <b>单表单行 CAS</b>，只写 tc_step。编辑期的高频写全在这</li>
 *   <li>{@code commit} —— 跨表事务，但一份草稿只发生一次</li>
 * </ul>
 *
 * <p>五条不变式：
 * <ol>
 *   <li><b>主键唯一约束就是幂等约束</b> —— UUID 由 CLI 本地生成，重试复用同一个。</li>
 *   <li><b>检查和写入必须在同一条 UPDATE 里</b> —— 状态和版本都写进 WHERE，杜绝 TOCTOU。</li>
 *   <li><b>{@code affectedRows == 0} 不能直接抛错</b> —— 读回来分情况，否则重放永远过不去。</li>
 *   <li><b>加锁顺序统一为 tc_step → tc_case</b> —— 跨表的路径只有 commit 和清理任务，
 *       两边同序才不会死锁。</li>
 *   <li><b>事务里不含任何等外部的东西</b> —— 不调模型、不等用户。
 *       用户确认发生在两次 CLI 调用<b>之间</b>，不占锁。</li>
 * </ol>
 *
 * <p>数据库是 PostgreSQL。错误判定走 <b>SQLSTATE</b> 而不是厂商 errorCode。
 */
public final class CaseStore {

    private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";
    private static final String SQLSTATE_CHECK_VIOLATION = "23514";

    private final ConnectionFactory connections;

    public CaseStore(ConnectionFactory connections) {
        this.connections = connections;
    }

    // ------------------------------------------------------------------ draft

    /**
     * 建草稿：{@code tc_case} 一行骨架 + {@code tc_step} 一行初始内容。
     *
     * <p>{@code caseId} 由<b>调用方</b>生成并在重试时复用 —— 这是幂等的全部来源。
     * 若改由数据库生成，"INSERT 成功但响应丢失 → 重试"会产生两条各自合法的草稿，
     * 而版本号救不了它们（是两行不同的记录）。
     */
    public StoreResult draft(String caseId, CaseType caseType, String title, String createdBy) {
        try (Connection conn = connections.open()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO tc_case (case_id, case_type, status, version,
                                             created_by, created_at, updated_at)
                        VALUES (?, ?, ?, 0, ?, now(), now())
                        """)) {
                    ps.setString(1, caseId);
                    ps.setInt(2, caseType.code());
                    ps.setInt(3, CaseStatus.AI_DRAFT.code());
                    ps.setString(4, createdBy);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO tc_step (step_id, case_id, step_json, status, version, updated_at)
                        VALUES (?, ?, ?::jsonb, ?, 0, now())
                        """)) {
                    ps.setString(1, java.util.UUID.randomUUID().toString());
                    ps.setString(2, caseId);
                    ps.setString(3, DraftHeader.initial(title));
                    ps.setInt(4, CaseStatus.AI_DRAFT.code());
                    ps.executeUpdate();
                }
                conn.commit();

            } catch (SQLException e) {
                conn.rollback();
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

    /**
     * 写内容 —— ⭐ <b>只碰 {@code tc_step} 一张表、一行</b>。
     *
     * <p>表头字段这时还只活在 {@code step_json} 里，等到 commit 才投影进 tc_case 的正式列。
     * 所以编辑期不管改多少次，都不会去动那张最终要落地的表，
     * <b>跨表事务与随之而来的加锁顺序问题在这条最高频的路径上根本不存在</b>。
     *
     * <p>CAS：状态必须仍是 {@code AI_DRAFT}，且版本号必须与调用方手上的一致。
     */
    public StoreResult update(String caseId, int expectedVersion, String draftJson) {
        try (Connection conn = connections.open()) {
            int affected;
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE tc_step
                       SET step_json = ?::jsonb, version = version + 1, updated_at = now()
                     WHERE case_id = ? AND status = ? AND version = ?
                    """)) {
                ps.setString(1, draftJson);
                ps.setString(2, caseId);
                ps.setInt(3, CaseStatus.AI_DRAFT.code());
                ps.setInt(4, expectedVersion);
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
     * 提交：{@code AI_DRAFT → DRAFT}，并把表头从冻结快照投影到 {@code tc_case} 的正式列。
     *
     * <p>⭐ 只收 caseId 和 version，<b>不接受任何外部内容</b> ——
     * 投影的输入是库里那一行，也就是用户已经确认过的那份快照。
     *
     * <p>⭐ 加锁顺序固定 <b>tc_step → tc_case</b>。清理任务（M5）必须同序，
     * 否则两者撞在同一条边界草稿上会死锁。
     *
     * <p>{@code ck_case_complete} 正好在这一刻校验必填 —— 编辑期允许残缺，
     * 一离开 AI_DRAFT 就必须完整，数据库直接守门。
     */
    public StoreResult commit(String caseId, int expectedVersion) {
        try (Connection conn = connections.open()) {
            conn.setAutoCommit(false);
            try {
                int affected;
                try (PreparedStatement ps = conn.prepareStatement("""
                        UPDATE tc_step
                           SET status = ?, version = version + 1, updated_at = now()
                         WHERE case_id = ? AND status = ? AND version = ?
                        """)) {
                    ps.setInt(1, CaseStatus.DRAFT.code());
                    ps.setString(2, caseId);
                    ps.setInt(3, CaseStatus.AI_DRAFT.code());
                    ps.setInt(4, expectedVersion);
                    affected = ps.executeUpdate();
                }

                if (affected != 1) {
                    conn.rollback();
                    return diagnoseMiss(conn, caseId, expectedVersion, "提交");
                }

                CaseRow row = findById(conn, caseId).orElse(null);
                if (row == null) {
                    conn.rollback();
                    return StoreResult.fail(ExitCode.INFRA_ERROR, "提交成功但读不回");
                }
                projectHeader(conn, caseId, DraftHeader.parse(row.draftJson()));
                conn.commit();

                return findById(conn, caseId)
                        .map(StoreResult::ok)
                        .orElseGet(() -> StoreResult.fail(ExitCode.INFRA_ERROR, "提交成功但读不回"));

            } catch (SQLException e) {
                conn.rollback();
                if (isCheckViolation(e)) {
                    return StoreResult.fail(ExitCode.VALIDATION_FAILED,
                            "案例必填字段不完整（case_code / title / module_id / priority / author），"
                                    + "被约束 ck_case_complete 拦下");
                }
                throw e;
            } catch (RuntimeException e) {
                conn.rollback();
                return StoreResult.fail(ExitCode.VALIDATION_FAILED,
                        "表头投影失败，提交已回滚：" + e.getMessage());
            }

        } catch (SQLException e) {
            return infra(e);
        }
    }

    /** 把冻结快照里的表头写进 tc_case 的正式列，同时翻状态。 */
    private void projectHeader(Connection conn, String caseId, CaseHeader h) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE tc_case
                   SET case_code = ?, title = ?, module_id = ?, priority = ?,
                       author = ?, precondition = ?,
                       status = ?, version = version + 1, updated_at = now()
                 WHERE case_id = ?
                """)) {
            ps.setString(1, h.caseCode());
            ps.setString(2, h.title());
            ps.setString(3, h.moduleId());
            setNullableInt(ps, 4, h.priority() == null ? null : h.priority().code());
            ps.setString(5, h.author());
            ps.setString(6, h.precondition());
            ps.setInt(7, CaseStatus.DRAFT.code());
            ps.setString(8, caseId);
            ps.executeUpdate();
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
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT c.case_id, c.case_type,
                       c.status  AS case_status,  c.version AS case_version,
                       s.status  AS draft_status, s.version AS draft_version,
                       s.step_json
                  FROM tc_case c
                  LEFT JOIN tc_step s ON s.case_id = c.case_id
                 WHERE c.case_id = ?
                """)) {
            ps.setString(1, caseId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new CaseRow(
                        rs.getString("case_id"),
                        CaseType.fromCode(rs.getInt("case_type")),
                        CaseStatus.fromCode(rs.getInt("draft_status")),
                        rs.getInt("draft_version"),
                        CaseStatus.fromCode(rs.getInt("case_status")),
                        rs.getInt("case_version"),
                        rs.getString("step_json")));
            }
        }
    }

    private static void setNullableInt(PreparedStatement ps, int idx, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(idx, java.sql.Types.SMALLINT);
        } else {
            ps.setInt(idx, value);
        }
    }

    private static boolean isUniqueViolation(SQLException e) {
        return hasSqlState(e, SQLSTATE_UNIQUE_VIOLATION);
    }

    private static boolean isCheckViolation(SQLException e) {
        return hasSqlState(e, SQLSTATE_CHECK_VIOLATION) || messageContains(e, "ck_case_complete");
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
