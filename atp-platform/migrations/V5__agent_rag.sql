-- V5 · Agent 会话与 RAG（pgvector）
--
-- ⚠️ 需要 pgvector 扩展。演示库的镜像必须是 pgvector/pgvector，不是 postgres:17
--    （scripts/demo-db.sh 已相应调整）。

BEGIN;

CREATE EXTENSION IF NOT EXISTS vector;

-- ══ Agent 侧 ═══════════════════════════════════════════════════

-- ── Agent 工作记忆：**不建表** ───────────────────────────────
-- AgentScope 1.0.12 自带 Session 实现：InMemorySession / JsonSession / MysqlSession /
-- RedisSession（Jedis、Lettuce、Redisson 三套适配）。没有 PG 版。
--
-- ⭐ 用 RedisSession，不自己写 PG 适配器：
--    · Redis 本来就在栈里（中断广播、工具熔断、执行队列都要它），不新增依赖
--    · agent 的多轮工作记忆是短生命周期的热状态，放 Redis 比放关系库合理
--    · 少一个要自己维护的框架适配层
--
-- ⚠️ 注意区分：这里说的是「下一轮喂给模型的上下文」。
--    给人看的会话列表与历史消息是另一回事，在下面两张表里，那才是持久化真相。

-- ── 前端聊天历史 ──────────────────────────────────────────────
-- ⚠️ 这与上面的 agent 工作记忆是**两回事**：
--    Redis 里的 Session 是下一轮喂给模型的上下文；这两张表是给人看的会话列表和历史页面。
--    混为一谈会导致「删了一条历史消息，模型却还记得」这类难解释的现象。
CREATE TABLE ag_conversation (
  conversation_id VARCHAR(36)    NOT NULL,
  user_id         VARCHAR(36)    NOT NULL,
  title           VARCHAR(200)   NULL,
  created_at      TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
  deleted         SMALLINT       NOT NULL DEFAULT 0,
  CONSTRAINT pk_ag_conversation PRIMARY KEY (conversation_id)
);
CREATE INDEX idx_conv_user ON ag_conversation (user_id, updated_at DESC);

CREATE TABLE ag_message (
  message_id      VARCHAR(36)    NOT NULL,
  conversation_id VARCHAR(36)    NOT NULL,
  role            SMALLINT       NOT NULL,
  content         TEXT           NULL,
  -- 前端的时间轴展示要按执行顺序还原「思考 → 进度 → 工具结果 → 回复」，
  -- 这些结构化片段跟正文一起存，刷新页面后才能重放出同样的时间轴。
  timeline_json   JSONB          NULL,
  agent_name      VARCHAR(64)    NULL,
  created_at      TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
  CONSTRAINT pk_ag_message PRIMARY KEY (message_id)
);
CREATE INDEX idx_msg_conv ON ag_message (conversation_id, created_at);

COMMENT ON COLUMN ag_message.role IS '角色 1=USER 2=ASSISTANT 3=SYSTEM 4=TOOL';

-- ── 活跃 agent（续跑与中断恢复）────────────────────────────────
CREATE TABLE ag_active_agent (
  session_id  VARCHAR(36)    NOT NULL,
  agent_name  VARCHAR(64)    NOT NULL,
  state_json  JSONB          NULL,
  updated_at  TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
  CONSTRAINT pk_ag_active PRIMARY KEY (session_id)
);

COMMENT ON TABLE ag_active_agent IS '哪个 agent 正停在等用户输入。HITL 场景下用户回话时要续跑的就是它';

-- ══ RAG 侧 ═════════════════════════════════════════════════════

-- ── 语料集 ────────────────────────────────────────────────────
-- 对应前端「数据集中心」的列表：atp-standards-v2 / atp-legacy-cases / …
CREATE TABLE rag_corpus (
  corpus_id       VARCHAR(36)    NOT NULL,
  name            VARCHAR(100)   NOT NULL,
  description     VARCHAR(500)   NULL,
  embedding_model VARCHAR(64)    NOT NULL DEFAULT 'bge-m3',
  chunk_size      INT            NOT NULL DEFAULT 512,
  chunk_overlap   INT            NOT NULL DEFAULT 64,
  docs_count      INT            NOT NULL DEFAULT 0,
  chunks_count    INT            NOT NULL DEFAULT 0,
  status          SMALLINT       NOT NULL DEFAULT 1,
  created_at      TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
  CONSTRAINT pk_rag_corpus      PRIMARY KEY (corpus_id),
  CONSTRAINT uk_rag_corpus_name UNIQUE (name)
);

COMMENT ON COLUMN rag_corpus.status IS '状态 1=INDEXING 2=READY 3=ARCHIVED';

CREATE TABLE rag_document (
  doc_id    VARCHAR(36)  NOT NULL,
  corpus_id VARCHAR(36)  NOT NULL,
  -- 稳定标识，形如 manual/04-定位器指南.md。评估集的 golden_id 以它为前缀
  source_id VARCHAR(200) NOT NULL,
  title     VARCHAR(200) NULL,
  doc_group VARCHAR(32)  NULL,
  CONSTRAINT pk_rag_document PRIMARY KEY (doc_id)
);
CREATE INDEX idx_doc_corpus ON rag_document (corpus_id);

COMMENT ON COLUMN rag_document.doc_group IS 'manual / standards / cases —— 检索时可据此区分手册与规范';

-- ── 切块与向量：**不建表** ───────────────────────────────────
-- AgentScope 1.0.12 自带 PgVectorStore（io.agentscope.core.rag.store），
-- 参数是 jdbcUrl / schema / tableName / dimensions / distanceType，**它自己建表和索引**。
--
-- ⭐ 所以这里不建 rag_chunk：自己再维护一套切块表，就要跟框架的表两头同步，
--    迟早不一致。上面两张表只管**业务元数据**（数据集中心的列表要显示什么），
--    向量与切块正文交给 PgVectorStore 的表（默认名见 atp-rag 的配置）。
--
-- ⚠️ 距离用 COSINE —— bge-m3 的向量是归一化的。
--    维度 1024，与 TEI 上跑的 bge-m3 实测值一致（写死会在换模型时静默出错，从配置读）。

-- ── 检索评估 ──────────────────────────────────────────────────
-- 前端「数据集中心」那张评估卡片读这张表。
-- ⚠️ 这不是消融实验框架 —— 跑一次、填一行、给面试官看一眼即可。
CREATE TABLE rag_eval_run (
  eval_id      VARCHAR(36)    NOT NULL,
  corpus_id    VARCHAR(36)    NOT NULL,
  dataset_name VARCHAR(100)   NOT NULL,
  question_cnt INT            NOT NULL DEFAULT 0,
  recall_at_1  NUMERIC(4,3)   NULL,
  recall_at_3  NUMERIC(4,3)   NULL,
  recall_at_5  NUMERIC(4,3)   NULL,
  mrr          NUMERIC(4,3)   NULL,
  note         VARCHAR(500)   NULL,
  ran_at       TIMESTAMPTZ(3) NOT NULL DEFAULT now(),
  CONSTRAINT pk_rag_eval PRIMARY KEY (eval_id)
);
CREATE INDEX idx_eval_corpus ON rag_eval_run (corpus_id, ran_at DESC);

COMMIT;
