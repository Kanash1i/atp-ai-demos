# demo1 交接文档 — ATP 知识助手 (Java 8 + langchain4j RAG)

> **给新 session 的第一条指令**：先完整读 `../00-SHARED-CONTEXT.md`，再读本文档。
> 工作目录：`/home/kanashi/Applications/interview-demos/demo1-atp-rag/`
> 本文档是自包含的，不需要回头问上一个 session。

---

## 1. 这个 demo 要证明什么

**面试岗位：AI 应用工程。**

所以这个 demo 的价值**不在于"我接通了大模型"** —— 那是 2023 年的门槛。它要证明三件事：

1. **我知道 RAG 的质量瓶颈在检索，不在生成**，并且我能用数字说话
2. **我能处理真实语料的脏活**：多语言、结构异构（文档 vs 结构化案例）、长尾查询（XPath 片段这种关键词型查询）
3. **我在遗留技术栈的约束下也能落地**（Java 8）

反过来说，**如果这个 demo 做成一个"能聊天的知识库"就是失败的**。必须有评估。

### 背景说明（面试话术）

老平台当年确实为了交付快速接入了大模型，但那部分我参与得不深，也不确定当时用的是不是 langchain4j。
**不要假装当年是自己主导的架构。** 诚实的讲法：

> "当年我们为了交付赶工接入过大模型，我在那个模块上参与有限。
> 这次准备面试，我用 langchain4j 把它完整重做了一遍，顺便摸清了它在 Java 8 上的边界 ——
> 比如 0.35.0 是最后一个 Java 8 字节码版本，0.36 开始就跳到 Java 17 了。"

这比含糊其辞可信得多，而且"我自己去验证了字节码版本"这个动作本身就说明你真的动过手。

---

## 2. 技术栈与版本约束（**已验证，不要改**）

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | **8** (Temurin 8.0.472) | 本机已通过 SDKMAN 装好，见 §9 |
| langchain4j | **0.35.0** | ⚠️ **硬约束**：`langchain4j-core` 0.35.0 字节码 major=52 (Java 8)，0.36.0 起 major=61 (Java 17)。已实测确认，不要升版本 |
| 构建 | Maven | `maven.compiler.source/target = 1.8` |
| 向量库 | Qdrant | 服务机 `:6333`，collection 维度 **1024**，距离 **Cosine** |
| Embedding | bge-m3 @ **TEI** | 服务机 `:8081`，`/embed` 原生 + `/v1/embeddings` OpenAI 兼容。**已部署验证** |
| Rerank | bge-reranker-v2-m3 @ **TEI** | 服务机 `:8082`，`/rerank`（字段 `texts`）**非** OpenAI 标准。**已部署验证** |
| 生成 | DeepSeek（后期切 Kimi） | `deepseek-v4-flash`，OpenAI 兼容 |

> **所有连接参数读仓库根目录 `../.env`**（由 `../.env.example` 复制）。
> 代码里不得硬编码 IP、端口、key。服务机为 `192.168.0.101`。

### 2.1 依赖清单

```
dev.langchain4j:langchain4j:0.35.0
dev.langchain4j:langchain4j-open-ai:0.35.0      ← 同时用于 DeepSeek 生成 和 TEI embedding
dev.langchain4j:langchain4j-qdrant:0.35.0
dev.langchain4j:langchain4j-document-parser-apache-tika:0.35.0   （如果要吃 PDF/docx；纯 Markdown 语料可省）
org.slf4j:slf4j-simple / logback-classic
com.fasterxml.jackson.core:jackson-databind      ← 解析案例 JSON
org.junit.jupiter:junit-jupiter:5.10.x           ← 评估跑在测试里
```

### 2.2 ⚠️ 开工第一件事：验证 Java 8 可行性

**不要先写业务代码。** 先做一个 30 行的 spike，确认整条链路在 JDK 8 上真的能跑：

