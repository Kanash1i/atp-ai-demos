package cli_test

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"

	atpcli "github.com/Kanash1i/atp-ai-demos/atp-cli"
	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/cli"
	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/config"
	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/store"
	"github.com/google/uuid"
	"github.com/testcontainers/testcontainers-go"
	tcpostgres "github.com/testcontainers/testcontainers-go/modules/postgres"
	"github.com/testcontainers/testcontainers-go/wait"
)

var dsn string

func TestMain(m *testing.M) {
	ctx := context.Background()
	c, err := tcpostgres.Run(ctx, "postgres:17",
		tcpostgres.WithDatabase("atp"), tcpostgres.WithUsername("atp"), tcpostgres.WithPassword("atp"),
		testcontainers.WithWaitStrategy(wait.ForListeningPort("5432/tcp")))
	if err != nil {
		fmt.Fprintln(os.Stderr, "起容器失败:", err)
		os.Exit(1)
	}
	defer testcontainers.TerminateContainer(c) //nolint:errcheck

	dsn, _ = c.ConnectionString(ctx, "sslmode=disable")
	conn, err := store.Open(ctx, dsn)
	if err != nil {
		fmt.Fprintln(os.Stderr, "连不上:", err)
		os.Exit(1)
	}
	v0, v1 := atpcli.MigrationSQL()
	for _, sql := range []string{v0, v1} {
		if _, err := conn.Exec(ctx, sql); err != nil {
			fmt.Fprintln(os.Stderr, "迁移失败:", err)
			os.Exit(1)
		}
	}
	conn.Close(ctx)

	// CLI 从环境变量读配置 —— 这里直接注入，比 Java 版本靠系统属性干净
	os.Setenv(config.EnvDBURL, dsn)
	os.Setenv(config.EnvDBUser, "atp")
	os.Setenv(config.EnvDBPassword, "atp")

	os.Exit(m.Run())
}

type result struct {
	code int
	out  string
	err  string
}

func (r result) json(t *testing.T) map[string]any {
	t.Helper()
	src := r.out
	if strings.TrimSpace(src) == "" {
		src = r.err
	}
	var v map[string]any
	if err := json.Unmarshal([]byte(src), &v); err != nil {
		t.Fatalf("输出不是 JSON:\nout=%s\nerr=%s", r.out, r.err)
	}
	return v
}

func (r result) data(t *testing.T, key string) any {
	t.Helper()
	d, ok := r.json(t)["data"].(map[string]any)
	if !ok {
		t.Fatalf("信封里没有 data: %s", r.out)
	}
	return d[key]
}

func run(args ...string) result {
	var o, e bytes.Buffer
	code := cli.Execute(args, &o, &e)
	return result{code: code, out: o.String(), err: e.String()}
}

