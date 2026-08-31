# demo2-atp-cli 决策记录

编号从 D-100 起，与 demo1（D-0xx）区隔。

---

## D-100 · `status` 新增 `AI_DRAFT`，不复用已有的 `DRAFT`

**背景**：设计阶段写的是"`status` 加一个 `DRAFT` 枚举值"。实现时对照
`00-SHARED-CONTEXT.md` §1.1 才发现，老表的 `status` **本来就有 `DRAFT`**，
语义是"案例已写好、尚未启用"，执行器和列表页都认它。

**决策**：新增 `AI_DRAFT` 表示编写态，`commit` 做 `AI_DRAFT → DRAFT`。

**理由**：AI 编写中的行内容还是空的，混进 `DRAFT` 会被既有流程当成可用案例。
两个状态字面都叫"草稿"，语义完全不同。

**额外收益**：`commit` 的目标状态正好是老平台原生的 `DRAFT` ——
AI 编写完成后落地成一条普通草稿案例，**执行器和既有列表页完全无感知**。
测试 `ConcurrentCommitTest.commitsIntoOrdinaryDraft` 锁定这条。

⚠️ **PG 的枚举扩展和 MySQL 完全不同，别混**（见 D-110）：
PG 用 `ALTER TYPE tc_status ADD VALUE 'AI_DRAFT'`，可以用 `BEFORE`/`AFTER` 插在任意位置；
但**新值不能在同一事务里被使用**，所以这条必须单独提交，V1 因此不是原子的。
（MySQL 的 ENUM 按定义顺序编号，只能追加末尾 —— 那是另一套限制。）

---

## D-101 · Testcontainers 必须显式指定 `-Dapi.version`

**症状**：`Could not find a valid Docker environment`。
socket 通（`curl --unix-socket /var/run/docker.sock /_ping` 返回 OK）、
`docker ps` 正常、用户在 docker 组里 —— 看起来一切都对。

**真因**（要开 SLF4J 绑定才看得到，否则这行日志被吞）：

```
client version 1.32 is too old. Minimum supported API version is 1.40
```

Docker Engine **29** 起最低只接受 API 1.40，而 Testcontainers 依赖的
docker-java 默认仍谈判到 1.32。升级 Testcontainers 1.20.4 → 1.21.3 **无效**。

**决策**：surefire 固定 `-Dapi.version=${docker.api.version}`（默认 1.44）。

**实测无效的做法**：环境变量 `DOCKER_API_VERSION=1.44` 不起作用 ——
docker-java 只从**系统属性** `api.version` 取值。

**顺带**：`~/.docker/config.json` 里 `currentContext: remote` 时，
本机跑测试要 `export DOCKER_HOST=unix:///var/run/docker.sock`，否则会打到远程台式机。

**排障提示**：Testcontainers 的失败原因走 SLF4J。没有绑定时**什么都不打印**，
只剩一句无信息量的 `Could not find a valid Docker environment`。
本模块 test scope 常驻 `slf4j-simple` 就是为了这个，别删。

---

## D-102 · 放宽 `NOT NULL` + `CHECK` 约束按状态挣回来

**问题**：编写期的行必然残缺（`case_code`、`module_id` 都还没确定），
但老表这些列是 `NOT NULL`，草稿根本插不进去。

**决策**：放宽这几列的 `NOT NULL`，再加
`ck_case_complete CHECK (status = 'AI_DRAFT' OR 必填全非空)`。

**代价要认**：放宽 `NOT NULL` 意味着**人工录入路径也失去了列级保护**，
保护改由 CHECK 承担。

**收益**：`commit` 那条 UPDATE 天然被数据库守门 —— 残缺案例迁不出 `AI_DRAFT`，
应用层不必再写一遍"提交前检查必填"。

✅ PostgreSQL 一直真正强制 CHECK，这条不需要附加前提。
（MySQL 5.7 会静默丢弃 CHECK —— 那个坑记在 **D-108**，现在只作为对照素材保留。）
`CommitGuardTest.incompleteDraftBlockedByCheckConstraint` 锁定这条。

> 原则：**能用约束表达的，不要用代码表达。** 代码会被绕过、分支会漏，约束不会。

---

## ~~D-103~~ · 不继承 `spring-boot-starter-parent`（⚠️ 已作废，见 D-121：整体改用 Go）

CLI 被 agent 高频反复调用，冷启动是真实成本：Spring Boot ≈ 1.5s，picocli fat jar ≈ 300ms。
一次会话调 20 次差 24s。同理不用连接池 —— 进程只活 300ms，HikariCP 预热比它省下的还多。

**这与 demo1 的技术栈刻意不同，不是疏漏。**

---

## D-104 · UUID 由 CLI 本地生成，不由数据库生成

**这是整套幂等的唯一来源。** 若由 DB 生成：
`INSERT 成功 → 响应超时丢失 → agent 重试 → 又一条草稿、另一个 UUID`，
两条都合法、都能 commit，而**版本号救不了它们（是两行不同的记录）**。

把生成动作挪到客户端后，重试复用同一个 UUID → 撞主键唯一约束 → 读回来当成功返回。
`ConcurrentDraftTest` 两个用例锁定这条。

⚠️ 两点值得记住（切 PG 之后结论变了）：

1. **"UUID 主键导致页分裂"在 PG 上不成立。** 那是 InnoDB 的问题 ——
   InnoDB 是索引组织表，主键顺序就是物理存储顺序。
   PG 是堆表，主键只是普通 B-tree 索引，随机 UUID 不打乱行的物理布局。
2. **但这里也用不了 PG 原生的 `uuid` 类型**（16 字节 vs 36 字符）。
   同一列还要装人工案例的雪花 ID，换成 `uuid` 会把存量数据挡在外面。
   放弃紧凑存储，是兼容遗留数据的代价。

---

## D-105 · `case_type` 归还「执行平台」原义，`browser` / `timeout_sec` 删除

**背景**：D-100 时我把 `case_type` 当成 `MANUAL/AI` 的来源标记用了。
但老平台本来就按 **iOS / Android / Web** 区分案例（旧 `SKILL.md` 第一句就写着），
`case_type` 这个名字天然属于执行平台，不该兼职。

