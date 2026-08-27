# 决策记录 — demo1

> ## 🗄️ 项目已归档（2026-08-19），但**这份文件没有过期**
>
> 代码作废不等于坑没踩过。下面 26 条里，只有版本约束类（D-001/D-002/D-014）随 Java 8 一起失效，
> **其余全部仍是面试素材**，尤其是「静默失败」那一族（D-002/D-003/D-010/D-013/D-015/D-024/D-025）
> 和「分数尺度不可比」那条主线（D-013 → D-016）。
>
> 已提炼进 **[`../03-HANDOFF-rag-v2.md`](../03-HANDOFF-rag-v2.md) §2**（七条跨项目经验）。
> 那一节是摘要，**这里是原始现场**——面试被追问细节时回来查这份。

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

## D-009 — 不用 AiServices，自己装配问答链路

**日期**：2026-08-09（M3）

**背景**：langchain4j 的 `AiServices` 能把 RAG 全链路包成一个返回 String 的接口方法，
样板代码最少，也是官方推荐的用法。

**问题**：它藏起来的恰恰是这个 demo 最需要的东西 ——
召回了哪些片段、路由走了哪条、rerank 把哪条从第几名提到了第几名、
模型引用的编号是否真的存在。**没有这些就写不出消融表，也算不了引用准确率。**

**决定**：检索层自己编排（`AtpRetriever` → `RetrievalResult`），生成层自己拼 prompt。
`AiServices` 不用。

**代价**：多写了大约 150 行装配代码。换来的是可观测性，以及一个额外的好处 ——
**评估可以只跑检索、不经过生成**。M4 跑 40 条评估集算 Recall/MRR 时不必烧 token，
也不受 LLM 波动影响。

> 仍然用了 langchain4j 的标准扩展点 `ScoringModel`（见 D-010），
> 所以这不是「不用框架」，而是「在需要观测的地方不用它的黑盒封装」。

---

## D-010 — rerank 适配器的两个静默错误点

**日期**：2026-08-09（M3）

embedding 和生成都能直接用 langchain4j 的 OpenAI 客户端（TEI 和 DeepSeek 都兼容那套协议），
**只有 rerank 不行** —— TEI 的 `/rerank` 不是 OpenAI 标准，得自己实现 `ScoringModel`。

实现过程中有三个坑，其中两个是静默的：

**1. 请求字段是 `texts` 不是 `documents`**（会报 422，好排查）

**2. 响应 `[{index, score}]` 未排序，但 `scoreAll` 的契约是返回与输入同序的列表**

直接按响应顺序读，等于把分数配错文档：第 1 名的分数会被安到第 1 个输入文档头上，
而它可能排在第 7。结果是 rerank 照常运行、分数分布看着也正常，**只是排序悄悄错乱**。
消融表里会表现为「rerank 提升不明显」甚至「反而变差」。

对策：严格按 `index` 回填，并校验完整性（少填/重复/越界一律当场失败）。
`TeiScoringModelTest` 里有一条测试专门抓这种错位 —— 把相关文档放在 index 1，
若实现写错，最高分会跑到 index 0 上。

**3. TEI 有 batch 上限（默认 32）**

双 collection 各召回 20 条、去重后常常正好超过 32，返回
`422 batch size 39 > maximum allowed batch size 32`。这个是 M3 实测撞到的。

对策：分批调用。注意 TEI 返回的 `index` 是**批内相对下标** ——
实现上让每批各自回填成完整的小列表再顺序拼接，避免在批与全局之间做下标换算，
那种换算最容易在边界上错一位，而错位之后分数照样有值、照样能排序，只是全都配错了文档。

---

## D-011 — 路由错误的代价不对称，所以一律倒向 BOTH

**日期**：2026-08-09（M3）

**决定**：`AtpQueryRouter` = 规则短路 + LLM 兜底 + **不确定就查两边**。

**为什么不是纯 LLM 路由**（langchain4j 有现成的 `LanguageModelQueryRouter`）：

1. 每次提问多一次 LLM 调用，而路由在检索链路最前端，延迟加在所有查询上
2. LLM 不稳定，同一个问题偶尔分到不同类，评估时会看到无法复现的波动

**为什么不确定时倒向 BOTH**：代价不对称。多查一个 collection 只多花几十毫秒，
**查漏了就是彻底召回不到** —— 后面 rerank 再强也救不回来。

**踩到的**：信号词表里最初写了「支持吗」，而「ATP 支持 App 自动化吗」中间隔了字，
匹配不上，白白退化成 BOTH。这类 D 类问题走 BOTH 会召回一堆不相关案例，
反而增加模型编造的机会。信号词必须是**连续子串**，已在测试里钉住。

---

## D-012 — 拒答用固定标记，而不是 LLM-as-judge

**日期**：2026-08-09（M3）

**背景**：D 类评估用例（应拒答 7 条）要测「模型有没有编」。

**决定**：prompt 要求模型无法回答时原样输出 `[资料不足]` 这个标记，
评估侧用 `contains` 判定，**纯规则、零成本、完全稳定**。

同理，引用准确率也是纯规则测的：回答里引了 `[7]` 但只给了 5 条资料，
就说明它在编 —— 这是可机器检测的幻觉信号。

**放弃的**：faithfulness 那类 LLM-as-judge 评分。40 条的规模上又慢又贵又不稳，
而且引出一个新问题：judge 自己的可靠性谁来评？

面试被问起时的说法：**「我知道 LLM-as-judge，但在这个规模下引用准确率是更可靠的代理指标」**
比堆一套评分体系更显清醒。

### 实测发现：D 类不是两种，是一条光谱

最初以为「应拒答」分两种，跑通生成层后发现是<b>三个位置</b>，而中间那个是麻烦所在：

| 提问 | 语料状况 | 正确行为 | 实测稳定性 |
|---|---|---|---|
| 「ATP 支持 App 自动化吗」 | FAQ 明确写了不支持 | 正常答「不支持」+ 引用 | ✅ 稳定 |
| 「ASSERT_JSON 怎么用」 | 语料声明 action 集合**封闭** | ？ | ❌ **两轮结果不同** |
| 「执行器线程池怎么配」 | 完全没覆盖 | 输出 `[资料不足]` | ✅ 稳定 |

中间那条起初被我归为「无依据的拒答」。实测发现不对 ——
M1 的《Action 参考》末尾有一句元陈述：
「Action 枚举是封闭的，如果你在别处看到一个不在这张表里的 action 名字，那它不存在」。
**这一句话把「语料没提到 ASSERT_JSON」变成了「语料间接否定了 ASSERT_JSON」**，
于是它跨到了「有依据」那一侧 —— 但只是勉强跨过去，模型两轮跑出了两种判断。

