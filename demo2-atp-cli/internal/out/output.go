// Package out 负责 atp 的两条输出通道：--json 给 agent，默认给人。
package out

import (
	"encoding/json"
	"fmt"
	"io"

	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/model"
)

// Envelope 是对 agent 的契约，改它等于改 API。
//
// ⚠️ 退出码才是 agent 的主要分派依据；信封里的 Code 是给人和日志看的冗余，
// 两者必须一致。
type Envelope struct {
	OK         bool            `json:"ok"`
	Code       string          `json:"code"`
	Replayed   bool            `json:"replayed"`
	Data       json.RawMessage `json:"data"`
	Violations []string        `json:"violations"`
	Questions  []string        `json:"questions"`
	Message    string          `json:"message,omitempty"`
}

type Writer struct {
	JSON bool
	Out  io.Writer
	Err  io.Writer
}

// Emit 把 store 层的结果落成输出与退出码。
func (w *Writer) Emit(r model.Result) int {
	if r.Succeeded() {
		return w.Ok(r.Row, r.Replayed, r.Message, r.Violations)
	}
	return w.Fail(r.Code, r.Message, r.Violations)
}

// Ok 成功输出。
//
// ⚠️ violations 在成功时【也可能非空】—— 规范校验的 WARN 不拦操作，
// 但 agent 看不到就不会去改。ok:true 且 violations 非空是正常状态，
// 它说的是"过了，但这几处该修"。
func (w *Writer) Ok(row *model.CaseRow, replayed bool, note string, violations []string) int {
	if violations == nil {
		violations = []string{}
	}
	var data json.RawMessage
	if row != nil {
		d := map[string]any{
			"caseId":   row.CaseID,
			"caseType": row.CaseType.String(),
			// status / version 来自 tc_step —— 编辑期状态机与乐观锁都在那里。
			// commit 要带回来的就是这个 version。
			"status":         row.Status.String(),
			"version":        row.Version,
			"platformStatus": row.PlatformStatus.String(),
			// ⭐ draft 就是 tc_step.step_json 本身，不用拼装：
			//    atp show --json | jq .data.draft > draft.json  改完直接喂回 atp update。
			"draft": rawOrString(row.DraftJSON),
		}
		data, _ = json.Marshal(d)
	}
	if w.JSON {
		return w.print(Envelope{OK: true, Code: model.OK.String(), Replayed: replayed,
			Data: data, Violations: violations, Questions: []string{}}, model.OK)
	}
	if row != nil {
		fmt.Fprintf(w.Out, "%s  status=%s  version=%d\n", row.DisplayRef(), row.Status, row.Version)
	}
	switch {
	case replayed:
		fmt.Fprintln(w.Out, "  (幂等重放：该操作此前已成功，未产生新的变更)")
	case note != "":
		fmt.Fprintln(w.Out, "  "+note)
	}
	for _, v := range violations {
		fmt.Fprintln(w.Out, "  ⚠ "+v)
	}
	return int(model.OK)
}

// OkRaw 任意载荷的成功输出（schema / modules 这类只读命令用）。
func (w *Writer) OkRaw(payload any, human string) int {
	if w.JSON {
		data, err := json.Marshal(payload)
		if err != nil {
			return w.Fail(model.InfraError, "序列化输出失败: "+err.Error(), nil)
		}
		return w.print(Envelope{OK: true, Code: model.OK.String(), Data: data,
			Violations: []string{}, Questions: []string{}}, model.OK)
	}
	fmt.Fprintln(w.Out, human)
	return int(model.OK)
}

func (w *Writer) Fail(code model.ExitCode, msg string, violations []string) int {
	if violations == nil {
		violations = []string{}
	}
	if w.JSON {
		return w.print(Envelope{Code: code.String(), Data: json.RawMessage("null"),
			Violations: violations, Questions: []string{}, Message: msg}, code)
	}
	fmt.Fprintf(w.Err, "[%s] %s\n", code, msg)
	for _, v := range violations {
		fmt.Fprintln(w.Err, "  - "+v)
	}
	return int(code)
}

// NeedsInput 缺信息、机器补不了 —— agent 必须去问用户，不要猜。
func (w *Writer) NeedsInput(msg string, questions []string) int {
	if questions == nil {
		questions = []string{}
	}
	if w.JSON {
		return w.print(Envelope{Code: model.NeedsInput.String(), Data: json.RawMessage("null"),
			Violations: []string{}, Questions: questions, Message: msg}, model.NeedsInput)
	}
	fmt.Fprintf(w.Err, "[%s] %s\n", model.NeedsInput, msg)
	for _, q := range questions {
		fmt.Fprintln(w.Err, "  ? "+q)
	}
	return int(model.NeedsInput)
}

func (w *Writer) print(e Envelope, code model.ExitCode) int {
	b, err := json.MarshalIndent(e, "", "  ")
	if err != nil {
		fmt.Fprintln(w.Err, "序列化输出失败: "+err.Error())
		return int(model.InfraError)
	}
	fmt.Fprintln(w.Out, string(b))
	return int(code)
}

func rawOrString(s string) any {
	if s == "" {
		return nil
	}
	var v any
	if json.Unmarshal([]byte(s), &v) == nil {
		return v
	}
	return s
}
