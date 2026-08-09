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

## 待决 / 下一步

- **hybrid search（sparse + dense）** — langchain4j 0.35 不支持，必须自己实现。
  计划在 M5 连同 D-002 的方案 B 一起做，作为消融表的一行。
- **Qwen3-Embedding-0.6B 对比**（消融表第 7 行）—— 它是**非对称**模型，
  query 侧要加 instruction prefix、document 侧不加，
  而 langchain4j 0.35 的 `EmbeddingModel` 接口里两者走同一个方法，没地方区分。
  需自己实现接口或绕开该抽象。详见共享文档 §3.1。
- **增量更新策略** — 案例每天都在新增，全量重建还是增量？（面试预演问题第 4 条）
