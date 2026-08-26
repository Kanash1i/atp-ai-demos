-- V0 · 老平台现状基线（不执行到生产，仅供测试与 code review 对照）
-- 来源：00-SHARED-CONTEXT.md §1.2。定位路径：项目 → 模块 → 案例。

-- ── 项目字典 ──────────────────────────────────────────────
CREATE TABLE tc_project (
  project_id   VARCHAR(32)  NOT NULL,
  project_code VARCHAR(32)  NOT NULL,
  project_name VARCHAR(100) NOT NULL,
  PRIMARY KEY (project_id),
  UNIQUE KEY uk_project_code (project_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── 模块字典（隶属于项目）─────────────────────────────────
CREATE TABLE tc_module (
  module_id   VARCHAR(32)  NOT NULL,
  project_id  VARCHAR(32)  NOT NULL,
  module_code VARCHAR(32)  NOT NULL COMMENT '全局唯一，用作 case_code 前缀',
  module_name VARCHAR(100) NOT NULL,
  PRIMARY KEY (module_id),
  UNIQUE KEY uk_module_code (module_code),
  -- 只建索引，不建外键约束（见 DECISIONS D-109）
  KEY idx_module_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── 案例主表 ──────────────────────────────────────────────
CREATE TABLE tc_case (
  case_id      VARCHAR(32)  NOT NULL COMMENT '平台生成的雪花 ID',
  -- 执行平台。老平台本来就按 iOS / Android / Web 区分案例。
  case_type    ENUM('IOS','ANDROID','PC_WEB') NOT NULL DEFAULT 'PC_WEB',
  case_code    VARCHAR(64)  NOT NULL COMMENT '业务编号 ATP-{MODULE}-{4位}',
  title        VARCHAR(200) NOT NULL,
  module_id    VARCHAR(32)  NOT NULL,
  priority     ENUM('P0','P1','P2','P3') NOT NULL,
  author       VARCHAR(64)  NOT NULL,
  precondition TEXT         NULL,
  status       ENUM('DRAFT','ACTIVE','DEPRECATED') NOT NULL DEFAULT 'DRAFT',
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (case_id),
  UNIQUE KEY uk_case_code (case_code),
  -- ⚠️ 只建索引，不建外键约束。
  --    引用完整性由写入方（CLI / 平台）保证 —— module_id 的有效性
  --    在写之前由 atp validate 对着 tc_module 查，不指望数据库兜底。
  KEY idx_case_module (module_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── 步骤子表 ──────────────────────────────────────────────
CREATE TABLE tc_step (
  step_id   VARCHAR(32) NOT NULL COMMENT '子表主键',
  case_id   VARCHAR(32) NOT NULL COMMENT '父表主键',
  seq       INT         NOT NULL COMMENT '1..n 连续无跳号',
  step_json JSON        NOT NULL COMMENT '步骤内容',
  PRIMARY KEY (step_id),
  -- 同一案例内 seq 不重复。这条是【唯一键】不是外键 —— 它约束的是本表内部，
  -- 与"不建外键"不冲突，该由数据库保证的仍然由数据库保证。
  -- 顺带：case_id 是这个联合索引的最左列，删步骤时能走到它，不必再单建索引。
  UNIQUE KEY uk_step_case_seq (case_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── 字典数据（00-SHARED-CONTEXT.md §1.2 的模块表，补上项目归属）──
INSERT INTO tc_project (project_id, project_code, project_name) VALUES
  ('P001','ECSHOP','EC サイト / 通販フロント'),
  ('P002','ADMIN', '管理コンソール / 后台管理');

INSERT INTO tc_module (module_id, project_id, module_code, module_name) VALUES
  ('M001','P001','LOGIN',  'ログイン / 登录认证'),
  ('M002','P001','SEARCH', '検索 / 商品搜索'),
  ('M003','P001','CART',   'カート / 购物车'),
  ('M004','P001','ORDER',  '注文 / 订单管理'),
  ('M005','P001','USER',   'ユーザー管理 / 用户中心'),
  ('M006','P001','PAYMENT','決済 / 支付'),
  ('M007','P002','REPORT', 'レポート / 报表导出'),
  ('M008','P002','ADMIN',  '管理画面 / 后台管理');
