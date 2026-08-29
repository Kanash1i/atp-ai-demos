# ATP 平台 — 设计文档

> 写于 2026-08-29。这份文档取代已删除的 `03-HANDOFF-rag-v2.md`（知识侧换引擎做消融表那条路线）。
> **消融表不再是交付物。** 现在的交付物是一个能在服务器上跑起来、能当面演示的完整平台。

---

## 0. 一句话

做一个 **ATP 自动化测试平台**：既有传统平台该有的功能（案例管理 / 派发执行 / 状态中心 / 录像回放 / 审批中心），
又有一个 **多 agent 协作模块** —— 用自然语言写案例、自己调 Playwright 执行、自己录像。

参考实现是 `~/llmentor/LLMentor/gogo-agent`（多 agent 差旅助手，Spring Boot 3.4 + AgentScope 1.0.12）。
它的骨架直接可用，业务场景整体换成 ATP。

---

## 1. 叙事主线：两条 AI 赋能路线的对照

这是整个仓库存在的理由，面试时先讲这个。

| | **保守路线** | **激进路线** |
|---|---|---|
| 载体 | `demo2-atp-cli/`（Go + cobra + pgx） | 本平台的 agent 模块 |
| 做法 | opencode 用自然语言生成案例，经 `atp` CLI 以**老平台格式**入库 | agent 自己写案例、自己调 Playwright、自己执行录像，走**一套新流程** |
| 对执行器 | **无感知** —— 落库格式与人工案例完全一致，老执行器照跑 | 不经过老执行器 |
| 风险 | 低。改动面只有"多一个写入方" | 高。等于在平台里再造一条执行链路 |
| 卡点 | 幂等消费与防重放（避免 agent 重试写脏数据）。CLI 侧已做一半 | 生成质量、执行稳定性、产物存储 |
| ⚠️ 已知的边界问题 | **数据库密钥不该放在 agent 那一侧** —— 最终 CAS 那套要由本平台提供接口，CLI 改为调接口而不是直连 PG。**现在不做，但表结构要为它留位置** | — |

两条路线**共用同一个 PostgreSQL 库**。这不是省事，是叙事的一部分：
同一份 `tc_case` / `tc_step`，两条路径写进去，可以当场对比落库结果。

> ⚠️ **Qdrant 出局**。向量检索改用 **pgvector**，跟业务表同库。
> 理由：少一个要部署的服务；演示时一个 `docker compose` 起得来；
> 而且 RAG 语料量（15 篇文档 + 80 条案例）根本用不上专用向量库。

---

## 2. 技术栈

照搬 gogo-agent，**数据库换 PostgreSQL**。

| 层 | 选型 | 说明 |
|---|---|---|
| 语言 / 框架 | Java 21 + Spring Boot 3.4 | 与 gogo 一致 |
| Agent 框架 | **AgentScope 1.0.12** (`io.agentscope`) + `agentscope-spring-boot-starter` | ReAct 循环、Hook、Tool、Session、工具暂停恢复全都现成 |
| 模型 | **DeepSeek**，`provider: openai-compatible` | gogo 的 test-env 已验证这条路；三档模型 `fast/strong/stable` 都指向 `deepseek-v4-flash` |
| Embedding / Rerank | 服务机 TEI 两个容器（`:8081` bge-m3 / `:8082` bge-reranker-v2-m3） | 沿用，已部署验证，1024 维 |
| 向量存储 | **pgvector**，经 AgentScope 自带的 `PgVectorStore` | 框架原生支持，不用自己写适配器。替代 Qdrant |
| 持久层 | MyBatis-Plus 3.5.9 + PostgreSQL | 与 demo2 共库 |
| 缓存 / 广播 | Redis | Sa-Token 登录态、agent 中断 Pub/Sub、工具熔断、执行队列，**以及 agent 的多轮工作记忆（`RedisSession`）** |
| 认证 | Sa-Token 1.39.0 | |
| 推流 | WebFlux + SSE | 对话流式、执行进度实时推送 |
| 执行器 | **Playwright for Java** | 真跑、真录像 |
| 前端 | React + Vite + TS（你的设计稿） | 见 §7 |

### ⭐ 查证过的两件事（原本以为要自己写，其实框架都有）

