package model

import "encoding/json"

// DisplayRef 给【人】看的案例标识。
//
// ⭐ 优先案例编号 —— 那是测试人员在 ATP 界面上看得到的东西。
// caseId 是数据库主键，不该出现在人读的文本里；--json 里保留它没问题，
// 那是给程序看的。
//
// ⚠️ 编辑期 case_code 在 step_json 的表头里；提交之后表头被投影进 tc_case、
// step_json 只剩纯步骤数组，这时就取不到了 —— 退回 caseId，
// 有个标识总比没有强。平台的 DraftView 补上 caseCode 之后这条退路可以去掉。
func (r *CaseRow) DisplayRef() string {
	if code := headerString(r.DraftJSON, "case_code"); code != "" {
		return code
	}
	return r.CaseID
}

func headerString(draftJSON, key string) string {
	if draftJSON == "" {
		return ""
	}
	var m map[string]json.RawMessage
	if json.Unmarshal([]byte(draftJSON), &m) != nil {
		return "" // 已提交（是数组）或坏 JSON，都取不到表头
	}
	var s string
	if json.Unmarshal(m[key], &s) != nil {
		return ""
	}
	return s
}

// CaseRow 一份案例的当前样子 —— 由 tc_case 与 tc_step 各取所需拼成。
//
// ⭐ 两张表各有自己的 version，管的是两个不同的生命周期：
//   - Version        来自 tc_step —— 编辑期的乐观锁。preview 给用户看的、commit 要带回来的就是它。
//   - PlatformVersion 来自 tc_case —— 案例落地后平台侧修改用的。编辑期一动不动。
//
// 编辑期的高频写因此全部落在 tc_step 一张表一行上，tc_case 只在 commit 那一刻被写一次。
type CaseRow struct {
	CaseID   string     `json:"caseId"`
	CaseType CaseType   `json:"-"`
	Status   CaseStatus `json:"-"` // tc_step.status —— 编辑期状态机
	Version  int        `json:"version"`

	PlatformStatus  CaseStatus `json:"-"` // tc_case.status
	PlatformVersion int        `json:"-"`

	// DraftJSON tc_step.step_json —— 编辑期是完整草稿（表头 + steps），提交后仍留作快照。
	DraftJSON string `json:"-"`
}

func (r *CaseRow) IsAIDraft() bool { return r.Status == StatusAIDraft }

// CaseHeader 草稿里的表头字段。
//
// 编辑期它只存在于 tc_step.step_json 里；commit 那一刻才投影到 tc_case 的正式列上 ——
// 所以编辑期完全不用碰 tc_case，也就没有跨表写。
//
// 投影的输入是库里那一行而不是调用方传来的内容，
// 因此不违反「commit 只收 id + version」：它展开的是用户已经确认过的那份快照。
type CaseHeader struct {
	CaseCode     *string
	Title        *string
	ModuleID     *string
	Priority     *Priority
	Author       *string
	Precondition *string
}

// Result store 层的统一返回。
//
// Replayed 为 true 时 Code 仍是 OK —— 重放在语义上是成功。
type Result struct {
	Code     ExitCode
	Replayed bool
	Row      *CaseRow
	Message  string

	// Violations 违反明细。只给一句"校验失败"的话 agent 只能瞎改 ——
	// 它需要知道违反了哪几条才能自我修正。
	//
	// ⚠️ 迁移前这条通路是断的：Emit 硬传 nil，只有本地 validate 命令
	// 直接调 Writer.Fail 才带得上。HTTP 实现要把平台 422 的 findings
	// 透出来，所以在这里补齐 —— pgx 实现走的 CHECK 约束报错同样受益。
	Violations []string
}

func (r Result) Succeeded() bool { return r.Code == OK }

func Ok(row *CaseRow) Result { return Result{Code: OK, Row: row} }
func Replayed(row *CaseRow) Result {
	return Result{Code: OK, Replayed: true, Row: row, Message: "幂等重放：该操作此前已成功"}
}
func Fail(code ExitCode, msg string) Result { return Result{Code: code, Message: msg} }

// Invalid 带违反明细的失败。
func Invalid(code ExitCode, msg string, violations []string) Result {
	return Result{Code: code, Message: msg, Violations: violations}
}
