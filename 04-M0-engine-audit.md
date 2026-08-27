# M0 摸底报告 — LLMentor / know-engine

> 对象：`/home/kanashi/llmentor/LLMentor.zip` → 已解压到 `/home/kanashi/llmentor/src/LLMentor/`
> 时间：2026-08-19。**本轮未改任何代码。**
> 承接 `03-HANDOFF-rag-v2.md` §5 M0、§7 待确认项。

---

## 0. 一句话结论

买来的是 **Hollis《LLMentor》付费课程的 monorepo**，其中 `know-engine` 是一个
**Java 21 + Spring Boot 3.5.6 + langchain4j 1.11.0** 的汽车领域知识引擎，
检索链路（混合检索 + RRF + rerank + 父子分段 + 三源路由）**完整且能用**，
**但没有任何评估代码** —— 消融表依然要从零写，这跟换不换引擎无关。

**三条最重要的结论**：

1. **框架统一的默认答案要翻转**：引擎是 langchain4j，不是 Spring AI（详见 §2）。
2. **`minScore(0.6)` 这个绝对阈值会让 ATP 案例类静默返回 0 条** —— 这正是
   `03-HANDOFF` §2.2 预言的坑，已确认存在于代码里（详见 §4.1）。
3. **父子切块在 ATP 语料上大概率整个不触发**，退化成纯标题切分，
   消融表第 2 行会没有内容可写（详见 §4.2）。

---

## 1. ⚠️ 授权状况（`03-HANDOFF` §7 最后一条，先说）

`LLMentor/README.md` 第 3 行，原文：

> ！！！本项目非开源项目，禁止发布到任何公开的 git 仓库中，如需自行 git 管理，
> 请发布到私密仓库！一经发现，将立即移除权限，并追究法律责任！

**推论与建议**：

| 事项 | 结论 |
|---|---|
| 推到 `Kanash1i/atp-ai-demos`（**private**） | ✅ 条款明确允许「发布到私密仓库」 |
| 推到任何 public 仓库 | ❌ 绝对不行 |
| 面试时**口头讲**架构和自己的改造 | ✅ 讲思路不是分发代码 |
| 面试时**屏幕演示**跑起来的系统 | ✅ 合理，但要说清边界 |
| 把仓库链接/代码包发给面试官 | ❌ 不要 |

**面试话术建议**（比含糊其辞可信得多）：

> 「知识侧我没有从零搭 RAG 管道 —— 我买了一门课的知识引擎作为底座，
> 它原本是汽车客服领域的。我做的是三件事：把语料换成我虚构的 ATP 平台文档、
> 补上它完全没有的评估框架、然后用消融实验去证伪它默认参数在我的语料上是否成立。
> 结果是三处必须重调，我一会儿讲。代码有授权限制我不能分发，但可以演示。」

这个说法的好处：**「我做的部分是 X/Y/Z」边界清晰**，而且落点正好在评估上 —— 这是岗位真正在考的能力。

> 另外：根 `pom.xml:46` 硬编码了一个 dashscope key，
> `know-engine/src/main/resources/application.yml:84` 硬编码了一个 deepseek key。
> **这两个都是别人的 key，迁进我们仓库前必须删掉**，否则违反本仓库 CLAUDE.md 的配置红线，
> 而且是把第三方凭据提交进 git。

---

## 2. ⭐ 框架统一方案建议

### 2.1 事实

| 项 | 实际情况 | 证据 |
|---|---|---|
| 根 pom 的框架 | 同时管理 Spring AI 1.1.0 BOM 和各模块自己的 langchain4j | `LLMentor/pom.xml:44,53-66` |
| **know-engine 用哪个** | **纯 langchain4j 1.11.0**，一行 Spring AI 都没有 | `know-engine/pom.xml:22-56` |
| 耦合深度 | **非常深** | 见下 |

`rag/modules/` 下 **每一个类都实现或继承 langchain4j 的 RAG 抽象**：

```
KnowEngineQueryRouter                → implements dev.langchain4j.rag.query.router.QueryRouter
KnowEngineQueryTransformer           → implements QueryTransformer
KnowEngineElasticsearchContentRetriever → extends AbstractElasticsearchEmbeddingStore
                                          implements ContentRetriever
KnowEngineReRankingContentAggregator → implements ContentAggregator
KnowEngineHybridContentAggregator    → implements ContentAggregator
ProgressAwareContentRetriever/Aggregator → 装饰器，同样是这套接口
TeiScoringModel                      → implements dev.langchain4j.model.scoring.ScoringModel
```

