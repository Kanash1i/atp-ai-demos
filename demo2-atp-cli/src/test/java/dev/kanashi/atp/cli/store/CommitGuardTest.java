package dev.kanashi.atp.cli.store;

import dev.kanashi.atp.cli.model.CaseDraft;
import dev.kanashi.atp.cli.model.ExitCode;
import dev.kanashi.atp.cli.model.StoreResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("commit 的守门：残缺、不存在、状态不对")
class CommitGuardTest extends MySqlTestBase {

    @Test
    @DisplayName("必填字段残缺 → 被 ck_case_complete 拦下，报 VALIDATION_FAILED")
    void incompleteDraftBlockedByCheckConstraint() {
        String caseId = UUID.randomUUID().toString();
        store.draft(caseId, PC_WEB, "只有标题", "agent-a");

        // 只写了标题，case_code / module_id / priority / author 全空
        CaseDraft partial = new CaseDraft(
                null, "只有标题", null, null, null, null, "{}");
        StoreResult updated = store.update(caseId, 0, partial);
        assertThat(updated.code())
                .as("编写期允许残缺，update 本身不该失败")
                .isEqualTo(ExitCode.OK);

        StoreResult commit = store.commit(caseId, 1);

        assertThat(commit.code()).isEqualTo(ExitCode.VALIDATION_FAILED);
        assertThat(commit.message()).contains("ck_case_complete");
        assertThat(store.show(caseId).row().status()).isEqualTo("AI_DRAFT");
    }

    @Test
    @DisplayName("不存在的 id → NOT_FOUND(11)")
    void unknownIdIsNotFound() {
        StoreResult r = store.commit(UUID.randomUUID().toString(), 0);

        assertThat(r.code()).isEqualTo(ExitCode.NOT_FOUND);
        assertThat(r.code().code()).isEqualTo(11);
    }

    @Test
    @DisplayName("已提交后又被改过 → STATE_CONFLICT，不是重放")
    void modifiedAfterCommitIsStateConflict() throws Exception {
        String caseId = UUID.randomUUID().toString();
        store.draft(caseId, PC_WEB, "登录成功", "agent-a");
        store.update(caseId, 0, completeDraft("登录成功"));
        store.commit(caseId, 1);        // → DRAFT, version=2

        // 模拟平台侧后续编辑，把 version 推到 3
        try (var c = connections.open(); var st = c.createStatement()) {
            st.executeUpdate("UPDATE tc_case SET version = version + 1 WHERE case_id = '" + caseId + "'");
        }

        StoreResult retry = store.commit(caseId, 1);

        assertThat(retry.code()).isEqualTo(ExitCode.STATE_CONFLICT);
        assertThat(retry.replayed()).isFalse();
    }

    @Test
    @DisplayName("退出码取值锁定 —— agent 的分流全靠它，改动等于破坏契约")
    void exitCodeContract() {
        assertThat(ExitCode.OK.code()).isZero();
        assertThat(ExitCode.VERSION_CONFLICT.code()).isEqualTo(10);
        assertThat(ExitCode.NOT_FOUND.code()).isEqualTo(11);
        assertThat(ExitCode.VALIDATION_FAILED.code()).isEqualTo(12);
        assertThat(ExitCode.STATE_CONFLICT.code()).isEqualTo(13);
        assertThat(ExitCode.NEEDS_INPUT.code()).isEqualTo(14);
        assertThat(ExitCode.INFRA_ERROR.code()).isEqualTo(20);
    }
}
