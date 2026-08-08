# demo2 — ATP 案例规范化 MCP Server (Java 17 + Spring Boot)

## 开工前必读

1. `../00-SHARED-CONTEXT.md` — 虚构世界观、共享领域模型（tc_case / tc_step / Action 枚举）、LLM provider 差异
2. `../02-HANDOFF-demo2-mcp.md` — 本 demo 的完整架构与里程碑

这两份文档是自包含的，包含全部设计决策，不需要向用户重新确认基础信息。

## 红线（不可协商）

- 这是**面试用的学习 demo**。DB schema、模块字典、规范条目全部虚构，**不使用任何真实公司资产**。
- 不要向用户索取任何来自其前雇主的 schema、案例或规范文档 —— 用户已明确因法律与道德原因不能提供。

## 配置

**所有配置读仓库根目录 `../.env`**（由 `../.env.example` 复制而来），
包括 `LLM_API_KEY` / `LLM_BASE_URL` / `LLM_MODEL` / `LLM_STRUCTURED_MODE`。
**代码里不得出现硬编码的 key、URL、IP。**

开发期用 DeepSeek（便宜），后期切 Kimi K3。切换只改 `.env`，不改代码。

## 硬约束

- **JDK 17**：`source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk env`
- **Spring Boot + Streamable HTTP**，部署到 k8s。理由：别的团队填个 URL 就能接入，不需要分发 SDK。
- **`protocol: STATELESS` 必须开**。默认的 STREAMABLE 模式维持 session，
  k8s 多副本下请求打到不同 pod 会直接失败 —— 这是 MCP 上 k8s 最经典的坑。
- ⚠️ **M0 先核实版本矩阵**：Spring AI 2.0 常配 Spring Boot 4.x，而 Spring Boot 4 的最低 JDK 可能高于 17。
  若不兼容就退到 Spring Boot 3.x + Spring AI 1.1.x，并把理由记进 `DECISIONS.md`。

## 本 demo 的核心主张

**让模型少做事。**

- 能用规则做的绝不给模型
- 模型**只填空，不重写全文**（丢整条案例让模型"输出规范格式"是错的 —— 它会悄悄改动原本正确的字段）
- 一切外键对照字典校验，防模型编造
- 校验不过就 `REJECTED` + 结构化诊断，**绝不猜一个值蒙混过去**

### ⭐ 不依赖 provider 的结构化输出

DeepSeek **不支持** `response_format: json_schema`（只有 `json_object`），
其 function calling strict 是 beta 且有已知 bug；Kimi K3 才支持 strict schema。

**所以：用最低公分母 `json_object` + 本地 JSON Schema 校验 + 带错误重试。**
provider 支持 strict 时作为额外保险开启，**但本地校验永不跳过**。

安全不变式（用属性测试证明）：
> 要么 ACCEPTED 且完全通过校验，要么 REJECTED 且带诊断。
> **永不存在「ACCEPTED 但违反 schema」的输出。**

## 开工第一步

先核实版本矩阵 + 打通一个 Hello World tool（Streamable HTTP 能被调用），
再写业务逻辑。**先打通协议和部署形态，别一上来写 pipeline。**
