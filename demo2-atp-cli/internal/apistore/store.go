package apistore

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"

	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/model"
)

// APIStore 走平台 HTTP 接口的 Backend 实现。
//
// ⭐ 它跟 store.CaseStore 返回【完全一样】的 model.Result —— 这是对拍的前提：
// 同一组输入分别喂两个实现，比返回值。返回类型不一致的话，
// 迁移正确性就只能靠"两边各自的测试都绿"，那弱得多。
type APIStore struct{ c *Client }

func New(base, id, secret string) *APIStore { return &APIStore{c: newClient(base, id, secret)} }

func (s *APIStore) Close(context.Context) {}

// draftView 是平台三个写接口共同的响应体。
type draftView struct {
	CaseID         string `json:"caseId"`
	DraftJSON      string `json:"draftJson"`
	Version        int    `json:"version"`
	Status         string `json:"status"`         // tc_step —— 编辑期状态机
	CaseType       string `json:"caseType"`       //
	PlatformStatus string `json:"platformStatus"` // tc_case —— 与 status 不是一回事
	Replayed       bool   `json:"replayed"`       // 平台侧尚未落地，见 toRow 注释

	Validation struct {
		Passed        bool     `json:"passed"`
		ErrorCount    int      `json:"errorCount"`
		WarnCount     int      `json:"warnCount"`
		ViolatedCodes []string `json:"violatedCodes"`
		Findings      []struct {
			STD      string `json:"std"`
			Severity string `json:"severity"`
			Seq      int    `json:"seq"`
			Message  string `json:"message"`
		} `json:"findings"`
	} `json:"validation"`
}

// warnings 只取 WARN/INFO —— ERROR 会让平台抛 422，走不到这里。
//
// ⚠️ validation.passed 只看 ERROR：passed=true 且 warnCount>0 是正常状态，
// 不是矛盾。WARN 不拦操作，但 agent 看不到就不会去改。
func (v *draftView) warnings() []string {
	out := make([]string, 0, len(v.Validation.Findings))
	for _, f := range v.Validation.Findings {
		if f.Severity == "ERROR" {
			continue
		}
		if f.Seq > 0 {
			out = append(out, fmt.Sprintf("[%s/%s] 第 %d 步：%s", f.STD, f.Severity, f.Seq, f.Message))
			continue
		}
		out = append(out, fmt.Sprintf("[%s/%s] %s", f.STD, f.Severity, f.Message))
	}
	return out
}

func (v *draftView) toRow() (*model.CaseRow, error) {
	ct, err := model.ParseCaseType(v.CaseType)
	if err != nil {
		return nil, fmt.Errorf("平台返回了无法识别的 caseType %q: %w", v.CaseType, err)
	}
	st, err := model.ParseCaseStatus(v.Status)
	if err != nil {
		return nil, fmt.Errorf("平台返回了无法识别的 status %q: %w", v.Status, err)
	}
	ps, err := model.ParseCaseStatus(v.PlatformStatus)
	if err != nil {
		return nil, fmt.Errorf("平台返回了无法识别的 platformStatus %q: %w", v.PlatformStatus, err)
	}
	return &model.CaseRow{
		CaseID:         v.CaseID,
		CaseType:       ct,
		Status:         st,
		Version:        v.Version,
		PlatformStatus: ps,
		DraftJSON:      v.DraftJSON,
		// ⚠️ PlatformVersion 不填：DraftView 没有它，而 CLI 侧本来就只有测试读过。
		// 「tc_case.version 编辑期不动」这条不变量已搬到平台的并发测试里断言 ——
		// 让 CLI 为了自测要求平台暴露一个业务上用不到的字段，是把测试需求
		// 泄漏进 API 形状。
	}, nil
}

// ok 把 200 响应变成成功的 Result。
func (s *APIStore) ok(raw []byte) model.Result {
	var v draftView
	if err := json.Unmarshal(raw, &v); err != nil {
		return model.Fail(model.InfraError, "平台返回的响应解析失败："+err.Error())
	}
	row, err := v.toRow()
	if err != nil {
		return model.Fail(model.InfraError, err.Error())
	}
	r := model.Result{Code: model.OK, Row: row, Replayed: v.Replayed, Violations: v.warnings()}
	if v.Replayed {
		r.Message = "幂等重放：该操作此前已成功"
	}
	return r
}

