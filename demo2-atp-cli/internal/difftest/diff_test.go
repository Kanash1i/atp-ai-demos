// Package difftest 是迁移的对拍：同一组输入分别喂 pgx 实现和 HTTP 实现，
// 比 model.Result。
//
// ⭐ 这是迁移正确性最直接的证据，比"两边各自的测试都绿"强一个量级 ——
// 各自绿只说明各自符合自己的预期，不说明两者一致。我 pgx 侧的 update
// 一直不认重放、平台侧认，两边测试当时全绿，因为【各自都没测那条路径】。
//
// ⚠️ 所以对拍的场景表必须覆盖【失败与重放路径】，不能只跑正常流程。
// 只跑正常路径的对拍会漏掉的，恰好就是最容易分叉的那些分支。
//
// 两个实现写同一个库，各用各的 caseId，因此比对时忽略 caseId 本身。
// 用 ATP-DIFF-9xxx 号段，不碰演示资产与种子数据。
//
// 这个包连同 pgx 实现一起，在迁移第 ④ 步删掉。
package difftest

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"reflect"
	"testing"
	"time"

	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/apistore"
	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/config"
	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/model"
	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/store"
	"github.com/google/uuid"
)

// backend 是两个实现的公共面 —— 与 cli.Backend 同形，
// 这里单独声明是为了不让测试包依赖命令层。
type backend interface {
	Draft(ctx context.Context, caseID string, ct model.CaseType, title, createdBy string) model.Result
	Update(ctx context.Context, caseID string, expectedVersion int, draftJSON string) model.Result
	Commit(ctx context.Context, caseID string, expectedVersion int) model.Result
	Show(ctx context.Context, caseID string) model.Result
}

type pgBackend struct{ *store.CaseStore }

var codeSeq = time.Now().UnixNano() % 1000

func nextCode() string {
	codeSeq++
	return fmt.Sprintf("ATP-DIFF-9%03d", codeSeq%1000)
}

// draftJSON 造一份【合规】的草稿。
//
// ⚠️ 必须合规，否则对拍会卡在内容质量上而不是路径行为上：
// 平台 commit 跑完整 STD 校验，ERROR 一律拦下；而 CLI 的 pgx 路径不跑
// （STD 规则引擎在 CLI 侧从未实现，M3 已取消）。用不合规的载荷去对拍，
// 每条脚本都会挂在同一个原因上，真正的路径差异反而被埋掉。
//
// 合规要点：CLICK 的 wait_strategy 必须 CLICKABLE（STD-005），
// ASSERT_* 必须 VISIBLE（STD-006），整条案例至少一个 ASSERT_*（STD-008）。
func draftJSON(code, title string, steps int) string {
	arr := ""
	for i := 1; i <= steps; i++ {
		if i > 1 {
			arr += ","
		}
		arr += fmt.Sprintf(`{"seq":%d,"action":"CLICK","locator_type":"CSS",`+
			`"locator_value":"[data-testid=\"btn-%d\"]","wait_strategy":"CLICKABLE",`+
			`"wait_timeout_sec":10,"on_failure":"ABORT"}`, i, i)
	}
	arr += fmt.Sprintf(`,{"seq":%d,"action":"ASSERT_VISIBLE","locator_type":"CSS",`+
		`"locator_value":"[data-testid=\"result\"]","wait_strategy":"VISIBLE",`+
		`"wait_timeout_sec":10,"on_failure":"ABORT"}`, steps+1)
	return fmt.Sprintf(`{"case_code":%q,"title":%q,"module_id":"M003",`+
		`"priority":"P1","author":"qa.kanashi","precondition":"已登录且购物车非空","steps":[%s]}`,
		code, title, arr)
}

// backends 起两个实现。平台或库不可达就跳过 —— 对拍要真环境，
// 但不该让没有环境的人 go test ./... 挂掉。
func backends(t *testing.T) (pg, api backend, cleanup func()) {
	t.Helper()
	ctx := context.Background()

	base, err := config.Load().APIBase()
	if err != nil {
		t.Skipf("没有 ATP_API_URL，跳过对拍：%v", err)
	}
	if c, err := net.DialTimeout("tcp", hostPort(base), 2*time.Second); err != nil {
		t.Skipf("平台不可达（%s），跳过对拍：%v", base, err)
	} else {
		c.Close()
	}

	dsn, err := config.Load().DSN()
	if err != nil {
		t.Skipf("没有数据库配置，跳过对拍：%v", err)
	}
	conn, err := store.Open(ctx, dsn)
	if err != nil {
		t.Skipf("连不上数据库，跳过对拍：%v", err)
	}

	return &pgBackend{store.NewCaseStore(conn)},
		apistore.New(base, os.Getenv(config.EnvClientID), os.Getenv(config.EnvClientSecret)),
		func() { conn.Close(ctx) }
}

