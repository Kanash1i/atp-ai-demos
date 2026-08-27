# demo2 交接文档 — ATP 案例编写 MCP Server (Java 21 + Spring Boot)

> **给新 session 的第一条指令**：先完整读 `../00-SHARED-CONTEXT.md`，再读本文档。
> 工作目录：`/home/kanashi/Applications/interview-demos/demo2-atp-mcp/`
> 配置从仓库根目录 `../.env` 读取（由 `.env.example` 复制）。
>
> **⚠️ 2026-08-19 修订**：本文档已并入 `mcp-case-authoring-server` 的**确认与提交**设计
> （ChangeSet 不可变快照 / 三态状态机 / changeSetId 幂等提交）。
> 原方案止步于「输出规范化结果，交给平台方入库」，缺了**用户确认**和**入库幂等**这一段。
> 受影响的章节：§1 架构图、§5 接口、§6.2、§7、§8、§9、§11。
> Java 版本由 17 提升到 **21**（`03-HANDOFF-rag-v2.md` 已确认 Java 8/17 那条约束链消失）。

---

## 1. 问题定义

**场景**：某个 agent（可能是 ATP 平台自己的案例生成 agent，也可能是别的团队的工具）产出了一条测试案例，
但字段不符合 ATP 老平台的关系型 DB schema。这条案例需要经过**规范化**，
才能由平台方入库，并被执行器正确执行。

```
┌─────────────┐  raw case   ┌───────────────────────┐               ┌────────────┐   ┌──────────┐
│ 请求方 agent │ ──────────► │  MCP Server (k8s)     │ ── commit ──► │  平台方     │──►│ MySQL    │
│ (任意团队)   │ Streamable  │                       │  幂等键=      │  受控保存   │   └──────────┘
└─────────────┘    HTTP     │  prepare: L0~L5       │  changeSetId  │  API       │         │
       ▲                    │    ↓ 规范化+校验+补全   │               └────────────┘         ▼
       │                    │    ↓ 冻结成 ChangeSet  │                          ┌──────────────┐
       │  normalizedDraft   │  commit: 只收 Key      │                          │  执行器       │
       └── + changes ────── │                       │                          │ 对此完全无感知 │
           (等用户确认)      │  ⚠️ MCP 传输层无状态   │                          └──────────────┘
                            │  ✅ 业务状态在共享 DB   │
                            └───────────────────────┘
                                       │
                                       ▼
                               ┌────────────────┐
                               │ case_change_set │  不可变确认快照
                               │ (MySQL)         │  + 幂等映射
                               └────────────────┘
```

**两段式的理由**：用户确认的是 `prepare` 返回的那一份 JSON。
如果提交时让调用方**再传一次完整案例**，就存在这条路径：

```
用户确认内容 A  →  Agent 提交前把它改成了 B  →  平台保存了 B
```

所以 `prepare` 成功时把规范化结果**冻结成不可变快照**，`commit` 只接收 `changeSetId`，
服务端从库里重新读回同一份 JSON。**Agent 在用户点头之后没有任何修改内容的机会。**

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

| Tool | 调模型 | 幂等 | 有副作用 | 说明 |
|---|---|---|---|---|
| `atp_describe_schema` | ✗ | ✓ | ✗ | 返回目标 schema、枚举、必填规则、规范摘要 |
| `atp_prepare_case` | 可能 | ✗ ⚠️ | 写 ChangeSet | **主入口**：L0~L5 + 冻结确认快照 |
| `atp_commit_case` | ✗ | ✅ **强** | 写案例 | 只收 `changeSetId`，幂等创建 |
| `atp_validate_case` | ✗ | ✓ | ✗ | **只校验不修改**，纯规则，毫秒级 |
| `atp_lint_locator` | ✗ | ✓ | ✗ | XPath/CSS 静态规范检查 |
| `atp_list_modules` | ✗ | ✓ | ✗ | 模块字典（外键取值范围） |

