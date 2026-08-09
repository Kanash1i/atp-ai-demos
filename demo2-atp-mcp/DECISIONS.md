# DECISIONS — demo2 ATP 案例规范化 MCP Server

记录**做了什么选择、为什么、以及验证方式**。只记有取舍的决策，不记流水账。

---

## M0-D1 版本矩阵：Spring Boot 4.1.0 + Spring AI 2.0.0 + JDK 17

**结论：走交接文档 §6.3 的「路线 1」——用最新版，享受 `@McpTool` 注解 API。**

交接文档担心「Spring AI 2.0 常配 Spring Boot 4.x，而 Spring Boot 4 的最低 JDK 可能高于 17」，
需要时退到 Spring Boot 3.x + Spring AI 1.1.x。**实测该担心不成立**，无需退版。

### 怎么验证的（不靠文档，靠字节码）

判断一个库的最低 JDK，最硬的证据是它自己的 class 文件版本号：

```bash
# spring-boot 4.0.7 / 4.1.0 的 MANIFEST 与字节码
unzip -p spring-boot-4.1.0.jar META-INF/MANIFEST.MF | grep Build-Jdk-Spec
#   → Build-Jdk-Spec: 17
od -An -t u1 -N 8 org/springframework/boot/SpringApplication.class   # 第 8 字节 = major version
#   → 61   （61=JDK17, 65=JDK21）
```

对 `spring-ai-mcp-annotations:2.0.0` 做同样检查，同样是 **major=61**。

| 组件 | 版本 | 字节码 major | 结论 |
|---|---|---|---|
| Spring Boot | 4.1.0 | 61 | JDK 17 ✅ |
| Spring AI | 2.0.0 | 61 | JDK 17 ✅ |
| MCP Java SDK | 2.0.0（经 starter 传递） | — | GA |

**最终判据不是字节码而是「真的跑起来了」**：JDK 17.0.16 下 `mvn package` 通过，
产物 class major 同样是 61，服务启动日志 `Starting McpServerApplication ... using Java 17.0.16`。

### 版本耦合关系（换版本时注意）

`spring-ai-starter-mcp-server-webmvc:2.0.0` 的 pom **硬编码依赖 `spring-boot-starter-web:4.1.0`**。
也就是说 Spring AI 2.0.0 与 Spring Boot 4.1.0 是绑定发布的，
升级其中一个而不动另一个大概率会撞版本冲突。

### 补记：为什么 demo2 可以用新栈，而 demo1 不行

ATP 平台本身是 **Java 8 + Spring 4 / Spring Boot 2.7 + MySQL 5.7** 的遗留系统。
demo1（RAG 知识助手）因此被钉死在 **Spring Boot 2.7.18 + langchain4j 0.35.0** ——
2.7.x 是最后一个支持 Java 8 的 Spring Boot 系列，0.35.0 是最后一个 Java 8 字节码的 langchain4j。

**demo2 不受这个约束，理由是它的定位不同**：

| | demo1 | demo2（本项目） |
|---|---|---|
| 定位 | 老平台**内部**的模块 | 老平台**旁边**的新服务 |
| 栈 | Spring Boot 2.7.18 + Java 8 | Spring Boot 4.1.0 + Java 17 |
| 版本从哪来 | 被平台绑死 —— 算出的是**下界的尽头** | 不被绑 —— 算出的是**上界可达** |

demo2 是一个通过 HTTP 被调用的旁路服务：它不嵌进老平台的进程，不共享它的依赖树，
甚至连数据库都不碰。所以它没有理由背上 Java 8 的包袱。

**两边其实是同一种做法**：版本都不是"选"出来的，是被约束算出来的。
只不过 demo1 算出了「最后一个还支持 Java 8 的版本」，
而 demo2 算出了「最新版恰好还够得着 JDK 17」——
若实测发现 Spring Boot 4 要求 JDK 21，这里就会老老实实退回 3.x（交接文档 §6.3 的预案）。