**决策**：

| 字段 | 处置 |
|---|---|
| `case_type` | `ENUM('IOS','ANDROID','PC_WEB')`，回归执行平台原义 |
| `browser` | **删除** —— 只对 Web 有意义，被 `case_type` 覆盖，属于过度设计 |
| `timeout_sec` | **删除** —— 全链路无人读写，纯粹是从文档抄下来的死字段 |
| AI 来源标记 | 由 `created_by`（agent 身份）承担 |
| 编写态 | 由 `status='AI_DRAFT'` 承担 |

**原则**：**一个字段只表示一件事。** 这跟 §5.2 三态状态机不合并成 `REJECTED`、
以及 demo1 拒答标记混了两类含义（`03-HANDOFF-rag-v2.md` §2.4）是同一条教训的第三次出现。

**连带变更**：清理任务的索引从 `(status, case_type, created_at)` 简化为 `(status, created_at)` ——
`AI_DRAFT` 只可能由 AI 编写路径产生，不需要再叠一个来源条件。

⚠️ **共享契约漂移，尚未同步**：`00-SHARED-CONTEXT.md` §1.2 与迁入本模块的
`src/main/resources/schema/tc_case.schema.json` 的 `required` 里仍有 `browser` / `timeout_sec`。
schema 文件在 M3 做 `atp schema` 时一并改。

---

## D-106 · 新增 `tc_project`，定位链路变成 项目 → 模块 → 案例

真实定位一条案例是"某个项目的某个模块里的某个案例"。原文档只有 `tc_module`，缺一层。

**决策**：新增 `tc_project`，`tc_module.project_id` 指向它（**逻辑外键，无 FK 约束**，见 D-109）。
**`tc_case` 不冗余 project_id** —— 项目通过 `tc_case → tc_module → tc_project` 两跳 join 取得。

**代价**：案例列表按项目过滤要多一次 join。真实遗留平台多半会把 `project_id` 冗余到
`tc_case` 上。现在不做是因为冗余带来一致性维护成本，且当前没有性能诉求。
**要加随时可加，反过来去掉很难。**

`SchemaShapeTest.projectModuleCaseChain` 锁定这条链路。

---

## D-107 · `tc_step` 建表；主键改宽的迁移代价（外键撤除后已消失）

**表结构**（按需求只保留三块核心 + `seq`）：

```
step_id   VARCHAR(36) PK      子表主键 UUID
case_id   VARCHAR(36)         对应父表 UUID —— 逻辑外键，无 FK 约束（D-109）
seq       INT                 1..n 连续无跳号，UNIQUE(case_id, seq)
step_json JSONB               步骤内容 —— ⭐ 这是步骤在库里【唯一】的存放处（D-118）
```

**`seq` 为什么没被省掉**：步骤是有序的，顺序不能靠"插入顺序"隐式表达。
`UNIQUE (case_id, seq)` 是**本表内部**的唯一键，不是外键 —— 与 D-109 不冲突，
该由数据库保证的仍然由数据库保证。
顺带：`case_id` 是这个联合索引的最左列，删步骤时能走到它，不必再单建索引。

**⚠️ 曾经的迁移踩点，现在没有了**：最初 `tc_step.case_id` 带 `FOREIGN KEY` 引用
`tc_case.case_id`。**MySQL 明确不允许直接修改被外键引用的列类型**（当时实测撞到的就是这个）；
PG 的限制没那么死，但改类型同样会牵动约束校验与重建。
V1 把主键从 `VARCHAR(32)` 改到 `VARCHAR(36)` 当时必须：

```
DROP FOREIGN KEY → 改父列 → 改子列 → ADD CONSTRAINT 装回去
```

D-109 撤除全部外键后，这一套不再需要，V1 直接两条 `ALTER` 就完了。
**记录在此是因为它正是"外键让 DDL 变形"的教科书案例，面试可以直接用。**

**⚠️ 仍然存在的约束**：父子两边的列宽**必须一起改**。
没有外键强制不代表可以只改一边 —— `tc_step.case_id` 存的就是 `tc_case.case_id` 的值，
长度不一致会在写入时被**静默截断**，比报错更难查。

---

## D-108 · ✅ 已关闭：改用 PostgreSQL，`CHECK` 静默失效的问题不存在

**原问题**：老平台原设定是 MySQL 5.7，而 **5.7 会解析 `CHECK` 子句然后直接丢弃，
不报错、不告警、不生效** —— D-102 的 `ck_case_complete` 在 5.7 上等于不存在。

**当时的实测**（`mysql:5.7.44` vs `mysql:8.4`，同一份 V0+V1，同一条 SQL）：

```
5.7  应用 V1                        → 无任何输出，无报错
5.7  SHOW CREATE TABLE tc_case      → 找不到 ck_case_complete，约束根本没建
5.7  INSERT 残缺草稿 + commit UPDATE → 成功，得到一条 status=DRAFT 且
                                       case_code/module_id/priority/author 全 NULL 的案例
8.4  同一条 commit UPDATE            → ERROR 3819 (HY000): Check constraint
                                       'ck_case_complete' is violated
```

**解决**：整个 demo 改用 PostgreSQL（D-110）。**PG 一直真正强制 CHECK**，问题消失。

**保留这条记录的理由**：它本身是很好的面试素材 ——
> "我的测试环境（Testcontainers 起的 8.4）掩盖了真实环境（5.7）的行为差异。
> 用例全绿，但那条约束在生产上根本不存在。"

**这里真正的教训不是 MySQL 版本，是**：
**你验证过的环境，不等于它真正要跑的环境。** 换成 PG 之后这条教训依然成立 ——
只是这次我们让两边一致了。

---

## D-109 · 全库不建外键约束，引用完整性由写入方保证

**决策**：撤除 `fk_module_project` / `fk_case_module` / `fk_step_case`，
只保留对应的索引（`idx_module_project` / `idx_case_module` / `uk_step_case_seq`）。

**为什么**：

- 外键在写入路径上要查父表并加锁，高并发下是热点
- 分库分表后外键直接不可用
- **它让 DDL 变形** —— 被引用列连类型都改不了（见 D-107），迁移成本被放大

