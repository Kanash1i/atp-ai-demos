package cli

import (
	"context"
	"encoding/json"
	"fmt"
	"os"

	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/model"
	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/rule"
	"github.com/google/uuid"
	"github.com/spf13/cobra"
)

// draftCmd 建草稿行，拿到 caseId 与 version=0。
//
// ⭐ --id 由调用方给，并且在重试时必须复用同一个 —— 这是整套幂等的唯一来源。
// 不给时 CLI 本地生成一个，但那样重试就不幂等了（会产生两条各自合法的草稿）。
// agent 应当自己 uuidgen 一次然后固定用它。
func (a *app) draftCmd() *cobra.Command {
	var id, platform, title, by string
	c := &cobra.Command{
		Use:   "draft",
		Short: "建一条 AI 编写态草稿，返回 caseId 与 version",
		RunE: func(cmd *cobra.Command, _ []string) error {
			ct, err := model.ParseCaseType(platform)
			if err != nil {
				a.code = a.w().Fail(model.ValidationFailed, err.Error(), nil)
				return nil
			}
			if id == "" {
				id = uuid.NewString()
			}
			return a.withBackend(func(ctx context.Context, be Backend) int {
				return a.w().Emit(be.Draft(ctx, id, ct, title, by))
			})
		},
	}
	c.Flags().StringVar(&id, "id", "",
		"案例主键（UUID）。⚠️ 重试时复用同一个 —— 它就是幂等键。不给则本地生成")
	c.Flags().StringVarP(&platform, "platform", "p", "", "执行平台: IOS / ANDROID / PC_WEB")
	c.Flags().StringVarP(&title, "title", "t", "", "案例标题")
	c.Flags().StringVar(&by, "by", "agent", "发起编写的 agent 身份")
	_ = c.MarkFlagRequired("platform")
	_ = c.MarkFlagRequired("title")
	return c
}

// showCmd 读回草稿当前内容与 version。
//
// agent 的典型用法：atp show <id> --json | jq .data.draft > draft.json
// 改完再 atp update <id> --version <data.version> -f draft.json
func (a *app) showCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "show CASE_ID",
		Short: "输出草稿当前的内容与 version",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return a.withBackend(func(ctx context.Context, be Backend) int {
				return a.w().Emit(be.Show(ctx, args[0]))
			})
		},
	}
}

// updateCmd 把整份草稿写进 tc_step.step_json。
//
// ⭐ 单表单行 CAS，不碰 tc_case。表头字段到 commit 那一刻才投影进 tc_case 的正式列。
//
// --version 是 CAS 的比较值：库里的版本号必须与它一致才写得进去。
// 对不上说明有人在你之前改过 —— 退出码 10，重新 show 再来。
//
// ⚠️ 写库前先本地校验：形状不对就不该占用一次数据库往返，而且 validate 是零成本的。
func (a *app) updateCmd() *cobra.Command {
	var version int
	var file string
	var skipValidate bool
	c := &cobra.Command{
		Use:   "update CASE_ID",
		Short: "校验并把草稿 JSON 写入案例（单表单行 CAS）",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
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
			if !skipValidate {
				v, err := rule.NewValidator()
				if err != nil {
					a.code = a.w().Fail(model.InfraError, err.Error(), nil)
					return nil
				}
				res := v.Validate(doc)
				if res.NeedsUserInput() {
					a.code = a.w().NeedsInput("必填字段缺失，机器补不出来，请向用户确认后再填", res.Missing)
					return nil
				}
				if !res.Passed() {
					a.code = a.w().Fail(model.ValidationFailed,
						"字段值不合法，请按下列 violations 修正后重试", res.Invalid)
					return nil
				}
			}
			return a.withBackend(func(ctx context.Context, be Backend) int {
				return a.w().Emit(be.Update(ctx, args[0], version, string(raw)))
			})
		},
	}
	c.Flags().IntVar(&version, "version", -1,
		"你手上那份的版本号（来自 draft / show / preview）。CAS 比较值")
	c.Flags().StringVarP(&file, "file", "f", "", "草稿 JSON 文件")
	c.Flags().BoolVar(&skipValidate, "skip-validate", false, "跳过本地校验（不建议，仅用于排查）")
	_ = c.MarkFlagRequired("version")
	_ = c.MarkFlagRequired("file")
	return c
}

