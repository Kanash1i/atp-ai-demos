package cli

import (
	"context"
	"fmt"
	"net/http"
	"strings"

	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/config"
	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/inspect"
	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/model"
	"github.com/spf13/cobra"
)

// runCmd 提交之后跑一次自验。
//
// ⭐ 只跑一次，如实报告，人决定改不改 —— 刻意不做「失败就改、改完再跑」的闭环。
// 两个理由任一都足以否掉自动重试，见 DECISIONS D-128。
//
// ⚠️ 本命令不碰数据库，只需要 ATP_API_URL。
func (a *app) runCmd() *cobra.Command {
	var timeoutSec int
	c := &cobra.Command{
		Use:   "run CASE_ID",
		Short: "跑一次自验，如实报告结果（不自动重试，不自动改案例）",
		Long: "跑一次自验，如实报告结果。\n\n" +
			"⭐ 只跑一次，不做「失败就改、改完再跑」的闭环：\n" +
			"  · 执行失败 ≠ 案例写错了 —— 被测系统真有 bug 时，\n" +
			"    自动改案例会把 bug 改没，而发现 bug 正是测试的目的。\n" +
			"  · 改到能跑通 ≠ 改对了 —— 以「跑通」为目标，最省力的路径是削弱断言。\n\n" +
			"⚠️ 案例 FAILED 也是退出码 0 —— 那是一个有效结论，不是错误。\n" +
			"   只有【拿不到结论】（没有执行机认领 / 等超时）才是 20。\n\n" +
			"⚠️ 本命令不碰数据库，只需要 ATP_API_URL。",
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			cfg := config.Load()
			base, err := cfg.APIBase()
			if err != nil {
				a.code = a.w().Fail(model.InfraError, err.Error(), nil)
				return nil
			}
			res, err := inspect.New(base, cfg.Optional(config.EnvClientID), cfg.Optional(config.EnvClientSecret)).Run(context.Background(), args[0], timeoutSec)
			if err != nil {
				a.code = a.w().Fail(model.InfraError, err.Error(), nil)
				return nil
			}
			a.code = a.emitRun(res)
			return nil
		},
	}
	c.Flags().IntVar(&timeoutSec, "timeout", 120, "等待执行结果的秒数（平台侧上限 300）")
	return c
}

// emitRun 把平台的 HTTP 状态映射成退出码。
//
// ⭐ 这里的区分和 inspect 不同，别照搬：
//
//	200 → 0   拿到结论了，【不论案例 PASSED 还是 FAILED】
//	504 → 20  没拿到结论：没有执行机认领，或等超时
//
// inspect 的 404 用 12，因为那说明"你路径写错了"，是 agent 自己的问题。
// 而案例 FAILED 未必是 agent 的问题（可能是被测系统真有 bug）——
// 用 12 会诱导 agent 去「修正」一份本来就对的案例。
func (a *app) emitRun(r *inspect.Result2) int {
	if r.Status == http.StatusOK {
		return a.w().OkRaw(r.Run, renderRun(r.Run))
	}
	// 拿不到结论 —— 环境问题。别让 agent 以为是自己写的案例不行。
	msg := deref(r.Run.Note)
	if msg == "" {
		msg = fmt.Sprintf("没有拿到执行结论（HTTP %d）", r.Status)
	}
	return a.w().Fail(model.InfraError,
		msg+"。这是环境问题，不是案例的问题 —— 请检查执行机是否在线，别改案例", nil)
}

func renderRun(r inspect.RunResult) string {
	var b strings.Builder
	fmt.Fprintf(&b, "%s   %s\n", statusBadge(r.Status), r.RunCode)
	if r.DurationMs != nil {
		fmt.Fprintf(&b, "耗时 %d ms\n", *r.DurationMs)
	}
	if r.FailedSeq != nil {
		fmt.Fprintf(&b, "\n失败在第 %d 步\n", *r.FailedSeq)
	}
	if em := deref(r.ErrorMsg); em != "" {
		// Playwright 的堆栈很长，人只看得下第一行
		fmt.Fprintf(&b, "  %s\n", strings.SplitN(em, "\n", 2)[0])
	}
	if v := deref(r.VideoURL); v != "" {
		fmt.Fprintf(&b, "\n录像 %s\n", v)
	}
	if r.Status == "FAILED" {
		b.WriteString("\n⚠️ 跑挂了不等于案例写错了 —— 也可能是被测系统真有 bug。\n" +
			"   请人工判断，别直接改案例（尤其别削弱断言让它变绿）。")
	}
	return strings.TrimRight(b.String(), "\n")
}

func statusBadge(s string) string {
	switch s {
	case "PASSED":
		return "✓ PASSED"
	case "FAILED":
		return "✗ FAILED"
	default:
		return "· " + s
	}
}

func deref(p *string) string {
	if p == nil {
		return ""
	}
	return *p
}