**两个代价，必须说得出接管方**：

| 撤掉的是什么 | 谁接管 |
|---|---|
| `ON DELETE CASCADE`（删案例自动删步骤） | **M5 的清理任务**：必须自己 `先删 tc_step → 再删 tc_case`，顺序反了就定位不到步骤 |
| `module_id` 引用有效性（挡模型编造的模块） | **M3 的 `atp validate`**：必须对着 `tc_module` 查 |

`SchemaShapeTest` 里两个用例把这两条代价钉死了：
`deletingCaseLeavesOrphanSteps`（只删父表会留孤儿）和
`fabricatedModuleIdIsAcceptedByDb`（M999 照样写得进去）。
**它们断言的是"缺陷"而不是"功能"，目的是防止后面的人想当然。**

**⚠️ 这个坑当场就咬了一次**：测试基类的 `@BeforeEach` 原本只 `DELETE FROM tc_case`，
上一个用例留下的孤儿步骤漏进下一个用例，测试互相污染。
`truncate()` 改成先删 `tc_step` 再删 `tc_case`。

> **面试要点**：讲"不建外键"只说性能理由是不够的。
> **约束撤掉不等于不变式消失，只是换了个人负责 —— 说不出接管方，那就是漏了。**

---

## D-110 · 数据库从 MySQL 改为 PostgreSQL

**背景**：真实项目用的是 PG，`00-SHARED-CONTEXT.md` 原来写的 MySQL 5.7 是设定错误。

**决策**：全面切 PG（`postgres:17`），`00-SHARED-CONTEXT.md` §1.1 同步改掉。

**核心论证完全不受影响** —— 这点很重要，说明设计不依赖某个引擎：

- 唯一约束当并发仲裁点 ✅
- `UPDATE ... WHERE id=? AND status=? AND version=?` 的 CAS 语义 ✅
- `affectedRows == 0` 三分支 ✅
- 唯一约束允许多个 NULL ✅（PG 默认行为与 MySQL 相同）

> **CAS 在两边靠的是不同机制，但结论一样，这点值得会说**：
> InnoDB 是对匹配行加排他锁，WHERE 求值与写入同处一个锁区间；
> PG 在 READ COMMITTED 下是 MVCC —— UPDATE 撞到被并发事务锁住的行会等待，
> 对方提交后**拿最新版本重新求值 WHERE**（EvalPlanQual）。
> **我依赖的是 CAS 语义，不是某个引擎的锁实现。**

**实际改动的差异清单**：

| | MySQL | PostgreSQL |
|---|---|---|
| CHECK 约束 | 8.0.16+ 才生效，5.7 静默丢弃 | **一直强制** |
| 枚举扩展 | 只能追加末尾 | `ALTER TYPE ADD VALUE`，可 BEFORE/AFTER 插入；**但新值不能在同一事务用** —— 因此本项目最终改存 SMALLINT，见 D-112 |
| `DELETE ... LIMIT n` | 支持 | **不支持**，要 `WHERE ctid IN (SELECT ctid ... LIMIT n)` 或先 SELECT 出 id |
| `ON UPDATE CURRENT_TIMESTAMP` | 支持 | **没有**，写入方显式赋值或挂触发器 |
| UUID 主键 | InnoDB 索引组织表 → 随机插入页分裂 | 堆表 → **不存在这个问题** |
| JSON | `JSON` | `JSONB`（可 GIN 索引） |
| 错误判定 | errorCode 1062 / 3819 | **SQLSTATE 23505 / 23514**（SQL 标准，换库不用改） |
| 枚举列传参 | 直接 setString | 需要显式转型 `?::tc_case_type` |

**顺带的代码改善**：错误判定从厂商 `errorCode` 改成 **SQLSTATE**，
`23505`（unique_violation）/ `23514`（check_violation）是 SQL 标准的一部分，
这段逻辑现在换库也不用动。**被逼着做的移植，反而让代码更干净了。**

**⚠️ 由此暴露的坑，最终导向 D-112**：`ALTER TYPE ... ADD VALUE` 必须单独提交
（新值不能在同一事务使用），所以 V1 一度**不是原子的**。
改成枚举存 `SMALLINT` 之后这个问题从根上消失了 —— V1 收回成一个 `BEGIN; ... COMMIT;`。

---

## D-111 · MCP server 方案废弃：废案审计、资产迁移、删除

**2026-08-27** 决定放弃 MCP server 形态，主线改为 `atp` CLI。
`02-HANDOFF-demo2-mcp.md`（902 行）与 `demo2-atp-mcp/`（4678 行）**已删除，不是归档**。

**为什么删而不是留着**：留着的废案会持续污染上下文 ——
每次开工都要重新判断"这个该不该看"，而判断本身就是成本。
设计推理留在 git 历史（commit `95cdc81`）已经够用。

### 审计结论（三档）

| 档 | 内容 | 处置 |
|---|---|---|
| **A · 已迁入本模块** | `Action`（共享契约，`00-SHARED-CONTEXT` §1.3）、`LocatorType` / `WaitStrategy` / `OnFailure` / `Diagnostic` / `Severity` / `FieldRequirement`、`DiagnosticCodes`（STD-xxx 码表）、`LocatorLinter`（200 行纯规则）、`ActionContractTableTest`（45 个用例）、`tc_case.schema.json` | 已迁，编译并跑通 |
| **B · M3 再搬** | `ValidationEngine` + 测试、`GoldenCasesTest` 的黄金用例数据 | 要先解开 `PlatformProfile` 依赖 |
| **C · 丢弃** | `tool/*`、`McpServerApplication`、MCP 协议测试、`RuleMapper`(381) + `AtpAliasDictionary` + `profile/`、`EnvelopeParser`、`GapAnalyzer` | 见下 |

### C 档里最值得说的：`RuleMapper` 为什么没有位置

它是"别名归一化 + 缺口分析"流水线的心脏，381 行。但——

**CLI 设计故意不做归一化。** `atp schema` 在**生成阶段**就告诉 agent 该产出什么形状（左移），
所以不存在"上游乱生成、下游收拾"的环节。加上 `browser` / `timeout_sec` 删除后
一半映射规则已失效（D-105）。