整条管道由 `DefaultRetrievalAugmentor.builder()` 组装
（`chat/service/ChatApplicationService.java:382-387`），
再挂到 `AiServices.builder(KnowEngineChatAiService.class)` 上（同文件 `:389-397`）。
意图识别用的是 langchain4j 的 `@AiService` + `@SystemMessage` + structured output
（`ai/service/IntentRecognitionService.java`）。

**换 Spring AI = 重写整个 `rag/modules` 包（约 2000 行）+ 意图识别 + 对话编排。**
`03-HANDOFF` §4.1 写的那个「唯一的例外」分支，**命中了**。

### 2.2 建议：统一 langchain4j —— 但这个问题比 §4.1 设想的小得多

> ⚠️ **2026-08-27 就地修正**：本节写于生产侧还是 Spring AI MCP server 的时候。
> **MCP 方案已废弃并删除**，生产侧改为 `atp` CLI，**零模型调用、无 AI 框架**。
> 结论从「冲突面积很小」变成「**冲突根本不存在**」—— 全仓只有知识侧一个 LLM 框架。

`03-HANDOFF` §4.1 担心的是「一个应用里塞两个 LLM 框架」。
**但生产侧现在没有 LLM 框架** —— `atp` CLI 是 picocli + JDBC 的确定性代码，
模型由 agent 调，CLI 只做校验和幂等落库。

所以 §4.1 列的三处传递依赖冲突（Jackson 2/3 分裂、向量库 client、双 HTTP 栈）
**一处都不会发生**。

推荐结构：

```
atp-ai-demos/
├── atp-common/   纯 POJO：领域模型、Action 枚举、STD 规范常量。零 AI 框架依赖
├── atp-rag/      知识侧 = know-engine 改造，langchain4j 1.11.0     ← 独立进程 :8009
├── atp-cli/      生产侧 = atp CLI，picocli + JDBC，无 AI 框架      ← 命令行进程
└── eval/         评估集 + 消融跑批（可以是 atp-rag 的 test 或独立 CLI）
```

**只有 `atp-common` 需要被两边依赖，而它不含任何 AI 框架** —— 冲突面积归零。

配套的三条：

- 根 pom 的 `dependencyManagement` 只 pin **公共基础库**（Jackson、slf4j、protobuf），
  两个 AI 框架的 BOM 各自留在自己的模块里，不往上提。
- **不要照搬 LLMentor 的根 pom** —— 它管着 18 个模块（agentscope、dodo-agent、gogo-agent…），
  跟我们无关。只搬 `know-engine` 一个模块，重写父 pom。
- 生产侧 `atp-cli` **刻意不继承 `spring-boot-starter-parent`** ——
  CLI 被 agent 高频反复调用，冷启动是真实成本（Spring Boot ≈ 1.5s / picocli fat jar ≈ 300ms）。
  见 `demo2-atp-cli/DECISIONS.md` D-103。

**面试叙事**（这个版本比「历史原因」诚实且更强）：

> 「知识侧是在一个 langchain4j 引擎上改的，它的 RAG 管道抽象
> （QueryRouter / ContentAggregator）我直接复用了。
> **生产侧我没有用任何 LLM 框架** —— 那条链路里 CLI 不调模型，调模型的是 agent，
> CLI 负责校验和幂等落库，是确定性代码。给不需要框架的代码接框架，只是增加依赖。
> 共享的领域模型抽在一个零框架依赖的 common 模块里。」

### 2.3 顺带：`03-HANDOFF` §4.1 有两处需要就地修正

- 「默认选 Spring AI 2.0」→ 改为「知识侧统一 langchain4j 1.11.0，生产侧保持 Spring AI，靠进程边界隔离」
- 「langchain4j 没有不可替代的东西」（引 demo1 D-020）→ **在 1.11.0 上不再成立**。
  这版已有 `ReciprocalRankFuser`、`ReRankingContentAggregator`、`QueryRouter`、
  `ElasticsearchConfigurationHybrid` 等一整套 RAG 抽象，Spring AI 1.1 仍然没有。
  D-020 的结论是在 langchain4j **0.35**（Java 8 那条约束链）上得出的，版本一换就翻转了。

---

## 3. ✅ 能直接用的（不用改，或只改配置）