### 两个后果

**一，拒答标记的语义必须收窄到只有一件事。**

prompt 最初写的是「资料不足**或者**该功能不支持，就输出标记」。
这个「或者」让标记同时表达了「我不知道」和「答案是否定的」，
于是「拒答率」这个指标的分子里混着两类东西，而其中一类答对了反而被算成失败 ——
**指标会奖励一个更差的系统**。

改法：标记只表示「我没有资料所以答不了」，并在 prompt 里显式写明
「资料明确说不支持时，那是有依据的答案，不要输出标记」。改完后前后两条行为都对了。

**二，评估集的 D 类必须选光谱两端，避开边界地带。**

边界问题（像 ASSERT_JSON）会给拒答率带上**不可复现的波动**，
而那种波动看起来像是「某项优化有效/无效」。40 条的样本量下，
一条边界用例的抖动就能造出几个百分点的假象。

M4 写评估集时：D 类的 7 条只取「明确否定」和「完全无覆盖」两端，
边界用例可以留一条<b>单独标注</b>、不计入主指标，作为「已知的不稳定项」如实说明。

> 这条是整个项目里我最没预料到的一个发现：
> **语料里一句看似无害的元陈述，改变了评估用例的性质。**
> 而且它是在实测中撞出来的 —— 光看代码和语料都想不到。

---

## D-013 — rerank 截断必须用相对阈值（M3 实测出的真实 bug）

**日期**：2026-08-09（M3 自查）

**背景**：精排后加了个 `minScore` 截断，初衷正当 ——
低分片段与其塞进 prompt，不如不给，无关上下文会诱导模型编造。初值拍了 `0.01`。

**问题**：写 review 说明时顺手量了一下分数分布，发现事情不对：

| 查询类型 | rerank top1 分数范围 |
|---|---|
| 文档类（「点击按钮用哪种等待策略」） | 0.59 ~ 0.99 |
| **案例类**（「找几个购物车案例」） | **0.008 ~ 0.38** |

差 1~2 个数量级。根因是 reranker 对
**「自然语言 query vs 结构化步骤序列」**的相关性打分天然偏低 ——
案例渲染出来是「1. OPEN_URL … 2. INPUT …」这样的步骤流，
和自然语言问句的形式差异太大，哪怕内容完全对得上，绝对分也上不去。

于是绝对阈值 `0.01` 对案例类是灾难性的：

```
有没有涉及文件上传的案例    候选 20 → 采用 0 条
找几个支付失败的案例        候选 20 → 采用 1 条
```

**「有没有涉及文件上传的案例」恰好是交接文档 §5.1 点名的 B 类评估用例。**
带着这个 bug 跑 M4，B 类 Recall 会直接归零，
而那张表上会显示成「案例检索效果差」—— 一个完全错误的结论，
并且会引导我去优化根本没问题的地方。

**决定**：改成 `阈值 = max(绝对下限, top1 × 相对比例)`，取 `0.0005` 与 `2%`。

- 文档类 top1≈0.95 → 门槛约 0.019
- 案例类 top1≈0.008 → 退到绝对下限 0.0005

修复后：文件上传 0→5 条，支付失败 1→5 条，文档类不变。
而「报表导出的案例有哪些」仍停在 4 条（第 5 条比 top1 差两个数量级），
说明相对阈值确实还在过滤，不是简单放行。

**为什么这个坑值得单独记**：

它和 D-002（Qdrant 空向量）、D-010（rerank 分数错位）是同一族 ——
**系统照常运行，只是某一类输入悄悄失效**。
但这个更阴险的地方在于：**它是我为了提升质量而主动加的东西**。
一个本意是「过滤噪音」的阈值，因为忽略了两类内容的分数尺度不可比，
反而把一整类查询清零了。

而且它<b>差点就混进消融表了</b> —— 如果不是写 review 指南时顺手量了分数分布，
M4 跑出来的 B 类数字会是错的，我还会拿着它去面试。

**教训**：任何跨异构内容的阈值都不能用绝对值。
分数是模型给的，而模型对不同形态的输入有不同的尺度。

---

## D-014 — 改用 Spring Boot 2.7 + Java 8（推翻了原来的纯 Java SE）

**日期**：2026-08-09（M3 之后的返工）

**背景**：M0~M3 一直是纯 Java SE + Maven，几个 `main` 方法跑。
交接文档 §2.1 的依赖清单里没有 Spring，§6 的目录结构写的是 `cli/Main`，所以当时照做了。

**问题**：review 时被指出 ——
**ATP 是企业级 Spring Boot 配置平台，用 Java SE 跑等于造一堆轮子。**

清点了一下，确实如此：

| 手写的 | Spring Boot 原生 |
|---|---|
| `Env` 178 行：找 `.env`、解析、`${VAR}` 展开、类型转换 | `spring.config.import` + 占位符解析 + `@ConfigurationProperties` |
| `ModelFactory` 手工装配，每个调用方各写一遍 `QdrantClient` 的 try-finally | `@Bean(destroyMethod = "close")` |
| `AtpRetriever` 构造函数塞 5 个依赖，调用方手工 new | 构造器注入 |
| 5 个各自独立的 `main` | `ApplicationRunner` + `@ConditionalOnProperty` |
| `RagConfig.Builder` 自己从 `Env` 读默认值 | `@ConfigurationProperties` 直接绑定 |

而且同一个仓库里 demo2 早就用 `spring.config.import` 一行解决了配置加载，
demo1 这边还在手写 —— 两套做法并存本身就是个信号。

**决定**：改用 **Spring Boot 2.7.18 + Java 8**。版本不是自己挑的，是被约束定死的：

- **2.7.x 是最后一个支持 Java 8 的 Spring Boot 系列**（3.0 起要求 Java 17）
- **2.7.18 是 2.7 系列的最后一个版本**

这和 langchain4j 锁 0.35.0 是<b>同一类约束</b>，也是同一个叙事：
ATP 平台是 Java 8 + Spring 4 + MySQL 5.7 的遗留系统，
给它做的模块只能停在各生态最后一个支持 Java 8 的版本上。

**为什么这个改动让 demo 更真实**：企业里不会写一个纯 SE 的 `main` 方法当产品。
一个给 Spring 老平台做的知识助手，本来就该是 Spring Boot 应用。

**改了什么，没改什么**：

