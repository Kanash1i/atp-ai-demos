# `atp` CLI 设计（可接入 opencode 演示）

> 骨架来自 `05-CLI-并发幂等答辩稿.md`。本文只讲**怎么建**，不重复论证。
> 模块位置：`demo2-atp-cli/`（取代 `02-HANDOFF-demo2-mcp.md` 的 MCP server 形态，
> 那份文档保留为"平台改不动时"的备选方案）。

---

## 1. 技术选型

| 项 | 选择 | 理由 |
|---|---|---|
| 语言 | Java 21 | 与 know-engine 一致 |
| CLI 框架 | **picocli 4.7.x** | 子命令、参数校验、自动 `--help` |
| **不用 Spring Boot** | 裸 JDBC + Jackson | ⭐ CLI 被 agent **高频反复调用**，冷启动是真实成本：Spring Boot ≈ 1.5s，picocli fat jar ≈ 300ms。一次会话调 20 次就是 24s vs 6s |
| DB | **PostgreSQL**，pgjdbc，**每次调用一条连接** | 进程活 300ms，连接池没有意义；错误判定走 SQLSTATE 不走厂商 errorCode |
| 打包 | `maven-shade-plugin` fat jar + `bin/atp` 包装脚本 | 后续可上 GraalVM native（≈40ms），但不是 demo 必需 |

> **面试点**：这是个反直觉但正确的取舍——"我是 Spring 工程师，但这里我没用 Spring，
> 因为 CLI 的性能画像和 web 服务完全不同"。主动讲这条比讲技术栈本身有价值。

---

## 2. 命令表

| 命令 | 打 DB | 幂等 | 说明 |
|---|---|---|---|
| `atp schema` | ✗ | ✓ | 输出目标 JSON Schema + 枚举 + 必填规则 |
| `atp modules` | ✓ 读 | ✓ | 模块字典（外键取值范围） |
| `atp validate -f <json>` | ✗ | ✓ | **纯本地规则校验，零网络零模型** |
| `atp lint-locator <glob...>` | ✗ | ✓ | XPath/CSS 静态检查，**支持批量**（CLI 相对 MCP 的收益就在这条） |
| `atp draft --id <uuid> --title <s>` | ✓ 写 | ✅ | INSERT 草稿行，返回 `{caseId,status,version}` |
| `atp show <id>` | ✓ 读 | ✓ | 输出当前 `draft_json` + `version` |
| `atp update <id> --version N -f <json>` | ✓ 写 | CAS | 写内容，`version+1` |
| `atp preview <id>` | ✓ 读 | ✓ | 人读渲染 + 高亮存疑字段 |
| `atp commit <id> --version N` | ✓ 写 | ✅ | `AI_DRAFT → DRAFT`，**不携带内容** |

全局参数：`--json`（结构化输出，给 agent）、`--profile <name>`（连接配置）、`-v`。
默认输出人类可读；agent 走 `--json`。

---

## 3. 输出协议与退出码

⭐ **退出码是 agent 唯一可靠的分流依据**，必须先定死。

| 码 | 常量 | 含义 | agent 该怎么做 |
|---|---|---|---|
| **0** | `OK` | 成功，**含幂等重放** | 继续 |
| 10 | `VERSION_CONFLICT` | 版本对不上，内容被改过 | 重新 `show` → `preview` → 重新确认 |
| 11 | `NOT_FOUND` | id 不存在或草稿已被清理 | 重新 `draft` |
| 12 | `VALIDATION_FAILED` | 规则校验不通过 | 读 `violations` 自己改 |
| 13 | `STATE_CONFLICT` | 状态不允许该操作（如已 ARCHIVED） | 停下，问用户 |
| 14 | `NEEDS_INPUT` | 缺必填信息，机器补不了 | **去问用户**，别猜 |
| 20 | `INFRA_ERROR` | DB 不通等 | 重试或报警 |

> ⚠️ **重放必须返回 0。** "幂等重放"在语义上是成功——返回非 0 会让 agent 认为失败而继续重试。
> 幂等逻辑做对了却在退出码上翻车，是很常见的失误。
>
> ⚠️ **12 和 14 必须分开**，理由和 MCP 方案里 `NEEDS_REVISION` / `NEEDS_INPUT` 一样：
> 一个是 agent 自己能改，一个是必须去问人。**下一步动作不同的，就不能合并成一个码。**

`--json` 输出统一信封：

```json
{
  "ok": true,
  "code": "OK",
  "replayed": false,
  "data": { "caseId": "...", "status": "AI_DRAFT", "version": 0 },
  "violations": [],
  "questions": []
}
```

---

## 4. 目录结构