| # | 组件 | 位置 | 说明 |
|---|---|---|---|
| 1 | **TEI embedding 接入** | `application.yml:108-116`、`rag/config/ElasticSearchConfiguration.java:25-33` | 已经是 `bge-m3` / 1024 维 / OpenAI 兼容端点，**和服务机 `:8081` 完全对齐**，改个 IP 就能跑 |
| 2 | **TEI rerank 适配器** | `rag/modules/reranker/TeiScoringModel.java` | 字段用的是 `texts`（对），**而且正确处理了「响应未排序」** —— 按 `rank.index()` 回填（`:69-77`），还对越界 index 显式抛异常。demo1 §3.3 要求确认的两件事，这件是对的 |
| 3 | **RRF 融合** | `rag/modules/reranker/KnowEngineReciprocalRankFuser.java` | 标准 RRF，k=60，**只用排名不用分数** → `03-HANDOFF` §2.2 的「异构分数不可比」问题在**融合层已免疫**（但截断层没有，见 §4.1） |
| 4 | **混合检索骨架** | `ChatApplicationService.java:310-334` | dense（`ElasticsearchConfigurationKnn`）+ 全文（`ElasticsearchConfigurationFullText`）两个 retriever 并行 → RRF → rerank。**消融表第 3、4 行的开关点就在这里**，天然可插拔 |
| 5 | **DeepSeek 生成模型** | `application.yml:80-92` | 已经是 `deepseek-v4-flash` @ `api.deepseek.com`，和本仓库 CLAUDE.md 一致 |
| 6 | **父子分段的检索侧机制** | `KnowEngineElasticsearchContentRetriever.java:154-220` | `parentChunkId` 回溯 + `brotherChunkId` 补全 + 三级缓存，机制是对的（触发条件要改，见 §4.2） |
| 7 | **文档生命周期与事件驱动** | `document/` 整个包 | 上传→转换→切片→向量化，Spring Event 串联 + XXL-Job 补偿。**demo 场景用不上补偿，但也不碍事** |
| 8 | **切分策略工厂** | `rag/modules/splitter/DocumentSplitterFactory.java` | 5 种策略（TITLE/LENGTH/REGEX/SEPARATOR/SMART）已经是工厂模式，**消融表第 1 行 baseline（固定切分）直接用 `LENGTH`**，第 2 行用 `TITLE` |
| 9 | **RAG 引用溯源** | `ProgressAwareContentAggregator` + `rag/util/ReferenceUtil.java` | 每条结果带 source + rerank 分数。**这是评估集打标和 `golden_ids` 比对的现成数据源** |
| 10 | **MinerU 接入** | `document/service/impl/MinerUProcessBaseServiceImpl.java` | HTTP 调用已封装（上传→zip→解压→图片传 MinIO→视觉模型生成描述），可用；调用方式要重新决策，见 §4.5 |

---

## 4. ⚠️ 换成 ATP 语料后**必须重调**的（按优先级）

### 4.1 🔴 `minScore(0.6)` 绝对阈值 —— 最高优先级

```java
// chat/service/ChatApplicationService.java:366-372
new KnowEngineHybridContentAggregator(
    KnowEngineReRankingContentAggregator.builder()
        .scoringModel(scoringModel)
        .minScore(0.6)        // ← 绝对阈值
        .maxResults(5)
        .build()
)
```

截断发生在 `KnowEngineReRankingContentAggregator.java:151`：
`.filter(entry -> minScore == null || entry.getValue() >= minScore)`。

**为什么这条在汽车语料上没暴露**：汽车语料大概率只有一种内容形态（手册类自然语言），
rerank 分数分布集中，0.6 是个合理的经验值。

**为什么换 ATP 必炸**：demo1 实测过 —— reranker 对
「自然语言 query vs 结构化步骤序列」的相关性打分天然偏低，
文档类 top1 落在 0.59~0.99，**案例类只有 0.008~0.38**，差 1~2 个数量级。
`0.6` 会让 **B 类（案例检索）10 条评估问题全部返回 0 条**，
表现为「拒答率飙升 / Recall 归零」，看起来像检索能力差，实际是阈值问题。

**必做**：
1. 灌完数据**第一件事**：量「手册 chunk」和「案例 chunk」两类的 rerank 分数分布（各取 20 条 query）。
2. 阈值改成 `max(绝对下限, top1 × 比例)`（demo1 的修法），或直接 **`minScore(null)` 只靠 `maxResults` 截断**。
   评估阶段建议后者 —— **少一个会污染消融表的变量**。
