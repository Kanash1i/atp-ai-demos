package store_test

import (
	"testing"

	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/model"
	"github.com/google/uuid"
)

// ⭐ 编辑期的写入全部落在 tc_step 一张表一行上，tc_case 只在 commit 那一刻被写一次。
//
// 这条设计的收益：最高频的路径（反复改草稿）不跨表，
// 也就没有跨表事务、没有加锁顺序问题。跨表只发生在 commit，一份草稿一次。

func TestUpdate_DoesNotTouchCaseTable(t *testing.T) {
	ctx, s, conn := newStore(t)
	id := uuid.NewString()
	mustDraft(t, ctx, s, id, "购物车结算")

	s.Update(ctx, id, 0, completeDraft("购物车结算", 3))
	s.Update(ctx, id, 1, completeDraft("购物车结算（二稿）", 5))

	row := s.Show(ctx, id).Row
	if row.Version != 2 {
		t.Fatalf("tc_step 的 version 应跟着编辑走，实际 %d", row.Version)
	}
	if row.PlatformVersion != 0 {
		t.Fatalf("tc_case 的 version 编辑期不该动，实际 %d", row.PlatformVersion)
	}
	var code *string
	if err := conn.QueryRow(ctx, "SELECT case_code FROM tc_case WHERE case_id=$1", id).Scan(&code); err != nil {
		t.Fatal(err)
	}
	if code != nil {
		t.Fatalf("表头此刻还只该活在 step_json 里，实际 case_code=%q", *code)
	}
}

func TestUpdate_OneStepRowPerCase(t *testing.T) {
	ctx, s, conn := newStore(t)
	id := uuid.NewString()
	mustDraft(t, ctx, s, id, "购物车结算")
	for v := 0; v < 4; v++ {
		s.Update(ctx, id, v, completeDraft("第 N 稿", v+1))
	}
	if n := scalar[int](t, ctx, conn, "SELECT count(*) FROM tc_step WHERE case_id=$1", id); n != 1 {
		t.Fatalf("tc_step 是一比一，反复 update 也只该有一行，实际 %d 行", n)
	}
}

func TestCommit_ProjectsHeaderIntoCaseTable(t *testing.T) {
	ctx, s, conn := newStore(t)
	id := uuid.NewString()
	mustDraft(t, ctx, s, id, "购物车结算")
	s.Update(ctx, id, 0, completeDraft("购物车结算", 3))

	if r := s.Commit(ctx, id, 1); r.Code != model.OK {
		t.Fatalf("提交应成功，实际 %s: %s", r.Code, r.Message)
	}

	for _, tc := range []struct{ col, want string }{
		{"case_code", "ATP-CART-0001"},
		{"title", "购物车结算"},
		{"module_id", "M003"},
	} {
		got := scalar[string](t, ctx, conn, "SELECT "+tc.col+" FROM tc_case WHERE case_id=$1", id)
		if got != tc.want {
			t.Errorf("tc_case.%s = %q，期望 %q", tc.col, got, tc.want)
		}
	}
}

func TestCommit_SnapshotSurvives(t *testing.T) {
	ctx, s, _ := newStore(t)
	id := uuid.NewString()
	mustDraft(t, ctx, s, id, "购物车结算")
	s.Update(ctx, id, 0, completeDraft("购物车结算", 3))
	s.Commit(ctx, id, 1)

	row := s.Show(ctx, id).Row
	// 提交后 step_json 仍在 —— 库里留着用户确认过的那一份快照
	if row.DraftJSON == "" {
		t.Fatal("快照不该消失")
	}
	if row.Status != model.StatusDraft || row.PlatformStatus != model.StatusDraft {
		t.Fatalf("两张表都该是 DRAFT，实际 step=%s case=%s", row.Status, row.PlatformStatus)
	}
}

// ⭐ 表头残缺时 CHECK 拦下 commit，tc_step 的状态翻转必须一起回滚 ——
// 否则就是提交了一条 tc_case 里没表头的空壳案例。
func TestCommit_CheckViolationRollsBackBothTables(t *testing.T) {
	ctx, s, conn := newStore(t)
	id := uuid.NewString()
	mustDraft(t, ctx, s, id, "只有标题")
	s.Update(ctx, id, 0, `{"title":"只有标题","steps":[{"seq":1,"action":"CLICK"}]}`)

	r := s.Commit(ctx, id, 1)

	if r.Code != model.ValidationFailed {
		t.Fatalf("残缺案例应被 ck_case_complete 拦下，实际 %s: %s", r.Code, r.Message)
	}
	row := s.Show(ctx, id).Row
	if row.Status != model.StatusAIDraft {
		t.Fatalf("tc_step 必须还停在编写态，实际 %s", row.Status)
	}
	if row.Version != 1 {
		t.Fatalf("version 不该跳，实际 %d", row.Version)
	}
	if st := scalar[int16](t, ctx, conn, "SELECT status FROM tc_case WHERE case_id=$1", id); st != 4 {
		t.Fatalf("tc_case 也不该被翻状态，实际 status=%d", st)
	}
}

// 草稿 JSON 里 priority 不是合法枚举 → 投影阶段抛错，提交必须整体回滚。
func TestCommit_MalformedDraftRollsBack(t *testing.T) {
	ctx, s, _ := newStore(t)
	id := uuid.NewString()
	mustDraft(t, ctx, s, id, "坏枚举")
	s.Update(ctx, id, 0, `{"case_code":"ATP-CART-0002","title":"坏枚举","module_id":"M003",
		"priority":"P9","author":"qa","steps":[{"seq":1,"action":"CLICK"}]}`)

	r := s.Commit(ctx, id, 1)

	if r.Code != model.ValidationFailed {
		t.Fatalf("应报 VALIDATION_FAILED，实际 %s: %s", r.Code, r.Message)
	}
	if s.Show(ctx, id).Row.Status != model.StatusAIDraft {
		t.Fatal("状态必须回滚到编写态")
	}
}
