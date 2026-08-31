// Package apistore 是 Backend 的 HTTP 实现 —— 走平台 API，不碰数据库。
//
// ⭐ 它存在的理由是【凭证边界】：CLI 只持一个 scope 到"写案例"的 token，
// 数据库账号密码只留在平台那一侧。不是靠约束 agent 别碰库，是它根本拿不到。
package apistore

import (
	"encoding/json"
	"fmt"
	"strings"

	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/model"
)

// problem 是 RFC 7807 的响应体。平台统一用 ProblemDetail 出错，
// 所以这一个结构能解所有错误响应。
//
// ⚠️ Type 是【标识符不是地址】—— RFC 7807 明确允许它不可解引用，别去请求它。
type problem struct {
	Type   string `json:"type"`
	Title  string `json:"title"`
	Status int    `json:"status"`
	Detail string `json:"detail"`

	// 422 校验失败时带的明细
	ViolatedCodes []string `json:"violatedCodes"`
	Findings      []struct {
		STD      string `json:"std"`
		Severity string `json:"severity"`
		Seq      int    `json:"seq"`
		Message  string `json:"message"`
	} `json:"findings"`
	MissingFields []string `json:"missingFields"`
}

// 平台承诺稳定的 problem type slug。前缀不变，slug 是契约面。
const problemPrefix = "https://atp.example/problems/"

const (
	kindVersionConflict  = "version-conflict"
	kindStateConflict    = "state-conflict"
	kindValidationFailed = "validation-failed"
	kindHeaderIncomplete = "header-incomplete"
)

func (p *problem) kind() string { return strings.TrimPrefix(p.Type, problemPrefix) }

// violations 把 findings 压成人和 agent 都能读的一行一条。
func (p *problem) violations() []string {
	out := make([]string, 0, len(p.Findings)+len(p.MissingFields))
	for _, f := range p.Findings {
		if f.Seq > 0 {
			out = append(out, fmt.Sprintf("[%s/%s] 第 %d 步：%s", f.STD, f.Severity, f.Seq, f.Message))
			continue
		}
		out = append(out, fmt.Sprintf("[%s/%s] %s", f.STD, f.Severity, f.Message))
	}
	for _, m := range p.MissingFields {
		out = append(out, "表头缺必填字段："+m)
	}
	return out
}

// toResult 把一个错误响应映射成退出码。
//
// ⭐ 这张表就是【agent 的调度 API】—— 每个码对应一个不同的下一步动作。
// 判断只看 status 和 type，【绝不解析 detail 文案】：detail 是给人看的，
// 平台改一次措辞就会让文本匹配静默失效，而测试还是绿的。
func toResult(status int, body []byte) model.Result {
	var p problem
	if err := json.Unmarshal(body, &p); err != nil {
		return model.Fail(model.InfraError,
			fmt.Sprintf("平台返回了无法解析的响应（HTTP %d）：%s", status, truncate(string(body), 200)))
	}
	msg := p.Detail
	if msg == "" {
		msg = p.Title
	}

	switch status {
	case 404:
		return model.Fail(model.NotFound, msg)

	case 409:
		// ⭐ 10 和 13 绝不合并 —— 下一步动作完全相反：
		// 前者"重新 show/preview 再确认一次"，后者"停下问人，重试没用"。
		switch p.kind() {
		case kindStateConflict:
			return model.Fail(model.StateConflict, msg)
		case kindVersionConflict:
			return model.Fail(model.VersionConflict, msg)
		}
		// 没有 type 就无法区分。宁可报 20 让人来看，也不要猜一个 —— 猜错的那半
		// 会让 agent 对着一个"重试也没用"的状态无限重来。
		return model.Fail(model.InfraError,
			"平台返回 409 但没给可识别的 problem type，无法区分版本冲突与状态冲突："+msg)

	case 422, 400:
		// 400（JSON 不合法 / 内容为空）与 422（规范未通过）对 agent 是同一个动作：
		// 读错误、改内容、重发。分成两个码不会让它做出不同的事。
		return model.Invalid(model.ValidationFailed, msg, p.violations())

	case 401:
		return model.Fail(model.InfraError, msg)

	case 403:
		// scope 不够。换 token 也没用 —— 这是"当前状态不允许该操作"，停下问人。
		return model.Fail(model.StateConflict, msg)
	}

	return model.Fail(model.InfraError, fmt.Sprintf("平台返回 HTTP %d：%s", status, msg))
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "…"
}