3. 同时检查 ES 层的 `minScore(0.5)`（`ChatApplicationService.java:314`）：
   这是 langchain4j ES KNN 的归一化分数 `(1+cosine)/2`，0.5 等价于 `cosine >= 0`，基本不截断；
   但**中文 query 召回日文文档时 cosine 会明显偏低**（C 类 8 条评估题全靠它），要实测确认没被砍。

> ⚠️ 注意这里有**两层阈值**（ES 层 0.5 + rerank 层 0.6），
> 而 `KnowEngineElasticsearchContentRetriever.java:278` 还有第三处 `f.score() > minScore`。
> 消融跑批前要把三处都收敛成配置项，否则「关掉 rerank」那一行改不干净。

### 4.2 🔴 父子切块的触发条件 —— 消融表第 2 行会没内容

```java
// rag/modules/splitter/MarkdownHeaderParentTextSplitter.java:340-378
if (content.length() <= chunkSize) {
    result.add(segment);              // ← 不产生 parentChunkId
} else {
    // 只有超出 chunkSize 才生成 父分段(SKIP_EMBEDDING=1) + 多个子分段
}
```

**父子分段只在「一个标题小节超过 chunkSize」时才产生。**

demo1 量过 ATP 语料：小节长度中位数 174、p90 347、**p95 407、max 610 字符**。
只要 `chunkSize >= 700`（默认值量级），**没有任何一个小节会超**，
于是父子切块整条链路（父分段、`skipEmbedding`、Redis 三级缓存、`parentChunkId` 回溯）
**一次都不会触发**，退化成纯标题切分。

**这不是 bug，是 §2.5 那条「参数在结构感知切分下是死参数」的同一现象**，
但后果更严重：**消融表第 2 行「+ 结构感知切分（父子块）」将无法体现父子块的贡献**。

**三个可选处理，建议第 1 个**：

1. **把 chunkSize 调到能触发父子块的量级**（例如 300），让 p75 以上的小节走父子路径。
   这样第 2 行才真的在测「父子块」，而且**参数选择有实测依据**（p50/p75/p95 那张表），面试可讲。
2. 或者把消融表第 2 行改成「+ 结构感知切分（标题层级）」，父子块**单独列一行**并诚实标注
   「在本语料上未触发，因为最长小节 610 < chunkSize」。
3. 或者给 ATP 语料补一两篇长文档（真实平台手册一节可能几千字），
   让父子块有用武之地 —— 但这动语料，**优先级低于评估**。

**顺带两个必查**：
- `overlap` 建议设 **0**（`03-HANDOFF` §2.5）：这里的回退是纯算术
  `start = end - Math.min(overlap, end)`（`:374`），不看边界，块首会出现半截 token。
  baseline 和主策略都设 0，否则消融不公平。
- `SMART` 模式硬编码 `overlap = chunkSize * 0.1`（`DocumentSplitterFactory.java:30`），
  **消融跑批不要用 SMART**，用 `TITLE` 显式传参。

### 4.3 🔴 意图识别 + 查询路由 —— 汽车领域硬编码，ATP 上必须整套重写

三处硬编码，一处也绕不开：

| 位置 | 内容 |
|---|---|
| `ai/constant/KnowEngineIntent.java` | 6 个枚举全是汽车（`CAR_BEFORE_SALES` / `CAR_MAINTENANCE` / …），`getIntent()` 里 switch 的是中文字符串「售前咨询与购买」等 |
| `resources/prompts/intent-recognition-new-prompt.txt` | 130+ 行的汽车客服意图分类 prompt，含 CoT 指引和 few-shot |
| `ai/model/IntentRecognitionResult.java` | 8 个实体字段全是汽车：`car_model` / `dealer` / `fault_description` / `part_name` … |
| `rag/modules/KnowEngineQueryRouter.java:88-125` | 路由 prompt 是内联字符串，「你是一个汽车领域的智能助手」，三源判据全是汽车例子 |
| `resources/prompts/car-*-query-prompt.txt` × 6 | 领域回答 prompt |

**ATP 的映射建议**（意图数量要砍，6 个太多，40 条评估集撑不起）：