- 改：装配层（`AtpProperties` / `ModelConfig` / `RetrieverFactory` / 5 个 Runner）
- **没改**：切分、检索编排、rerank 适配、prompt 组装 —— 核心逻辑一行没动
- 测试：集成测试改成 `@SpringBootTest`，顺带把「容器装配是否正确」也一起验了

**顺带解决的**：D-003 那个 slf4j 版本错配（api 2.x 配 provider 1.7 静默走 NOP）
不复存在了 —— 版本一致性由 `spring-boot-starter-parent` 的 BOM 保证，不再靠手工 pin。

**顺带踩到的**：`@ConditionalOnProperty(havingValue = "", matchIfMissing = true)`
的语义反直觉。看起来是「属性缺失或为空」，实际上<b>空的 `havingValue` 等价于未指定</b>，
而未指定时的规则是「属性存在且不等于 false 就匹配」——
于是 `--atp.task=spike` 也命中了「打印用法」那个 Runner，
用法提示跟在每个任务输出后面一起打出来。改用 `@ConditionalOnExpression` 判空串才对。

**代价**：
- 依赖变多，启动慢了约 1.4 秒（对 CLI 无感，对评估是一次性成本）
- `RagConfig` 不能是 bean —— 消融实验要在一次进程里造六七个不同配置轮流跑，
  而容器里的 bean 是单例。所以模型客户端交给容器（重、有连接、该复用），
  检索器按配置临时创建（轻、随配置变），中间用 `RetrieverFactory` 衔接

**教训**：交接文档的依赖清单是<b>建议</b>不是红线。
红线只有三条：Java 8、langchain4j 0.35、评估优先。
当「照着清单做」和「贴合世界观」冲突时，该重新想，而不是照做。

---

## D-015 — 用框架原生能力时，别把自己加固过的东西弄丢

**日期**：2026-08-09（D-014 之后的自查，由 demo2 那边提醒）

**背景**：D-014 把手写的 `Env`（178 行）换成了 Spring Boot 的
`spring.config.import` + `@ConfigurationProperties`。轮子是去掉了，
但 demo2 同步过来一条对称的经验：

> **你手写 Env 的问题是造轮子，我用原生的问题是它默认太安静。**

`spring.config.import` 必须带 `optional:` 前缀 —— CI 和容器里没有 `.env`，
配置靠环境变量注入，不加就直接启动失败。**代价是配置源缺失时零提示。**

**验证**：把 `ATP_DOTENV_DIR` 指到一个不存在的目录跑一遍，报错是

```
java.lang.IllegalArgumentException: Expected URL scheme 'http' or 'https' but no colon was found
```

这是 okhttp 构造 embedding 客户端时抛的。未解析的占位符被当成字面值一路传下去，
最后在一个**完全无关**的地方炸掉。人会去查 URL 拼接、查 TEI 服务、查 okhttp 版本，
唯独想不到是 `.env` 没找到。

**而手写那版是有这个加固的** —— 它会说「没有找到任何 .env 文件，从 X 向上找过：[...]」，
还会区分「路径问题」和「配置漏写」。换成框架原生能力时，**这个能力被我丢掉了，而且退化得更糟**。

**决定**：补 `ConfigSourceCheck implements EnvironmentPostProcessor`，
在容器建立之前检查必填项能否解析，缺失时抛出带路径、带处理建议的异常。

两个实现要点：

- **走 `META-INF/spring.factories` 注册**，不能用 `@Component` ——
  它要在组件扫描发生之前就运行
- **判据是「值最终拿不到」，不是「文件不存在」**。CI / 容器里没有 `.env` 是正常状态，
  只要环境变量提供了值就放行。已实测三条路径：有 `.env` 正常跑、
  缺 `.env` 报根因、纯环境变量注入也放行

**这条记下来的真正价值不在这个类本身**：

D-014 是一次「用框架替掉手写代码」的重构，而重构最容易丢的正是
**那些当初为了填坑而加的、看起来不属于主流程的东西**。
`Env.require()` 里那段区分「路径问题 vs 配置漏写」的报错，
是 M1 期间踩过 worktree 软链问题之后专门加的（见 M1 的 `Env` 加固）。
换框架时我只看了「功能有没有对齐」，没看「加固有没有对齐」。

**教训**：替换实现时，除了对齐功能，还要专门过一遍
「上一版为了防什么而写的代码」。那部分往往没有测试覆盖 ——
因为它防的是环境问题，不是逻辑问题。

---

## D-016 — 路由决定配额，不决定开关；名额也要按组分配

**日期**：2026-08-09（M3 合并后的自查）

**背景**：`mvn clean test` 偶尔失败一项，重跑就好 —— 典型的 flaky。
如果放着不管，M4 跑 40 条 × 6 组配置时，消融表会带上无法复现的抖动。

### 根因不是测试写得脆，是设计错了

失败的断言是「知识问答应该召回到文档章节」，而实际召回：

```
Q: 点击按钮之前应该用哪种等待策略        ← 典型知识问答
实际: [ATP-ORDER-0003, ATP-ORDER-0002, ATP-SEARCH-0009, ...]   ← 5 条全是案例
```

链条是：这种问法没命中信号词表 → 落到 LLM 路由 → **LLM 判成 CASES** →
开关式实现下**完全不查文档库**。

**我在 D-011 里预见过这个风险**（「LLM 会不稳定，评估时会看到无法复现的波动」），
也写下了原则（「路由错误的代价不对称，所以不确定就查两边」），
但实现上仍然让路由当了开关 —— **判成 CASES 就一条文档都不查**，
这正是我说要避免的「查漏了就是彻底召回不到」。

### 修了两处，因为同一个根因咬了两次

**一、召回阶段：路由决定配额，不决定开关。**

被判为「不相关」的那一侧仍然查，只是 topK 从 20 降到 5。
路由判错的后果从「召回不到」降级为「配额不理想」。
案例库只有 80 条，多一次 Qdrant 查询的开销远小于一次彻底召回失败的代价，
而且 embedding 只算一次，没有额外模型调用。

**二、结果阶段：名额也要按组分配。**

改完第一处，新问题立刻出现：

```
Q: 帮我找几个购物车相关的案例参考       ← 路由判对了，案例也召回了
top5: 2 条案例 + 3 条文档              ← 文档凭高分挤了进来
```

因为 **rerank 分数在文档和案例之间不可比**（D-013 那个老问题）：
文档类 top1 落在 0.59~0.99，案例类只有 0.008~0.38。
全局按分数排序，等于让案例和文档比绝对分 —— 案例必输。
一个明确要案例的问题，返回的多数却是文档。

