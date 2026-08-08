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

## 待决 / 下一步

- **M1**：领域模型 + `AtpProfile`。tool annotations 按 M0-D4 显式声明。
- **M2**：加 `json-schema-validator` 显式依赖时按 M0-D2 的警告复核版本线。
- **M6**：起 2 副本验证 STATELESS —— `atp_echo` 已返回 `servedBy`（读 `HOSTNAME`，
  k8s 注入 pod 名），届时连续调用应能看到不同 pod 名，作为 STATELESS 生效的可视证据。