| ATP 意图 | 对应评估集类型 | 说明 |
|---|---|---|
| `SPEC_QUERY`（规范/知识问答） | A 类 15 条 | 「wait_strategy 有几种」「XPath 能用 class 吗」 |
| `CASE_SEARCH`（案例检索） | B 类 10 条 | 「找几个购物车加购的案例」 |
| `CASE_REVIEW`（案例审查） | 加分项 | 「这条案例有没有违反规范」→ 接 demo2 的校验器 |
| `OTHER` | D 类 | 兜底 |

实体字段砍到 ATP 契约里真实存在的：`module_code` / `action` / `wait_strategy` / `case_code` / `std_code`。

**⚠️ 最危险的一处 —— `related` 字段**：

prompt 要求「与汽车领域不相关 → `related: false`」，代码据此**直接绕过整条 RAG 走通用对话**
（`ChatApplicationService` 的分支）。换成 ATP 后：
**D 类「完全无覆盖」的问题（如「执行器线程池怎么配」）会被判成 `related: true`（它确实是 ATP 话题），
但语料里没有 → 走 RAG → 召回一堆不相关内容 → LLM 自由发挥。**
反过来，措辞稍偏的问题会被判 `related: false` → **绕过 RAG → 通用对话直接编**，
两条路都不会输出 `[资料不足]`。

**结论：拒答（D 类 7 条）不能依赖 `related` 判断，必须落在生成 prompt 的约束里**，
而且按 `03-HANDOFF` §2.4，标记语义要收窄到只表示「我没有资料所以答不了」，
不能带「或者该功能不支持」那个「或者」。

**信号词提醒**（demo1 的教训）：ATP 的问法是
「点击按钮之前应该用哪种等待策略」「有几种」「有哪些」「什么时候用」——
这类句式在汽车 prompt 的 few-shot 里一个都没有，**必须用评估集校准，不能凭想象列**。

### 4.4 🟡 路由是「开关」不是「配额」

`KnowEngineQueryRouter.route()` 一次只返回**一个** strategy 对应的 retriever 集合
（prompt 明确写「其一次只返回一个」，`:118`）。判错 = 整类召回不到。
异常时降级返回全部（`:181`），但**判对格式、判错类别时不降级**。

`03-HANDOFF` §2.2 的修法是**改成配额**：判为不相关的一侧仍然查，只是 topK 降低。

**但 ATP 场景可以更省事**：我们只有 ES 一个数据源（没有 MySQL 车辆表、没有 Neo4j 图谱），
「文档 vs 案例」的区分应该走 **metadata filter**（`doc_type: manual|case|standard`）
而不是走 QueryRouter。这样：

- 消融表第 3 行「+ 混合检索」测的是 dense+BM25+RRF，干净
- 路由降级为 metadata 配额，不需要 LLM 调用，**评估跑批不烧 token**（§2.3 的硬要求）
- Text2SQL / Text2Cypher 两条路**直接砍掉** —— ATP demo 没有对应数据源

> ⚠️ 砍的时候注意 `ChatApplicationService.java:384` 是
> `List.of(embeddingRetriever, fullTextRetriever, sqlRetriever, neo4jRetriever)`，
> **`List.of` 不接受 null 元素**。而 `sqlRetriever` / `neo4jRetriever` 在构造失败时
> 被 catch 后保持 `null`（`:334-361`）→ **直接 NPE**。
> 也就是说：Neo4j 没起来的话，现在这版根本进不了对话流程。这是移植时第一个会撞上的墙。

### 4.5 🟡 MinerU 是**在线 HTTP 服务**，不是离线批处理

`MinerUProcessBaseServiceImpl.java:66` → `@Value("${file.parse.api.url:http://localhost:8000}")`，
每次上传文档都实时调用。而且图片描述还要再调一个**视觉模型服务**
（`application.yml:74-77`，llama.cpp `qwen3-vl-8b` @ `:8083`）。

`03-HANDOFF` §4.2 建议的是**离线批处理**（语料固定，转好 markdown 存盘进版本库，
查询链路完全不依赖 MinerU 在线）。**这个建议依然正确，理由更充分了**：

- 演示时少两个会挂的服务（MinerU + qwen3-vl）
- **显存账**：服务机 RTX 5080 是 16G，桌面基线 ~1950 MiB，TEI 两个模型实测 ~5165 MiB。
  MinerU 的版面/OCR/公式模型 + qwen3-vl-8b 一起加载，**16G 大概率不够**。
  离线批处理可以和 TEI 错峰跑，这笔账就不用算。
