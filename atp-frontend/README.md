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

## 路由

| 路径 | 内容 |
|---|---|
| `/` | Landing。三语循环打字机、滚动淡入、「立即体验」进 dashboard |
| `/dashboard/cases` | 案例中心：项目 pill → 模块树 → 案例详情（步骤表 + 规范校验） |
| `/dashboard/runs` | 执行状态：四张统计卡、执行中批次（真派发 + 2 秒轮询）、最近执行、失败详情抽屉（播真录像） |
| `/dashboard/agent` | 智能 Agent 助手（**M3 才有后端**，当前是静态稿） |
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
| `VITE_DEMO_USER` | M1 还没接 Sa-Token，当前用户由 `?user=` 带。`kaneshiro` / `sato` / `tanaka` |

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

## 几个刻意的决定

**写侧按钮做出来但置灰。** 「新建」「编辑」「申请审批」在 M3 才有后端 ——
人在 UI 上编辑和 agent 生成走同一条写入路径，先给 UI 做一遍、M3 再为 agent 做一遍，
几乎必然出现两套语义。按钮按稿子摆在原位并标着 `M3`，而不是先藏起来：
藏起来演示时就看不出平台的完整形状了。「执行」同理，标 `M2`。

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