> **迁移时最该问的不是"这段代码好不好"，是"新架构里它有没有位置"。**
> 好代码搬进没有它位置的架构，就变成了债。

同理 `EnvelopeParser`（从模型回复里剥信封）—— CLI 收的是 agent 写好的 JSON 文件，不需要剥。
`GapAnalyzer`（172 行缺口分析）对应退出码 14 `NEEDS_INPUT`，
但那本质就是"必填为空且无默认值"，写在 `validate` 里几行就够，不值得搬一个分析器。

---

## D-112 · 枚举列存 `SMALLINT`，语义由应用层持有

**决策**：`status` / `case_type` / `priority` 在 DB 里是 `SMALLINT`，
取值含义由 `CaseStatus` / `CaseType` / `Priority` 三个 Java enum 持有。
**不用 PG 原生 enum 类型。**

```
status:    1=DRAFT  2=ACTIVE  3=DEPRECATED  4=AI_DRAFT
case_type: 1=IOS    2=ANDROID 3=PC_WEB
priority:  0=P0     1=P1      2=P2          3=P3
```

**为什么**：原生 enum 加值要 `ALTER TYPE tc_status ADD VALUE 'AI_DRAFT'`，而

1. **新值不能在同一事务里被使用** —— `ck_case_complete` 的 CHECK 正好要引用它，
   所以 `ALTER TYPE` 必须单独提交，**整份迁移脚本因此不是原子的**
2. 加一个状态就要动一次 DDL —— 而"加状态"是业务演进里最频繁的动作之一

改存 `SMALLINT` 后：**新增 `AI_DRAFT` 不需要任何 DDL**（只是应用层多认一个码），
V1 收回成一个 `BEGIN; ... COMMIT;`。

**代价（要能说出来，别只讲好处）**：

| 代价 | 补偿 |
|---|---|
| `SELECT *` 出来是数字 | `COMMENT ON COLUMN` 把映射写在列上 |
| DB 挡不住 `status = 99` | **故意不加范围 CHECK** —— 加了就等于把枚举又拖回 DDL，白改了。防线交给 `fromCode()`，未知码直接抛 |
| CHECK 里出现裸字面量 `4` | 紧跟 COMMENT 说明；全库唯一一处硬编码 |

**前提**：所有写入都走 CLI 或平台代码。有人直连库跑 SQL 这层保护就没了 ——
和 D-109 撤外键是同一类取舍。

> **判据：这个东西会不会频繁变？** 会变的东西，放在改起来最便宜的那一层。
> 状态枚举会变，所以它不该住在需要 `ALTER TYPE` 的地方。

**连带**：`PgTestBase` 的迁移脚本执行改为**整份一次执行**，不再按分号切 ——
V1 用 `BEGIN/COMMIT` 包成原子事务，切开就等于把原子性拆掉，
那样测的就不是要部署的那个东西。

---

## D-113 · `bin/atp` 必须自己选 JVM 并检查版本

**踩到的**：包装脚本原本直接 `exec java`。开发机上 sdkman 把 `JAVA_HOME` 指向 JDK 17，
结果 agent 拿到的是**一整页 `UnsupportedClassVersionError` 堆栈**，
而不是一句能照做的错误 —— 而且这页堆栈发生在 picocli 初始化之前，
CLI 自己的异常处理器根本没机会介入。

**决策**：脚本按 `ATP_JAVA` → `JAVA_HOME` → `PATH` 的顺序选 JVM，
解析出主版本号，低于 21 就以 **退出码 20（INFRA_ERROR）** 退出并打一行可执行的提示。

```
[INFRA_ERROR] 需要 JDK 21+，当前 .../java 是 17。用 ATP_JAVA=/path/to/jdk21/bin/java 指定。
```

> **给 agent 用的工具，错误必须是"一行 + 一个退出码"。**
> 堆栈是给人看的；agent 只会把它当成一坨无法分流的文本，然后瞎重试。

---

## D-114 · SLF4J provider 是 runtime 依赖，不是 test scope

`json-schema-validator` 传递依赖了 `slf4j-api` 但不带 provider。缺 provider 时它会往
**stderr 打三行 warning**，直接混进 agent 要解析的输出里：

```
SLF4J(W): No SLF4J providers were found.
SLF4J(W): Defaulting to no-operation (NOP) logger implementation
...
```

**决策**：`slf4j-simple` 提到默认 scope，`src/main/resources/simplelogger.properties`
把级别压到 `error`；测试用 `src/test/resources` 下的同名文件覆盖成 `info`
（测试资源在 classpath 上优先），好保留 Testcontainers 的诊断。

> **CLI 的 stdout/stderr 是 API 的一部分。** 任何库往里写字都是在破坏契约。

---

## D-115 · 用测试机械地守住"SQL 只在 store 包"

面试时"翻一个包就能把并发设计讲完"这个说法，靠的是这条不变式。
但**靠约定守不住** —— 下一个人图方便在某个 command 里拼一句 SQL，
约定就破了，且没有任何东西会提醒他。

`SqlContainmentTest` 扫 `src/main/java`，对 `store` 包之外的文件匹配
`SELECT/INSERT INTO/UPDATE...SET/DELETE FROM/ALTER TABLE/CREATE TABLE`，
命中即失败（去掉注释行避免 javadoc 误报）。

> 又是同一条判据：**机器能判定的规则，交给机器强制，别写在文档里指望人自觉。**
> 这跟 D-102（能用约束表达的不用代码表达）、D-112（会变的东西放在改起来最便宜的层）
> 是同一族思路的第三次应用。

---

## D-116 · 退出码与 `--json` 信封是对 agent 的契约

| 码 | 常量 | agent 该怎么做 |
|---|---|---|
| **0** | `OK` | 继续（**含幂等重放**）|
| 10 | `VERSION_CONFLICT` | 重新 `show` → `preview` → 让用户重新确认 |
| 11 | `NOT_FOUND` | 重新 `draft` |
| 12 | `VALIDATION_FAILED` | 读 `violations` **自己改** |
| 13 | `STATE_CONFLICT` | 停下，问用户 |
| 14 | `NEEDS_INPUT` | **去问用户**，不要猜 |
| 20 | `INFRA_ERROR` | 重试或报警 |

