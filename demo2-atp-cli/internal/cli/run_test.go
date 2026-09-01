package cli_test

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/config"
)

// 下面三个响应体是平台侧实跑出来的原文，不是我编的。
const (
	runPassed = `{"terminal":true,"runCode":"RUN-20260831-0015","taskId":"725463ca-0000-0000-0000-000000000000",
 "status":"PASSED","durationMs":301,"failedSeq":null,"errorMsg":null,
 "videoUrl":"/api/artifacts/RUN-20260831-0015/ATP-SEARCH-0012-725463ca/02be68bc.webm","note":null}`

	runFailed = `{"terminal":true,"runCode":"RUN-20260831-0016","taskId":"2b1a26be-0000-0000-0000-000000000000",
 "status":"FAILED","durationMs":11191,"failedSeq":2,
 "errorMsg":"TimeoutError: Timeout 10000ms exceeded.\n=========================== logs ===========================\nwaiting for locator(...)\n",
 "videoUrl":"/api/artifacts/RUN-20260831-0016/x/y.webm","note":null}`

	runNoNode = `{"terminal":false,"runCode":"RUN-20260831-0017","taskId":null,
 "status":"TIMEOUT","durationMs":null,"failedSeq":null,"errorMsg":null,
 "videoUrl":null,"note":"任务仍在 PENDING 状态"}`
)

func stubRun(t *testing.T, status int, body string) *map[string]any {
	t.Helper()
	var received map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// 鉴权：桩也要能发 token —— CLI 现在所有打平台的请求都先换 token。
		// （auth_test.go 里有专门要求 token 的桩，这里只是让老用例继续能跑。）
		if r.URL.Path == "/api/auth/token" {
			w.Write([]byte(`{"token":"t","expiresIn":2592000}`))
			return
		}
		if r.URL.Path != "/api/executions/run-once" {
			t.Errorf("打错了路径: %s", r.URL.Path)
		}
		raw, _ := io.ReadAll(r.Body)
		json.Unmarshal(raw, &received)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		io.WriteString(w, body)
	}))
	t.Cleanup(srv.Close)
	t.Setenv(config.EnvAPIURL, srv.URL)
	return &received
}

func TestRun_PassedIsZero(t *testing.T) {
	stubRun(t, 200, runPassed)

	r := run("run", "--json", "case-1")

	if r.code != 0 {
		t.Fatalf("PASSED 应是 0，实际 %d: %s%s", r.code, r.out, r.err)
	}
	d, _ := r.json(t)["data"].(map[string]any)
	if d["status"] != "PASSED" || d["terminal"] != true {
		t.Fatalf("data 没原样带回: %v", d)
	}
}

// ⭐ 案例 FAILED 也是 200 / 退出码 0 —— 跑挂是一个【有效结论】，不是错误。
//
// 这次调用成功地告诉了 agent「案例跑挂了」。用非 0 会诱导 agent 去"修正"
// 一份可能本来就对的案例（被测系统真有 bug 的情况）。
func TestRun_FailedIsAlsoZero(t *testing.T) {
	stubRun(t, 200, runFailed)

	r := run("run", "--json", "case-1")

	if r.code != 0 {
		t.Fatalf("案例 FAILED 仍应是退出码 0（拿到结论了），实际 %d", r.code)
	}
	d, _ := r.json(t)["data"].(map[string]any)
	if d["status"] != "FAILED" {
		t.Fatalf("status 应是 FAILED，实际 %v", d["status"])
	}
	if d["failedSeq"] != float64(2) {
		t.Errorf("failedSeq 应是 2，实际 %v", d["failedSeq"])
	}
	// ⚠️ errorMsg 里有换行和引号（Playwright 堆栈），JSON 转义不能弄坏
	em, _ := d["errorMsg"].(string)
	if !strings.Contains(em, "TimeoutError") || !strings.Contains(em, "\n") {
		t.Errorf("errorMsg 的换行被弄坏了: %q", em)
	}
}

// ⭐ 拿不到结论才是 20 —— 没有执行机认领属于环境问题，不是案例的问题。
func TestRun_NoNodeIsTwenty(t *testing.T) {
	stubRun(t, 504, runNoNode)

	r := run("run", "--json", "case-1")

	if r.code != 20 {
		t.Fatalf("拿不到结论应是 20，实际 %d", r.code)
	}
	msg, _ := r.json(t)["message"].(string)
	if !strings.Contains(msg, "PENDING") {
		t.Errorf("应把平台的 note 透出来，实际 %q", msg)
	}
	if !strings.Contains(msg, "别改案例") {
		t.Error("20 的提示要明确告诉 agent 别去改案例 —— 否则它会开始改一份没问题的案例")
	}
	if vs, _ := r.json(t)["violations"].([]any); len(vs) != 0 {
		t.Error("环境问题不该往 violations 里塞东西")
	}
}

// timeoutSec 原样透传，CLI 不做上限裁剪 —— 上限规则只放在平台一处
func TestRun_PassesTimeoutThrough(t *testing.T) {
	got := stubRun(t, 200, runPassed)
	if r := run("run", "--json", "--timeout", "90", "case-1"); r.code != 0 {
		t.Fatalf("应成功，实际 %d", r.code)
	}
	if (*got)["timeoutSec"] != float64(90) {
		t.Fatalf("timeoutSec 应原样透传 90，平台收到 %v", (*got)["timeoutSec"])
	}
	if (*got)["caseId"] != "case-1" {
		t.Fatalf("caseId 应原样透传，平台收到 %v", (*got)["caseId"])
	}
}

// 人类可读模式：FAILED 时必须提醒"跑挂 ≠ 案例写错了"
func TestRun_HumanOutputWarnsAgainstBlindFixing(t *testing.T) {
	stubRun(t, 200, runFailed)

	r := run("run", "case-1")

	if r.code != 0 {
		t.Fatalf("应是 0，实际 %d", r.code)
	}
	for _, want := range []string{"FAILED", "第 2 步", "TimeoutError", "录像", "削弱断言"} {
		if !strings.Contains(r.out, want) {
			t.Errorf("人类可读输出里应有 %q\n实际:\n%s", want, r.out)
		}
	}
	// Playwright 堆栈很长，人只看得下第一行 —— 不该整坨糊上去
	if strings.Contains(r.out, "=========================== logs") {
		t.Error("errorMsg 只该显示第一行，不该把整个堆栈打出来")
	}
}

// ⭐ run 不碰数据库。见 inspect_test.go 里那条同名断言的注释：
// 假值设成"能看起来合法"而不是空串，挡的是"直连被加回来"。
func TestRun_NeverReadsDatabaseCredentials(t *testing.T) {
	stubRun(t, 200, runPassed)
	t.Setenv("ATP_DB_URL", "postgres://nobody:nothing@127.0.0.1:1/atp")
	t.Setenv("ATP_DB_USER", "nobody")
	t.Setenv("ATP_DB_PASSWORD", "nothing")

	if r := run("run", "--json", "case-1"); r.code != 0 {
		t.Fatalf("run 不该碰数据库，实际 %d: %s%s", r.code, r.out, r.err)
	}
}
