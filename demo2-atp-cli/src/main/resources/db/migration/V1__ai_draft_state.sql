-- V1 · AI 案例编写的状态机改造
-- 这是要真正执行到老平台的那一支。设计依据：05-CLI-并发幂等答辩稿.md §3。

ALTER TABLE tc_case

  -- ① 主键放宽到 36 位。UUID 是 36 字符，老列 VARCHAR(32) 装不下。
  --    改造后 AI 案例的主键由 CLI 本地生成，人工案例仍走平台雪花 ID —— 两者不冲突，
  --    因为唯一约束只关心"不重复"，不关心谁生成的。
  MODIFY COLUMN case_id VARCHAR(36) NOT NULL COMMENT 'AI 案例=客户端生成的 UUID；人工案例=平台雪花 ID',

  -- ② ⚠️ 新增 AI_DRAFT，**不复用已有的 DRAFT**。
  --    老平台的 DRAFT 语义是"案例已写好、尚未启用"，执行器和列表页都认它。
  --    AI 编写中的行内容还是空的，混进 DRAFT 会被既有流程当成可用案例。
  --    枚举值追加在末尾 —— MySQL 的 ENUM 按定义顺序编号，
  --    在中间插值会重排既有行的存储值，只能往后加。
  MODIFY COLUMN status ENUM('DRAFT','ACTIVE','DEPRECATED','AI_DRAFT')
         NOT NULL DEFAULT 'DRAFT',

  -- ③ 编写期这些字段还填不出来，只能放宽 NOT NULL。
  --    放宽是代价，第 ⑥ 步的 CHECK 把它按状态挣回来。
  MODIFY COLUMN case_code VARCHAR(64)  NULL,
  MODIFY COLUMN module_id VARCHAR(32)  NULL,
  MODIFY COLUMN priority  ENUM('P0','P1','P2','P3') NULL,
  MODIFY COLUMN author    VARCHAR(64)  NULL,
  MODIFY COLUMN title     VARCHAR(200) NULL,

  -- ④ 区分来源，也是清理任务的过滤条件之一
  ADD COLUMN case_type ENUM('MANUAL','AI') NOT NULL DEFAULT 'MANUAL' AFTER case_id,

  -- ⑤ 乐观锁。它替代了外挂方案里的 contentHash：
  --    用户 preview 拿到 version=N，commit 带 N，中间任何人改一下 version 就跳，commit 必失败。
  ADD COLUMN version INT NOT NULL DEFAULT 0 AFTER status,

  -- 编写期的原始 JSON（含 steps）。落地时投影到正式列 + tc_step。
  ADD COLUMN draft_json JSON NULL,
  ADD COLUMN created_by VARCHAR(64) NULL COMMENT '发起编写的 agent 身份',

  -- ⑥ ⭐ 约束随状态而变：编写期允许残缺，一旦离开 AI_DRAFT 就必须完整。
  --    这样 commit 那条 UPDATE 天然被数据库守门 —— 不完整的案例根本迁不出去，
  --    不需要在应用层再写一遍"提交前检查必填"。
  ADD CONSTRAINT ck_case_complete CHECK (
        status = 'AI_DRAFT'
     OR (case_code IS NOT NULL AND title    IS NOT NULL
     AND module_id IS NOT NULL AND priority IS NOT NULL
     AND author    IS NOT NULL)
  );

-- ⑦ 清理任务（XXL-JOB 每月一次）要走的索引，顺序即选择性顺序
CREATE INDEX idx_ai_draft_cleanup ON tc_case (status, case_type, created_at);
