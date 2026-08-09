# ATP 案例规范化 MCP Server

把任意形状的测试案例 JSON，规范化成 ATP 平台关系型 schema 能直接入库的形态，
并给出**分级诊断**与**每个字段的来源**。

以 Streamable HTTP 暴露 MCP 协议，部署在 k8s 上 —— 别的团队填一个 URL 就能接入。

---

## ⚠️ 关于本项目

这是一个**面试用的学习 demo**。平台手册、公司规范、DB schema、模块字典、存量案例
**全部为虚构**，与任何真实企业无关。虚构平台 `ATP` 是通用 Web UI 自动化测试平台的泛化描述，
不模仿任何具体商业产品。

真实实现涉及前雇主的公司资产，因法律与职业道德原因不能带出，故用虚构语料重做了这个复现 demo。

---

## 它解决什么问题

某个 agent（可能是平台自己的案例生成 agent，也可能是别的团队的工具）产出了一条测试案例，
但字段不符合 ATP 老平台的关系型 DB schema。这条案例需要经过规范化才能入库，
并被执行器正确执行。

```
┌─────────────┐   raw case    ┌──────────────────┐  normalized   ┌────────────┐   ┌──────────┐
│ 请求方 agent │ ────────────► │  本服务 (k8s)     │ ────────────► │  平台方     │──►│ MySQL    │
│ (任意团队)   │  Streamable   │                  │  + 诊断信息    │  (入库)     │   └──────────┘
└─────────────┘     HTTP      │  ⚠️ 不碰数据库    │               └────────────┘         │
                              │  ⚠️ 完全无状态    │                                      ▼
                              └──────────────────┘                              ┌──────────────┐
                                                                                │  执行器       │
                                                                                │ 对此完全无感知 │
                                                                                └──────────────┘
```

**"执行器无感知"翻译成工程语言**：输出必须 100% 满足 schema 的全部约束 ——
类型、枚举、长度、必填、外键，以及 action 与 locator 的契约。
只要有一条不满足，执行器就会在运行时炸，而那时已经离故障源很远了。

---

## 为什么是一个服务，而不是一段 skill / prompt

这是本项目最该被追问的立项问题：**同样的规范化，请求方用 agent + skill 也能做，
为什么要单独起一个服务？**

### 出发点：挡板是刚需，不是可选项

先确立一个双方都同意的前提：**无论哪种方案，案例入库前都必须有一道校验挡板。**
平台不能收脏数据 —— 执行器读到不合规的案例会在运行时炸，而那时排查成本是当场拒绝的几十倍。

既然挡板无论如何都要写，问题就不再是"要不要这个服务"，而变成了：

> **挡板放在哪、由谁写、写几份、和生产者是什么关系。**

下面四条都是从这个问题推出来的。

### ① 挡板必须和生产者同源，否则两份实现必然漂移

skill 方案下，挡板其实存在**两份实现**：平台方入库前那一道，和 skill 里描述规则的那段 prompt。
两份东西描述同一个规范，随着规范演进必然漂移 —— 而漂移的表现是
"skill 说合规、挡板说不合规"，且没有任何一方是明确的权威。

本服务的做法是让它们**物理上是同一段代码**：
`atp_validate_case` 与 `atp_normalize_case` 内部的 L4 校验共用同一个实现。
并有契约测试守住这条：

> **normalize 输出的每条 `ACCEPTED` 结果，必须能通过 `validate_case`。**

所以平台方那道挡板不是"被替代了"，而是**被交付了一份和生产者同源的实现**。
这也是为什么 `atp_validate_case` 必须作为独立 tool 暴露 ——
把校验藏在 normalize 内部，平台方就只能自己再写一份，漂移又回来了。

### ② 挡板只能拒绝，不能修复 —— 所以"防 agent 随机性"防不胜防

挡板有两个结构性弱点：

**一是它只能检查它想到要检查的东西。** agent 的错误形态是开放集合，
写挡板时想不到的那些，就是会漏进去的那些。于是只能二选一：
写得极严（误杀正常案例）或者留缝（脏数据进库）。

**二是拒绝之后没有收敛机制。** 挡板说"不行"，agent 重新生成 —— 那**又是一次随机采样**。
可能第三次才过，也可能这条过了下条又不行。没有任何机制在推动它收敛。

