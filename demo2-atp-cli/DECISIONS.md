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

## D-103 · 不继承 `spring-boot-starter-parent`

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

## D-118 · 删掉 `tc_case.draft_json`，步骤只住 `tc_step`

**原方案**（已废）：`tc_case` 上加一列 `draft_json JSONB` 存整份草稿，
`commit` 时再展开投影到 `tc_step`。

**问题**：**同一份数据存两遍。** `tc_step` 本来就是步骤的正位 ——
老平台的人工案例一直存在那儿。父表再存一个 blob，两边就必须同步，
而"必须同步的两份数据"迟早不一致。

**决策**：`tc_case` 只留基本信息（表头字段），步骤在 `atp update` 时直接写进 `tc_step`。
不管是老平台的人工案例还是 AI 写的案例，走同一条路。

### 连带：原子性的压力从 `commit` 移到 `update`

| | 写什么 | 事务要求 |
|---|---|---|
| `update` | 表头进 `tc_case`，步骤**整批替换** `tc_step` | ⭐ 跨两张表，**必须一个事务** |
| `commit` | 只翻状态 | 单条 UPDATE，天然原子 |

**`commit` 反而回归纯粹** —— 它现在真的只收 `id` 和 `version`，
不带内容也不搬运数据。这正是原设计想要的样子，之前的投影方案反而把它弄脏了。

### 全删再全插，不做 diff

草稿的步骤量级是个位数，diff 的复杂度换不来收益。全量替换的语义简单得多：
**库里的步骤永远等于最后一次 `update` 传进来的那一份**，没有中间态可推理。

### 「确认的和提交的是同一份」还成立吗

成立。`update` 是**唯一**写 `tc_step` 的路径，而它每次都 CAS 掉 `tc_case.version` ——
version 依然罩得住「表头 + 步骤」这个整体。谁动了步骤，version 就跳，commit 就失败。

前提和 D-109（不建外键）、D-112（枚举存 int）是同一条：**所有写入都走 CLI 或平台代码。**

### 测试钉死的四条（`StepStorageTest`）

- `update` 写进 `tc_step`，seq 不变
- 再次 `update` 是全量替换，旧步骤不残留
- **CAS 失败时步骤一步都不能动**（拿过期 version 写入，旧步骤原封不动）
- ⭐ **步骤写入失败必须连表头一起回滚**（seq 重复撞 `uk_step_case_seq` → 标题与 version 都不变）

另外 `seq` 重复被映射成 `VALIDATION_FAILED(12)` 而不是 `INFRA_ERROR(20)` ——
那是内容问题，agent 自己能改，别让它去傻等重试。

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