```
demo2-atp-cli/
├─ pom.xml
├─ bin/atp                                  # 包装脚本
├─ src/main/java/com/atp/cli/
│  ├─ AtpCli.java                           # picocli 根命令
│  ├─ cmd/{Draft,Show,Update,Preview,Commit,Validate,LintLocator,Schema,Modules}Command.java
│  ├─ store/CaseStore.java                  # ⭐ 所有 SQL 只在这一个类里
│  ├─ rule/{RuleEngine,LocatorLinter,Violation}.java   # 纯本地，无 IO
│  ├─ model/{CaseDraft,CaseRow,Result}.java
│  └─ out/{Output,ExitCode}.java
├─ src/main/resources/schema/tc_case.schema.json
└─ src/test/java/com/atp/cli/
   ├─ ConcurrentCommitTest.java             # ⭐ 10 线程并发
   ├─ ConcurrentDraftTest.java
   ├─ TocTouTest.java                       # preview 后被改 → commit 必须失败
   └─ ZeroNetworkValidateTest.java
```

**`store/CaseStore.java` 是唯一持有 SQL 的类** —— 面试时直接翻这一个文件就能讲完并发设计。

---

## 5. 两段核心代码

### 5.1 `draft` —— 主键唯一约束即幂等约束

```java
public Result<CaseRow> draft(String id, String title, String platform, String createdBy) {
    try (var conn = ds.getConnection();
         var ps = conn.prepareStatement("""
             INSERT INTO tc_case (id, case_type, status, version, title, platform, created_by, created_at)
             VALUES (?, 'AI', 'AI_DRAFT', 0, ?, ?, ?, NOW(3))
             """)) {
        ps.setString(1, id);
        ps.setString(2, title);
        ps.setString(3, platform);
        ps.setString(4, createdBy);
        ps.executeUpdate();
        return Result.ok(new CaseRow(id, "AI_DRAFT", 0));

    } catch (SQLIntegrityConstraintViolationException dup) {
        // ⭐ 上一次其实成功了，只是响应丢了 —— 把并发/重试的失败者转成幂等的成功者
        return findById(id)
                .map(row -> Result.replayed(row))
                .orElseGet(() -> Result.fail(ExitCode.INFRA_ERROR, "唯一冲突但读不回该行"));
    }
}
```

### 5.2 `commit` —— 一条 CAS UPDATE + `affectedRows==0` 的三分支

```java
public Result<CaseRow> commit(String id, int expectedVersion) {
    try (var conn = ds.getConnection()) {

        // ⭐ 状态检测与版本检测都在 WHERE 里，求值和写入同处一个行锁区间
        int affected;
        try (var ps = conn.prepareStatement("""
                UPDATE tc_case
                   SET status = 'DRAFT', version = version + 1, committed_at = NOW(3)
                 WHERE case_id = ? AND status = 'AI_DRAFT' AND version = ?
                """)) {
            ps.setString(1, id);
            ps.setInt(2, expectedVersion);
            affected = ps.executeUpdate();
        }

        if (affected == 1) {
            return Result.ok(new CaseRow(id, "DRAFT", expectedVersion + 1));
        }

        // affectedRows == 0 —— 绝不能直接抛错，否则重放永远过不去
        var row = findById(id).orElse(null);
        if (row == null) {
            return Result.fail(ExitCode.NOT_FOUND, "案例不存在，或草稿已被清理任务回收");
        }
        if ("DRAFT".equals(row.status()) && row.version() == expectedVersion + 1) {
            return Result.replayed(row);                       // 上次成功了，响应丢了 → 退出码 0
        }
        if ("AI_DRAFT".equals(row.status()) && row.version() > expectedVersion) {
            return Result.fail(ExitCode.VERSION_CONFLICT,
                    "确认后内容被修改（当前 version=%d，你确认的是 %d），请重新 preview"
                            .formatted(row.version(), expectedVersion));
        }
        return Result.fail(ExitCode.STATE_CONFLICT, "当前状态 " + row.status() + " 不允许提交");
    }
}
```

> 这两段是整个 demo 的**代码高光**。面试时如果只给你看一屏代码，给这一屏。

---

## 6. 接入 opencode

### 6.1 `opencode.json`（放在项目根）

```json
{
  "$schema": "https://opencode.ai/config.json",
  "permission": {
    "bash": {
      "atp schema*":       "allow",
      "atp modules*":      "allow",
      "atp validate*":     "allow",
      "atp lint-locator*": "allow",
      "atp show*":         "allow",
      "atp preview*":      "allow",
      "atp draft*":        "allow",
      "atp update*":       "allow",
      "atp commit*":       "ask",
      "*":                 "ask"
    }
  }
}
```

⚠️ **`atp commit` 单独设成 `ask`** —— opencode 的权限弹窗**就是那道人工确认关卡**，
不需要另做 UI。这是这个 demo 最省事也最能讲的一处：
**"用户确认"不是提示词里的一句话，是宿主的权限门。**

> ⚠️ 开工第一件事核对 `permission.bash` 的 glob 语义（`@opencode-ai/plugin` 已确认取值是
> `"ask" | "deny" | "allow"`，但配置侧的匹配规则要对着当前版本验一遍，别照抄）。

### 6.2 `.opencode/skills/atp-case-authoring/SKILL.md`

