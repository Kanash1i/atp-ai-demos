// Package atpcli 只做一件事：把 DDL 与 schema 嵌进二进制。
//
// 放在模块根而不是塞进某个 internal 包，是为了让 migrations/ 与 schema/
// 在目录树顶层保持可见 —— 它们是要交给 DBA 和调用方看的交付物，不是实现细节。
// （go:embed 只能嵌入包目录及其子目录，所以嵌入点必须在这一层。）
package atpcli

import "embed"

//go:embed schema/tc_case.schema.json
var SchemaFS embed.FS

//go:embed migrations/*.sql
var MigrationsFS embed.FS

// SchemaJSON 草稿的 JSON Schema。
func SchemaJSON() []byte {
	b, err := SchemaFS.ReadFile("schema/tc_case.schema.json")
	if err != nil {
		panic("内置 schema 缺失: " + err.Error()) // 编译期就该保证，运行到这里说明构建坏了
	}
	return b
}

// MigrationSQL 按顺序返回 V0、V1 的内容。
func MigrationSQL() (v0, v1 string) {
	a, err1 := MigrationsFS.ReadFile("migrations/V0__baseline_legacy.sql")
	b, err2 := MigrationsFS.ReadFile("migrations/V1__ai_draft_state.sql")
	if err1 != nil || err2 != nil {
		panic("内置迁移脚本缺失")
	}
	return string(a), string(b)
}