**一、向量库不用换。** 参考实现 gogo-agent 用的是 `InMemoryStore`（进程内，每次启动重新索引 docx）
加 `BailianKnowledge`（阿里云百炼托管），**没有用 Elasticsearch** ——
ES 是同仓库另一个项目 know-engine 用的。
而 AgentScope 1.0.12 的 `io.agentscope.core.rag.store` 里自带
`PgVectorStore` / `ElasticsearchStore` / `QdrantStore` / `MilvusStore` / `InMemoryStore`，
**pgvector 开箱即用**（参数：`jdbcUrl` / `schema` / `tableName` / `dimensions` / `distanceType`，
它自己建表和索引）。所以 PG 不但不用换掉，还是这里成本最低的选择。

**二、Session 用 `RedisSession`，不写 PG 适配器。**
AgentScope 的 Session 实现有 `InMemorySession` / `JsonSession` / `MysqlSession` /
`RedisSession`（Jedis、Lettuce、Redisson 三套适配），确实没有 PG 版 ——
但 Redis 本来就在栈里，而 agent 的多轮工作记忆是短生命周期的热状态，放 Redis 比放关系库合理。
**原计划里「M0 的阻塞项 PgSession」因此直接取消。**

> ⚠️ 由此产生的两处 schema 简化：`ag_session` 与 `rag_chunk` 两张表都**不建** ——
> 前者交给 Redis，后者交给 `PgVectorStore` 自建。
> 自己再维护一套，就要跟框架的表两头同步，迟早不一致。

### ⚠️ 从 gogo 拆掉的东西

| 拆掉 | 理由 |
|---|---|
| 百炼长期记忆（`AGENT_CONTROL` 模式） | 要阿里云 AK/SK 和记忆库 ID。ATP 场景不需要跨会话用户画像 |
| DashScope SDK | 模型统一走 DeepSeek openai-compatible |
| 5 个外部 Skill（tuniu-cli / flyai 等） | 全是差旅业务的外部 CLI，与 ATP 无关 |
| weather / orizn-visa MCP | 同上 |
| 敏感信息脱敏（houbb sensitive） | 演示数据全是虚构的，没有真实 PII |

> ⚠️ LLMentor 的父 `pom.xml` 里有原作者的**真实 DashScope API key**，
> `application.yml` 里有 MySQL 密码 `Hollis666`。**一个都不要带进本仓库。**
> 所有配置读仓库根 `.env`，代码里不得出现硬编码 key / URL / IP（这条红线不变）。

---

## 3. 模块划分

```
atp-platform/                     ← 新增的 Maven 多模块工程
├── atp-common/                   领域模型、Action/STD 枚举、校验器、配置加载
├── atp-platform-api/             传统平台功能：项目/模块/案例 CRUD、审批、执行记录查询
├── atp-agent/                    多 agent 协作层（AgentScope）
├── atp-rag/                      pgvector 检索 + TEI embedding/rerank
├── atp-runner/                   Playwright 执行器（独立 Spring Boot 应用）
├── atp-web/                      启动模块：REST + SSE + Sa-Token，聚合以上
└── mock-shop/                    被测 mock 站点（静态页 + 少量 JS）
```

**为什么 `atp-runner` 独立成一个应用**：它要拉起真实浏览器，内存和 CPU 占用与主应用完全不同量级；
演示时"执行节点"这个概念（设计稿里的 node-01…node-08）需要能横向起多个进程才立得住。
主应用与 runner 之间通过 **PG 表 + Redis 队列**通信，不是 HTTP 直调 —— 这样 runner 挂了任务还在队列里。

> ⚠️ **`mock-shop` 是红线要求的产物**：不抓取、不爬取任何真实站点，被测页面一律本地 mock。
> 80 条存量案例的定位器指向 `shop.local`，**以 XPath 为主**（其中 4 条是刻意留下的
> `/html/body/...` 绝对路径，3 条依赖框架生成的动态 id）。mock 站要照着它们造，
> 否则存量案例一条都跑不起来 —— 这也是 §9 待拍板的第 3 条。

---

## 3.1 中间件（`infra/`）

两条路线的中间件统一在一个 compose project 里，**全部跑在台式机 192.168.0.101**：