所以最终名额也按意图分配：主类占多数（5 席里 3 席），次类保留一席之地，
一侧配额用不满时名额让给另一侧。**「哪一类该占多数」由路由意图决定，
而不是由两个不可比的分数决定。**

### 顺带修的：信号词表漏了「选哪一个」类问法

「点击按钮之前应该用哪种等待策略」被 LLM 稳定判成 CASES。
补上「应该用 / 用哪 / 哪种 / 哪个 / 什么时候 / 有几种 / 有哪些」之后，
规则就能接住它，不必落到 LLM —— 又快又稳。

**这说明信号词表需要用评估集来校准**，而不是凭想象列。
M4 的 40 条评估集正好可以顺带检验路由准确率，这也该是 M4 的一个产出。

### 这条最值得记的地方

**同一个根因（rerank 分数在异构内容间不可比）在三个位置咬了我三次**：

| 位置 | 表现 | 修法 |
|---|---|---|
| 阈值截断（D-013） | 案例检索静默返回 0 条 | 绝对阈值 → 相对 top1 |
| 路由开关（本条一） | 路由判错就整类召回不到 | 开关 → 配额 |
| 结果排序（本条二） | 案例被文档凭高分挤掉 | 全局排序 → 按组分配名额 |

前两次我都在打补丁，直到第三次才意识到**它们是一个问题**。
教训不是「要写更多补丁」，而是：
**当异构内容共用一条打分链路时，任何「按分数比较」的地方都要先问一句
「这两个分数可比吗」** —— 阈值、排序、截断，一个都不能漏。

面试可讲：这比「我加了 rerank」有价值得多 ——
它说明我理解了 reranker 的分数是<b>相对于单次 query-doc 对</b>的，
不是一个跨内容类型的绝对质量分。

---

## D-017 — chunkSize / overlap 的真实依据（以及它在主策略下是死参数）

**日期**：2026-08-11

**背景**：被问「chunkSize 和 overlap 的依据是什么」。当时的答案是
「700 是从 512 token 倒推的，80 是拍的」—— 这个答案在面试里站不住。

### 于是去量了语料

```
小节长度（199 个）：中位数 174、p75 246、p90 347、p95 407、max 610 字符
段落长度（532 个）：中位数  58、p95 184、max 576 字符
```

### 然后发现了一件更要紧的事

| chunkSize | HEADING_PATH 切出的块数 | FIXED 切出的块数 |
|---|---|---|
| 300 | 114 | 118 |
| 400 |  96 |  79 |
| **700** |  **89** |  **41** |
| 1200 |  **89** |  25 |

**size 从 700 加到 1200，HEADING_PATH 纹丝不动。**

原因：语料最长的小节才 610 字符，全都短于 700，
`sliceBySize` 直接走 `length() <= size` 那个分支返回 ——
**overlap 那段循环从来没被执行过**。

**所以在主配置（HEADING_PATH）下，chunkSize 和 overlap 是死参数。**

### 这不是 bug，是这个策略的本意

标题路径切分的意义就是**让 chunk 边界由文档的语义结构决定，而不是由一个拍出来的数字决定**。
参数只在 baseline（FIXED）那一行真正生效，而 baseline 本来就是用来当对照组的。

**面试该这么答**：

> 「在我的语料上这两个参数对主策略不起作用 —— 小节 p95 是 407 字符，
> 而上限设的 700，从来没触发过切分。实际的 chunk 粒度是文档的标题结构决定的。
> 参数只在 baseline 那一行生效。」

这比「我设了 700」强得多，因为它显示**量过**。而且它自然引出下一个问题：
换一份长文档语料（比如真实的平台手册，一节可能几千字），这两个参数就会开始起作用，
那时才需要扫参。

### 顺带修正 D-006 的一处事实错误

D-006 写的理由是「Java 8 这边没有 bge-m3 的 tokenizer」—— **这句话是错的**。
TEI 提供了 `/tokenize` 端点，用的就是 bge-m3 自己的 XLM-R 分词器：

```
中文 15 字 → 12 tokens        700 字符 → 中文 399~476 tokens
日文 18 字 → 10 tokens                   日文 323~393 tokens
```

结论（用字符数）仍然成立，但**理由要改**：不是「拿不到 tokenizer」，
而是「切分时每块都调一次网络不划算，而且在这个语料上精确 token 数不影响任何结果」。

另外原来估的「700 字符约合 500~700 token」也是**高估**，实测 320~480。

---

## D-018 — 加入父子切块（small-to-big）作为第三种策略

**日期**：2026-08-11

**背景**：被问「有没有用父子切块」。答案是没有，而且这是个真实的缺失 ——
不是「不需要」。

### 为什么这个语料特别适合它

D-017 量出来的数字：**小节中位数只有 174 字符**。

短对检索是好事（语义集中、命中精准），对回答却是坏事 ——
命中「优先使用 data-testid 属性，其次是 name」这一句，
模型看不到同章节里的反例、理由和适用边界。

### 与已有策略的关系

`HEADING_PATH` 用**标题前缀**给小块补上下文，但前缀只说明「这段在讲什么」；
`PARENT_CHILD` 用**父块正文**补，补的是「完整的论述」。
两者解决同一个问题，深度不同 —— 所以它们该是消融表相邻的两行。

### 实测效果（8 篇手册）

| 策略 | 块数 | 平均 embed 文本 | 平均交出文本 |
|---|---|---|---|
| FIXED | 41 | 620 | 620 |
| HEADING_PATH | 89 | 257 | **227** |
| PARENT_CHILD | 89 | 257 | **593** |

**索引粒度完全一样（257 字符），交给模型的上下文从 227 涨到 593。** 这就是 small-to-big。

### 实现上的两个决定

**父块取二级章节，不取整篇文档。** 整篇太长会把无关内容一起塞进 prompt，
`##` 那一层是「一个完整论点」的天然边界。

**检索层要按父块去重。** 同一章节下的多个子块常常一起被召回（它们语义相近，
本来就该一起命中），但它们的 `rawText` 是同一份父块正文 ——
不去重的话同一段话会被喂给模型好几遍，白白挤掉别的内容。
为此 `RetrievedItem` 加了 `dedupeKey()`：有父块时按父块去重，否则按自身 anchor。

---

## D-019 — 图片转文字：做完整链路，但不部署 VLM

**日期**：2026-08-11

**背景**：被问「碰到图片要调用能识图的模型，图转文字描述才能落进向量库吧」。
这个判断是对的 —— bge-m3 是**纯文本**模型，一张截图不转文字就等于不存在。
而 ATP 的手册天然图很多，**图里往往就是答案**（报错长什么样、字段填在哪）。

