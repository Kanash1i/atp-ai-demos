# demo1 — ATP 知识助手（Java 8 + langchain4j RAG）

一个面向遗留 Web UI 自动化测试平台（虚构的 **ATP**）的 RAG 知识助手。

**这个 demo 想证明的不是「接通了大模型」，而是「我知道 RAG 的质量瓶颈在检索，并且能用数字说话」。**
核心交付物是[消融实验表](#消融实验)——逐项叠加检索优化，量化每一项带来多少提升。

> ⚠️ 全部语料为 AI 生成的虚构合成数据，不涉及任何真实公司资产。

---

## 当前进度

| 里程碑 | 状态 |
|---|---|
| **M0** Java 8 链路 spike | ✅ 已完成 |
| **M1** 语料生成 | ✅ 已完成 |
| **M2** 入库 pipeline | ✅ 已完成 |
| M3 基础检索 + CLI | ⬜ |
| M4 评估框架 + baseline | ⬜ |
| M5 逐项优化 + 消融表 | ⬜ |
| M6 演示脚本 | ⬜ |

---

## 技术栈

| 组件 | 选型 | 说明 |
|---|---|---|
| JDK | **8** | 遗留平台的现实约束，不是偷懒 |
| langchain4j | **0.35.0** | 最后一个 Java 8 字节码版本，见 `DECISIONS.md` D-001 |
| 向量库 | Qdrant **v1.11.5** | tag 钉死，原因见 D-002 |
| Embedding | bge-m3 @ TEI | 本地 GPU，1024 维，中日英均衡 |
| Rerank | bge-reranker-v2-m3 @ TEI | 本地 GPU，自己实现 `ScoringModel` 适配 |
| 生成 | DeepSeek | 唯一走外网的一步 |

**为什么 embedding / rerank 本地跑，只有生成走 API**：合规（内部文档不出网）、
成本（embed 调用量是生成的几十倍）、以及把 16G 显存用在刀刃上。
速度反而是最次要的理由。详见 `../00-SHARED-CONTEXT.md` §3。

---

## 跑起来

### 1. 配置

```bash
cp ../.env.example ../.env   # 配置统一放仓库根目录，代码里零硬编码
```

填 `LLM_API_KEY` 和 `SERVICE_HOST`。

### 2. 切到 JDK 8

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk env
java -version   # 必须是 1.8.0_xxx
```

### 3. 链路自检

```bash
mvn -q compile exec:java -Dexec.mainClass=com.atp.rag.spike.LinkageSpike
```

四项检查，任何一项失败都会打印根因和降级方案：

```
[ 0. 运行时环境 ]        java.version = 1.8.0_472
[ 1. TEI embedding ]     中文 → 1024 维，日文 → 1024 维
[ 2. Qdrant gRPC 读写 ]  server version = 1.11.5 → 建 collection / 写 / 检索
[ 3. TEI rerank ]        区分度 = 45056 倍（基线约 4 个数量级）
```

**这个 spike 不只是「跑一下看看」**——它检查的每一项都对应一个
**会静默失败**的地方（详见下节）。进 M1 之前必须全绿。

---

### 4. 语料入库

```bash
python3 tools/gen_cases.py                                             # 重新生成案例（可选，已提交）
mvn -q compile exec:java -Dexec.mainClass=com.atp.rag.ingest.IngestMain
```

一次建好消融实验需要的全部 collection：

| collection | 内容 | 对应消融表 |
|---|---|---|
| `atp_all_fixed` | 158 点（78 chunk + 80 案例） | 第 1 行 baseline |
| `atp_all_heading` | 264 点（184 chunk + 80 案例） | 第 2 行 |
| `atp_docs_heading` / `atp_cases_heading` | 184 / 80 点 | 第 3 行及以后 |

collection 名由 `RagConfig` 按配置派生，不写死在 `.env` —— 否则跑第 2 组会覆盖第 1 组的数据，
最后整张消融表只有末行是真的（见 `DECISIONS.md` D-005）。

**看看检索现在什么样**：

```bash
mvn -q compile exec:java -Dexec.mainClass=com.atp.rag.cli.SearchProbe
```

> ⚠️ `mvn -q` 会抑制 `exec:java` 转发的应用日志。要看 INFO 日志就去掉 `-q`。
> 关键结果一律走 `System.out`，不受影响。

## 一条贯穿始终的主线：把静默失败变成显式检查

这个项目踩到的坑有个共同特征：**系统照常运行，只是结果悄悄错了**。

| 坑 | 表现 | 真实后果 |
|---|---|---|
| TEI 退化到 CPU | 容器正常、`/health` 200、正常返回 1024 维向量 | 14 核满载，GPU 利用率 0%，**靠 CPU 风扇声才发现** |
| Qdrant proto 字段迁移 | 命中、score、payload 全对，只有向量是空的 | 报错指向「向量长度不一致」，根因却在 server 版本 |
| slf4j provider 版本错配 | 不报错 | 日志**全部消失**，等要排查检索问题时才付出代价 |
| rerank 打分坏掉 | 照常返回分数 | 消融表整张被污染，**比没有 rerank 严重得多** |

所以 spike 里的每项检查都不是走过场，而是把上面这些变成 fail-fast。
被问「你怎么保证检索质量」时，这比罗列技术栈有力。

---

## 消融实验

> M5 产出，当前为空。

| # | 配置 | Recall@5 | MRR@10 | 拒答率(D类) |
|---|---|---|---|---|
| 1 | baseline：固定 512 切分 + 单 collection + 纯向量 top5 | — | — | — |
| 2 | + chunk 带标题路径前缀 | — | — | — |
| 3 | + 双 collection 与查询路由 | — | — | — |
| 4 | + bge-reranker 精排（召回 40 → 精排 5） | — | — | — |
| 5 | + query 改写 / XPath lint 通道 | — | — | — |
| 6 | + 拒答 prompt 约束 | — | — | — |
| 7 | 换 Qwen3-Embedding-0.6B（其余同第 6 行） | — | — | — |

评估集 40 条，分四类：知识问答 15 / 案例检索 10 / **跨语言 8** / **应拒答 7**。

跨语言那 8 条（中文问 → 日文规范文档）是纯中文 demo 做不出来的展示点；
应拒答那 7 条考察抗幻觉——大多数 RAG demo 一问就编。

---

## 相关文档

- `DECISIONS.md` — 决策记录，每条含背景 / 选项 / 决定 / 代价
- `../00-SHARED-CONTEXT.md` — 虚构世界观、领域模型、模型服务拓扑
- `../01-HANDOFF-demo1-rag.md` — 完整架构与里程碑