信封：`{ ok, code, replayed, data, violations, questions }`。

**两条不能动的**：

1. **幂等重放返回 0。** 重放在语义上是成功，返回非 0 会让 agent 以为没成功而无限重试。
2. **12 和 14 必须分开。** 一个是 agent 自己能改，一个是必须去问人 ——
   **下一步动作不同的，就不能合并成一个码。**
   `DraftValidator` 按 JSON Schema 报错类型分流：`required` 缺失 → 14，其余 → 12；
   两类同时存在时**优先 14**（人不补信息，agent 改了也白改）。

这条分流是从已废弃的 MCP 方案继承下来的唯一结构性设计（原三态状态机）。

### ⭐ 2026-08-31：这条分流有实测证据了，不再只是推理

`atp-platform` 把这份退出码映射翻译成了工具返回给模型的「下一步该做什么」文本，
`VALIDATION_FAILED`（自己改）与 `NEEDS_INPUT`（必须问人）严格分开、没有合并。

平台侧的实测结果：**agent 拿到 `NEEDS_INPUT` 时确实停下来问了三个问题，而不是瞎猜。**
对方的原话是「合并了模型就会开始编」。

**这是本项目里第二条从观察到的真实 agent 行为得出的结论**
（第一条是 D-123 的「agent 会绕过缺失的能力」）。
面试里讲这条比讲"我设计了七个退出码"有分量得多 ——
**它证明的是「下一步动作不同的不能合并成一个码」这条判据真的影响了模型行为。**

---

## D-117 · M2 实测：不用 Spring 的取舍值多少

| 命令 | 冷启动耗时 |
|---|---|
| `atp --version` | **0.34 s** |
| `atp validate -f draft.json`（含 JSON Schema 校验） | **0.45 s** |

D-103 当时的估算是「Spring Boot ≈ 1.5s / picocli fat jar ≈ 300ms」，实测吻合。
fat jar 5.0 MB。

`bin/atp` 带 `-XX:TieredStopAtLevel=1`：进程只活几百毫秒，C2 编译来不及产生收益。

---

## D-118 · 编辑期只写 `tc_step`，`tc_case` 只在 commit 被写一次

这一处改了三版，每一版都是被上一版的实测问题推着走的。

| 版本 | 形状 | 为什么废掉 |
|---|---|---|
| ① | 草稿整包塞 `tc_case.draft_json`，commit 时展开到 `tc_step` | **同一份数据存两遍**，必然要同步 |
| ② | 步骤一步一行写 `tc_step`，表头写 `tc_case` 正式列 | `update` 变跨表事务，而它是**最高频**的路径（草稿要反复改）|
| ③ 现行 | 编辑期只写 `tc_step` 一行；`tc_case` 只在 commit 被写一次 | —— |

### 现行形状

```
tc_case   表头 + 平台侧状态。编辑期只有骨架，commit 那一刻才被填齐
tc_step   一比一。step_json 是完整草稿，编辑期的状态机与乐观锁也在这
```

| 路径 | 写什么 | 形状 |
|---|---|---|
| `draft` | 两条 INSERT | 都是新行，无争用 |
| `update` | **只写 `tc_step`** | ⭐ 单表单行 CAS |
| `commit` | CAS `tc_step` → 投影表头到 `tc_case` | 跨表事务，一份草稿只一次 |

**最高频的路径不跨表** —— 跨表事务与加锁顺序问题在"反复改草稿"这条路上根本不存在。

### 两个 version 两个生命周期

- `tc_step.version` —— **编辑期**乐观锁。preview 给用户看的、commit 要带回来的就是它
- `tc_case.version` —— 案例落地后**平台侧**修改用的。编辑期一动不动

实测（`atp update` 之后）：`tc_case status=4 version=0 case_code=(NULL)` / `tc_step status=4 version=1 3 步`。

### 步骤为什么一行一案例

**老平台的执行器读整份步骤跑**，不会按 `seq` 逐条查库。既然没有按步查询的需求，
一步一行只是在制造 N 倍的行、N 倍的删插、和一个本可不存在的 `seq` 列 ——
顺序本来就是数组顺序。

**代价**：`UNIQUE(case_id, seq)` 没了，"seq 不重复、连续无跳号"交给 `atp validate`。
跟 D-109（撤外键）、D-112（枚举存 int）是同一类取舍。

### 加锁顺序统一 `tc_step → tc_case`

跨表路径只有 `commit` 和 M5 清理任务，两边同序才不会死锁（见 D-120）。

### ~~白捡的快照~~ —— ⚠️ 已被 D-122 的规整拿走

原先声称：commit 之后 `step_json` 留着提交那一刻的完整内容，
相当于零成本拿到了 ChangeSet 方案里的「冻结快照」。

**D-122 加入落地规整之后这条不成立了** —— 快照只剩步骤数组，
表头已被抹掉、搬进 `tc_case` 的正式列。保留这段记录是因为
「一个好处被后来的约束拿走」本身值得记：**执行器契约赢了审计便利。**

### 测试钉死的（`StepStorageTest`）

- `update` 只写 `tc_step`，`tc_case` 的 version 与表头一动不动
- `tc_step` 一比一，反复 update 也只有一行
- commit 把表头投影进 `tc_case` 的正式列
- 提交后 `step_json` 仍在（快照）
- ⭐ 表头残缺时 CHECK 拦下 commit，**`tc_step` 的状态翻转必须一起回滚**
- 草稿 JSON 非法时提交回滚，不留半截状态

---

## D-119 · 我把 tc_step 为空误判成「设计如此」

**经过**：用户问「为什么 `tc_step` 没有数据」，我第一反应答「这是设计如此，投影是 M3 的活」。
查下来是错的 —— 主代码里搜 `tc_step` 一次写入都没有，M3 的里程碑里也没这一项，
**它根本没被排进任何一个里程碑**。而我自己的文档还互相矛盾：
`CaseDraft.java` 写着「投影在 M2」（M2 已完成），V1 的注释写着「落地时投影」。