1. `mvn -Dmaven.compiler.source=1.8 -Dmaven.compiler.target=1.8` 能编译过
2. 用 JDK 8 **运行**（不只是编译）能连通 Qdrant（langchain4j-qdrant 走 gRPC，grpc-java 对 Java 8 的支持要实测）
3. 调 TEI 的 `/v1/embeddings` 能拿到 **1024 维**向量
4. **跑 rerank 冒烟测试**（见共享文档 §2.1）确认打分方向正确 —— 这一步不能省，理由见 §2.4

**如果 gRPC 或某个传递依赖在 Java 8 上炸了**，按这个顺序降级，并把过程记进 `DECISIONS.md`（这是很好的面试素材）：
- 方案 B：换 `langchain4j-qdrant` 为 REST 调用（Qdrant 有 HTTP API，自己实现 `EmbeddingStore` 接口）
- 方案 C：换向量库为 pgvector（`langchain4j-pgvector`，纯 JDBC，Java 8 无压力）
- 方案 D：`InMemoryEmbeddingStore` + 启动时加载（**最后手段**，面试会被追问持久化，要准备好说辞）

> 记录"我试了什么、为什么退到方案 X"比"一次就成功"更有说服力。

### 2.3 一个省事的技巧

TEI 的 `/v1/embeddings` 是 **OpenAI 兼容**的，所以不用自己实现 `EmbeddingModel`：

```
OpenAiEmbeddingModel.builder()
    .baseUrl(env("EMBEDDING_BASE_URL"))   // http://192.168.0.101:8081
    .apiKey("dummy")                       // TEI 未设 api-key，但 builder 要求非空
    .modelName(env("EMBEDDING_MODEL"))
    .build()
```

**生成侧同理** —— DeepSeek 也是 OpenAI 兼容，用 `OpenAiChatModel` 改 `baseUrl` 即可：

```
OpenAiChatModel.builder()
    .baseUrl(env("LLM_BASE_URL"))          // https://api.deepseek.com/v1
    .apiKey(env("LLM_API_KEY"))
    .modelName(env("LLM_MODEL"))           // deepseek-v4-flash
    .temperature(0.0)
    .build()
```

> ⚠️ `deepseek-chat` / `deepseek-reasoner` 已于 2026-07-24 废弃，别用旧名字。
> DeepSeek V4 默认开 thinking 模式，更慢更贵，按需关闭。

但 **rerank 没有这个便利** —— TEI 的 `/rerank` 不是 OpenAI 标准：

```
POST http://{SERVICE_HOST}:8082/rerank
{ "query": "...", "texts": ["doc1", "doc2", ...] }
  ↓
[ {"index": 0, "score": 0.735}, {"index": 1, "score": 0.0000163}, ... ]
```

⚠️ 两个易错点：字段是 **`texts`** 而非 `documents`；返回结果**未按分数排序**，需自己降序。

需要自己实现 `dev.langchain4j.model.scoring.ScoringModel`（就一个 `scoreAll` 方法），
内部用 HTTP client 调它。**这段自写适配器是面试可以展开讲的点。**

### 2.4 ⚠️ 开工前必须确认「真的在 GPU 上」

**这个坑本项目已经踩过，而且是最值得讲的一个。**

TEI 在检测不到 CUDA 时**不会报错退出，而是静默降级到 CPU**，只打一条 WARN。
表现是：容器正常运行、`/health` 返回 200、API 正常返回 1024 维向量 ——
**一切看起来都对**，实际 14 核 CPU 满载、GPU 利用率 0%，慢一个数量级。
最后是靠**CPU 风扇的声音**发现的。

根因是 CUDA forward-compat 库（`/usr/local/cuda-12.9/compat/libcuda.so.*`）
在 WSL2 上有害 —— WSL 没有真正的 NVIDIA 内核模块，必须用转发到 Windows 的那个 `libcuda`。
修复是启动时加 `--tmpfs /usr/local/cuda-12.9/compat`。完整分析见共享文档 §2.1(a)。

**开工前必须确认**（三项冒烟测试见共享文档 §2.1(e)）：