> ⚠️ **`atp_prepare_case` 本身不幂等**，这是个已知缺口，不要在面试里说漏。
> 客户端超时重试会生成**多个 ChangeSet**（都合法、都能提交，但只有一个被用户确认过）。
> 修法：请求带客户端生成的 `draftRequestId`，对 `(draftRequestId, contentHash)` 加唯一约束。
> **被问到「你哪里还不够幂等」时，主动答这个** —— 承认一个具体缺口比宣称全链路幂等可信。

**`atp_describe_schema` 为什么必须有**：让调用方 agent 在**生成阶段**就知道该产出什么形状，
从源头降低错误率 —— 这是"左移"。只提供 `normalize` 等于放任上游乱生成再下游收拾。

**`atp_validate_case` 为什么必须独立暴露**：平台方入库前应**再调一次**做最终守门（信任但验证）。
纯规则、无副作用、完全幂等。把校验藏在 prepare 内部，平台方就没法自己把关。

**为什么 `commit` 只收一个 Key，不收案例内容**：见 §1 架构图下的那段。
这是整个设计里最容易被追问、也最容易答好的一处。

### 5.2 ⭐ 三态状态机 —— 不要把「缺信息」和「值非法」混成一个 REJECTED

`atp_prepare_case` 的返回状态：

| 状态 | 生成 changeSetId | 谁来处理 | 下一步 |
|---|---|---|---|
| `NEEDS_INPUT` | ✗ | **用户** | 返回 `questions`，Agent 转成自然语言去问，**MCP 绝不自己猜** |
| `NEEDS_REVISION` | ✗ | **Agent** | 返回 `violations`，Agent 按平台规则改字段，重新 prepare |
| `READY_FOR_CONFIRMATION` | ✅ | **用户** | 展示 `normalizedDraft` + `changes`，等明确确认 |

**为什么不能合并成一个 `REJECTED`**：这两类的下一步动作完全不同 ——
一个要去问人，一个 Agent 自己就能改。压成一个状态，调用方只能靠猜或靠读诊断文本。

> 这跟 `03-HANDOFF-rag-v2.md` §2.4 那条教训是同一个毛病的变体：
> **一个状态只能表示一件事**。demo1 的拒答标记当初写成「资料不足**或者**功能不支持」，
> 结果指标的分子里混了两类东西，答对了反而被算成失败。这里混的是「问人」和「自己改」。

`questions` 和 `violations` 同时存在时，**优先返回 `NEEDS_INPUT`**（人不补信息，Agent 改了也白改），
但 `violations` 仍要一并返回，让调用方一次看全。

校验不通过时的分流（原 §3 的 L4 出口，在这里落地）：

```
校验失败
├─ 错误在「模型填的字段」上   → 带错误回 L3 重试一次，仍失败 → NEEDS_REVISION
├─ 错误在「输入本身的值」上   → NEEDS_REVISION（重试没用，白烧钱）
└─ 不是错，是「压根没给」     → NEEDS_INPUT
```

### 5.3 ⭐ ChangeSet：`changes` 与 `provenance` 合并成一条记录

原方案的 `provenance`（§4）和 case-authoring 的 `changes` 是**同一个东西的两种切法**，
合并成一条记录，`prepare` 返回它、preview 展示它、审计留存它：

```json
{
  "path": "module_id",
  "oldValue": null,
  "newValue": "M003",
  "source": "MODEL",
  "confidence": 0.65,
  "reason": "标题含「カート」且步骤操作购物车页面"
}
```

`source` 取值仍是 §4 那四个：`INPUT` / `RULE`（附规则号如 `STD-005`）/ `DEFAULT` / `MODEL`。

**⭐ 合并之后 preview 才真正有价值。** 只有规则产生的 changes，条条可信，
用户 preview 基本是走过场；加上 provenance 之后，preview 界面该高亮的是

```
source == "MODEL" && confidence < 0.8
```

**用户不是来逐字读 JSON 的，是来看「AI 在哪几处拿不准」的。**
这一步把 preview 从仪式变成了实际的风控动作 —— 这是面试里讲 preview 唯一值得讲的角度。