> 面试叙事：**「同一个平台，两次介入。一次在里面，所以被它的栈绑住；
> 一次在外面，所以能用新栈。这两种处境在企业里都很常见。」**
> 比单说"一个用 8 一个用 17"有信息量得多。

---

## M0-D2 ⚠️ Spring Boot 4 迁移到 Jackson 3，包名变成 `tools.jackson.*`

**这是本次踩到的最有影响的意外**，会一路影响到 M2/M3/M4，必须先记下来。

写测试时 `import com.fasterxml.jackson.databind.ObjectMapper` **编译不过**：
`package com.fasterxml.jackson.databind does not exist`。

依赖树给出了答案：

```
tools.jackson.core:jackson-databind:3.1.4          ← Jackson 3，groupId 和包名全变了
io.modelcontextprotocol.sdk:mcp-json-jackson3:2.0.0 ← MCP SDK 也已切到 Jackson 3
com.fasterxml.jackson.core:jackson-annotations:2.21 ← 只剩 annotations 还是 2.x（被其他库传递带入）
```

**影响与应对**：

| 项 | Jackson 2 | Jackson 3 |
|---|---|---|
| 包名 | `com.fasterxml.jackson.*` | `tools.jackson.*` |
| 取字符串 | `node.asText()` | `node.asString()`（`asText()` 保留但已非惯用名） |
| `readTree` 异常 | checked `JsonProcessingException` | `JacksonException` |

搜到的绝大多数 Spring AI / MCP 示例代码还是 Jackson 2 写法，**直接抄会编译不过**。
本项目统一用 `tools.jackson.*`。

### 顺带的好消息：核心校验库已经在依赖树里了

```
com.networknt:json-schema-validator:3.0.0   ← 由 MCP SDK 传递引入
```

这正是交接文档指定的 L4 本地校验库，而且是**适配 Jackson 3 的 3.0.x 线**。

⚠️ **M2 加显式依赖时不要贪新写 `3.0.6`**（Maven Central 上的 latest），
必须确认它与传递进来的 `3.0.0` 及 Jackson `3.1.4` 处在同一条兼容线上，
否则会出现两套 `ObjectMapper` 并存的诡异问题。届时用 `dependency:tree` 复核。

---

## M0-D3 ⭐ `protocol: STATELESS` —— 做了对照实验，不是照抄配置

CLAUDE.md 把这条列为红线。为了确认它**真的**在解决问题（而不是一句传说），
本次起了两个实例做 A/B 对照，同一个 jar，只改 `spring.ai.mcp.server.protocol`。

### 实测配置事实（从 `spring-configuration-metadata.json` 提取，非猜测）

```
spring.ai.mcp.server.protocol   默认值 = streamable      ← 默认就是会踩坑的那个
枚举值 = SSE | STREAMABLE | STATELESS                     ← javap 反查 ServerProtocol 确认
spring.ai.mcp.server.streamable-http.mcp-endpoint  默认 /mcp
```

### 对照结果

同一个请求（`tools/list`，不带 `Mcp-Session-Id`，模拟「被 LB 打到另一个 pod」）：

| 模式 | 结果 |
|---|---|
| `STREAMABLE`（默认） | ❌ `{"jsonRpcError":{"code":-32601,"message":"Session ID missing"}}` |
| `STATELESS` | ✅ 正常返回 tools 列表 |

且 `initialize` 的响应头：

| 模式 | `Mcp-Session-Id` |
|---|---|
| `STREAMABLE` | 下发，形如 `7e1389af-8941-45e8-9a83-5cc24c7a46e0` |
| `STATELESS` | **不下发** |

两种模式走的是**完全不同的 autoconfiguration 分支**，启动日志可区分：

```
STATELESS  → McpServerStatelessAutoConfiguration : Registered tools: 1
STREAMABLE → McpServerAutoConfiguration          : Registered tools: 1
```

### 为什么这个坑特别危险

它**在单副本本地开发时完全不会暴露**：本地只有一个进程，session 永远在。
只有上了 k8s 多副本、请求被负载均衡分散之后才会炸，
而那时错误信息（`Session ID missing`）离根因（一行 yaml 配置）已经很远。