- 语料是 15 篇固定文档，转一次就行，没有任何在线需求

**做法**：保留 `MarkdownProcessServiceImpl` 这条路径（直接吃 markdown），
MinerU 那一步在本地手工跑一次、把产物 commit 进 `corpus/`。

### 4.6 🟡 Embedding / 跨语言 —— 已经对齐，但有一个必查

`application.yml:110-114` 已经是 `BAAI/bge-m3` / 1024 维，**跟服务机部署完全一致**，
中日英均衡，C 类跨语言评估没问题。`ElasticSearchConfiguration.java:31` 也正确传了
`maxSegmentsPerBatch=32`（TEI 的 batch 上限）。

**唯一必查**：`TeiScoringModel.scoreAll()`（`:44-80`）**没有做分批**，
把 `segments` 一次性全发给 `/rerank`。embedding 侧有 32 的保护，**rerank 侧没有**。
混合检索时 dense(5) + fulltext(ES 默认 10) + 兄弟/父分段扩展，
送进 rerank 的条数可能超过 32 → TEI 返回 413 或截断。
**消融跑批时召回 N 会调大（召回 20 → 精排 5），这条必炸**，要先补分批。

---

## 5. 顺手发现的问题（都不是我们的需求，但会咬人）

| # | 位置 | 问题 | 会怎么表现 |
|---|---|---|---|
| 1 | `ChatApplicationService.java:384` | `List.of(...)` 含可能为 null 的 retriever | Neo4j/MySQL 不可用时 **NPE，整个对话打不开** |
| 2 | `TeiScoringModel.java:44-80` | rerank 无 batch 分批 | 召回数 >32 时 413 或**静默截断** |
| 3 | `MarkdownHeaderParentTextSplitter.java:375` | `overlap >= chunkSize` 时 `start` 不前进 | **死循环 + OOM**。`TITLE` 模式 overlap 由前端传，**无校验** |
| 4 | `KnowEngineReRankingContentAggregator.java:145-150` | 用 `Map<TextSegment, Double>` 承载分数 | 文本+metadata 完全相同的两条会被**静默合并**，结果条数变少。父分段替换后正好会产生重复文本 |
| 5 | `KnowEngineElasticsearchContentRetriever.java:240-270` | `doFullTextQuery` 没设 `.size()` | `maxResults(5)` 对全文检索**不生效**，ES 默认返回 10 条 → **消融表控变量失真** |
| 6 | 同上 `:178-186` | 兄弟分段检索用 `EmbeddingSearchRequest.builder().filter(...)` 但**没有 `queryEmbedding`** | langchain4j 对该字段有 `ensureNotNull` —— 触发路径上大概率抛异常。需实测（要先造出有 `brotherChunkId` 的数据） |
| 7 | 根 `pom.xml:46` / `application.yml:84,90` | 硬编码第三方 API key | 迁进仓库前必须删 |

第 1、2、3、5 条正好都属于 `03-HANDOFF` §2.1 那类
**「系统照常运行，只是结果悄悄错了」**，可以直接进消融表的「显式检查」叙事。

---

## 6. `03-HANDOFF` §7 待确认项 — 逐条填

- [x] **仓库地址 / 本地路径** → `/home/kanashi/llmentor/src/LLMentor/know-engine`（zip 原件同目录）
- [x] **Spring AI 还是 langchain4j？耦合多深？** → **langchain4j 1.11.0，极深**（§2.1）。方案见 §2.2
- [x] **意图识别是规则、LLM 还是小模型？** → **LLM structured output**（`@AiService` + Record + `@JsonPropertyDescription`），无规则兜底。汽车领域硬编码（§4.3）
- [x] **混合检索的融合方式** → **RRF，k=60**（`KnowEngineReciprocalRankFuser`）。融合层对 §2.2 免疫；**但截断层用绝对阈值 0.6，不免疫**（§4.1）
- [x] **embedding 模型 / 日文支持** → 已是 `bge-m3` 1024 维，**不用换**
- [x] **MinerU 调用方式 / 显存** → 在线 HTTP `:8000` + 视觉模型 `:8083`。**建议改离线批处理**，显存实测可省（§4.5）
- [x] **向量库** → **Elasticsearch 8.17.10**（compose 自带），**不是 Qdrant**。见下
- [x] **授权状况** → 私有仓库 OK，公开禁止。话术见 §1

### 6.1 新增决策点：ES 还是 Qdrant？

