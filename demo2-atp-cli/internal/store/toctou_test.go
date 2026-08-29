package store_test

import (
	"testing"

	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/model"
	"github.com/google/uuid"
)

// 这一组测的是整套设计存在的理由：
// 用户确认的那一份，和最终落库的那一份，必须是同一份。
//
// 如果 commit 写成"先 SELECT 检查状态和版本、再 UPDATE"，
// 检查通过之后、UPDATE 执行之前 agent 改一次内容，这里就会静默地提交错的版本。
// 把状态和版本压进同一条 UPDATE 的 WHERE，窗口才是零。

func TestTocTou_StaleVersionRejected(t *testing.T) {
	ctx, s, _ := newStore(t)
	id := uuid.NewString()
	mustDraft(t, ctx, s, id, "购物车结算")

	afterFirst := s.Update(ctx, id, 0, completeDraft("购物车结算", 2))
	previewed := afterFirst.Row.Version // 用户 preview 看到的就是这个
	if previewed != 1 {
		t.Fatalf("首次 update 后 version 应为 1，实际 %d", previewed)
	}

	// 用户点确认之前，agent 又偷偷改了一版
	sneaky := s.Update(ctx, id, 1, completeDraft("购物车结算（被改过）", 2))
	if sneaky.Row.Version != 2 {
		t.Fatalf("二次 update 后 version 应为 2，实际 %d", sneaky.Row.Version)
	}

	r := s.Commit(ctx, id, previewed)

	if r.Code != model.VersionConflict {
		t.Fatalf("拿过期 version 提交必须被拒，实际 %s: %s", r.Code, r.Message)
	}
	if got := s.Show(ctx, id).Row.Status; got != model.StatusAIDraft {
		t.Fatalf("案例必须还停在编写态，实际 %s", got)
	}
}

func TestTocTou_RecoverByRepreview(t *testing.T) {
	ctx, s, _ := newStore(t)
	id := uuid.NewString()
	mustDraft(t, ctx, s, id, "购物车结算")
	s.Update(ctx, id, 0, completeDraft("购物车结算", 2))
	s.Update(ctx, id, 1, completeDraft("购物车结算（终稿）", 2))

	current := s.Show(ctx, id).Row.Version
	if r := s.Commit(ctx, id, current); r.Code != model.OK {
		t.Fatalf("重新 preview 后用新版本号提交应成功，实际 %s: %s", r.Code, r.Message)
	}
}

func TestTocTou_ConcurrentUpdateLosesOnCAS(t *testing.T) {
	ctx, s, _ := newStore(t)
	id := uuid.NewString()
	mustDraft(t, ctx, s, id, "登录成功")

	win := s.Update(ctx, id, 0, completeDraft("A 写的", 1))
	lose := s.Update(ctx, id, 0, completeDraft("B 写的", 1))

	if win.Code != model.OK {
		t.Fatalf("先到的应成功，实际 %s", win.Code)
	}
	if lose.Code != model.VersionConflict {
		t.Fatalf("后到的应被 CAS 挡下，实际 %s", lose.Code)
	}
}