```bash
docker logs tei-embed 2>&1 | grep -i "model on"
# 必须是 Starting FlashBert model on Cuda(...)，不能是 on Cpu
```

**为什么这对本 demo 特别致命**：

rerank 是消融实验里预期增益最大的一项（§5.3 第 4 行）。
如果它悄悄坏掉 —— 分数全部接近、或排序方向反了 ——
你会得到一张**看起来正常但完全错误的消融表**，并拿着它去面试。
面试官只要追问"rerank 提升这么小/这么怪，你查过原因吗"，就会当场穿帮。

**当前状态**：bge-reranker-v2-m3 在 TEI 上**已实测通过**，
区分度达 4 个数量级（0.735 vs 1.6e-5），基线数值见共享文档 §2.1(e)。

若日后换模型导致验证不通过 → 设 `RERANK_ENABLED=false`，在消融表里**如实标注**该行数据缺失。
**如实标注一个缺失项，远比伪造一行漂亮数字安全。**
而且"我发现了推理后端的一个静默降级并定位到根因"本身就是极好的面试素材。

---

## 3. 架构设计

### 3.1 核心洞察：这不是一个检索任务，是两个

用户明确说了两个用途：

1. **帮新员工熟悉平台功能、XPath 写法** → 知识问答，语料是**文档**
2. **帮用户写新案例时借鉴存量案例** → 案例推荐，语料是**结构化案例**

**这两者的最优检索策略完全不同**，用一个 collection 一套 chunk 策略去做，两边都会烂：

| | 文档库 | 案例库 |
|---|---|---|
| chunk 粒度 | 按 Markdown 标题层级切，512 token | **整条案例不切**（切碎了步骤就失去上下文） |
| 检索意图 | 语义相似 | 语义相似 + **metadata 过滤**（同模块优先） |
| 返回形态 | 文本片段 + 章节引用 | 完整案例 + 案例编号 |
| 典型 query | "XPath 怎么写才稳定" | "帮我找几个购物车相关的案例参考" |

**所以架构的第一个决策就是双 collection + 查询路由。** 这是整个 demo 最值得讲的设计。

### 3.2 检索链路

```
                          用户 Query
                              │
                              ▼
                  ┌───────────────────────┐
                  │  QueryRouter (LLM)    │  ← langchain4j LanguageModelQueryRouter
                  │  判断意图              │     分类: 知识问答 / 案例检索 / 两者都要
                  └───────────────────────┘
                       │              │
          ┌────────────┘              └─────────────┐
          ▼                                          ▼
┌──────────────────────┐              ┌──────────────────────────┐
│ ContentRetriever A   │              │ ContentRetriever B       │
│ collection: atp_docs │              │ collection: atp_cases    │
│ 手册 + 公司规范        │              │ 存量案例                  │
│ topK=20              │              │ topK=20, metadata filter │
└──────────────────────┘              └──────────────────────────┘
          │                                          │
          └────────────────┬─────────────────────────┘
                           ▼
              ┌────────────────────────────┐
              │ ReRankingContentAggregator │  ← bge-reranker-v2-m3
              │ 40 条 → 精排 → top 5        │     自己实现的 ScoringModel
              │ minScore 阈值过滤            │
              └────────────────────────────┘
                           │
                           ▼
              ┌────────────────────────────┐
              │ ContentInjector            │  ← 注入时**必须带来源标识**
              │ 拼 prompt + 来源编号         │
              └────────────────────────────┘
                           │
                           ▼
              ┌────────────────────────────┐
              │ OpenAI ChatModel           │
              │ system prompt 强约束：       │
              │  - 必须引用来源编号           │
              │  - 检索不到就说不知道         │
              │  - 禁止凭空编造 XPath        │
              └────────────────────────────┘
                           │
                           ▼
                  回答 + 引用列表 + 召回详情
```

### 3.3 三个必须做对的细节

**(a) chunk 要带标题路径前缀**

Markdown 切出来的 chunk 如果只有正文，会脱离上下文。比如一个 chunk 是：