### 三个事实决定了做法

1. 本项目语料是我生成的**纯文本**，一张图都没有
2. 服务机余量 8.7G 显存，跑 Qwen2.5-VL-3B **够用，技术上没有障碍**
3. DeepSeek 只有 `deepseek-v4-flash` / `deepseek-v4-pro`，**都不支持视觉**

### 决定：抽象与链路做完整，模型不部署

实现了：

- `ImageDescriber` 接口
- `AltTextImageDescriber` —— 降级实现，从 alt 文本 + **文件名关键词**提取信息。
  `case-edit-wait-strategy.png` → `case edit wait strategy`，
  原作者偷懒没写 alt 时，文件名往往是唯一线索
- `VlmImageDescriber` —— 走 OpenAI 兼容的 vision 协议（base64 内联图片），
  本地 vLLM / Ollama / 云端 VLM 都能接
- markdown 解析时识别 `![alt](path)` 并**替换**成描述
  （替换而非追加：图片语法本身对检索是纯噪音）
- Spring 装配：配了 `atp.vlm.base-url` 就用 VLM，没配自动降级，启动时打日志说明用的哪种

并且**造了一张 mock 截图放进《等待策略》验证链路**，实测输出：

```
［图片］案例编辑页里 CLICK 步骤的 wait_strategy 字段…（case edit wait strategy）
```

**为什么不部署模型**：为一条演示链路去部署、调通一个模型，投入产出比不划算 ——
何况 Blackwell（sm_120）的容器兼容性这个项目已经踩过一次（TEI 的 CUDA compat 坑），
再来一个模型就是再来一轮排查。接上真 VLM 只需在 `.env` 填一行 base-url。

### 一个容易被忽略的合规点

图片要以 base64 塞进请求体。**用云端 VLM 等于内部文档截图出网** ——
截图里可能有账号、内网地址、业务数据。这和本项目
「embedding / rerank 本地跑、只有生成走 API」的前提是冲突的。

所以真要上，应优先本地 VLM。这一点比「会不会调 VLM」更值得在面试里讲：
**多模态入库的合规边界比纯文本严格，因为截图携带的信息比文本更难预先审查。**

### 单张图失败不能让整批入库失败

`VlmImageDescriber` 捕获所有异常并降级成空描述。
15 篇文档里有一张图调不通，不该导致 264 个点全部灌不进去。

---

## D-020 — 框架能力调研：langchain4j 1.18.1 的切分能力与 0.35 完全一样

**日期**：2026-08-12

**背景**：被问「这两个切分策略是框架自带的还是手搓的」。答案是手搓，
顺手查了最新版有没有补上。

### 版本事实

`maven-metadata.xml` 的 `<latest>` = **1.18.1**（2026-08-11 更新）。

> ⚠️ 查版本要看 `maven-metadata.xml`，**不要用 search.maven.org 的 solrsearch 接口**
> —— 它的返回顺序不保证按版本排，我因此误报过「最新是 1.0.0」。

### 切分能力：从 0.35 到 1.18.1，零变化

两个版本的 splitter 完全相同，就六个：

```
DocumentByCharacter / ByLine / ByParagraph / ByRegex / BySentence / ByWord
+ HierarchicalDocumentSplitter（抽象基类）
```

搜 `markdown` / `heading` / `parent` 只匹配到那个抽象基类。
**跨越一年多的迭代，按标题切和父子切块依然没有。**

所以 `HeadingPathSplitter` 手搓是必要的，不是没找现成的。
根本原因是 `DocumentSplitter` 接口一进一出（`List<TextSegment> split(Document)`），
而这两个策略都要求**一块携带两份文本**（算向量的 vs 交给模型的），接口表达不了。

### 生态对比（回答「Python 那边多什么」）

| | Python LangChain | langchain4j 1.18.1 | Spring AI 1.1 |
|---|---|---|---|
| 按 Markdown 标题切 | ✅ | ❌ | ❌ |
| 语义切分 | ✅ | ❌ | ❌ |
| 父子 / small-to-big | ✅ | ❌ | ❌ |
| 多路融合 + **RRF** | ✅ `EnsembleRetriever` | ❌ | ❌ |
| 自查询（NL→filter） | ✅ | ❌ | ❌ |
| 有状态图编排 | ✅ LangGraph | 社区移植 LangGraph4j | 不成熟 |
| 评估平台 | ✅ LangSmith | ❌ | ❌ |

**Java 生态在检索组件上整体落后。** 但这不全是劣势：langchain4j 的 provider /
vectorstore 覆盖更广（20+ / 30+），Spring AI 的 Micrometer 可观测性更深。

面试讲法：不是「Java 不行」，而是「我知道这些组件在 Python 生态叫什么、
解决什么问题，并在没有现成实现的情况下自己落地了」。

### 0.35 的工具调用是完整的

顺带查证：**0.35 完整支持 tool calling**，不缺。
`@Tool` / `@P` / `ToolSpecification` / `DefaultToolExecutor` / `ToolProvider` 都有，
`AiServices.tools(...)` 跑完整的「调模型 → 执行工具 → 结果喂回 → 再调」循环，
`ChatLanguageModel.generate(messages, toolSpecifications)` 也支持。

1.18.1 新增的是**规模化之后的工程问题**，不是基础能力：

| 新增 | 解决什么 |
|---|---|
| `VectorToolSearchStrategy` | 工具几十上百个时，先用向量检索挑候选再给模型 |
| `ToolAwareRepromptExecutor` | 模型填错参数时的重试 |
| `guardrail.*` | 输入输出护栏 |

**这些对本项目都不构成损失** —— demo1 是纯 RAG 不调工具，demo2 用 Spring AI 2.0 不受 0.35 约束。
被问「锁 0.35 的代价」时要说清这一点，别笼统地说「拿不到新特性」。

---

## D-021 — overlap 的机制，以及我一度理解错的地方

**日期**：2026-08-12

**背景**：给切分细节文档举例时，我画了一张「第 1 块结尾的 XPath 被切坏」的图。
**那张图是编的**，被指出后回到原文验证。

### 实测：overlap 不改变切点

同一篇文档，只改 overlap：

```
overlap=80  第1块 677 字符，结尾 …- 按位置取第 n 个兄弟节点
overlap=0   第1块 677 字符，结尾 …- 按位置取第 n 个兄弟节点   ← 完全相同
```

**切点由 `preferBoundary()` 找的换行边界决定，与 overlap 无关。**
overlap 只决定「下一块从哪开始读」= `end1 - overlap`，而这个位置是**纯算术回退**，
不过 `preferBoundary`。

