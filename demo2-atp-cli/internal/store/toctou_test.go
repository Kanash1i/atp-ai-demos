package store_test

import (
	"encoding/json"
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

// update 的响应丢失后重试：版本前进一格【且库里就是我想写的那份】= 重放。
func TestUpdate_LostResponseThenRetryIsReplay(t *testing.T) {
	ctx, s, _ := newStore(t)
	id := uuid.NewString()
	mustDraft(t, ctx, s, id, "登录成功")

	payload := completeDraft("登录成功", 2)
	if r := s.Update(ctx, id, 0, payload); r.Code != model.OK {
		t.Fatalf("首次写入应成功，实际 %s", r.Code)
	}
	// 响应丢了，agent 用同一个 version 和同一份内容重试
	retry := s.Update(ctx, id, 0, payload)

	if retry.Code != model.OK {
		t.Fatalf("重放必须返回 0，实际 %s: %s", retry.Code, retry.Message)
	}
	if !retry.Replayed {
		t.Fatal("要标成重放，否则调用方分不清这次到底改了没有")
	}
	if retry.Row.Version != 1 {
		t.Fatalf("重放不该再推版本，应停在 1，实际 %d", retry.Row.Version)
	}
}

// ⭐ 只看版本号会把这条判错。
//
//	A: update(v=0, 内容 X) → 成功，v=1
//	B: update(v=0, 内容 Y) → 版本条件同样满足（1 == 0+1）
//
// B 不是重放 —— 它想写的 Y 一个字都没进去。当成功返回的话，B 会以为
// 自己写成功了，而库里其实是 A 的内容。这是【静默丢写】，比报错严重得多。
func TestUpdate_SameVersionDifferentContentIsConflictNotReplay(t *testing.T) {
	ctx, s, _ := newStore(t)
	id := uuid.NewString()
	mustDraft(t, ctx, s, id, "登录成功")

	if r := s.Update(ctx, id, 0, completeDraft("A 写的", 2)); r.Code != model.OK {
		t.Fatalf("A 应成功，实际 %s", r.Code)
	}
	b := s.Update(ctx, id, 0, completeDraft("B 写的", 2))

	if b.Code != model.VersionConflict {
		t.Fatalf("内容不同就不是重放，应为 10，实际 %s —— 判成成功会让 B 以为自己写进去了", b.Code)
	}
}

// 内容比的是 JSON 语义不是字符串：键顺序和空白不同的同一份内容仍是重放。
// 按字符串比会把真重放误判成版本冲突。
func TestUpdate_ReplayComparesJSONSemanticsNotBytes(t *testing.T) {
	ctx, s, _ := newStore(t)
	id := uuid.NewString()
	mustDraft(t, ctx, s, id, "登录成功")

	original := completeDraft("登录成功", 2)
	if r := s.Update(ctx, id, 0, original); r.Code != model.OK {
		t.Fatalf("首次写入应成功，实际 %s", r.Code)
	}

	// 同一份内容重新序列化一遍 —— 键顺序与空白都可能变
	var any1 map[string]any
	if err := json.Unmarshal([]byte(original), &any1); err != nil {
		t.Fatal(err)
	}
	reserialized, err := json.MarshalIndent(any1, "", "    ")
	if err != nil {
		t.Fatal(err)
	}

	retry := s.Update(ctx, id, 0, string(reserialized))
	if retry.Code != model.OK || !retry.Replayed {
		t.Fatalf("重新序列化的同一份内容仍该判成重放，实际 %s（replayed=%v）",
			retry.Code, retry.Replayed)
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
