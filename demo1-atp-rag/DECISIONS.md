# 决策记录 — demo1

> 边做边记，不事后补。面试官最爱问「为什么选 X 不选 Y」，
> 而**踩坑 + 定位根因 + 做出取舍**的过程比「一次就成功」有说服力得多。

格式：每条记 **背景 / 选项 / 决定 / 代价**。有实测数据的一律附上。

---

## D-001 — langchain4j 锁定 0.35.0

**日期**：2026-08-08（M0）

**背景**：平台是 Java 8 + Spring 4 的遗留系统，demo 必须在 JDK 8 上跑才有意义。

**事实**（实测字节码 major version，`unzip -p <jar> <class> | od -j7 -N1`）：

| jar | major | 对应 JDK |
|---|---|---|
| langchain4j-core **0.35.0** | 52 | Java 8 ✅ |
| langchain4j-core **0.36.0** | 61 | Java 17 ❌ |

整棵依赖树也一并验过，全部 ≤ 52：
`grpc-*` 1.65.1 / `qdrant-client` 1.11.0 / `okhttp` 4.12.0 / `protobuf-java` 3.25.1
/ `jackson` 2.17.2 / `guava` 32.0.0-jre 均为 52，
`failureaccess`(51) 和 `jackson-annotations`(50) 更老，向下兼容无碍。

**决定**：锁 `0.35.0`，pom 里写死不用 range。

**代价**：拿不到 0.36+ 的新特性；langchain4j 官方对 0.35 已停止维护。
后果之一直接体现在 D-002。

---

## D-002 — Qdrant server 降级到 v1.11.5（而非自己实现 EmbeddingStore）

**日期**：2026-08-08（M0）

**背景**：M0 spike 第 2 步失败，报错是

```
Length of vector a (0) must be equal to the length of vector b (1024)
```

**这个报错完全指不到根因上**，值得完整记一遍定位过程。

### 定位

先排除 Java 8 —— 依赖字节码全部 ≤ 52（见 D-001），
且 collection 建成功、点也写进去了，gRPC 通道本身是好的。

用 qdrant-client 直接发 gRPC 请求，打印原始 `ScoredPoint`：

```
score   = 1.0                                        ← 对
payload = {text=string_value:"a"}                    ← 对
vectors = vector { 101: { 1: "\000\000\200?..." } }  ← 数据在 field 101
```

Qdrant **命中了、打分对了、payload 也回来了**，唯独向量落在字段 101。

根因：**Qdrant 1.12 起把 dense 向量从 `Vector.data`(field 1)
挪进了 oneof 的 `dense`(field 101)**（为支持 sparse / multi-dense）。
qdrant-client 1.11.0 的 proto 里没有 101，当成 unknown field 丢掉，
`getDataList()` 于是返回**空**向量。

而 langchain4j 的 `QdrantEmbeddingStore` **不信任服务端返回的 score**，
要拿召回点的向量跟 query 向量在客户端重算一遍 cosine —— 空向量在这里炸掉。

> 服务机上跑的是 `qdrant/qdrant:latest`，当时解析到 **1.19.0**。

### 三种组合的实测结果

| client | server | 结果 |
|---|---|---|
| 1.11.0（langchain4j 0.35 传递依赖） | 1.19.0 | ❌ 向量解析为空 |
| **1.14.1** | — | ❌ `ScoredPoint.getVectors()` 返回类型已改为 `VectorsOutput`，langchain4j 编译期绑的是旧签名 → `NoSuchMethodError`。**升级 client 救不了** |
| 1.11.0 | **v1.11.5** | ✅ 返回 `data: 1.0 ...`，正常解析 |

结论：这是 **langchain4j-qdrant 0.35.0 锁死在旧版 Qdrant proto**，
不是 Java 8 的问题，也不是配置问题。

### 选项

| | 方案 | 工作量 |
|---|---|---|
| A | 降级 server 到 v1.11.5 | 零代码 |
| B | 自己实现 `EmbeddingStore`，`search` 走 Qdrant REST | 约半天 |
| C | 用 client 1.14.1 自己实现，走 gRPC | 与 B 相当，多一层 proto 成本 |

补充事实（影响判断）：**坏的只有 `search` 一个方法**。
写入路径走 `upsert`，proto 没变，langchain4j 那套是好的。
所以 B 可以只是个薄 wrapper：`add*` 委托给 `QdrantEmbeddingStore`，只重写 `search`。

**决定：A（降级到 v1.11.5）。**

理由不是 B 不好，而是**写它的时机不是 M0**：

