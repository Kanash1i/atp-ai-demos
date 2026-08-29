package cli

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"strings"

	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/model"
	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/rule"
	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/store"
	"github.com/jackc/pgx/v5"
	"github.com/spf13/cobra"
)

// schemaCmd ⭐ 这条命令是"左移"的落地：让 agent 在【生成阶段】就知道该产出什么形状，
// 而不是生成完再由下游收拾。只提供事后校验，等于放任上游乱生成。
//
// 零 DB、零网络、零模型。
func (a *app) schemaCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "schema",
		Short: "输出案例草稿的 JSON Schema（含必填、枚举、不该由调用方产出的字段）",
		RunE: func(cmd *cobra.Command, _ []string) error {
			// schema 本身就是 JSON，两个通道输出一样 —— 不套信封，方便直接 > schema.json
			fmt.Fprintln(a.stdout, strings.TrimRight(string(rule.SchemaJSON()), "\n"))
			a.code = 0
			return nil
		},
	}
}

// modulesCmd 模块字典 —— agent 用它确认 module_id 的取值范围。
// 防编造靠这条，不靠外键（见 DECISIONS D-109）。
func (a *app) modulesCmd() *cobra.Command {
	var project string
	c := &cobra.Command{
		Use:   "modules",
		Short: "列出项目与模块字典（module_id 的合法取值范围）",
		RunE: func(cmd *cobra.Command, _ []string) error {
			return a.withDB(func(ctx context.Context, conn *pgx.Conn) int {
				all, err := store.NewDictStore(conn).ListModules(ctx)
				if err != nil {
					return a.w().Fail(model.InfraError, err.Error(), nil)
				}
				rows := all
				if project != "" {
					rows = nil
					for _, m := range all {
						if strings.EqualFold(m.ProjectCode, project) {
							rows = append(rows, m)
						}
					}
				}
				if len(rows) == 0 {
					msg := "没有匹配的模块"
					if project != "" {
						msg += "（project=" + project + "）"
					}
					return a.w().Fail(model.NotFound, msg, nil)
				}
				var b strings.Builder
				fmt.Fprintf(&b, "  %-8s %-10s %-9s %-8s %s\n", "项目", "project_id", "module_id", "code", "名称")
				for _, m := range rows {
					fmt.Fprintf(&b, "  %-8s %-10s %-9s %-8s %s\n",
						m.ProjectCode, m.ProjectID, m.ModuleID, m.ModuleCode, m.ModuleName)
				}
				return a.w().OkRaw(rows, strings.TrimRight(b.String(), "\n"))
			})
		},
	}
	c.Flags().StringVarP(&project, "project", "p", "", "只看某个项目（project_code，如 ECSHOP）")
	return c
}

// validateCmd 纯本地校验：零网络、零 DB、零模型调用，毫秒级。
//
// agent 可以放心高频调、并发调 —— 纯函数，没有任何副作用。
// 这也是 CLI 相对 MCP 最直接的收益：批量校验不需要 N 次来回过模型。
func (a *app) validateCmd() *cobra.Command {
	var file string
	c := &cobra.Command{
		Use:   "validate",
		Short: "校验草稿 JSON 的形状（纯本地，无副作用）",
		RunE: func(cmd *cobra.Command, _ []string) error {
			raw, err := os.ReadFile(file)
			if err != nil {
				a.code = a.w().Fail(model.NotFound, "读不到文件: "+err.Error(), nil)
				return nil
			}
			var doc any
			if err := json.Unmarshal(raw, &doc); err != nil {
				a.code = a.w().Fail(model.ValidationFailed, "不是合法 JSON: "+err.Error(), nil)
				return nil
			}
			v, err := rule.NewValidator()
			if err != nil {
				a.code = a.w().Fail(model.InfraError, err.Error(), nil)
				return nil
			}
			res := v.Validate(doc)
			switch {
			case res.Passed():
				a.code = a.w().OkRaw(map[string]any{"file": file, "violations": []string{}},
					"✓ 校验通过: "+file)
			case res.NeedsUserInput():
				// ⭐ 缺信息优先于值非法 —— 人不补信息，agent 改了也白改
				a.code = a.w().NeedsInput("必填字段缺失，机器补不出来，请向用户确认后再填", res.Missing)
			default:
				a.code = a.w().Fail(model.ValidationFailed,
					"字段值不合法，请按下列 violations 修正后重试", res.Invalid)
			}
			return nil
		},
	}
	c.Flags().StringVarP(&file, "file", "f", "", "草稿 JSON 文件")
	_ = c.MarkFlagRequired("file")
	return c
}
