# atp CLI 演示环境

这个目录是 ATP 案例编写 CLI 的 demo。**编写测试案例时走 `atp-case-authoring` 技能。**

- CLI 入口是 `./bin/atp`（不在 PATH 上，必须带 `./bin/` 前缀）
- 数据库连接从仓库根 `.env` 读（`ATP_DB_URL` / `ATP_DB_USER` / `ATP_DB_PASSWORD`）
- 所有 `atp` 命令都**不调用模型**。模型是你调的，CLI 只做确定性的校验与落库
