-- V8 · 机器主体与窄 token
--
-- 解决的问题：CLI 现在直连 PostgreSQL，数据库凭证发到了每一台装 opencode 的客户电脑上。
-- 这不是理论风险 —— demo2 的 DECISIONS D-123 记了一次真实事故：
-- agent 想删草稿、发现没有对应工具，就直接去读 .env 拼 SQL 了。
--
-- ⚠️ PG 一旦上云，这条就从「该改」变成「必须先改」：
--    数据库凭证发到每台客户电脑 + 数据库对公网开放，这个组合不能上线。
--
-- 目标形态：CLI 只持 ATP 的窄 token，数据库凭证只有平台持有。
-- 让 agent 那一层**根本拿不到**数据库密码，而不是靠约束它不要去拿。

BEGIN;

-- ── 机器主体 ──────────────────────────────────────────────────
-- ⚠️ 不复用 sys_user：那张表是「人」，有姓名、角色、部门。
--    机器主体没有这些，却需要 secret 轮换、scope 白名单、吊销 —— 字段几乎不重合。
--    混在一张表里，「这一行是人还是程序」就得靠一个 type 列去猜，查询处处要带条件。
CREATE TABLE sys_api_client (
  client_id      VARCHAR(64)    NOT NULL,
  client_name    VARCHAR(100)   NOT NULL,

  -- ⚠️ 存 hash 不存明文。secret 只在创建时返回一次，之后平台自己也读不出来 ——
  --    读得出来的密钥迟早会被打进日志、备份、或者某次 SELECT * 的截图里。
  secret_hash    VARCHAR(128)   NOT NULL,
  secret_salt    VARCHAR(32)    NOT NULL,

  -- 权限白名单，逗号分隔。⭐ 这里是「窄」token 的落点：
  --   case:write     写案例（draft / update / commit）
  --   exec:run-once  跑单条自验 —— **不含派发批次**，那是平台调度，不该发给客户端
  --   inspect        页面探查
  scopes         VARCHAR(500)   NOT NULL DEFAULT '',

  enabled        BOOLEAN        NOT NULL DEFAULT TRUE,
  -- 吊销不删行：删了就查不出「这个 client 曾经存在过、做过什么」
  revoked_at     TIMESTAMPTZ(3) NULL,
  last_used_at   TIMESTAMPTZ(3) NULL,
  created_at     TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
  created_by     VARCHAR(64)    NULL,
  CONSTRAINT pk_api_client PRIMARY KEY (client_id)
);

CREATE INDEX idx_api_client_enabled ON sys_api_client (enabled);

COMMENT ON TABLE  sys_api_client            IS '机器主体（CLI 等程序）。人的账号在 sys_user';
COMMENT ON COLUMN sys_api_client.scopes     IS '权限白名单，逗号分隔。case:write / exec:run-once / inspect';
COMMENT ON COLUMN sys_api_client.secret_hash IS 'SHA-256(salt + secret)。明文只在创建时返回一次';
COMMENT ON COLUMN sys_api_client.revoked_at IS '吊销时刻。吊销不删行 —— 删了就查不出它曾做过什么';

COMMIT;
