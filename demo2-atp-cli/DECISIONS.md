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

**这与 demo1 / demo2-atp-mcp 的技术栈刻意不同，不是疏漏。**

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

⚠️ **共享契约漂移**：`00-SHARED-CONTEXT.md` §1.2 仍写着 `browser` / `timeout_sec`，
`demo2-atp-mcp/src/main/resources/schema/tc_case.schema.json` 的 `required` 里也还有它们。
两处都需要同步，**尚未做**。

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
step_json JSON                步骤内容
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
| 枚举扩展 | 只能追加末尾 | `ALTER TYPE ADD VALUE`，可 BEFORE/AFTER 插入；**但新值不能在同一事务用** |
| `DELETE ... LIMIT n` | 支持 | **不支持**，要 `WHERE ctid IN (SELECT ctid ... LIMIT n)` 或先 SELECT 出 id |
| `ON UPDATE CURRENT_TIMESTAMP` | 支持 | **没有**，写入方显式赋值或挂触发器 |
| UUID 主键 | InnoDB 索引组织表 → 随机插入页分裂 | 堆表 → **不存在这个问题** |
| JSON | `JSON` | `JSONB`（可 GIN 索引） |
| 错误判定 | errorCode 1062 / 3819 | **SQLSTATE 23505 / 23514**（SQL 标准，换库不用改） |
| 枚举列传参 | 直接 setString | 需要显式转型 `?::tc_case_type` |

**顺带的代码改善**：错误判定从厂商 `errorCode` 改成 **SQLSTATE**，
`23505`（unique_violation）/ `23514`（check_violation）是 SQL 标准的一部分，
这段逻辑现在换库也不用动。**被逼着做的移植，反而让代码更干净了。**

**⚠️ 新发现的坑**：`ALTER TYPE ... ADD VALUE` 必须单独提交（新值不能在同一事务使用），
所以 **V1 整体不是原子的** —— 第一条成功、后面失败会停在中间态。
已改成 `ADD VALUE IF NOT EXISTS` 让脚本可重跑。
> 一整套设计都在讲幂等，结果自己的迁移脚本一开始不幂等。这个自嘲在面试里效果很好。
