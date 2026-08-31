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

// stubPlatform 桩掉平台的探查接口 —— 测试不依赖平台真的在跑。
//
// 返回 handler 收到的 path，用来断言 CLI【原样透传】没做改写。
func stubPlatform(t *testing.T, status int, body string) (base string, got *string) {
	t.Helper()
	var received string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/inspect/page" {
			t.Errorf("打错了路径: %s", r.URL.Path)
		}
		raw, _ := io.ReadAll(r.Body)
		var req struct {
			Path string `json:"path"`
		}
		json.Unmarshal(raw, &req)
		received = req.Path
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		io.WriteString(w, body)
	}))
	t.Cleanup(srv.Close)
	t.Setenv(config.EnvAPIURL, srv.URL)
	return srv.URL, &received
}

const okBody = `{"ok":true,"code":"OK","httpStatus":200,
 "url":"http://localhost:8088/products/p001","title":"商品詳細 · ATP Shop",
 "candidates":[
   {"kind":"testid","locatorType":"XPATH","locatorValue":"//button[@data-testid='add-to-cart']","text":"カートに入れる","note":""},
   {"kind":"link","locatorType":"XPATH","locatorValue":"//a[@data-testid='nav-cart']","text":"カート 0","note":"/cart"}]}`

func TestInspect_OK(t *testing.T) {
	_, _ = stubPlatform(t, 200, okBody)

	r := run("inspect", "--json", "/products/p001")
	if r.code != 0 {
		t.Fatalf("应成功，实际 %d: %s%s", r.code, r.out, r.err)
	}
	d, ok := r.json(t)["data"].(map[string]any)
	if !ok {
		t.Fatalf("信封里没有 data: %s", r.out)
	}
	if d["title"] != "商品詳細 · ATP Shop" {
		t.Errorf("title 没带回来: %v", d["title"])
	}
	cs, _ := d["candidates"].([]any)
	if len(cs) != 2 {
		t.Fatalf("应有 2 个候选，实际 %d", len(cs))
	}
	first, _ := cs[0].(map[string]any)
	if first["locatorValue"] != "//button[@data-testid='add-to-cart']" {
		t.Errorf("locatorValue 没原样带回: %v", first["locatorValue"])
	}
}

// ⭐ CLI 必须【原样透传】路径，不做任何解析或改写 ——
// 解析规则（相对路径 / 完整 URL / ${base_url}）只应存在于平台一处。
func TestInspect_PassesPathThroughVerbatim(t *testing.T) {
	for _, path := range []string{
		"/products/p001",
		"http://localhost:8088/products/p001",
		"${base_url}/products/p001", // 案例原文写法，agent 多半直接从步骤里贴过来
	} {
		t.Run(path, func(t *testing.T) {
			_, got := stubPlatform(t, 200, okBody)
			if r := run("inspect", "--json", path); r.code != 0 {
				t.Fatalf("应成功，实际 %d", r.code)
			}
			if *got != path {
				t.Fatalf("路径被改写了\n传入: %q\n平台收到: %q", path, *got)
			}
		})
	}
}

// ⭐ 12 与 20 必须分开：都返回"探查失败"的话，
// agent 分不清是自己查错了还是环境坏了，大概率退回编造 ——
// 而编造正是这个工具要消灭的东西。
func TestInspect_NotFoundIsTwelve(t *testing.T) {
	_, _ = stubPlatform(t, 404,
		`{"ok":false,"code":"NOT_FOUND","httpStatus":404,"url":"http://localhost:8088/product/p001"}`)

	r := run("inspect", "--json", "/product/p001")

	if r.code != 12 {
		t.Fatalf("查错路径应是 12（你自己能改），实际 %d", r.code)
	}
	vs, _ := r.json(t)["violations"].([]any)
	if len(vs) == 0 {
		t.Fatal("404 要把原因放进 violations —— agent 靠它知道该改什么")
	}
}

func TestInspect_PlatformDownIsTwenty(t *testing.T) {
	_, _ = stubPlatform(t, 503, `{"ok":false,"code":"INFRA_ERROR","message":"浏览器起不来"}`)

	r := run("inspect", "--json", "/products/p001")

	if r.code != 20 {
		t.Fatalf("环境坏了应是 20（别改案例），实际 %d", r.code)
	}
	if vs, _ := r.json(t)["violations"].([]any); len(vs) != 0 {
		t.Error("503 不是 agent 的错，不该往 violations 里塞东西")
	}
	if msg, _ := r.json(t)["message"].(string); !strings.Contains(msg, "浏览器") {
		t.Errorf("应把平台的 message 透出来，实际 %q", msg)
	}
}

// 连不上平台也是 20 —— 不能让 agent 以为是自己写错了
func TestInspect_UnreachablePlatformIsTwenty(t *testing.T) {
	t.Setenv(config.EnvAPIURL, "http://127.0.0.1:1")
	if r := run("inspect", "--json", "/products/p001"); r.code != 20 {
		t.Fatalf("连不上应是 20，实际 %d", r.code)
	}
}

// ⭐ inspect 不碰数据库 —— 没有任何 DB 凭证也必须能跑。
// 这是「agent 那一层不该看到 DB 密码」这个方向上第一个真正做到的命令。
func TestInspect_NeedsNoDatabaseCredentials(t *testing.T) {
	_, _ = stubPlatform(t, 200, okBody)
	t.Setenv(config.EnvDBURL, "")
	t.Setenv(config.EnvDBUser, "")
	t.Setenv(config.EnvDBPassword, "")

	if r := run("inspect", "--json", "/products/p001"); r.code != 0 {
		t.Fatalf("没有 DB 凭证也该能探查，实际 %d: %s%s", r.code, r.out, r.err)
	}
}
