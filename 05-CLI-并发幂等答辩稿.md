# 答辩稿 · CLI 方案下的幂等与并发状态设计

> 用途：面试口述稿，按讲述顺序排列，可直接背。
> **MCP server 方案已于 2026-08-27 废弃并删除**，设计推理留在 git 历史（commit `95cdc81`）。
> 两者的区别是**前提不同**：MCP 方案假设「老平台改不动」，本方案的前提是「老平台改得动」。
> 被问到时答"这是前提不同，不是谁对谁错"（见 §9 末尾那张表）。

---

> ⚠️ **先摆正定位**：这套设计的**主线是「把能力从提示词搬进确定性代码」** ——
> 判据是「机器能不能判定这条规则被违反了」，能就下沉、下沉完从提示词里删掉。
> **下面这整份讲的并发幂等，是为了兜住 agent 重试与 multiagent 并发导致的重复写入，
> 是一个具体风险的应对，不是项目目的。**
> 讲项目从 `07-CLI-项目综述.md` 开的头，这份是被追问细节时展开用的。

## 0. 一页速记

| | |
|---|---|
| 核心动作 | 先在**老平台的案例表**里插一条 AI_DRAFT 行拿到 UUID，一切围绕这行做 |
| 幂等键 | 就是那个 **UUID 主键**，由 CLI 本地生成 |
| 编辑期写入 | **只碰 `tc_step` 一张表一行**；`tc_case` 只在 commit 被写一次 |
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

## 3. 老平台的改造（成本很低，这是方案成立的前提）

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
6. **枚举列一律存 `SMALLINT`，语义由应用层的 Java enum 持有** —— 见 §3.4

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

**⭐ 整份是一个原子事务** —— 因为状态枚举存的是 `SMALLINT`，
新增 `AI_DRAFT` 根本不需要 DDL，它只是应用层多认一个码 `4`。

