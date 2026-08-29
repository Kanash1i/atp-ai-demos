package store_test

import (
	"context"
	"fmt"
	"os"
	"testing"

	atpcli "github.com/Kanash1i/atp-ai-demos/atp-cli"
	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/model"
	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/store"
	"github.com/jackc/pgx/v5"
	"github.com/testcontainers/testcontainers-go"
	tcpostgres "github.com/testcontainers/testcontainers-go/modules/postgres"
	"github.com/testcontainers/testcontainers-go/wait"
)

// dsn 指向 TestMain 起的那个真 PostgreSQL。
var dsn string

// TestMain 起一个真 PostgreSQL，按 V0 → V1 的顺序跑迁移。
//
// ⭐ 测试【先建老表、再跑改造脚本】，而不是直接建改造后的表 ——
// 这样 V1 是不是真能在老平台的形状上执行得下去，本身就被测到了。
func TestMain(m *testing.M) {
	ctx := context.Background()
	c, err := tcpostgres.Run(ctx, "postgres:17",
		tcpostgres.WithDatabase("atp"),
		tcpostgres.WithUsername("atp"),
		tcpostgres.WithPassword("atp"),
		testcontainers.WithWaitStrategy(wait.ForListeningPort("5432/tcp")),
	)
	if err != nil {
		fmt.Fprintln(os.Stderr, "起 PostgreSQL 容器失败:", err)
		os.Exit(1)
	}
	defer testcontainers.TerminateContainer(c) //nolint:errcheck

	dsn, err = c.ConnectionString(ctx, "sslmode=disable")
	if err != nil {
		fmt.Fprintln(os.Stderr, "取连接串失败:", err)
		os.Exit(1)
	}
	conn, err := store.Open(ctx, dsn)
	if err != nil {
		fmt.Fprintln(os.Stderr, "连不上:", err)
		os.Exit(1)
	}
	v0, v1 := atpcli.MigrationSQL()
	for _, sql := range []string{v0, v1} {
		if _, err := conn.Exec(ctx, sql); err != nil {
			fmt.Fprintln(os.Stderr, "迁移失败:", err)
			os.Exit(1)
		}
	}
	conn.Close(ctx)

	os.Exit(m.Run())
}

// ------------------------------------------------------------------ 夹具

func openConn(t *testing.T) *pgx.Conn {
	t.Helper()
	ctx := context.Background()
	conn, err := store.Open(ctx, dsn)
	if err != nil {
		t.Fatalf("连不上: %v", err)
	}
	t.Cleanup(func() { conn.Close(ctx) })
	return conn
}

// newStore 每个用例一条干净的连接 + 清空数据。
//
// ⚠️ 顺序不能反，也不能只删父表：本库不建外键、没有 ON DELETE CASCADE，
// 只删 tc_case 会把孤儿步骤漏给下一个用例。M5 的清理任务面对同一个约束。
func newStore(t *testing.T) (context.Context, *store.CaseStore, *pgx.Conn) {
	t.Helper()
	ctx := context.Background()
	conn := openConn(t)
	for _, sql := range []string{"DELETE FROM tc_step", "DELETE FROM tc_case"} {
		if _, err := conn.Exec(ctx, sql); err != nil {
			t.Fatalf("清库失败: %v", err)
		}
	}
	return ctx, store.NewCaseStore(conn), conn
}

// completeDraft 一份能通过 ck_case_complete 的完整草稿。
// module_id 必须是 tc_module 里真实存在的值。
func completeDraft(title string, steps int) string {
	arr := ""
	for i := 1; i <= steps; i++ {
		if i > 1 {
			arr += ","
		}
		arr += fmt.Sprintf(`{"seq":%d,"action":"CLICK","wait_strategy":"VISIBLE"}`, i)
	}
	return fmt.Sprintf(`{"case_code":"ATP-CART-0001","title":%q,"module_id":"M003",
		"priority":"P1","author":"qa.kanashi","precondition":"已登录且购物车非空","steps":[%s]}`,
		title, arr)
}

func mustDraft(t *testing.T, ctx context.Context, s *store.CaseStore, id, title string) {
	t.Helper()
	if r := s.Draft(ctx, id, model.TypePCWeb, title, "agent-a"); !r.Succeeded() {
		t.Fatalf("建草稿失败: %s %s", r.Code, r.Message)
	}
}

func scalar[T any](t *testing.T, ctx context.Context, conn *pgx.Conn, sql string, args ...any) T {
	t.Helper()
	var v T
	if err := conn.QueryRow(ctx, sql, args...).Scan(&v); err != nil {
		t.Fatalf("查询失败 %q: %v", sql, err)
	}
	return v
}