1. M0 的任务是证明链路能通，不是把架构建到最优。卡在 M0 会拖累消融表（本 demo 的核心交付物）。
2. 「hybrid search 会不会受影响」这个顾虑不成立 ——
   **langchain4j 0.35 本来就不支持 sparse vector**，那条路无论 server 什么版本都得绕开它自己写。
   降级在这件事上没有额外损失。
3. B 迟早要写（做 hybrid search 那行消融时必须写），但**在 M5 写更合理**：
   那时 payload schema 已定、filter 需求清楚，不用盲写；
   叙事上也是「为了新增一行消融数据而做的优化」，而不是「绕开一个版本坑」。
4. 可逆 —— 我们用到的那部分 REST API 在 v1.11.5 和 1.19 之间兼容，将来换回去只是换个容器。

**代价**：
- server 停在 v1.11.5（2024 年末）。被问起就照实说：langchain4j 0.35 是最后一个 Java 8 版本，它绑死了 Qdrant 1.11 的 proto。
- 镜像 tag 从 `latest` 改成 **`v1.11.5`**。用 `latest` 正是这次踩坑的直接原因 —— 基础设施不该浮动。
- 旧 volume（1.19 的 storage 格式 1.11 读不了）已重建。当时 `collections` 为空，无数据损失。

### 顺带产出

spike 里加了一道 **Qdrant server 版本前置检查**，
命中不兼容版本时直接抛出带根因的异常，而不是等 `CosineSimilarity` 抛那句莫名其妙的话。

理由和共享文档 §2.1(e) 三项冒烟测试是同一个：
**把会静默失败的地方变成显式检查**。这个坑只坏一半（score / payload 全对，只有向量是空的），
正是最难发现的那一类。

### 面试可讲的点

- 「score 和 payload 都对，只有向量是空的」—— 半静默失败比全崩难查
- 光看报错会一路查到 embedding 维度上去，实际根因在 proto 字段迁移
- 升级依赖是最直觉的反应，但这里升级 client 只会把错误换成 `NoSuchMethodError`
- 定位手段：直接打印原始 `ScoredPoint` 的 protobuf 文本，unknown field 会以字段号裸露出来

---

## D-003 — slf4j provider 必须与 api 同为 2.x

**日期**：2026-08-08（M0）

**背景**：spike 首次运行时日志里出现

```
SLF4J: No SLF4J providers were found.
SLF4J: Defaulting to no-operation (NOP) logger implementation
```

langchain4j 0.35.0 依赖 `slf4j-api` **2.0.7**，而 pom 里配的是 `slf4j-simple` **1.7.36**。
1.7 的 provider 走 `StaticLoggerBinder`，2.x 走 `ServiceLoader`，**两者互不识别**。

**决定**：`slf4j-api` 与 `slf4j-simple` 一起 pin 到 **2.0.13**（2.0.x 的 baseline 仍是 Java 8）。

**为什么值得单独记一条**：混用**不会报错**，只会静默降级成 NOP —— 日志全部消失。
真正的代价要到排查检索问题时才付：那时候没有日志。

这和 D-002、和 TEI 那个 CUDA compat 坑（共享文档 §2.1(a)）是**同一类问题的三个实例**：
系统照常运行，只是结果悄悄错了。

---

## D-004 — 配置统一从仓库根 `.env` 读，代码零硬编码

**日期**：2026-08-08（M0）

**决定**：`com.atp.rag.config.Env` 是唯一的配置入口。从工作目录逐级向上找 `.env`，
所以在 demo1 目录和仓库根目录下都能直接跑。

两个实现细节值得记：

- **`${VAR}` 展开要自己做**。`.env` 里写的是
  `EMBEDDING_BASE_URL=http://${SERVICE_HOST}:8081`，
  shell `source` 会自动展开，但 Java 按行读文件不会。
- **进程环境变量优先于 `.env`**，方便消融实验临时覆盖（比如第 7 行换 embedding 模型）。

**M0 期间顺手修掉的**：`.env` 里 `SERVICE_HOST` 误写成 `192.169.0.101`（正确是 `192.168`）。
`.env` 不进版本库，所以只能在这里记一笔。

**新增 `QDRANT_GRPC_PORT=6334`**（已同步进 `.env.example`）：
langchain4j-qdrant 走 gRPC 是 6334，而原来只有 REST 的 6333。
建 collection 这类管理操作用 REST 更方便，读写向量走 gRPC，两个端口都要。

---

## D-005 — collection 名由配置派生，不写死在 .env

**日期**：2026-08-09（M2）

**背景**：消融表要跑 6~7 组配置，其中前 3 组的向量各不相同（切分策略变了），
第 7 组换 embedding 模型（向量空间整个变了）。

