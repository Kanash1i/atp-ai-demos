# 面试复盘 Demo — ATP 平台的两次 AI 介入

两个可运行的 demo，用于面试时复盘"在遗留测试平台上落地 AI"的工程经验。
面试岗位：**AI 应用工程**。

> ⚠️ 全部语料、schema、规范均为**虚构合成**，不含任何真实公司资产。详见 `00-SHARED-CONTEXT.md` §0。

**仓库**：`https://github.com/Kanash1i/atp-ai-demos`（private）

---

## Git 工作流

**每个里程碑一个 PR，不直接推 main** —— 便于逐步 review 与回溯。

```bash
git checkout -b demo1/m4-evaluation    # 分支名：demo{1,2}/{里程碑}-{简述}
git commit -m "feat(demo1): 加入检索评估框架与 baseline 指标"
gh pr create --fill                     # 自动套用 .github/PULL_REQUEST_TEMPLATE.md
```

PR 模板要求填写**怎么验证的**、**决策记录**、**踩到的坑**、**面试可讲的点**。
最后一项不是形式主义 —— 如果一个 PR 想不出能讲什么，通常说明这步做得太机械了。

PR 由你 review 后 merge，两个 session 都不会自行合并。

---

## 文档地图

| 文件 | 读的人 | 内容 |
|---|---|---|
| `.env.example` | **你** | ⚠️ **API key 填在这里**（复制为 `.env`） |
| `00-SHARED-CONTEXT.md` | **两个 session 都要读** | 虚构世界观、共享领域模型、机器拓扑、provider 差异、面试叙事主线 |
| `01-HANDOFF-demo1-rag.md` | demo1 session | RAG 助手的架构、语料设计、**评估体系** |
| `02-HANDOFF-demo2-mcp.md` | demo2 session | MCP server 的流水线、k8s 部署、可靠性策略、测试方案 |

每个 demo 目录下已放 `CLAUDE.md`，新 session 启动时自动读到红线与硬约束。

---

## 两个 demo 是什么

```
        ┌──────────────────────────────────────────────┐
        │           ATP 遗留测试平台（虚构）              │
        │          Java 8 / Spring 4 / MySQL           │
        └──────────────────────────────────────────────┘
                 ▲                          ▲
    ┌────────────┴──────────┐   ┌───────────┴──────────────┐
    │  demo1: 知识侧「读」     │   │  demo2: 生产侧「写」        │
    │  RAG 知识助手           │   │  MCP 规范化服务 (k8s)      │
    │  Java 8 + langchain4j │   │  Java 17 + Spring Boot   │
    │                       │   │                          │
    │  难点：召回质量怎么量化   │   │  难点：怎么让模型少做事     │
    └───────────────────────┘   └──────────────────────────┘
```

**面试主线**（详见 `00-SHARED-CONTEXT.md` §4）：
> 老平台有两个瓶颈 —— 新人上手慢，以及 AI 生成的案例进不了库。
> 前者是**检索问题**，用 RAG 解决；后者是**结构化输出的可靠性问题**，用 MCP + schema 约束解决。
> 看起来都是"接大模型"，但工程重点完全不同。

---

## 快速开始

```bash
cd /home/kanashi/Applications/interview-demos
cp .env.example .env
# 编辑 .env，填入 LLM_API_KEY
```

然后各开一个 session（两个 demo 互相独立，可并行）：

```bash
cd demo1-atp-rag     # session A
cd demo2-atp-mcp     # session B
```

第一句话直接说：**"读 CLAUDE.md 和交接文档，然后从 M0 开始"**。

---

## 本机环境（已配置完成 ✅）

| 组件 | 版本 |
|---|---|
| JDK 8 | Temurin 8.0.472 |
| JDK 17 | Temurin 17.0.16 |
| Maven | 3.9.16 |

两个 demo 目录下已放 `.sdkmanrc`，进目录执行 `sdk env` 自动切换：

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk env      # demo1 → JDK 8，demo2 → JDK 17
```

已验证：demo1 目录切出 `1.8.0_472`，demo2 目录切出 `17.0.16`。

---

## ⬜ 还需要你做的事

### 1. 填 API key ← **最直接的一步**

```bash
cp .env.example .env
```

编辑 `.env`，把 key 填在 **`LLM_API_KEY=`** 这一行。

开发期用 DeepSeek（已预设好 `base_url` 和 model），后期切 Kimi 只需改三行配置，不改代码。

> ⚠️ 两家的结构化输出能力**不对等**（DeepSeek 无 `json_schema` strict），
> 这是 demo2 的一个核心设计约束，见 `00-SHARED-CONTEXT.md` §2.2。

### 2. 服务机起模型服务（**只有 demo1 需要**）

台式机 `192.168.0.101` —— ⚠️ **Windows 11 Pro（日文环境），命令是 PowerShell**。

| | 状态 |
|---|---|
| SSH 免密 `kkaib@192.168.0.101` | ✅ 已通（默认 shell 是 cmd.exe） |
| `docker context remote` | ✅ 已通，笔记本敲 docker 命令即操作台式机 |
| llama.cpp | ✅ 已装 `C:\Users\kkaib\mydisk\llama_dir\llama-b9360-bin-win-cuda-13.1-x64\`（不在 PATH） |
| GPU | ✅ RTX 5080 16303 MiB，driver 610.88 |
| GGUF 模型 | ⬜ **待下载** bge-m3 + bge-reranker-v2-m3（各约 600MB） |
| llama-server × 2 | ⬜ 待启动（`:8081` embedding / `:8082` rerank） |
| Qdrant | ⬜ 待启动（`:6333`） |
| Windows 防火墙 | ⬜ 待放行 8081/8082/6333 |

完整命令（含下载、启动、防火墙）见 `00-SHARED-CONTEXT.md` §2.1。

**用 llama.cpp 而不是 Ollama/Infinity 的理由见 §2.3** —— 简短版：
Ollama 慢有具体根因（无批处理、模型卸载），但对本项目**速度根本不是瓶颈**；
选 llama.cpp 是因为你已验证过、有官方 win-cuda binary、且台式机的 WSL 只有 `docker-desktop`
（没有通用 Linux 发行版，跑 Ollama/Infinity 要凭空多一层）。

> ⚠️ 起好后**必须跑 §2.1(e) 的两个冒烟测试**：embedding 维度是否 1024、rerank 打分方向是否正确。
> 这两项防的都是**静默失败** —— 服务照常运行但结果悄悄是错的。
> 拿一个坏掉的 rerank 去跑评估会污染整张消融表，而你不会发现。

### 3. SSH 免密 ✅ 已完成

`ssh kkaib@192.168.0.101` 免密可用，`docker context remote` 也已生效。

⚠️ 通过 SSH 跑 PowerShell 时用 `-EncodedCommand` 传 base64，
否则 bash → ssh → powershell 三层引号转义会崩（共享文档 §2.1 有现成的 `psrun` 函数）。

---

## 建议的开发顺序

**先做 demo2。**

理由：它**不需要服务机**，只要一个 API key，现在就能开工；
而且 M2（纯规则链路）完成时已有完整演示价值，投入产出比最高。

demo1 需要等服务机就绪，可以在等待期间先做 M1 语料生成（纯文本，无依赖）。
