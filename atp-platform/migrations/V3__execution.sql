-- V3 · 执行侧：批次、任务、步骤结果、执行节点
--
-- 这一支支撑前端设计稿的「案例执行状态」面板：
--   今日统计 / 执行中的批次（带实时进度）/ 最近执行结果表 / 失败详情
--
-- ⚠️ 执行是**真跑 Playwright**，不是状态机模拟。所以这些表里的
--    duration_ms、video_url、failed_seq 都是执行器回写的真实值。

BEGIN;

-- ── 批次 ──────────────────────────────────────────────────────
-- 一次「派发」产生一条 exec_run 和 N 条 exec_task。
CREATE TABLE exec_run (
  run_id         VARCHAR(36)    NOT NULL,
  run_code       VARCHAR(64)    NOT NULL,
  project_id     VARCHAR(32)    NOT NULL,
  suite_name     VARCHAR(100)   NULL,
  browser        SMALLINT       NOT NULL DEFAULT 1,
  status         SMALLINT       NOT NULL DEFAULT 1,

  -- 计数列冗余存一份，避免每次查看板都对 exec_task 做聚合。
  -- 由执行器在每条任务收尾时增量更新 —— 看板是高频读、低频写的典型。
  total_count    INT            NOT NULL DEFAULT 0,
  passed_count   INT            NOT NULL DEFAULT 0,
  failed_count   INT            NOT NULL DEFAULT 0,
  skipped_count  INT            NOT NULL DEFAULT 0,
  running_count  INT            NOT NULL DEFAULT 0,

  -- ⭐ 两条 AI 赋能路线在这一列上分叉，看板可以直接按它分组统计：
  --    人工派发 / agent 派发 / 定时任务，跑的是同一套执行器。
  trigger_source SMALLINT       NOT NULL DEFAULT 1,
  created_by     VARCHAR(64)    NULL,
  started_at     TIMESTAMPTZ(3) NULL,
  finished_at    TIMESTAMPTZ(3) NULL,
  created_at     TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
  CONSTRAINT pk_exec_run      PRIMARY KEY (run_id),
  CONSTRAINT uk_exec_run_code UNIQUE (run_code)
);
CREATE INDEX idx_run_project ON exec_run (project_id, created_at DESC);
CREATE INDEX idx_run_status  ON exec_run (status);

COMMENT ON COLUMN exec_run.run_code       IS '展示用批次号 RUN-YYYYMMDD-NNNN';
COMMENT ON COLUMN exec_run.browser        IS '浏览器 1=CHROME 2=FIREFOX 3=EDGE（与 tc_case.browser 同码）';
COMMENT ON COLUMN exec_run.status         IS '批次状态 1=PENDING 2=RUNNING 3=DONE 4=ABORTED';
COMMENT ON COLUMN exec_run.trigger_source IS '触发来源 1=MANUAL 2=AGENT 3=SCHEDULED';

-- ── 单条案例的执行 ────────────────────────────────────────────
CREATE TABLE exec_task (
  task_id        VARCHAR(36)    NOT NULL,
  run_id         VARCHAR(36)    NOT NULL,
  case_id        VARCHAR(36)    NOT NULL,
  -- case_code 与 title 在这里**冗余一份快照**：案例后来被改名或删除，
  -- 历史执行记录仍要能显示当时跑的是什么。执行记录是不可变的事实。
  case_code      VARCHAR(64)    NOT NULL,
  case_title     VARCHAR(200)   NULL,
  browser        SMALLINT       NOT NULL DEFAULT 1,
  node_name      VARCHAR(32)    NULL,
  status         SMALLINT       NOT NULL DEFAULT 1,
  duration_ms    INT            NULL,
  error_msg      TEXT           NULL,
  -- 失败落在第几步。前端「点 FAIL 进失败详情」靠它直接定位，不用扫全部步骤结果
  failed_seq     INT            NULL,
  video_url      VARCHAR(500)   NULL,
  screenshot_url VARCHAR(500)   NULL,
  trace_url      VARCHAR(500)   NULL,
  queued_at      TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
  started_at     TIMESTAMPTZ(3) NULL,
  finished_at    TIMESTAMPTZ(3) NULL,
  CONSTRAINT pk_exec_task PRIMARY KEY (task_id)
);
CREATE INDEX idx_task_run    ON exec_task (run_id);
CREATE INDEX idx_task_case   ON exec_task (case_id, finished_at DESC);
-- 「最近执行结果 · 显示最近 200 条」走这个索引
CREATE INDEX idx_task_recent ON exec_task (finished_at DESC NULLS FIRST);
-- 执行器从队列取任务：按状态捞 PENDING
CREATE INDEX idx_task_status ON exec_task (status, queued_at);

COMMENT ON COLUMN exec_task.status    IS '任务状态 1=PENDING 2=RUNNING 3=PASSED 4=FAILED 5=SKIPPED 6=ABORTED';
COMMENT ON COLUMN exec_task.video_url IS 'Playwright 录像（webm）。⚠️ 存 URL 不存本地路径 —— 执行节点与查询进程不在同一台机器';

-- ── 步骤级结果 ────────────────────────────────────────────────
-- ⚠️ 单独成表而不是塞进 exec_task 的一个 JSON 列：
--    「失败详情页定位到失败步骤」要按 seq 查、按 status 过滤，塞 JSON 里查不动。
CREATE TABLE exec_step_result (
  result_id      VARCHAR(36)    NOT NULL,
  task_id        VARCHAR(36)    NOT NULL,
  seq            INT            NOT NULL,
  action         VARCHAR(32)    NOT NULL,
  status         SMALLINT       NOT NULL,
  duration_ms    INT            NULL,
  error_msg      TEXT           NULL,
  screenshot_url VARCHAR(500)   NULL,
  CONSTRAINT pk_step_result      PRIMARY KEY (result_id),
  CONSTRAINT uk_step_result_seq  UNIQUE (task_id, seq)
);
CREATE INDEX idx_step_result_task ON exec_step_result (task_id, seq);

COMMENT ON COLUMN exec_step_result.status IS '步骤状态 1=PASSED 2=FAILED 3=SKIPPED';
COMMENT ON COLUMN exec_step_result.action IS 'Action 枚举名，直接存字符串 —— 它是共享契约的一部分，可读性优先';

-- ── 执行节点 ──────────────────────────────────────────────────
-- 前端顶栏的「ENGINE · 6/8 · node-01…node-08」读这张表。
CREATE TABLE exec_node (
  node_id         VARCHAR(36)    NOT NULL,
  node_name       VARCHAR(32)    NOT NULL,
  status          SMALLINT       NOT NULL DEFAULT 1,
  capacity        INT            NOT NULL DEFAULT 1,
  current_task_id VARCHAR(36)    NULL,
  -- 心跳。执行节点是独立进程，进程没了不会来改自己的状态，
  -- 所以「在线」靠心跳时间判断，不靠 status 列本身。
  heartbeat_at    TIMESTAMPTZ(3) NULL,
  registered_at   TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
  CONSTRAINT pk_exec_node      PRIMARY KEY (node_id),
  CONSTRAINT uk_exec_node_name UNIQUE (node_name)
);

COMMENT ON COLUMN exec_node.status       IS '节点状态 1=IDLE 2=BUSY 3=OFFLINE';
COMMENT ON COLUMN exec_node.heartbeat_at IS '最后心跳。判定在线看这个，不看 status —— 进程崩了不会自己改状态';

COMMIT;