func hostPort(base string) string {
	s := base
	for _, p := range []string{"http://", "https://"} {
		if len(s) > len(p) && s[:len(p)] == p {
			s = s[len(p):]
		}
	}
	for i, c := range s {
		if c == '/' {
			s = s[:i]
			break
		}
	}
	if _, _, err := net.SplitHostPort(s); err != nil {
		return s + ":80"
	}
	return s
}

// snapshot 是 Result 里【两个实现必须一致】的部分。
//
// caseId 不在内：两边各写各的案例，id 本来就不同。
// PlatformVersion 不在内：DraftView 没有它，这是刻意的（见 apistore.toRow）。
type snapshot struct {
	Code           string
	Replayed       bool
	Version        int
	Status         string
	PlatformStatus string
	CaseType       string
	Draft          any // JSON 语义比，不比字符串
	HasMessage     bool
}

func snap(r model.Result) snapshot {
	s := snapshot{Code: r.Code.String(), Replayed: r.Replayed, HasMessage: r.Message != ""}
	if r.Row != nil {
		s.Version = r.Row.Version
		s.Status = r.Row.Status.String()
		s.PlatformStatus = r.Row.PlatformStatus.String()
		s.CaseType = r.Row.CaseType.String()
		if r.Row.DraftJSON != "" {
			_ = json.Unmarshal([]byte(r.Row.DraftJSON), &s.Draft)
			// case_code 跟 caseId 同理：两个实现写同一个库，号必须各不相同，
			// 所以它本来就该不一样。留着比会让每一步都"不一致"，真差异被埋掉。
			if m, ok := s.Draft.(map[string]any); ok {
				delete(m, "case_code")
			}
		}
	}
	return s
}

// script 是一串对同一个案例的操作。两个实现各跑一遍，逐步比。
//
// pending 非空表示【已知的、平台侧正在修的】分叉：差异照样打出来，但不判失败。
// 这样 go test ./... 保持绿，同时不把问题藏起来 —— 平台修好之后
// 删掉这一行 pending，断言就自动生效。
type script struct {
	name    string
	pending string
	run     func(ctx context.Context, b backend, id, code string) []model.Result
}

