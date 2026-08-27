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
| **`03-HANDOFF-rag-v2.md`** | **知识侧 session** | ⭐ 当前入口。换引擎重做的路线、**七条跨项目经验**、资产迁移清单 |
| `05-CLI-并发幂等答辩稿.md` | demo2 session | ⭐ `atp` CLI 的并发幂等设计（面试口述稿）|
| `06-atp-cli-设计.md` | demo2 session | CLI 命令表、退出码契约、opencode 接入、里程碑 |
| `01-HANDOFF-demo1-rag.md` | — | 🗄️ 已归档（Java 8 + langchain4j 路线）。有效部分已提炼进 `03-` |

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
    │  知识侧「读」            │   │  生产侧「写」               │
    │  RAG 知识助手           │   │  atp 案例编写 CLI          │
    │  Java 21 + langchain4j│   │  Java 21，无 AI 框架       │
    │                       │   │                          │
    │  难点：召回质量怎么量化   │   │  难点：并发下只落库一次     │
    └───────────────────────┘   └──────────────────────────┘
```

> **2026-08-19 路线调整**：知识侧原为 Java 8 + langchain4j 全手搓（`demo1-atp-rag/`，已归档，
> 停在 M3、消融表未跑）。现改为在买来的 Java 21 RAG 引擎上重做。详见 `03-HANDOFF-rag-v2.md`。
>
> **2026-08-27 路线调整**：生产侧的 MCP server 方案**已废弃并删除**（原 `demo2-atp-mcp/`），
> 改做 `atp` CLI（`demo2-atp-cli/`）。详见 `05-CLI-并发幂等答辩稿.md`、`06-atp-cli-设计.md`。

**面试主线**（详见 `00-SHARED-CONTEXT.md` §4）：
> 老平台有两个瓶颈 —— 新人上手慢，以及 AI 生成的案例进不了库。
> 前者是**检索问题**，用 RAG 解决；后者是**并发写入的一致性问题** ——
> 把幂等键做成平台案例表的主键，用唯一约束 + CAS UPDATE 当仲裁点。
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
cd demo2-atp-cli     # session B
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

**知识侧优先**，按 `03-HANDOFF-rag-v2.md` §5 的里程碑走：

```
M0 拉代码摸底 + 定框架统一方案
M1 换 ATP 语料
M2 ⭐ 评估集 + baseline      ← 这一步不完成，不准做 M3
M3 ⭐ 消融表 4 行
M4 合并 demo2 + 演示脚本
```

demo2（CLI）M1 已完成（状态机 + 并发幂等，63 个用例绿），在 M4 并入多模块骨架。

> **唯一的硬纪律：语料一能检索，下一件事就是评估集，不是加功能。**
> demo1 就是栽在这条上 —— 功能做了一堆（多格式解析、图片链路、父子切块），
> 而第一天写下的「砍功能，不砍评估」被自己违反了，最后消融表一行没跑出来。
