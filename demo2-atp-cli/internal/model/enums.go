package model

import "fmt"

// 枚举一律存 SMALLINT，语义由这里持有（见 DECISIONS D-112）。
// 这样加一个新状态不需要任何 DDL —— 只是应用层多认一个码。
// 代价是 SELECT * 出来是数字，靠 COMMENT ON COLUMN 补偿可读性。

type CaseStatus int16

const (
	// StatusDraft 案例已写好、尚未启用。执行器与列表页认这个状态，也是 commit 的落地目标。
	StatusDraft      CaseStatus = 1
	StatusActive     CaseStatus = 2
	StatusDeprecated CaseStatus = 3
	// StatusAIDraft AI 编写中。刻意不复用 StatusDraft ——
	// 编写中的行内容还是空的，混进 DRAFT 会被既有流程当成可用案例。
	StatusAIDraft CaseStatus = 4
)

func (s CaseStatus) String() string {
	switch s {
	case StatusDraft:
		return "DRAFT"
	case StatusActive:
		return "ACTIVE"
	case StatusDeprecated:
		return "DEPRECATED"
	case StatusAIDraft:
		return "AI_DRAFT"
	default:
		return fmt.Sprintf("UNKNOWN(%d)", int16(s))
	}
}

// CaseType 执行平台。老平台本来就按 iOS / Android / Web 区分案例。
type CaseType int16

const (
	TypeIOS     CaseType = 1
	TypeAndroid CaseType = 2
	TypePCWeb   CaseType = 3
)

func (t CaseType) String() string {
	switch t {
	case TypeIOS:
		return "IOS"
	case TypeAndroid:
		return "ANDROID"
	case TypePCWeb:
		return "PC_WEB"
	default:
		return fmt.Sprintf("UNKNOWN(%d)", int16(t))
	}
}

func ParseCaseType(s string) (CaseType, error) {
	switch s {
	case "IOS":
		return TypeIOS, nil
	case "ANDROID":
		return TypeAndroid, nil
	case "PC_WEB":
		return TypePCWeb, nil
	default:
		return 0, fmt.Errorf("未知的执行平台 %q（可选：IOS / ANDROID / PC_WEB）", s)
	}
}

// Priority 码值与 P 后面的数字一致。
type Priority int16

func (p Priority) String() string { return fmt.Sprintf("P%d", int16(p)) }

func ParsePriority(s string) (Priority, error) {
	switch s {
	case "P0":
		return 0, nil
	case "P1":
		return 1, nil
	case "P2":
		return 2, nil
	case "P3":
		return 3, nil
	default:
		return 0, fmt.Errorf("未知的优先级 %q（可选：P0 / P1 / P2 / P3）", s)
	}
}
