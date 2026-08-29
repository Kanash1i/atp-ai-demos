package store

import (
	"context"

	"github.com/jackc/pgx/v5"
)

// ModuleEntry 模块字典的一行 —— agent 用它确认 module_id 的取值范围。
// 「防模型编造 module_id」靠这条，不靠外键（见 DECISIONS D-109）。
type ModuleEntry struct {
	ProjectID   string `json:"projectId"`
	ProjectCode string `json:"projectCode"`
	ProjectName string `json:"projectName"`
	ModuleID    string `json:"moduleId"`
	ModuleCode  string `json:"moduleCode"`
	ModuleName  string `json:"moduleName"`
}

type DictStore struct{ conn *pgx.Conn }

func NewDictStore(conn *pgx.Conn) *DictStore { return &DictStore{conn: conn} }

func (d *DictStore) ListModules(ctx context.Context) ([]ModuleEntry, error) {
	rows, err := d.conn.Query(ctx, `
		SELECT p.project_id, p.project_code, p.project_name,
		       m.module_id, m.module_code, m.module_name
		  FROM tc_module m
		  JOIN tc_project p ON p.project_id = m.project_id
		 ORDER BY p.project_code, m.module_code`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var out []ModuleEntry
	for rows.Next() {
		var e ModuleEntry
		if err := rows.Scan(&e.ProjectID, &e.ProjectCode, &e.ProjectName,
			&e.ModuleID, &e.ModuleCode, &e.ModuleName); err != nil {
			return nil, err
		}
		out = append(out, e)
	}
	return out, rows.Err()
}
