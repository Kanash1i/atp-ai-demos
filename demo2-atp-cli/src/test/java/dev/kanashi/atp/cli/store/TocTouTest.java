package dev.kanashi.atp.cli.store;

import dev.kanashi.atp.cli.model.ExitCode;
import dev.kanashi.atp.cli.model.StoreResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ 这个类测的是整套设计存在的理由：
 * <b>用户确认的那一份，和最终落库的那一份，必须是同一份。</b>
 *
 * <p>如果 commit 写成"先 SELECT 检查状态和版本、再 UPDATE"，
 * 检查通过之后、UPDATE 执行之前 agent 改一次内容，这里就会静默地提交错的版本。
 * 把状态和版本压进同一条 UPDATE 的 WHERE，窗口才是零。
 */
@DisplayName("TOCTOU：确认之后内容被改过，提交必须失败")
class TocTouTest extends MySqlTestBase {

    @Test
    @DisplayName("preview 拿到 version=1 后被改成 2 → commit(version=1) 报 VERSION_CONFLICT")
    void staleVersionRejected() {
        String caseId = UUID.randomUUID().toString();
        store.draft(caseId, PC_WEB, "购物车结算", "agent-a");

        StoreResult afterFirstEdit = store.update(caseId, 0, completeDraft("购物车结算"));
        int previewedVersion = afterFirstEdit.row().version();   // 用户 preview 看到的就是这个
        assertThat(previewedVersion).isEqualTo(1);

        // 用户点确认之前，agent 又偷偷改了一版
        StoreResult sneaky = store.update(caseId, 1, completeDraft("购物车结算（被改过）"));
        assertThat(sneaky.row().version()).isEqualTo(2);

        StoreResult commit = store.commit(caseId, previewedVersion);

        assertThat(commit.code()).isEqualTo(ExitCode.VERSION_CONFLICT);
        assertThat(commit.message()).contains("被改过");
        assertThat(store.show(caseId).row().status())
                .as("案例必须还停在编写态，不能被提交出去")
                .isEqualTo("AI_DRAFT");
    }

    @Test
    @DisplayName("重新 preview 后用新版本号提交 → 成功")
    void recoverByRepreview() {
        String caseId = UUID.randomUUID().toString();
        store.draft(caseId, PC_WEB, "购物车结算", "agent-a");
        store.update(caseId, 0, completeDraft("购物车结算"));
        store.update(caseId, 1, completeDraft("购物车结算（终稿）"));

        int current = store.show(caseId).row().version();
        StoreResult commit = store.commit(caseId, current);

        assertThat(commit.code()).isEqualTo(ExitCode.OK);
        assertThat(commit.row().status()).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("并发 update 撞版本 → 后到的被 CAS 挡下")
    void concurrentUpdateLosesOnCas() {
        String caseId = UUID.randomUUID().toString();
        store.draft(caseId, PC_WEB, "登录成功", "agent-a");

        StoreResult win = store.update(caseId, 0, completeDraft("A 写的"));
        StoreResult lose = store.update(caseId, 0, completeDraft("B 写的"));

        assertThat(win.code()).isEqualTo(ExitCode.OK);
        assertThat(lose.code()).isEqualTo(ExitCode.VERSION_CONFLICT);
    }
}
