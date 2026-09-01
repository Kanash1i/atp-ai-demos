// Package atpcli 只做一件事：把草稿的 JSON Schema 嵌进二进制。
//
// 放在模块根而不是塞进某个 internal 包，是为了让 schema/ 在目录树顶层
// 保持可见 —— 它是要交给调用方看的交付物，不是实现细节。
// （go:embed 只能嵌入包目录及其子目录，所以嵌入点必须在这一层。）
//
// ⚠️ 迁移之前这里还嵌着 migrations/*.sql —— 那些随直连数据库的实现
// 一起删掉了（D-130）。建表现在是平台的事，CLI 不再需要知道表长什么样。
//
// arch_test.go 也挂在这一层：它从模块根往下扫，断言整个 CLI 不出现任何 SQL。
package atpcli

import "embed"

//go:embed schema/tc_case.schema.json
var SchemaFS embed.FS

// SchemaJSON 草稿的 JSON Schema。
func SchemaJSON() []byte {
	b, err := SchemaFS.ReadFile("schema/tc_case.schema.json")
	if err != nil {
		panic("内置 schema 缺失: " + err.Error()) // 编译期就该保证，运行到这里说明构建坏了
	}
	return b
}
