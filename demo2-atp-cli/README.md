# demo2-atp-cli · ATP 案例编写 CLI

把幂等键做成老平台案例表的主键，用**唯一约束 + 一条 CAS UPDATE** 当并发仲裁点。

- 设计与答辩口径：`../05-CLI-并发幂等答辩稿.md`
- 命令表 / opencode 接入 / 里程碑：`../06-atp-cli-设计.md`
- 实现过程中的决策与踩坑：`DECISIONS.md`

## 技术栈

**Go 1.25 + cobra + pgx/v5**，单文件静态二进制。

| | |
|---|---|
| CLI 框架 | `spf13/cobra`（kubectl / gh / docker / terraform 的同款）|
| 数据库 | `jackc/pgx/v5`，**每次调用一条连接、不用连接池** —— 进程只活几毫秒 |
| JSON Schema | `santhosh-tekuri/jsonschema/v6` |
| 测试 | 标准库 `testing` + `testcontainers-go`（起真 PostgreSQL 17）|

⭐ **为什么不是 Java**：CLI 被 agent 高频反复调用，冷启动是真实成本。
同一套设计的 Java 实现实测冷启动 **349 ms**，Go 是 **9 ms** —— 差 39 倍。
一轮 agent 任务调 20 次，就是 7 秒 vs 0.2 秒。

⭐ **为什么不是 TypeScript**：这个 demo 的核心断言是「10 个线程并发提交同一个 key，
只创建一条」。事件循环上的 `Promise.all` 是并发不是并行，**测不出行锁与唯一约束的真实行为**；
Go 的 goroutine 默认跑满 `GOMAXPROCS`，是真的多条连接同时撞库。

## 当前进度

M1（状态机与并发正确性）/ M2（命令层与退出码契约）/ M4（opencode 接入）已完成。
M3（完整规则引擎）与 M5（XXL-JOB 清理）**刻意押后** —— 演示脚本不依赖它们，
而知识侧的消融表才是核心交付物。见 `../CLAUDE.md` 的硬纪律。

```bash
make build          # 构建 bin/atp
make db-up          # 起演示库 + 跑迁移 + 写 .env
make test           # 27 个用例，起真 PostgreSQL 17

./bin/atp modules -p ECSHOP
./bin/atp draft --json --id "$(uuidgen)" -p PC_WEB -t "购物车结算"
./bin/atp validate -f draft.json
./bin/atp update  <id> --version 0 -f draft.json
./bin/atp preview <id>
./bin/atp commit  <id> --version 1
```

⚠️ 配置取不到会 fail fast，**不给默认值** —— 默认值会把「配置漏了」变成「连到了错的库」。

## 结构

| | |
|---|---|
| `migrations/V0__baseline_legacy.sql` | 老平台现状基线（仅测试用） |
| `migrations/V1__ai_draft_state.sql` | ⭐ 真正要执行到老平台的改造 |
| `internal/store/` | ⭐ **全项目唯一持有 SQL 的包**（`arch_test.go` 机械检查） |
| `internal/rule/` | 纯本地规则：零网络、零 DB、零模型调用 |
| `internal/cli/` | cobra 命令层，很薄 |
| `assets.go` | 把 DDL 与 schema 嵌进二进制（`go:embed`），单文件分发 |

数据落点：
- **编辑期只写 `tc_step` 一行**（`step_json` = 完整草稿，状态机与乐观锁也在这）
- **`tc_case` 只在 commit 那一刻被写一次**（表头投影 + 翻状态）

最高频的路径（反复改草稿）因此是单表单行 CAS，不跨表。见 `DECISIONS.md` **D-118**。

状态机：`AI_DRAFT --commit--> DRAFT`（落地成老平台原生的草稿状态，执行器无感知）

## 测试

27 个用例，起真 **PostgreSQL 17**（testcontainers-go），**先建老表再跑改造脚本** ——
V1 能不能在老平台的形状上执行得下去，本身就被测到了。

