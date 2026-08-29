package rule_test

import (
	"encoding/json"
	"strings"
	"testing"

	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/rule"
)

func parse(t *testing.T, s string) any {
	t.Helper()
	var v any
	if err := json.Unmarshal([]byte(s), &v); err != nil {
		t.Fatalf("测试数据不是合法 JSON: %v", err)
	}
	return v
}

const good = `{"case_code":"ATP-CART-0001","title":"购物车结算","module_id":"M003",
 "priority":"P1","author":"qa.kanashi",
 "steps":[{"seq":1,"action":"CLICK","wait_strategy":"VISIBLE","wait_timeout_sec":10,"on_failure":"ABORT"}]}`

func TestValidate_Passes(t *testing.T) {
	v, err := rule.NewValidator()
	if err != nil {
		t.Fatal(err)
	}
	if res := v.Validate(parse(t, good)); !res.Passed() {
		t.Fatalf("应通过，实际 missing=%v invalid=%v", res.Missing, res.Invalid)
	}
}

// ⭐ 这是整个校验器存在的理由：两类失败的下一步动作完全不同。
//   - 必填缺失 → agent 必须去问用户
//   - 值不合法 → agent 自己就能改
//
// 压成一个"校验失败"，调用方只能靠读诊断文本猜 —— 那是在赌。
func TestValidate_MissingVsInvalidAreSeparated(t *testing.T) {
	v, _ := rule.NewValidator()

	missing := v.Validate(parse(t, `{"title":"只有标题"}`))
	if !missing.NeedsUserInput() {
		t.Fatal("必填缺失时必须走 NEEDS_INPUT 分支")
	}
	if len(missing.Missing) == 0 {
		t.Fatal("应列出缺了哪些字段")
	}
	joined := strings.Join(missing.Missing, " ")
	for _, f := range []string{"case_code", "module_id", "priority", "author", "steps"} {
		if !strings.Contains(joined, f) {
			t.Errorf("缺失清单里应点名 %q，实际: %s", f, joined)
		}
	}

	invalid := v.Validate(parse(t, strings.Replace(good, `"P1"`, `"P9"`, 1)))
	if invalid.Passed() {
		t.Fatal("P9 不是合法优先级，应报错")
	}
	if invalid.NeedsUserInput() {
		t.Fatal("值不合法是 agent 自己能改的，不该走 NEEDS_INPUT")
	}
}

// 两类同时存在时优先 NEEDS_INPUT —— 人不补信息，agent 改了也白改。
func TestValidate_MissingWinsOverInvalid(t *testing.T) {
	v, _ := rule.NewValidator()
	res := v.Validate(parse(t, `{"title":"标题","priority":"P9"}`))
	if !res.NeedsUserInput() {
		t.Fatal("缺信息应优先于值非法")
	}
}

func TestParseHeader(t *testing.T) {
	h, err := rule.ParseHeader(good)
	if err != nil {
		t.Fatal(err)
	}
	if h.CaseCode == nil || *h.CaseCode != "ATP-CART-0001" {
		t.Errorf("case_code 解析错")
	}
	if h.Priority == nil || h.Priority.String() != "P1" {
		t.Errorf("priority 解析错")
	}
	if _, err := rule.ParseHeader(`{"priority":"P9"}`); err == nil {
		t.Error("非法枚举应报错，而不是静默吞掉")
	}
}

// schema 是契约：不该由调用方产出的字段必须在里面说清楚。
func TestSchema_IsSelfConsistent(t *testing.T) {
	var doc map[string]any
	if err := json.Unmarshal(rule.SchemaJSON(), &doc); err != nil {
		t.Fatal(err)
	}
	req, _ := json.Marshal(doc["required"])
	for _, dead := range []string{"browser", "timeout_sec", "status"} {
		if strings.Contains(string(req), dead) {
			t.Errorf("required 里不该还有 %q", dead)
		}
	}
	notProduced, _ := json.Marshal(doc["x-not-produced-by-this-service"])
	if !strings.Contains(string(notProduced), "状态机") {
		t.Error("schema 应说明 status 由状态机持有，不接受调用方指定")
	}
}
