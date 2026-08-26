-- V0 · 老平台现状基线（不执行到生产，仅供测试与 code review 对照）
-- 来源：00-SHARED-CONTEXT.md §1.1。字段与类型逐条照抄，不要在这里"顺手改进"。

CREATE TABLE tc_case (
  case_id      VARCHAR(32)  NOT NULL COMMENT '平台生成的雪花 ID',
  case_code    VARCHAR(64)  NOT NULL COMMENT '业务编号 ATP-{MODULE}-{4位}',
  title        VARCHAR(200) NOT NULL,
  module_id    VARCHAR(32)  NOT NULL,
  priority     ENUM('P0','P1','P2','P3') NOT NULL,
  author       VARCHAR(64)  NOT NULL,
  precondition TEXT         NULL,
  status       ENUM('DRAFT','ACTIVE','DEPRECATED') NOT NULL DEFAULT 'DRAFT',
  browser      ENUM('CHROME','FIREFOX','EDGE')     NOT NULL DEFAULT 'CHROME',
  timeout_sec  INT          NOT NULL DEFAULT 30,
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (case_id),
  UNIQUE KEY uk_case_code (case_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE tc_module (
  module_id   VARCHAR(32)  NOT NULL,
  module_code VARCHAR(32)  NOT NULL,
  module_name VARCHAR(100) NOT NULL,
  PRIMARY KEY (module_id),
  UNIQUE KEY uk_module_code (module_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO tc_module VALUES
  ('M001','LOGIN','登录'), ('M002','SEARCH','搜索'), ('M003','CART','购物车');
