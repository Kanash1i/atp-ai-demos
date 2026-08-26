# 答辩稿 · CLI 方案下的幂等与并发状态设计

> 用途：面试口述稿，按讲述顺序排列，可直接背。
> 取代此前的 MCP server 方案（`02-HANDOFF-demo2-mcp.md` §5）——
> **那份仍然有效，但它的前提是「老平台改不动」。本方案的前提是「老平台改得动」。**
> 被问到两者区别时，答"这是前提不同，不是谁对谁错"（见 §8）。

---

## 0. 一页速记

| | |
|---|---|
| 核心动作 | 先在**老平台的案例表**里插一条 AI_DRAFT 行拿到 UUID，一切围绕这行做 |
| 幂等键 | 就是那个 **UUID 主键**，由 CLI 本地生成 |
| 并发仲裁点 | **PostgreSQL 的主键唯一约束 + 一条带 CAS 的 UPDATE** |
| 防"确认后被偷改" | **version 乐观锁**（替代了 contentHash） |
| 草稿清理 | **XXL-JOB 每月一次硬删除** |
| 需要额外的 server 吗 | **不需要**。仲裁点在平台自己的表上 |

四句话骨架：

1. **幂等键必须在用户确认之前就存在，而且由发起方生成**
2. **应用层的 if 只是优化，唯一索引才是正确性**
3. **检查和写入必须在同一条语句里，否则检查的结论会过期**
4. **幂等键要传到最后一次写磁盘的地方——我直接把它做成了那张表的主键**

---

## 1. 先把问题定义对（开场 30 秒）

面试官问"高并发"，多数人条件反射答 QPS、缓存、限流。**Agent 场景的高并发不是这个形状**，第一句就要把它拧过来：

> "传统 Web 的高并发是**大量不同用户做不同的事**，压力点在热点行的锁竞争。
> Agent 的高并发是**同一个逻辑意图被重复投递多次**。"

重复投递的四个来源（背下来，后面所有设计都是从这里推的）：

1. **LLM 客户端超时重试** —— 工具调用慢一点就重发
2. **Agent 自己的 retry loop** —— 拿到非 0 退出码就重试，而且**它不知道"这次失败但副作用已经发生了"**
3. **Multiagent fan-out** —— 同一个子任务被派给两个 worker，或 supervisor 重新派发
4. **人** —— 用户在 preview 界面上重复点确认

**所以核心矛盾是幂等，吞吐是次要的。**

---

## 2. 为什么是 CLI 而不是 MCP server

### 2.1 CLI 的三条真实优势（先认账，别护短）

| # | 论据 | 实质 |
|---|---|---|
| 1 | **Token 成本** | MCP 的 tool definitions 开场就全量灌进 context。CLI 是按需 `--help`，渐进披露 |
| 2 | **组合性** | MCP 一次 tool call 一个来回、每次都过模型。CLI 可以 `find \| xargs`，批量 lint 200 个 locator 是一行 |
| 3 | **中间结果不过 context** | CLI 可以把大块输出重定向到文件，模型压根不看 |

### 2.2 但 CLI 有一条硬边界

> **CLI 可以替代协议和呈现，替代不了仲裁点的位置。**

因为"同一意图只落库一次"是一个**全局不变式**，而全局不变式只能在**所有并发参与方都会经过的那一个点**上强制。每个 CLI 进程都是局部参与方——**你写得再对，两个不通信的进程也无法达成互斥。**

### 2.3 所以我的选择是：不外挂仲裁点，把仲裁点放回平台自己的表

MCP 方案是在外面挂一张 ChangeSet 表来当仲裁点。但既然老平台改得动，**那张表就是多余的一层转发**——

> **幂等键要传到最后一次写磁盘的地方。**
> 那我干脆让它**就是**最后写磁盘的那张表的主键。

一表两用，少一张表、少一个进程、少一套部署。

---

## 3. 老平台的两处改造（成本很低，这是方案成立的前提）

### 3.1 改造清单