| 文件 | 锁定的不变式 |
|---|---|
| `internal/store/concurrency_test.go` | ⭐ **10 个 goroutine 真并行**打同一个 key → 只创建一条，其余 `replayed` 且退出码 0 |
| `internal/store/toctou_test.go` | ⭐ 确认后内容被改过 → commit 必须报 `VERSION_CONFLICT` |
| `internal/store/editing_test.go` | `update` 只写 `tc_step`；commit 投影表头；CHECK 拦下时两张表一起回滚 |
| `internal/store/guard_test.go` | 退出码取值契约；提交后被改 → `STATE_CONFLICT` |
| `internal/rule/validate_test.go` | ⭐ 必填缺失(14) 与值非法(12) 必须分开；缺信息优先 |
| `internal/cli/e2e_test.go` | 完整七步；退出码 10/11/12/14；重放退出码为 0 |
| `arch_test.go` | ⭐ 架构不变式：SQL 只出现在 `internal/store` |

### 变异检验（这些测试有牙齿）

把 `CaseStore.Commit` 的 `AND version = $4` 改成 `>= $4`（放行过期版本），
`TestTocTou_StaleVersionRejected` 立刻红 —— 已实测。

## 用 opencode 验证（端到端演示）

### 一次性准备

```bash
# 1. 起中间件 + 应用全部迁移 + 把连接串写进仓库根 .env（幂等，可重复跑）
#    ⚠️ PG 与 Redis 跑在台式机 192.168.0.101 上，两条路线共用 —— 见 infra/compose.yaml
./infra/infra.sh up

# 2. 打包 fat jar
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -pl . package -DskipTests

# 3. 自检：不带任何环境变量也应该能连上（走 .env）
./bin/atp modules -p ECSHOP
```

⚠️ 如果 `./bin/atp` 报 `[INFRA_ERROR] 需要 JDK 21+`，说明 shell 里的 `JAVA_HOME`
指向别的版本（sdkman 常见）。用 `export ATP_JAVA=/usr/lib/jvm/java-21-openjdk-amd64/bin/java` 指定。

### 起 opencode

```bash
cd demo2-atp-cli      # ⚠️ 必须在这个目录，opencode 才会读到本目录的 opencode.json 与 .opencode/skills/
opencode
```

然后输入：

```
帮我加一个购物车结算的 Web 测试案例
```

### 该看到什么（按顺序）

| # | agent 的动作 | 要盯的点 |
|---|---|---|
| 1 | `./bin/atp modules --json` | **左移**：先问取值范围，不编造 `module_id` |
| 2 | `./bin/atp schema` | 生成前就知道该产出什么形状 |
| 3 | `uuidgen` → `./bin/atp draft --id ...` | 幂等键此刻已存在，且由客户端生成 |
| 4 | 写 `draft.json` → `./bin/atp validate` | 纯本地、毫秒级、**零模型调用** |
| 5 | `./bin/atp update --version 0` | CAS，`version` 0→1 |
| 6 | `./bin/atp preview` | 展示的是**库里的行**，不是本地文件 |
| 7 | `./bin/atp commit --version 1` | ⭐ **opencode 弹出权限确认框** |

⭐ **第 7 步是这个 demo 最值得讲的一处**：`opencode.json` 里只有 `commit` 是 `"ask"`，
其余全 `"allow"`。也就是说——

> **「用户确认」不是提示词里的一句话，是宿主强制的权限门。** 不需要另做 UI。

### 手动制造并发冲突（全场最高光，一定要演）

在 opencode **等你点确认的时候**，另开一个终端：

```bash
cd demo2-atp-cli
# 偷偷改一版内容，version 1 → 2
sed 's/购物车结算/购物车结算（被偷改）/' draft.json > draft2.json
./bin/atp update <caseId> --version 1 -f draft2.json
```

回到 opencode 点确认，`commit --version 1` 会被拒：

```
[VERSION_CONFLICT] 版本不一致：库中 version=2，你手上是 1。
内容在你确认之后被改过，请重新 show/preview 再确认
退出码 10
```

**用户确认的那一份和最终落库的那一份必须是同一份 —— 这是整套设计存在的理由。**

### 验证幂等重放

```bash
./bin/atp commit <caseId> --version <N> --json   # 第一次：replayed=false
./bin/atp commit <caseId> --version <N> --json   # 第二次：replayed=true，退出码仍是 0
echo $?                                          # ← 必须是 0，不是错误码
../infra/infra.sh psql                        # 进库看：只有一行，status=DRAFT
```

### 收摊

```bash
../infra/infra.sh down
```