原文标注（`raw[560:760]`）：

```
- 按文本内容查找元素（`//button[te
⟪第2块从这里开始⟫   ← 597 = 677-80，落在 text() 中间
xt()="提交"]`）
- 从子元素向上找父元素（…）
- 按位置取第 n 个兄弟节点
⟪第1块到这里结束⟫   ← 677，落在换行处
```

### 所以 overlap 的真实代价

1. **冗余** —— 实测相邻块重叠正好 80 字符，同一段存两份（Chroma 扣 IoU 的原因）
2. **块首碎片** —— `xt()="提交"]` 这 11 个字符进 embedding，不是有效 XPath 也不是完整句子

### 一个更关键的发现：preferBoundary 和 overlap 功能重叠

在这个切点上，overlap 拉回来的 80 字符**恰好是第 1 块已经完整包含的三行列表** ——
零收益，纯冗余。

因为 `preferBoundary` 已经让切点落在自然边界、语义已经完整，
而 overlap 是为「切点可能落在句中」准备的保险。**有了前者，后者的收益被大幅削弱。**

这解释了为什么 Chroma 实测 overlap=0 更好，也说明**这个取舍取决于有没有边界感知的切分**：
纯字符硬切（无 preferBoundary）时 overlap 有价值，有边界感知时基本是纯成本。

---

## D-022 — 多格式语料链路：为什么 PDF/DOCX 是地基而不是加分项

**日期** 2026-08-13　**分支** `demo1/m3e-multiformat`

### 起因

用户指出：企业里手册和规范都是 PDF/DOCX 交付的，**markdown 只有开发者看**。
只支持 md 的链路在面试里等于没做 —— 「这个链路能不能吃我们公司的文档」是第一个会被问的问题。

### 为什么这不只是「多一个 parser」

现有的两个核心策略 **完全建立在 markdown 的 `##` 之上**：

```java
int level = headingLevel(line);          // 数 # 的个数
stack.add(line.substring(level + 1));    // 层级路径从这来
```

`PDFTextStripper` 抽出来的是一坨扁平文本，没有任何 `#`。层级栈无米下锅
→ `HEADING_PATH` 和 `PARENT_CHILD` **双双退化成 FIXED**。
也就是说，消融表第 2、3 行在 PDF 语料上直接归零。

### 依赖选型（都实测过字节码）

| 依赖 | 版本 | major | 说明 |
|---|---|---|---|
| `org.apache.pdfbox:pdfbox` | 2.0.30 | 50 | **不能升 3.x**：API 全变（`PDDocument.load` → `PDFParser`+`RandomAccessRead`） |
| `org.apache.poi:poi-ooxml` | 5.4.1 | 52 | Java 8 兼容 |
| `langchain4j-document-parser-apache-pdfbox` | 0.35.0 | 52 | 查过但**没用** —— 它只给纯文本，拿不到 outline |

### DOCX 为什么不走 Tika

Spring AI 的 `TikaDocumentReader` 走 Tika，把 docx 抽成一坨扁平文本，
**段落样式全丢**。而 docx 的层级恰恰存在样式里：

```xml
<w:pPr><w:pStyle w:val="Heading2"/><w:outlineLvl w:val="1"/></w:pPr>
```

POI 的 `XWPFParagraph.getStyle()` 直接能读。所以走 POI，不走 Tika。

判据用了两条（缺一不可）：样式名 `Heading\d`，以及 `outlineLvl` ——
后者是给「用自定义样式名的企业模板」兜底的，Word 导航窗格本身也靠它。

### 结果

三种格式入库，**chunk 数完全一致**：

```
atp_docs_heading       199 chunk   (markdown)
atp_docs_heading_pdf   199 chunk
atp_docs_heading_docx  199 chunk
```

层级三方对齐，所以 `golden_ids`（`sourceId#小节标题`）三种格式通用，
消融表能加一组「同样的问题、同样的策略，只换语料格式」的单变量对照。

---

## D-023 — 移植 Spring AI 的 PDF outline 切块（以及不能照抄的地方）

**日期** 2026-08-13

### 出处与改动

算法移植自 Spring AI 的 `ParagraphManager` / `ParagraphPdfDocumentReader`
（Apache 2.0，作者 Christian Tzolov）。移植而非依赖：Spring AI 要 Java 17，
且按 PDFBox 3.x 写。

它的核心手法很漂亮 —— **不是按整页抽文本，而是按书签的 Y 坐标切矩形**，
所以同一页上的几个小节能被正确分开。手册里一页放三四个小节是常态，
只按页码的话它们会粘成一块。

### ⚠️ 三处不能照抄

**1. 坐标系原点不同（会静默出错）**

```
书签的 top（PDPageXYZDestination）   原点在左下角，Y 向上
PDFTextStripperByArea 的 Rectangle  原点在左上角，Y 向下
```

必须 `Rectangle.y = pageHeight - pdfTop`。原版是直接填的。
照抄会把区域搬到页面的另一半，抽出来的文本张冠李戴 —— **而且不报错**。
这套换算由 `PdfSpikeTest` 第 3 个用例钉死。

**2. 原版没有 headingPath**

`Paragraph` 里有 `parent` 和 `children`，但 `get()` 只是 flatten 后按相邻对抽文本，
**父子关系从没被用来生成标题路径**。而标题路径正是本项目 `HEADING_PATH` 的输入，
所以补了 `Node.headingPath(skipLevelsAbove)` 沿 parent 链拼。

**3. 原版没有图片处理**

`ParagraphPdfDocumentReader` 只抽文本。PDF 内嵌图片的抽取、转文字、原图留存
全部要自己做（见 D-025）。

### Spring AI 也没做的：无 outline 降级

原版直接 `Assert` 抛异常，让你改用 `PagePdfDocumentReader`。
本项目同样抛异常但由调用方决定降级路径 —— **不静默返回空文档**，
那会让一整篇内容凭空消失且无人察觉。

（按字号启发式恢复标题的降级路径尚未实现，见「待决」。）

---

## D-024 — 生成 PDF/DOCX 测试语料时踩的三个坑

**日期** 2026-08-13

不能爬真实 PDF（项目红线），所以语料要自己造。本机没有 pandoc/libreoffice，
但 PDFBox 能写 PDF、POI 能写 docx —— 生成器本身就是 Java，零外部工具。

### 坑 1：Noto CJK 嵌不进去

```
UnsupportedOperationException: OTF fonts do not have a glyf table
```

