# atp-ai-demos — ATP 测试平台与两条 AI 赋能路线

一个可运行、可当面演示的 **ATP 自动化测试平台**，外加一条对照用的保守路线。
面试岗位：**AI 应用工程**。

> ⚠️ 全部语料、schema、规范均为**虚构合成**，不含任何真实公司资产。详见 `00-SHARED-CONTEXT.md` §0。

**仓库**：`https://github.com/Kanash1i/atp-ai-demos`（private）

---

## 这个仓库在做什么

```
              ┌──────────────────────────────────────────┐
              │        ATP 自动化测试平台（虚构）           │
              │   案例管理 / 派发执行 / 状态中心 /          │
              │   录像回放 / 审批中心                      │
              │            PostgreSQL                    │
              └──────────────────────────────────────────┘
                                ▲
                    ┌───────────┴────────────┐
                    │   demo2-atp-cli（Go）   │  ⭐ 所有 agent 写案例的唯一入口
                    │   状态机 / 幂等 / 校验    │
                    └───────────┬────────────┘
                   ┌────────────┴────────────┐
      ┌────────────┴───────────┐    ┌────────┴────────────────┐
      │  保守路线                │    │  激进路线                 │
      │  opencode（用户机器上）   │    │  平台内的 agent 模块        │
      │                        │    │                         │
      │  自然语言 → 案例          │    │  多 agent 协作            │
      │  权限门拦 commit，人确认   │    │  自然语言 → 案例，自主提交   │
      │  → 老执行器无感知照跑      │    │  → 自己调 Playwright 执行  │
      │                        │    │  → 自己录像                │
      │  难点：把规则从提示词下沉   │    │  难点：生成质量、执行链路    │
      └────────────────────────┘    └─────────────────────────┘
                        两条路线共用同一个 PG 库
```

⭐ **两个 agent 走同一个 CLI 写库 —— 格式漂移在物理上不可能发生。**
这比"约定两边都按同一个格式写"强一个量级：**约定会漂，同一份代码不会。**

**面试主线**：同一个平台，两种介入方式。一条把 AI 挡在平台边界之外（人在环里确认，
落地为普通案例，执行器完全无感知）；一条让 AI 走进来（自主提交、自己跑、自己出产物）。
**区别不在「怎么写库」——那条路径是共用的——而在「谁触发、要不要人确认、怎么执行」。**
一个保守一个激进，是**探索**而不是二选一 —— 讲清楚各自的代价和边界，比讲"我用了 agent"有价值得多。

> **2026-08-29 方向变更**：原「知识侧 RAG demo + 消融实验表」路线已废弃，
> demo1 源码与归档已物理删除（git 历史可回溯），语料保留在 `seed/`。
> RAG 现在是平台里的一个功能模块（规范问答助手），不再是独立 demo。

---

## 文档地图

| 文件 | 内容 |
|---|---|
| `.env.example` | ⚠️ **API key 填在这里**（复制为 `.env`） |
| `00-SHARED-CONTEXT.md` | 虚构世界观、共享领域模型、机器拓扑、provider 差异 |
| **`01-PLATFORM-设计.md`** | ⭐ **当前入口**。平台架构、表结构、agent 层、执行器、里程碑 |
| **`02-前端契约.md`** | ⭐ **给前端/设计 session**。设计稿要改的 8 处 + M1 全部接口契约（含真实响应示例） |
| `07-CLI-项目综述.md` | 保守路线的开场文档 —— 定位、判据、实测数字、简历口径 |
| `05-CLI-并发幂等答辩稿.md` | 并发幂等的细节（被追问时展开） |
| `06-atp-cli-设计.md` | CLI 命令表、退出码契约、opencode 接入 |
| `seed/` | 种子数据：80 条案例 JSON + 15 篇文档（手册 + 规范）+ 生成器 |

`CLAUDE.md` 会被新 session 自动读到，里面是红线与硬约束。

---

## 参考实现

`~/llmentor/LLMentor/gogo-agent` —— 多 agent 差旅助手（Spring Boot 3.4 + AgentScope 1.0.12）。
骨架直接可用：三层意图路由、ReAct + Tool、生命周期 Hook、SSE 推送、审批中心、React 前端。

