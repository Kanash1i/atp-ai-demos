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

// ⭐ 落地后 step_json 必须是【纯步骤数组】，与人工案例完全一致。
//
// 保守路线的主张是「老执行器无感知照跑」，而老执行器读的是数组。
// 留成编辑期那个带表头的对象，那句主张就是假的 —— 而且它崩的时候没人知道是谁写进去的。
func TestCommit_NormalizesStepJSONToArray(t *testing.T) {
	ctx, s, conn := newStore(t)
	id := uuid.NewString()
	mustDraft(t, ctx, s, id, "购物车结算")
	s.Update(ctx, id, 0, completeDraft("购物车结算", 3))

	// 编辑期是对象 —— 表头暂存在里面，这是对的
	if k := scalar[string](t, ctx, conn,
		"SELECT jsonb_typeof(step_json) FROM tc_step WHERE case_id=$1", id); k != "object" {
		t.Fatalf("编辑期 step_json 应是 object，实际 %s", k)
	}

	if r := s.Commit(ctx, id, 1); r.Code != model.OK {
		t.Fatalf("提交应成功，实际 %s: %s", r.Code, r.Message)
	}

	if k := scalar[string](t, ctx, conn,
		"SELECT jsonb_typeof(step_json) FROM tc_step WHERE case_id=$1", id); k != "array" {
		t.Fatalf("落地后 step_json 必须是 array，实际 %s", k)
	}
	if n := scalar[int](t, ctx, conn,
		"SELECT jsonb_array_length(step_json) FROM tc_step WHERE case_id=$1", id); n != 3 {
		t.Fatalf("步骤应有 3 条，实际 %d", n)
	}
	// 表头必须已经从 step_json 里消失（它现在住在 tc_case 的正式列上）
	if n := scalar[int](t, ctx, conn, `
		SELECT count(*) FROM tc_step
		 WHERE case_id=$1 AND step_json::text LIKE '%case_code%'`, id); n != 0 {
		t.Fatal("规整后 step_json 里不该还有表头字段")
	}

	row := s.Show(ctx, id).Row
	if row.Status != model.StatusDraft || row.PlatformStatus != model.StatusDraft {
		t.Fatalf("两张表都该是 DRAFT，实际 step=%s case=%s", row.Status, row.PlatformStatus)
	}
}

// 幂等重放不能把已经规整好的数组改坏。
//
// SQL 靠 COALESCE(step_json->'steps', step_json) 自幂等：
// 已是数组时 -> 'steps' 返回 NULL，原样保留。
func TestCommit_ReplayDoesNotCorruptNormalizedArray(t *testing.T) {
	ctx, s, conn := newStore(t)
	id := uuid.NewString()
	mustDraft(t, ctx, s, id, "购物车结算")
	s.Update(ctx, id, 0, completeDraft("购物车结算", 4))
	s.Commit(ctx, id, 1)

	before := scalar[string](t, ctx, conn, "SELECT step_json::text FROM tc_step WHERE case_id=$1", id)

	r := s.Commit(ctx, id, 1) // 重放
	if r.Code != model.OK || !r.Replayed {
		t.Fatalf("重放应是 OK + replayed，实际 %s replayed=%v", r.Code, r.Replayed)
	}

	after := scalar[string](t, ctx, conn, "SELECT step_json::text FROM tc_step WHERE case_id=$1", id)
	if before != after {
		t.Fatalf("重放不该改动 step_json\n改前: %s\n改后: %s", before, after)
	}
	if k := scalar[string](t, ctx, conn,
		"SELECT jsonb_typeof(step_json) FROM tc_step WHERE case_id=$1", id); k != "array" {
		t.Fatalf("重放后仍应是 array，实际 %s", k)
	}
}

// 草稿里没有 steps 数组时，规整挡不住、ck_case_complete 也管不到 ——
// 必须在提交前拦下，否则会写进一个执行器读不了的对象。
func TestCommit_MissingStepsArrayIsRejected(t *testing.T) {
	ctx, s, conn := newStore(t)
	id := uuid.NewString()
	mustDraft(t, ctx, s, id, "没有步骤")
	// 表头齐全（能过 ck_case_complete），但没有 steps 键
	s.Update(ctx, id, 0, `{"case_code":"ATP-CART-0002","title":"没有步骤","module_id":"M003",
		"priority":"P1","author":"qa.kanashi"}`)

	r := s.Commit(ctx, id, 1)

	if r.Code != model.ValidationFailed {
		t.Fatalf("应报 VALIDATION_FAILED，实际 %s: %s", r.Code, r.Message)
	}
	if s.Show(ctx, id).Row.Status != model.StatusAIDraft {
		t.Fatal("状态必须回滚到编写态")
	}
	if k := scalar[string](t, ctx, conn,
		"SELECT jsonb_typeof(step_json) FROM tc_step WHERE case_id=$1", id); k != "object" {
		t.Fatalf("回滚后 step_json 应原样是 object，实际 %s", k)
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