`NotoSansCJK-Regular.ttc` 是 **OpenType/CFF**（PostScript 轮廓），
而 **PDFBox 2.x 只能嵌 TrueType（glyf）** —— CFF 嵌入是 3.0 才支持的。

系统上扫了一遍，唯一含 `glyf` 的 CJK 字体是 `DroidSansFallbackFull.ttf`。
所以字体选择不能按「哪个好看」，要**按能力探测**：逐个候选真的 load 一次，
第一个成功的用（`CjkFontLoader`）。

### 坑 2：Droid Sans Fallback 没有 ASCII

```
No glyph for U+0043 (C) in font DroidSansFallback
```

它是 fallback 字体，**只有 CJK 字形，连大写 C 都没有**。而语料里满是
`CLICK` / `CLICKABLE` / `data-testid`。

解法是 `MixedFont`：CJK 走外挂字体、拉丁走内置 Helvetica，
**按 encode 实际探测**而不是按 Unicode 范围猜 —— 全角标点、破折号、各种符号
散落在十几个区段里，范围判断在真实语料上必碎。

### 坑 3：书签坐标要在绘制前记

`drawLine()` 会把 `cursorY` 往下推一整行。原来在 drawLine **之后**才算书签坐标，
于是书签指到了标题文字的**下方** —— 解析侧从那个 Y 往下切，标题自己不在区域内，
反而把下一节的标题吃了进去。

症状：PDF 比 md 多出 4 个 section（那些「有标题无正文」的过渡层级
抽到的正文正好是下一节的标题）。由 `PdfDocumentTest` 钉死。

### 顺带发现的两个既存 bug

**① `MarkdownDocument.flush()` 丢掉了每篇文档的引言**

```java
if (!content.isEmpty() && !stack.isEmpty()) {   // ← 第二个条件是错的
```

`# 标题` 之后、第一个 `##` 之前的引言，标题栈是空的，于是 **15 篇全被丢**，
合计 1464 字符。丢的还都是高价值内容：

```
STD-001  制定日：2023-04-01 ／ 最終改訂：2025-11-20 ／ 管理：QA 基盤チーム
```

「这份规范谁维护、什么时候改的」在库里根本没有答案，且不报错。
**这个 bug 一直存在于 md 入库链路**，是比对 md 与 PDF 文本层的字符差异才发现的。

**② 图片路径 resolve 错了根**

语料里 `img/manual/x.png` 相对的是 `docs/` 根，不是 md 文件所在目录。
按后者 resolve 得到 `docs/manual/img/manual/…`，找不到文件 —— 静默跳过。
改成按候选根目录依次试（`ImagePathResolver`）。

---

## D-025 — 图片链路：转文字进 embedding，原图进 payload

**日期** 2026-08-13

### 两样都要，缺一不可

- **描述文本** → 拼进正文参与 embedding，让图里的内容可被检索
- **原图地址** → 只进 payload，不参与 embedding，供引用展示

因为**描述是有损的**。VLM 说「案例编辑页的步骤表单，wait_strategy 显示 CLICKABLE 且置灰」，
用来检索够了，但用户看到引用时想确认的是「界面到底长什么样」。
这也是 RAG 处理非文本内容的通行做法：**检索用文字投影，呈现用原件**。

### ObjectStorage 抽象

本地 demo 用文件系统，但企业里一定在 OSS/S3 上 —— 入库进程和查询进程通常不在
一台机器上，本地路径在另一端打不开。payload 里存的**始终是 URL 而不是路径**，
所以将来换 OSS，已入库的数据结构不用变。

key 必须**稳定**（`images/{文档}/{文档}-img-N.png`，不掺时间戳）——
消融实验会反复重跑，key 不稳就在存储里堆副本。由测试钉死。

### ⚠️ 最坑的一个：裸 PDFStreamEngine 不注册操作符

抽图必须带位置（一页多节时按页归属必然归错）。走 `PDFStreamEngine` 拦 `Do` 操作符，
从 CTM 读位置。但：

```
[DIAG] 抽到图: page=2 top=1.0      ← translateY=0 + scalingFactorY=1，单位矩阵
```

**裸的 `PDFStreamEngine` 不注册任何操作符处理器**（`PDFTextStripper` 是在自己构造函数里注册的）。
不注册的话 `cm`（矩阵变换）没有处理器，`super.processOperator` 直接忽略，
CTM 永远停在单位矩阵 —— 所有图片位置都算成页面底部，全归到最后一个小节，**不报任何错**。

补上 `Concatenate` / `Save` / `Restore` / `SetMatrix` / `SetGraphicsStateParameters`
之后拿到 `top=781.89`，图片归进正确的小节。

### 一个诚实的局限：PDF 下降级实现基本无效

`AltTextImageDescriber` 从文件名榨关键词。md 场景下文件名是作者起的
（`case-edit-wait-strategy`），有语义；**但 PDF 格式本身不存 alt**，
文件名是我们自己编的，榨出来是「05 等待策略 img 1」，毫无信息量。

DOCX 有救 —— 它能存 `docPr/@descr`。让生成器把 alt 写进去之后，零成本拿到：

```
［图片］案例编辑页里 CLICK 步骤的 wait_strategy 字段，显示 CLICKABLE 由平台自动补完
```

**结论：图片链路在 PDF 上真正依赖 VLM，降级实现只对 md 和 docx 有效。**
这一点要在消融表里如实标注，不能拿 md 的图片效果去代表 PDF。

---

## D-026 — @Lazy 不是性能优化，是可用性问题

**日期** 2026-08-13

服务机被收回去用时（Qdrant 停了），发现**造语料这种纯本地的活也起不来**：

```
BeanCreationException: ... 连不上 Qdrant REST (http://…:6333)，服务没起？
```

`qdrantClient` 在构造时要连 Qdrant 做版本校验（D-002 的加固），而它是 eager singleton。

只在 `@Bean` 方法上标 `@Lazy` **不够** —— `RetrieverFactory` 是无条件 `@Component`，
构造注入 `QdrantClient`，一样会触发创建。注入点那一侧也得标，Spring 才会注入代理。

两边都标之后：`--atp.qdrant.host=10.255.255.1`（不存在的地址）跑 `gen-corpus` 正常完成。
版本校验该守的场景一个没少。

---

## D-027 — `settings.json` 的 `env` 是 JSON，不做 shell 变量展开

**日期** 2026-08-19（归档当天发现）

### 现象

敲 `git` 报 `command not found`。`java` 和 `mvn` 一切正常。

