-- V4 · 审批中心
--
-- 对应前端设计稿的三类审批卡片：规范例外 / 案例变更 / 数据集发布。
-- 三类的差异全部收在 payload_json 里，表结构只有一张。
--
-- 为什么不拆三张表：三者的生命周期完全相同（提交 → SLA 倒计时 → 批准/退回/挂起），
-- 列也几乎重合。拆开就要在查询「待我审批」时做三路 UNION，
-- 而差异部分本来就是**只给人看的展示数据**，不参与过滤和排序 —— 那正是 JSONB 的用武之地。

BEGIN;

CREATE TABLE tc_approval (
  request_id    VARCHAR(36)    NOT NULL,
  type          SMALLINT       NOT NULL,
  -- 被审批对象。案例变更是 case_id，数据集发布是 corpus_id，规范例外是 case_id。
  -- 类型不同指向不同的表，所以这里**不建索引之外的任何约束** —— 多态引用只能靠应用层保证。
  target_id     VARCHAR(36)    NULL,
  title         VARCHAR(200)   NOT NULL,
  summary       VARCHAR(500)   NULL,

  -- 三类审批的差异全在这里：
  --   RULE_EXCEPTION   { violated_std, step_seq, reason, expire_at }
  --   CASE_CHANGE      { before: {...}, after: {...}, diff_summary: ["+4 steps", "timeout 30→60"] }
  --   DATASET_RELEASE  { corpus_name, docs_count, index_progress, evaluated: false }
  -- ⚠️ CASE_CHANGE 存**整包 before/after** 而不是变更字段：
  --    审批要展示 diff，而 diff 需要两侧完整快照才算得出来；
  --    只存变更字段的话，案例在待审期间又被改了，diff 就对不上了。
  payload_json  JSONB          NOT NULL DEFAULT '{}'::jsonb,

  status        SMALLINT       NOT NULL DEFAULT 1,
  submitter     VARCHAR(64)    NOT NULL,
  submitted_at  TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
  -- SLA 只存截止时刻，「超时」是查询时算出来的，不额外存状态。
  -- 存状态就要有人定时去翻它 —— 多一个会忘记跑的定时任务。
  sla_due_at    TIMESTAMPTZ(3) NULL,
  assignee      VARCHAR(64)    NULL,
  decided_by    VARCHAR(64)    NULL,
  decided_at    TIMESTAMPTZ(3) NULL,
  decision_note TEXT           NULL,
  CONSTRAINT pk_approval PRIMARY KEY (request_id)
);

-- 「待我审批」：按受理人 + 状态过滤，按 SLA 紧迫程度排序
CREATE INDEX idx_approval_pending ON tc_approval (status, sla_due_at);
CREATE INDEX idx_approval_assignee ON tc_approval (assignee, status);
-- 「我提交的」
CREATE INDEX idx_approval_submitter ON tc_approval (submitter, submitted_at DESC);
CREATE INDEX idx_approval_target ON tc_approval (target_id);

COMMENT ON COLUMN tc_approval.type   IS '类型 1=RULE_EXCEPTION（规范例外）2=CASE_CHANGE（案例变更）3=DATASET_RELEASE（数据集发布）';
COMMENT ON COLUMN tc_approval.status IS '状态 1=PENDING 2=APPROVED 3=REJECTED 4=HOLD（挂起）';

COMMIT;
