package cli

import (
	"context"
	"fmt"
	"net/http"
	"sort"
	"strings"

	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/config"
	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/inspect"
	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/model"
	"github.com/spf13/cobra"
)

// inspectCmd 真的去打开一次被测页面，把候选定位器拿回来。
//
// ⭐ 这条命令存在的理由是一个已复现两次的失败：
// agent 把商品详情页 URL 编成 /product/p001，而真实路由是 /products/{id} ——
// 两次都通过了 validate 与 STD 规范校验，然后 404、等待超时、执行失败。
//
// 校验器管的是【形状与规范】，抓不到「agent 不知道被测系统长什么样」。
// 这不是校验器不够好，是这两类性质根本不同（DECISIONS D-124）。
//
// 所以解法不是加约束，是加工具 —— agent 编造通常是因为它没有查询的手段，
// 而不是因为它不老实（D-123）。
//
// ⚠️ 两个不变量（平台侧保证，CLI 只透传）：
//  1. candidates 里的 locatorValue 是规范允许的写法，agent 照抄就能过校验；
//  2. 探查在【执行机】上跑 —— 探查环境必须与执行环境是同一个，
//     否则看到的 DOM 未必是执行时看到的 DOM，探出来的定位器仍可能跑不通。
func (a *app) inspectCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "inspect PATH_OR_URL",
		Short: "打开被测页面，返回可直接使用的候选定位器",
		Long: "打开被测页面，返回可直接使用的候选定位器。\n\n" +
			"PATH_OR_URL 三种写法都接受，CLI 原样透传给平台：\n" +
			"  /products/p001\n" +
			"  http://host:8088/products/p001\n" +
			"  ${base_url}/products/p001    ← 案例原文写法，可以直接从步骤里贴过来\n\n" +
			"⚠️ 本命令不碰数据库，只需要 ATP_API_URL。",
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			base, err := config.Load().APIBase()
			if err != nil {
				a.code = a.w().Fail(model.InfraError, err.Error(), nil)
				return nil
			}
			res, err := inspect.New(base).Inspect(context.Background(), args[0])
			if err != nil {
				// 连不上、超时、返回非 JSON —— 都是环境问题，不是 agent 写错了
				a.code = a.w().Fail(model.InfraError, err.Error(), nil)
				return nil
			}
			a.code = a.emitInspect(res)
			return nil
		},
	}
}

// emitInspect 把平台的 HTTP 状态映射成退出码。
//
// ⭐ 12 与 20 必须分开 —— 都返回"探查失败"的话，
// agent 分不清是【自己查错了】还是【环境坏了】，大概率退回编造，
// 而编造正是这个工具要消灭的东西。（同 D-116 的判据）
func (a *app) emitInspect(r *inspect.Result) int {
	switch r.Status {
	case http.StatusOK:
		// OkRaw 自己按 --json 分派两个通道，这里不用再判一次
		return a.w().OkRaw(map[string]any{
			"url": r.Page.URL, "title": r.Page.Title,
			"httpStatus": r.Page.HTTPStatus, "candidates": r.Page.Candidates,
		}, renderPage(r.Page))

	case http.StatusNotFound:
		// 你查错了 —— agent 自己能改：换个路径，或者问用户
		return a.w().Fail(model.ValidationFailed,
			"页面不存在，请换一个路径，或向用户确认正确的 URL",
			[]string{fmt.Sprintf("页面不存在（HTTP 404）: %s", nonEmpty(r.Page.URL, r.Page.Message))})

	default:
		// 环境坏了 —— 别改案例，重试或如实报告
		return a.w().Fail(model.InfraError,
			nonEmpty(r.Page.Message,
				fmt.Sprintf("平台探查失败（HTTP %d），请重试或报告环境问题", r.Status)), nil)
	}
}

// renderPage 人类可读输出：按 kind 分组，locatorValue 是重点。
func renderPage(p inspect.Page) string {
	var b strings.Builder
	fmt.Fprintf(&b, "%s\n%s\n", p.Title, p.URL)
	if len(p.Candidates) == 0 {
		b.WriteString("\n(页面打开了，但没有找到符合规范的候选定位器)")
		return b.String()
	}

	byKind := map[string][]inspect.Candidate{}
	for _, c := range p.Candidates {
		byKind[c.Kind] = append(byKind[c.Kind], c)
	}
	kinds := make([]string, 0, len(byKind))
	for k := range byKind {
		kinds = append(kinds, k)
	}
	// testid 排最前 —— 它是最稳的一类，agent 该优先用
	sort.Slice(kinds, func(i, j int) bool {
		if (kinds[i] == "testid") != (kinds[j] == "testid") {
			return kinds[i] == "testid"
		}
		return kinds[i] < kinds[j]
	})

	for _, k := range kinds {
		fmt.Fprintf(&b, "\n[%s]  %d 个\n", k, len(byKind[k]))
		for _, c := range byKind[k] {
			fmt.Fprintf(&b, "  %-58s %s%s\n", c.LocatorValue, c.Text, note(c.Note))
		}
	}
	fmt.Fprintf(&b, "\n共 %d 个候选。locatorValue 可直接写进案例步骤 —— 平台保证它们符合定位器规范。",
		len(p.Candidates))
	return b.String()
}

func note(s string) string {
	if s == "" {
		return ""
	}
	return "  (" + s + ")"
}

func nonEmpty(vals ...string) string {
	for _, v := range vals {
		if v != "" {
			return v
		}
	}
	return ""
}
