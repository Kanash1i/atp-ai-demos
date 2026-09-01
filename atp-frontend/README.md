# atp-frontend

ATP 平台的前端。React 19 + TypeScript + Vite + Tailwind v4，五个面板对应
`02-前端契约.md` 里的五组接口。

```bash
npm install
npm run dev        # http://localhost:5273
```

后端要单独起（见仓库根 README）：

```bash
./infra/infra.sh up
cd atp-platform && ./scripts/run-web.sh --seed
curl -s localhost:8080/api/health
```

后端没起也能开 —— 每个面板会显示「连不上后端」并把启动命令写在页面上，
而不是白屏或者假装有数据。

> 未做的事、推迟的理由、以及**部署前必须处理的四件事**，见 [`BACKLOG.md`](./BACKLOG.md)。

## 路由

| 路径 | 内容 |
|---|---|
| `/` | Landing。三语循环打字机、滚动淡入、「立即体验」进 dashboard |
| `/login` | 登录页。三个演示账号一键填用户名（**不填口令**） |
| `/dashboard/cases` | 案例中心：项目 pill → 模块树 → 案例详情（步骤表 + 规范校验） |
| `/dashboard/runs` | 执行状态：四张统计卡、执行中批次（真派发 + 2 秒轮询）、最近执行、失败详情抽屉（播真录像） |
| `/dashboard/agent` | 智能 Agent 助手：SSE 流式对话、路由结论、思考过程实时流 |
| `/dashboard/datasets` | 数据集中心（**M3 才有后端**，当前是静态稿） |
| `/dashboard/approvals` | 审批中心：三类审批卡片、diff、批准/退回/挂起 |

五个面板各有自己的 URL，演示时可以直接把某一屏的链接发出去。

## 配置

只读仓库约定的环境变量，代码里不出现硬编码 URL / IP：

```bash
cp .env.example .env.local
```

| 变量 | 作用 |
|---|---|
| `VITE_API_ORIGIN` | dev 时 vite 把 `/api` 代理到这里，默认 `http://localhost:8080` |
| `VITE_API_BASE` | 前端请求前缀，默认空 = 同源 `/api`。只有不经代理、直连别的域时才设 |
| `VITE_DEMO_USER` | 审批中心 `?user=` 查询参数的默认值（看谁的待办）。`kaneshiro` / `sato` / `tanaka` |

演示账号的口令在**后端** `.env` 的 `ATP_DEMO_PASSWORD`。**前端不内置口令** ——
登录页只帮你把用户名填好，口令要敲一次，之后 30 天不掉线。

> ⚠️ **代理会把 `Origin` 头摘掉**，这是必需的而不是优化。浏览器对非 GET/HEAD
> 的请求即使同源也会带 `Origin`；`changeOrigin` 改了 `Host` 却留着指向 dev server
> 的 `Origin`，后端就会判定跨域并以 403 `Invalid CORS request` 拒掉 ——
> 而所有读接口照常工作（同源 GET 不带 `Origin`），只有派发执行和审批决策会挂。

## 派发执行（M2）

「派发执行」和案例详情的「执行」都是真的：`POST /api/executions/dispatch` 立刻返回，
进度靠 2 秒轮询 `/running`，**拿到 204 就停止轮询**并刷新 `/stats`、`/recent`、`/nodes`。

**只给派发 LOGIN / CART / ORDER。** `mock-shop` 只做了这三个模块，其余模块没有对应页面，
派发出去会全部超时失败 —— 所以派发面板里根本不给选，案例详情的「执行」按钮在
其他模块下也是禁用的。让人点了再等一屏超时，是浪费一次演示。

**`etaSec` 永远带「≈」。** 它可能为 `null`（还没有任务完成时推算不出来），
有值时也是按已完成任务的平均耗时外推的。印成精确值会让人以为那是个承诺。

**`ATP-ORDER-0003` 的红是刻意的。** 它与 `ATP-CART-0007` 对购物车初始状态的要求相反
（一条要求含缺货商品并被结算拦下，一条要求没有缺货能进结算页），真实平台靠每条案例
独立的测试数据准备解决。失败详情里挂了一张 `BY DESIGN` 说明卡 —— 演示时这条红的
比一张全绿的表更能说明测试数据管理的重要性，不是缺陷。

