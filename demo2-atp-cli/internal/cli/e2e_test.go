package cli_test

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/cli"
	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/config"
	"github.com/google/uuid"
)

// TestMain 把 e2e 指向【真实运行的平台】，不再起 PG 容器。
//
// ⚠️ 迁移之前这里起一个 postgres:17 容器、跑 DDL、注入 DSN —— 那是 CLI
// 直连数据库时代的形状。现在 CLI 一句 SQL 都没有（arch_test 守着），
// 端到端的另一端就是平台本身。
//
// 打真平台而不是写桩，是因为桩要把版本推进、CAS、重放全模拟一遍 ——
// 那等于把平台再实现一次，而且模拟错了测试照样绿。
//
// 平台不可达时跳过：e2e 需要真环境，但不该让没有环境的人 go test ./... 挂掉。
func TestMain(m *testing.M) {
	base, err := config.Load().APIBase()
	if err != nil {
		fmt.Fprintln(os.Stderr, "跳过 e2e：没有 ATP_API_URL —", err)
		os.Exit(0)
	}
	c, err := net.DialTimeout("tcp", strings.TrimPrefix(strings.TrimPrefix(base, "http://"), "https://"), 2*time.Second)
	if err != nil {
		fmt.Fprintf(os.Stderr, "跳过 e2e：平台不可达（%s）— %v\n", base, err)
		os.Exit(0)
	}
	c.Close()

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

// caseSeq 给每个用例发一个不重复的 case_code。
//
// ⚠️ 编号必须符合 schema 的 ^ATP-[A-Z]+-[0-9]{4}$ —— 拿 UUID 前几位凑会含小写字母，
//
//	validate 直接判 12，而症状看起来像"业务逻辑挂了"，很误导。
var caseSeq atomic.Int32

// nextCaseCode 取一个本轮唯一的案例编号。
//
// ⚠️ 迁移之前每次跑都起一个新的 PG 容器，库是空的，固定号段就够用。
// 现在 e2e 打的是持久化的平台库 —— 固定号段会跟【上一次跑】撞，
// 症状是首次跑过、第二次挂，比"单跑过全量挂"更难查，因为它跟并发无关。
// 所以基数取自启动时刻。
//
// 用 ATP-CLIT- 前缀而不是 ATP-CART-：后者里有 agent 端到端跑通的演示资产
// （ATP-CART-0013/0014），测试不该往那个号段里塞东西。
// ⚠️ schema 的 pattern 是 ^ATP-[A-Z]+-[0-9]{4}$ —— 前缀只能是字母，
// 所以不能叫 ATP-E2E（"E2E" 里有数字，我第一版就是这么挂的）。
var codeBase = int64(time.Now().Unix() % 9000)

func nextCaseCode() string {
	return fmt.Sprintf("ATP-CLIT-%04d", 1000+(codeBase+int64(caseSeq.Add(1)))%9000)
}

// mustDraftFile 只要文件路径时用。
func mustDraftFile(t *testing.T, title string) string {
	p, _ := draftFile(t, title)
	return p
}

func draftFile(t *testing.T, title string) (path, caseCode string) {
	t.Helper()
	p := filepath.Join(t.TempDir(), "draft.json")
	// ⚠️ case_code 上有唯一约束，每个用例必须用不同的编号 ——
	//    否则先提交的那条会让后面的用例撞 uk_case_code，
	//    而且症状是"某个用例单跑过、全量跑挂"，很难查。
	// ⚠️ 内容必须【合规】—— 迁移之后 commit 必经平台的 StandardsValidator，
	//    ERROR 一律拦下。迁移之前这里只有一个 OPEN_URL 也能提交成功，
	//    因为 CLI 的直连路径根本不跑 STD 校验（规则引擎在 CLI 侧从未实现）。
	//    「规则是硬的，agent 绕不过去」这句话是迁移之后才为真的。
	//    STD-008：整条案例至少一个 ASSERT_*。
	code := nextCaseCode()
	body := fmt.Sprintf(`{"case_code":%q,"title":%q,"module_id":"M003",
	 "priority":"P1","author":"qa.kanashi",
	 "steps":[{"seq":1,"action":"OPEN_URL","input_data":"http://x/cart",
	           "wait_strategy":"PRESENCE","wait_timeout_sec":10,"on_failure":"ABORT"},
	          {"seq":2,"action":"ASSERT_VISIBLE","locator_type":"CSS",
	           "locator_value":"[data-testid=\"cart\"]",
	           "wait_strategy":"VISIBLE","wait_timeout_sec":10,"on_failure":"ABORT"}]}`,
		code, title)
	if err := os.WriteFile(p, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	return p, code
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

	f, code := draftFile(t, "购物车结算")
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
	// ⭐ preview 是给【人】看的 —— 提示里用案例编号，不是 caseId。
	// caseId 是数据库主键，用户在 ATP 界面上根本看不到它，
	// 让他复制一串 UUID 去敲命令是把内部标识泄漏到了用户语言里。
	want := fmt.Sprintf("atp commit %s --version 1", code)
	if !strings.Contains(p.out, want) {
		t.Fatalf("preview 必须打出要带回 commit 的编号与 version，期望含 %q\n实际:\n%s", want, p.out)
	}
	if strings.Contains(p.out, id) {
		t.Fatalf("给人看的输出里不该出现 caseId(%s)\n实际:\n%s", id, p.out)
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
	run("update", "--json", id, "--version", "0", "-f", mustDraftFile(t, "登录成功"))
	// 用户 preview 拿到 version=1；确认之前 agent 又改了一版
	run("update", "--json", id, "--version", "1", "-f", mustDraftFile(t, "登录成功（被改过）"))

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
	body, _ := os.ReadFile(mustDraftFile(t, "标题"))
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

// ⭐ 对外契约：带 --json 时，任何失败都必须是一个 JSON 信封。
//
// 平台侧（atp-platform）的 agent 直接 exec 这个 CLI。
// 若参数错误走纯文本、业务错误走 JSON，调用方就得写两条解析路径 —— 那是契约有洞。
func TestContract_ParamErrorAlsoEmitsEnvelope(t *testing.T) {
	r := run("draft", "--json", "-t", "没给 platform")

	if r.code != 12 {
		t.Fatalf("缺必填参数应返回 12，实际 %d", r.code)
	}
	env := r.json(t)
	for _, k := range []string{"ok", "code", "replayed", "data", "violations", "questions"} {
		if _, ok := env[k]; !ok {
			t.Errorf("信封缺字段 %q", k)
		}
	}
	if env["code"] != "VALIDATION_FAILED" {
		t.Errorf("code 应是 VALIDATION_FAILED，实际 %v", env["code"])
	}
	if env["ok"] != false {
		t.Errorf("ok 应是 false")
	}
	if msg, _ := env["message"].(string); !strings.Contains(msg, "platform") {
		t.Errorf("message 应点名缺了哪个参数，实际 %q", msg)
	}
}

// 不带 --json 时保持纯文本 —— 人读的通道不该被信封污染。
func TestContract_ParamErrorStaysPlainWithoutJSONFlag(t *testing.T) {
	r := run("draft", "-t", "没给 platform")
	if r.code != 12 {
		t.Fatalf("退出码应是 12，实际 %d", r.code)
	}
	if !strings.Contains(r.err, "[VALIDATION_FAILED]") {
		t.Fatalf("应是纯文本，实际 %q", r.err)
	}
	if strings.Contains(r.err, "\"ok\"") {
		t.Fatal("不带 --json 不该输出信封")
	}
}

// ⭐ data.draft 的类型会随状态变：编辑期是对象，落地后是纯步骤数组。
//
// 这是调用方最容易踩的一处 —— 它不是字段名变了，是同一个字段的 JSON 类型变了。
func TestContract_DraftTypeChangesAfterCommit(t *testing.T) {
	id := uuid.NewString()
	run("draft", "--json", "--id", id, "-p", "PC_WEB", "-t", "类型契约")
	f, _ := draftFile(t, "类型契约")
	run("update", "--json", id, "--version", "0", "-f", f)

	if _, ok := run("show", "--json", id).data(t, "draft").(map[string]any); !ok {
		t.Fatal("编辑期 data.draft 应是 object")
	}

	c := run("commit", "--json", id, "--version", "1")
	if c.code != 0 {
		t.Fatalf("commit 应成功，实际 %d: %s%s", c.code, c.out, c.err)
	}
	if _, ok := c.data(t, "draft").([]any); !ok {
		t.Fatalf("落地后 data.draft 应是 array（老平台契约），实际 %T", c.data(t, "draft"))
	}
}