ChangeSet 表（`case_change_set`，MySQL）：

| 字段 | 用途 |
|---|---|
| `change_set_id` | 主键，格式 `CHANGE-<UUID>` |
| `normalized_json` | 用户最终确认的完整 JSON（不可变） |
| `content_hash` | 规范化 JSON 的 SHA-256，检测快照漂移 |
| `changes_json` | 上面那份合并记录，审计用 |
| `schema_version` | 生成快照时的规范版本，规则升级后据此决定旧快照能否提交 |
| `status` | `PREPARED` / `SUPERSEDED` / `COMMITTED` |
| `expires_at_ms` | TTL 30 分钟 |
| `committed_case_id` | 首次成功创建的案例 ID |

**用户 preview 后要求修改**，走这条路（不是改旧快照）：

```
不改原 ChangeSet
  → Agent 按用户意见重构 draft
  → 重新 prepare，拿到新的 changeSetId
  → 把旧的置为 SUPERSEDED     ← ⚠️ 这条必须做
  → 用户确认新的，提交新的
```

> ⚠️ `SUPERSEDED` 在 case-authoring 原实现里是**缺的**（它列为 P1，旧 Key 在 30 分钟内仍可提交）。
> 但「用户 preview 后要求修改」是**主路径不是边缘情况**，这个洞必须补。
> 只靠 Skill 提示词约束「不要提交旧 Key」是不够的 —— 提示词不是数据安全边界。

### 5.4 幂等提交算法（`atp_commit_case`）

```
1. 校验 changeSetId 非空
2. 查 case_change_set
   ├─ 已有 committed_case_id  → 直接返回原 caseId, replayed=true
   ├─ status = SUPERSEDED     → 拒绝，提示已有更新版本
   ├─ 超过 TTL                → EXPIRED
3. 按 idempotency_key 查案例表
   └─ 已存在 → 比对 contentHash
        ├─ 相同 → 返回原 caseId, replayed=true（并修复 ChangeSet 状态）
        └─ 不同 → IDEMPOTENCY_CONFLICT，**不覆盖**，打高优先级告警
4. 不存在 → 插入
5. 并发触发唯一键冲突 → 捕获 DuplicateKeyException，读回胜出的记录返回
6. 更新 ChangeSet 为 COMMITTED
```

**第 5 步是关键**：唯一约束是并发安全的**最后防线**，
不能只靠「先查询再插入」—— 那之间有窗口。

```sql
CONSTRAINT uk_case_idempotency UNIQUE (idempotency_key)
```

**幂等必须下沉到最终写入边界**。只有 MCP 这一侧记一张表是不够的：

```
平台实际保存成功  →  MCP 没收到响应  →  MCP 重试  →  平台又创建一条重复案例
```

所以 MCP 调平台的受控保存 API 时，把 `changeSetId` 作为 `externalRequestId` 传下去，
由平台在自己的写入边界上加唯一约束。**平台不支持时，就在贴近写入边界处放一张幂等代理表。**

`caseId` 和 `case_code` 仍然由**平台方**生成 —— 平台是案例的最终事实来源（见 §11 问题 7）。

### 5.5 Resources

`atp://schema/tc_case` · `atp://modules` · `atp://standards/xpath`

### 5.6 多平台扩展

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

**⚠️ 这里要精确区分两个「状态」，不能混着讲**（合并 ChangeSet 之后原来那句「我们业务本来就无状态」不再成立）：

| | 说的是什么 | 我们的情况 |
|---|---|---|
| **MCP 传输层 stateless** | 每个 HTTP 请求自包含，不依赖某个进程里的 session | ✅ 是 —— 所以能横向扩容，不需要粘性会话 |
| **业务无状态** | 系统不持久化任何东西 | ❌ **不是** —— ChangeSet、幂等映射、最终案例都必须落库 |

**Stateless 指的是「没有进程内 MCP 会话」，不代表业务没有状态。**
可以横向扩容的是无会话的 MCP 进程；业务状态放在所有副本共享的数据库里。

