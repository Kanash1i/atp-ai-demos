# `atp` CLI 对外契约

> **这份是冻结契约。** 消费方目前有两个：
> - opencode（保守路线，人在环里确认）
> - `atp-platform` 的 agent 模块（激进路线，直接 exec）
>
> ⚠️ **改这里的任何内容之前，先同步两侧。** 命令名、flag 名、`data` 字段名、
> 退出码取值、信封形状 —— 都算契约面。
>
> 契约由测试锁定，不靠这份文档自觉：
> `internal/cli/e2e_test.go` 里 `TestContract_*` 与 `TestExitCodeContract`。

---

## 1. 命令

| 命令 | 打库 | 幂等 | 说明 |
|---|---|---|---|
| `atp schema` | ✗ | ✓ | 输出草稿的 JSON Schema。**不套信封**（本身就是 JSON，方便 `> schema.json`）|
| `atp modules [-p CODE]` | ✓ 读 | ✓ | 项目与模块字典 —— `module_id` 的合法取值范围 |
| `atp validate -f FILE` | ✗ | ✓ | 纯本地校验，零网络零模型 |
| `atp draft --id UUID -p PLATFORM -t TITLE [--by WHO]` | ✓ 写 | ✅ | 建编写态草稿 |
| `atp show CASE_ID` | ✓ 读 | ✓ | 读回草稿与 version |
| `atp update CASE_ID --version N -f FILE` | ✓ 写 | CAS | 写内容（先本地校验）|
| `atp preview CASE_ID` | ✓ 读 | ✓ | 人读渲染；`--json` 时只出信封 |
| `atp commit CASE_ID --version N` | ✓ 写 | ✅ | 落地为平台原生 `DRAFT` |

`PLATFORM` ∈ `IOS` / `ANDROID` / `PC_WEB`。

> ⭐ **`--id` 必须由调用方生成，且重试时复用同一个** —— 这是整套幂等的唯一来源。
> 不给的话 CLI 会本地生成一个，但那样重试就不幂等了（会产生两条各自合法的草稿）。

---

## 2. `--json` 信封

```json
{
  "ok": false,
  "code": "VALIDATION_FAILED",
  "replayed": false,
  "data": null,
  "violations": [],
  "questions": [],
  "message": "…"        // 仅失败时出现
}
```

- `ok` 与 `code` 是**退出码的冗余**，三者必须一致。**分派请以退出码为准。**
- `violations` / `questions` **永远存在**（可能为空数组），不会是 `null`。
- `message` 只在失败时出现（`omitempty`）。

### ⭐ 带 `--json` 时，任何失败都是信封 —— 包括参数错误

```
$ atp draft --json -t "没给 platform"
{ "ok": false, "code": "VALIDATION_FAILED", …, "message": "required flag(s) \"platform\" not set" }
退出码 12
```

**唯一的例外**：如果 `--json` 自己都没被解析到（比如它排在一个非法 flag 后面），
会退回纯文本 `[VALIDATION_FAILED] unknown flag: --bogus`。
这个边界消不掉 —— 要输出信封，总得先知道调用方要不要信封。
**把 `--json` 放在最前面就不会遇到。**

不带 `--json` 时一律纯文本，人读的通道不被信封污染。

---

## 3. `data` 字段（成功时）

| 字段 | 类型 | 说明 |
|---|---|---|
| `caseId` | string | 案例主键 |
| `caseType` | string | `IOS` / `ANDROID` / `PC_WEB` |
| `status` | string | **`tc_step` 的状态** —— 编辑期状态机 |
| `version` | int | **`tc_step` 的版本** —— ⭐ **commit 要带回来的就是它** |
| `platformStatus` | string | `tc_case` 的状态（平台侧生命周期）|
| `draft` | **object 或 array** | 见下 |

### ⚠️ `data.draft` 的**类型**会随状态变，这是最容易踩的一处

```
编辑期（status = AI_DRAFT）   object   {"case_code":…,"title":…,"steps":[…]}
落地后（status = DRAFT）      array    [{"seq":1,…},{"seq":2,…}]
```

不是字段名变了，是**同一个字段的 JSON 类型变了**。

原因：编辑期表头需要有地方暂存，就放在 `step_json` 里；
commit 时表头投影进 `tc_case` 的正式列，`step_json` 规整成**纯步骤数组** ——
因为老执行器读的是数组，格式必须与人工案例完全一致。

**调用方要么按 `status` 分支，要么只在编辑期读 `draft`。**

### 往返用法

```bash
atp show <id> --json | jq .data.draft > draft.json   # 编辑期
# 改 draft.json
atp update <id> --version <data.version> -f draft.json --json
```

---

## 4. 退出码 —— 这是分派契约

**权威定义在 `internal/model/exitcode.go`**，由 `TestExitCodeContract` 锁定取值。

| 码 | 常量 | 调用方该做什么 |
|---|---|---|
| **0** | `OK` | 继续。**含幂等重放**（看 `replayed` 区分，但两者都是成功）|
| 10 | `VERSION_CONFLICT` | 内容在确认后被改过。重新 `show` → 让人重新确认 → 用新 version 重试 |
| 11 | `NOT_FOUND` | 案例不存在或草稿已被清理。重新 `draft` |
| 12 | `VALIDATION_FAILED` | 值不合法 / 参数错误。**读 `violations` 自己改**，别重试原样的 |
| 13 | `STATE_CONFLICT` | 状态不允许该操作。**停下，报给人** |
| 14 | `NEEDS_INPUT` | 缺必填信息，机器补不出来。**读 `questions` 去问人，不要猜** |
| 20 | `INFRA_ERROR` | 配置缺失 / 库不通。可重试，重试仍失败则报警 |

### 两条不能动的

1. **幂等重放返回 0。** 重放在语义上是成功 —— 返回非 0 会让调用方以为没成功而无限重试。
2. **12 与 14 必须分开。** 一个是调用方自己能改，一个必须去问人。
   **下一步动作不同的，就不能合并成一个码。**

### 映射成 agent 语义的建议

| 类别 | 码 |
|---|---|
| 可自动重试 | `20`（退避后重试；`draft` 重试务必复用同一个 `--id`）|
| agent 自己改内容再来 | `12` |
| 必须问人 | `14`、`13` |
| 重新走确认流程 | `10` |
| 重建 | `11` |

---

## 5. 配置

从环境变量或仓库根 `.env` 读，**取不到直接 fail fast（20），不给默认值** ——
默认值会把「配置漏了」变成「连到了错的库」，后者难查得多。

```
ATP_DB_URL        jdbc:postgresql://host:port/db  或  postgres://…
ATP_DB_USER
ATP_DB_PASSWORD   允许为空
```

> ⚠️ **CLI 目前直连 PostgreSQL。** 按 `CLAUDE.md`，最终 CAS 那套要由平台提供接口、
> CLI 改为调接口（数据库密钥不该放在 agent 那一侧）。
> **那次改动会动到本契约的第 5 节，但第 1–4 节的形状会保持不变。**

---

## 6. 构建

```bash
make build          # → bin/atp
```

⚠️ **在容器里构建时请加 `--user $(id -u):$(id -g)`**，否则产物属主是 root，
宿主机上的人和其他 session 都覆盖不了它（已经发生过一次）。