// previewCmd 给人看的渲染。
//
// ⭐ 它读的是库里的行，不是本地文件 —— 用户确认的对象和最终提交的对象
// 因此在物理上就是同一份。本地 JSON 只是编辑面。
func (a *app) previewCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "preview CASE_ID",
		Short: "渲染草稿供用户确认，并打印要带回 commit 的 version",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return a.withBackend(func(ctx context.Context, be Backend) int {
				r := be.Show(ctx, args[0])
				// --json 时只出信封，人类模式才渲染下面这块（两个通道不要混着打）
				if !r.Succeeded() || a.jsonOut {
					return a.w().Emit(r)
				}
				a.renderPreview(r.Row)
				return int(model.OK)
			})
		},
	}
}

func (a *app) renderPreview(row *model.CaseRow) {
	h, err := rule.ParseHeader(row.DraftJSON)
	fmt.Fprintln(a.stdout, "──────── 待确认的案例（来自数据库，不是本地文件）────────")
	fmt.Fprintf(a.stdout, "caseId  : %s\n", row.CaseID)
	fmt.Fprintf(a.stdout, "平台    : %s   状态: %s\n", row.CaseType, row.Status)
	if err != nil {
		fmt.Fprintf(a.stdout, "⚠️ 草稿解析失败: %v\n", err)
	} else {
		fmt.Fprintf(a.stdout, "编号    : %s\n", nz(h.CaseCode))
		fmt.Fprintf(a.stdout, "标题    : %s\n", nz(h.Title))
		fmt.Fprintf(a.stdout, "模块    : %s   优先级: %s   作者: %s\n",
			nz(h.ModuleID), priorityOf(h), nz(h.Author))
		if h.Precondition != nil {
			fmt.Fprintf(a.stdout, "前置    : %s\n", *h.Precondition)
		}
	}
	fmt.Fprintln(a.stdout, "步骤    :")
	fmt.Fprintln(a.stdout, prettySteps(row.DraftJSON))
	fmt.Fprintln(a.stdout, "────────────────────────────────────────────────")
	fmt.Fprintf(a.stdout, "确认无误后执行：atp commit %s --version %d\n", row.CaseID, row.Version)
}

// commitCmd 提交：AI_DRAFT → DRAFT。
//
// ⭐ 只收 caseId 和 version，不收任何案例内容 —— 纯状态迁移 + 表头投影。
// 用户 preview 的和最终落库的，物理上就是同一份快照。
//
// ⭐ 幂等重放返回退出码 0：重放在语义上是成功。
// 返回非 0 会让 agent 以为没成功而无限重试。
func (a *app) commitCmd() *cobra.Command {
	var version int
	c := &cobra.Command{
		Use:   "commit CASE_ID",
		Short: "提交草稿，落地为老平台原生的 DRAFT 案例（执行器无感知）",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return a.withBackend(func(ctx context.Context, be Backend) int {
				return a.w().Emit(be.Commit(ctx, args[0], version))
			})
		},
	}
	c.Flags().IntVar(&version, "version", -1,
		"用户确认时看到的版本号（来自 atp preview）。对不上就是内容被改过，拒绝提交")
	_ = c.MarkFlagRequired("version")
	return c
}

// ------------------------------------------------------------------ 渲染辅助

func nz(s *string) string {
	if s == nil || *s == "" {
		return "—"
	}
	return *s
}

func priorityOf(h model.CaseHeader) string {
	if h.Priority == nil {
		return "—"
	}
	return h.Priority.String()
}

func prettySteps(draftJSON string) string {
	var doc struct {
		Steps []struct {
			Seq          int    `json:"seq"`
			Action       string `json:"action"`
			Description  string `json:"description"`
			LocatorValue string `json:"locator_value"`
		} `json:"steps"`
	}
	if err := json.Unmarshal([]byte(draftJSON), &doc); err != nil {
		return "  (步骤解析失败: " + err.Error() + ")"
	}
	if len(doc.Steps) == 0 {
		return "  (还没写入步骤，先跑 atp update)"
	}
	var b []byte
	for _, s := range doc.Steps {
		note := s.Description
		if note == "" {
			note = s.LocatorValue
		}
		b = append(b, fmt.Sprintf("  %2d. %-16s %s\n", s.Seq, s.Action, note)...)
	}
	return string(b[:len(b)-1])
}