> 这个论证比原来那句「我们业务本来就无状态所以毫无妥协」**更经得起追问**。
> 原来那版有个说不出口的弱点：面试官问
> **「用户确认之后 Agent 偷偷改了内容怎么办」**，它答不上来 ——
> 因为无状态就意味着没有那份被确认的快照可比对。
> 加了 ChangeSet 之后，这道题反而成了强项（§1、§5.3）。

**⛔ 因此 H2 不能进生产**（case-authoring 原实现用的是 H2 文件库，它自己把这条列为 P0）：
每个 Pod 的本地文件互不共享，Pod 重建可能丢数据，共享文件卷也不等价于支持多实例并发的数据库。
**用 MySQL**（ATP 平台本来就是 MySQL，见 `00-SHARED-CONTEXT.md` §1.1），靠唯一约束保证并发幂等。

### 6.3 版本矩阵 —— ⚠️ 开工第一件事是核实

搜索显示 **Spring AI 2.0.0 于 2026-06-12 GA**，引入 `@McpTool` / `@McpResource` 注解 API，
并**废弃 SSE 转向 Streamable HTTP**；MCP Java SDK 2.0.0 也已 GA（对应 2025-11-25 spec）。

> **2026-08-19 更新**：原文这里的顾虑是「Spring Boot 4.x 最低 JDK 可能高于 17」。
> 基线改为 **Java 21** 之后这个顾虑消失了 —— 但**仍然要实测版本矩阵再写代码**，
> 别把「顾虑消失」当成「已经验证过」。

决策顺序：
1. 先试 Spring Boot 4.x + Spring AI 2.0（`@McpTool` 注解 API 更干净）
2. 起不来就**退到 Spring Boot 3.x + Spring AI 1.1.x**（`spring-ai-starter-mcp-server-webmvc`，同样支持 STATELESS）
3. 把选择和理由记进 `DECISIONS.md`

> 参考点：`know-engine` 实测跑通的组合是 **Java 21 + Spring Boot 3.5.6**。
> 两个 demo 的 Spring Boot 大版本**不必一致**（独立进程、独立 boot jar），
> 但如果懒得排查，3.5.x 是有实测背书的那条路。

