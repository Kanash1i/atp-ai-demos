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

// TestCAS_SerialAdvanceAlwaysSucceeds 正常路径：每次都带上一次拿到的 version，
// 连续 5 次 update 必须次次成功，版本逐格递增。
//
// ⭐ 它挡的不是"没人覆盖"，是"挂了指不对地方"。
//
// 我实测过：把 CAS 条件改成恒假（模拟写得过于严格、永远返回冲突），
// 现有的 11 个用例会红 —— 包括并发那条。所以覆盖是有的。
// 平台侧原本的预测是"并发测试照样全绿"，那条不成立，我验之前就接受了它。
//
// 但红的 11 条里有 8 条是 TestCommit_* —— 因为它们都拿 Update 当前置步骤，
// CAS 一坏，前置就崩，报错全落在 Commit 上。照着那份输出去查，
// 人会从 Commit 开始 debug，而 bug 在 Update。
//
// 这条用例的价值因此是【诊断精度】而不是覆盖率：它红的时候只说一句
// "第 N 次串行 update 应当成功，实际 VERSION_CONFLICT"，指向就是对的。
func TestCAS_SerialAdvanceAlwaysSucceeds(t *testing.T) {
	ctx, s, _ := newStore(t)
	id := uuid.NewString()
	mustDraft(t, ctx, s, id, "购物车结算")

	version := 0
	for i := 1; i <= 5; i++ {
		r := s.Update(ctx, id, version, completeDraft("购物车结算", 2))
		if r.Code != model.OK {
			t.Fatalf("第 %d 次串行 update 应当成功，实际 %s: %s", i, r.Code, r.Message)
		}
		if r.Row.Version != i {
			t.Fatalf("第 %d 次 update 后 version 应为 %d，实际 %d", i, i, r.Row.Version)
		}
		version = r.Row.Version
	}

	// 收尾：正常路径推进完，commit 仍然走得通 —— 否则"能改但提交不了"也是死路
	if r := s.Commit(ctx, id, version); r.Code != model.OK {
		t.Fatalf("串行推进后 commit 应当成功，实际 %s: %s", r.Code, r.Message)
	}
}
