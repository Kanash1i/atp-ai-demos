package store_test

import (
	"sync"
	"testing"

	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/model"
	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/store"
	"github.com/google/uuid"
)

// parallel 起 n 个 goroutine 同时打同一个目标。
//
// ⭐ 这是【真并行】：Go 默认 GOMAXPROCS = NumCPU，goroutine 分布在多个 OS 线程上，
// 真的会有多条连接同时撞到数据库。事件循环式的并发（await 交错）做不到这一点，
// 那种"并发测试"测不出行锁与唯一约束的行为。
//
// 每个 goroutine 一条自己的连接 —— pgx.Conn 不是并发安全的，
// 这也更贴近真实：每个 agent 进程各开各的连接。
func parallel(t *testing.T, n int, fn func(s *store.CaseStore) model.Result) []model.Result {
	t.Helper()
	var (
		wg      sync.WaitGroup
		start   = make(chan struct{})
		results = make([]model.Result, n)
	)
	for i := 0; i < n; i++ {
		conn := openConn(t)
		wg.Add(1)
		go func(i int, s *store.CaseStore) {
			defer wg.Done()
			<-start // 尽量压到同一瞬间
			results[i] = fn(s)
		}(i, store.NewCaseStore(conn))
	}
	close(start)
	wg.Wait()
	return results
}

func TestConcurrentDraft_SameUUID_OnlyOneRow(t *testing.T) {
	ctx, _, conn := newStore(t)
	id := uuid.NewString()

	results := parallel(t, 10, func(s *store.CaseStore) model.Result {
		return s.Draft(ctx, id, model.TypePCWeb, "购物车结算", "agent")
	})

	var fresh, replayed int
	for _, r := range results {
		if r.Code != model.OK {
			t.Fatalf("重放也必须是 OK，实际 %s: %s", r.Code, r.Message)
		}
		if r.Row.CaseID != id {
			t.Fatalf("caseId 不一致: %s", r.Row.CaseID)
		}
		if r.Replayed {
			replayed++
		} else {
			fresh++
		}
	}
	if fresh != 1 {
		t.Fatalf("只应有一个线程真正插入成功，实际 %d 个", fresh)
	}
	if replayed != 9 {
		t.Fatalf("其余应全部走幂等重放，实际 %d 个", replayed)
	}
	if n := scalar[int](t, ctx, conn, "SELECT count(*) FROM tc_case WHERE case_id=$1", id); n != 1 {
		t.Fatalf("库里应只有一行，实际 %d 行", n)
	}
}

func TestConcurrentDraft_RetryIsReplay(t *testing.T) {
	ctx, s, _ := newStore(t)
	id := uuid.NewString()

	first := s.Draft(ctx, id, model.TypePCWeb, "登录成功", "agent-a")
	second := s.Draft(ctx, id, model.TypePCWeb, "登录成功", "agent-a")

	if first.Replayed {
		t.Fatal("第一次不该是重放")
	}
	if !second.Replayed || second.Code != model.OK {
		t.Fatalf("第二次应是重放且退出码 OK，实际 replayed=%v code=%s", second.Replayed, second.Code)
	}
}

func TestConcurrentCommit_SameKey_OneWinsRestReplay(t *testing.T) {
	ctx, s, conn := newStore(t)
	id := uuid.NewString()
	mustDraft(t, ctx, s, id, "购物车结算")
	if r := s.Update(ctx, id, 0, completeDraft("购物车结算", 3)); !r.Succeeded() {
		t.Fatalf("update 失败: %s", r.Message)
	}

	results := parallel(t, 10, func(s *store.CaseStore) model.Result {
		return s.Commit(ctx, id, 1)
	})

	var fresh, replayed int
	for _, r := range results {
		if r.Code != model.OK {
			t.Fatalf("重放在语义上是成功，返回非 0 会让 agent 无限重试。实际 %s: %s", r.Code, r.Message)
		}
		if r.Row.Status != model.StatusDraft {
			t.Fatalf("落地状态应是 DRAFT，实际 %s", r.Row.Status)
		}
		if r.Replayed {
			replayed++
		} else {
			fresh++
		}
	}
	if fresh != 1 || replayed != 9 {
		t.Fatalf("应为 1 真提交 + 9 重放，实际 %d / %d", fresh, replayed)
	}
	// 落地为老平台原生的 DRAFT(1)，执行器无感知
	if st := scalar[int16](t, ctx, conn, "SELECT status FROM tc_case WHERE case_id=$1", id); st != 1 {
		t.Fatalf("tc_case.status 应为 1(DRAFT)，实际 %d", st)
	}
}

func TestCommit_LostResponseThenRetry(t *testing.T) {
	ctx, s, _ := newStore(t)
	id := uuid.NewString()
	mustDraft(t, ctx, s, id, "登录成功")
	s.Update(ctx, id, 0, completeDraft("登录成功", 2))

	first := s.Commit(ctx, id, 1)
	retry := s.Commit(ctx, id, 1)

	if first.Replayed {
		t.Fatal("第一次不该是重放")
	}
	if retry.Code != model.OK || !retry.Replayed {
		t.Fatalf("重试应返回 OK + replayed，实际 %s replayed=%v", retry.Code, retry.Replayed)
	}
	if retry.Row.Version != first.Row.Version {
		t.Fatalf("重放的 version 应与首次一致: %d vs %d", retry.Row.Version, first.Row.Version)
	}
}