**所以把它固化成了测试**（`McpStatelessProtocolTest`，4 个用例），
断言的是协议层的可观察行为：不带 session 的请求必须能完成，且 `initialize` 不得下发 session id。
配置被改坏时测试立刻红，而不是等上线。

> 面试话术：这不是"我配了个参数"，是"我复现了故障、量化了差异、并用测试锁死了它"。

---

## M0-D4 tool 的 annotations 默认值是错的，M1 必须显式覆盖

`tools/list` 的实际返回里，`atp_echo` 带着这样一组 annotations：

```json
"annotations": { "readOnlyHint": false, "destructiveHint": true,
                 "idempotentHint": false, "openWorldHint": true }
```

**这是 MCP 规范的默认值，对我们的 tool 全是错的。**
交接文档 §5.1 里 5 个 tool 有 4 个是只读且幂等的
（`atp_describe_schema` / `atp_validate_case` / `atp_lint_locator` / `atp_list_modules`）。

被标成 `destructiveHint: true` 的后果是实际的：调用方 agent 可能因此
在调用前向用户请求确认，或干脆回避调用一个「破坏性」工具 ——
而它其实只是个毫秒级的纯函数校验。

**M1 起每个 tool 都要显式写 `@McpTool(annotations = ...)`**，不吃默认值。

---

## M0-D5 配置来源：`spring.config.import` 读根目录 `.env`

CLAUDE.md 硬约束「所有配置读仓库根目录 `../.env`，代码里不得出现硬编码 key/URL/IP」。

用 Spring Boot 原生能力实现，不引第三方 dotenv 库：

```yaml
spring:
  config:
    import: "optional:file:../.env[.properties]"
```

- `[.properties]` 告诉 Spring 按 properties 格式解析这个无扩展名的文件
- `optional:` 前缀让文件缺席时也能启动 —— **CI 和容器里没有 `.env`，靠环境变量注入**，
  没有这个前缀会直接启动失败
- 环境变量优先级高于导入的文件，所以 k8s 里用 Secret 覆盖 `LLM_API_KEY` 是自然的

---

## M0-D2 补记 ⚠️ Jackson 3 的注解包名**没有**跟着改

M1 写序列化时踩到的后续坑，接在 M0-D2 后面记：

```
tools.jackson.core:jackson-databind:3.1.4          ← databind / core 是新包名
  └── com.fasterxml.jackson.core:jackson-annotations ← 注解仍是旧 groupId、旧包名
```

即 Jackson 3 处于一个**混合状态**：

| 用途 | 包名 |
|---|---|
| `ObjectMapper` / `JsonNode` | `tools.jackson.databind.*` |
| `@JsonProperty` / `@JsonInclude` | **`com.fasterxml.jackson.annotation.*`**（没变） |

同一个文件里两种 `com.fasterxml` / `tools.jackson` 前缀并存看着很怪，但这是对的。
凭直觉把注解 import 也改成 `tools.jackson.*` 会直接编译不过。

---

## M1-D1 ⭐ 把 Action 契约表编码进枚举，而不是写进校验器的 if-else

共享契约 §1.3 规定了每个 action 的 locator / input_data / expected 是否必填。
这份信息有两个消费方：**L1 要据此填充**，**L4 要据此校验**。

如果写成校验器里的 if-else，L1 和 L4 会各写一份，然后慢慢漂移 ——
而漂移的表现是「校验通过但填充错了」这种最难查的不一致。

所以做成 `Action` 枚举的常量声明：

```java
//                locator     input_data  expected    wait 策略                依据
CLICK            (REQUIRED,   FORBIDDEN,  FORBIDDEN,  WaitStrategy.CLICKABLE, "STD-005"),
ASSERT_TEXT      (REQUIRED,   FORBIDDEN,  REQUIRED,   WaitStrategy.VISIBLE,   "STD-006"),
```

两层读同一份声明，**想让它们对不上都做不到**。

