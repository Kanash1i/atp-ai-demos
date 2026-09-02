---
name: atp-case-authoring
description: 用 atp CLI 根据自然语言创建符合 ATP 平台标准的自动化测试案例。用户要求新增、生成、编写或保存测试案例时使用。
compatibility: opencode
metadata:
  demo: "atp-cli"
---

# 测试案例创建流程

## 流程

1. 确认**执行平台**（`IOS` / `ANDROID` / `PC_WEB`），不明确就先问用户。
2. `./bin/atp modules --json` —— 拿到项目与模块字典。
   **禁止编造 module_id**：只能用字典里真实存在的值。
3. `./bin/atp schema` —— 拿到草稿的目标结构、必填字段、枚举取值。
4. `ID=$(uuidgen)`，然后 `./bin/atp draft --json --id "$ID" -p <平台> -t "<标题>"`。
   ⚠️ 命令失败需要重试时**复用同一个 `$ID`**，不要重新生成 —— 它是幂等键。
5. 按 schema 写出 `draft.json`，先 `./bin/atp validate -f draft.json --json` 本地校验。
6. `./bin/atp update --json "$ID" --version <N> -f draft.json` 写入。
7. `./bin/atp preview "$ID"` —— 把输出**原样**展示给用户，等用户明确说"确认"。
8. `./bin/atp commit "$ID" --version <N>` —— 只传 id 和 version，不传内容。

## 退出码

| 码 | 你该做什么 |
|---|---|
| 0 | 成功（含幂等重放），继续 |
| 10 | 版本冲突：内容在确认后被改过。重新 `show` → `preview` → 让用户重新确认 |
| 11 | 不存在：重新 `draft` |
| 12 | 值不合法：读 `violations` **自己改** |
| 13 | 状态冲突：停下，问用户 |
| 14 | 缺必填信息：**去问用户**，不要猜 |
| 20 | 基础设施故障：检查 `.env` 与数据库 |

## 约束

- 不得把自己的推断描述成用户的明确输入。
- 用户没有明确说"确认"之前，不要执行 `commit`。