依赖（1.1.x 形态，2.0 需按实际调整）：
```
spring-boot-starter-web
spring-ai-starter-mcp-server-webmvc
com.networknt:json-schema-validator      ← L4 本地校验，核心
com.fasterxml.jackson.core:jackson-databind
spring-boot-starter-actuator             ← k8s 探针
spring-boot-starter-validation
spring-boot-starter-jdbc                 ← ⭐ ChangeSet 持久化（合并后新增）
com.mysql:mysql-connector-j              ← ⭐ 同上
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

这也印证了 §5.6 的设计价值 —— **核心逻辑与 transport 解耦**，换入口不动业务代码。

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

### 7.4 零模型路径 & 两种「幂等」

⚠️ **这里有两个不同的性质，测试要分开写，面试也要分开讲**：

| | 性质 | 怎么测 |
|---|---|---|
| `prepare` | **确定性**（同输入 → 同 `normalizedDraft` 和 `contentHash`） | 同输入跑 3 次，比对 hash |
| `commit` | **幂等**（同 `changeSetId` → 只创建一个案例） | 见 §7.7 |

- 完整案例 → 断言**模型调用次数 == 0**（Mock LLM 计数）
- `prepare` 同输入跑 3 次 → `contentHash` 一致
- ⚠️ 但 `prepare` **本身不幂等**：跑 3 次会产生 3 个 `changeSetId`。这是已知缺口（§5.1）

### 7.5 策略层对拍测试
同一批输入分别走 `JsonObjectStrategy` 和 `NativeSchemaStrategy`，
断言最终 normalized 结果一致。**证明"换 provider 零成本"不是空话。**

### 7.6 Mock LLM（必须有）
否则测试要花钱、要联网、不稳定，CI 跑不了。
定义 `LlmClient` 接口，生产实现打 HTTP，测试实现返回预设响应并计数。

### 7.7 ⭐ 确认与提交路径的测试（合并后新增，这块最容易出并发 bug）

- **10 个线程并发 commit 同一个 Key** → 只产生一个案例，其余全部 `replayed=true` 且 `caseId` 相同
- 案例插入成功但 ChangeSet 状态更新失败 → 重试后能自愈（读回已存在的案例并补标记）
- ChangeSet 过期 → `EXPIRED`
- 不存在的 `changeSetId` → `NOT_FOUND`
- 相同 Key、不同 `contentHash` → `IDEMPOTENCY_CONFLICT`，**断言不发生覆盖**
- 重新 prepare 之后，**旧 changeSetId 提交被拒**（`SUPERSEDED`）← 这条防的是 §5.3 那个洞
- 网络超时重放：commit 成功但响应丢失 → 重试返回同一个 `caseId`, `replayed=true`

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
    │   │   └── AtpCaseAuthoringTools
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
    │   ├── changeset/                   ← ⭐ 合并后新增
    │   │   ├── ChangeSetRepository       ← 接口，便于换存储
    │   │   ├── MySqlChangeSetRepository
    │   │   ├── LegacyCaseGateway         ← 接口：调平台受控保存 API
    │   │   └── DemoCaseGateway           ← demo 实现，直接写本地表
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
| M0 | **版本矩阵核实** + Hello World tool | Spring Boot 起得来，一个 echo tool 可被调用 | ⚠️ §6.3，先核实 Spring AI / Boot 与 JDK 21 的矩阵 |
| M1 | 领域模型 + AtpProfile schema/枚举/字典 | `describe_schema`、`list_modules` 可用 | |
| M2 | L0/L1/L2 纯规则链路 + `validate_case` + `lint_locator` | **零模型的规范化已可用** | ⭐ 此时已有演示价值 |
| M3 | LLM 策略层 + L3 补全 | `prepare_case` 的规范化内核完整可用 | ⭐ §2.3 |
| M4 | Provenance/changes 合并 + 分级诊断 + 降级 | 输出结构完整，LLM 挂掉能降级 | ⭐ §5.3 |
| **M5** | **ChangeSet + 三态 + 幂等 commit** | preview → 确认 → 入库闭环 | ⭐ **合并后新增** |
| M6 | 七类测试跑通 | 属性测试、策略对拍、**并发 commit** 是重点 | ⭐ |
| M7 | Dockerfile + k8s manifest + 2 副本验证 | 证明 STATELESS 有效（业务状态在 MySQL） | ⭐ 差异化 |
| M8 | GenericJUnitProfile + README | 可扩展性证明 | |

**M2 结束时做一次演示彩排** —— 纯规则版本已能讲清"让模型少做事"的主张。
**M5 结束时再彩排一次** —— 这时才有完整的「按时间顺序走一遍」的演示脚本（§12）。

---

## 10. 环境

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk env          # ⚠️ .sdkmanrc 需从 JDK 17 改为 21
```

配置从 `../.env` 读取。**本 demo 不需要服务机**（不用向量库、不用本地模型），
只需 `LLM_API_KEY` + 一个 MySQL（ChangeSet 与案例表，可用 docker compose 起本地实例），
可以完全独立于知识侧开发。

> ⚠️ 合并 ChangeSet 之后，本 demo **不再是零依赖**了 —— 多了一个数据库。
> 这是为「用户确认」付的代价，值得付（§6.2），但要在 README 里写清楚，
> 别让接手人以为 `mvn spring-boot:run` 就完事。

---

## 11. 面试预演问题

