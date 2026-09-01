# atp-frontend 部署交接

> 只写**前端这一半**：产物长什么样、需要什么、`/api` 要转到哪。
> 编排（compose / Caddy / 证书）归 `infra/` 统一管，这里不塞第二份。

---

## 一、⭐ 一句话前提：必须同源

前端页面和 `/api` **必须在同一个 origin 下**。

浏览器对非 GET/HEAD 的请求**即使同源也会带 `Origin`**（Fetch 规范）。同源时
`Origin` 与 `Host` 一致，Spring 判定为同源请求，根本不进 CORS 分支，什么都不用配。

一旦分域，后端就必须配 CORS 白名单并允许 `Authorization` 头。**前端没有第三条路**
—— dev 下是 vite 代理把 `Origin` 摘掉的，生产没有那一层。

> 这个坑本项目已经踩过一次：dev 代理开了 `changeOrigin`（它改的是 `Host`，不是 `Origin`），
> 结果后端看到 `Host: localhost:8080` 配 `Origin: http://localhost:5273`，
> 判定跨域，写操作全部 403 —— 而所有读接口正常，看起来像「后端没做派发」。

---

## 二、要什么

### 构建参数（⚠️ 是 build arg，不是运行时环境变量）

Vite 在 **build 时**把 `VITE_*` 静态替换进 JS。运行时再设环境变量**不生效**。
这一点和后端「运行时读 `.env`」的习惯相反，是最容易配错的地方。

| build arg | 值 | 说明 |
|---|---|---|
| `VITE_API_BASE` | **留空** | 空 = 走同源 `/api`。只有分域部署才设 |
| `VITE_DEMO_USER` | `kaneshiro` | 审批中心 `?user=` 的默认值（看谁的待办） |
| `VITE_DEMO_PASSWORD` | 同后端 `ATP_DEMO_PASSWORD` | 登录页预填。不传就留空，手动输入 |

⚠️ `VITE_DEMO_PASSWORD` 会**明文出现在产物 JS 里**，任何能打开页面的人都看得到。
本项目可接受（虚构演示账号、一次性口令、私有仓库的面试 demo）。
换到任何真实场景，不传这个 arg 即可。

### 运行时

前端**没有运行时**。产物是纯静态文件，交给 Caddy 服务。

---

## 三、产物怎么交付

`Dockerfile` 构建出来的镜像**不自带 web server**，跑起来只做一件事：
把 `dist` 倒进挂载出来的目录，然后退出。

```yaml
# infra/compose.yaml 片段
services:
  atp-frontend-dist:
    build:
      context: ../atp-frontend
      args:
        VITE_API_BASE: ""
        VITE_DEMO_USER: "kaneshiro"
        VITE_DEMO_PASSWORD: "${ATP_DEMO_PASSWORD}"
    volumes:
      - web-dist:/srv
    restart: "no"          # 一次性任务，倒完就退

  caddy:
    image: caddy:2-alpine
    depends_on:
      atp-frontend-dist:
        condition: service_completed_successfully
    volumes:
      - web-dist:/srv:ro
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy-data:/data
    ports: ["80:80", "443:443"]

volumes:
  web-dist:
  caddy-data:
```

不想用这个镜像也行 —— `npm ci && npm run build`，把 `dist/` 里的东西丢到
Caddy 的 `root` 下即可，没有任何后处理步骤。

---

## 四、Caddy 那边要做的三件事

完整片段见 [`Caddyfile.snippet`](./Caddyfile.snippet)，要点：

1. **`/api/*` 反代到平台**，用 `handle` 不是 `handle_path` —— 前缀要保留。
2. **SPA history fallback**：`try_files {path} /index.html`。
   `/dashboard/cases`、`/login` 在服务器上没有对应文件，直接访问或刷新会 404。
3. **`index.html` 不能缓存**，`/assets/*` 带哈希可以长缓存。

### 换别的反代要检查的（Caddy 默认就是对的，所以这里只是备忘）

| 项 | 为什么 | Caddy | nginx 需要显式配 |
|---|---|---|---|
| `Host` 头 | 改了就变跨域 → 403 | 默认透传 | `proxy_set_header Host $host;` |
| 响应缓冲 | 开着 SSE 就不实时了 | 对 `text/event-stream` 自动关 | `proxy_buffering off;` |
| 读超时 | agent 一轮 1~3 分钟 | 无默认读超时 | `proxy_read_timeout 300s;`（默认才 60s） |

三条都是**静默失败**：不配也能起来，只在特定场景坏，而且坏得像后端的锅。

---

## 五、部署后先验这几条

按 [`BACKLOG.md`](./BACKLOG.md) 第五节的九步演示脚本走一遍。最能暴露配置问题的三条：

| 现象 | 多半是 |
|---|---|
| 读接口正常，**新建 / 编辑 / 提交 / 派发 403** | 不同源，或反代改了 `Host` |
| agent 对话**跑到 60 秒断掉** | 反代读超时太短 |
| agent 的 thinking **不实时、一次性刷出来** | 反代开了响应缓冲 |
| 刷新 `/dashboard/cases` **404** | 少了 SPA history fallback |
| 发版后仍是旧页面 | `index.html` 被缓存了 |