```sql
BEGIN;

-- 主键改宽。UUID 是 36 字符，老列 VARCHAR(32) 装不下。
-- ⚠️ 父子两边必须一起改：tc_step.case_id 存的就是 tc_case.case_id 的值，
--    本库不建外键约束，但长度不一致仍会在写入时被静默截断。
ALTER TABLE tc_case ALTER COLUMN case_id TYPE VARCHAR(36);
ALTER TABLE tc_step
  ALTER COLUMN case_id TYPE VARCHAR(36),
  ALTER COLUMN step_id TYPE VARCHAR(36),
  -- ⭐ 编辑期的状态机与乐观锁放在【这里】，不放 tc_case。见 §3.5
  ADD COLUMN status  SMALLINT NOT NULL DEFAULT 1,
  ADD COLUMN version INT      NOT NULL DEFAULT 0;

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

  ADD COLUMN version    INT NOT NULL DEFAULT 0,
  -- ⚠️ 这里【不加】任何整包 JSON 列。草稿的正位是 tc_step.step_json，见 §3.5
  ADD COLUMN created_by VARCHAR(64) NULL,

  -- ⭐ 约束随状态而变：编写期允许残缺，一旦离开 AI_DRAFT 就必须完整
  --    字面量 4 就是 AI_DRAFT —— 这是"枚举存 int"的可读性代价，用 COMMENT 补偿
  ADD CONSTRAINT ck_case_complete CHECK (
        status = 4
     OR (case_code IS NOT NULL AND title    IS NOT NULL
     AND module_id IS NOT NULL AND priority IS NOT NULL
     AND author    IS NOT NULL)
  );

COMMENT ON COLUMN tc_case.status IS '状态 1=DRAFT 2=ACTIVE 3=DEPRECATED 4=AI_DRAFT（AI 编写中）';

CREATE INDEX idx_ai_draft_cleanup ON tc_case (status, created_at);

COMMIT;
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

### 3.3 ⚠️ 全库不建外键约束

`tc_case.module_id → tc_module`、`tc_step.case_id → tc_case` 都是**逻辑外键**，
只建索引、不建 `FOREIGN KEY` 约束。引用完整性由**写入方**（CLI 与平台）保证。

理由和代价都要能说：

| | |
|---|---|
| **为什么不建** | 外键在写入路径上要查父表加锁，高并发下是热点；分库分表直接不可用；且它会让 DDL 变形 —— 被引用列连类型都改不了，必须 `DROP FK → 改父 → 改子 → 装回去` |
| **代价①** | **没有 `ON DELETE CASCADE`。** 清理任务必须自己**先删子表再删父表**，顺序反了就找不到要删的步骤了（见 §8） |
| **代价②** | **数据库不再挡编造的 `module_id`。** 「防模型编造模块」这条责任转移到了 `atp validate`，它必须对着 `tc_module` 查 |

> **面试点**：讲"不建外键"时，只说性能理由是不够的 ——
> **要能说出你把那两件事接管到哪儿去了。**
> 约束撤掉不等于不变式消失，只是换了个人负责。说不出接管方，那就是漏了。

### 3.4 ⭐ 枚举列存 `SMALLINT`，不用 PG 原生 enum

`status` / `case_type` / `priority` 在 DB 里都是 `SMALLINT`，
取值含义由应用层的 Java enum 持有（`CaseStatus` / `CaseType` / `Priority`）。

**为什么不用 PG 原生 enum 类型**：

```sql
-- 原生 enum 要这么加值：
ALTER TYPE tc_status ADD VALUE 'AI_DRAFT';
```

这条有两个连锁问题：

1. **新加的枚举值不能在同一个事务里被使用** —— 而 `ck_case_complete` 的 CHECK 正好要引用它，
   所以 `ALTER TYPE` 必须单独提交。**整份迁移脚本因此不是原子的**：
   第一条成功、后面失败会停在"枚举多了个值但表没改"的中间态。
2. 加一个状态就要动一次 DDL。**而"加状态"是业务演进里最频繁的动作之一。**

改存 `SMALLINT` 之后：**新增 `AI_DRAFT` 不需要任何 DDL**，只是应用层多认一个码。
迁移脚本收回成一个 `BEGIN; ... COMMIT;`。

**代价要认，别只讲好处**：

| 代价 | 补偿 |
|---|---|
| `SELECT *` 出来是数字，DBA 看不懂 | `COMMENT ON COLUMN` 把映射写在列上 |
| DB 层面挡不住写入非法码（比如 `status=99`） | 不加范围 CHECK —— **加了就等于把枚举又拖回 DDL，白改了**。这道防线交给应用层的 `fromCode()`，非法码直接抛异常 |
| CHECK 里出现裸字面量 `4` | 紧跟一条 COMMENT 说明；这是唯一一处硬编码 |

> **面试点**：这是一次**把语义从 DDL 层挪回应用层**的取舍。
> 判据是"**这个东西会不会频繁变**" —— 会变的东西放在改起来最便宜的那一层。
> 状态枚举会变，所以它不该住在需要 `ALTER TYPE` 的地方。

---

### 3.5 ⭐ 编辑期只写 `tc_step`，`tc_case` 只在 commit 被写一次

这是整套设计里**改动最多、也最值得讲**的一处。它经过两轮修正才落到现在的形状。

**第一版**：草稿整包塞进 `tc_case.draft_json`，commit 时展开到 `tc_step`。
删掉了 —— **同一份数据存两遍，必然要同步。**

**第二版**：步骤一步一行写 `tc_step`，表头写 `tc_case` 的正式列。
问题是 `update` 变成跨表事务，而**它是整条链路上最高频的路径**（草稿要反复改）。

**现在这版**：

```
tc_case   表头 + 平台侧状态。编辑期只有骨架，commit 那一刻才被填齐
tc_step   一比一。step_json 是完整草稿，编辑期的状态机与乐观锁也在这
```

| 路径 | 写什么 | 形状 |
|---|---|---|
| `draft` | 两条 INSERT | 都是新行，无争用 |
| `update` | **只写 `tc_step`** | ⭐ **单表单行 CAS** |
| `commit` | CAS `tc_step` → 投影表头到 `tc_case` | 跨表事务，但一份草稿只发生一次 |

> **最高频的路径不跨表** —— 跨表事务和随之而来的加锁顺序问题，
> 在"反复改草稿"这条路上根本不存在。

#### 两个 version，两个生命周期

| | 管什么 | 编辑期 |
|---|---|---|
| `tc_step.version` | **编辑期**乐观锁。preview 给用户看的、commit 要带回来的就是它 | 每改一次跳一次 |
| `tc_case.version` | 案例落地后**平台侧**修改用的 | **一动不动** |

实测（`atp update` 跑两次之后）：

```
tc_case  | status=4 | version=0 | case_code=(NULL)   ← 一动没动
tc_step  | status=4 | version=1 | 3 步
```

#### 步骤为什么是「一行一案例」而不是「一步一行」

因为**老平台的执行器就是读整份步骤跑的**，不会按 `seq` 逐条查库。
既然没有按步查询的需求，一步一行就只是在制造 N 倍的行、N 倍的删插、
和一个本可以不存在的 `seq` 列 —— **顺序本来就是数组顺序**。

**代价**：`UNIQUE(case_id, seq)` 没了，"seq 不重复、连续无跳号"交给 `atp validate`。
跟 §3.3（撤外键）、§3.4（枚举存 int）是同一类取舍 —— **能说出接管方就行**。

#### 「确认的和提交的是同一份」还成立吗

成立，而且更干净。`update` 是**唯一**写 `tc_step` 的路径，
用户 preview 看到的 `version` 就是 `tc_step` 的。谁动了草稿，version 就跳，commit 就失败。

**⭐ 白捡一个好处**：commit 之后 `step_json` 留着提交那一刻的完整快照。
这就是当初 ChangeSet 方案里的「冻结快照」，现在不花额外代价就有了 ——
**被追问"用户到底确认了什么"时，库里查得到。**

#### 加锁顺序统一为 `tc_step → tc_case`

跨表的路径只有 `commit` 和 M5 的清理任务。两边同序才不会死锁（见 §8）。

> ⚠️ 前提和 §3.3、§3.4 是同一条：**所有写入都走 CLI 或平台代码。**

---

## 4. 完整流程（按时间顺序讲）

```
① atp draft --id <uuid> --title "..."      → INSERT: status=AI_DRAFT, version=0
     └─ 返回 { caseId, status, version }    ← 主键在这一刻就存在了

