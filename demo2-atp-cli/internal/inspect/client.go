// Package inspect 调平台的页面探查接口。
//
// ⭐ 这是 CLI 第一个【不碰数据库】的命令：它只需要 ATP_API_URL，
// 不需要任何数据库凭证。也是「CLI 改调平台 API」那步风险最小的试水 ——
// 探查是只读的，不需要鉴权，做错了也不会写坏数据。
package inspect

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"
)

// Candidate 一个候选定位器。
//
// ⚠️ 平台侧保证 LocatorValue 是【规范允许的写法】：优先 data-testid，
// 其次 name / 稳定 id，绝不产出绝对路径 XPath（STD-001 是 ERROR），
// 带随机后缀的 id 直接跳过（STD-002）。
// agent 照抄就能过校验 —— 这是这个工具有用的前提。
type Candidate struct {
	Kind         string `json:"kind"` // testid / button / link / input / heading
	LocatorType  string `json:"locatorType"`
	LocatorValue string `json:"locatorValue"`
	Text         string `json:"text"`
	Note         string `json:"note"`
}

// Page 探查结果。
type Page struct {
	OK         bool        `json:"ok"`
	Code       string      `json:"code"` // OK / NOT_FOUND / INFRA_ERROR
	HTTPStatus int         `json:"httpStatus"`
	URL        string      `json:"url"`
	Title      string      `json:"title"`
	Candidates []Candidate `json:"candidates"`
	Message    string      `json:"message"`
}

// Result 带上平台返回的 HTTP 状态 —— 退出码的分派依据是它，不是 body。
type Result struct {
	Status int
	Page   Page
}

type Client struct {
	Base string
	HTTP *http.Client
}

func New(base string) *Client {
	// 探查要真的打开页面渲染，比一般接口慢。30s 足够，又不至于让 agent 干等。
	return &Client{Base: base, HTTP: &http.Client{Timeout: 30 * time.Second}}
}

// Inspect 把路径【原样透传】给平台。
//
// 三种写法平台都处理：/products/p001、http://host:8088/products/p001、
// ${base_url}/products/p001（案例原文写法，agent 多半直接从步骤里贴过来）。
// CLI 不做任何解析或改写 —— 解析规则只应存在于一个地方。
func (c *Client) Inspect(ctx context.Context, path string) (*Result, error) {
	body, err := json.Marshal(map[string]string{"path": path})
	if err != nil {
		return nil, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		c.Base+"/api/inspect/page", bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := c.HTTP.Do(req)
	if err != nil {
		return nil, fmt.Errorf("调不通平台探查接口 %s: %w", c.Base, err)
	}
	defer resp.Body.Close()

	raw, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return nil, err
	}
	var page Page
	if err := json.Unmarshal(raw, &page); err != nil {
		// 平台没按契约返回 JSON —— 这是环境问题，不是 agent 写错了
		return nil, fmt.Errorf("平台返回的不是合法 JSON（HTTP %d）: %s", resp.StatusCode, truncate(string(raw), 200))
	}
	return &Result{Status: resp.StatusCode, Page: page}, nil
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "…"
}