配套的 `ActionContractTableTest` 把 §1.3 的表格**独立誊写一遍**做逐行对照。
这个重复是刻意的：期望值必须有独立于实现的来源，否则测试只能证明"枚举等于它自己"。

**顺带的收益**：这张表通过 `atp_describe_schema` 的 `action_contracts` 字段
直接暴露给调用方 agent，所以不存在"文档写的和服务执行的不一致"——
它们本来就是同一个对象。

---

## M1-D2 ⭐ 规范与语义冲突时：显式偏离 + 诊断，而不是二选一

实现 STD-006（`ASSERT_*` 的 wait_strategy 必须是 `VISIBLE`）时发现一处真实矛盾：

> **`ASSERT_NOT_EXIST` 断言的是元素【不存在】。**
> 若按字面填 `VISIBLE`，执行器会去等一个不该出现的元素变为可见 ——
> 必然空耗到 `wait_timeout_sec` 超时。每条这样的用例都会白白慢十几秒，甚至被误判为失败。

三个选项，选第三个：

| 方案 | 问题 |
|---|---|
| 严格按字面填 `VISIBLE` | 产出一个**已知会超时**的配置，把问题丢给执行器 |
| 悄悄改成 `NONE` | 服务擅自修改了平台规范，且无人知晓 —— 正是本项目最反对的静默行为 |
| ✅ 填 `NONE` + 在契约里**显式标注偏离与理由** | 平台方看得到、可裁定；规范该不该改是他们的事 |

落地为 `Action.waitStrategyDeviation()`，随 `describe_schema` 一起返回，
并有测试断言**目前有且只有这一处偏离**（偏离越多，这个字段的警示作用越被稀释）。

> 这是「失败不静默」在规范冲突场景下的形态：
> 不假装规范没有漏洞，也不擅自替平台方改规范，而是把选择摊开。

---

## M1-D3 `PlatformProfile` 只声明当前有实现的方法

交接文档 §5.3 给出的接口还包含 `aliases()` / `mappers()` / `validators()`，
分别服务 L0 / L1 / L4 —— 而这三层要到 M2 才存在。

M1 **刻意不提前声明**它们。提前声明一批只能返回空列表的方法，
除了让 profile 看起来"完整"以外没有任何好处，反而掩盖了「哪些能力真的可用」。
M2 落地 L0~L2 时按需扩展。

---

## M1-D4 JSON Schema 只管形状，三类约束**故意**不放进去

`tc_case.schema.json` 里用 `x-validation-not-covered-here` 显式列出了它**不负责**的部分：

| 约束 | 为什么不放进 schema |
|---|---|
| `module_id` 外键有效性 | 放进去等于把字典复制一份到 schema，两处维护必然漂移。字典是唯一真源 |
| action ↔ locator 契约 | JSON Schema 要用 13 组 `if/then` 表达，可读性极差，且与 `Action` 枚举重复 |
| STD-001/002/003 定位器写法 | 需要解析 XPath 语法，超出 schema 的表达能力 |
| seq 连续性、STD-008 至少一个断言 | 跨数组元素的约束，schema 表达不了 |

**把边界写进 schema 文件本身**，是为了让后来者一眼看到「schema 通过 ≠ 案例合法」，
不会误以为跑完 `json-schema-validator` 就万事大吉。

---

## M1-D5 枚举字典由反射生成，不手写

`describe_schema` 返回的 `enums` 从 Java 枚举反射导出。
手写一份的话，改了枚举忘了改字典，调用方就会按字典生成一个服务其实不接受的值 ——
而这种不一致在测试里很难发现，因为两边各自都"自洽"。

对应测试直接断言 `enums.action` 的个数等于 `Action.values().length`。

---

## M1-D6 落实 M0-D4：`atp_echo` 的 annotations 一并修正

M0 留下的 `atp_echo` 吃了 MCP 默认值，对外自称 `destructiveHint=true`。
M1 顺手修正 —— 一个纯回显的自检接口谎称自己有破坏性，会让调用方 agent
无谓地要求用户确认。

现在三个 tool 全部显式声明，并由 `AtpSchemaToolsProtocolTest` 参数化断言守住。
M1 实测对照（同一次 `tools/list`）：

