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
