-- V1 · AI 案例编写的状态机改造
-- 这是要真正执行到老平台的那一支。设计依据：05-CLI-并发幂等答辩稿.md §3。
--
-- ✅ 本库不建外键约束（DECISIONS D-109），所以主键改宽不需要
--    "先摘外键 → 改父 → 改子 → 再装回去" 那套 —— 父子两张表各改各的即可。

-- ── 第一步：主键改宽 ──────────────────────────────────────────
-- UUID 是 36 字符，老列 VARCHAR(32) 装不下。
-- 改造后 AI 案例的主键由 CLI 本地生成，人工案例仍走平台雪花 ID ——
-- 两者不冲突，唯一约束只关心"不重复"，不关心谁生成的。
-- ⚠️ 父子两边必须一起改：tc_step.case_id 存的就是 tc_case.case_id 的值，
--    虽然没有外键约束强制，长度不一致仍会在写入时被截断。
ALTER TABLE tc_case
  MODIFY COLUMN case_id VARCHAR(36) NOT NULL
        COMMENT 'AI 案例=客户端生成的 UUID；人工案例=平台雪花 ID';

ALTER TABLE tc_step
  MODIFY COLUMN case_id VARCHAR(36) NOT NULL COMMENT '父表主键（逻辑外键，无约束）',
  MODIFY COLUMN step_id VARCHAR(36) NOT NULL COMMENT '子表主键，AI 生成时同为 UUID';

-- ── 第二步：编写态与乐观锁 ────────────────────────────────────
ALTER TABLE tc_case

  -- ⚠️ 新增 AI_DRAFT，**不复用已有的 DRAFT**。
  --    老平台的 DRAFT 语义是"案例已写好、尚未启用"，执行器和列表页都认它。
  --    AI 编写中的行内容还是空的，混进 DRAFT 会被既有流程当成可用案例。
  --    枚举值只能追加在末尾 —— MySQL 的 ENUM 按定义顺序编号，
  --    在中间插值会重排既有行的存储值。
  MODIFY COLUMN status ENUM('DRAFT','ACTIVE','DEPRECATED','AI_DRAFT')
         NOT NULL DEFAULT 'DRAFT',

  -- 编写期这些字段还填不出来，只能放宽 NOT NULL。
  -- ⭐ case_code 放宽后仍带 UNIQUE：MySQL 的唯一索引允许多个 NULL，
  --    所以并存任意多条尚未编号的草稿不会互相撞键。
  MODIFY COLUMN case_code VARCHAR(64)  NULL,
  MODIFY COLUMN module_id VARCHAR(32)  NULL,
  MODIFY COLUMN priority  ENUM('P0','P1','P2','P3') NULL,
  MODIFY COLUMN author    VARCHAR(64)  NULL,
  MODIFY COLUMN title     VARCHAR(200) NULL,

  -- 乐观锁。它替代了外挂方案里的 contentHash：
  -- 用户 preview 拿到 version=N，commit 带 N，中间任何人改一下 version 就跳，commit 必失败。
  ADD COLUMN version INT NOT NULL DEFAULT 0 AFTER status,

  -- 编写期的原始 JSON（含 steps）。落地时投影到正式列 + tc_step。
  ADD COLUMN draft_json JSON NULL,
  ADD COLUMN created_by VARCHAR(64) NULL COMMENT '发起编写的 agent 身份，也是 AI 来源的唯一标记',

  -- ⭐ 约束随状态而变：编写期允许残缺，一旦离开 AI_DRAFT 就必须完整。
  -- ⚠️⚠️ 只在 MySQL 8.0.16+ 生效。5.7 会解析这段但静默丢弃，不报错不告警。
  --      老平台的 DB 版本必须先确认，见 DECISIONS D-108。
  ADD CONSTRAINT ck_case_complete CHECK (
        status = 'AI_DRAFT'
     OR (case_code IS NOT NULL AND title    IS NOT NULL
     AND module_id IS NOT NULL AND priority IS NOT NULL
     AND author    IS NOT NULL)
  );

-- 清理任务（XXL-JOB 每月一次）要走的索引。
-- 只需要 status —— AI_DRAFT 这个状态只可能由 AI 编写路径产生。
-- ⚠️ 没有 ON DELETE CASCADE，清理任务必须自己【先删 tc_step 再删 tc_case】。
CREATE INDEX idx_ai_draft_cleanup ON tc_case (status, created_at);