```markdown
---
name: atp-case-authoring
description: 用 atp CLI 根据自然语言创建符合 ATP 平台标准的自动化测试案例。用户要求新增、生成、编写或保存测试案例时使用。
compatibility: opencode
metadata:
  demo: "atp-cli"
---

# 测试案例创建流程

## 流程

1. 确认项目 ID 和执行平台（iOS / Android / Web），不明确就先问用户。
2. `atp schema --json` 和 `atp modules --json` —— 拿到目标结构和模块字典。
   **禁止编造模块 ID、元素标识和期望结果。**
3. `atp draft --id $(uuidgen) --title "<标题>" --json` —— 拿到 caseId 和 version。
   ⚠️ 命令失败需要重试时，**复用同一个 uuid**，不要重新生成。
4. 按 schema 写出草稿 json，先 `atp validate -f draft.json --json` 本地校验。
   退出码 12 → 按 violations 自己改；退出码 14 → **去问用户**，不要猜。
5. `atp update <id> --version <N> -f draft.json --json` 写入。
6. `atp preview <id>` —— 把输出原样展示给用户，等用户明确说"确认"。
7. `atp commit <id> --version <N>` —— 只传 id 和 version。

## 退出码

0 成功（含重放）· 10 版本冲突，重新 show+preview · 11 不存在 · 12 校验失败自己改
· 13 状态冲突，停下问用户 · 14 缺信息，问用户 · 20 基础设施故障

## 约束

- 不得把自己的推断描述成用户的明确输入。
- 用户没有明确说"确认"之前，不要执行 commit。
```

### 6.3 ⭐ 这份 SKILL.md 为什么比原来的短一半

原 `test-case-authoring/SKILL.md` 有 8 条流程 + 5 条安全约束。新版是 7 条流程 + 2 条约束。
删掉的三条**不是放松了要求，是被数据库约束吃掉了**：

| 原 skill 里的约束 | 现在谁来保证 |
|---|---|
| "不得跳过 prepare 直接提交" | **DB**：commit 要一个 id，而 id 只能由 draft 产生，且 `status` 必须是 `AI_DRAFT` |
| "不得提交旧 changeSetId" | **DB**：version 乐观锁，内容一改 version 就跳，commit 直接失败 |
| "规范化结果必须在确认摘要中说明" | **CLI**：`atp preview` 直接把规范化差异打出来，不依赖模型转述 |

> **判据（这是整个 demo 想证明的东西）：**
> **"这条规则，机器能不能判定它被违反了？"**
> 能 → 下沉到 CLI/DB 强制，然后**从 skill 里删掉**；
> 不能（如"不得把推断说成用户输入"）→ 留在 skill，并承认它不可靠。
>
> 提示词里每留一条机器能管的规则，都是在用不可靠的手段做可靠的事。

---

## 7. 演示脚本（5 分钟，按时间顺序）

| 时间 | 动作 | 讲什么 |
|---|---|---|
| 0:00 | 用户："帮我加个购物车结算的 Web 案例" | — |
| 0:20 | agent 跑 `atp schema` / `atp modules` | **左移**：生成前先知道该产出什么形状 |
| 0:40 | `atp draft --id 550e...` → `version=0` | **幂等键此刻已存在，且由客户端生成** |
| 1:20 | agent 写 draft.json，`atp validate` 报 12 | 纯本地、毫秒级、**零模型调用** |
| 1:40 | 改完再 validate 过，`atp update --version 0` → `version=1` | CAS |
| 2:10 | `atp preview` | 用户看到的是**库里的行**，不是本地文件 |
| 2:40 | **另开一个终端** `atp update --version 1` 偷偷改内容 → `version=2` | 制造 §6 的 TOCTOU 场景 |
| 3:00 | `atp commit <id> --version 1` → **退出码 10 拒绝** | ⭐ **全场最高光**：确认的和要提交的不是同一份，DB 直接挡掉 |
| 3:40 | 重新 preview → `commit --version 2` → 成功；opencode 弹权限框 | 权限门就是确认关卡 |
| 4:10 | **同一条 commit 再跑一次** → 退出码仍是 0，`replayed=true` | 幂等重放 |
| 4:40 | 跑 `ConcurrentCommitTest`：10 线程 → 1 成功 + 9 replayed | 用测试收尾，不是用嘴 |

> 2:40 那一步是**手动制造并发**，比空口讲"如果两个进程同时……"强十倍。**一定要演。**

---

## 8. 里程碑

| # | 内容 | 产出 |
|---|---|---|
| M1 | DDL 迁移脚本 + `CaseStore` 的 draft/show/update/commit | Testcontainers 起 PostgreSQL，`ConcurrentCommitTest` 绿 |
| M2 | picocli 命令层 + 退出码 + `--json` 信封 | `bin/atp` 能跑通完整七步 |
| M3 | `validate` / `lint-locator` 纯本地规则 | 断言零网络零模型 |
| M4 | opencode 接入（`opencode.json` + SKILL.md） | 在 opencode 里走完演示脚本 |
| M5 | XXL-JOB 清理任务 | 造 2500 条过期草稿，分 3 批删净 |

**M1 先于 M2**：先把并发测试跑绿，再包 CLI 外壳。
外壳好写，**并发正确性是这个 demo 唯一的硬内容**。
