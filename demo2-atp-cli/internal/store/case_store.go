// Package store 是全项目【唯一】持有 SQL 的包（SqlContainmentTest 机械守住这条）。
//
// 数据落点：
//
//	tc_case   表头 + 平台侧状态。编辑期只有骨架，commit 那一刻才被填齐
//	tc_step   一比一。step_json 是完整草稿，编辑期的状态机与乐观锁也在这
//
// 因此三条路径的形状完全不同：
//
//	Draft  —— 两条 INSERT（都是新行，无争用）
//	Update —— 单表单行 CAS，只写 tc_step。编辑期的高频写全在这
//	Commit —— 跨表事务，但一份草稿只发生一次
//
// 五条不变式：
//
//  1. 主键唯一约束就是幂等约束 —— UUID 由 CLI 本地生成，重试复用同一个。
//  2. 检查和写入必须在同一条 UPDATE 里 —— 状态和版本都写进 WHERE，杜绝 TOCTOU。
//  3. rowsAffected == 0 不能直接抛错 —— 读回来分情况，否则重放永远过不去。
//  4. 加锁顺序统一为 tc_step → tc_case —— 跨表的路径只有 Commit 和清理任务，同序才不会死锁。
//  5. 事务里不含任何等外部的东西 —— 不调模型、不等用户。
//     用户确认发生在两次 CLI 调用之间，不占锁。
package store

import (
	"context"
	"errors"
	"fmt"

	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/model"
	"github.com/Kanash1i/atp-ai-demos/atp-cli/internal/rule"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
)

// SQL 标准的 SQLSTATE，不是厂商私有错误码 —— 这样这段逻辑换库也不用改。
const (
	sqlstateUniqueViolation = "23505"
	sqlstateCheckViolation  = "23514"
)

type CaseStore struct{ conn *pgx.Conn }

func NewCaseStore(conn *pgx.Conn) *CaseStore { return &CaseStore{conn: conn} }

// ---------------------------------------------------------------- Draft

// Draft 建草稿：tc_case 一行骨架 + tc_step 一行初始内容。
//
// caseID 由【调用方】生成并在重试时复用 —— 这是幂等的全部来源。
// 若改由数据库生成，"INSERT 成功但响应丢失 → 重试"会产生两条各自合法的草稿，
// 而版本号救不了它们（是两行不同的记录）。
func (s *CaseStore) Draft(ctx context.Context, caseID string, ct model.CaseType, title, createdBy string) model.Result {
	tx, err := s.conn.Begin(ctx)
	if err != nil {
		return infra(err)
	}
	defer tx.Rollback(ctx) //nolint:errcheck // 已 Commit 时是 no-op

	_, err = tx.Exec(ctx, `
		INSERT INTO tc_case (case_id, case_type, status, version, created_by, created_at, updated_at)
		VALUES ($1, $2, $3, 0, $4, now(), now())`,
		caseID, int16(ct), int16(model.StatusAIDraft), createdBy)
	if err == nil {
		_, err = tx.Exec(ctx, `
			INSERT INTO tc_step (step_id, case_id, step_json, status, version, updated_at)
			VALUES ($1, $2, $3::jsonb, $4, 0, now())`,
			newUUID(), caseID, rule.InitialDraft(title), int16(model.StatusAIDraft))
	}
	if err != nil {
		if !isSQLState(err, sqlstateUniqueViolation) {
			return infra(err)
		}
		// ⭐ 唯一约束是并发的最后防线，也是幂等的入口：
		//    把"并发/重试的失败者"转换成"幂等的成功者"。
		_ = tx.Rollback(ctx)
		row, ferr := s.findByID(ctx, s.conn, caseID)
		switch {
		case ferr != nil:
			return infra(ferr)
		case row == nil:
			return model.Fail(model.InfraError, "唯一约束冲突但读不回该行，请检查隔离级别与连接是否同库")
		case !row.IsAIDraft():
			return model.Fail(model.StateConflict,
				fmt.Sprintf("case_id 已被占用且不处于编写态（当前 status=%s）：%s", row.Status, caseID))
		default:
			return model.Replayed(row)
		}
	}
	if err := tx.Commit(ctx); err != nil {
		return infra(err)
	}
	return s.readBack(ctx, caseID, "插入成功但读不回")
}

// ---------------------------------------------------------------- Update

