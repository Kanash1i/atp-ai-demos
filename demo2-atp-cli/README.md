# demo2-atp-cli · ATP 案例编写 CLI

把幂等键做成老平台案例表的主键，用**唯一约束 + 一条 CAS UPDATE** 当并发仲裁点。

- 设计与答辩口径：`../05-CLI-并发幂等答辩稿.md`
- 命令表 / opencode 接入 / 里程碑：`../06-atp-cli-设计.md`
- 实现过程中的决策与踩坑：`DECISIONS.md`

## 当前进度：M2 完成

M1 = 状态机与并发正确性；M2 = 命令行外壳、退出码契约、`--json` 信封、本地校验。

```bash
mvn -pl demo2-atp-cli package -DskipTests
export ATP_DB_URL=jdbc:postgresql://127.0.0.1:5432/atp ATP_DB_USER=atp ATP_DB_PASSWORD=...
./bin/atp modules -p ECSHOP
./bin/atp draft --json --id "$(uuidgen)" -p PC_WEB -t "购物车结算"
./bin/atp validate -f draft.json
./bin/atp update  <id> --version 0 -f draft.json
./bin/atp preview <id>
./bin/atp commit  <id> --version 1
```

⚠️ 配置取不到会 fail fast，**不给默认值** —— 默认值会把「配置漏了」变成「连到了错的库」。

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

69 个用例（18 状态机 + 5 CLI 端到端 + 1 架构不变式 + 45 Action 契约表），起真 **PostgreSQL 17**（Testcontainers），
**先建老表再跑改造脚本** —— V1 能不能在老平台的形状上执行得下去，本身就被测到了。

| 测试类 | 锁定的不变式 |
|---|---|
| `ConcurrentDraftTest` | 10 线程同一 UUID → 只插 1 行，其余 9 个 `replayed=true` 且**退出码 0** |
| `ConcurrentCommitTest` | 10 线程同一 id+version → 1 真提交 + 9 重放；落地状态是普通 `DRAFT` |
| `TocTouTest` | ⭐ 确认后内容被改过 → `commit` 必须报 `VERSION_CONFLICT` |
| `CommitGuardTest` | `ck_case_complete` 拦残缺案例；退出码取值契约 |
| `SchemaShapeTest` | 多条 NULL `case_code` 并存；无级联的孤儿代价；`seq` 唯一；项目→模块→案例链路 |
| `ActionContractTableTest` | Action 枚举与 locator/input/expected 的契约表（`00-SHARED-CONTEXT` §1.3）|
| `CliEndToEndTest` | 七步全流程；退出码 10/11/12/14 的契约；幂等重放退出码为 0 |
| `SqlContainmentTest` | ⭐ 架构不变式：SQL 只出现在 `store` 包（机械检查，不靠约定）|

### 变异检验（这些测试有牙齿）

把 `CaseStore.commit` 的 `AND version = ?` 改成 `AND version >= ?`（放行过期版本），
`TocTouTest.staleVersionRejected` 立刻红 —— 已实测。

## 已知坑

`Could not find a valid Docker environment` 十有八九**不是**没有 daemon，
而是 docker-java 谈判到 API 1.32 而 Engine 29 最低要 1.40。见 `DECISIONS.md` **D-101**。
