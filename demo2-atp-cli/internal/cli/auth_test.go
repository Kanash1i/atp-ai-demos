package cli_test

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/config"
)

// 这一组补的是一个真实漏掉的 bug。
//
// 迁移之前 inspect/run 有自己的裸 HTTP 客户端；迁移时我只给新写的 apistore
// 加了鉴权。平台把 atp.auth.enabled 打开之后，inspect 拿裸请求打过去、
// 收到 401 就报错退出 —— 换 token 那段代码根本没执行到，因为它不在那个客户端里。
//
// ⭐ 而当时 50 个用例全绿：鉴权关着时不带 token 也返回 200，
// 「401 → 换 token → 重试」这条分支【一次都没被触发过】。
// 覆盖看起来有，但触发它的前提（服务端要求鉴权）在测试环境里不成立。
//
// 所以这些桩【强制要求 token】。没有它，同样的 bug 会再漏一次。

// authStub 一个要求 Bearer token 的平台。
type authStub struct {
	logins  atomic.Int32
	noToken atomic.Int32 // 收到不带 token 的业务请求次数 —— 必须为 0
}

func (a *authStub) serve(t *testing.T, path string, body any) string {
	t.Helper()
	const token = "tok-abc"
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/auth/token" {
			a.logins.Add(1)
			var in map[string]string
			_ = json.NewDecoder(r.Body).Decode(&in)
			if in["clientId"] == "" || in["clientSecret"] == "" {
				w.WriteHeader(401)
				return
			}
			_ = json.NewEncoder(w).Encode(map[string]any{
				"token": token, "expiresIn": 2592000,
				"scopes": []string{"case:write", "exec:run-once", "inspect"},
			})
			return
		}
		if r.Header.Get("Authorization") != "Bearer "+token {
			a.noToken.Add(1)
			w.WriteHeader(401)
			_ = json.NewEncoder(w).Encode(map[string]any{
				"type": "about:blank", "status": 401,
				"detail": "缺少或无效的 Authorization: Bearer <token>",
			})
			return
		}
		if r.URL.Path != path {
			w.WriteHeader(404)
			return
		}
		_ = json.NewEncoder(w).Encode(body)
	}))
	t.Cleanup(srv.Close)
	return srv.URL
}

func withCreds(t *testing.T, base string) {
	t.Helper()
	t.Setenv(config.EnvAPIURL, base)
	t.Setenv(config.EnvClientID, "atp-cli")
	t.Setenv(config.EnvClientSecret, "s3cret")
}

// ⭐ inspect 必须换 token 再打业务接口。
func TestAuth_InspectAuthenticates(t *testing.T) {
	a := &authStub{}
	base := a.serve(t, "/api/inspect/page", map[string]any{
		"code": "OK", "httpStatus": 200, "url": "http://x/products/p001",
		"candidates": []any{},
	})
	withCreds(t, base)

	r := run("inspect", "--json", "/products/p001")
	if r.code != 0 {
		t.Fatalf("鉴权开着时 inspect 应当成功，实际 %d: %s%s", r.code, r.out, r.err)
	}
	if a.logins.Load() == 0 {
		t.Fatal("一次 token 都没换 —— 说明 inspect 走的不是带鉴权的客户端")
	}
	if n := a.noToken.Load(); n != 0 {
		t.Fatalf("有 %d 次业务请求没带 token；应当先换 token 再打", n)
	}
}

// ⭐ run 同理。它和 inspect 用同一个客户端，但要各自钉住 ——
// 曾经就是"其中一个有、另一个没有"。
func TestAuth_RunAuthenticates(t *testing.T) {
	a := &authStub{}
	base := a.serve(t, "/api/executions/run-once", map[string]any{
		"caseId": "c1", "status": "PASSED", "terminal": true, "durationMs": 479,
	})
	withCreds(t, base)

	r := run("run", "--json", "c1")
	if r.code != 0 {
		t.Fatalf("鉴权开着时 run 应当成功，实际 %d: %s%s", r.code, r.out, r.err)
	}
	if a.logins.Load() == 0 {
		t.Fatal("一次 token 都没换 —— 说明 run 走的不是带鉴权的客户端")
	}
	if n := a.noToken.Load(); n != 0 {
		t.Fatalf("有 %d 次业务请求没带 token", n)
	}
}

// ⭐ 写路径同样要认证。这条本来是绿的（apistore 一直有鉴权），
// 留着是为了让"所有打平台的命令都要认证"这句话在测试里是完整的，
// 而不是"我记得写路径有"。
func TestAuth_WritePathAuthenticates(t *testing.T) {
	a := &authStub{}
	base := a.serve(t, "/api/cases/draft", map[string]any{
		"caseId": "c1", "draftJson": "{}", "version": 0,
		"status": "AI_DRAFT", "caseType": "PC_WEB", "platformStatus": "AI_DRAFT",
	})
	withCreds(t, base)

	r := run("draft", "--json", "--id", "c1", "-p", "PC_WEB", "-t", "标题")
	if r.code != 0 {
		t.Fatalf("鉴权开着时 draft 应当成功，实际 %d: %s%s", r.code, r.out, r.err)
	}
	if n := a.noToken.Load(); n != 0 {
		t.Fatalf("有 %d 次业务请求没带 token", n)
	}
}

// 没配凭证时，401 要报得能让人知道该去配什么 ——
// 只说"HTTP 401"的话，人会去查平台是不是挂了。
func TestAuth_MissingCredentialsSaysWhat(t *testing.T) {
	a := &authStub{}
	base := a.serve(t, "/api/inspect/page", map[string]any{})
	t.Setenv(config.EnvAPIURL, base)
	t.Setenv(config.EnvClientID, "")
	t.Setenv(config.EnvClientSecret, "")

	r := run("inspect", "--json", "/products/p001")
	if r.code != 20 {
		t.Fatalf("没凭证又被要求鉴权，应报 20，实际 %d", r.code)
	}
	if !strings.Contains(r.out+r.err, "401") {
		t.Fatalf("错误信息要带上 401，否则人不知道是鉴权问题：%s%s", r.out, r.err)
	}
}