// Update 写内容 —— ⭐ 只碰 tc_step 一张表、一行。
//
// 表头字段这时还只活在 step_json 里，等到 Commit 才投影进 tc_case 的正式列。
// 所以编辑期不管改多少次，都不会去动那张最终要落地的表，
// 跨表事务与随之而来的加锁顺序问题在这条最高频的路径上根本不存在。
//
// CAS：状态必须仍是 AI_DRAFT，且版本号必须与调用方手上的一致。
func (s *CaseStore) Update(ctx context.Context, caseID string, expectedVersion int, draftJSON string) model.Result {
	tag, err := s.conn.Exec(ctx, `
		UPDATE tc_step
		   SET step_json = $1::jsonb, version = version + 1, updated_at = now()
		 WHERE case_id = $2 AND status = $3 AND version = $4`,
		draftJSON, caseID, int16(model.StatusAIDraft), expectedVersion)
	if err != nil {
		return infra(err)
	}
	if tag.RowsAffected() == 1 {
		return s.readBack(ctx, caseID, "更新成功但读不回")
	}
	return s.diagnoseMiss(ctx, caseID, expectedVersion, "写入")
}

// ---------------------------------------------------------------- Commit

// Commit 提交：AI_DRAFT → DRAFT，并把表头从冻结快照投影到 tc_case 的正式列。
//
// ⭐ 只收 caseID 和 version，不接受任何外部内容 ——
// 投影的输入是库里那一行，也就是用户已经确认过的那份快照。
//
// ⭐ 加锁顺序固定 tc_step → tc_case。清理任务（M5）必须同序，
// 否则两者撞在同一条边界草稿上会死锁。
//
// ck_case_complete 正好在这一刻校验必填 —— 编辑期允许残缺，
// 一离开 AI_DRAFT 就必须完整，数据库直接守门。
//
// ⭐ 落地时 step_json 会被规整成【纯步骤数组】：编辑期它是带表头的对象（表头暂存在那儿），
// 落地后必须与人工案例的格式完全一致，否则老执行器读不了。
func (s *CaseStore) Commit(ctx context.Context, caseID string, expectedVersion int) model.Result {
	tx, err := s.conn.Begin(ctx)
	if err != nil {
		return infra(err)
	}
	defer tx.Rollback(ctx) //nolint:errcheck

	tag, err := tx.Exec(ctx, `
		UPDATE tc_step
		   SET status = $1, version = version + 1, updated_at = now()
		 WHERE case_id = $2 AND status = $3 AND version = $4`,
		int16(model.StatusDraft), caseID, int16(model.StatusAIDraft), expectedVersion)
	if err != nil {
		return infra(err)
	}
	if tag.RowsAffected() != 1 {
		_ = tx.Rollback(ctx)
		return s.diagnoseMiss(ctx, caseID, expectedVersion, "提交")
	}

	row, err := s.findByID(ctx, tx, caseID)
	if err != nil {
		return infra(err)
	}
	if row == nil {
		return model.Fail(model.InfraError, "提交成功但读不回")
	}

	header, err := rule.ParseHeader(row.DraftJSON)
	if err != nil {
		// 草稿内容有问题（如 priority 不是枚举值）—— 状态迁移必须一起回滚
		return model.Fail(model.ValidationFailed, "表头投影失败，提交已回滚："+err.Error())
	}
	if err := projectHeader(ctx, tx, caseID, header); err != nil {
		if isSQLState(err, sqlstateCheckViolation) {
			return model.Fail(model.ValidationFailed,
				"案例必填字段不完整（case_code / title / module_id / priority / author），被约束 ck_case_complete 拦下")
		}
		return infra(err)
	}

	// ⭐ 表头已经投影到 tc_case 的正式列，step_json 必须规整回老平台的【纯步骤数组】。
	//
	//    保守路线的主张是「落库格式与人工案例完全一致，老执行器无感知照跑」，
	//    而老执行器读的是数组。留成对象的话那句主张就是假的 ——
	//    更糟的是它崩的时候没人知道是谁写进去的。
	//
	//    ⚠️ 顺序不能动：必须排在 ParseHeader 之后，因为规整会把表头从 step_json 里抹掉。
	kind, err := normalizeStepJSON(ctx, tx, caseID)
	if err != nil {
		return infra(err)
	}
	if kind != "array" {
		// 走到这里说明草稿里根本没有 steps 数组。ck_case_complete 只管表头、管不到这个 ——
		// 与其写进去让执行器崩，不如在这里挡掉。
		return model.Fail(model.ValidationFailed,
			"草稿缺少 steps 数组（规整后 step_json 是 "+kind+"），提交已回滚")
	}

	if err := tx.Commit(ctx); err != nil {
		return infra(err)
	}
	return s.readBack(ctx, caseID, "提交成功但读不回")
}