func draftFile(t *testing.T, title string) string {
	t.Helper()
	p := filepath.Join(t.TempDir(), "draft.json")
	body := fmt.Sprintf(`{"case_code":"ATP-CART-0001","title":%q,"module_id":"M003",
	 "priority":"P1","author":"qa.kanashi",
	 "steps":[{"seq":1,"action":"OPEN_URL","input_data":"http://x/cart",
	           "wait_strategy":"PRESENCE","wait_timeout_sec":10,"on_failure":"ABORT"}]}`, title)
	if err := os.WriteFile(p, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	return p
}

// 完整七步：draft → show → validate → update → preview → commit → 重放
func TestFullFlow(t *testing.T) {
	id := uuid.NewString()

	d := run("draft", "--json", "--id", id, "-p", "PC_WEB", "-t", "购物车结算")
	if d.code != 0 {
		t.Fatalf("draft 应成功，实际 %d: %s%s", d.code, d.out, d.err)
	}
	if got := d.data(t, "status"); got != "AI_DRAFT" {
		t.Fatalf("status 应是 AI_DRAFT，实际 %v", got)
	}
	if got := d.data(t, "version"); got != float64(0) {
		t.Fatalf("初始 version 应是 0，实际 %v", got)
	}

	if r := run("show", "--json", id); r.code != 0 {
		t.Fatalf("show 应成功，实际 %d", r.code)
	}

	f := draftFile(t, "购物车结算")
	if r := run("validate", "--json", "-f", f); r.code != 0 {
		t.Fatalf("validate 应通过，实际 %d: %s%s", r.code, r.out, r.err)
	}

	u := run("update", "--json", id, "--version", "0", "-f", f)
	if u.code != 0 || u.data(t, "version") != float64(1) {
		t.Fatalf("update 后 version 应是 1，实际 code=%d %v", u.code, u.data(t, "version"))
	}

	p := run("preview", id)
	if p.code != 0 {
		t.Fatalf("preview 应成功，实际 %d", p.code)
	}
	want := fmt.Sprintf("atp commit %s --version 1", id)
	if !strings.Contains(p.out, want) {
		t.Fatalf("preview 必须打出要带回 commit 的 version，期望含 %q\n实际:\n%s", want, p.out)
	}

	c := run("commit", "--json", id, "--version", "1")
	if c.code != 0 {
		t.Fatalf("commit 应成功，实际 %d: %s%s", c.code, c.out, c.err)
	}
	if got := c.data(t, "status"); got != "DRAFT" {
		t.Fatalf("落地状态应是 DRAFT（老平台原生，执行器无感知），实际 %v", got)
	}
	if c.json(t)["replayed"] != false {
		t.Fatal("首次提交不该是重放")
	}

	// ⭐ 幂等重放必须返回退出码 0 —— 返回非 0 会让 agent 无限重试
	rp := run("commit", "--json", id, "--version", "1")
	if rp.code != 0 {
		t.Fatalf("重放的退出码必须是 0，实际 %d", rp.code)
	}
	if rp.json(t)["replayed"] != true {
		t.Fatal("第二次应标记为重放")
	}
}

// 确认之后内容被改过 → commit 退出码 10
func TestStaleVersionExitsTen(t *testing.T) {
	id := uuid.NewString()
	run("draft", "--json", "--id", id, "-p", "PC_WEB", "-t", "登录成功")
	run("update", "--json", id, "--version", "0", "-f", draftFile(t, "登录成功"))
	// 用户 preview 拿到 version=1；确认之前 agent 又改了一版
	run("update", "--json", id, "--version", "1", "-f", draftFile(t, "登录成功（被改过）"))

	if r := run("commit", "--json", id, "--version", "1"); r.code != 10 {
		t.Fatalf("拿过期 version 提交应返回 10，实际 %d: %s%s", r.code, r.out, r.err)
	}
}

// ⭐ 必填缺失 → 14（去问人），值非法 → 12（自己改）。
// 下一步动作不同的，就不能合并成一个码。
func TestMissingVsInvalidAreDifferentExitCodes(t *testing.T) {
	dir := t.TempDir()

	missing := filepath.Join(dir, "missing.json")
	os.WriteFile(missing, []byte(`{"title":"只有标题"}`), 0o600)
	r1 := run("validate", "--json", "-f", missing)
	if r1.code != 14 {
		t.Fatalf("必填缺失应返回 14，实际 %d", r1.code)
	}
	if qs, _ := r1.json(t)["questions"].([]any); len(qs) == 0 {
		t.Fatal("NEEDS_INPUT 必须带上要问用户的问题")
	}

	invalid := filepath.Join(dir, "invalid.json")
	body, _ := os.ReadFile(draftFile(t, "标题"))
	os.WriteFile(invalid, []byte(strings.Replace(string(body), `"P1"`, `"P9"`, 1)), 0o600)
	r2 := run("validate", "--json", "-f", invalid)
	if r2.code != 12 {
		t.Fatalf("值非法应返回 12，实际 %d: %s%s", r2.code, r2.out, r2.err)
	}
	if vs, _ := r2.json(t)["violations"].([]any); len(vs) == 0 {
		t.Fatal("VALIDATION_FAILED 必须带上 violations")
	}
}

func TestNotFoundAndReadOnlyCommands(t *testing.T) {
	if r := run("commit", "--json", uuid.NewString(), "--version", "0"); r.code != 11 {
		t.Fatalf("不存在的 id 应返回 11，实际 %d", r.code)
	}
	if r := run("schema"); r.code != 0 {
		t.Fatalf("schema 应成功，实际 %d", r.code)
	}
	if r := run("modules", "--json", "-p", "ECSHOP"); r.code != 0 {
		t.Fatalf("modules 应成功，实际 %d: %s%s", r.code, r.out, r.err)
	}
}

// schema 输出可直接被 validate 消费 —— 契约自洽
func TestSchemaIsSelfConsistent(t *testing.T) {
	r := run("schema")
	var doc map[string]any
	if err := json.Unmarshal([]byte(r.out), &doc); err != nil {
		t.Fatalf("schema 输出不是合法 JSON: %v", err)
	}
	req, _ := json.Marshal(doc["required"])
	for _, dead := range []string{"browser", "timeout_sec", "status"} {
		if strings.Contains(string(req), dead) {
			t.Errorf("required 里不该还有 %q", dead)
		}
	}
}
