package cli

import (
	"context"
	"fmt"
	"os"
	"strings"

	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/config"
	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/model"
	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/store"
)

// Backend 是命令层看得见的全部写入能力。
//
// ⭐ 接口定义在【消费侧】而不是实现侧 —— 命令层说出它需要什么，
// 由实现去满足；反过来会让命令层跟着某一个实现的形状走。
//
// 四个方法全部返回 model.Result，这不是巧合而是前提：
// 只有返回类型一致，两个实现才能喂同一组输入直接比返回值（第 ③ 步的对拍）。
// 任何一个方法改成返回具体类型，对拍就退化成"各自测各自的"。
type Backend interface {
	// Draft 建 AI 编写态草稿。caseID 由调用方给，它是整套幂等的唯一来源。
	Draft(ctx context.Context, caseID string, ct model.CaseType, title, createdBy string) model.Result
	// Update 单行 CAS 写草稿。expectedVersion 对不上 → 10。
	Update(ctx context.Context, caseID string, expectedVersion int, draftJSON string) model.Result
	// Commit 落地为老平台原生案例。
	Commit(ctx context.Context, caseID string, expectedVersion int) model.Result
	// Show 读草稿当前内容与 version。
	Show(ctx context.Context, caseID string) model.Result
	// ListModules 模块字典 —— module_id 的合法取值范围。
	//
	// ⚠️ 它跟上面四个一起放进 Backend，不是因为它属于"写入"，
	// 而是因为【只要还有一个命令直连 PG，凭证边界就没关上】。
	// 边界是二元的，漏一个口就是没关。
	ListModules(ctx context.Context) ([]store.ModuleEntry, error)
	// Close 释放底层资源。HTTP 实现是空操作。
	Close(ctx context.Context)
}

// EnvBackend 选后端。⚠️ 迁移脚手架，删除条件明确：
// pgx 实现删掉时它一起删。留一个能切回直连数据库的开关，
// 本身就是那条被绕开的凭证边界。
const EnvBackend = "ATP_BACKEND"

// pgBackend 直连 PostgreSQL —— 迁移前的实现，迁移期作为回退路径与对拍基准。
type pgBackend struct {
	*store.CaseStore
	dict *store.DictStore
	conn interface{ Close(context.Context) error }
}

func (b *pgBackend) ListModules(ctx context.Context) ([]store.ModuleEntry, error) {
	return b.dict.ListModules(ctx)
}

func (b *pgBackend) Close(ctx context.Context) { _ = b.conn.Close(ctx) }

func openPG(ctx context.Context) (Backend, error) {
	dsn, err := config.Load().DSN()
	if err != nil {
		return nil, err
	}
	conn, err := store.Open(ctx, dsn)
	if err != nil {
		return nil, fmt.Errorf("连不上数据库: %w", err)
	}
	return &pgBackend{
		CaseStore: store.NewCaseStore(conn),
		dict:      store.NewDictStore(conn),
		conn:      conn,
	}, nil
}

// withBackend 统一收口后端的生命周期。配置缺失或连不上一律 INFRA_ERROR(20)。
//
// 命令层到此为止 —— 它不知道底下是 pgx 还是 HTTP，也不该知道。
func (a *app) withBackend(fn func(ctx context.Context, be Backend) int) error {
	ctx := context.Background()

	var (
		b   Backend
		err error
	)
	switch kind := strings.ToLower(strings.TrimSpace(os.Getenv(EnvBackend))); kind {
	case "", "pg":
		b, err = openPG(ctx)
	case "api":
		err = fmt.Errorf("ATP_BACKEND=api 尚未实现（迁移第 ② 步）")
	default:
		err = fmt.Errorf("ATP_BACKEND 只接受 pg / api，收到 %q", kind)
	}
	if err != nil {
		a.code = a.w().Fail(model.InfraError, err.Error(), nil)
		return nil
	}
	defer b.Close(ctx)

	a.code = fn(ctx, b)
	return nil
}