**建议留 ES，不要换 Qdrant。** 理由：

- 混合检索（dense + BM25）**是 ES 的原生能力**，引擎已经用 `ElasticsearchConfigurationFullText`
  / `ElasticsearchConfigurationHybrid` 接好了。换 Qdrant 就要自己实现 BM25 那一路 ——
  **这正是 demo1 D-002 方案 B「留给 M5 从未做」的那件事**，换回去等于把已经解决的问题重新捡起来。
- 消融表第 3 行「+ 混合检索（BM25 + dense，RRF 融合）」在 ES 上是改配置，在 Qdrant 上是写代码。
- 服务机上的 Qdrant 可以留着不用；ES 8.17 用 compose 在**笔记本本地**起就行
  （`ES_JAVA_OPTS: -Xms512m -Xmx512m`，很轻），不占服务机显存。

**代价（要在 DECISIONS 里写清）**：Qdrant 那个「能点开给面试官看召回了哪几条」的 Web UI 没了 ——
但 compose 里**自带 Kibana**（`:5601`），演示效果等价甚至更好。

---

## 7. 需要你确认的三件事

1. **这个 zip 已经被适配过一轮了吗？** 几处强烈迹象：
   - `application.yml` 已经指向 **TEI bge-m3 + DeepSeek**（README 架构图里还写着 `text-embedding-v4 1536d`，说明是后改的）
   - `.env.example` 里 TEI/MinerU/视觉模型的注释是英文且写明「Windows host」
   - 有一份 414 行的 `know-engine面经答案-自动化测试平台AI赋能版.md`，开头明确写「把汽车客服案例统一替换成自动化测试平台 AI 赋能场景」
   - `mcp/mcp-case-authoring-server/` 里有 `prepare_case_draft` / `commit_case_draft(changeSetId)`，
     跟当时 demo2 的 MCP 形态**完全对得上**（该形态已于 2026-08-27 废弃，见下方第 2 条）

   → 如果这些是你自己（或另一个 session）做的，我需要知道做到哪一步了，避免重复劳动。
     如果是课程自带的，那这份面经答案是**现成的面试叙事素材**，值得单独归档。

2. ~~**`mcp-case-authoring-server` 和 demo2 是什么关系？**~~
   ✅ **2026-08-27 已解决**：两边都做「案例草稿生成 + 幂等入库」，最后**两边都没留**。
   MCP 形态整体废弃（`demo2-atp-mcp/` 已删除），生产侧改做 `atp` CLI ——
   幂等键做成平台案例表的主键，用唯一约束 + CAS UPDATE 当仲裁点，不外挂 server。
   `mcp-case-authoring-server` 的 ChangeSet 设计有效结论已并入 `05-CLI-并发幂等答辩稿.md`。

3. **意图分类砍到 3~4 个（§4.3）可以吗？** 6 个汽车意图对应 6 份领域 prompt，
   ATP 场景用不了那么多，而 40 条评估集也撑不起 6 分类的路由准确率统计。

---

## 8. 建议的下一步（M0 收尾 → M1）

**先不要碰功能代码。** 按这个顺序：

1. 跑 `03-HANDOFF` §6 的开工检查清单（三个服务 + GPU 判据 + rerank 打分方向），**确认基线没坏**
2. `docker compose up` 起 ES + MySQL + Redis + MinIO，**原样跑通一次汽车 demo** ——
   见过它「对」的样子，后面才知道改坏了没有（M0 的卡点原话：不摸清就改，会在别人的假设上叠自己的假设）
3. 把 `know-engine` 单模块摘出来进 `atp-ai-demos`，**删掉硬编码 key**，重写父 pom（§2.2）
4. 砍掉 Text2SQL / Text2Cypher / 钉钉 / Sa-Token / XXL-Job 四块（demo 用不上，且是 §4.4 的 NPE 源头）
5. **然后才是 M1 换语料** —— 而 M1 一能检索出结果，**下一件事就是 M2 评估集，不是加功能**

> 引擎里**没有任何评估代码**（全仓库 grep `ragas|recall@|mrr|ndcg|evaluat` 只命中面经文档和一个无关的
> `CalculateTool`）。所以 `03-HANDOFF` §5 的 M2/M3 **一行都不能省，换引擎没有帮我们省掉这部分**。
> 换引擎省掉的是 M0~M1 的搭链路，省下来的预算**必须花在消融表上**。