**没有中止接口**，所以「中止」按钮还是置灰的。

## 登录与权限

`POST /api/auth/login` 换 token，存 `localStorage`，之后所有请求带
`Authorization: Bearer <token>`（和 CLI 的机器 token 同一个头）。

**401 与 403 处置相反**：

| | 含义 | 前端 |
|---|---|---|
| 401 | 没带 token / token 无效或过期 | 清 token → 路由守卫送回登录页 |
| 403 | token 有效但缺 scope | **不清、不跳**。重新登录也拿不到那个权限，踢回登录页只会让人再登一次、再撞一次同样的 403 |

**审批的「批准 / 退回 / 挂起」按 `user.canApprove` 开关**（来自 `sys_user.role`：
`REVIEWER` / `ADMIN` 为 true，`QA_ENGINEER` 为 false）。

⚠️ **`decidedBy` 由 token 决定，不是请求体里那个字段。** 所以「谁批的」一定是当前登录者：
用 `?user=sato` 看佐藤的待办然后点批准，记录上仍会是当前登录的人。
要演示「提交人 ↔ 审批人」两个视角，**真的换登录**（侧栏齿轮）——
这比参数切换更真实，也不会出现两个身份来源。

## 案例写侧（M3）

三段式：`POST /api/cases/draft` 建草稿 → `PUT /api/cases/{id}/draft` 反复保存 →
`POST /api/cases/{id}/commit` 提交落地。人在 UI 上编辑和 agent 生成走的是同一条路径。

**`caseId` 由前端生成（UUID），是幂等键。** 「点了新建但响应丢了」重试一次即可，
不会建出两条空草稿 —— 所以它只在组件挂载时生成一次，不能每次渲染换一个。

**`version` 是并发仲裁点。** 撞到 409 提示「已被他人修改」并给「重新载入」，
**不静默重试** —— 重试会拿你手上这份把别人的改动整个盖掉。

**`draftJson` 的表头字段是 snake_case。** 后端 commit 时按 `case_code` / `title` /
`module_id` / `priority` / `author` / `precondition` 六个键投影进 `tc_case` 的正式列，
camelCase 不认。缺了会返回 **422** 并在 `missingFields` 里列出少了哪几个，UI 直接照抄
—— 比让人去比对文档快。

**`case_code` 走 `GET /api/modules/{moduleId}/next-case-code` 取号**，不在前端自己推：
「已有条数 +1」和「最大序号 +1」在删过案例之后就不一样了，编号是单调的、计数不是。
agent 的 `next_case_code` 工具调的是同一份实现，两边不会算出不同的号。

**取号不是原子的。** 两个人同时新建同一模块的案例会拿到同一个号，后提交的被
`uk_case_code` 拦下。编辑器撞到这个会**自动重取一个号、重存、重提交一次**（只一次，
避免真出问题时死循环）—— 这不是数据写坏了，是号被抢了，重试是安全的。
⚠️ 后端目前把它报成 **500 `DuplicateKeyException`**，所以只能靠匹配约束名来认，不能靠状态码。

**保存反馈以 version 为准，时间戳只是补充。** 同一分钟内保存两次，`editUpdatedAt`
看不出变化，而 `version` 每次必跳。`editUpdatedAt` 来自 `tc_step`（草稿的最后保存时间），
不是 `tc_case` 的 `updatedAt` —— 后者是案例本身的最后变更，编辑草稿根本不动它。

**`replayed: true` 表示这次调用是一次幂等重放** —— 对应的写入上一次其实就成功了
（响应丢在路上，调用方重试了）。撞号自动重试那条路径要看它：`true` 意味着第一次
已经成功，不该算成第二次写入，界面也不说「保存成功」而是说清楚发生了什么。

**`draftJson` 的形状在 commit 那一步会变**：编辑期是对象 `{title, steps: [...]}`，
落地后是**纯步骤数组** `[{seq:1,…}]`（老执行器读数组）。`parseDraft()` 按 `status` 分支，
不分支的话提交成功那一刻页面就白屏。

## 智能 Agent 助手（M3）

`POST /api/chat/{conversationId}`，SSE。`conversationId` 前端生成并保持，
同一个 id 共享多轮上下文；关闭面板时 `DELETE` 释放。

