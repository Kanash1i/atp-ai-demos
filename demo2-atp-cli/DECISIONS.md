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

⚠️ MySQL 的 ENUM 按定义顺序编号，**新值只能追加在末尾**，在中间插值会重排既有行的存储值。

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

⚠️ **前提是 MySQL 8.0.16+**。5.7 上这条约束是假的，见 **D-108**。
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

⚠️ 已知未优化：UUID 作 InnoDB 聚簇主键会随机插入、页分裂。
AI 草稿日增百级，暂不优化；要优化就改自增 BIGINT 聚簇 + UUID 唯一二级索引。

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
`tc_case.case_id`，而 MySQL **不允许直接修改被外键引用的列类型**。
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

## D-108 · ⚠️ 未决：`CHECK` 约束在 MySQL 5.7 上是静默失效的

`00-SHARED-CONTEXT.md` §1.1 写明老平台是 **Java 8 + Spring 4 + MySQL 5.7**。

**MySQL 5.7 会解析 `CHECK` 子句然后直接丢弃，不报错、不告警、不生效。**

**已实测**（`mysql:5.7.44` vs `mysql:8.4`，同一份 V0+V1，同一条 SQL）：

```
5.7  应用 V1                       → 无任何输出，无报错
5.7  SHOW CREATE TABLE tc_case     → 找不到 ck_case_complete，约束根本没建
5.7  INSERT 残缺草稿 + commit UPDATE → 成功，得到一条 status=DRAFT 且
                                       case_code/module_id/priority/author 全 NULL 的案例
8.4  同一条 commit UPDATE           → ERROR 3819 (HY000): Check constraint
                                       'ck_case_complete' is violated
```

**最危险的地方是我们的测试发现不了**：Testcontainers 起的是 8.4，
`CommitGuardTest.incompleteDraftBlockedByCheckConstraint` 一直是绿的。
**只有在真实平台上才会炸，而且炸的时候没有报错，只是数据脏了** ——
执行器会读到一条必填字段全空的 DRAFT 案例。

（`JSON` 类型 5.7.8+ 有，`DATETIME(3)` 5.7 也有，只有 `CHECK` 是断的。）

**三个选项，待定**：

1. 设定老平台的 DB 已升到 8.0（真实企业里很常见，也最省事）
2. 改用 `BEFORE UPDATE` 触发器 —— 5.7 可用，但触发器难测、难看、易被 DBA 删
3. 退回应用层校验 —— 但这就推翻了"能用约束表达的不要用代码表达"这条论点

**在面试里这是加分项不是减分项**：说得出"我知道这条约束在 5.7 上是假的、
而且我的测试环境掩盖了它"，比默认它一定生效强得多 ——
**它证明你会区分「我验证过的环境」和「它真正要跑的环境」。**

⚠️ 若最终选 (a)（DB 升 8.0），要在 `00-SHARED-CONTEXT.md` §1.1 里把
"MySQL 5.7" 改掉，否则契约文档和实现继续互相矛盾。

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