| 服务 | 端口 | 容器 |
|---|---|---|
| PostgreSQL + pgvector | `25432` | `atp-infra-postgres` |
| Redis | `25379` | `atp-infra-redis` |

`./infra/infra.sh up` 一条命令起容器、按顺序应用两处的六支迁移（CLI 的 V0/V1 + 平台的 V2~V5）、
把连接串写进仓库根 `.env`。**迁移必须一起应用** —— 两条路线共用一个库，
分开跑的话谁先起库谁的表才在，另一边要到运行时才发现表不存在。

> ⚠️ **端口刻意选在 254xx / 253xx。** 台式机上停着十几个容器（know-engine、rhd、dodo-agent、gogo-test…），
> 它们没在跑但 `HostConfig.PortBindings` 里写着端口，随时会被拉起来撞车。
> `./infra/infra.sh ports` 会把「所有容器配置的端口」和「Windows 实际监听的端口」都列出来。
>
> ⚠️ 数据卷用 named volume，不用 bind mount —— 宿主是 Windows + WSL2 backend，
> bind 一个 Windows 路径有路径转换和权限问题，IO 也明显更慢。

---

## 4. 数据库设计（PostgreSQL，与 demo2 共库）

### 4.1 已有的（demo2 的 `migrations/`，不动）

`tc_project` / `tc_module` / `tc_case` / `tc_step`

保持 demo2 定下的两条约定：
- **枚举一律存 `SMALLINT`**，含义由应用层 Java enum 持有（D-112）
- **不建外键约束**，只建索引，引用完整性由写入方保证（D-109）

⚠️ 设计稿里有**三个项目**（电商主站 / 管理后台 / 移动端 H5），而 `V0__baseline_legacy.sql` 只有两个。
`V2` 补了 `P003 MOBILE` 与四个移动端模块。

> 移动端的 `module_code` 用 `MLOGIN` / `MSEARCH` / `MCART` / `MORDER` 而不是复用 `LOGIN` 等：
> `module_code` 全局唯一（`case_code` 规范 `ATP-{MODULE}-{4位}` 直接取它），复用会让两个项目的案例编号撞车。
> 这是最省事的解法 —— 不动既有 schema，也不改编号规范。

#### ⭐ `browser` 与 `timeout_sec`：**刻意不加**

共享契约 §1.2 的领域模型里有这两列，`V0` 没建 —— 那是有意的，不是遗漏。

- **`browser` 是执行参数，不是案例属性。** 案例由 `case_type` 区分平台（IOS / ANDROID / PC_WEB），
  而「用哪个浏览器跑」只对 PC_WEB 有意义，且同一条案例本来就该能在 Chrome 和 Firefox 上各跑一遍。
  钉在 `tc_case` 上既表达不了这件事，还逼着 iOS / Android 案例带一个没有意义的列。
  正位是 `exec_run.browser` / `exec_task.browser` —— **派发时指定**。
- **`timeout_sec` 不做。** 没有确定的消费方（执行器按步骤的 `wait_timeout_sec` 走），
  多一列就多一处要维护、要校验、要在 UI 上解释的东西。

种子 JSON 里的这两个字段在导入时丢弃。前端案例详情页的 BROWSER / TIMEOUT 两格要相应调整
（改成显示最近一次执行用的浏览器，或者去掉）。

> ⚠️ iOS / Android 的执行链路**不实现** —— 演示只跑 PC Web。
> 但字段设计要经得起「如果要做」的追问，所以 `case_type` 留着，browser 不进案例表。

### 4.2 新增：执行侧

```sql
exec_run          批次      run_id, project_id, suite_name, browser, status,
                            total/passed/failed/skipped/running, started_at, finished_at, created_by,
                            trigger_source  -- 1=人工 2=AGENT 3=定时  ← 两条路线在这里分叉可见
exec_task         单条执行   task_id, run_id, case_id, case_code, browser, node_name, status,
                            duration_ms, started_at, finished_at, error_msg,
                            video_url, screenshot_url, trace_url, failed_seq
exec_step_result  步骤结果   result_id, task_id, seq, action, status, duration_ms,
                            error_msg, screenshot_url
exec_node         执行节点   node_id, node_name, status, current_task_id, heartbeat_at, capacity
```

