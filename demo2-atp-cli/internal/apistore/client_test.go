package apistore

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/model"
)

// authStub 模拟带鉴权的平台：/api/auth/token 发 token，其余接口校验 Bearer。
type authStub struct {
	logins   atomic.Int32
	calls    atomic.Int32
	accepted string // 当前有效的 token
}

func (a *authStub) handler(t *testing.T, onCall http.HandlerFunc) http.HandlerFunc {
	t.Helper()
	return func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/auth/token" {
			a.logins.Add(1)
			var body map[string]string
			_ = json.NewDecoder(r.Body).Decode(&body)
			if body["clientId"] == "" || body["clientSecret"] == "" {
				writeProblem(w, 401, "", "clientId/clientSecret 不能为空", nil)
				return
			}
			_ = json.NewEncoder(w).Encode(map[string]string{"token": a.accepted})
			return
		}
		a.calls.Add(1)
		if r.Header.Get("Authorization") != "Bearer "+a.accepted {
			writeProblem(w, 401, "", "缺少或无效的 Authorization: Bearer <token>", nil)
			return
		}
		onCall(w, r)
	}
}

func authed(t *testing.T, a *authStub, onCall http.HandlerFunc) *APIStore {
	t.Helper()
	srv := httptest.NewServer(a.handler(t, onCall))
	t.Cleanup(srv.Close)
	return New(srv.URL, "cli", "s3cret")
}

// 配了凭证就先换 token，省掉一轮必然的 401。
func TestAuth_LoginsOnceThenReusesToken(t *testing.T) {
	a := &authStub{accepted: "tok-1"}
	s := authed(t, a, func(w http.ResponseWriter, _ *http.Request) {
		okView(w, map[string]any{"caseId": "c1", "version": 1})
	})

	for i := 0; i < 3; i++ {
		if r := s.Show(context.Background(), "c1"); r.Code != model.OK {
			t.Fatalf("第 %d 次调用应当成功，实际 %s: %s", i+1, r.Code, r.Message)
		}
	}
	if got := a.logins.Load(); got != 1 {
		t.Fatalf("token 该在进程内复用，只换一次，实际换了 %d 次", got)
	}
}

// token 中途失效 → 重换一次再重试，这一次要成功。
func TestAuth_RefreshesOnceOn401(t *testing.T) {
	a := &authStub{accepted: "tok-1"}
	first := true
	s := authed(t, a, func(w http.ResponseWriter, _ *http.Request) {
		if first {
			first = false
			// 让第一次请求之后 token 失效（模拟被吊销/过期）
			a.accepted = "tok-2"
			writeProblem(w, 401, "", "token 已失效", nil)
			return
		}
		okView(w, map[string]any{"caseId": "c1", "version": 1})
	})

	if r := s.Show(context.Background(), "c1"); r.Code != model.OK {
		t.Fatalf("换过 token 之后该成功，实际 %s: %s", r.Code, r.Message)
	}
	if got := a.logins.Load(); got != 2 {
		t.Fatalf("应当换两次 token（首次 + 401 后一次），实际 %d 次", got)
	}
}

// ⭐ 只重试一次，不循环。
//
// 换完 token 仍然 401 说明凭证本身不对（id/secret 写错、或被吊销），
// 再试一百次也一样 —— 而循环会把"配置写错了"变成一次挂起，
// agent 那边看到的是超时，根本查不到真正原因。
func TestAuth_DoesNotLoopWhenCredentialsAreWrong(t *testing.T) {
	a := &authStub{accepted: "tok-never-matches"}
	srv := httptest.NewServer(func() http.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) {
			if r.URL.Path == "/api/auth/token" {
				a.logins.Add(1)
				// 发一个跟校验用的不一致的 token —— 模拟凭证根本不对
				_ = json.NewEncoder(w).Encode(map[string]string{"token": "stale"})
				return
			}
			a.calls.Add(1)
			writeProblem(w, 401, "", "缺少或无效的 Authorization", nil)
		}
	}())
	t.Cleanup(srv.Close)

	s := New(srv.URL, "cli", "wrong")
	r := s.Show(context.Background(), "c1")

	if r.Code != model.InfraError {
		t.Fatalf("持续 401 应报 20，实际 %s", r.Code)
	}
	if got := a.calls.Load(); got != 2 {
		t.Fatalf("业务请求应恰好发 2 次（首次 + 换 token 后一次），实际 %d 次 —— 多了就是在循环", got)
	}
	if got := a.logins.Load(); got != 2 {
		t.Fatalf("换 token 应恰好 2 次，实际 %d 次", got)
	}
}

// 平台的 atp.auth.enabled 关着时不配凭证也能跑 —— 过渡期要能用。
func TestAuth_WorksWithoutCredentialsWhenPlatformAuthIsOff(t *testing.T) {
	var sawAuthHeader bool
	s := stub(t, func(w http.ResponseWriter, r *http.Request) {
		sawAuthHeader = r.Header.Get("Authorization") != ""
		okView(w, map[string]any{"caseId": "c1", "version": 1})
	})
	if r := s.Show(context.Background(), "c1"); r.Code != model.OK {
		t.Fatalf("不配凭证时应当能直接调通，实际 %s: %s", r.Code, r.Message)
	}
	if sawAuthHeader {
		t.Fatal("没有 token 时不该带 Authorization 头")
	}
}

// 换 token 这一步本身失败，要报得清楚是哪一步挂了。
func TestAuth_LoginFailureIsReportedClearly(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		writeProblem(w, 401, "", "clientId 不存在", nil)
	}))
	t.Cleanup(srv.Close)

	s := New(srv.URL, "nobody", "nothing")
	r := s.Show(context.Background(), "c1")
	if r.Code != model.InfraError {
		t.Fatalf("换 token 失败应报 20，实际 %s", r.Code)
	}
	if !strings.Contains(r.Message, "换 token") {
		t.Fatalf("要说清是换 token 这一步挂了，实际：%s", r.Message)
	}
}

// 不碰数据库 —— 这是整个迁移的目的，用测试钉住。
func TestAPIStore_NeedsNoDatabaseCredentials(t *testing.T) {
	for _, k := range []string{"ATP_DB_URL", "ATP_DB_USER", "ATP_DB_PASSWORD"} {
		t.Setenv(k, "")
	}
	s := stub(t, func(w http.ResponseWriter, _ *http.Request) {
		okView(w, map[string]any{"caseId": "c1", "version": 1})
	})
	if r := s.Show(context.Background(), "c1"); r.Code != model.OK {
		t.Fatalf("没有任何数据库凭证也该跑通，实际 %s: %s", r.Code, r.Message)
	}
}
