// Package cli 是命令层：解析参数、调 store 或 rule、把结果交给 out。
//
// 每个命令都刻意很薄 —— 业务不变式在 store，纯规则在 rule，这里只做接线。
package cli

import (
	"io"

	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/model"
	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/out"
	"github.com/spf13/cobra"
)

const Version = "0.1.0"

// app 持有一次调用的全部状态。
//
// code 是子命令写进来的退出码 —— cobra 的 RunE 只能返回 error，
// 而我们需要的是 7 个有语义的码，所以让子命令直接写这里，Execute 最后读走。
type app struct {
	jsonOut bool
	stdout  io.Writer
	stderr  io.Writer
	code    int
}

func (a *app) w() *out.Writer {
	return &out.Writer{JSON: a.jsonOut, Out: a.stdout, Err: a.stderr}
}

// Execute 跑根命令并返回退出码。不在这里 os.Exit —— 测试要能直接调。
func Execute(args []string, stdout, stderr io.Writer) int {
	a := &app{stdout: stdout, stderr: stderr}

	root := &cobra.Command{
		Use:   "atp",
		Short: "ATP 案例编写 CLI",
		Long: "ATP 案例编写 CLI —— 幂等键做成平台案例表的主键，\n" +
			"唯一约束加一条 CAS UPDATE 当并发仲裁点。所有命令均不调用模型。",
		Version:       Version,
		SilenceUsage:  true,
		SilenceErrors: true,
		RunE:          func(cmd *cobra.Command, _ []string) error { return cmd.Help() },
	}
	root.PersistentFlags().BoolVar(&a.jsonOut, "json", false,
		"输出结构化信封（给 agent 用）。默认输出人类可读文本")
	root.SetOut(stdout)
	root.SetErr(stderr)
	root.SetArgs(args)
	root.AddCommand(
		a.schemaCmd(), a.modulesCmd(), a.validateCmd(),
		a.draftCmd(), a.showCmd(), a.updateCmd(), a.previewCmd(), a.commitCmd(),
		a.inspectCmd(), a.runCmd(),
	)

	if err := root.Execute(); err != nil {
		// 参数错误归 VALIDATION_FAILED —— 别给 agent 打一整页 usage 当结果。
		//
		// ⭐ 走 a.w() 而不是直接 Fprintf：对外契约的承诺是
		//    「带 --json 时，任何失败都是一个 JSON 信封」。
		//    调用方不该为"早期参数错误"单写一条纯文本解析路径。
		//
		// ⚠️ 唯一的例外：如果 --json 自己都没被解析到（比如它排在一个非法 flag 后面），
		//    a.jsonOut 还是 false，此时退回纯文本。这个边界无法消除 ——
		//    要输出信封，总得先知道调用方要不要信封。
		return a.w().Fail(model.ValidationFailed, err.Error(), nil)
	}
	return a.code
}
