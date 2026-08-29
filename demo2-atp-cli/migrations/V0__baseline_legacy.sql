-- V0 · 老平台现状基线（不执行到生产，仅供测试与 code review 对照）
-- 来源：00-SHARED-CONTEXT.md §1.2。定位路径：项目 → 模块 → 案例。
-- 方言：PostgreSQL。

-- ⭐ 枚举一律存 SMALLINT，取值含义由应用层的 Java enum 持有，DB 只存码。
--    不用 PG 原生 enum 类型的理由见 DECISIONS D-112：
--    · 加一个新状态不需要任何 DDL（原生 enum 要 ALTER TYPE，还不能在同一事务里用新值）
--    · 迁移脚本因此可以是单个原子事务
--    · 枚举语义本来就该由应用侧和 CLI 持有，DB 存 int 就够
--    代价：SELECT * 出来是数字，可读性差 —— 用 COMMENT 把映射写在列上补偿。

-- ── 项目字典 ──────────────────────────────────────────────
CREATE TABLE tc_project (
  project_id   VARCHAR(32)  NOT NULL,
  project_code VARCHAR(32)  NOT NULL,
  project_name VARCHAR(100) NOT NULL,
  CONSTRAINT pk_project      PRIMARY KEY (project_id),
  CONSTRAINT uk_project_code UNIQUE (project_code)
);

-- ── 模块字典（隶属于项目）─────────────────────────────────
CREATE TABLE tc_module (
  module_id   VARCHAR(32)  NOT NULL,
  project_id  VARCHAR(32)  NOT NULL,
  module_code VARCHAR(32)  NOT NULL,
  module_name VARCHAR(100) NOT NULL,
  CONSTRAINT pk_module      PRIMARY KEY (module_id),
  CONSTRAINT uk_module_code UNIQUE (module_code)
);
-- 只建索引，不建外键约束（见 DECISIONS D-109）
CREATE INDEX idx_module_project ON tc_module (project_id);

COMMENT ON COLUMN tc_module.module_code IS '全局唯一，用作 case_code 前缀';

-- ── 案例主表 ──────────────────────────────────────────────
CREATE TABLE tc_case (
  case_id      VARCHAR(32)    NOT NULL,
  case_type    SMALLINT       NOT NULL DEFAULT 3,
  case_code    VARCHAR(64)    NOT NULL,
  title        VARCHAR(200)   NOT NULL,
  module_id    VARCHAR(32)    NOT NULL,
  priority     SMALLINT       NOT NULL,
  author       VARCHAR(64)    NOT NULL,
  precondition TEXT           NULL,
  status       SMALLINT       NOT NULL DEFAULT 1,
  -- ⚠️ PG 没有 MySQL 的 ON UPDATE CURRENT_TIMESTAMP。
  --    updated_at 由写入方显式赋值（CaseStore 每条 UPDATE 都带 now()），
  --    否则就得挂触发器。这是从 MySQL 迁过来最容易漏的一条。
  created_at   TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
  CONSTRAINT pk_case      PRIMARY KEY (case_id),
  CONSTRAINT uk_case_code UNIQUE (case_code)
);
-- ⚠️ 只建索引，不建外键约束。引用完整性由写入方（CLI / 平台）保证 ——
--    module_id 的有效性在写之前由 atp validate 对着 tc_module 查，不指望数据库兜底。
CREATE INDEX idx_case_module ON tc_case (module_id);

COMMENT ON COLUMN tc_case.case_id   IS '平台生成的雪花 ID';
COMMENT ON COLUMN tc_case.case_code IS '业务编号 ATP-{MODULE}-{4位}';
COMMENT ON COLUMN tc_case.case_type IS '执行平台 1=IOS 2=ANDROID 3=PC_WEB（老平台本来就按平台区分案例）';
COMMENT ON COLUMN tc_case.priority  IS '优先级 0=P0 1=P1 2=P2 3=P3';
COMMENT ON COLUMN tc_case.status    IS '状态 1=DRAFT 2=ACTIVE 3=DEPRECATED';

-- ── 步骤表：与 tc_case 一比一 ──────────────────────────────
-- ⚠️ 一个案例一行，step_json 是【全量步骤数组】，不是一步一行。
--    因为老平台的执行器就是读整份步骤跑，不会按 seq 逐条查库。
--    顺序是数组元素里的 seq key，不抽成列 —— 抽出来只有"一步一行"时才有意义。
CREATE TABLE tc_step (
  step_id   VARCHAR(32) NOT NULL,
  case_id   VARCHAR(32) NOT NULL,
  step_json JSONB       NOT NULL,
  CONSTRAINT pk_step         PRIMARY KEY (step_id),
  -- 一比一由这个唯一键保证。它约束的是本表内部，与"不建外键"（D-109）不冲突。
  CONSTRAINT uk_step_case_id UNIQUE (case_id)
);

COMMENT ON COLUMN tc_step.step_id   IS '子表主键';
COMMENT ON COLUMN tc_step.case_id   IS '父表主键（逻辑外键，无约束）；一比一';
COMMENT ON COLUMN tc_step.step_json IS '全量步骤数组，seq 是元素里的 key';

-- ── 字典数据（00-SHARED-CONTEXT.md §1.2 的模块表，补上项目归属）──
INSERT INTO tc_project (project_id, project_code, project_name) VALUES
  ('P001', 'ECSHOP', 'EC サイト / 通販フロント'),
  ('P002', 'ADMIN',  '管理コンソール / 后台管理');

INSERT INTO tc_module (module_id, project_id, module_code, module_name) VALUES
  ('M001', 'P001', 'LOGIN',   'ログイン / 登录认证'),
  ('M002', 'P001', 'SEARCH',  '検索 / 商品搜索'),
  ('M003', 'P001', 'CART',    'カート / 购物车'),
  ('M004', 'P001', 'ORDER',   '注文 / 订单管理'),
  ('M005', 'P001', 'USER',    'ユーザー管理 / 用户中心'),
  ('M006', 'P001', 'PAYMENT', '決済 / 支付'),
  ('M007', 'P002', 'REPORT',  'レポート / 报表导出'),
  ('M008', 'P002', 'ADMIN',   '管理画面 / 后台管理');
