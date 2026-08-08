# demo2 交接文档 — ATP 案例规范化 MCP Server (Java 17 + Spring Boot)

> **给新 session 的第一条指令**：先完整读 `../00-SHARED-CONTEXT.md`，再读本文档。
> 工作目录：`/home/kanashi/Applications/interview-demos/demo2-atp-mcp/`
> 配置从仓库根目录 `../.env` 读取（由 `.env.example` 复制）。

---

## 1. 问题定义

**场景**：某个 agent（可能是 ATP 平台自己的案例生成 agent，也可能是别的团队的工具）产出了一条测试案例，
但字段不符合 ATP 老平台的关系型 DB schema。这条案例需要经过**规范化**，
才能由平台方入库，并被执行器正确执行。

```
┌─────────────┐   raw case    ┌──────────────────┐  normalized   ┌────────────┐   ┌──────────┐
│ 请求方 agent │ ────────────► │  MCP Server      │ ────────────► │  平台方     │──►│ MySQL    │
│ (任意团队)   │  Streamable   │  (本 demo, k8s)  │  + 诊断信息    │  (入库)     │   └──────────┘
└─────────────┘     HTTP      │                  │               └────────────┘         │
                              │  ⚠️ 不碰数据库    │                                      ▼
                              │  ⚠️ 完全无状态    │                              ┌──────────────┐
                              └──────────────────┘                              │  执行器       │
                                                                                │ 对此完全无感知 │
                                                                                └──────────────┘
```

**"执行器无感知"翻译成工程语言**：输出必须 100% 满足 schema 的**全部**约束 ——
类型、枚举、长度、必填、外键，以及 Action 与 locator 的契约（共享文档 §1.3）。
只要有一条不满足，执行器就会在运行时炸，而那时已经离故障源很远了。

---

## 2. 核心设计原则：**让模型少做事**

### 2.1 最大的生产风险是什么

不是模型答不出来，**是模型静默编造一个看起来合理的值**。

例：模型把 `module_id` 填成 `"M009"`（不存在）。
schema 校验能过（是字符串），格式也对（M+3位数字），入库时若无外键约束就悄悄进去了，
执行器读到这条案例找不到模块配置 → 报一个八竿子打不着的错。
**排查要花几小时，根因是几天前模型编的一个 ID。**

### 2.2 五条铁律

| # | 原则 | 具体做法 |
|---|---|---|
| **1** | **确定性优先** | 能用规则映射的字段绝不给模型。模型是最后手段，不是第一手段 |
| **2** | **模型只填空，不重写** | ❌ 把整条案例丢给模型让它"输出规范格式"<br>✅ 规则先跑完，算出缺口，**只把缺失字段的填空题**给模型<br>理由：让模型输出全文，它会悄悄改动原本正确的字段，而你无法察觉 |
| **3** | **不依赖 provider 的结构化输出保证** | 见 §2.3 —— 这条在本项目里有具体的、可验证的理由 |
| **4** | **一切外键对照字典校验** | 模型填的 `module_id` 必须在字典中真实存在，否则直接 REJECT |
| **5** | **失败不静默** | 校验不过 → `REJECTED` + 结构化诊断。**宁可拒绝，不可错入** |

### 2.3 ⭐ 为什么不依赖 Structured Outputs（本项目的实证理由）

开发期用 DeepSeek、后期切 Kimi K3。**两者的结构化输出能力不对等**：