`exec_step_result` 单独成表而不是塞进 `exec_task` 的 JSON 里 ——
设计稿的「点 FAIL 进失败详情，定位失败步骤」要按 seq 查，塞 JSON 里查不动。

### 4.3 新增：审批

```sql
tc_approval  request_id, type, target_id, title, summary,
             payload_json,          -- 三类审批的差异全在这里：before/after diff、STD 违反明细、语料集元信息
             status,                -- 1=PENDING 2=APPROVED 3=REJECTED 4=HOLD
             submitter, submitted_at, sla_due_at,
             decided_by, decided_at, decision_note
```

`type`：`1=RULE_EXCEPTION`（规范例外）/ `2=CASE_CHANGE`（案例变更）/ `3=DATASET_RELEASE`（数据集发布）
—— 与设计稿审批中心的三类卡片一一对应。SLA 超时靠 `sla_due_at` 算，不额外存状态。

### 4.4 新增：Agent 与对话

```sql
-- ⚠️ agent 的工作记忆不在这里，在 Redis（RedisSession）。见 §2。
ag_conversation  会话      conversation_id, user_id, title, created_at
ag_message       消息      message_id, conversation_id, role, content,
                           progress_json, thinking_json, timeline_json  -- 前端时间轴要用
ag_active_agent  活跃 agent 状态，支持续跑与中断恢复
```

### 4.5 新增：RAG（pgvector）

```sql
rag_corpus    语料集   corpus_id, name, description, docs_count, chunks_count,
                       embedding_model, status  -- READY / INDEXING / ARCHIVED
rag_document  文档     doc_id, corpus_id, source_id, title, doc_group
-- ⚠️ 切块与向量表**不建** —— AgentScope 的 PgVectorStore 自己建表和索引（见 §2）。
--    这两张只管业务元数据：数据集中心的列表要显示什么。
rag_eval_run  评估     eval_id, corpus_id, dataset_name, recall_at_1/3/5, mrr, ran_at
```

`rag_eval_run` 保留是因为**设计稿的数据集中心有那张评估卡片**（recall@1/3/5、MRR）。
但它现在是**展示用的一张表**，不是要跑消融实验 —— 数字来自一次评估运行，跑一次填进去就行。

### 4.6 新增：用户

`sys_user`：Sa-Token 认证用。设计稿里有三个人（金城 悠人 / 佐藤 美咲 / 田中 直樹），种子数据照这个造。

---

## 5. Agent 层设计

骨架照搬 gogo：`ChatController → ChatAgentExecutor → AgentPipelineService → 三层意图识别 → 直跳子 Agent 或 MasterAgent → ReAct + Tool → Hook 落库`

### 5.1 意图分类（`IntentCategory`）

| 意图 | 例子 | 目标 Agent |
|---|---|---|
| `CASE_AUTHORING` | 「给购物车加个优惠券叠加上限的案例」 | CaseAuthoringAgent |
| `CASE_QUERY` | 「找找登录模块 P0 的案例」 | CaseQueryAgent |
| `EXECUTION` | 「把回归套件跑一遍」「刚才那批跑完了吗」 | ExecutionAgent |
| `KNOWLEDGE` | 「CLICK 该用什么 wait_strategy」 | KnowledgeAgent（RAG） |
| `APPROVAL` | 「这条要申请 SLEEP 例外」 | ApprovalAgent |
| `UNKNOWN` | | MasterAgent 兜底 / 拒答 |

### 5.2 三层意图识别（照搬 gogo 的 L1/L2/L3，命中即短路）

| 层 | 实现 | 手段 |
|---|---|---|
| L1 | `IntentRuleMatcher` | 关键词 / 正则。ATP 场景关键词很硬（"执行""跑一遍""新建案例""规范"），命中率会比差旅场景高 |
| L2 | `IntentVectorMatcher` | 种子语料 `intent-seed.yml` → TEI embedding → **pgvector** Top-1（gogo 用的是 DashScope + 内存库） |
| L3 | `IntentRecognitionAgent` | LLM 兜底，单次 `Model.stream`，不进 ReAct 循环 |

加上 gogo 那两条优化：**查询重写条件触发**（L1/L2 命中就跳过改写）、**单意图高置信直跳子 Agent**（跳过 MasterAgent）。
这两条是能讲的点 —— 省掉两次 LLM 调用。