1. **主键放宽到 `VARCHAR(36)`** —— UUID 是 36 字符，老列 `VARCHAR(32)` 装不下。
   AI 案例的主键由 CLI 本地生成，人工案例仍走平台雪花 ID —— 两者不冲突，
   **唯一约束只关心"不重复"，不关心谁生成的。**
2. **`case_type` 保持原义（`IOS` / `ANDROID` / `PC_WEB`）** —— 这是老平台本来就有的
   「执行平台」概念，不要拿它兼职做 AI/人工的来源标记。
   AI 来源由 `created_by`（agent 身份）承担，编写态由 `status='AI_DRAFT'` 承担 ——
   **一个字段只表示一件事**
3. **`status` 加 `AI_DRAFT` 枚举值** —— ⚠️ **不复用已有的 `DRAFT`**，见下
4. **加 `version` 字段** —— 乐观锁
5. **放宽几个 `NOT NULL`，再用 `CHECK` 按状态挣回来** —— 见下

> **⭐ 为什么必须新造 `AI_DRAFT` 而不是复用 `DRAFT`**（这条是实现时才发现的，很能讲）：
> 老平台的 `DRAFT` 语义是"**案例已写好、尚未启用**"，执行器和列表页都认它。
> 而 AI 编写中的行内容还是空的 —— 混进 `DRAFT` 会被既有流程当成可用案例。
> **两个状态字面都叫"草稿"，语义完全不同。**
>
> 反过来，`commit` 的目标状态正好就是老平台的 `DRAFT`：
> **AI 编写完成后落地成一条普普通通的草稿案例，执行器和既有列表页完全无感知。**
> ——被问"你怎么保证不影响现有系统"时，答这一句。

> **为什么加枚举值几乎零成本**：老平台的查询本来就**按类型收敛**，没有大一统查询，
> 每条 SQL 本来就带 `case_type` 条件。新增一个枚举值不会串到既有查询里。
> ⚠️ 唯一要 grep 一遍的是 `WHERE status != 'X'` 这种**黑名单写法**（见 §9③）。

### 3.2 DDL（实际执行的那一支，已在 PostgreSQL 17 上跑通）

```sql
-- 第一步：给状态枚举加编写态。
-- ⚠️ PG 的坑，和 MySQL 完全相反，别混：
--    · PG 可以用 BEFORE / AFTER 把新值插在枚举任意位置（MySQL 只能追加末尾）
--    · 但【新加的枚举值不能在同一个事务里被使用】，所以这条必须单独提交，
--      下面引用 'AI_DRAFT' 的 CHECK 才建得起来 —— 也就是说 V1 不是原子的（见 §9④）
ALTER TYPE tc_status ADD VALUE 'AI_DRAFT';

-- 第二步：主键改宽。UUID 是 36 字符，老列 VARCHAR(32) 装不下。
-- ⚠️ 父子两边必须一起改：tc_step.case_id 存的就是 tc_case.case_id 的值，
--    本库不建外键约束，但长度不一致仍会在写入时被静默截断。
ALTER TABLE tc_case ALTER COLUMN case_id TYPE VARCHAR(36);
ALTER TABLE tc_step
  ALTER COLUMN case_id TYPE VARCHAR(36),
  ALTER COLUMN step_id TYPE VARCHAR(36);

-- 第三步：放宽必填 + 乐观锁 + 编写期内容
ALTER TABLE tc_case
  -- 编写期填不出来，只能放宽 NOT NULL。
  -- ⭐ case_code 放宽后仍带 UNIQUE：PG 的唯一约束默认允许多个 NULL
  --    （PG 15+ 可用 NULLS NOT DISTINCT 改掉，我们要的正是默认行为），
  --    所以并存任意多条尚未编号的草稿不会互相撞键。
  ALTER COLUMN case_code DROP NOT NULL,
  ALTER COLUMN module_id DROP NOT NULL,
  ALTER COLUMN priority  DROP NOT NULL,
  ALTER COLUMN author    DROP NOT NULL,
  ALTER COLUMN title     DROP NOT NULL,

  ADD COLUMN version    INT   NOT NULL DEFAULT 0,
  ADD COLUMN draft_json JSONB NULL,
  ADD COLUMN created_by VARCHAR(64) NULL,

  -- ⭐ 约束随状态而变：编写期允许残缺，一旦离开 AI_DRAFT 就必须完整
  ADD CONSTRAINT ck_case_complete CHECK (
        status = 'AI_DRAFT'
     OR (case_code IS NOT NULL AND title    IS NOT NULL
     AND module_id IS NOT NULL AND priority IS NOT NULL
     AND author    IS NOT NULL)
  );

CREATE INDEX idx_ai_draft_cleanup ON tc_case (status, created_at);
```