必答：
1. 为什么不直接把整条案例丢给模型让它输出规范格式？（**§2.2 铁律 2，最核心**）
2. 模型编了一个不存在的 module_id 怎么办？（外键校验 + provenance + REJECT）
3. 怎么保证执行器不会因规范化的案例炸？（§7.3 安全不变式）
4. 为什么不用 Structured Outputs？（**§2.3 —— 有实证的 provider 能力差异，答得好非常加分**）
5. MCP server 上 k8s 多副本，session 怎么办？（**§6.2 STATELESS，这是经典坑**）
6. 你的 MCP server 到底碰不碰数据库？
   （**这题合并后答案变了，要重新背**：碰 —— 但只碰**自己的** `case_change_set`。
   案例本身仍然由平台方的受控保存 API 写入，MCP 不复制平台的多表写入逻辑、不管它的事务和关联表。
   MCP 传 `changeSetId` 作为 `externalRequestId`，**幂等下沉到平台的写入边界**。§5.4）
7. case_code 需要全局唯一序号但你不碰库，怎么办？
   （返回模板 + `requires_platform_assignment` 标记，由平台方分配 —— 体现无状态边界的思考）
8. LLM 挂了整个服务就废了吗？（**§6.5 降级为纯规则规范化器**）
9. 换一个平台接入要改多少代码？（§5.6）

13. **用户确认之后，Agent 在提交前偷偷改了内容怎么办？**
    （⭐ **这是合并后最值得答的一题**：改不了 —— `commit` 只接收 `changeSetId`，
    内容是服务端从库里重新读回来的。用户确认的和最终入库的是**同一份字节**，`contentHash` 可验证。§1、§5.3）
14. 用户 preview 的时候到底该看什么？
    （不是逐字读 JSON，是看 `source=MODEL && confidence<0.8` 那几行 —— **AI 在哪儿拿不准**。§5.3）
15. 你说 STATELESS，那 ChangeSet 存哪？
    （MCP **传输层**无状态 ≠ 业务无状态。可横向扩容的是无会话的进程，状态在共享 MySQL。§6.2）
16. 提交超时了，客户端重试会不会创建两条案例？
    （不会。唯一约束是最后防线，并发冲突时捕获 `DuplicateKeyException` 读回胜出记录。§5.4）
17. 用户看完 preview 说「改一下第 3 步」，怎么走？
    （不改旧快照 —— 重新 prepare 出新 `changeSetId`，旧的置 `SUPERSEDED`。§5.3）

会踩的：
10. 你怎么知道模型补的 priority 是对的？
    （**诚实答**：不知道，所以才有 confidence 和 provenance —— 设计上承认不确定性，而不是假装确定）
11. 准确率多少？（用黄金用例集给数字，并说明 30 条的样本量限制）
12. 为什么不用 Spring AI 的 ChatClient？（§6.3 末尾 —— 需要精确控制 response_format 原始形状）
18. **你哪里还不够幂等？**
    （⭐ **主动答这个**：`prepare` 本身不幂等，客户端超时重试会生成多个 ChangeSet ——
    都合法、都能提交，但只有一个被用户确认过。修法是加客户端 `draftRequestId` 唯一约束。§5.1
    **承认一个具体缺口，比宣称全链路幂等可信得多。**）
19. 用户确认这件事，服务端有证据吗？
    （**诚实答**：当前没有 —— `commit` 只要收到有效 Key 就提交，"用户点过头"只由 Skill 提示词约束，
    而**提示词不是数据安全边界**。生产要么由可信前端调确认 API 记 `confirmedBy/confirmedAt`，
    要么给 ChangeSet 加 `PREPARED → CONFIRMED → COMMITTED` 三段状态 + 短期 `confirmationToken`。）

---

## 12. ⭐ 演示脚本 —— 按时间顺序走一遍

> 面试时**不要按模块讲**（"我有 6 个 tool，第一个是…"）—— 那是读目录。
> **跟着一条案例走完它的一生**，每一步只讲"这一刻发生了什么、为什么必须是这样"。
> 全程约 5 分钟。括号里是**这一步会被问什么**。

### T0 · 用户开口（0:00）

```
"帮 PROJECT-DEMO 搞一个安卓手机号登录成功的用例，
 先填手机号再点登录，最后看看首页出来没有。"
```