| | DeepSeek | Kimi K3 |
|---|---|---|
| `response_format: json_object` | ✓ | ✓ |
| `response_format: json_schema` + `strict` | ✗ **不支持** | ✓ |
| function calling `strict` | beta，需 `/beta` 端点，[有已知 bug](https://github.com/deepseek-ai/DeepSeek-V3/issues/1069) | ✓ |
| `tool_choice: required` | [V4 拒绝](https://github.com/deepseek-ai/DeepSeek-V3/issues/1376) | ✓ |

`json_object` 只保证**是合法 JSON**，**不保证符合你的 schema** —— 字段可以缺、枚举可以错、类型可以乱。

**所以架构上引入一个策略层**：

```
interface StructuredOutputStrategy {
    String buildRequest(JsonSchema gapSchema, String context);
    JsonNode parseResponse(String raw);
}

├── JsonObjectStrategy      ← 最低公分母。schema 写进 prompt + few-shot。DeepSeek 走这条
└── NativeSchemaStrategy    ← response_format: json_schema, strict:true。Kimi K3 / OpenAI 走这条
```

**关键点：无论走哪条策略，本地 JSON Schema 校验（L4）都必须执行，永不跳过。**
native strict 只是"少重试几次"的优化，**不是**可以省掉校验的理由。

配置：`LLM_STRUCTURED_MODE = json_object | native_schema | auto`（默认 `json_object`，最保守）。

> **面试话术**：
> "我的补全环节不依赖任何 provider 的 structured output 特性。
> 因为我实测过 DeepSeek 和 Kimi 的能力不对等 —— DeepSeek 只有 json_object，
> strict schema 还在 beta 且有已知 bug。所以我用最低公分母加自己校验。
> 副作用是换 provider 零成本，这也是为什么我能开发期用 DeepSeek、上线切 Kimi。"
>
> 这比"我用了 OpenAI 的 Structured Outputs"高一个层次 ——
> 前者是**在异构 provider 现实下做可靠性设计**，后者只是会用一个 API 参数。

### 2.4 一个容易被忽略的收益

按此设计，**请求方给的案例足够完整时，整条链路的模型调用次数是 0**。
纯规则跑完直接输出，又快、又免费、又完全确定。

**多数人的"AI 服务"是无脑每次都调模型。** 能说清"我的服务在 X% 的输入上根本不调 LLM"，
体现的是工程判断力。记得在测试里验证（§7.4），并在 README 给出实测比例。

---

## 3. 转换流水线

```
raw input (任意形状 JSON)
    │
    ▼
┌───────────────────────────────────────────────────────────────┐
│ L0  输入规整 (Envelope Parsing)              【纯规则】          │
│  • 容忍多种输入形状：嵌套 / 扁平 / steps 叫 actions 或 操作步骤    │
│  • 字段别名字典：name|caseName|title|标题|タイトル → title        │
└───────────────────────────────────────────────────────────────┘
    ▼
┌───────────────────────────────────────────────────────────────┐
│ L1  确定性映射 (Rule Mapping)                【纯规则】          │
│  • 枚举归一化：点击|click|tap|press|クリック → CLICK              │
│  • locator_type 推断：/ 或 // 开头→XPATH，# 或 . 开头→CSS        │
│  • wait_strategy 填充（STD-005/006）：                          │
│      CLICK→CLICKABLE, ASSERT_*→VISIBLE, INPUT→VISIBLE          │
│  • seq 重排为 1..n 连续                                         │
│  • 默认值：status=DRAFT, browser=CHROME, timeout_sec=30 ...     │
│  • 长度超限 → ERROR（**不要截断**，截断改变语义）                 │
└───────────────────────────────────────────────────────────────┘
    ▼
┌───────────────────────────────────────────────────────────────┐
│ L2  缺口分析 (Gap Analysis)                  【纯规则】⭐        │
│  • 对照 schema 算出：还缺哪些必填字段？哪些规则填不了？            │
│  • 产出「填空题清单」+ 一份**只含缺失字段**的子 JSON Schema        │
│  • ⭐ 清单为空 → 直接跳到 L4，**零模型调用**                     │
└───────────────────────────────────────────────────────────────┘
    │ 清单非空
    ▼
┌───────────────────────────────────────────────────────────────┐
│ L3  模型补全                            【唯一调用 LLM 的地方】   │
│  • 策略层：JsonObjectStrategy / NativeSchemaStrategy（§2.3）    │
│  • 输入：填空题清单 + 原案例上下文 + 模块字典 + 规范摘要           │
│  • 输出：只含缺失字段，每项带 confidence + reason                │
│  • temperature=0，thinking 关闭                                │
│  • 典型任务：module_id 语义匹配、priority 推断、description 补全  │
└───────────────────────────────────────────────────────────────┘
    ▼
┌───────────────────────────────────────────────────────────────┐
│ L4  校验 (Validation)                        【纯规则，分级】    │
│  • JSON Schema：类型 / 枚举 / 长度 / 必填  ← **永不跳过**        │
│  • 外键：module_id 必须在字典中真实存在  ← ⭐ 防模型编造          │
│  • Action 契约：共享文档 §1.3（哪个 action 必须带 locator）       │
│  • 业务规范：STD-001 ~ STD-008                                  │
│  • seq 连续性、至少一个断言步骤                                  │
│  • 分级：ERROR(拒绝) / WARN(接受但标记) / INFO(建议)             │
└───────────────────────────────────────────────────────────────┘
    │
    ├── 通过 ──────────────────────────────────► L5
    └── 不通过
         ├─ 错误源自「模型填的字段」？ ─是─► 带错误信息回 L3 **重试一次**
         │                                  （仍失败 → REJECTED）
         └─ 错误源自「输入本身」？ ───否─► 直接 REJECTED，不重试
                                            （输入错了重试也没用，白烧钱）
    ▼
┌───────────────────────────────────────────────────────────────┐
│ L5  组装输出                                                   │
│  normalized_case / provenance / diagnostics / trace_id        │
│  status: ACCEPTED | ACCEPTED_WITH_WARNINGS | REJECTED         │
└───────────────────────────────────────────────────────────────┘
```

---

## 4. Provenance：每个字段都要能说清来源

这是**让平台方敢用**的关键。

| 来源 | 含义 | 平台方该怎么对待 |
|---|---|---|
| `INPUT` | 请求方原样提供 | 直接信任 |
| `RULE` | 规则推导（附规则编号如 `STD-005`） | 可信且可审计 —— 规则是明文的 |
| `DEFAULT` | schema 默认值 | 可信 |
| `MODEL` | 模型推断（附 `confidence` + `reason`） | ⚠️ 可配置是否需人工确认 |

```json
{
  "provenance": {
    "title":     { "source": "INPUT" },
    "module_id": { "source": "MODEL", "confidence": 0.92,
                   "reason": "标题含「カート」且步骤操作购物车页面" },
    "priority":  { "source": "MODEL", "confidence": 0.65,
                   "reason": "无明确线索，按核心交易流程推断为 P1" },
    "browser":   { "source": "DEFAULT" },
    "steps[0].wait_strategy": { "source": "RULE", "rule": "STD-005" }
  }
}
```

平台方可据此定策略：*"`MODEL` 来源且 `confidence < 0.8` 的字段，案例入库为 `DRAFT` 并进人工复核队列"*。
**没有 provenance，平台方只能全信或全不信，这个服务就没法在生产用。**

---

## 5. MCP 接口设计

### 5.1 Tools

| Tool | 调模型 | 幂等 | 说明 |
|---|---|---|---|
| `atp_describe_schema` | ✗ | ✓ | 返回目标 schema、枚举、必填规则、规范摘要 |
| `atp_normalize_case` | 可能 | ~ | **主入口**：转换 + 补全 + 校验 |
| `atp_validate_case` | ✗ | ✓ | **只校验不修改**，纯规则，毫秒级 |
| `atp_lint_locator` | ✗ | ✓ | XPath/CSS 静态规范检查 |
| `atp_list_modules` | ✗ | ✓ | 模块字典（外键取值范围） |

**`atp_describe_schema` 为什么必须有**：让调用方 agent 在**生成阶段**就知道该产出什么形状，
从源头降低错误率 —— 这是"左移"。只提供 `normalize` 等于放任上游乱生成再下游收拾。

**`atp_validate_case` 为什么必须独立暴露**：平台方入库前应**再调一次**做最终守门（信任但验证）。
纯规则、无副作用、完全幂等。把校验藏在 normalize 内部，平台方就没法自己把关。

### 5.2 Resources

`atp://schema/tc_case` · `atp://modules` · `atp://standards/xpath`

### 5.3 多平台扩展

用户明确要求"保留其他平台接入的可能"。抽象成 `PlatformProfile`：

```java
interface PlatformProfile {
    String id();                      // "atp" / "generic-junit"
    JsonSchema targetSchema();
    AliasDictionary aliases();        // L0
    List<FieldMapper> mappers();      // L1
    List<Validator> validators();     // L4
    EnumDictionary enums();
    ForeignKeyResolver foreignKeys();
}
```

**核心流水线 L0~L5 完全不认识 ATP**，只认 `PlatformProfile`。
除 `atp` 外再放一个精简的 `generic-junit` profile 证明可扩展（能跑通即可，不必完整）。

> 面试点：**"平台无关的领域层"** —— 新接一个平台只需实现 profile，不改流水线一行代码。

---

## 6. 部署形态：Spring Boot + Streamable HTTP + k8s

> **这是本 demo 相对"玩具 MCP server"最大的差异化。**

### 6.1 为什么是 HTTP 而不是 stdio

stdio 要求每个使用方在自己的机器上拉起你的进程 —— 等于**给每个团队发 SDK**，
版本分裂、升级困难、依赖冲突。

服务跑在 k8s 上、以 Streamable HTTP 暴露，则：
- 别的团队填一个 URL 就能接入，**不需要分发任何东西**
- 修 bug 上线一次全体生效
- 监控、限流、鉴权、灰度都能复用现有基础设施

**这是真实的生产权衡，比"面试演示方便"重要得多。** 采纳 HTTP 方案。

### 6.2 ⭐ 必须用 STATELESS 模式

MCP 的 Streamable HTTP **默认是 STREAMABLE 模式，会维持 session 状态**。
在 k8s 多副本下，请求被负载均衡打到不同 pod，
**第二个请求找不到 session → 连接失败**。这是 MCP 上 k8s 最经典的坑。

Spring AI 提供 STATELESS 模式，每个请求自包含，无 session 亲和性要求：

```yaml
spring:
  ai:
    mcp:
      server:
        protocol: STATELESS      # ← k8s 多副本必须
        name: atp-normalizer
        version: 1.0.0
```

**我们的业务设计本来就是无状态的**（每次 normalize 独立，不碰 DB），
所以这里完全没有妥协 —— 架构与部署形态天然契合。这点值得在面试里点明。

### 6.3 版本矩阵 —— ⚠️ 开工第一件事是核实

搜索显示 **Spring AI 2.0.0 于 2026-06-12 GA**，引入 `@McpTool` / `@McpResource` 注解 API，
并**废弃 SSE 转向 Streamable HTTP**；MCP Java SDK 2.0.0 也已 GA（对应 2025-11-25 spec）。

但有一个**必须先验证的兼容性问题**：

> Spring AI 2.0 常与 Spring Boot 4.x 搭配，而 **Spring Boot 4.x 的最低 JDK 要求可能高于 17**。
> 用户要求 Java 17。**先跑通版本矩阵再写代码。**

决策顺序：
1. 若 Spring Boot 4 + Spring AI 2.0 支持 JDK 17 → 用最新，享受 `@McpTool` 注解
2. 若要求 JDK 21 → **退到 Spring Boot 3.x + Spring AI 1.1.x**（`spring-ai-starter-mcp-server-webmvc`，同样支持 STATELESS）
3. 把选择和理由记进 `DECISIONS.md`

依赖（1.1.x 形态，2.0 需按实际调整）：
```
spring-boot-starter-web
spring-ai-starter-mcp-server-webmvc
com.networknt:json-schema-validator      ← L4 本地校验，核心
com.fasterxml.jackson.core:jackson-databind
spring-boot-starter-actuator             ← k8s 探针
spring-boot-starter-validation
```

**LLM 调用不要用 Spring AI 的 ChatClient 抽象** —— §2.3 的策略层需要精确控制
`response_format` 的原始形状，多包一层反而碍事。直接用 `RestClient` + Jackson 打 OpenAI 兼容端点。

### 6.4 k8s 清单

```
k8s/
├── deployment.yaml     replicas: 2（证明 STATELESS 真的有效）
├── service.yaml
├── configmap.yaml      非敏感配置
├── secret.yaml.example LLM_API_KEY（真实 secret 不进 git）
└── hpa.yaml            基于 CPU 的水平扩展
```

要点：
- **探针**：`/actuator/health/liveness` 与 `/readiness`。
  readiness 应检查 LLM 端点可达性 —— 但**不要**让 LLM 故障导致 pod 被杀，
  见 §6.5 的降级策略
- **优雅停机**：`server.shutdown=graceful`，配合 `terminationGracePeriodSeconds`
- **资源**：无状态纯计算，requests 给小，靠 HPA 扩
- **Secret**：`LLM_API_KEY` 走 k8s Secret，绝不进镜像或 ConfigMap

### 6.5 LLM 故障时的降级（生产可靠性关键）

LLM 超时/限流/挂掉时，**不要让整个请求失败**。降级策略：

1. 返回**纯规则跑出来的部分结果**
2. `status = ACCEPTED_WITH_WARNINGS` 或 `REJECTED`（取决于缺的是否必填）
3. diagnostics 明确标注 `MODEL_UNAVAILABLE`，列出哪些字段待补
4. 平台方可选择存为 DRAFT 等人工补全

**理由**：规则已经完成了 80% 的工作，全盘丢弃是浪费。
而且这让服务在 LLM 完全不可用时**依然有价值**（退化为纯规则规范化器）。
这个降级设计是面试的加分项。

超时设置要短（建议 10~15s）并有熔断，别让 LLM 拖垮整个服务的线程池。

### 6.6 stdio 作为可选的第二入口

保留一个 stdio profile 用于**本地调试**和**面试现场在 Claude Code 里直接演示**，
用 Spring profile 切换（`--spring.profiles.active=stdio`）。

⚠️ 启用 stdio 时的坑（HTTP 模式下不存在，但值得知道，面试可能被问）：
> stdio 下 JSON-RPC 帧走 **stdout**，**任何往 stdout 写的字节都会破坏协议**。
> Spring Boot banner、默认 ConsoleAppender、`System.out.println` 都会破坏它。
> 必须：`spring.main.banner-mode=off`，logback appender 显式指向 **stderr**。

这也印证了 §5.3 的设计价值 —— **核心逻辑与 transport 解耦**，换入口不动业务代码。

---

## 7. 测试策略（可靠性的证明）

"生产环境最不容易出错"不是靠嘴说的，是靠测试证明的。

### 7.1 黄金用例集（30 条）
`(raw input, expected output)` 对，覆盖：完整输入（应零模型调用）/ 缺 module_id、priority、title /
枚举需归一（中日英三语动作名）/ locator_type 缺失 / 违反 STD-001 绝对路径 / 违反 STD-004 用了 SLEEP /
无断言步骤 / seq 乱序跳号 / 字段超长 / 完全无法救的垃圾输入。

### 7.2 契约测试
**`normalize` 输出的每条 `ACCEPTED` 结果，必须能通过 `validate_case`。**
参数化跑遍全部黄金用例。

### 7.3 ⭐ 属性测试（核心安全不变式）
随机生成畸形输入，断言这条永远成立：

> **要么 `ACCEPTED` 且完全通过校验，要么 `REJECTED` 且带诊断。
> 永远不存在「ACCEPTED 但违反 schema」的输出。**

这就是"执行器无感知"的形式化表述。**面试能说出这句话，比讲十个功能点管用。**

### 7.4 零模型路径 & 幂等
- 完整案例 → 断言**模型调用次数 == 0**（Mock LLM 计数）
- 同输入跑 3 次 → 结果一致

### 7.5 策略层对拍测试
同一批输入分别走 `JsonObjectStrategy` 和 `NativeSchemaStrategy`，
断言最终 normalized 结果一致。**证明"换 provider 零成本"不是空话。**

### 7.6 Mock LLM（必须有）
否则测试要花钱、要联网、不稳定，CI 跑不了。
定义 `LlmClient` 接口，生产实现打 HTTP，测试实现返回预设响应并计数。

---

## 8. 目录结构

```
demo2-atp-mcp/
├── pom.xml
├── README.md                       ← 面向面试官 + 其他团队如何接入
├── DECISIONS.md
├── Dockerfile
├── k8s/                            ← §6.4
└── src/
    ├── main/java/.../atp/mcp/
    │   ├── McpServerApplication
    │   ├── tool/                       ← @McpTool 定义（薄层，只做参数转换）
    │   │   └── AtpNormalizeTools
    │   ├── pipeline/                   ← ⭐ 平台无关的核心
    │   │   ├── NormalizationPipeline    (L0~L5 编排)
    │   │   ├── EnvelopeParser           (L0)
    │   │   ├── RuleMapper               (L1)
    │   │   ├── GapAnalyzer              (L2) ⭐
    │   │   ├── ModelCompleter           (L3)
    │   │   └── ValidationEngine         (L4)
    │   ├── profile/
    │   │   ├── PlatformProfile          ← 扩展点
    │   │   ├── atp/AtpProfile
    │   │   └── junit/GenericJUnitProfile
    │   ├── llm/
    │   │   ├── LlmClient                ← 接口（便于 Mock）
    │   │   ├── OpenAiCompatibleClient   ← DeepSeek / Kimi 共用
    │   │   └── strategy/                ← ⭐ §2.3 策略层
    │   │       ├── StructuredOutputStrategy
    │   │       ├── JsonObjectStrategy
    │   │       └── NativeSchemaStrategy
    │   ├── domain/                      ← TestCase / TestStep / Provenance / Diagnostic
    │   └── lint/LocatorLinter
    ├── main/resources/
    │   ├── application.yml              ← STATELESS 配置
    │   ├── application-stdio.yml
    │   └── schema/tc_case.schema.json
    └── test/java/.../                   ← §7 的六类测试
```

---

## 9. 里程碑

| # | 里程碑 | 产出 | 备注 |
|---|---|---|---|
| M0 | **版本矩阵核实** + Hello World tool | Spring Boot 起得来，一个 echo tool 可被调用 | ⚠️ §6.3，先解决 JDK 17 兼容性 |
| M1 | 领域模型 + AtpProfile schema/枚举/字典 | `describe_schema`、`list_modules` 可用 | |
| M2 | L0/L1/L2 纯规则链路 + `validate_case` + `lint_locator` | **零模型的规范化已可用** | ⭐ 此时已有演示价值 |
| M3 | LLM 策略层 + L3 补全 | `normalize_case` 完整可用，两种策略都能跑 | ⭐ §2.3 |
| M4 | Provenance + 分级诊断 + 降级 | 输出结构完整，LLM 挂掉能降级 | ⭐ |
| M5 | 六类测试跑通 | 属性测试与策略对拍是重点 | ⭐ |
| M6 | Dockerfile + k8s manifest + 2 副本验证 | 证明 STATELESS 有效 | ⭐ 差异化 |
| M7 | GenericJUnitProfile + README | 可扩展性证明 | |

**M2 结束时做一次演示彩排** —— 纯规则版本已能讲清"让模型少做事"的主张。

---

## 10. 环境

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk env          # 目录下已有 .sdkmanrc → JDK 17.0.16
```

配置从 `../.env` 读取。**本 demo 不需要服务机**（不用向量库、不用本地模型），
只需 `LLM_API_KEY`，可以完全独立于 demo1 开发，**现在就能开工**。

---

## 11. 面试预演问题

必答：
1. 为什么不直接把整条案例丢给模型让它输出规范格式？（**§2.2 铁律 2，最核心**）
2. 模型编了一个不存在的 module_id 怎么办？（外键校验 + provenance + REJECT）
3. 怎么保证执行器不会因规范化的案例炸？（§7.3 安全不变式）
4. 为什么不用 Structured Outputs？（**§2.3 —— 有实证的 provider 能力差异，答得好非常加分**）
5. MCP server 上 k8s 多副本，session 怎么办？（**§6.2 STATELESS，这是经典坑**）
6. 为什么 MCP server 不直接入库？（职责边界 / 无状态 / 权限 / 可测试性）
7. case_code 需要全局唯一序号但你不碰库，怎么办？
   （返回模板 + `requires_platform_assignment` 标记，由平台方分配 —— 体现无状态边界的思考）
8. LLM 挂了整个服务就废了吗？（**§6.5 降级为纯规则规范化器**）
9. 换一个平台接入要改多少代码？（§5.3）

会踩的：
10. 你怎么知道模型补的 priority 是对的？
    （**诚实答**：不知道，所以才有 confidence 和 provenance —— 设计上承认不确定性，而不是假装确定）
11. 准确率多少？（用黄金用例集给数字，并说明 30 条的样本量限制）
12. 为什么不用 Spring AI 的 ChatClient？（§6.3 末尾 —— 需要精确控制 response_format 原始形状）