### 5.3 子 Agent 与工具

| Agent | 职责 | 关键工具 |
|---|---|---|
| **MasterAgent** | 总路由、多意图编排、结果整合 | 子 agent 作为 tool 挂载 |
| **CaseAuthoringAgent** | 自然语言 → 案例草稿 | `query_module`（查模块字典）、`search_similar_cases`（RAG 找存量参考）、`query_standards`（查规范）、`save_case_draft`（写 `AI_DRAFT`）、`ask_user`（HITL 确认） |
| **StandardsCheckAgent** | 对草稿跑 STD-001~008 | `validate_case` —— 返回 ERROR/WARN/INFO 明细 |
| **CaseQueryAgent** | 查案例 | `query_cases`（按模块/优先级/状态过滤） |
| **ExecutionAgent** | 派发、查状态、取录像 | `dispatch_run`、`query_run_status`、`get_video_url`、`abort_run` |
| **KnowledgeAgent** | 规范/手册问答 | pgvector 检索 + rerank，返回引用 chip |
| **ApprovalAgent** | 提交/查询审批 | `submit_approval`、`query_approvals` |
| **QueryRewritingAgent** | 指代消解、上下文补全 | — |

### 5.4 Hook

从 gogo 直接迁移：`ProgressNotifierHook`（SSE）、`SessionPersistenceHook`（改 PG）、
`ActiveAgentPersistenceHook`、`AgentExecutionRegistryHook`（优雅中断）、`AgentExecutionLoggerHook`、
`ToolCircuitBreakerHook`（熔断）。

**新增一个 ATP 特色的**：`StandardsGateHook` —— 案例落库前**强制**跑一遍 STD 校验，
ERROR 直接拦下，不给 LLM 绕过的机会。

> 这个 Hook 是激进路线里最该讲的一块：**规则是硬的，LLM 只在规则之内自由。**
> 它跟保守路线里 CLI 的校验器是同一套规则的两处实现 —— 面试时正好对照着讲。

### 5.5 Human-in-the-Loop

案例生成完**不直接落 ACTIVE**，走 `AI_DRAFT` → 用户在前端确认/改 → commit。
这正好接上 demo2 已经设计好的 `tc_step` 单表单行 CAS（`05-CLI-并发幂等答辩稿.md` §3.5）。

---

## 6. 执行器（真 Playwright + 真录屏）

### 6.1 参与者与职责

| | 部署在 | 职责 |
|---|---|---|
| **atp-web** 主应用 | 云服务器 | 派发、补偿扫描、看板查询 |
| **PostgreSQL** | 云服务器 | **任务状态的唯一真相** |
| **Redis** | 云服务器 | 队列。只放 taskId，只做「有活儿了」的通知 |
| **atp-runner** ×N | 台式机 | 认领、执行、回写。**一进程一节点** |
| **mock-shop** | 台式机 | 被测站点，必须与 runner 同机 |

> ⚠️ mock-shop 与 runner 同机不是图省事：浏览器访问被测页面要是绕 Tailscale 回云端，
> 每个 `OPEN_URL` 都多几十毫秒，家里网络一抖执行就失败 ——
> 那会让「执行失败」变成噪音，掩盖真正的用例失败。

### 6.2 完整链路

```
[主应用]                [Redis]          [执行节点]              [PG]
   │
   │─── ① 派发 ──────────────────────────────────────────────────►│
   │    INSERT exec_run(RUNNING) + N × exec_task(PENDING)
   │─── LPUSH taskIds ──►│
   │                     │
   │                     │◄─ ② BRPOP 阻塞5s ─┤
   │                     │                   ├─ CAS 认领 ────────►│
   │                     │                   │  WHERE status=PENDING
   │                     │                   │  affected=0 → 被抢走，跳过
   │                     │                   │
   │                     │                   ├─ ③ 执行           │
   │                     │                   │  读 tc_case+tc_step ◄┤
   │                     │                   │  Playwright 逐步跑
   │                     │                   │  录像 800×600 / 失败截图
   │                     │                   │
   │                     │                   ├─ ④ 回写（一个事务）►│
   │                     │                   │  task + steps + 计数+1
   │                     │                   │  计数凑齐 → 批次 DONE
   │                     │                   └─ 心跳 30s ────────►│
   │
   │─── ⑤ 补偿扫描 30s ───────────────────────────────────────────►│
   │    RUNNING 僵尸 / PENDING 孤儿 → 改状态
   │─── LPUSH（**必须在事务提交后**）──►│
```