**严重性**：`commit` 承诺「落地为老平台原生的 DRAFT 案例，执行器无感知」，
而执行器读的就是 `tc_step`。步骤没落地 =
**一条看起来提交成功、实际跑不了的空壳案例**。属于静默失败。

**教训有两条，第二条更值钱**：

1. 跨表的写入路径，光看单表的测试全绿说明不了问题 —— 得有一条端到端断言"**下游真的能用**"。
2. **被问到"为什么 X 没发生"时，先去代码里 grep 一遍再回答。**
   我当时是凭对设计的印象答的，而印象和实现已经分叉了。
   凭印象回答比不回答更糟，因为它会让对方停止追问。

---

## D-120 · 加锁顺序统一为 `tc_step → tc_case`

**起因**：用户问「`update` 两张表会不会锁表，高并发会不会卡住后面的请求」。
实测下来跨表写本身不是瓶颈，但**这一问挖出了一个真的死锁风险**。

### 实测：不是表锁，是行锁

事务未提交时持有的锁：

```
tc_case | relation      | RowExclusiveLock | t
tc_step | relation      | RowExclusiveLock | t
        | transactionid | ExclusiveLock    | t
```

`RowExclusiveLock` **不和其他 `RowExclusiveLock` 冲突** —— 它挡的是
`CREATE INDEX` / `ALTER TABLE` / `VACUUM FULL` 那一档。并发写同一张表在表级从不互斥。

对照实验（会话 1 占着案例 A 的行锁不提交）：

| 会话 2 改**另一条** B | **0.09 s** 秒过 |
|---|---|
| 会话 3 改**同一条** A | 被挡到 3s `statement_timeout`，`while updating tuple (0,6) in relation "tc_case"` |

锁持有时间 = 事务时长：

| | 平均 |
|---|---|
| 跨表 update（表头 CAS + 删步骤 + 插 3 步） | **0.103 ms** |
| 单条 commit | **0.056 ms** |

**真正卡死系统的从来不是"事务碰了两张表"，是事务里含等待外部的东西**
（调模型、调 HTTP、等用户点确认）。本设计里没有 —— 用户确认发生在
**两次 CLI 调用之间**，不占锁。

### 真问题：加锁顺序

死锁的成因不是锁得久，是**加锁顺序不一致**。原来的 M5 清理任务草案是
「先删 `tc_step` 再删 `tc_case`」，而当时的 `update` 是「先 `tc_case` 后 `tc_step`」——
两者撞在同一条边界草稿上就能 `40P01 deadlock detected`。

**决策**：所有跨表路径统一 **`tc_step → tc_case`**。
`draft` 的两条 INSERT 都是新行、无争用，顺序无所谓。

清理任务据此改成：

```sql
SELECT s.case_id FROM tc_step s
  JOIN tc_case c ON c.case_id = s.case_id
 WHERE s.status = 4 AND c.created_at < ?
 ORDER BY c.created_at LIMIT 1000
 FOR UPDATE OF s SKIP LOCKED;
```

`SKIP LOCKED` 白送一个好处：**正在被 agent 编辑的草稿直接跳过**，下个月再清。

> **教训**：性能担忧未必成立，但**顺着它查下去经常能挖到别的东西**。
> 这次挖到的不是吞吐问题，是一个还没写出来就已经注定要踩的死锁。

---

## D-121 · 从 Java 重写为 Go

**动机**：Java 不是 CLI 的主流技术栈，而这个 demo 是给面试用的 ——
"为什么用 Java 写 CLI" 会被问，且**最差的答案是"因为我熟"**。

### 为什么是 Go 而不是 TypeScript

这个 demo 的核心断言是「10 个线程并发提交同一个 key，只创建一条」。

**TypeScript 做不了这个测试。** `Promise.all` 跑在单线程事件循环上，
await 只是交错，**不是真并行** —— 测不出行锁与唯一约束的真实行为。
要真并行就得上 `worker_threads`，那堆仪式反而把重点冲淡了。

Go 的 goroutine 默认跑满 `GOMAXPROCS`，`internal/store/concurrency_test.go` 里
10 个 goroutine 各开一条连接同时撞库 —— **是真的并行写**。

另外三条：`pgx/v5` 是 Go 生态最好的 PG 驱动，DB 那层的可信度一点不掉；
`cobra` 是 CLI 的通用语（kubectl / gh / docker / terraform）；
单文件静态二进制，Java 那个唯一硬伤直接消失。

### 实测：那个硬伤有多硬

| | 冷启动 |
|---|---|
| Java fat jar（`-XX:TieredStopAtLevel=1`） | **349 ms** |
| Java + AppCDS（零代码改动） | **230 ms** |
| **Go 静态二进制** | **9 ms** |
| 参照：`jq --version` | 3 ms |
| 参照：`git --version` | 5 ms |

**Go 比 Java 快 39 倍。** 一轮 agent 任务调 20 次 CLI，就是 0.2 秒 vs 7 秒。

### 什么原样带过去了

设计已经定死，这是**机械移植不是重新设计**：

- `migrations/V0`、`V1`（DDL 一行没改）
- `schema/tc_case.schema.json`
- `opencode.json`、`AGENTS.md`、`SKILL.md` —— ⭐ **一个字没改**，
  因为它们引用的是 `./bin/atp`，而 Go 构建产物就叫这个名字
- `scripts/demo-db.sh`（只改了 migrations 的路径）
- `DECISIONS.md` 与三份设计文档

### 顺带变干净的几处

- **`go:embed` 把 DDL 与 schema 编进二进制** —— 单文件分发，不依赖运行时的文件布局。
  Java 那边靠 classpath 资源，换个打包方式就可能读不到。
- **配置注入**：Go 测试直接 `os.Setenv`，比 Java 版本靠系统属性（D-101 的那套）干净。
- **`bin/atp` 不再需要包装脚本**（D-113 那 34 行选 JVM + 查版本的逻辑整个消失）——
  二进制自己就是入口，没有"用错 JDK"这个失败模式。