本服务改变的不是"检查得更严"，而是**让大部分字段根本不经过随机性**：

| 字段 | 谁来决定 |
|---|---|
| `wait_strategy` | 规则按 action 确定性填充（STD-005/006） |
| `locator_type` | 按 locator_value 的形状推断 |
| `status` / `browser` / `timeout_sec` | schema 默认值 |
| `seq` | 规则重排为 1..n 连续 |
| `module_id` / `priority` | **只有这类需要语义判断的才交给模型**，且带 confidence |

一句话：

> **不给模型犯错的机会，比检查模型有没有犯错，便宜一个数量级。**

### ③ 责任在数据里，不在会议室里

skill 方案下出了问题无法判定责任：是 skill 写得不对、agent 没照做、
还是使用方私自改过 skill？三方都拿不出证据。

本服务为每个字段附带 **provenance**：

| source | 含义 | 出问题时的归属 |
|---|---|---|
| `INPUT` | 请求方原样提供 | 请求方的数据 |
| `RULE` | 规则推导（附规则编号如 `STD-005`） | 我们的规则；规则是明文的，可复查可审计 |
| `DEFAULT` | schema 默认值 | 契约本身 |
| `MODEL` | 模型推断（附 `confidence` + `reason`） | 模型；平台方可自行设定阈值策略 |

平台方据此可以定出这样的策略：
*"`MODEL` 来源且 `confidence < 0.8` 的字段，案例入库为 `DRAFT` 并进人工复核队列"*。

这已经不是"少扯皮"，而是**根本不需要扯** —— 判据在数据里。

### ④ 不是所有调用方都是 agent

这条容易被忽略。需要这套校验能力的还有：

- **平台方入库前的最终守门**（信任但验证）—— 执行者是后端代码
- **CI 流水线**、批量回填脚本、数据订正任务

它们都不跑模型。而 `atp_validate_case` 本身是**纯规则、毫秒级、完全幂等**的 ——
把一个确定性函数塞进概率性执行器里跑，本身就是设计错误。

skill 只能被 agent 消费；本服务在 MCP 协议之下就是个普通 HTTP 服务，谁都能调。

### 其余几条

- **零模型路径**：请求方给的案例足够完整时，整条链路的**模型调用次数是 0** ——
  纯规则跑完直接输出，毫秒级、零成本、完全确定。skill 方案下每次都要模型
  把 schema + 规范 + 字典读一遍再逐字段推理，且结果不保证可复现（幂等性也没了）。
- **升级与漂移**：skill 是复制品，每个团队 fork 一份改一点就分叉了，
  而**这种漂移是不可见的** —— 某团队删掉一段自认为用不上的规则，
  产出的案例从此悄悄不合规，你不会收到任何信号。服务是引用不是复制，改一次上线全体生效。
- **依赖隔离**：本服务需要 JDK 17 + JSON Schema 校验库，关在自己的容器里。
  skill 带脚本的话，每个使用方的机器上都得有这套运行时。
- **基础设施复用**：限流、鉴权、灰度、监控、调用量统计（比如"哪个团队的案例合规率最低"）
  在服务端是现成的，skill 侧全是空白。

### 边界：什么时候 skill 确实更合适

不掩饰这一点。skill 更适合：

- 逻辑是**指导性**的而非判定性的（"怎么写出好案例" vs "这条案例合不合规"）
- 调用方确定就是 agent
- 需要高频迭代措辞，不想走发版流程

### 结论：这不是二选一

**最佳组合是：skill 教 agent 怎么用这个服务。**

- skill 负责：什么时候该先调 `describe_schema`、拿到 `REJECTED` 该怎么改、
  `confidence` 低时要不要提请人工确认
- 本服务负责：**什么是对的**

而且两者存在**不对称性** —— 服务可以内嵌 skill 的功能，反过来不行。
MCP 协议本身有 `instructions` 字段，tool 有 `description`，
`atp_describe_schema` 的返回里还带了 `guidance` 数组和完整的 `action_contracts`。
这些本质就是 skill 的内容，区别在于：

> **它们由服务端下发，永远和服务端逻辑同一个版本 —— 不会漂移。**

---

## 核心设计原则：让模型少做事