```
atp_describe_schema  readOnly=True  destructive=False idempotent=True  openWorld=False
atp_list_modules     readOnly=True  destructive=False idempotent=True  openWorld=False
atp_echo（修正前）    readOnly=False destructive=True  idempotent=False openWorld=True
```

---

## M2-D1 ⭐⭐ 缺口分析不只回答"缺什么"，还要回答"该找谁补"

**这是 M2 最重要的设计判断，也是「让模型少做事」被忽略的另一半。**

通常的理解是"能用规则做的别给模型"。但还有一条同样关键：
> **有些字段模型根本没有信息来源，让它补就是让它编。**

最典型的是 `locator_value`。模型**从未见过被测页面**。让它补一个 XPath，
它一定能写出语法正确、看起来很合理的表达式 —— 而那个表达式指向的元素可能根本不存在。
schema 校验会通过（是字符串），`atp_lint_locator` 也可能通过（用了 `data-testid`），
一路绿灯进库，直到执行器报"元素未找到"。**那时没人会想到根因是模型编了个选择器。**

所以引入 `GapFillability`：

| 值 | 含义 | 举例 | 处理 |
|---|---|---|---|
| `MODEL` | 答案已在案例里，只是需要理解 | `module_id` 语义匹配、`priority` 推断、`locator_type` 判断 | 交给 L3 |
| `REQUESTER` | 模型没有信息来源，补即编造 | `locator_value`、`expected`、`input_data`、`author` | **直接 REJECTED** |
| `PLATFORM` | 需要全局状态，本服务无状态 | `case_code` 的 4 位序号 | 标注后交给平台 |

`REQUESTER` 那一行**在 M3 接入模型之后依然是 REJECTED** —— 它不是"暂时没能力"，
而是"本来就不该由模型做"。

> 面试点：被问"缺字段你怎么补"时，"看情况，有些字段我绝不补"比"我用模型补"更有说服力。
> **找错人比不找更糟。**

---

## M2-D2 ⚠️ networknt json-schema-validator 3.x 的 API 完全重写了

M0-D2 只验证了它的 Jackson 版本线，没料到 3.x 连类名都换了一遍：

| 2.x / 旧教程 | 3.0.6 |
|---|---|
| `JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)` | `SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)` |
| `JsonSchema` | `Schema` |
| `ValidationMessage` | `Error` |
| `Set<ValidationMessage> validate(...)` | `List<Error> validate(...)` |

网上能搜到的示例几乎全是 2.x 写法，**一行都编译不过**。

**⚠️ 另一个坑**：`com.networknt.schema.Error` 会遮蔽 `java.lang.Error`。
`import` 进来虽然能编译，但读代码的人在 catch/throw 附近看到 `Error` 会瞬间误解。
本项目一律用全限定名 `com.networknt.schema.Error`。

---

## M2-D3 L4 的输入是 JSON，不是领域对象

`ValidationEngine.validate(JsonNode)` 而非 `validate(NormalizedCase)`。这保证了两件事：

1. **契约测试才有意义**。`atp_validate_case` 校验的东西，和 `normalize` 最终输出的东西，
   是**同一份字节**。若这边校验领域对象、那边输出序列化结果，两者之间就留了一道缝 ——
   而"normalize 说 ACCEPTED、validate 说 REJECTED"正是本服务承诺不会发生的事。
2. 平台方可以拿任意来源的 JSON 来校验，不必先构造我们的对象。

配套地，`NormalizedCase` / `TestStep` 用 `@JsonInclude(NON_NULL)` —— 这不只是让输出好看：
- `step_id` 恒为 null（平台生成），排除后才不会撞上 schema 的 `additionalProperties:false`
- 必填字段若为 null，字段直接消失 → schema 报 required 缺失，**正是我们想要的判定**

若改成输出 null，前者会变成"多了个不认识的字段"，后者会变成"类型不对"，两种诊断都指不到真问题上。

---

## M2-D4 两个「诊断本身静默失效」的 bug —— 都由测试抓到