**① 派发** —— 先落库、后入队。反过来的话，节点会拿到一个还没落库的任务号，那是查不到也补不回来的。

**② 认领** —— ⭐ 仲裁点就是那句 `WHERE status = PENDING`。受影响行数 1 = 抢到，0 = 别人先拿了。
**不需要分布式锁** —— 数据库的行锁本来就提供这个语义，再加一层 Redis 锁只是多一个会失效、会过期、会脑裂的组件。
队列因此可以放心重复投递，这是整套设计能容忍各种不一致的基础。

> 这是本仓库同一个思路的第三次应用：demo2 CLI 的案例落库（幂等键做主键 + CAS）、
> 审批的并发决策、以及这里。共同点是**把仲裁交给已经存在的唯一性约束**，而不是引入新的协调者。

**③ 执行** —— 节点从 PG 读案例（消息里只有 id），Playwright 跑，产物落盘。

**④ 回写** —— 三张表在一个事务里。计数用 `SET x = x + 1` 数据库侧自增，
不是「读出来 +1 再写回去」—— 后者在多节点同时收尾时会丢更新，而且丢多少完全看并发时机，事后对不上账。
批次是否跑完也在 SQL 里判断，避免最后两条同时收尾时谁都不去收尾。

**⑤ 补偿** —— 见 §6.3。

### 6.3 补偿：两类扫描，证据不同

异常有两种形态，判定依据不一样：

**RUNNING 僵尸** —— 节点被 kill / 断电 / 断网。任务卡在 RUNNING，没有任何人有责任去收尾。

判定要**两个条件同时成立**：任务已跑超 90 秒 **且** 它所在节点的心跳过期。
只看时间会误伤正常的慢案例（导致重复执行）；只看心跳会在节点重启的瞬间抢走它刚认领的任务。

重投带次数上限（默认 2）：如果某条任务本身会让节点崩溃，无限重试就是无限崩溃，
一条毒任务能把整个执行池拖垮。超限直接判失败，把问题暴露在看板上。

**PENDING 孤儿** —— 任务状态是待执行，但**队列里没有它的号**，没有任何人会再推它。
Redis 重启丢消息、内存淘汰、或者代码 bug 让消息发早了被丢弃，都会产生它。

判定：PENDING 超 60 秒没被认领 → 重投。重复投递由②的 CAS 兜底，无害。

> ⭐ **这第二类是「Redis 只做通知、PG 是真相」这个设计的必要配套。**
> 没有它，那句话就只是一句好听的注释 —— 实测中就是因为缺了它，任务永远躺在那儿没人动。

### 6.4 ⚠️ 实测踩到的坑（都不报错）

| 坑 | 阶段 | 表现 |
|---|---|---|
| **事务内发消息** | ⑤ | `UPDATE` 未提交，`LPUSH` 已可见 → 节点取到任务号，认领时看到的还是旧状态 → 静默丢弃。**日志里明明写着「已重投」，任务却永远躺着**。入队必须在事务提交之后 |
| 异常从日志渲染里逃逸 | ③→④ | `describe()` 写在 try 之外，它内部也要解析占位符 —— 一个「变量未定义」让任务连 FAILED 都记不上，永久挂 RUNNING |
| 录像目录没按任务隔离 | ③ | 只按 `{runCode}/{caseCode}` 分，重跑时新旧录像混在一起，取第一个 `.webm` 会**播出上一次的录像** |
| `SWITCH_WINDOW` 只 bringToFront | ③ | 执行器的当前页面引用没换，后续断言在老窗口找元素，报「元素找不到」。切窗口的本质是切换**后续所有操作的目标** |
| 瞬态计数冗余存 | 看板 | `passed/failed` 是终态可以冗余；`RUNNING` 是瞬态 —— 节点崩溃时减法永远不会执行，冗余值只增不减 |
| `updateById` 跳过 null 字段 | ⑤ | MyBatis-Plus 的默认行为，`setNodeName(null)` 不生效，任务重投后还挂着已掉线的节点名 |