> ⚠️ **PG 没有 MySQL 的 `ON UPDATE CURRENT_TIMESTAMP`。**
> `updated_at` 由写入方显式赋值（`CaseStore` 每条 UPDATE 都带 `now()`），否则得挂触发器。
> 这是从 MySQL 迁过来最容易漏的一条 —— 漏了不报错，只是时间戳永远停在创建那一刻。

**⭐ 第 5 条要单独讲：放宽 `NOT NULL` 是代价，`CHECK` 把它挣回来。**

编写期的行必然残缺（`case_code`、`module_id` 都还没确定），
所以列上的 `NOT NULL` 只能松开。松开就意味着**人工录入路径也失去了保护** —— 这是真代价。

`ck_case_complete` 把这个保护按状态重新装回去：**只有 `AI_DRAFT` 允许残缺。**
带来的直接好处是 `commit` 那条 UPDATE **天然被数据库守门** ——
残缺的案例根本迁不出 `AI_DRAFT`，**应用层不需要再写一遍"提交前检查必填"**。

> 被问"校验逻辑写在哪"时，这是最好的答案：**能用约束表达的，就不要用代码表达。**
> 代码会被绕过、会有分支漏掉，约束不会。

⚠️ **两点关于 UUID 的，主动说出来比等对方问出来强**：

1. **这里不能用 PG 原生的 `uuid` 类型**（16 字节，比 36 字符的 varchar 省一半）。
   因为同一列还要装人工案例的雪花 ID，换成 `uuid` 会把存量数据挡在外面。
   **放弃紧凑存储，是兼容遗留数据的代价。**
2. **"UUID 做主键导致页分裂"这条在 PG 上不成立** —— 那是 InnoDB 的问题，
   因为 InnoDB 是索引组织表，主键顺序就是物理存储顺序。
   PG 是堆表，主键只是一个普通 B-tree 索引，随机 UUID 不会打乱行的物理布局。
   （被问"为什么不怕随机主键"时答这个 —— 能区分这两种存储结构，比背结论强。）

---

## 4. 完整流程（按时间顺序讲）

```
① atp draft --id <uuid> --title "..."      → INSERT: status=AI_DRAFT, version=0
     └─ 返回 { caseId, status, version }    ← 主键在这一刻就存在了

② atp show <id> > draft.json               → 拿到骨架，agent 在本地编辑

③ (agent 生成内容 → 用户口头修改 → 改 draft.json)

④ atp validate -f draft.json               → 纯本地规则校验，不打网络、毫秒级

⑤ atp update <id> --version 0 -f draft.json  → CAS 写入，version 0→1

⑥ atp preview <id>                         → 从库里读出来渲染，打印 version=1
                                              ↑ 用户看的是库里的，不是本地文件

⑦ atp commit <id> --version 1              → CAS: AI_DRAFT → DRAFT
```

### 讲这一段时要点出的两个设计决定

**(a) 内容存在行里，不留在本地 json。**
本地 json 只是**编辑面**，库里的行才是**事实来源**。
理由：`preview` 和 `commit` 是两次独立调用，如果 commit 读本地文件，
**agent 完全可以在两次调用之间把文件重写一遍，而你发现不了。**
存在行里之后，agent 一改内容 version 就跳，commit 立刻失败。