**问题**：如果所有配置共用 `atp_docs` / `atp_cases` 这两个固定名字，
跑第 2 组会覆盖第 1 组的数据 —— 最后整张消融表只有最后一行是真的，
而且**不会报错**，因为每一组单独看都跑得好好的。

**决定**：collection 名由 `RagConfig` 按规则生成：

```
{前缀}_{docs|cases|all}_{fixed|heading}[_{embedding标签}]
```

例：`atp_all_fixed`（baseline）、`atp_docs_heading`、`atp_docs_heading_qwen3`。

`.env` 里只留 `QDRANT_COLLECTION_PREFIX=atp`，原来的
`QDRANT_COLLECTION_DOCS` / `QDRANT_COLLECTION_CASES` 删掉。

**代价**：collection 名不再能从 `.env` 一眼看全，得看 `RagConfig.collectionName()` 的规则。
换来的是各组数据天然隔离、可反复重跑、评估阶段不必临时灌数据。

---

## D-006 — 切分用字符数而非 token 数

**日期**：2026-08-09（M2）

**背景**：交接文档 §5.3 写的是「固定 512 切分」，单位是 token。

**问题**：Java 8 这边没有 bge-m3 的 tokenizer。硬凑一个（比如借 tiktoken 或
langchain4j 的 `OpenAiTokenizer`）会得到一个**精确但错误**的数字 ——
那是 GPT 系的分词，和 bge-m3 的 XLM-R 分词完全不是一回事，中日文上差得尤其远。

**决定**：用字符数，配置项显式命名为 `CHUNK_SIZE_CHARS=700`（中日文约合 500~700 token）。

**理由**：bge-m3 的输入上限是 8192 token，我们的 chunk 离它很远，
所以精确 token 数在这里不影响任何结果。真正要保证的是
**两种切分策略用同一个上限**，否则「标题路径更好」可能只是「chunk 更小」的假象。
这一点由 `SplitterTest.bothStrategiesRespectSameSizeLimit()` 固定住。

**诚实标注**：消融表里这一行会写「固定 700 字符」而不是「固定 512 token」。
被问起就照实说 —— 用一个假装精确的 token 数反而更难解释。

---

## D-007 — Qdrant 版本检查前移到客户端构造

**日期**：2026-08-09（M2）

**背景**：M0 时把版本检查加在了 spike 里。M2 开发期间服务重启过一次，
让我意识到这个位置不够。

**问题**：**入库阶段完全不会因为版本不对而报错。** 写入走 upsert，proto 没变，
158 个点会规规矩矩地写进去，点数核对也能通过。
问题要到检索时才以 `Length of vector a (0)` 的形式爆出来，而那个报错指不到根因。

也就是说，只在 spike 里检查等于「只在我记得跑 spike 的时候才检查」。

**决定**：把检查挪进 `ModelFactory.qdrantClient()` —— 入库、检索、评估都必须经过这里，
绕不过去。

**顺带**：`CorpusIngestor` 加了入库后的**检索冒烟**。点数对、维度对、payload 对，
都不能证明检索可用；必须真的查一次。这一步同时也能发现 embedding 服务中途退化
（比如 TEI 悄悄换到 CPU），避免带着坏数据去跑 M4 的评估。

---

## D-008 — 入库用全量重建，不做增量

**日期**：2026-08-09（M2）

**决定**：每次入库都 drop 掉 collection 重建。

**理由**：语料是全量生成的，增量 upsert 会让旧 chunk 残留在库里。
**切分策略一改，旧 chunk 的边界就不对了，但它们还会被召回** ——
表现为「某几个 query 莫名其妙地差」，在评估里极难定位。

**这个做法不能照搬到生产**：真实平台的案例每天新增，全量重建不现实。
生产的正确做法是按 `case_id` upsert + 软删标记，语料版本号进 payload，
检索时按版本过滤。这是面试预演问题第 4 条，答案要能说清楚
**为什么 demo 这么做、生产为什么不能这么做**。

---

## 待决 / 下一步

- **hybrid search（sparse + dense）** — langchain4j 0.35 不支持，必须自己实现。
  计划在 M5 连同 D-002 的方案 B 一起做，作为消融表的一行。
- **Qwen3-Embedding-0.6B 对比**（消融表第 7 行）—— 它是**非对称**模型，
  query 侧要加 instruction prefix、document 侧不加，
  而 langchain4j 0.35 的 `EmbeddingModel` 接口里两者走同一个方法，没地方区分。
  需自己实现接口或绕开该抽象。详见共享文档 §3.1。
- **增量更新策略** — 案例每天都在新增，全量重建还是增量？（面试预演问题第 4 条）