**用 `fetch` + `ReadableStream` 而不是 `EventSource`** —— `EventSource` 只能 GET，
而这个接口要 POST 一个 body。

**`thinking` 是增量、`message` 是完整**，拼接方式不同。一轮通常 150~800 个 `thinking`
加 1 个 `message`；把 `message` 也当增量拼，最终回复会显示两遍。

**不设自己的超时。** 一轮可能 1~3 分钟（agent 要查规范、用浏览器实测页面、写草稿、
校验、提交、跑自验），后端 `SseEmitter` 是 300 秒。

**`route` 事件带路由结论与置信度**（`案例编写 · L2 0.98`），显示在气泡上方 ——
路由会判错，用户看得见才能立刻说「不是这个意思」，而不是等一大段跑完才发现跑偏。

## 几个刻意的决定

**写侧只对 `AI_DRAFT` 开放。** 「新建」是真的（建草稿 → 反复保存 → 提交落地）；
「编辑」只在案例还是 `AI_DRAFT` 时可用 —— 一旦 commit 落地，后端就不再接受 `PUT`，
返回 409「案例已经提交过了，本次更新不予执行」。库里 80 条存量全是已落地状态，
所以它们的「编辑」是禁用的**并说明原因**，而不是点了才报错。
「申请审批」仍置灰标 `M3` —— 审批**决策**接口早就有，但「案例变更 → 生成审批单」这一步还没接。

**领域数据不参与 i18n。** 项目名、模块名（本身就是 `ログイン / 登录认证` 这种双语串）、
案例标题、`case_code`、Action 枚举、STD 编号、node 名，以及**规范校验的 message**，
都由 API 原样返回。message 承载的是判断理由不是 UI 文案，三语切换时保持中文。
翻译的只有导航、表头、按钮、指标名、空状态。

**`steps[]` 保持 snake_case。** 外层 camelCase、`steps` 内部 snake_case 是刻意的：
`steps` 就是 `tc_step.step_json` 的形状，而那是平台 / agent / `atp` CLI(Go) /
Playwright 执行器四方共享的契约。在表示层转一次 camelCase，调试时就要多做一次心算。

**时间不做时区转换。** 后端已经转成 Asia/Tokyo 并格式化好，相对时间（`剩余 21h`）
也是后端算的，前端直接渲染。

**409 不自动重试。** 两个审批人同时点，后点的拿到「已经被 X 处理为 Y」。
提示「刷新看看」——重试只会再撞一次，还可能盖掉别人的决策。

**null 渲染成 `—`。** 步骤字段大量为 null（`OPEN_URL` 没有定位器，`CLICK` 没有输入数据），
渲染成空白或 `"null"` 都是错的。

**「执行中的批次」的空状态是正经状态。** 后端没批次在跑时返回 204 而不是 200 加空对象。
历史执行数据是种子，但「正在跑」这件事必须是真的 —— 摆个不动的假进度条，一刷新就露馅。

## 与设计稿的差异

设计稿（Claude Design canvas，Landing / Dashboard 两张画板）画在后端定型之前，
以下按 `02-前端契约.md` §一 修正过：

- 案例详情的 `BROWSER` / `TIMEOUT` 两格 → `CASE TYPE` / `VERSION`（两个旧字段在数据模型里不存在）
- 模块归属按 `module_code` 全局唯一重排，移动端是 `P003` 而不是 PC 端模块的复用
- `Selenium Grid` → `Playwright Workers`，节点在线数看 `online` 字段不看 `status`
- 「RAG 问答助手」→「智能 Agent 助手」，加了执行进度 / 工具调用 / HITL 卡片
- 审批卡片的「经 MCP 规范化服务」去掉（MCP 方案已废弃删除）
- Landing 的 `MySQL` → `PostgreSQL`、`Qdrant` → `pgvector`、`MCP 规范化服务` → `atp CLI (Go)`
- `STD-008` 从 `[待补充]` 填成「每条案例至少 1 个断言步骤」
- 分块设置去掉 `CHUNK SIZE` / `OVERLAP` —— 主策略下它们是死参数，摆出来是给自己挖坑
- 「执行中的批次」从静态进度条改成真派发 + 轮询；录像从占位改成真 `<video>`（webm，后端带 Range）