**(b) `commit` 不携带任何内容，只有 `id` 和 `version`。**
它是一次纯状态迁移。这样"用户确认的"和"最终落库的"物理上就是同一行——
**从结构上消灭了内容漂移，而不是靠提示词约束 agent 别乱改。**

---

## 5. 幂等的三个落点（核心段落）

### 5.1 `draft` —— UUID 由 CLI 本地生成，主键唯一约束即幂等约束

```
uuid = randomUUID()                    # 本地生成；agent 重试时命令行不变，uuid 就不变
INSERT INTO tc_case (id, ...) VALUES (uuid, ...)
  └─ DuplicateKeyException  →  说明上一次其实成功了，只是响应丢了
                            →  读回该行，返回 replayed=true，退出码 0
```

**这一步是整套设计里最容易被漏掉、也最能体现理解深度的地方。**
如果 UUID 由数据库生成，那么：

```
INSERT 成功 → 响应超时丢失 → agent 重试 → 又一条草稿、另一个 UUID
两条都合法、都能 commit，但只有一条被用户看过
```

版本号救不了这个——**它们是两行不同的记录。**
把生成动作挪到客户端，这个洞就免费消失了。

> 金句：**幂等键必须由发起方生成，并且在用户确认之前就存在。**

### 5.2 `update` —— CAS 乐观锁

```sql
UPDATE tc_case SET draft_json = ?, version = version + 1
 WHERE case_id = ? AND status = 'AI_DRAFT' AND version = ?
```

`affectedRows = 0` → 说明有人在你之前改过 → 让 agent 重新 `show` 再改。

### 5.3 `commit` —— 状态检测与版本检测必须在同一条 UPDATE 里

```sql
UPDATE tc_case SET status = 'DRAFT', version = version + 1
 WHERE case_id = ? AND status = 'AI_DRAFT' AND version = ?
```

---

## 6. 为什么必须是一条 UPDATE（被追问时的杀手锏）

先展示**错误写法**——它看起来该查的都查了：

```java
var row = SELECT * FROM tc_case WHERE case_id = ?;
if (row.status  != "AI_DRAFT")      return ERROR;   // 检查状态
if (row.version != expectedVer)  return ERROR;   // 检查版本
UPDATE tc_case SET status='DRAFT', version=version+1 WHERE case_id = ?;   // ← WHERE 里只有 id
```

问题：**检查的结论，在 UPDATE 真正执行的那一刻可能已经过期。**

```
用户 preview 时拿到 version = 3，点了确认

线程A (commit):  SELECT  → status=AI_DRAFT, version=3   ✓ 两项检查都通过
线程B (agent):   UPDATE draft_json, version 3 → 4     ← agent 又改了一版
线程A (commit):  UPDATE SET status='DRAFT' WHERE id=x ← 把 version=4 的内容提交了
```

**用户确认的是 version 3，落库的是 version 4。** 版本检测写了、执行了、还通过了——
它在 SELECT 那一刻是对的，等到 UPDATE 执行时已经失效。这就是 **TOCTOU**。

合并成一条之后就没有窗口了。**PG 和 MySQL 走的是不同机制，但结论一样，这点值得会说**：

- **InnoDB**：UPDATE 对匹配行加排他锁，WHERE 的求值和写入在同一个锁区间内完成
- **PostgreSQL（READ COMMITTED）**：MVCC 下 UPDATE 撞到被并发事务锁住的行时会**等待**，
  对方提交后**拿最新版本重新求值 WHERE**（EvalPlanQual）。
  所以线程A 看到的一定是线程B 写完之后的 `version`，`AND version = 3` 自然不匹配。

> **面试点**：能说出"我依赖的是 CAS 语义，不是某个引擎的锁实现"，
> 比只会背 InnoDB 行锁强 —— 它说明这套设计换库不会塌。

线程B 只有两种可能：

- 排在前面 → 线程A 的 `AND version = 3` 不匹配 → `affectedRows = 0` → **正确拒绝**
- 排在后面 → 线程A 已把 status 改成 DRAFT → 线程B 的 `AND status='AI_DRAFT'` 不匹配 → 失败

