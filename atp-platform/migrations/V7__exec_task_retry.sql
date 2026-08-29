-- V7 · 任务重试次数
--
-- ⚠️ 起因：执行节点是独立进程，被 kill / 断电 / 断网时，
--    它已认领的任务会**永远挂在 RUNNING** —— 没有任何人会去收尾。
--    表现是批次进度条停在 19/20 等下去，而且不报任何错。
--
-- 回收机制（ZombieTaskReaper）会把这类任务放回队列让别的节点接管，
-- 但必须有次数上限：如果任务本身会让节点崩溃（比如触发了浏览器的某个 bug），
-- 无限重试就是无限崩溃，整个执行池被一条任务拖垮。

BEGIN;

ALTER TABLE exec_task
  ADD COLUMN IF NOT EXISTS retry_count SMALLINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN exec_task.retry_count IS '因节点掉线被重新入队的次数。超过上限直接判失败，避免一条毒任务反复拖垮节点';

-- 回收任务要按 (status, started_at) 扫，量大时值得有索引
CREATE INDEX IF NOT EXISTS idx_task_running ON exec_task (status, started_at);

COMMIT;