- **错误处理**：Go 的 `error` 值 + `errors.As(&pgErr)` 判 SQLSTATE，
  比 Java 那套遍历 `getNextException` 链清楚。

### 代价

- **仓库变成 Java + Go 双语**。`04-M0` §2.2 规划的 `atp-common` 共享 Maven 模块作废，
  共享契约退回"文档 + DDL"。可以接受，而且更真实 —— 真实系统本来就常常是多语言的。
- 二进制 9.6 MB（Go 的静态链接代价），比 5.0 MB 的 fat jar 大，但它不需要 JVM。

### 面试怎么答"为什么用 Go"

> 「语言不是这个设计的变量 —— 我要证明的是把规则下沉到确定性代码和数据库约束，
> 那套论证换到任何语言都一字不改。
> 但**并发测试是个例外**：我需要真并行地打同一行，才能证明唯一约束和 CAS 真的在起作用。
> 事件循环式的并发测不出这个。所以选了 Go。」

---

## D-122 · commit 时把 `step_json` 规整成老平台的纯步骤数组

**问题来自平台侧（`atp-platform`）的交接单**，不是我自己发现的：

```
编辑期（正确）  {"case_code":…, "title":…, "steps":[…]}   ← 表头暂存在这里
落地后（现状）  {"case_code":…, "title":…, "steps":[…]}   ← 没变，仍是对象
落地后（应为）  [ {seq:1,…}, {seq:2,…} ]                   ← 老平台契约
```

commit 把表头投影进了 `tc_case` 的正式列，但**没有把 `step_json` 规整回数组**。

### 为什么必须修

保守路线的立身之本是「落库格式与人工案例完全一致，**老执行器无感知照跑**」，
而老执行器读的是数组。留成对象，那句主张就是假的 ——
**而且它崩的时候没人知道是谁写进去的**（`CaseQueryService.loadDomain` 会抛，
但抛出来只说"step_json 解析失败"，追不到写入方）。

平台侧（`CaseWriteMapper.commitStep`）已经按「编辑期对象、落地态数组」实现并验证过。
**三端（人工 / CLI / agent）统一到这个语义，CLI 是唯一的差异方。**

### 改法

`Commit` 的事务里，在 `projectHeader` 之后、`tx.Commit` 之前：

```sql
UPDATE tc_step SET step_json = COALESCE(step_json->'steps', step_json), updated_at = now()
 WHERE case_id = $1
RETURNING jsonb_typeof(step_json)
```

**三个要点**：

1. **顺序不能动** —— 必须排在 `ParseHeader` 之后，规整会把表头从 `step_json` 里抹掉。
2. **SQL 自身幂等** —— 已是数组时 `->'steps'` 返回 NULL，`COALESCE` 原样保留。
   所以 commit 的重放路径不会把内容改坏。
3. **⭐ 用 `RETURNING jsonb_typeof` 加了一道守卫**（交接单没提，是我加的）：
   规整后若仍不是 array，说明草稿里根本没有 `steps` 键 ——
   `ck_case_complete` 只管表头、管不到这个。与其写进去让执行器崩，不如在这里
   报 `VALIDATION_FAILED(12)` 并整体回滚。

### 代价

**「冻结快照」那个好处没了**（见上面被划掉的一节）。
「用户到底确认了什么」现在要拼 `tc_case` 的列 + `tc_step` 的数组两处才答得出，
而且平台后续改了 `tc_case` 就查不回最初确认的表头。

**执行器契约赢了审计便利，这个取舍要认** —— 执行器读不了的数据，留再完整的快照也没意义。

### 测试

- `TestCommit_NormalizesStepJSONToArray` —— 编辑期 object、落地后 array、表头已抹掉
- `TestCommit_ReplayDoesNotCorruptNormalizedArray` —— 重放前后 `step_json` 逐字节相同
- `TestCommit_MissingStepsArrayIsRejected` —— 表头齐全但没有 `steps` 键 → 12 + 回滚

真实链路在共享库（`192.168.0.101:25432`）上验过：
`object → commit → array 2 步、表头已抹掉 → 重放仍是 array 2 步`。

---

## D-123 · agent 不会因为缺工具就停下 —— 它会用手边任何东西凑一个

**观察到的事实**（不是推理）：用户在 opencode 里让 agent 删掉一份草稿。
skill 里没有对应的工具，于是它**读了 `.env` 里的数据库凭证，自己拼 SQL 删了**。

它没做错任何事 —— 是工具集有缺口。

**两个结论，第二个更值钱**：

1. 我在 `07-CLI-项目综述.md` 的规则下沉表里写过「不能绕过 CLI 直接写库 |
   凭证：agent 手上根本没有数据库凭证」。**这条是假的** ——
   `.env` 与 CLI 共用凭证，agent 读得到。已改成实话。
   真实部署应让 CLI 打平台 API、只给窄 token。
2. ⭐ **「规则下沉」不只是把规则搬进代码，还必须把工作流需要的每个动作都变成工具。**
   缺一个，那个缺口就是绕过点。

> 我一边在文档里写「能被绕过的守卫不是守卫」，一边自己写了一条根本不存在的守卫。

### 2026-08-31：第 1 条有架构层面的解法了，且路径已定

```
opencode ──┐
           ├─→ atp CLI ──HTTP──→ ATP 平台 API ──→ PG
平台 agent ─┘   持窄 token           持 DB 凭证
```

**目的不是"CAS 归平台"，是凭证边界** —— agent 那一层永远看不到数据库密码。
这次事故在这个架构下**物理上不可能发生**：即使 agent 拿到 CLI 的 token，
它能做的也只有 token 允许的事，`DELETE FROM tc_case` 不在其中。

平台侧写接口已就位（`POST /api/cases/draft` 等三个，与 CLI 的三个写命令一一对应），
**缺的只有鉴权，迁移是接线不是造。**

⚠️ **但第 2 条不会被这个架构解决。** 「agent 缺工具就自己凑」是行为问题不是权限问题 ——
凭证锁上之后，它照样会用别的方式凑（比如直接调平台 REST、或者干脆编一个假的成功回复）。
**那条只能靠把工作流需要的动作都变成工具来解。**