> "优先使用 data-testid 属性，其次是 name，避免依赖 class。"

这段话检索时不知道它在讲什么。正确做法是把标题层级拼进去再 embed：

```
[ATP平台手册 > 定位器指南 > XPath 编写建议]
优先使用 data-testid 属性，其次是 name，避免依赖 class。
```

**这一条改动通常能让 Recall@5 提升 5~10 个点**，而且是评估表里第一个能展示的优化。

**(b) 关键词型查询要救**

RAG 的经典弱点：用户直接贴一段 XPath 问"这样写有问题吗"。
纯向量检索对这种**字面匹配型**查询召回很差（embedding 会把所有 XPath 都算成相似）。

处理方式（按投入产出排序）：
1. **最小成本**：在 prompt 层做 query 改写 —— 用 `CompressingQueryTransformer` 把"这段 XPath 有问题吗 `//div[3]/span`"改写成"XPath 绝对路径 索引定位 规范"
2. **进阶**：Qdrant 支持 sparse vector，做真正的 hybrid（BM25 + dense）。0.35 的 langchain4j 集成可能不直接支持，需要自己调 REST
3. **务实的中间态**：加一个纯规则的 `lint` 通道 —— 检测到 query 里含 XPath 特征（以 `/` 或 `//` 开头），直接走规范匹配而非向量检索

> 建议做 1 + 3。方案 2 作为"如果有时间"，或者在面试里作为"下一步会怎么做"来讲。

**(c) 拒答比乱答重要**

新员工问"XPath 怎么写"，如果模型编了一个看起来对但实际定位不到的 XPath，
危害远大于回答"手册里没写，建议问 XX"。

system prompt 里要硬性约束，并且**在评估里专门测这个**（见 §5 的 D 类用例）。

---

## 4. 合成语料设计

> ⚠️ 全部由 AI 生成，不使用任何真实公司资产。见 `00-SHARED-CONTEXT.md` §0。

### 4.1 文档库 `corpus/docs/`（约 15 篇 Markdown）

**手册类 `manual/`（8 篇）**
| 文件 | 内容要点 |
|---|---|
| `01-快速开始.md` | 登录 ATP、创建第一条案例、执行、看报告 |
| `02-案例结构.md` | tc_case / tc_step 的字段含义（呼应共享契约 §1.2） |
| `03-Action参考.md` | 13 个 action 逐个说明 + 示例（呼应 §1.3） |
| `04-定位器指南.md` | XPath / CSS / ID 各自适用场景、写法示例、**常见错误** |
| `05-等待策略.md` | 4 种 wait_strategy 的区别、为什么禁用 SLEEP |
| `06-数据驱动.md` | 参数化、测试数据管理 |
| `07-执行与报告.md` | 执行器工作原理、并发、失败重试、报告解读 |
| `08-トラブルシューティング.md` | **日文**：常见报错与排查（元素找不到、超时、frame 切换失败） |

**规范类 `standards/`（7 篇）**
| 文件 | 内容要点 |
|---|---|
| `STD-001-XPath編集規約.md` | **日文**：STD-001~003 的详细规定与正反例 |
| `STD-004-待機戦略規約.md` | **日文**：禁用 SLEEP 的理由、显式等待的正确用法 |
| `STD-005-命名規約.md` | case_code 格式、title 写法、step description 要求 |
| `STD-006-断言规范.md` | 每条案例至少一个断言、断言粒度、避免过度断言 |
| `STD-007-案例评审checklist.md` | 提交前自检清单 |
| `STD-008-模块划分说明.md` | 8 个模块的边界与归属规则 |
| `FAQ-新人常见问题.md` | 20 条 Q&A，中日混排 |

**语言分布是刻意设计的**：
- 手册主体中文，`08` 用日文
- 规范类 STD-001/004/005 用日文（贴合"日本公司的正式规范文档是日文"的真实情况）
- FAQ 中日混排

