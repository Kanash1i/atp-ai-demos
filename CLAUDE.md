# atp-ai-demos — 面试 demo monorepo

## 开工前必读

1. `01-PLATFORM-设计.md` — ⭐ **当前的入口文档**（ATP 平台架构、表结构、agent 层、执行器、里程碑）
2. `02-前端契约.md` — 前端接口契约与设计稿差异（改后端接口形状时**必须同步这份**）
3. `00-SHARED-CONTEXT.md` — 虚构世界观、共享领域模型、模型服务拓扑
4. `07-CLI-项目综述.md` — 保守路线（demo2 `atp` CLI）的开场文档
5. `05-CLI-并发幂等答辩稿.md` + `06-atp-cli-设计.md` — 被追问 CLI 细节时展开

这几份文档是自包含的，不需要向用户重新确认基础信息。

> **2026-08-29 方向变更**：原来的「知识侧 RAG demo + 消融实验表」路线**已废弃**。
> demo1 的源码、归档、路线文档（`03-HANDOFF-rag-v2.md`、`04-M0-engine-audit.md`、`archive/`）
> **已物理删除**，在 git 历史里可回溯。
> 语料保留在 **`seed/`**（80 条案例 JSON + 15 篇文档），它是新平台的种子数据与 RAG 语料。
>
> 现在做的是**一个完整的 ATP 平台**：传统功能（案例管理 / 派发执行 / 状态中心 / 录像 / 审批）
> ＋ 多 agent 协作模块（自然语言生成案例、自己调 Playwright 执行录像）。
> 参考实现是 `~/llmentor/LLMentor/` 下那份多 agent 应用。

## 红线（不可协商）

- 这是**面试用的学习 demo**。全部语料为虚构合成，**不使用任何真实公司资产**。
- **不抓取、不爬取任何真实站点。被测页面一律用本地 mock（`mock-shop/`）。**
- 不要向用户索取任何来自其前雇主的文档、案例或 schema —— 用户已明确因法律与道德原因不能提供。
- ⚠️ 参考项目 `~/llmentor/LLMentor` 是**第三方课程代码**，其父 `pom.xml` 里有原作者的真实
  DashScope API key、`application.yml` 里有 MySQL 密码。**一个都不要带进本仓库。**

## 两条路线（这是面试叙事的主线，别做丢了）

> ⚠️ **2026-08-31 修正**：两条路线**共用同一条写入路径**（`atp` CLI），
> 区别不在「怎么写库」，而在「谁触发、要不要人确认、怎么执行」。

| | 保守路线 | 激进路线 |
|---|---|---|
| agent 在哪 | opencode，跑在用户机器上 | 本平台的 agent 模块，跑在平台进程里 |
| **写案例** | **`atp` CLI** | **`atp` CLI（同一个）** |
| 确认环节 | opencode 的权限门拦 `commit`，人点确认 | agent 自主提交 |
| 执行 | 落地为普通案例，**老执行器照跑，无感知** | agent 自己派发、自己调 Playwright 录像 |

⭐ **两个 agent 走同一个 CLI 写库 —— 格式漂移在物理上不可能发生。**
这比"约定两边都按同一个格式写"强一个量级：约定会漂，同一份代码不会。

**两条路线共用同一个 PostgreSQL 库** —— 同一份 `tc_case`/`tc_step`，可以当场对比。

> ### 终态：凭证边界
>
> ```
> opencode ──┐
>            ├─→ atp CLI ──HTTP──→ ATP 平台 API ──→ PG
> 平台 agent ─┘   持窄 token           持 DB 凭证
> ```
>
> **目的不是"CAS 归平台"，是让 agent 那一层永远看不到数据库密码** ——
> 不是靠约束，是它根本拿不到。
> （`demo2-atp-cli/DECISIONS.md` **D-123** 记了一次真实事故：
> agent 想删草稿、没找到工具，就读了 `.env` 拼 SQL 删了。这个架构下不可能发生。）
>
> **现状**：CLI 仍直连 PG。平台侧写接口已就位
> （`POST /api/cases/draft`、`PUT /api/cases/{id}/draft`、`POST /api/cases/{id}/commit`），
> **缺的只有鉴权**。迁移是接线不是造。

## 硬纪律

**M1 结束时平台要能独立演示，哪怕 agent 一行没写。**