> ⚠️ 还有一个不在代码里但反复咬人的：**`pkill -f` 杀不掉 `mvn spring-boot:run` fork 出的子进程**。
> 表现是「新代码没生效」，让人往功能上找原因。按端口杀才可靠：
> `ss -tlnp | grep :8080 | grep -oP 'pid=\K[0-9]+' | xargs kill -9`

### 6.5 为什么不用 MQ / XXL-JOB（暂缓）

MQ 能替代的是**①的入队 + ⑤的第一类扫描**（消费者断连自动 requeue）。
但②的认领 CAS、④的事务、⑤的第二类扫描一样省不掉 ——
**MQ 的 ack 状态和业务状态机是两套状态，对齐工作 MQ 不做**：
节点崩溃后 MQ 重投消息，但数据库那行还是 RUNNING，新节点认领照样失败。

而且「事务内发消息」这个坑，换成 MQ 会一模一样地犯。

XXL-JOB 解决的是另一件事（定时任务的可视化、多实例去重、手动触发补单），与 MQ 不重叠，
真要引入的话它的性价比更高 —— 但它官方只提供 MySQL 建表脚本，用 PG 要自己适配。

**当前结论：先不引入。** 资源账（云服务器 4C4G）：现有约 500MB，
RabbitMQ 约 250MB 可行，RocketMQ 1.5GB+ 吃紧，XXL-JOB admin 约 400MB 外加 PG 适配工作。

### 6.6 Action → Playwright 映射

13 个 Action 枚举全部要实现（`00-SHARED-CONTEXT.md` §1.3）。
`wait_strategy` 映射到 Playwright 的等待状态：

| wait_strategy | Playwright |
|---|---|
| `NONE` | 不等 |
| `PRESENCE` | `waitFor(state=ATTACHED)` |
| `VISIBLE` | `waitFor(state=VISIBLE)` |
| `CLICKABLE` | `waitFor(state=VISIBLE)` + `isEnabled()` 轮询 |

⚠️ `SLEEP` 要实现（存量案例里有 3 条用了它），但校验器对新案例一律 ERROR 拦下 —— 这个反差本身是展示点。

### 6.7 产物

- **录像**：Playwright 原生 `recordVideoDir` 出 webm。前端 `<video>` 直接能放 webm，
  **不需要转 mp4**（少一个 ffmpeg 依赖）。存本地目录，通过 `/api/artifacts/**` 暴露。
- **失败截图**：失败步骤当场 `screenshot()`
- **trace**：Playwright trace zip，可选

### 6.8 mock-shop

被测站点。要覆盖 8 个模块的页面，定位器用 `data-testid`（与 STD-003 一致）。
纯静态 HTML + 少量 JS 即可，**登录失败计数、库存、优惠券这些状态放 localStorage 或内存**，
不需要真数据库。

---

## 7. 前端

已有设计稿（Claude Design canvas，三张画板：Scope / Landing / Dashboard）。
Dashboard 五个面板 = 五组 API：

| 面板 | 后端接口 |
|---|---|
| 案例中心 | 项目/模块树、案例列表、案例详情（含步骤表 + STD 校验结果） |
| 案例执行状态 | 今日统计、执行中批次（SSE）、最近执行结果、失败详情 |
| **智能 Agent 助手**（原稿的「RAG 问答助手」） | SSE 流式对话 + 引用 chip + 检索命中面板 + **执行进度 / 工具调用 / HITL 交互卡片**，对标 gogoagent |
| 数据集中心 | 语料集列表、检索评估结果、分块设置 |
| 审批中心 | 三类审批列表、diff、批准/退回/挂起 |

**要改的两处**（已确认，由写前端的 session 执行）：

1. 执行引擎文案「Selenium Grid · node-01…node-08」→ **Playwright Workers**。node 池的概念保留。
2. 「RAG 问答助手」面板 → **智能 Agent 助手**。它不只是问答：要能展示 agent 的执行进度、
   工具调用、以及 HITL 的交互卡片（确认 / 选择 / 表单），对标 gogoagent 的 ChatWindow + ProcessingPanel。

另外设计稿的案例编号与库里的种子数据不对应（稿里 `ATP-LOGIN-0002` 是「密码错 3 次锁定」，
库里是「正常登录」），前端接线时以库为准。

