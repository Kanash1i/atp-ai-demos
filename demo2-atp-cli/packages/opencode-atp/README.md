# opencode-atp-cli

把 ATP 平台的 `atp` CLI 装进 opencode，用一句话写出**符合规范、能真实跑起来**的测试案例。

## 安装

```bash
opencode plugin opencode-atp-cli
```

装完重开 opencode 即可。二进制随包分发（linux / darwin 的 amd64、arm64，windows/amd64），不需要额外下载。

## 配置

案例最终要落进 ATP 的库，所以工作目录下需要一个 `.env`：

```bash
ATP_DB_URL=jdbc:postgresql://<host>:<port>/<db>
ATP_DB_USER=<user>
ATP_DB_PASSWORD=<password>
```

## 用法

直接说人话：

> 帮我写一条案例：购物车里加超过库存的数量，应该被拦下并提示

它会依次查规范、查模块字典、探查真实页面拿定位器、生成草稿、本地校验，
然后把完整内容念给你听——**等你确认后才写库**。

## ⭐ commit 需要你点确认

插件注入的权限规则里，只有一条是 `ask`：

```
<binary> commit*   →  ask
```

其余命令（`schema` / `modules` / `draft` / `update` / `preview` / `run` …）都放行。

这不是提示词里的一句叮嘱，是**模型执行不了的一道门**：它可以起草、可以改、可以预览、可以试跑，
但把案例真正写进库的那一步，必须你按下确认。

## 它不会做的事

- **不会替你判断执行失败**。案例跑挂了，它报告现象（第几步、什么错、录像在哪），不自己改案例——
  因为「改到能跑通」最省力的办法是削弱断言，那样测试会变绿而什么都不保证。
- **不猜 URL 和定位器**。每一个都来自对被测页面的真实探查。
- **不把口令写进案例**。口令一律用 `@cred{xxx}` 引用，执行日志里只会出现 `***`。
