// Package caseref 把「人给的案例标识」解析成 caseId。
//
// ⭐ 存在的理由：**工具的参数应该是用户语言里存在的东西，不是数据库主键。**
//
// 测试人员在 ATP 界面上看到的是 ATP-CART-0014，caseId 那个 UUID 他们
// 没有任何渠道拿到。之前 `atp run <caseId>` 对 agent 能用 —— 因为它刚
// draft 完、主键在手；对人则是从第一步就卡住。
//
// 两种标识都收，靠【形状】区分，不加 --by-code 之类的 flag：
// 加 flag 等于把「我该用哪个」这个问题推给调用方，
// 而调用方手上只有一个，它自己知道那是什么。
package caseref

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"regexp"
	"strings"
)

// Doer 只要求「能发一个请求」—— httpx.Client 满足它。
//
// 用接口而不是直接依赖 httpx，是为了让这段解析逻辑只存在一份：
// 写路径（apistore）和执行路径（inspect）都要用，而它们是两个包。
// 上一个 bug 就是同一段能力长在两个客户端里、只给其中一个加了鉴权。
type Doer interface {
	Do(ctx context.Context, method, path string, in any) (int, []byte, error)
}

// codePattern 案例编号的形状，与 schema 里的 pattern 一致。
//
// ⚠️ 大小写不敏感：用户手敲的编号可能是小写，而 STD-007 规定的形状是全大写。
// 为这个让人重敲一遍没道理 —— 平台侧的查询接口也是大小写不敏感的。
var codePattern = regexp.MustCompile(`(?i)^ATP-[A-Z]+-[0-9]{4}$`)

// IsCode 判断这个标识是不是案例编号。
func IsCode(ref string) bool { return codePattern.MatchString(strings.TrimSpace(ref)) }

// Resolve 把标识解析成 caseId。
//
// 不是编号就原样返回 —— 不去猜它是不是合法 UUID：
// 猜错了报的是"格式不对"，而真正的原因可能是案例不存在，
// 那会把人指向错误的方向。让平台去判，它返回 404 我们映射成 11。
func Resolve(ctx context.Context, d Doer, ref string) (string, error) {
	ref = strings.TrimSpace(ref)
	if !IsCode(ref) {
		return ref, nil
	}

	status, raw, err := d.Do(ctx, http.MethodGet, "/api/cases/by-code/"+ref, nil)
	if err != nil {
		return "", err
	}
	switch status {
	case http.StatusOK:
		var v struct {
			CaseID string `json:"caseId"`
		}
		if err := json.Unmarshal(raw, &v); err != nil || v.CaseID == "" {
			return "", fmt.Errorf("平台按编号查到了案例，但响应里没有 caseId：%s", trunc(string(raw)))
		}
		return v.CaseID, nil
	case http.StatusNotFound:
		// ⭐ 与 caseId 查不到用同一个码。对调用方来说「你给的标识找不到案例」
		// 是同一件事，不该因为标识的种类不同而分成两个码 ——
		// 这跟 400/422 都归 12 是同一条判据：不会让它做出不同的事，就不该分码。
		return "", &NotFoundError{Code: ref}
	}
	return "", fmt.Errorf("按编号查案例失败（HTTP %d）：%s", status, trunc(string(raw)))
}

// NotFoundError 编号不存在。调用方据此映射成退出码 11。
type NotFoundError struct{ Code string }

func (e *NotFoundError) Error() string {
	return "案例编号不存在：" + e.Code
}

func trunc(s string) string {
	if len(s) <= 200 {
		return s
	}
	return s[:200] + "…"
}
