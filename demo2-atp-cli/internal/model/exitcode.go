package model

// ExitCode 是 atp 对调用方（agent）的分派契约。
//
// ⚠️ 两条不能动的约定：
//
//  1. 幂等重放返回 OK —— 重放在语义上是成功；返回非 0 会让 agent 以为没成功而无限重试。
//  2. ValidationFailed 与 NeedsInput 必须分开 —— 一个是 agent 自己能改，一个必须去问人。
//     下一步动作不同的，就不能合并成一个码。
type ExitCode int

const (
	OK ExitCode = 0
	// VersionConflict 版本对不上：内容在用户确认之后被改过。重新 show → preview → 确认。
	VersionConflict ExitCode = 10
	// NotFound 案例不存在，或草稿已被每月清理任务回收。
	NotFound ExitCode = 11
	// ValidationFailed 值不合法，agent 读 violations 自己改。
	ValidationFailed ExitCode = 12
	// StateConflict 当前状态不允许该操作。停下，问用户。
	StateConflict ExitCode = 13
	// NeedsInput 缺必填信息且机器补不出来。去问用户，不要猜。
	NeedsInput ExitCode = 14
	// InfraError 配置缺失、数据库不通等。
	InfraError ExitCode = 20
)

func (c ExitCode) String() string {
	switch c {
	case OK:
		return "OK"
	case VersionConflict:
		return "VERSION_CONFLICT"
	case NotFound:
		return "NOT_FOUND"
	case ValidationFailed:
		return "VALIDATION_FAILED"
	case StateConflict:
		return "STATE_CONFLICT"
	case NeedsInput:
		return "NEEDS_INPUT"
	case InfraError:
		return "INFRA_ERROR"
	default:
		return "UNKNOWN"
	}
}