| # | 原则 | 具体做法 |
|---|---|---|
| 1 | **确定性优先** | 能用规则映射的字段绝不给模型。模型是最后手段，不是第一手段 |
| 2 | **模型只填空，不重写** | 不把整条案例丢给模型让它"输出规范格式" —— 它会悄悄改动原本正确的字段，而你无法察觉 |
| 3 | **不依赖 provider 的结构化输出** | 用最低公分母 `json_object` + 本地 JSON Schema 校验 + 带错误重试 |
| 4 | **一切外键对照字典校验** | 模型填的 `module_id` 必须在字典中真实存在，否则直接拒绝 |
| 5 | **失败不静默** | 校验不过 → `REJECTED` + 结构化诊断。**宁可拒绝，不可错入** |

### 安全不变式

整个服务要证明的就是这一条：

> **要么 `ACCEPTED` 且完全通过校验，要么 `REJECTED` 且带诊断。
> 永远不存在「`ACCEPTED` 但违反 schema」的输出。**

这是"执行器对规范化过程完全无感知"的形式化表述，用属性测试（随机畸形输入）来证明。
**这也是 skill 方案给不出的保证 —— prompt 没法被属性测试。**

### 关于第 3 条：为什么不依赖 Structured Outputs

开发期用 DeepSeek、后期切 Kimi K3，两者的结构化输出能力**不对等**：
DeepSeek 只有 `response_format: json_object`（保证是合法 JSON，**不保证符合你的 schema**），
`json_schema` + `strict` 不支持，function calling 的 strict 模式为 beta 且有已知缺陷。

所以架构上引入策略层（`JsonObjectStrategy` / `NativeSchemaStrategy`），
用最低公分母打底，provider 支持 strict 时作为**额外保险**开启，
但**本地 JSON Schema 校验永不跳过**。副作用是换 provider 零成本。

---

## 部署形态：Streamable HTTP + k8s

### 为什么不是 stdio

stdio 要求每个使用方在自己机器上拉起你的进程 —— 等于给每个团队发 SDK，
版本分裂、升级困难、依赖冲突。跑在 k8s 上以 HTTP 暴露，则别的团队填一个 URL 就能接入，
修 bug 上线一次全体生效，监控/限流/鉴权/灰度都复用现有基础设施。

### ⭐ 必须用 STATELESS 模式

MCP 的 Streamable HTTP **默认是 `STREAMABLE` 模式，会维持 session**。
k8s 多副本下请求被负载均衡打到不同 pod，第二个请求找不到 session 就直接失败。

这个坑的危险之处在于：**它在单副本本地开发时 100% 不会暴露**，
只有上了多副本才炸，而报错（`Session ID missing`）离根因（一行 yaml 配置）已经很远。

本项目起两个实例做了 A/B 对照实测：

| 模式 | 不带 session 的 `tools/list` | `initialize` 是否下发 `Mcp-Session-Id` |
|---|---|---|
| `STREAMABLE`（默认） | ❌ `{"code":-32601,"message":"Session ID missing"}` | 下发 |
| `STATELESS` | ✅ 正常返回 | **不下发** |

并用 `McpStatelessProtocolTest` 把这个行为锁死 —— 配置被改坏时测试立刻红，而不是等上线。

> 我们的业务设计本来就是无状态的（每次 normalize 独立、不碰 DB），
> 所以这里完全没有妥协：架构与部署形态天然契合。

---

## 当前进度

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M0 | 版本矩阵核实 + Streamable HTTP 打通 | ✅ 完成 |
| M1 | 领域模型 + AtpProfile + `describe_schema` / `list_modules` | ✅ 完成 |
| M2 | L0/L1/L2/L4 纯规则链路 + `normalize_case` / `validate_case` / `lint_locator` | ✅ 完成 |
| M3 | LLM 策略层 + L3 字段补全 | ⬜ 下一步 |
| M4 | Provenance + 分级诊断 + LLM 故障降级 | ⬜ |
| M5 | 六类测试（属性测试与策略对拍是重点） | ⬜ |
| M6 | Dockerfile + k8s manifest + 2 副本验证 | ⬜ |
| M7 | `GenericJUnitProfile`（可扩展性证明） | ⬜ |

**上文描述的设计中，尚未落地的部分**：L3 模型补全与策略层、LLM 故障降级、
属性测试、k8s 部署清单、`GenericJUnitProfile`。它们的设计已确定
（见 `../02-HANDOFF-demo2-mcp.md`），但代码要到对应里程碑才存在 ——
本 README 不把计划写成既成事实。

