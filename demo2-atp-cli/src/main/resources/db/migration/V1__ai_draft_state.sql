-- V1 · AI 案例编写的状态机改造
-- 这是要真正执行到老平台的那一支。设计依据：05-CLI-并发幂等答辩稿.md §3。
--
-- ✅ 本库不建外键约束（D-109），主键改宽不需要"先摘外键再装回去"那套。
-- ✅ 枚举存 SMALLINT（D-112），新增 AI_DRAFT 状态**不需要任何 DDL** ——
--    它只是应用层多认一个码 4。所以这份脚本整体是**一个原子事务**，
--    不像用 PG 原生 enum 时要被 ALTER TYPE 拆开。

BEGIN;

-- ── 主键改宽 ──────────────────────────────────────────────────
-- UUID 是 36 字符，老列 VARCHAR(32) 装不下。
-- 改造后 AI 案例的主键由 CLI 本地生成，人工案例仍走平台雪花 ID ——
-- 两者不冲突，唯一约束只关心"不重复"，不关心谁生成的。
--
-- ⚠️ 因此这里【不能】用 PG 原生的 uuid 类型：同一列还要装人工案例的雪花 ID，
--    换成 uuid 会把存量数据挡在外面。放弃 16 字节的紧凑存储，是兼容遗留数据的代价。
--
-- ⚠️ 父子两边必须一起改：tc_step.case_id 存的就是 tc_case.case_id 的值，
--    虽然没有外键约束强制，长度不一致仍会在写入时被静默截断。
ALTER TABLE tc_case ALTER COLUMN case_id TYPE VARCHAR(36);
ALTER TABLE tc_step
  ALTER COLUMN case_id TYPE VARCHAR(36),
  ALTER COLUMN step_id TYPE VARCHAR(36);

-- ── 放宽必填 + 乐观锁 + 编写期内容 ────────────────────────────
ALTER TABLE tc_case
  -- 编写期这些字段还填不出来，只能放宽 NOT NULL。
  -- 放宽是代价，最后那条 CHECK 把它按状态挣回来。
  -- ⭐ case_code 放宽后仍带 UNIQUE：PG 的唯一约束默认允许多个 NULL
  --    （PG 15+ 可用 NULLS NOT DISTINCT 改掉，我们要的正是默认行为），
  --    所以并存任意多条尚未编号的草稿不会互相撞键。
  ALTER COLUMN case_code DROP NOT NULL,
  ALTER COLUMN module_id DROP NOT NULL,
  ALTER COLUMN priority  DROP NOT NULL,
  ALTER COLUMN author    DROP NOT NULL,
  ALTER COLUMN title     DROP NOT NULL,

  -- 乐观锁。它替代了外挂方案里的 contentHash：
  -- 用户 preview 拿到 version=N，commit 带 N，中间任何人改一下 version 就跳，commit 必失败。
  ADD COLUMN version INT NOT NULL DEFAULT 0,

  -- 编写期的原始内容（含 steps）。落地时投影到正式列 + tc_step。
  ADD COLUMN draft_json JSONB NULL,
  ADD COLUMN created_by VARCHAR(64) NULL,

  -- ⭐ 约束随状态而变：编写期允许残缺，一旦离开 AI_DRAFT 就必须完整。
  --    这样 commit 那条 UPDATE 天然被数据库守门 —— 不完整的案例根本迁不出去，
  --    不需要在应用层再写一遍"提交前检查必填"。
  --    ✅ PG 一直真正强制 CHECK（不像 MySQL 5.7 会静默丢弃）。
  --    ⚠️ 这里的字面量 4 就是 AI_DRAFT —— 这是"枚举存 int"要付的可读性代价，
  --       所以下面紧跟一条 COMMENT 把映射写在列上。
  ADD CONSTRAINT ck_case_complete CHECK (
        status = 4
     OR (case_code IS NOT NULL AND title    IS NOT NULL
     AND module_id IS NOT NULL AND priority IS NOT NULL
     AND author    IS NOT NULL)
  );

COMMENT ON COLUMN tc_case.status     IS '状态 1=DRAFT 2=ACTIVE 3=DEPRECATED 4=AI_DRAFT（AI 编写中）';
COMMENT ON COLUMN tc_case.case_id    IS 'AI 案例=客户端生成的 UUID；人工案例=平台雪花 ID';
COMMENT ON COLUMN tc_case.version    IS '乐观锁版本号';
COMMENT ON COLUMN tc_case.created_by IS '发起编写的 agent 身份，也是 AI 来源的唯一标记';

-- 清理任务（XXL-JOB 每月一次）要走的索引。
-- 只需要 status —— AI_DRAFT 这个状态只可能由 AI 编写路径产生。
-- ⚠️ 没有 ON DELETE CASCADE，清理任务必须自己【先删 tc_step 再删 tc_case】。
CREATE INDEX idx_ai_draft_cleanup ON tc_case (status, created_at);

COMMIT;