② atp show <id> > draft.json               → 拿到骨架，agent 在本地编辑

③ (agent 生成内容 → 用户口头修改 → 改 draft.json)

④ atp validate -f draft.json               → 纯本地规则校验，不打网络、毫秒级

⑤ atp update <id> --version 0 -f draft.json  → 单表单行 CAS，只写 tc_step，version 0→1
                                              （tc_case 一动不动）

⑥ atp preview <id>                         → 从库里读出来渲染，打印 version=1
                                              ↑ 用户看的是库里的，不是本地文件

⑦ atp commit <id> --version 1              → CAS tc_step → 投影表头进 tc_case
                                              （跨表事务，一份草稿只一次）
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
UPDATE tc_step SET step_json = ?::jsonb, version = version + 1, updated_at = now()
 WHERE case_id = ? AND status = 4 /* AI_DRAFT */ AND version = ?
-- ⭐ 就这一条。单表、单行、不碰 tc_case
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
线程B (agent):   UPDATE tc_step, version 3 → 4        ← agent 又改了一版
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
- 排在后面 → 线程A 已把 `tc_step.status` 改成 DRAFT → 线程B 的 `AND status = 4` 不匹配 → 失败

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

⚠️ **加锁顺序必须与 `commit` 一致（`tc_step` → `tc_case`）**，否则两者撞在同一条
边界草稿上会死锁。`FOR UPDATE SKIP LOCKED` 顺带白送一个好处：
正在被 agent 编辑的草稿直接跳过，下个月再清。

```java
@XxlJob("atpDraftCleanupHandler")
public void cleanup() {
    Timestamp cutoff = Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS));
    int total = 0, batch;
    do {
        // ① 先在【tc_step】上锁定这一批 —— 与 commit 同序，且跳过正在被编辑的草稿
        // ⚠️ PG 不支持 DELETE ... LIMIT（MySQL 支持），分批只能先 SELECT 出 id 再按 id 删。
        List<String> ids = jdbc.queryForList("""
                SELECT s.case_id FROM tc_step s
                  JOIN tc_case c ON c.case_id = s.case_id
                 WHERE s.status = 4 /* AI_DRAFT */ AND c.created_at < ?
                 ORDER BY c.created_at
                 LIMIT 1000
                 FOR UPDATE OF s SKIP LOCKED
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

1. **⭐ 先子后父，且加锁顺序要和 `commit` 一致（`tc_step` → `tc_case`）。**
   删完 `tc_case` 再想删 `tc_step`，`WHERE` 条件已经查不到了 —— 永久孤儿行，**没有任何报错**。
   顺序若与 `commit` 相反，两者撞在同一条边界草稿上会 `40P01 deadlock detected`。
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

**④ ⚠️ 数据库不校验枚举码的合法性。**

枚举存 `SMALLINT` 换来了"加状态不用动 DDL"，代价是
**`UPDATE tc_case SET status = 99` 数据库照收**。

我**故意没加**范围 CHECK —— 加了就等于把枚举语义又拖回 DDL，
那这次改造的收益就没了。这道防线放在应用层：`CaseStatus.fromCode()` 遇到未知码直接抛异常。

**所以这条依赖"所有写入都走 CLI 或平台代码"这个前提。**
如果有人直接连库跑 SQL，这层保护就没了 —— 和 §3.3 撤外键是同一类取舍：
**约束撤掉不等于不变式消失，只是换了个人负责。**

> 被追问"那你怎么保证没人直连库改"时，老实答：**保证不了，靠权限收口。**
> 承认边界比编一个不存在的防护强。

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