> ⚠️ 这是**第三方课程代码**。其父 `pom.xml` 有原作者的真实 DashScope key，
> `application.yml` 有 MySQL 密码。**一个都不要带进本仓库。**
> 面试展示时的口径也要想好边界：「基于一个开源引擎改造，我做的部分是 X、Y、Z」。

---

## 快速开始

```bash
cd /home/kanashi/Applications/interview-demos
cp .env.example .env
# 编辑 .env，填入 LLM_API_KEY

./infra/infra.sh up          # 起 PG + Redis（在台式机上）+ 应用全部迁移 + 写 .env
cd atp-platform && ./scripts/run-web.sh --seed   # 起平台并导入 80 条种子案例
curl -s localhost:8080/api/health
```

新 session 的第一句话：**"读 CLAUDE.md 和 01-PLATFORM-设计.md，然后从 M0 开始"**。

---

## 本机环境（已配置完成 ✅）

| 组件 | 版本 |
|---|---|
| JDK 21 | 平台需要（AgentScope + Spring Boot 3.4） |
| JDK 17 | Temurin 17.0.16 |
| Maven | 3.9.16 |
| Go | demo2 CLI |

---

## 服务机 ✅ 已部署

台式机 `192.168.0.101`（Windows 11 Pro / RTX 5080）。

| | 状态 |
|---|---|
| SSH 免密 `kkaib@192.168.0.101` | ✅ 已通（默认 shell 是 cmd.exe） |
| `docker context remote` | ✅ 笔记本敲 docker 命令即操作台式机 |
| **TEI embedding** (bge-m3) | ✅ `:8081`，**FlashBert on Cuda**，1024 维 |
| **TEI rerank** (bge-reranker-v2-m3) | ✅ `:8082`，**FlashBert on Cuda** |
| **PostgreSQL + pgvector** | ✅ `:25432`，compose project `atp-infra`，两条路线共用 |
| **Redis** | ✅ `:25379`，同上 |
| Windows 防火墙 | ✅ 无需配置，Docker Desktop 端口转发已生效 |

> ⚠️ **Qdrant 已出局** —— 向量检索改用 pgvector，与业务表同库。
>
> ⚠️ **踩过的坑**：TEI 检测不到 CUDA 时**不报错，静默降级到 CPU**，health 照样 200、
> API 照样返回 1024 维向量，但 14 核满载、GPU 空转。
> 验证：`docker logs tei-embed | grep "model on"` 必须是 **Cuda** 而非 Cpu。

---

## 里程碑

| # | 里程碑 | 产出 |
|---|---|---|
| M0 ✅ | 骨架 | Maven 六模块 + PG schema（16 表 + pgvector）+ 种子导入（80 案例/412 步骤）+ STD 校验器 |
| M1 ✅ | 传统平台功能（读侧） | 案例树/详情/规范校验、审批中心（含并发仲裁）、执行看板（3580 条历史） |
| M2 | 执行链路 | mock-shop + Playwright runner + 真录像 + SSE |
| M3 | Agent 层 | 三层意图路由 + 案例生成 + RAG + HITL |
| M4 | 前端接线 | 五个面板接真接口 |
| M5 | 部署 | docker compose，服务器上跑起来 |

> **硬纪律：M1 结束时平台要能独立演示，哪怕 agent 一行没写。**
> 先有一个能看的平台，agent 才有地方接进去；反过来做，agent 会悬在空中。

---

## Git 工作流

**每个里程碑一个 PR，不直接推 main** —— 便于逐步 review 与回溯。

```bash
git checkout -b platform/m1-case-center    # 分支名：{模块}/{里程碑}-{简述}
git commit -m "feat(platform): 案例中心的树与详情接口"
gh pr create --fill                         # 自动套用 .github/PULL_REQUEST_TEMPLATE.md
```

PR 模板要求填写**怎么验证的**、**决策记录**、**踩到的坑**、**面试可讲的点**。
最后一项不是形式主义 —— 如果一个 PR 想不出能讲什么，通常说明这步做得太机械了。

PR 由你 review 后 merge，session 不会自行合并。