一句口语。里面没有 `module_id`、没有 `wait_strategy`、没有 `seq`、没有断言类型。
**这就是问题的全部** —— 老平台要的是关系型 schema 的 13 个必填字段，用户给的是一句话。

### T1 · Agent 先问规范，不是先生成（0:20）

```
→ atp_describe_schema(projectId, platform)
← schema、枚举、模块字典、默认值、标准示例
```

**为什么这一步不能省**：只提供 `normalize` 等于放任上游乱生成、下游收拾。
把标准前置给 Agent，是把错误率从源头压下去 —— **左移**。

> （问：为什么不直接让模型输出规范格式？→ §2.2 铁律 2。
> 让模型输出全文，它会**悄悄改动原本正确的字段**，而你无法察觉。）

### T2 · Agent 生成雏形，然后交给规则（1:00）

```
→ atp_prepare_case({ draft: { ...口语化、字段不全、动作名是中文... } })
```

进入 L0→L5。**这段的主张是「让模型少做事」**：

```
L0 输入规整   name|caseName|title|标题|タイトル → title
L1 确定性映射 点击|click|tap|クリック → CLICK
              CLICK → wait_strategy=CLICKABLE   (STD-005)
              // 开头 → locator_type=XPATH
              seq 重排 1..n
L2 缺口分析   ⭐ 算出还缺什么 → 清单为空就直接跳到 L4，零模型调用
L3 模型补全   只把「填空题」给模型，不给全文
L4 校验       JSON Schema + 外键 + Action 契约 + STD-001~008，永不跳过
```

> （问：模型编了个不存在的 `module_id` 怎么办？
> → L4 拿模块字典做外键校验，编的 ID 过不去，直接 `NEEDS_REVISION`。）
>
> （**加分**：请求方给的案例足够完整时，**整条链路的模型调用次数是 0**。
> 多数人的"AI 服务"是无脑每次都调模型。测试里有断言，README 里有实测比例。）

### T3 · 三态分流（1:40）

这一刻服务要回答的不是"对不对"，而是**"接下来该谁动手"**：

```
NEEDS_INPUT     缺业务语义（比如：成功的标准到底是什么？）→ 去问用户，MCP 绝不自己猜
NEEDS_REVISION  值非法（priority 写了 P9）              → Agent 自己改，重新 prepare
READY_FOR_...   通过                                    → 冻结快照，等用户点头
```

> （问：为什么不合并成一个 `REJECTED`？
> → 因为这两类的**下一步动作完全不同**，一个要去问人，一个 Agent 自己就能改。
> 压成一个状态，调用方只能靠猜。
> 我在知识侧犯过同一个错误的变体：拒答标记当初写成"资料不足**或者**功能不支持"，
> 结果指标分子里混了两类东西，**答对了反而被算成失败**。
> 教训是：**一个状态只能表示一件事。**）

### T4 · 冻结快照（2:20）

```
← READY_FOR_CONFIRMATION
  changeSetId: CHANGE-a3f8...
  normalizedDraft: { ...完整合规的 13 个字段... }
  changes: [ ...每一处自动修改... ]
  contentHash: sha256(...)
  expiresAt: +30min
```

`normalizedDraft` 被**原样写进 `case_change_set`，不可变**。

> （问：你不是说 STATELESS 吗，这状态哪来的？
> → **MCP 传输层无状态 ≠ 业务无状态**。可横向扩容的是无会话的进程，
> 状态在所有副本共享的 MySQL 里。k8s 多副本下 session 亲和性是经典坑，我们从设计上不需要它。）

### T5 · 用户 preview —— 这一步的价值全在高亮上（2:50）

把 `changes` 展示给用户。**但用户不是来逐字读 JSON 的。**

```
  seq            1,2,3      ← RULE     重排
  wait_strategy  CLICKABLE  ← RULE     STD-005
  browser        CHROME     ← DEFAULT
  status         DRAFT      ← DEFAULT
▶ module_id      M003       ← MODEL  confidence 0.65   ⚠️
    "标题含「カート」且步骤操作购物车页面"
```