// call 发请求并映射。第二个返回值是 RFC 7807 的 problem type slug ——
// 调用方靠它区分同样映射成 13 的两种情况：409 state-conflict（状态冲突，
// 可能其实是重放）与 403（scope 不够，跟状态无关，不该去探测）。
func (s *APIStore) call(ctx context.Context, method, path string, in any) (model.Result, string) {
	status, raw, err := s.c.do(ctx, method, path, in)
	if err != nil {
		return model.Fail(model.InfraError, err.Error()), ""
	}
	if status == http.StatusOK {
		return s.ok(raw), ""
	}
	var p problem
	_ = json.Unmarshal(raw, &p)
	return toResult(status, raw), p.kind()
}

// diagnoseStateConflict 把"响应丢失后的重试"从真正的状态冲突里分出来。
//
// ⭐ 这是整套幂等的第一条规则：【重放在语义上是成功，必须返回 0】。
// 返回非 0 会让 agent 以为没成功 —— 要么无限重试，要么放弃已经完成的工作。
//
// 场景：agent 提交成功，响应在网络上丢了，它用同一个 version 重试。
// 平台此刻看到 status 已离开编写态，抛的是状态冲突（409 state-conflict），
// 而正确的答案是"你那次已经成功了"。
//
// 判据跟 pgx 实现里的 diagnoseMiss 完全一致：
// 版本恰好前进一格 + 状态离开编写态 = 上一次用这个 version 的提交成功了。
// 换成"别人改过、状态早就变了"的话，版本不会正好是 expected+1。
//
// ⚠️ 这是【临时补偿】。平台侧正在给 DraftView 加 replayed 字段，
// 落地之后这个函数连同这次多余的 GET 一起删掉 ——
// 重放判定属于写入方，客户端不该为了还原它多打一次请求。
func (s *APIStore) diagnoseStateConflict(ctx context.Context, caseID string,
	expectedVersion int, conflict model.Result) model.Result {
	probe := s.Show(ctx, caseID)
	if probe.Code != model.OK || probe.Row == nil {
		return conflict // 读不回来就维持原判，不猜
	}
	if probe.Row.Version == expectedVersion+1 && probe.Row.Status != model.StatusAIDraft {
		return model.Replayed(probe.Row)
	}
	return conflict
}

func (s *APIStore) Draft(ctx context.Context, caseID string, ct model.CaseType,
	title, createdBy string) model.Result {
	r, _ := s.call(ctx, http.MethodPost, "/api/cases/draft", map[string]string{
		"caseId": caseID, "title": title,
		"caseType": ct.String(), "createdBy": createdBy,
	})
	return r
}

func (s *APIStore) Update(ctx context.Context, caseID string, expectedVersion int,
	draftJSON string) model.Result {
	r, kind := s.call(ctx, http.MethodPut, "/api/cases/"+caseID+"/draft", map[string]any{
		"draftJson": draftJSON, "version": expectedVersion,
	})
	if kind == kindStateConflict {
		return s.diagnoseStateConflict(ctx, caseID, expectedVersion, r)
	}
	return r
}

func (s *APIStore) Commit(ctx context.Context, caseID string, expectedVersion int) model.Result {
	// ⚠️ 只带 version，不带内容 —— 提交的是库里那份用户已经确认过的快照。
	// 允许带内容的话，「确认的」和「提交的」就可能不是同一份东西。
	r, kind := s.call(ctx, http.MethodPost, "/api/cases/"+caseID+"/commit", map[string]any{
		"version": expectedVersion,
	})
	if kind == kindStateConflict {
		return s.diagnoseStateConflict(ctx, caseID, expectedVersion, r)
	}
	return r
}

func (s *APIStore) Show(ctx context.Context, caseID string) model.Result {
	r, _ := s.call(ctx, http.MethodGet, "/api/cases/"+caseID+"/draft", nil)
	return r
}

func (s *APIStore) ListModules(ctx context.Context) ([]model.ModuleEntry, error) {
	status, raw, err := s.c.do(ctx, http.MethodGet, "/api/modules", nil)
	if err != nil {
		return nil, err
	}
	if status != http.StatusOK {
		r := toResult(status, raw)
		return nil, fmt.Errorf("%s", r.Message)
	}
	var out []model.ModuleEntry
	if err := json.Unmarshal(raw, &out); err != nil {
		return nil, fmt.Errorf("模块字典解析失败: %w", err)
	}
	return out, nil
}