---

## 8. 里程碑

| # | 里程碑 | 产出 | 卡点 |
|---|---|---|---|
| **M0** | 骨架 ✅ | Maven 六模块 + PG schema V2~V5（16 张表 + pgvector）+ 种子导入（80 案例 / 412 步骤 / 3 用户）+ STD 校验器 + 健康检查 | ~~PgSession~~ 已取消，用 RedisSession |
| **M1** | 传统平台功能（**读侧**）✅ | 案例树/详情/规范校验、审批中心（含并发仲裁）、执行看板（3580 条历史）。**先不接 agent，先让平台自己立得住** | STD 校验器（两条路线共用的规则实现） |
| **M2** | 执行链路 | mock-shop + Playwright runner + 真录像 + SSE 进度 | Action 翻译层；worker 池并发 |
| **M3** | Agent 层 **+ 案例写侧** | 三层意图路由 + CaseAuthoringAgent + KnowledgeAgent(RAG) + HITL + StandardsGateHook；**案例的新建/编辑/提交审批接口也在这里做** | AgentScope 的坑；pgvector 检索质量；写侧要与 CLI 的语义对齐 |

> ⭐ **写侧为什么押后到 M3**（2026-08-29 决定）：人在 UI 上编辑案例、agent 生成案例，
> 走的是**同一条写入路径**（草稿 → STD 校验 → 单表单行 CAS 落库）。
> 先做一遍给 UI 用、M3 再为 agent 做一遍，几乎必然出现两套语义 ——
> 而这条路径上已经有 `demo2-atp-cli` 的一套实现了（`draft` / `update` / `commit`），
> 第三套只会让「哪个才是对的」变得更难回答。
>
> 代价：M1 交付的前端里，「新建」「编辑」「申请审批」三个按钮暂时没有后端。
| **M4** | 前端接线 | 五个面板接真接口，SSE 打通 | |
| **M5** | 部署 | docker compose（app + runner + PG + Redis + mock-shop），服务器上跑起来 | |

**顺序上的一条纪律**（从 demo1 的失败里学到的，换了个形式）：
**M1 结束时平台要能独立演示**，哪怕 agent 一行没写。
先有一个能看的平台，agent 才有地方"接进去"；反过来做，agent 会悬在空中。

---

## 9. 已定的四件事（2026-08-29 拍板）

**一、认证：与 gogo-agent 的取舍保持一致。**
`sa-token-spring-boot3-starter` + `sa-token-redis-jackson`，token 走请求头 `Authorization`，
30 天有效、`active-timeout: -1`（演示期间不因闲置被踢）、允许多地登录但不共享 token、`random-128`。
配置已落在 `atp-web/src/main/resources/application.yml`。

**二、`mock-shop` 先做 3 个模块。** 建议 LOGIN / CART / ORDER ——
它们在设计稿里案例数最多，也覆盖了「登录失败计数」「库存状态」「订单状态流转」三种有状态的页面行为，
够撑起一次完整演示。其余模块的案例在派发时标 SKIPPED。

**三、80 条存量案例的定位器：保持原样，不迁到 `data-testid`。**

> 那 4 条 `/html/body/...` 绝对路径和 3 条框架动态 id（`ext-gen1234` / `mat-input-7` / `el-id-8237-14`）
> 是演示「规范校验拦得住什么」的**唯一素材**，迁走就没有反面教材了。
> 而 mock-shop 是我们自己造的页面 —— 可以同时给出稳定的 `data-testid` **和**与绝对路径吻合的 DOM 结构，
> 让两类定位器都能跑起来。这样「合规案例跑得稳、脏案例也能跑但被校验器标红」的对比才立得住。

**四、审批的 diff：`payload_json` 存整包 before/after 快照。**
只存变更字段的话，案例在待审期间又被人改了，diff 就对不上了。

---

## 10. 红线（不变）

- 面试用的**学习 demo**，全部语料虚构合成，不使用任何真实公司资产
- 不抓取、不爬取任何真实站点，被测页面一律本地 mock
- 不向用户索取任何来自其前雇主的文档、案例或 schema
- 所有配置读仓库根 `.env`，代码里不得出现硬编码 key / URL / IP
- 每个里程碑一个 PR，不直接推 main，由用户 review 后 merge
