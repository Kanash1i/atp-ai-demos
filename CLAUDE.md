# atp-ai-demos — 面试 demo monorepo

## 开工前必读

1. `03-HANDOFF-rag-v2.md` — ⭐ **知识侧当前的入口文档**（换引擎重做的路线、七条跨项目经验、资产迁移清单）
2. `00-SHARED-CONTEXT.md` — 虚构世界观、共享领域模型、模型服务拓扑
3. `07-CLI-项目综述.md` — ⭐ **生产侧的开场文档**（定位、判据、数字、简历口径）
4. `05-CLI-并发幂等答辩稿.md` + `06-atp-cli-设计.md` — 被追问细节时展开（面试口述稿 / 实现设计）

> **知识侧**：`01-HANDOFF-demo1-rag.md` 和 `demo1-atp-rag/` 已归档（2026-08-19），**不要以它们开工**。
> 有效内容已提炼进 `03-`。`demo1-atp-rag/DECISIONS.md` 仍是面试素材库，被追问细节时回来查。
>
> **生产侧**：MCP server 方案已废弃（2026-08-27），`02-HANDOFF-demo2-mcp.md` 与 `demo2-atp-mcp/`
> **已删除**（不是归档）。改做 `atp` CLI —— 仲裁点放回平台自己的表，不外挂 server。
> 废案的设计推理留在 git 历史（commit `95cdc81`），可复用资产已迁入 `demo2-atp-cli/`。

这几份文档是自包含的，不需要向用户重新确认基础信息。

## 红线（不可协商）

- 这是**面试用的学习 demo**。全部语料为虚构合成，**不使用任何真实公司资产**。
- 不抓取、不爬取任何真实站点。被测页面一律用本地 mock。
- 不要向用户索取任何来自其前雇主的文档、案例或 schema —— 用户已明确因法律与道德原因不能提供。

## ⭐ 唯一的硬纪律：评估先于功能

面试岗位是 **AI 应用工程**。**做成「一个能聊天的知识库」就算失败。**
核心交付物是消融实验表 —— 用数字证明每一项检索优化带来了多少提升。

**语料一能检索出结果，下一件事就是评估集和 baseline 数字**，不是加功能、不是调 prompt、不是做 UI。

> demo1 已经违反过这条一次：多格式解析、图片链路、父子切块全做了，
> 评估框架从没开始，消融表一行没跑出来 —— 这就是它被归档的直接原因。
> 时间不够时的取舍：**砍功能，不砍评估。**

## 配置

**所有配置读仓库根目录 `.env`**（由 `.env.example` 复制）。
**代码里不得出现硬编码的 key、URL、IP。**

- 生成：DeepSeek（`deepseek-v4-flash`，OpenAI 兼容），后期可切 Kimi
- Embedding / Rerank：服务机 `192.168.0.101` 上的两个 **TEI 容器**（`:8081` / `:8082`），已部署验证

> ⚠️ 服务机是 **Windows 11 Pro（日文环境）**，不是 Linux。
> `ssh kkaib@192.168.0.101` 免密可登，但默认 shell 是 cmd.exe；
> 跑 PowerShell 要用 `-EncodedCommand` 传 base64，否则三层引号转义必崩。
> 笔记本已配 `docker context remote`，直接敲 docker 命令即可操作台式机上的容器。
> 详见 `00-SHARED-CONTEXT.md` §2.1。

## ⚠️ 开工前的冒烟测试

```bash
docker logs tei-embed  2>&1 | grep -i "model on"   # 必须是 Cuda，不能是 Cpu
docker logs tei-rerank 2>&1 | grep -i "model on"
```

这个项目**已经踩过一次**：服务 health 200、API 正常返回 1024 维向量，一切看起来都对，
实际却在 CPU 上跑（14 核满载），靠 CPU 风扇声才发现。
完整三项冒烟测试见 `00-SHARED-CONTEXT.md` §2.1(e)。

若 rerank 不通，设 `RERANK_ENABLED=false` 并在消融表中如实标注缺失。
**绝不能拿一个坏掉的 rerank 去跑评估** —— 那会污染整张表，比没有 rerank 严重得多。

## ⚠️ 不要在 `.claude/settings*.json` 的 `env` 里设 `PATH`

那是 JSON，**`${PATH}` 不会被展开**，会把整个系统 PATH 替换掉 —— 症状是
`git: command not found` 而 `java` / `mvn` 一切正常，且不报任何错。
本仓库踩过，见 `demo1-atp-rag/DECISIONS.md` **D-027**。锁 JDK 用 `JAVA_HOME` 就够。

## Git 工作流

仓库：`https://github.com/Kanash1i/atp-ai-demos`（private，monorepo）

**每个里程碑一个 PR，不要直接推 main。** 用户要逐步 review 和回溯。

```bash
git checkout -b rag/m2-evaluation        # 分支名：{模块}/{里程碑}-{简述}
git commit -m "feat(rag): 加入检索评估框架与 baseline 指标"
gh pr create --fill                       # 自动套用 PR 模板
```

- commit 前缀：`feat` / `fix` / `docs` / `test` / `chore`
- PR 模板里的**「怎么验证的」必填** —— 没有验证方式的 PR 不该合
- **PR 由用户 review 后 merge，不要自行 merge**
- 改动共享契约（`00-SHARED-CONTEXT.md` 的领域模型 / Action 枚举）必须在 PR 里显式说明
