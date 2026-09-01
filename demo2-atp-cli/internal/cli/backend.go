package cli

import (
	"context"
	"errors"

	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/apistore"
	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/caseref"
	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/config"
	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/model"
)

// Backend 是命令层看得见的全部写入能力。
//
// ⭐ 接口定义在【消费侧】而不是实现侧 —— 命令层说出它需要什么，
// 由实现去满足；反过来会让命令层跟着某一个实现的形状走。
//
// 迁移期这里有两个实现（pgx 与 HTTP），靠对拍确认行为一致；
// 现在只剩 HTTP 一个。接口留着是因为它仍然是那道边界：
// 命令层不知道底下是什么，也不该知道。
type Backend interface {
	// Draft 建 AI 编写态草稿。caseID 由调用方给，它是整套幂等的唯一来源。
	Draft(ctx context.Context, caseID string, ct model.CaseType, title, createdBy string) model.Result
	// Update 写草稿。expectedVersion 对不上 → 10。
	Update(ctx context.Context, caseID string, expectedVersion int, draftJSON string) model.Result
	// Commit 落地为老平台原生案例。
	Commit(ctx context.Context, caseID string, expectedVersion int) model.Result
	// Show 读草稿当前内容与 version。
	Show(ctx context.Context, caseID string) model.Result
	// ListModules 模块字典 —— module_id 的合法取值范围。
	ListModules(ctx context.Context) ([]model.ModuleEntry, error)
	// Resolve 把案例编号（ATP-CART-0014）解析成 caseId；已经是 caseId 就原样返回。
	//
	// ⭐ 人在 ATP 界面上只看得到编号，caseId 那个 UUID 他们没渠道拿。
	// 工具的参数应该是用户语言里存在的东西，不是数据库主键。
	Resolve(ctx context.Context, ref string) (string, error)
	// Close 释放底层资源。HTTP 实现是空操作。
	Close(ctx context.Context)
}

// withBackend 统一收口后端的生命周期。配置缺失一律 INFRA_ERROR(20)。
//
// ⭐ 这里【没有后端可选】—— 迁移期那个 ATP_BACKEND 开关已经删掉。
// 留一行能切回直连数据库的配置，本身就是那条被绕开的凭证边界：
// 它不会被审计发现（配置是"正常"的），只会在某次排查问题时被人临时切回去，
// 然后忘了切回来。
//
// CLI 现在只持 ATP_CLIENT_ID / ATP_CLIENT_SECRET，
// 数据库账号密码只存在于平台那一侧 —— 不是靠约束 agent 别碰库，是它拿不到。
// resolved 解析案例标识后再跑 fn —— show / preview / commit / update 共用。
//
// 编号查不到映射成 11，与 caseId 查不到同一个码：对调用方来说
// 「你给的标识找不到案例」是同一件事，不该因为标识的种类不同而分成两个码。
func (a *app) resolved(be Backend, ctx context.Context, ref string,
	fn func(caseID string) int) int {
	id, err := be.Resolve(ctx, ref)
	if err != nil {
		var nf *caseref.NotFoundError
		if errors.As(err, &nf) {
			return a.w().Fail(model.NotFound, err.Error(), nil)
		}
		return a.w().Fail(model.InfraError, err.Error(), nil)
	}
	return fn(id)
}

func (a *app) withBackend(fn func(ctx context.Context, be Backend) int) error {
	ctx := context.Background()

	cfg := config.Load()
	base, err := cfg.APIBase()
	if err != nil {
		a.code = a.w().Fail(model.InfraError, err.Error(), nil)
		return nil
	}

	b := apistore.New(base, cfg.Optional(config.EnvClientID), cfg.Optional(config.EnvClientSecret))
	defer b.Close(ctx)

	a.code = fn(ctx, b)
	return nil
}