本项目反复强调"把会静默失败的地方变成显式检查"。M2 撞上了这个主题的一个变种：
**诊断机制自己坏掉了，而服务照常返回结果。**

**bug 1：L0 的诊断在 L1 之后凭空消失。**
`RuleMapper` 只返回了自己产生的诊断，没带上 `ParsedEnvelope` 里的。
后果是"字段名拼错了""同一字段给了两遍"这类提示全部丢失 —— 没有任何报错，
请求方以为一切正常。被 `RuleMappingTest` 的两个用例抓到。

**bug 2：REJECTED 的响应里写着"输入足够完整"。**
`buildNote` 把「没调模型」等同于「输入完整」。但一条缺了 `locator_value` 的案例
同样是 `model_calls=0` —— 因为那个字段本来就不该由模型补（见 M2-D1）。
**自相矛盾的说明比没有说明更糟**，它会让请求方怀疑整个诊断的可信度。

顺带修了第三处：schema 的 required 缺失类错误，`instanceLocation` 指向父对象，
缺失的字段名只在 `getProperty()` 里 —— 只取前者会让一堆诊断的 path 全是 null，
agent 拿到"某处缺了必填字段"根本没法定位。现在两者拼接，输出 `case_code` / `author`。

---

## M2-D5 L1 的三条自我约束

1. **不猜。** 推断不出就留 null 交给 L2。规则猜错和模型猜错一样有害，而且更隐蔽 ——
   规则的错会稳定地重复出现。具体体现：`locator_type` 只认 `/`→XPATH、`#`/`.`→CSS 两条形状规则；
   `ID` / `NAME` / `LINK_TEXT` 的值都是普通字符串，没有任何特征能区分，一律不猜。
2. **不截断。** 超长字段原样保留并判 ERROR。截断能让 schema 通过，
   但那是把语义损坏藏起来 —— 一条被截断的断言文本会让用例静默地测错东西。
3. **纠正要出声。** 请求方给的 `wait_strategy` 与规范强制值冲突时纠正并 WARN。
   静默纠正等于让请求方永远学不会正确写法；而让规范可被覆盖，规范就变成了建议。

---

## M2-D6 信封解包用结构判据，不维护词表

剥 `{"testCase": {...}}` / `{"payload": {...}}` 这类外层包装时，判据是：
**顶层只有一个字段、其值是对象、且这个字段名不是我们认识的任何东西** → 那它只可能是包装层。

不用 `case/data/payload/案例/ケース/...` 的词表，因为词表永远列不全，
而这个结构特征对所有包装形式都成立（实测 `{"payload":{"wrapper":{...}}}` 两层也能剥）。

同理，别名字典**只收无歧义的别名**：`id` 不映射到 `case_code`（它同样可能指 `case_id`），
`name` 不作为 locator 提示（可能是步骤名称）。猜错一个字段名的后果是
把 A 字段的值填进 B 字段，**而两边都是字符串时校验还发现不了**。

---

## 待决 / 下一步

- **M3**：LLM 策略层 + L3 补全。
  - `atp_normalize_case` 的 `openWorldHint` 需从 `false` 改为 `true` —— 届时会真的出网
  - `model_calls` 要真实计数（目前恒为 0），Mock LLM 需支持计数以验证零模型路径
  - L4 判断错误源自"模型填的字段"才回 L3 重试一次；源自输入本身直接 REJECTED，不白烧钱
  - `LLM_API_KEY` 读不到时必须 fail-fast 并打印诊断，
  **不能拿空 key 去调 API** —— 否则报 401，错误指向「key 无效」而非「配置没读到」。
  同时把 `spring.config.import` 改成多候选路径（`./.env` 与 `../.env`），
  因为相对路径锚定进程 cwd，换个目录启动就会静默跳过。
- **M6**：起 2 副本验证 STATELESS —— `atp_echo` 已返回 `servedBy`（读 `HOSTNAME`，
  k8s 注入 pod 名），届时连续调用应能看到不同 pod 名，作为 STATELESS 生效的可视证据。
