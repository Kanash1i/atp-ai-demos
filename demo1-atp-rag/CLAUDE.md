# demo1 — ATP 知识助手 (Java 8 + langchain4j RAG)

## 开工前必读

1. `../00-SHARED-CONTEXT.md` — 虚构世界观、共享领域模型、模型服务拓扑
2. `../01-HANDOFF-demo1-rag.md` — 本 demo 的完整架构与里程碑

这两份文档是自包含的，包含全部设计决策，不需要向用户重新确认基础信息。

## 红线（不可协商）

- 这是**面试用的学习 demo**。全部语料为虚构合成，**不使用任何真实公司资产**。
- 不抓取、不爬取任何真实站点。被测页面一律用本地 mock。
- 不要向用户索取任何来自其前雇主的文档、案例或 schema —— 用户已明确因法律与道德原因不能提供。

## 硬约束

- **JDK 8**。`langchain4j` 锁定 **0.35.0** —— 已实测 `langchain4j-core` 0.35.0 字节码 major=52 (Java 8)，
  0.36.0 起为 major=61 (Java 17)。**不要升级版本**，升了就编译不过。
- 切换 JDK：`source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk env`（目录下已有 `.sdkmanrc`）

## 配置

**所有配置读仓库根目录 `../.env`**（由 `../.env.example` 复制），
包括 `LLM_API_KEY` / `EMBEDDING_BASE_URL` / `RERANK_BASE_URL` / `QDRANT_HOST`。
**代码里不得出现硬编码的 key、URL、IP。**

- 生成：DeepSeek（`deepseek-v4-flash`，OpenAI 兼容），后期可切 Kimi
- Embedding / Rerank：服务机 `192.168.0.101` 上的两个 llama-server 实例（`:8081` / `:8082`）

> ⚠️ **服务机是 Windows 11 Pro（日文环境），不是 Linux。**
> `ssh kkaib@192.168.0.101` 可免密登录，但默认 shell 是 cmd.exe；
> 跑 PowerShell 要用 `-EncodedCommand` 传 base64，否则三层引号转义必崩。
> 笔记本已配 `docker context remote`，直接敲 docker 命令即可操作台式机上的容器。
> 详见共享文档 §2.1。

## ⚠️ rerank 必须先验证

llama.cpp 的 rerank 端点对部分模型有打分错误的已知缺陷。
**开工前先跑冒烟测试**（共享文档 §2.1）。若不通过，设 `RERANK_ENABLED=false` 并在消融表中如实标注缺失，
**绝不能拿一个坏掉的 rerank 去跑评估** —— 那会污染整张表，比没有 rerank 严重得多。详见交接文档 §2.4。

## 本 demo 的成败标准

面试岗位是 **AI 应用工程**。

**做成"一个能聊天的知识库"就算失败。** 核心交付物是 `../01-HANDOFF-demo1-rag.md` §5 的
**消融实验表** —— 用数字证明每一项检索优化带来了多少提升。

时间不够时的取舍：**砍功能，不砍评估**。

## 开工第一步

不要先写业务代码。先做 §2.2 的 **Java 8 链路 spike**：
确认 JDK 8 能连通 Qdrant (gRPC) 和 llama.cpp 的 embedding 端点。不通就得按文档里的方案 B/C/D 降级，
并把过程记进 `DECISIONS.md`。