这样评估集里可以放**跨语言查询**（中文提问召回日文文档），
直接演示 bge-m3 的价值。**这是一个纯中文 demo 做不出来的展示点。**

### 4.2 案例库 `corpus/cases/`（80 条 JSON）

按 §1.4 的 8 个模块分布，每模块 10 条。字段严格符合 §1.2 schema。

**刻意植入的"脏数据"（约 15 条，用于展示真实感）：**
- 3 条使用了 `SLEEP`（违反 STD-004，属于历史遗留）
- 4 条 XPath 是绝对路径（违反 STD-001）
- 3 条依赖动态 id（违反 STD-002）
- 2 条没有断言步骤（违反 STD-008）
- 3 条 case_code 不符合命名规范

**为什么要植入脏数据**：这样"借鉴存量案例"这个场景才真实 ——
助手不能无脑推荐，得能指出"这条案例可以参考结构，但它的等待策略违反了 STD-004，别照抄"。
**这个能力是 demo1 从"检索"升级到"有判断力的助手"的关键**，也是面试的记忆点。

每条案例的 metadata（存进 Qdrant payload，用于过滤检索）：
```
module_code, priority, actions_used[], has_violation, violation_codes[], step_count
```

### 4.3 被测站点 mock

案例里的 URL 全部指向 `http://localhost:8080/mock/...`，
可以用一组静态 HTML 页面（登录页、商品列表、购物车…）。
**这部分优先级最低** —— demo1 不需要真的执行案例，案例只是被检索的文本。
除非有余力做"生成的案例真的能跑"的加分项，否则跳过。

---

## 5. 评估体系（**本 demo 的核心，优先级最高**）

> 如果时间不够，**砍功能也不要砍评估**。
> 一个有评估的简陋 RAG，在 AI 应用工程面试里的分数远高于一个没评估的华丽 RAG。

### 5.1 评估集 `eval/questions.jsonl`（40 条）

每条格式：
```json
{
  "qid": "Q001",
  "query": "点击按钮之前应该用哪种等待策略",
  "type": "A",
  "lang": "zh",
  "golden_ids": ["manual/05-等待策略.md#显式等待", "standards/STD-004#CLICK"],
  "note": "考察 STD-005 规则"
}
```

**四类用例，配比很重要：**

| 类型 | 数量 | 说明 | 考察点 |
|---|---|---|---|
| **A. 知识问答** | 15 | "wait_strategy 有几种""XPath 能用 class 吗" | 基础召回 |
| **B. 案例检索** | 10 | "找几个购物车加购的案例参考""有没有涉及文件上传的案例" | 路由是否走对 collection + metadata 过滤 |
| **C. 跨语言** | 8 | 中文问 → 日文规范文档；日文问 → 中文手册 | bge-m3 的跨语言能力 |
| **D. 应拒答 / 陷阱** | 7 | "ATP 支持 App 自动化吗"（不支持）、"ASSERT_JSON 怎么用"（不存在的 action） | **抗幻觉**，模型必须说"没有"而不是编 |

D 类是最容易被忽略但面试最出彩的 —— 大多数人的 RAG demo 一问就编。

### 5.2 指标

**检索层（自动化，无需 LLM）：**
- `Recall@5` / `Recall@10` — 主指标
- `MRR@10` — 排序质量
- `nDCG@10` — 多 golden 时更准

**生成层：**
- **引用准确率** — 回答里引的来源是否真的在召回结果里（**纯规则可测，不用 LLM judge**，优先做这个）
- **拒答率** — D 类用例里正确说"不知道"的比例（**纯规则可测**：检查回答是否含拒答标记）
- 忠实度 (faithfulness) — LLM-as-judge，**优先级最低**，有时间再做

> 优先做纯规则能算的指标。LLM-as-judge 又慢又贵又不稳，
> 面试时说"我知道 LLM-as-judge，但在这个规模下引用准确率是更可靠的代理指标"反而显得清醒。

### 5.3 消融实验（**这张表就是你的面试王牌**）

