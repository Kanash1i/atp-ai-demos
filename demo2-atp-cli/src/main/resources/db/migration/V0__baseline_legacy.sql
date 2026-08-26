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
  KEY idx_module_project (project_id),
  CONSTRAINT fk_module_project FOREIGN KEY (project_id) REFERENCES tc_project (project_id)
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
  KEY idx_case_module (module_id),
  CONSTRAINT fk_case_module FOREIGN KEY (module_id) REFERENCES tc_module (module_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── 步骤子表 ──────────────────────────────────────────────
CREATE TABLE tc_step (
  step_id   VARCHAR(32) NOT NULL COMMENT '子表主键',
  case_id   VARCHAR(32) NOT NULL COMMENT '父表主键',
  seq       INT         NOT NULL COMMENT '1..n 连续无跳号',
  step_json JSON        NOT NULL COMMENT '步骤内容',
  PRIMARY KEY (step_id),
  -- 同一案例内 seq 不重复，靠唯一键而不是靠应用层自觉
  UNIQUE KEY uk_step_case_seq (case_id, seq),
  -- ⭐ CASCADE 是给清理任务用的：删弃置草稿时步骤必须跟着走，
  --    否则每月清理会留下一堆孤儿步骤行。
  CONSTRAINT fk_step_case FOREIGN KEY (case_id) REFERENCES tc_case (case_id) ON DELETE CASCADE
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
