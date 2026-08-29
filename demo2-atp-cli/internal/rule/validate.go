package rule

import (
	"encoding/json"
	"fmt"
	"sort"
	"strings"

	atpcli "github.com/Kanash1i/atp-ai-demos/atp-cli"
	"github.com/santhosh-tekuri/jsonschema/v6"
	"github.com/santhosh-tekuri/jsonschema/v6/kind"
	"golang.org/x/text/language"
	"golang.org/x/text/message"
)

// jsonschema v6 的 LocalizedString 需要一个真的 printer —— 传 nil 会 panic。
var printer = message.NewPrinter(language.English)

// SchemaJSON 草稿的 JSON Schema —— 编进二进制，单文件分发，不依赖运行时的文件布局。
func SchemaJSON() []byte { return atpcli.SchemaJSON() }

// ValidationResult ⭐ 把校验失败分成两类，因为两类的下一步动作完全不同：
//
//   - Missing（必填字段压根没给）→ NEEDS_INPUT(14)：机器补不出来，
//     agent 必须去问用户，猜一个填进去就是在制造假象
//   - Invalid（给了但值不合法）  → VALIDATION_FAILED(12)：agent 自己按 violations 改
//
// 两类同时存在时优先报 NEEDS_INPUT —— 人不补信息，agent 改了也白改。
//
// 这条分流是从已废弃的 MCP 方案里继承下来的唯一结构性设计（原三态状态机）。
// 压成一个"校验失败"，调用方就只能靠读诊断文本猜下一步 —— 那是在赌。
type ValidationResult struct {
	Missing []string
	Invalid []string
}

func (r ValidationResult) Passed() bool { return len(r.Missing) == 0 && len(r.Invalid) == 0 }

// NeedsUserInput 缺信息优先于值非法。
func (r ValidationResult) NeedsUserInput() bool { return len(r.Missing) > 0 }

type Validator struct{ schema *jsonschema.Schema }

func NewValidator() (*Validator, error) {
	var doc any
	if err := json.Unmarshal(SchemaJSON(), &doc); err != nil {
		return nil, fmt.Errorf("内置 schema 解析失败: %w", err)
	}
	c := jsonschema.NewCompiler()
	if err := c.AddResource("atp://schema/tc_case", doc); err != nil {
		return nil, err
	}
	s, err := c.Compile("atp://schema/tc_case")
	if err != nil {
		return nil, err
	}
	return &Validator{schema: s}, nil
}

func (v *Validator) Validate(draft any) ValidationResult {
	err := v.schema.Validate(draft)
	if err == nil {
		return ValidationResult{}
	}
	ve, ok := err.(*jsonschema.ValidationError)
	if !ok {
		return ValidationResult{Invalid: []string{err.Error()}}
	}
	var res ValidationResult
	for _, leaf := range leaves(ve) {
		// ⭐ 分流就在这一行：kind.Required 是"必填字段压根没给"，其余都是"给了但值不合法"。
		if req, ok := leaf.ErrorKind.(*kind.Required); ok {
			at := location(leaf)
			for _, f := range req.Missing {
				res.Missing = append(res.Missing, fmt.Sprintf("%s 缺少必填字段 %q", at, f))
			}
			continue
		}
		res.Invalid = append(res.Invalid, describe(leaf))
	}
	sort.Strings(res.Missing)
	sort.Strings(res.Invalid)
	return res
}

// leaves 取最深的那层错误 —— 上层是"这个对象不合法"这种没信息量的包装。
func leaves(e *jsonschema.ValidationError) []*jsonschema.ValidationError {
	if len(e.Causes) == 0 {
		return []*jsonschema.ValidationError{e}
	}
	var out []*jsonschema.ValidationError
	for _, c := range e.Causes {
		out = append(out, leaves(c)...)
	}
	return out
}

func location(e *jsonschema.ValidationError) string {
	if len(e.InstanceLocation) == 0 {
		return "(根对象)"
	}
	return "/" + strings.Join(e.InstanceLocation, "/")
}

func describe(e *jsonschema.ValidationError) string {
	return fmt.Sprintf("%s: %v", location(e), e.ErrorKind.LocalizedString(printer))
}
