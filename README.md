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

### 2. 服务机模型服务 ✅ **已部署完成**

台式机 `192.168.0.101`（Windows 11 Pro / RTX 5080）。**这一项不用你做了，已经跑起来了。**

| | 状态 |
|---|---|
| SSH 免密 `kkaib@192.168.0.101` | ✅ 已通（默认 shell 是 cmd.exe） |
| `docker context remote` | ✅ 已通，笔记本敲 docker 命令即操作台式机 |
| GPU 直通 | ✅ 已实测：容器内 `cuInit`/`cudaGetDeviceCount` 均成功 |
| **TEI embedding** (bge-m3) | ✅ `:8081` 运行中，**FlashBert on Cuda** |
| **TEI rerank** (bge-reranker-v2-m3) | ✅ `:8082` 运行中，**FlashBert on Cuda** |
| **Qdrant** 1.19.0 | ✅ `:6333` 运行中 |
| 自动重启 | ✅ 三个容器均 `--restart unless-stopped` |
| Windows 防火墙 | ✅ 无需配置，Docker Desktop 端口转发已生效 |
| 冒烟测试 | ✅ 全部通过（维度 1024、rerank 区分度 4 个数量级） |

镜像：`ghcr.io/huggingface/text-embeddings-inference:120-1.9.3`（Blackwell sm_120 专用 tag）。
完整命令与排障见 `00-SHARED-CONTEXT.md` §2.1。

**为什么是 TEI 而不是 llama.cpp/Ollama** —— 见 §2.3。简短版：llama.cpp 对 bge 系列
（尤其 reranker）支持不完善；TEI 专为 embedding/rerank 设计，标准协议 + 动态 batching，
实测 rerank 区分度达 4 个数量级。

> ⚠️ **踩过的坑，重启服务后请留意**：TEI 检测不到 CUDA 时**不报错，静默降级到 CPU**，
> health 照样 200、API 照样返回 1024 维向量，但 14 核满载、GPU 空转。
> 修复靠启动参数 `--tmpfs /usr/local/cuda-12.9/compat`（已加）。
> 验证方式：`docker logs tei-embed | grep "model on"` 必须出现 **Cuda** 而非 Cpu。

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