跑一个 `EvaluationTest`，逐步叠加优化，产出这张表：

| # | 配置 | Recall@5 | MRR@10 | 拒答率(D类) | 备注 |
|---|---|---|---|---|---|
| 1 | baseline: 固定512切分 + 单collection + 纯向量top5 | ? | ? | ? | 起点 |
| 2 | + chunk 带标题路径前缀 | ? | ? | ? | 预期 Recall +5~10pt |
| 3 | + 双 collection 与查询路由 | ? | ? | ? | 主要改善 B 类 |
| 4 | + bge-reranker 精排 (召回40→精排5) | ? | ? | ? | 预期最大增益 |
| 5 | + query 改写 / XPath lint 通道 | ? | ? | ? | 主要改善关键词型查询 |
| 6 | + 拒答 prompt 约束 | ? | ? | ? | 主要改善 D 类 |
| 7 | **换 Qwen3-Embedding-0.6B**（其余同第 6 行） | ? | ? | ? | 模型选型对比，见下 |

**关于第 7 行**：Qwen3-Embedding 目前是 MTEB multilingual 榜首级别，
但**它是非对称模型** —— query 侧需要 instruction prefix，document 侧不加，用错会掉点。
langchain4j 0.35 的 `EmbeddingModel` 接口里 query 和 document 走同一个方法，**没法区分**，
所以这一行需要自己实现 `EmbeddingModel` 或绕开该抽象。共享文档 §3.1 有详细说明。

这一行的价值不在于"用上了更强的模型"，而在于你能说
**"我在自己的中日混排语料上实测过两个 embedding 模型"** —— 这比引用榜单分数有力得多。
**如果实测下来 Qwen3 反而更差，那更值得讲**（分析为什么：语料太小？instruction 用法不对？领域不匹配？）。

**实现要求**：每个配置用开关控制（配置文件或枚举），一条命令跑完全部 6 组，输出 Markdown 表格。
**不要手动改代码跑 6 遍** —— 面试官可能会让你现场加一组配置。

> 数字填 `?` 是故意的。**不要预设结果**，跑出来是多少就是多少。
> 如果某项优化没有带来提升，那更值得讲 —— 分析为什么没提升，比编一个漂亮数字有价值得多。

---

## 6. 目录结构

```
demo1-atp-rag/
├── pom.xml
├── README.md                    ← 面向面试官：一句话说明 + 如何跑
├── DECISIONS.md                 ← 决策记录，见 §8
├── .env.example                 ← 环境变量模板（.env 进 .gitignore）
├── corpus/
│   ├── docs/manual/             ← 8 篇手册
│   ├── docs/standards/          ← 7 篇规范
│   └── cases/                   ← 80 条案例 JSON
├── eval/
│   ├── questions.jsonl          ← 40 条评估集
│   └── results/                 ← 消融实验输出（Markdown 表格）
└── src/
    ├── main/java/.../atp/rag/
    │   ├── ingest/              ← 语料加载、切分、metadata 提取、入库
    │   │   ├── DocumentIngestor
    │   │   ├── CaseIngestor
    │   │   └── HeadingPathSplitter    ← 标题路径前缀的实现
    │   ├── retrieve/
    │   │   ├── DocsRetriever
    │   │   ├── CasesRetriever
    │   │   ├── AtpQueryRouter
    │   │   └── XPathLintChannel       ← 关键词型查询的规则通道
    │   ├── model/
    │   │   ├── TeiScoringModel        ← ⭐ 自己实现的 rerank 适配器
    │   │   └── ModelFactory           ← 统一构造 embedding/chat model
    │   ├── assistant/
    │   │   ├── AtpAssistant           ← AiServices 接口定义
    │   │   └── PromptTemplates
    │   ├── config/
    │   │   └── RagConfig              ← 消融开关都在这
    │   └── cli/
    │       └── Main                   ← 交互式 CLI（面试演示用）
    └── test/java/.../
        ├── EvaluationTest             ← ⭐ 消融实验
        └── RetrievalMetrics           ← Recall/MRR/nDCG 计算
```

