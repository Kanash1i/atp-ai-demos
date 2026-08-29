package store_test

import (
	"testing"

	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/model"
	"github.com/google/uuid"
)

func TestCommit_UnknownIDIsNotFound(t *testing.T) {
	ctx, s, _ := newStore(t)
	r := s.Commit(ctx, uuid.NewString(), 0)
	if r.Code != model.NotFound {
		t.Fatalf("不存在的 id 应报 NOT_FOUND(11)，实际 %s", r.Code)
	}
	if int(r.Code) != 11 {
		t.Fatalf("NOT_FOUND 的码值必须是 11，实际 %d", int(r.Code))
	}
}

// 提交之后又有人动过这份快照 → STATE_CONFLICT，不是重放。
func TestCommit_ModifiedAfterCommitIsStateConflict(t *testing.T) {
	ctx, s, conn := newStore(t)
	id := uuid.NewString()
	mustDraft(t, ctx, s, id, "登录成功")
	s.Update(ctx, id, 0, completeDraft("登录成功", 2))
	s.Commit(ctx, id, 1)

	// ⚠️ CAS 在 tc_step 上 —— 编辑期的乐观锁住在那张表
	if _, err := conn.Exec(ctx, "UPDATE tc_step SET version = version + 1 WHERE case_id=$1", id); err != nil {
		t.Fatal(err)
	}

	r := s.Commit(ctx, id, 1)
	if r.Code != model.StateConflict {
		t.Fatalf("应报 STATE_CONFLICT，实际 %s: %s", r.Code, r.Message)
	}
	if r.Replayed {
		t.Fatal("这不是重放")
	}
}

// 退出码取值锁定 —— agent 的分派全靠它，改动等于破坏契约。
func TestExitCodeContract(t *testing.T) {
	for _, tc := range []struct {
		code model.ExitCode
		want int
		name string
	}{
		{model.OK, 0, "OK"},
		{model.VersionConflict, 10, "VERSION_CONFLICT"},
		{model.NotFound, 11, "NOT_FOUND"},
		{model.ValidationFailed, 12, "VALIDATION_FAILED"},
		{model.StateConflict, 13, "STATE_CONFLICT"},
		{model.NeedsInput, 14, "NEEDS_INPUT"},
		{model.InfraError, 20, "INFRA_ERROR"},
	} {
		if int(tc.code) != tc.want {
			t.Errorf("%s 的码值应是 %d，实际 %d", tc.name, tc.want, int(tc.code))
		}
		if tc.code.String() != tc.name {
			t.Errorf("码 %d 的名字应是 %s，实际 %s", tc.want, tc.name, tc.code)
		}
	}
}
