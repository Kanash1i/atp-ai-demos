-- V2 · 平台扩展：第三个项目、移动端模块、系统用户
-- 承接 demo2-atp-cli/migrations/V0 与 V1。方言：PostgreSQL。
--
-- 沿用 demo2 定下的两条约定：
--   · 枚举一律存 SMALLINT，含义由应用层 Java enum 持有（D-112）
--   · 不建外键约束，只建索引，引用完整性由写入方保证（D-109）

BEGIN;

-- ── 第三个项目：移动端 H5 ────────────────────────────────────
-- 前端设计稿的项目切换器有三个 pill，V0 只造了两个。
INSERT INTO tc_project (project_id, project_code, project_name) VALUES
  ('P003', 'MOBILE', 'モバイル H5 / 移动端 H5')
ON CONFLICT (project_id) DO NOTHING;

-- ── 移动端的模块 ──────────────────────────────────────────────
-- ⚠️ module_code 是**全局唯一**的（uk_module_code），因为 case_code 的规范是
--    ATP-{MODULE}-{4位序号}（STD-007），MODULE 段直接取 module_code。
--    所以移动端不能复用 LOGIN / CART 这些 code —— 复用会让两个项目的案例编号撞车。
--    加 M 前缀区分：MLOGIN / MSEARCH / MCART / MORDER。
--
--    ⚠️ 设计稿把「用户中心 M005」同时画在电商主站和管理后台下，把 M001~M004 画在移动端下。
--       那是展示上的简写，与本 schema 不一致。**以 schema 为准**，前端按这里的归属调整。
INSERT INTO tc_module (module_id, project_id, module_code, module_name) VALUES
  ('M009', 'P003', 'MLOGIN',  'ログイン（H5）/ 移动端登录'),
  ('M010', 'P003', 'MSEARCH', '検索（H5）/ 移动端搜索'),
  ('M011', 'P003', 'MCART',   'カート（H5）/ 移动端购物车'),
  ('M012', 'P003', 'MORDER',  '注文（H5）/ 移动端订单')
ON CONFLICT (module_id) DO NOTHING;

-- ── 关于 browser 与 timeout_sec：**刻意不加**  ────────────────
-- 00-SHARED-CONTEXT.md §1.2 的领域模型里有这两列，V0__baseline_legacy.sql 没建 —— 那是有意的。
--
-- ⭐ browser 是**执行参数**，不是案例属性。
--    案例本身由 case_type 区分平台（1=IOS 2=ANDROID 3=PC_WEB），
--    而「用哪个浏览器跑」只对 PC_WEB 有意义，且同一条案例本来就该能在 Chrome 和 Firefox 上各跑一遍。
--    把它钉在 tc_case 上，既表达不了这件事，也逼着 iOS / Android 案例带一个没有意义的列。
--    正位是 exec_run.browser / exec_task.browser（V3 已建）—— 派发时指定。
--
-- ⭐ timeout_sec 不做。案例级超时没有确定的消费方（执行器按步骤的 wait_timeout_sec 走），
--    多一列就多一处要维护、要校验、要在 UI 上解释的东西。
--
-- 种子 JSON 里的这两个字段在导入时**丢弃**，见 SeedImporter。

-- ── 系统用户 ──────────────────────────────────────────────────
-- Sa-Token 认证用。演示数据里的三个人来自前端设计稿。
CREATE TABLE IF NOT EXISTS sys_user (
  user_id       VARCHAR(36)    NOT NULL,
  username      VARCHAR(64)    NOT NULL,
  display_name  VARCHAR(100)   NOT NULL,
  password_hash VARCHAR(128)   NOT NULL,
  role          SMALLINT       NOT NULL DEFAULT 1,
  avatar_text   VARCHAR(4)     NULL,
  created_at    TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
  CONSTRAINT pk_sys_user       PRIMARY KEY (user_id),
  CONSTRAINT uk_sys_user_name  UNIQUE (username)
);

COMMENT ON COLUMN sys_user.role        IS '角色 1=QA_ENGINEER 2=REVIEWER（可审批）3=ADMIN';
COMMENT ON COLUMN sys_user.avatar_text IS '头像里显示的两个字母，前端设计稿用的是姓名首字母';

COMMIT;