**没有中间态。把"检查"和"写入"压进一条语句，窗口就是零——这就是 CAS 在 SQL 里的样子。**

---

## 7. `affectedRows == 0` 之后必须再读回来分情况

**不能直接抛错**，否则重放永远过不去，agent 会无限重试。

| 读回来的状态 | 含义 | 返回 | 退出码 |
|---|---|---|---|
| `status=DRAFT`，`version = N+1` | **重放**：上次成功了，响应丢了 | `caseId`，`replayed=true` | **0（成功）** |
| `status=AI_DRAFT`，`version > N` | 确认后内容被改过 | `VERSION_CONFLICT`，要求重新 preview | 10 |
| 行不存在 | id 错了，或草稿已被清理 | `NOT_FOUND` | 11 |

⚠️ **第一行的退出码是 0，不是错误码。** 这是给 agent 用的工具，
"重放"在语义上是**成功**——返回非 0 会让 agent 认为没成功而继续重试。
**幂等设计做对了一半、却在退出码上翻车，是很常见的失误。**

---

## 8. 草稿清理：XXL-JOB 每月一次

⚠️ 没有外键级联，所以**必须自己删两次，且顺序不能反**。

```java
@XxlJob("atpDraftCleanupHandler")
public void cleanup() {
    Timestamp cutoff = Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS));
    int total = 0, batch;
    do {
        // ① 先圈出这一批要删的 case_id（走 idx_ai_draft_cleanup）
        // ⚠️ PG 不支持 DELETE ... LIMIT（MySQL 支持），分批只能先 SELECT 出 id 再按 id 删，
        //    或者用 WHERE ctid IN (SELECT ctid ... LIMIT n)。这里用前者，因为子表也要按 id 删。
        List<String> ids = jdbc.queryForList("""
                SELECT case_id FROM tc_case
                 WHERE status = 'AI_DRAFT' AND created_at < ?
                 LIMIT 1000
                """, String.class, cutoff);
        if (ids.isEmpty()) break;

        // ② 先删子表 —— 必须在删父表之前，父表没了就再也定位不到这些步骤
        jdbc.update("DELETE FROM tc_step WHERE case_id IN (:ids)", Map.of("ids", ids));
        // ③ 再删父表
        batch = jdbc.update("DELETE FROM tc_case WHERE case_id IN (:ids)", Map.of("ids", ids));
        total += batch;
    } while (batch == 1000);
    XxlJobHelper.log("清理弃置 AI 草稿 {} 条", total);
}
```

Cron：`0 0 3 1 * ?`（每月 1 号 03:00）。

要讲的四点：

1. **⭐ 先子后父，顺序不能反。** 删完 `tc_case` 再想删步骤，`WHERE` 条件已经查不到了 ——
   直接产生永久孤儿行，而且**没有任何报错**。
   > 做清理设计时第一件事是问：**这行有没有子表？没有级联的话谁来删？**
   >
   > 这个坑我在写测试时真踩了：`@BeforeEach` 只 `DELETE FROM tc_case`，
   > 上一个用例的孤儿步骤漏进下一个用例，测试互相污染。
2. **必须分批。** 一条 `DELETE WHERE created_at < ?` 扫全表会长时间持锁，在生产库上是事故。
   走 `idx_ai_draft_cleanup` 索引，每批 1000 行。
   ⚠️ **PG 不支持 `DELETE ... LIMIT`**（MySQL 支持）—— 只能先 `SELECT` 出 id 再按 id 删，
   或者 `WHERE ctid IN (SELECT ctid ... LIMIT n)`。**这是背错了会当场露馅的一条。**
3. **保留期一个月，不是 30 分钟。** 确认人是 QA 同事，可能隔几天才回来看。
   TTL 是按**人的节奏**定的，不是按技术方便定的。
4. **⭐ 一个月 + 硬删除，让 `EXPIRED` 这个状态不需要存在。**
   过期的表现就是**行不存在 → `NOT_FOUND`**，状态机少一个状态。

