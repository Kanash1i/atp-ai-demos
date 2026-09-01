-- V9 · 人的登录
--
-- M1 时 sys_user 就有 password_hash，但存的是明文 "demo" —— 那时没有登录链路，
-- 它只是个占位。现在要真的校验密码了，补上 salt 并重置为真正的 hash。
--
-- ⚠️ 与机器主体（sys_api_client）用同一套算法：SHA-256(salt + secret)。
--    两种主体的凭据存储方式一致，将来换算法时不会漏掉一边。

BEGIN;

ALTER TABLE sys_user ADD COLUMN password_salt VARCHAR(32) NOT NULL DEFAULT '';

COMMENT ON COLUMN sys_user.password_hash IS 'SHA-256(salt + 明文)。与 sys_api_client 同一套算法';
COMMENT ON COLUMN sys_user.password_salt IS '每个用户独立的盐 —— 共用盐的话，两个人密码相同 hash 也相同，一眼就能看出来';

-- 三个演示账号统一口令，明文写在 .env.example 里（这是虚构的演示数据，不是真实凭据）
--   salt 与 hash 由应用启动时的 UserSeed 写入 —— 不在迁移里硬编码 hash，
--   因为那样改口令就要改迁移文件，而迁移是不该被修改的历史。
UPDATE sys_user SET password_hash = '', password_salt = '' WHERE password_hash = 'demo';

COMMIT;
