package model

// ModuleEntry 模块字典的一行 —— agent 用它确认 module_id 的取值范围。
//
// 「防模型编造 module_id」靠这条，不靠外键（D-109）。
//
// 字段名与平台 GET /api/modules 的响应逐字对齐，反序列化零转换。
type ModuleEntry struct {
	ProjectID   string `json:"projectId"`
	ProjectCode string `json:"projectCode"`
	ProjectName string `json:"projectName"`
	ModuleID    string `json:"moduleId"`
	ModuleCode  string `json:"moduleCode"`
	ModuleName  string `json:"moduleName"`
}