先有一个能看的平台，agent 才有地方接进去；反过来做，agent 会悬在空中。

> 这条是从旧路线的失败里换形式学来的：那次是「功能做了一堆，评估一行没跑」。
> 同一个病根 —— 交付物没有先立住。

## 配置

**所有配置读仓库根目录 `.env`**（由 `.env.example` 复制）。
**代码里不得出现硬编码的 key、URL、IP。**

- 生成：DeepSeek（`deepseek-v4-flash`，OpenAI 兼容）。AgentScope 走 `provider: openai-compatible`
- Embedding / Rerank：服务机 `192.168.0.101` 上的两个 **TEI 容器**（`:8081` / `:8082`），已部署验证
- 向量存储：**pgvector**（与业务表同库），经 AgentScope 自带的 `PgVectorStore` —— 框架原生支持，
  不用自己写适配器。⚠️ **Qdrant 已出局，不要再用**
- Agent 多轮工作记忆：**`RedisSession`**（AgentScope 自带）。没有 PG 版 Session，也不要自己写
- 数据库：**PostgreSQL**，与 `demo2-atp-cli` 共库

### 中间件：全部跑在台式机，统一由 `infra/` 管

```bash
./infra/infra.sh up      # 起 PG + Redis + 应用全部迁移 + 写 .env（幂等）
./infra/infra.sh ports   # ⚠️ 加新服务前先跑这个
```

| | 地址 | 说明 |
|---|---|---|
| PostgreSQL (pgvector) | `192.168.0.101:25432` | compose project `atp-infra`，两条路线共用 |
| Redis | `192.168.0.101:25379` | Sa-Token / RedisSession / 中断广播 / 熔断 / 执行队列 |
| TEI embed / rerank | `:8081` / `:8082` | **已在跑，不归 infra 管，别动** |
| MinerU | `:8000` | 同上 |

> ⚠️ **端口要往后避。** 台式机上停着一批容器，它们没在跑但配置里写着端口
> （3306 3307 5432 6333 6379 6380 8080 9200 13306 13307 15432 16380 16381 18081-18085 …），
> 随时可能被拉起来撞车。加新服务前先 `./infra/infra.sh ports`。

> ⚠️ 服务机是 **Windows 11 Pro（日文环境）**，不是 Linux。
> `ssh kkaib@192.168.0.101` 免密可登，但默认 shell 是 cmd.exe；
> 跑 PowerShell 要用 `-EncodedCommand` 传 base64，否则三层引号转义必崩。
> 笔记本已配 `docker context remote`，直接敲 docker 命令即可操作台式机上的容器。
> 详见 `00-SHARED-CONTEXT.md` §2.2。

## ⚠️ 开工前的冒烟测试

```bash
docker logs tei-embed  2>&1 | grep -i "model on"   # 必须是 Cuda，不能是 Cpu
docker logs tei-rerank 2>&1 | grep -i "model on"
```

这个项目**已经踩过一次**：服务 health 200、API 正常返回 1024 维向量，一切看起来都对，
实际却在 CPU 上跑（14 核满载），靠 CPU 风扇声才发现。
完整三项冒烟测试见 `00-SHARED-CONTEXT.md` §2.2(e)。

## ⚠️ 不要在 `.claude/settings*.json` 的 `env` 里设 `PATH`

那是 JSON，**`${PATH}` 不会被展开**，会把整个系统 PATH 替换掉 —— 症状是
`git: command not found` 而 `java` / `mvn` 一切正常，且不报任何错。
本仓库踩过。锁 JDK 用 `JAVA_HOME` 就够。

## Git 工作流

仓库：`https://github.com/Kanash1i/atp-ai-demos`（private，monorepo）

**每个里程碑一个 PR，不要直接推 main。** 用户要逐步 review 和回溯。

```bash
git checkout -b platform/m1-case-center     # 分支名：{模块}/{里程碑}-{简述}
git commit -m "feat(platform): 案例中心的树与详情接口"
gh pr create --fill                          # 自动套用 PR 模板
```

- commit 前缀：`feat` / `fix` / `docs` / `test` / `chore`
- PR 模板里的**「怎么验证的」必填** —— 没有验证方式的 PR 不该合
- **PR 由用户 review 后 merge，不要自行 merge**
- 改动共享契约（`00-SHARED-CONTEXT.md` 的领域模型 / Action 枚举）必须在 PR 里显式说明
