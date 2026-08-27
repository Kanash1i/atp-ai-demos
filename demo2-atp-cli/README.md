# demo2-atp-cli · ATP 案例编写 CLI

把幂等键做成老平台案例表的主键，用**唯一约束 + 一条 CAS UPDATE** 当并发仲裁点。

- 设计与答辩口径：`../05-CLI-并发幂等答辩稿.md`
- 命令表 / opencode 接入 / 里程碑：`../06-atp-cli-设计.md`
- 实现过程中的决策与踩坑：`DECISIONS.md`

## 当前进度：M1 完成

M1 交付的是**状态机和并发正确性**，还没有命令行外壳（M2）。

| | |
|---|---|
| `src/main/resources/db/migration/V0__baseline_legacy.sql` | 老平台现状基线（仅测试用） |
| `src/main/resources/db/migration/V1__ai_draft_state.sql` | ⭐ 真正要执行到老平台的改造 |
| `src/main/java/.../store/CaseStore.java` | ⭐ **全项目唯一持有 SQL 的类** |
| `src/main/java/.../model/{CaseStatus,CaseType,Priority}.java` | 枚举语义（DB 只存 SMALLINT，见 D-112）|
| `src/main/java/.../{model,rule}/` 里的 `Action` / `LocatorLinter` / `DiagnosticCodes` | 从已删除的 MCP 废案迁入，M3 用（见 D-111）|

状态机：`AI_DRAFT --commit--> DRAFT`（落地成老平台原生的草稿状态，执行器无感知）

## 跑测试

```bash
export DOCKER_HOST=unix:///var/run/docker.sock     # ~/.docker 的 currentContext 是 remote 时必须
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn test
```

63 个用例（18 个状态机 + 45 个 Action 契约表），起真 **PostgreSQL 17**（Testcontainers），
**先建老表再跑改造脚本** —— V1 能不能在老平台的形状上执行得下去，本身就被测到了。

| 测试类 | 锁定的不变式 |
|---|---|
| `ConcurrentDraftTest` | 10 线程同一 UUID → 只插 1 行，其余 9 个 `replayed=true` 且**退出码 0** |
| `ConcurrentCommitTest` | 10 线程同一 id+version → 1 真提交 + 9 重放；落地状态是普通 `DRAFT` |
| `TocTouTest` | ⭐ 确认后内容被改过 → `commit` 必须报 `VERSION_CONFLICT` |
| `CommitGuardTest` | `ck_case_complete` 拦残缺案例；退出码取值契约 |
| `SchemaShapeTest` | 多条 NULL `case_code` 并存；无级联的孤儿代价；`seq` 唯一；项目→模块→案例链路 |
| `ActionContractTableTest` | Action 枚举与 locator/input/expected 的契约表（`00-SHARED-CONTEXT` §1.3）|

### 变异检验（这些测试有牙齿）

把 `CaseStore.commit` 的 `AND version = ?` 改成 `AND version >= ?`（放行过期版本），
`TocTouTest.staleVersionRejected` 立刻红 —— 已实测。

## 已知坑

`Could not find a valid Docker environment` 十有八九**不是**没有 daemon，
而是 docker-java 谈判到 API 1.32 而 Engine 29 最低要 1.40。见 `DECISIONS.md` **D-101**。