## 9. 主动交代的边界（比宣称"全链路幂等"可信得多）

被问"你这套哪里还不够"时，答这四条：

**① CLI 的版本收敛是 O(N)。**
20 台机器上 20 个 CLI 版本，规则改一条要全量升级，且旧版本在升级前一直产生不合规数据。
Server 是改一行重启、全局生效。**这一维上 CLI 输给 server，我认。**
缓解：CLI 启动时向平台校验 `schema_version`，不匹配直接拒绝执行并提示升级。

**② 凭证粒度不如独立 server。**
CLI 需要一个能写 `tc_case` 的 token。可以 scope 到 `case_type='AI' AND status='AI_DRAFT'`，
但**"必须经用户确认过"这个约束在 token 层面无法表达**——它靠的是状态机，不是权限。

**③ 既有 status 过滤要确认是白名单还是黑名单。**

```sql
WHERE status IN ('ACTIVE','ARCHIVED')   -- ✅ 白名单：新枚举默认排除，安全
WHERE status != 'DELETED'               -- ⚠️ 黑名单：新枚举默认被包含进来
```

黑名单写法会把草稿静默捞进结果集。grep 一遍 status 的过滤方式即可，命中就那几处。

**④ ⚠️ V1 迁移脚本不是原子的。**

`ALTER TYPE tc_status ADD VALUE 'AI_DRAFT'` **必须单独提交** ——
PG 不允许在添加枚举值的同一个事务里使用这个新值，而后面 `ck_case_complete` 的
CHECK 表达式正好要引用它。

后果：如果第一条成功、后面的 `ALTER TABLE` 失败，库会停在
**"枚举多了个值、但表结构没改"** 的中间态。不致命（多一个用不到的枚举值无害），
但**重跑迁移时 `ADD VALUE` 会因为值已存在而报错**，脚本必须写成
`ADD VALUE IF NOT EXISTS` 才是幂等的。

> **自嘲一句效果很好**：我整套设计讲的都是幂等，
> **结果自己的迁移脚本一开始不幂等** —— 幂等这件事，稍不留神就漏。

### 什么时候这套不成立，必须回到 MCP server 方案

| 前提 | 本方案 | 回退到 server |
|---|---|---|
| 老平台改得动 | ✅ 必需 | 改不动时 → 外挂 ChangeSet 表 |
| Preview 能在平台 UI 上做 | ✅ 草稿就在案例表里，平台原生能渲染 | 做不了 → 需要共享存储 |
| 最终写入边界认幂等键 | ✅ 主键就是幂等键 | 不认 → 需要幂等代理表 |

> **答法：这不是两个方案谁更好，是前提不同。**
> 三个前提全满足就用 CLI，任一条不满足，仲裁点就得外挂，那就是一个 server。

---

## 10. 我怎么证明它真的幂等（测试清单）

- **10 个线程并发 `commit` 同一个 id** → 只有 1 个 `affectedRows=1`，其余 9 个全部 `replayed=true` 且 `caseId` 相同、**退出码 0**
- **10 个线程并发 `draft` 同一个 uuid** → 只插入 1 行，其余全部命中唯一约束后读回
- `commit` 成功但响应丢失 → 重试返回同一个 `caseId`，`replayed=true`
- preview 拿 version=N 后，另一线程 `update` 使 version=N+1 → `commit --version N` **必须失败**（这条直接测 §6 那个 TOCTOU）
- 不存在的 id → `NOT_FOUND`，退出码 11
- `validate` 断言**零网络调用、零模型调用**
- 清理任务：造 2500 条过期草稿 → 分 3 批删完，未过期的一条不动

---

## 11. 收尾（如果只让说一句）

> "我没有在外面加一层 server 来保证幂等。我做的是**把幂等键做成老平台案例表的主键**，
> 让数据库的唯一约束和一条带 CAS 的 UPDATE 去当仲裁点。
> 因为幂等的本质不是写多少代码，是**谁持有那个所有并发方都必须经过的点**。"