---

## 7. 里程碑（建议顺序，每步都可独立演示）

| # | 里程碑 | 产出 | 预估 |
|---|---|---|---|
| M0 | **Java 8 链路 spike** | 30 行代码打通 JDK8 → TEI → Qdrant | 最先做，不通就得改方案 |
| M1 | 语料生成 | corpus/ 下全部文档与案例 | 可并行 |
| M2 | 入库 pipeline | 双 collection 建好，Qdrant UI 能看到数据 | |
| M3 | 基础检索 + CLI | 能问能答，带引用 | **第一个可演示版本** |
| M4 | 评估框架 + baseline 数字 | 消融表第 1 行 | ⭐ 关键 |
| M5 | 逐项优化 + 填满消融表 | 消融表全部 6 行 | ⭐ 核心产出 |
| M6 | README + 演示脚本 | 面试现场 5 分钟能讲完 | |

**如果时间紧，M0→M3→M4 是最小可交付集**（有评估的简陋版），
比 M0→M1→M2→M3 做得再漂亮但没数字要强。

---

## 8. DECISIONS.md 要记录的内容

面试官最喜欢问"为什么选 X 不选 Y"。**边做边记**，不要事后补：

- 为什么锁 langchain4j 0.35.0（字节码版本实测）
- 为什么 embedding/rerank 本地、生成走 API（见共享文档 §3）
- 为什么选 bge-m3 而不是 OpenAI embedding（多语言，不只是省钱）
- 为什么选 Qdrant 而不是 pgvector / ES
  - 备好反方观点：**如果公司已有 PG，pgvector 才是对的选择**（少一个中间件 = 少一份运维成本）。
    选 Qdrant 是因为 demo 需要 UI 可视化。承认这一点比硬吹 Qdrant 更显成熟
- 为什么双 collection 而不是单 collection + metadata 过滤
- 遇到的坑与退让（尤其是 Java 8 相关的）

---

## 9. 环境（本机已配置好）

JDK 8 / JDK 17 / Maven 已通过 SDKMAN 安装在本机。在 demo1 目录下切到 Java 8：

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk use java 8.0.472-tem
java -version   # 应显示 1.8.0_472
```

目录下已配 `.sdkmanrc`（如果没有就创建），可用 `sdk env` 自动切换。

**配置**：全部读仓库根目录 `../.env`（由 `../.env.example` 复制）。服务机 `192.168.0.101`。

开工前先确认三个服务连通：

```bash
curl -s http://192.168.0.101:8081/health          # embedding (bge-m3)
curl -s http://192.168.0.101:8082/health          # rerank (bge-reranker-v2-m3)
curl -s http://192.168.0.101:6333/                # qdrant
```

然后跑共享文档 §2.1 的 **rerank 冒烟测试**（§2.4 说明了为什么这步不能省）。

---

## 10. 面试预演问题（做完 demo 后自己过一遍）

必答：
1. 你的 chunk 策略是什么？为什么？换一种会怎样？（**有消融数据就能直接甩表**）
2. rerank 带来多少提升？如果不用 rerank 你会怎么补？
3. 检索不到怎么办？怎么防止模型编 XPath？
4. 语料更新了怎么办？增量还是全量重建？（**这个要想清楚**：案例每天都在新增）
5. 怎么评估？为什么用这几个指标？Recall 高但用户还是不满意，你怎么查？
6. 成本多少？如果 QPS 翻 100 倍，瓶颈在哪？
7. 为什么是 RAG 不是微调？

会踩的：
8. 你这个评估集才 40 条，够吗？（**诚实答**：不够，只能做相对比较不能做绝对判断；生产上要从真实 query 日志采样标注）
9. LLM 路由不稳定怎么办？（路由错了会怎样？有没有兜底 —— 比如置信度低时两个 collection 都查）
10. Java 8 是不是拖累了你？（**转成优势**：这是遗留系统的现实约束，能在约束下交付才是工程能力）
