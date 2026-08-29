// Package rule 是纯本地规则：零网络、零数据库、零模型调用。
//
// 这里的东西 agent 可以放心高频调、并发调 —— 全是纯函数，没有任何副作用。
package rule

import (
	"encoding/json"
	"fmt"

	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/model"
)

// ParseHeader 从草稿 JSON 里取出表头字段。JSON 解析留在 rule 包，SQL 留在 store 包。
func ParseHeader(draftJSON string) (model.CaseHeader, error) {
	var h model.CaseHeader
	if draftJSON == "" {
		return h, nil
	}
	var raw struct {
		CaseCode     *string `json:"case_code"`
		Title        *string `json:"title"`
		ModuleID     *string `json:"module_id"`
		Priority     *string `json:"priority"`
		Author       *string `json:"author"`
		Precondition *string `json:"precondition"`
	}
	if err := json.Unmarshal([]byte(draftJSON), &raw); err != nil {
		return h, fmt.Errorf("草稿 JSON 解析失败: %w", err)
	}
	h = model.CaseHeader{
		CaseCode: raw.CaseCode, Title: raw.Title, ModuleID: raw.ModuleID,
		Author: raw.Author, Precondition: raw.Precondition,
	}
	if raw.Priority != nil {
		p, err := model.ParsePriority(*raw.Priority)
		if err != nil {
			return h, err
		}
		h.Priority = &p
	}
	return h, nil
}

// InitialDraft 建草稿时的初始内容：只有标题和一个空步骤数组。
func InitialDraft(title string) string {
	b, _ := json.Marshal(map[string]any{"title": title, "steps": []any{}})
	return string(b)
}
