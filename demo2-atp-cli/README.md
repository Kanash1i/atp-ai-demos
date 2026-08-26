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

状态机：`AI_DRAFT --commit--> DRAFT`（落地成老平台原生的草稿状态，执行器无感知）

## 跑测试

```bash
export DOCKER_HOST=unix:///var/run/docker.sock     # ~/.docker 的 currentContext 是 remote 时必须
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn test
```

12 个用例，起真 MySQL 8.4（Testcontainers），**先建老表再跑改造脚本** ——
V1 能不能在老平台的形状上执行得下去，本身就被测到了。

| 测试类 | 锁定的不变式 |
|---|---|
| `ConcurrentDraftTest` | 10 线程同一 UUID → 只插 1 行，其余 9 个 `replayed=true` 且**退出码 0** |
| `ConcurrentCommitTest` | 10 线程同一 id+version → 1 真提交 + 9 重放；落地状态是普通 `DRAFT` |
| `TocTouTest` | ⭐ 确认后内容被改过 → `commit` 必须报 `VERSION_CONFLICT` |
| `CommitGuardTest` | `ck_case_complete` 拦残缺案例；退出码取值契约 |

### 变异检验（这些测试有牙齿）

把 `CaseStore.commit` 的 `AND version = ?` 从 WHERE 里去掉，
`TocTouTest.staleVersionRejected` 立刻红 —— 已实测。

## 已知坑

`Could not find a valid Docker environment` 十有八九**不是**没有 daemon，
而是 docker-java 谈判到 API 1.32 而 Engine 29 最低要 1.40。见 `DECISIONS.md` **D-101**。