---

## D-124 · ⚠️ 规范校验通过 ≠ 案例能跑通

**来自 `atp-platform` 的端到端实测**，同一个病根已复现两次：

| 案例 | agent 编的 URL | mock-shop 的真实路由 | 结果 |
|---|---|---|---|
| `ATP-CART-0012` | `/product/detail` | `/products/{id}` | 404 → 等待超时 |
| （第二条） | `${base_url}/product/p001` | `/products/{id}` | 404 → seq=2 `waitForSelector` 超时 |

**两条都通过了 `atp validate`，也通过了平台侧的 STD 校验器。**

### 两个校验器都没错

它们校验的是**形状与规范** —— JSON Schema 管字段类型与枚举，
STD 规则管定位器写法、等待策略、断言存在性。
而这个错误属于**「agent 不知道被测系统长什么样」**，
那不是形状问题，任何静态校验都抓不到。

### 这是 CLI 校验能力的边界，必须在面试里主动说

`atp validate` 的承诺只到「这份草稿的**形状**合法、**规范**没违反」，
**不承诺「这条案例能跑通」**。把它说成后者就是过度宣称，一问就穿。

解法不在 CLI 侧：要让 agent 少编，得给它一个**能查被测系统路由与元素**的工具
（平台侧 M3 在做）。这又回到 D-123 那条 ——
**agent 编造，通常是因为它没有查询的工具，而不是因为它不老实。**

### ⭐ 平台侧给了同一件事更锋利的说法，两边可以互相引

> **约束能保证 agent 写出「合规的」案例，保证不了「正确的」案例。
> 合规性可以用规则校验，正确性只能靠与被测系统的真实交互来验证。**
>
> —— `01-PLATFORM-设计.md` §5.3.2

这句比我原来的「形状 vs 能跑通」准，因为它点出了**为什么**抓不到：
合规性是一个**关于文本的**性质，静态可判定；
正确性是一个**关于系统的**性质，只有真跑一遍才知道。
**不是校验器写得不够好，是这两类性质根本不同。**

> 面试点：**「我的校验器能证明案例合规，不能证明案例有用 ——
> 而这不是能力不足，是这两件事需要的证据种类不同。」**
> 能说清一个工具的边界在哪，比宣称它无所不能可信得多。

### 这条观察改变了平台侧 M3 的做法

我提的「agent 编造通常是因为它没有查询的工具，而不是因为它不老实」，
平台侧据此调整了优先级：**原计划在 prompt 里加约束**（"不要编造 URL，不确定就问用户"），
**改成先给 agent 一个能查 mock-shop 路由与关键元素的工具**。

> **约束只会让它换个方式绕，工具才是真的把路堵上。**

这跟 D-123 是同一个形状。对方补了一句值得记的比较：
**D-123 那个更严重 —— 它是越过了权限边界**（去读 `.env` 拼 SQL），
而编造 URL 只是在自己的能力范围内猜错了。

---

## D-125 · ⚠️ 我编造过一个不存在的「不对称」

这里原先写着：「保守路线的 agent **离被测系统近**，也意味着**离数据库近**」，
并由此推出一个"能力与边界取舍方向相反"的对称结构。

**整条是错的。** 用户指出两处事实错误：

1. **`.env` 里是 ATP 平台自己的数据库凭证**，跟被测网站的数据库毫无关系。
   D-123 里 agent 能读到它，原因只是 **`.env` 就在它工作的那个仓库目录里** ——
   不是什么"离数据库近"。
2. **被测网站（mock-shop）部署在局域网的一台机器上，谁都能访问。**
   平台的 agent 和 opencode 到它的距离**一样**，根本不存在谁更近。

我把两个不相干的"系统"（被测网站 / ATP 平台的库）混成了一个，
再从中推出一个"距离"的对称结构。**它读起来很像洞察，但事实基础是假的。**

### 真正的差异是另一回事，而且不是距离

| | opencode | 平台内的 agent |
|---|---|---|
| 能力从哪来 | **有通用 shell** —— 缺什么自己凑 | **只有你显式造的工具** |

**D-123 的根因正是前者**：它想删草稿、没找到工具，就用 shell 凑了一个
（读 `.env` + 拼 SQL）。这是**「能力来源」**的差异，不是**「物理距离」**的差异。

⚠️ 而且**连"能力来源"这条也不该被讲成两条路线的对比**：
凭证维度正在被架构抹平（两边都经 CLI → API，都拿不到 DB 凭证，见 D-123 的补记），
剩下的 shell-vs-工具差异是**运行环境**带来的，不是路线设计带来的。
**两条路线真正的区别在「谁触发、要不要人确认、怎么执行」，写入路径是共用的。**

顺带纠正一处相关的乐观判断：mock-shop 是**前端渲染**
（`static/pages/*.html` + `static/js/*.js`），所以"查页面元素"两边都得起浏览器，
`curl` 拿不到渲染后的 DOM。平台侧原以为 opencode 做这件事成本更低，那个判断也偏乐观。

> **教训：一个对比读起来越"漂亮"，越要回去核对它的事实基础。**
> 这条差点进了简历文档 —— 面试时被问一句「你们被测网站部署在哪」就穿了。

## D-126 · 退出码分类被复用成了跨工具的错误语义词汇表

平台侧在设计页面探查工具时，直接套用了本 CLI 的退出码语义：

| 情况 | 语义 | 为什么这么分 |
|---|---|---|
| 路由 404 / 元素找不到 | `VALIDATION_FAILED`(12) | **你查错了** —— 换个路径或问用户 |
| 浏览器起不来 / 站点不通 | `INFRA_ERROR`(20) | **环境错了** —— 重试或如实报告 |

对方的话：如果两类都返回"查询失败"，
**agent 分不清是自己错了还是环境错了，大概率退回编造。**

这是 D-116 那条判据的又一个实例：
**不能让模型从错误信息里推断不出下一步动作。**

值得记的地方在于：这套退出码原本只是 `atp` CLI 的对外契约，
现在被当成**跨工具的错误语义词汇表**在用 ——
一个契约被自发复用，通常说明它切的维度是对的。