// normalizeStepJSON 把编辑期的对象格式规整成老平台的纯步骤数组。
//
// SQL 本身幂等：对象取 steps；已经是数组时 -> 'steps' 返回 NULL，COALESCE 原样保留。
// 所以 commit 的幂等重放路径不会把已经规整好的内容改坏。
//
// 返回规整后的 jsonb 类型，调用方据此判断草稿里到底有没有 steps。
func normalizeStepJSON(ctx context.Context, tx pgx.Tx, caseID string) (string, error) {
	var kind *string
	err := tx.QueryRow(ctx, `
		UPDATE tc_step
		   SET step_json = COALESCE(step_json->'steps', step_json), updated_at = now()
		 WHERE case_id = $1
		RETURNING jsonb_typeof(step_json)`, caseID).Scan(&kind)
	if err != nil {
		return "", err
	}
	if kind == nil {
		return "null", nil
	}
	return *kind, nil
}

// projectHeader 把冻结快照里的表头写进 tc_case 的正式列，同时翻状态。
func projectHeader(ctx context.Context, tx pgx.Tx, caseID string, h model.CaseHeader) error {
	var priority *int16
	if h.Priority != nil {
		p := int16(*h.Priority)
		priority = &p
	}
	_, err := tx.Exec(ctx, `
		UPDATE tc_case
		   SET case_code = $1, title = $2, module_id = $3, priority = $4,
		       author = $5, precondition = $6,
		       status = $7, version = version + 1, updated_at = now()
		 WHERE case_id = $8`,
		h.CaseCode, h.Title, h.ModuleID, priority, h.Author, h.Precondition,
		int16(model.StatusDraft), caseID)
	return err
}

// ---------------------------------------------------------------- 查询

func (s *CaseStore) Show(ctx context.Context, caseID string) model.Result {
	row, err := s.findByID(ctx, s.conn, caseID)
	if err != nil {
		return infra(err)
	}
	if row == nil {
		return model.Fail(model.NotFound, "案例不存在："+caseID)
	}
	return model.Ok(row)
}

// ------------------------------------------------- rowsAffected == 0

// diagnoseMiss ⭐ rowsAffected == 0 之后必须读回来分情况，绝不能直接报错 ——
// 否则幂等重放永远过不去，agent 会一直重试到超时。
func (s *CaseStore) diagnoseMiss(ctx context.Context, caseID string, expectedVersion int, op string) model.Result {
	row, err := s.findByID(ctx, s.conn, caseID)
	if err != nil {
		return infra(err)
	}
	switch {
	case row == nil:
		return model.Fail(model.NotFound, "案例不存在，或草稿已被每月清理任务回收："+caseID)
	case !row.IsAIDraft():
		if row.Version == expectedVersion+1 {
			return model.Replayed(row) // ← 退出码 0
		}
		return model.Fail(model.StateConflict, fmt.Sprintf(
			"案例已提交（当前状态 %s，version=%d），此后又被修改过，本次%s不予执行",
			row.Status, row.Version, op))
	default:
		return model.Fail(model.VersionConflict, fmt.Sprintf(
			"版本不一致：库中 version=%d，你手上是 %d。内容在你确认之后被改过，请重新 show/preview 再确认",
			row.Version, expectedVersion))
	}
}

// ---------------------------------------------------------------- 内部

// querier 让 findByID 既能在事务里用，也能在事务外用。
type querier interface {
	QueryRow(ctx context.Context, sql string, args ...any) pgx.Row
}

func (s *CaseStore) findByID(ctx context.Context, q querier, caseID string) (*model.CaseRow, error) {
	var (
		r          model.CaseRow
		ct, cs, ds int16
		draft      *string
	)
	err := q.QueryRow(ctx, `
		SELECT c.case_id, c.case_type,
		       c.status AS case_status, c.version AS case_version,
		       s.status AS draft_status, s.version AS draft_version,
		       s.step_json::text
		  FROM tc_case c
		  LEFT JOIN tc_step s ON s.case_id = c.case_id
		 WHERE c.case_id = $1`, caseID).
		Scan(&r.CaseID, &ct, &cs, &r.PlatformVersion, &ds, &r.Version, &draft)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	r.CaseType, r.PlatformStatus, r.Status = model.CaseType(ct), model.CaseStatus(cs), model.CaseStatus(ds)
	if draft != nil {
		r.DraftJSON = *draft
	}
	return &r, nil
}

func (s *CaseStore) readBack(ctx context.Context, caseID, failMsg string) model.Result {
	row, err := s.findByID(ctx, s.conn, caseID)
	if err != nil {
		return infra(err)
	}
	if row == nil {
		return model.Fail(model.InfraError, failMsg)
	}
	return model.Ok(row)
}

func isSQLState(err error, state string) bool {
	var pgErr *pgconn.PgError
	return errors.As(err, &pgErr) && pgErr.Code == state
}

func infra(err error) model.Result {
	var pgErr *pgconn.PgError
	if errors.As(err, &pgErr) {
		return model.Fail(model.InfraError,
			fmt.Sprintf("数据库操作失败: [SQLSTATE %s] %s", pgErr.Code, pgErr.Message))
	}
	return model.Fail(model.InfraError, "数据库操作失败: "+err.Error())
}
