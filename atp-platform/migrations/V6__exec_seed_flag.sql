-- V6 · 标记哪些执行记录是演示种子
--
-- ⚠️ 起因：执行看板的历史数据是按「造数据那一刻的今天」生成的，
--    跨过零点之后「今日执行」就变成 0，看板一夜之间空掉。
--
-- 解决办法是每次启动（以及每天零点）把种子数据按当前时刻重造一遍。
-- 但重造要能**只删种子、不碰真实执行** —— M2 起 Playwright 真跑的记录必须留着。
-- 所以给批次加一个标记，删除时按它筛。
--
-- ⭐ 标记放在 exec_run 而不是 exec_task：一个批次要么整批是种子、要么整批是真跑，
--    不存在混着的情况。放父表少一列冗余，删除时用子查询定位即可。

BEGIN;

ALTER TABLE exec_run
  ADD COLUMN IF NOT EXISTS is_seed SMALLINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN exec_run.is_seed IS '1=演示种子（每次启动会被重造）0=真实执行（Playwright 跑出来的，永不删）';

-- 重造时要按这一列扫全表，量级到几万条时值得有个索引
CREATE INDEX IF NOT EXISTS idx_run_is_seed ON exec_run (is_seed);

COMMIT;