当前 `atp_normalize_case` 是**纯规则版本**：输入完整时走完全程（这是终态，不是过渡），
存在需要推断的字段时返回 `REJECTED` 并标注 `GAP_COMPLETION_UNAVAILABLE`，M3 会把这条路接上。

---

## 目前可用的接口

服务以 Streamable HTTP 暴露在 `/mcp`。**STATELESS 模式下无需 `initialize`，
每个请求自包含。**

| Tool | 调模型 | 幂等 | 说明 |
|---|---|---|---|
| `atp_normalize_case` | 尚未 | ✓ | **主入口**：任意形状 → 规范形态 + 诊断 + provenance + gaps |
| `atp_validate_case` | ✗ | ✓ | 只校验不修改，供平台方入库前守门 |
| `atp_lint_locator` | ✗ | ✓ | 单个定位器的规范检查，写案例时可随时调 |
| `atp_describe_schema` | ✗ | ✓ | 目标 schema、枚举字典、**每个 action 的字段契约**、规范摘要 |
| `atp_list_modules` | ✗ | ✓ | `module_id` 全集（外键取值范围） |
| `atp_echo` | ✗ | ✓ | 连通性自检，返回处理该请求的实例标识 |

### 实测：一次完整的规范化

输入是一条**纯日文、字段名和动作名都不符合 schema** 的案例：

```json
{"テストケース":{"タイトル":"カートに商品を追加できる","モジュール":"CART",
  "優先度":"P1","担当者":"yamada","手順":[
    {"操作":"打开","入力値":"https://example.test/cart"},
    {"操作":"クリック","xpath":"//*[@data-testid='add-to-cart']"},
    {"操作":"断言文本","css":"[data-testid='cart-count']","期待値":"1"}]}}
```

输出（节选）：

```
status = ACCEPTED     model_calls = 0     zero_model_path = true

case_code = ATP-CART-0000  module_id = M003  priority = P1
  seq=1 OPEN_URL     wait=NONE       -      https://example.test/cart
  seq=2 CLICK        wait=CLICKABLE  XPATH  //*[@data-testid='add-to-cart']
  seq=3 ASSERT_TEXT  wait=VISIBLE    CSS    [data-testid='cart-count']

provenance: title→INPUT   module_id→RULE   browser→DEFAULT
            case_code→RULE(STD-007)   steps[1].wait_strategy→RULE(STD-005)
requires_platform_assignment: ["case_code"]
```

外层信封被剥掉、三语字段名与动作名归一、`module_code` 查表换成 `module_id`、
`wait_strategy` 按 action 强制填充、`locator_type` 由字段名判定、`seq` 重排 ——
**整个过程 0 次模型调用**，毫秒级，结果完全可复现。

在当前 15 条黄金用例中，**13 条无需任何模型参与**（数字由 `GoldenCasesTest` 实测打印，
随用例集演进）。

> **调用方应当先调 `atp_describe_schema`。** 它把 action 契约
> （CLICK 必须带 locator、ASSERT_TEXT 必须带 expected……）直接给出来，
> 让 agent 在**生成阶段**就对齐，而不是先乱生成一版再由 normalize 收拾。
> 这是"左移" —— 只提供 normalize 等于放任上游乱生成、下游收拾。

### 本地运行

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk env   # JDK 17
mvn spring-boot:run
```

配置从仓库根目录 `../.env` 读取（由 `../.env.example` 复制）。
代码中不含任何硬编码的 key / URL / IP。

### 调用示例

```bash
curl -s -X POST http://localhost:8090/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call",
       "params":{"name":"atp_list_modules","arguments":{}}}'
```

---

## 相关文档

| 文档 | 内容 |
|---|---|
| [`DECISIONS.md`](DECISIONS.md) | 实现期的技术取舍与踩坑记录（版本矩阵、Jackson 3 迁移、规范冲突处理等） |
| [`../00-SHARED-CONTEXT.md`](../00-SHARED-CONTEXT.md) | 虚构世界观、共享领域模型、Action 枚举 |
| [`../02-HANDOFF-demo2-mcp.md`](../02-HANDOFF-demo2-mcp.md) | 完整架构设计与里程碑规划 |
