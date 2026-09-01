// Package httpx 是【唯一】的平台 HTTP 客户端 —— 所有打平台的请求都走它。
//
// ⚠️ 这个包是从一次真实的 bug 里长出来的：迁移之前 inspect/run 有自己的
// 裸客户端，迁移时我只给新写的 apistore 加了鉴权。平台把 auth 打开之后，
// inspect 直接拿裸请求打过去、收到 401 就报错退出 —— 换 token 那段代码
// 根本没执行到，因为它不在这个客户端里。
//
// ⭐ 而 50 个用例全绿：鉴权关着时不带 token 也返回 200，
// 「401 → 换 token → 重试」这条分支【一次都没被触发过】。
// 覆盖看起来有，但触发它的前提在测试环境里不成立。
//
// 所以只留一个客户端。两个客户端就会有一个先长出能力、另一个落下。
package httpx

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"
)

// Client 一次 CLI 调用的 HTTP 会话。
//
// ⚠️ 刻意不做 token 落盘缓存：CLI 进程只活几百毫秒，一次调用最多换一次 token。
// 落盘要处理并发写、过期判断、以及"缓存文件里的 token 属于哪个 clientId"，
// 而省下的是一次几十毫秒的请求。这笔账不划算，而且落盘的 token 本身是新的泄漏面。
type Client struct {
	Base   string
	ID     string
	Secret string
	HTTP   *http.Client

	token string // 本进程内缓存，够用
}

func New(base, id, secret string) *Client {
	return &Client{Base: base, ID: id, Secret: secret,
		HTTP: &http.Client{Timeout: 30 * time.Second}}
}

// login 换 token。
func (c *Client) login(ctx context.Context) error {
	body, _ := json.Marshal(map[string]string{"clientId": c.ID, "clientSecret": c.Secret})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		c.Base+"/api/auth/token", bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := c.HTTP.Do(req)
	if err != nil {
		return fmt.Errorf("调不通平台换 token 接口 %s: %w", c.Base, err)
	}
	defer resp.Body.Close()

	raw, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("换 token 失败（HTTP %d）：%s", resp.StatusCode, Truncate(string(raw), 200))
	}
	var out struct {
		Token string `json:"token"`
	}
	if err := json.Unmarshal(raw, &out); err != nil || out.Token == "" {
		return fmt.Errorf("换 token 的响应里没有 token：%s", Truncate(string(raw), 200))
	}
	c.token = out.Token
	return nil
}

// do 发一个请求。401 时重换一次 token 再重试。
//
// ⭐ 只重试一次，不循环 —— 换完 token 仍然 401 说明凭证本身不对（id/secret 错、
// 或被吊销），再试一百次也一样，而循环会把"配置写错了"变成一次挂起。
func (c *Client) Do(ctx context.Context, method, path string, in any) (int, []byte, error) {
	send := func() (*http.Response, error) {
		var rdr io.Reader
		if in != nil {
			b, err := json.Marshal(in)
			if err != nil {
				return nil, err
			}
			rdr = bytes.NewReader(b)
		}
		req, err := http.NewRequestWithContext(ctx, method, c.Base+path, rdr)
		if err != nil {
			return nil, err
		}
		if in != nil {
			req.Header.Set("Content-Type", "application/json")
		}
		if c.token != "" {
			req.Header.Set("Authorization", "Bearer "+c.token)
		}
		return c.HTTP.Do(req)
	}

	// 还没有 token 且配了凭证 —— 先换一次，省掉一轮必然的 401。
	// 平台的 atp.auth.enabled 关着时不配凭证也能跑，所以这里不强制要求。
	if c.token == "" && c.ID != "" {
		if err := c.login(ctx); err != nil {
			return 0, nil, err
		}
	}

	resp, err := send()
	if err != nil {
		return 0, nil, fmt.Errorf("调不通平台 %s: %w", c.Base, err)
	}
	raw, _ := io.ReadAll(resp.Body)
	resp.Body.Close()

	if resp.StatusCode == http.StatusUnauthorized && c.ID != "" {
		if err := c.login(ctx); err != nil {
			return 0, nil, err
		}
		resp2, err := send()
		if err != nil {
			return 0, nil, fmt.Errorf("换 token 后重试仍调不通平台 %s: %w", c.Base, err)
		}
		raw, _ = io.ReadAll(resp2.Body)
		resp2.Body.Close()
		return resp2.StatusCode, raw, nil
	}
	return resp.StatusCode, raw, nil
}

// Truncate 截断长响应体 —— 报错信息里贴一整页 HTML 对谁都没用。
func Truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "…"
}