**只有最后一行需要人看。** 前面四行是明文规则推出来的，可审计、可信任。

> ⭐ 这是整个 preview 设计的落点：
> `source=MODEL && confidence<0.8` 才是**人类判断力真正被需要的地方**。
> 没有 provenance，用户只能全信或全不信，preview 就是走过场。
>
> （问：你怎么知道模型补的 priority 是对的？
> → **诚实答**：不知道。所以才有 `confidence` 和 `provenance` ——
> 设计上**承认不确定性**，而不是假装确定。）

### T6 · 用户说"第 3 步改一下"（3:20）

**不改旧快照。**

```
Agent 按意见重构 draft → 重新 prepare → 新的 changeSetId
                                     → 旧的置 SUPERSEDED
```

> （问：为什么不直接改？→ 因为快照的全部意义就是"用户确认的那一份"。
> 一旦可改，它就不再是证据了。
>
> 顺带说一个我补的洞：参考实现里旧 Key **30 分钟内仍然可提交**，
> 只靠 Skill 提示词约束"不要提交旧 Key"。**提示词不是数据安全边界** ——
> 换个模型、换个客户端就不遵守了。所以我加了 `SUPERSEDED` 状态，在服务端拒。）

### T7 · 用户确认，提交（4:00）

```
→ atp_commit_case({ changeSetId: "CHANGE-a3f8..." })      ← 只有这一个参数
```

**没有第二个参数。** 案例内容是服务端从库里重新读回来的。

> （⭐ **问：用户确认之后 Agent 偷偷改了内容怎么办？**
> → 改不了。它手里只有一个 Key。用户确认的和最终入库的是**同一份字节**，`contentHash` 可验证。
> 这是整个两段式设计存在的唯一理由。）

### T8 · 幂等落地（4:30）

```
← COMMITTED, caseId: CASE-77b1..., replayed: false
```

网络超时重试一次：

```
← COMMITTED, caseId: CASE-77b1..., replayed: true      ← 同一个 caseId
```

> （问：并发提交呢？
> → 唯一约束是**最后防线**，不能只靠"先查询再插入"——那之间有窗口。
> 并发冲突时捕获 `DuplicateKeyException`，读回胜出的记录返回同一个 `caseId`。
> 10 线程并发的测试在 §7.7。）
>
> （问：MCP 记了幂等，平台不认怎么办？
> → 那还是会重复创建。**幂等必须下沉到最终写入边界** ——
> `changeSetId` 作为 `externalRequestId` 传给平台，由平台在自己的写入边界加唯一约束。
> 平台改不了的话，就在贴近写入边界处放一张幂等代理表。）

### T9 · 案例入库为 DRAFT，执行器无感知（5:00）

**AI 生成的内容一律先落 `DRAFT`**，不直接进执行或发布流程。

而"执行器无感知"这句话的**形式化表述**，就是那条安全不变式：

> **要么 `ACCEPTED` 且完全通过校验，要么被拒并带诊断。
> 永远不存在「已接受但违反 schema」的输出。**

这条由属性测试保证（§7.3）—— 随机生成畸形输入，断言它永远成立。

> **面试能说出这一句，比讲十个功能点管用。**

---

### 12.1 讲这条线时的三个节奏点

| 时刻 | 一句话 | 为什么放在这 |
|---|---|---|
| T2 | 「请求方案例够完整时，模型调用次数是 0」 | 打破"AI 服务=每次调模型"的默认想象 |
| T5 | 「只有 confidence<0.8 那一行需要人看」 | preview 从仪式变成风控，这是设计而非功能 |
| T7 | 「它手里只有一个 Key，改不了」 | 一句话回答"AI 不可控怎么办"这个终极追问 |

### 12.2 被打断时的退路

面试官常在 T2 打断问细节。**答完立刻回到时间线**：
「这是 L1 的部分，我先把这条案例走完，细节我们回头展开。」
**时间线是你的主场，细节是他的主场** —— 别在他的主场待太久。