```
PATH=[/home/kanashi/.sdkman/candidates/java/8.0.472-tem/bin:/home/kanashi/.sdkman/candidates/maven/3.9.16/bin:${PATH}]
                                                                                                          ^^^^^^^^
```

**结尾是字面量 `${PATH}`，一个不存在的目录名。**
整个系统 PATH 被这两个 sdkman 目录替换掉了 —— `/usr/bin` 不在里面，
于是 `git` / `grep` / `head` / `ls` / `cat` 全部消失，只有 java 和 maven 活着。

### 根因

`demo1-atp-rag/.claude/settings.local.json` 里为了锁 JDK 8 写了：

```json
"PATH": "/home/kanashi/.sdkman/candidates/java/8.0.472-tem/bin:.../maven/3.9.16/bin:${PATH}"
```

**这个文件是 JSON，不是 shell 脚本。** `${PATH}` 不会被展开，原样当成路径的一段传给子进程。
写的时候套用了 shell 里 `PATH="新目录:$PATH"` 的肌肉记忆，而那个语义在 JSON 里不存在。

### 决定

只保留 `JAVA_HOME`，删掉 `PATH`：

```json
{ "env": { "JAVA_HOME": "/home/kanashi/.sdkman/candidates/java/8.0.472-tem" } }
```

Maven 是按 `JAVA_HOME` 决定编译用哪个 JDK 的，所以**锁 JDK 8 的目的一点没丢**，
只是 `java -version` 会显示 sdkman 的 current 版本。目录里的 `.sdkmanrc` + `sdk env`
管的是交互式终端，那条路径不受影响。

真要在 JSON 里追加 PATH，唯一正确的写法是把系统路径**全部写死**
（`/usr/local/bin:/usr/bin:/bin:...`）。但那样很脆 —— nvm 的 node 版本一升就断。
**能不设就不设。**

### 为什么值得记

**这是「静默失败」那一族的又一个实例，而且是最完整的一个**：

| 特征 | 本条 | 对照 |
|---|---|---|
| 不报错 | 配置照常加载，`mvn` 照常跑 | D-003 slf4j 静默走 NOP |
| 症状延迟 | 加进去那天起就坏了，**直到几天后敲 `git` 才暴露** | D-007 入库不报错，检索时才炸 |
| 症状与根因无关 | 表现是「git 没装？」，根因在一个锁 JDK 版本的配置文件里 | D-015 `.env` 缺失，报错跑到 okhttp 上 |
| **是为了改善而主动加的** | 锁工具链版本本身完全正当 | D-013 rerank 阈值，本意是过滤噪音，却把一整类查询清零 |

最后一行是最刺的：**又一次，坏东西不是疏忽留下的，是我为了把事情做对而主动加的。**

**教训**：跨语言的配置层（JSON / YAML / properties / `.env`）里出现 `${VAR}`，
第一件事是确认**谁负责展开它**。shell 会、Spring 的占位符解析会，
而 JSON 本身**永远不会** —— 它只是字符串。
这和 D-004 记的那条是同一件事的两面：当时是「`.env` 里的 `${VAR}`，
shell `source` 会展开但 Java 按行读文件不会，得自己实现」。

**顺带**：这个坑正好卡在项目归档当天，逼出了一次完整的定位过程 ——
`echo "PATH=[$PATH]"` 加方括号才看清结尾那串字面量。
**打印可疑变量时加定界符**，否则尾部的空白和未展开的占位符看不出来。

---

## 待决 / 下一步

> 最后更新 2026-08-13。M0~M3 已合入 main，PR #14（父子切块 + 图片）待 review，
> `demo1/m3e-multiformat`（PDF/DOCX 链路）开发中。

**主线（核心交付物，尚未开始）**

- **M4 评估框架** —— `eval/questions.jsonl` 40 条评估集 + Recall@5 / MRR@10 / nDCG@10。
  这是交接文档 §5 说的核心交付物，目前 `eval/` 是空目录，README 的消融表 7 行全是空
- **M5 消融表** —— 跑满 6~7 行真实数字

**已知待修（按优先级）**

1. **overlap 让 baseline 不公平** —— overlap 在 HEADING_PATH 下不触发、在 FIXED 下生效，
   导致消融表第 1 行背着一个 Chroma 实测会掉 1.4pt recall 的负担，
   「标题路径前缀」的提升会被高估。建议两边都设 0（见 D-017 / D-021）
2. **手册与规范不区别处理** —— `doc_group` 字段已存但检索时没用。零成本可改
3. **payload 存两份正文** —— `embed_text` 与 `text_segment` 内容重复
4. ~~**两条链路未实现**~~ —— 链路 A 已完成（D-022~D-025）：PDF/DOCX/md 三格式入库，
   chunk 数 199 三方一致，图片抽取+转文字+原图 URL 全通。链路 B（表格模型）仍未做

**多格式链路的已知缺口**

- **无 outline 的 PDF 没有降级路径** —— 目前直接抛异常。企业里扫描件之外，
  第三方拼接的 PDF 也常常没书签。需要按字号/字重启发式恢复标题层级
- **PDF 下图片描述基本无效** —— 格式不存 alt，降级实现榨不出语义，真正依赖 VLM（D-025）
- **表格没有专门处理** —— 实测本项目语料 0 处碎片，但那是语料表格短（3~5 行）的运气。
  单张表超过约 525 字符必被拦腰切断且下半截无表头。三档改法见对话记录
- **PDF 文本层与 md 不逐字一致** —— 34 处符号（→ ❌ ✅ ⚠ ★ ≠）因字体缺字形被丢弃。
  跨格式对照出现差异时，这是第一个要排除的原因

**留给 M5 的**

- hybrid 检索 + RRF —— langchain4j 不支持 sparse vector，需自实现 EmbeddingStore（D-002 方案 B）。
  注意 RRF 正好能优雅解决 D-013/D-016 那个「分数尺度不可比」的问题
- Qwen3-Embedding 对比（消融表第 7 行）—— 非对称模型，需处理 instruction prefix

- **hybrid search（sparse + dense）** — langchain4j 0.35 不支持，必须自己实现。
  计划在 M5 连同 D-002 的方案 B 一起做，作为消融表的一行。
- **Qwen3-Embedding-0.6B 对比**（消融表第 7 行）—— 它是**非对称**模型，
  query 侧要加 instruction prefix、document 侧不加，
  而 langchain4j 0.35 的 `EmbeddingModel` 接口里两者走同一个方法，没地方区分。
  需自己实现接口或绕开该抽象。详见共享文档 §3.1。
- **增量更新策略** — 案例每天都在新增，全量重建还是增量？（面试预演问题第 4 条）