var scripts = []script{
	{"正常路径：draft → update → commit", "", func(ctx context.Context, b backend, id, code string) []model.Result {
		return []model.Result{
			b.Draft(ctx, id, model.TypePCWeb, "购物车结算", "agent-a"),
			b.Update(ctx, id, 0, draftJSON(code, "购物车结算", 2)),
			b.Commit(ctx, id, 1),
			b.Show(ctx, id),
		}
	}},

	// ⭐ 这条就是两个实现真实分叉过的那一条。
	{"update 响应丢失后重试（同 version 同内容）", "", func(ctx context.Context, b backend, id, code string) []model.Result {
		p := draftJSON(code, "登录成功", 2)
		return []model.Result{
			b.Draft(ctx, id, model.TypePCWeb, "登录成功", "agent-a"),
			b.Update(ctx, id, 0, p),
			b.Update(ctx, id, 0, p), // 重放，必须两边都是 0 + replayed
		}
	}},

	{"update 同 version 不同内容（不是重放，是冲突）", "", func(ctx context.Context, b backend, id, code string) []model.Result {
		return []model.Result{
			b.Draft(ctx, id, model.TypePCWeb, "登录成功", "agent-a"),
			b.Update(ctx, id, 0, draftJSON(code, "A 写的", 2)),
			b.Update(ctx, id, 0, draftJSON(code, "B 写的", 2)),
		}
	}},

	{"commit 响应丢失后重试", "平台侧 PR #49：重放检查要排在表头校验之前 —— " +
		"第一次 commit 之后 step_json 已规整成纯数组、表头投影进 tc_case，" +
		"第二次再校验表头必然报「缺 case_code」。修好后删掉这行 pending。",
		func(ctx context.Context, b backend, id, code string) []model.Result {
			return []model.Result{
				b.Draft(ctx, id, model.TypePCWeb, "购物车结算", "agent-a"),
				b.Update(ctx, id, 0, draftJSON(code, "购物车结算", 2)),
				b.Commit(ctx, id, 1),
				b.Commit(ctx, id, 1), // 重放，必须两边都是 0 + replayed
			}
		}},

	{"draft 重试同一个 uuid", "", func(ctx context.Context, b backend, id, code string) []model.Result {
		return []model.Result{
			b.Draft(ctx, id, model.TypePCWeb, "购物车结算", "agent-a"),
			b.Draft(ctx, id, model.TypePCWeb, "购物车结算", "agent-a"),
		}
	}},

	{"拿过期 version 提交（TOCTOU）", "", func(ctx context.Context, b backend, id, code string) []model.Result {
		return []model.Result{
			b.Draft(ctx, id, model.TypePCWeb, "购物车结算", "agent-a"),
			b.Update(ctx, id, 0, draftJSON(code, "第一版", 2)),
			b.Update(ctx, id, 1, draftJSON(code, "第二版", 2)),
			b.Commit(ctx, id, 1), // 用户确认时看到的是 1，但内容已经被改到 2
		}
	}},

	{"提交后再改（状态冲突）", "", func(ctx context.Context, b backend, id, code string) []model.Result {
		return []model.Result{
			b.Draft(ctx, id, model.TypePCWeb, "购物车结算", "agent-a"),
			b.Update(ctx, id, 0, draftJSON(code, "购物车结算", 2)),
			b.Commit(ctx, id, 1),
			b.Update(ctx, id, 2, draftJSON(code, "提交后又改", 2)),
		}
	}},

	{"不存在的案例", "", func(ctx context.Context, b backend, id, code string) []model.Result {
		ghost := uuid.NewString()
		return []model.Result{
			b.Show(ctx, ghost),
			b.Update(ctx, ghost, 0, draftJSON(code, "无主", 1)),
			b.Commit(ctx, ghost, 0),
		}
	}},

	{"内容不合规范（校验失败）", "", func(ctx context.Context, b backend, id, code string) []model.Result {
		return []model.Result{
			b.Draft(ctx, id, model.TypePCWeb, "缺步骤", "agent-a"),
			b.Update(ctx, id, 0, `{"case_code":"`+code+`","title":"缺步骤","steps":[]}`),
			b.Commit(ctx, id, 1),
		}
	}},
}

func TestDiff_TwoBackendsAgree(t *testing.T) {
	pg, api, cleanup := backends(t)
	defer cleanup()
	ctx := context.Background()

	for _, sc := range scripts {
		t.Run(sc.name, func(t *testing.T) {
			if sc.pending != "" {
				t.Logf("⏳ 已知分叉，暂不判失败：%s", sc.pending)
			}
			// 各用各的 caseId 与 case_code —— 同库，不能撞
			gotPG := sc.run(ctx, pg, uuid.NewString(), nextCode())
			gotAPI := sc.run(ctx, api, uuid.NewString(), nextCode())

			if len(gotPG) != len(gotAPI) {
				t.Fatalf("步数不一致：pgx %d 步，http %d 步", len(gotPG), len(gotAPI))
			}
			for i := range gotPG {
				a, b := snap(gotPG[i]), snap(gotAPI[i])
				if reflect.DeepEqual(a, b) {
					continue
				}
				// 指出【哪个字段】不一致 —— 只说"不一致"的话还得自己去比
				var diffs []string
				for _, f := range []struct {
					name string
					x, y any
				}{
					{"code", a.Code, b.Code}, {"replayed", a.Replayed, b.Replayed},
					{"version", a.Version, b.Version}, {"status", a.Status, b.Status},
					{"platformStatus", a.PlatformStatus, b.PlatformStatus},
					{"caseType", a.CaseType, b.CaseType}, {"draft", a.Draft, b.Draft},
					{"hasMessage", a.HasMessage, b.HasMessage},
				} {
					if !reflect.DeepEqual(f.x, f.y) {
						diffs = append(diffs, fmt.Sprintf("%s: pgx=%v http=%v", f.name, f.x, f.y))
					}
				}
				report := t.Errorf
				if sc.pending != "" {
					report = t.Logf
				}
				report("第 %d 步不一致 —— %v\n  pgx  message: %s\n  http message: %s",
					i+1, diffs, gotPG[i].Message, gotAPI[i].Message)
			}
		})
	}
}
