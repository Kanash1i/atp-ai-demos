# ============================================================
# atp-frontend —— 只产出静态文件，不自带 web server
#
# 编排（Caddy / compose）统一由 infra/ 管，这里不塞第二份。
# 这个镜像跑起来只做一件事：把构建产物倒进挂载出来的目录，然后退出。
# 详见 DEPLOY.md。
# ============================================================

FROM node:24-alpine AS build
WORKDIR /app

# 先只拷贝依赖清单 —— 源码改动不会让这一层失效
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY . .

# ⚠️ Vite 在 **build 时**把 VITE_* 静态替换进 JS，不是运行时读。
# 所以这些必须是 build arg，运行时再设环境变量是不生效的 ——
# 这一点和后端「运行时读 .env」的习惯相反，最容易配错。
#
# VITE_API_BASE 留空 = 走同源 /api（由 Caddy 反代到后端）。
# 只有前后端分域部署时才设它，那种情况后端还得配 CORS 白名单。
ARG VITE_API_BASE=""
ARG VITE_DEMO_USER="kaneshiro"
# 登录页预填的演示口令。不传就留空，登录页要手动输入。
#
# ⚠️ docker build 会对下面这两行报 SecretsUsedInArgOrEnv —— **这个警告在这里是预期的**，
# 不要用 --mount=type=secret 去「修」它：Vite 是把值静态替换进 JS 的，
# 口令无论如何都会明文出现在产物里，藏在构建层里没有任何意义。
# 真实场景的做法是**不传这个 arg**，让登录页留空手动输入。
ARG VITE_DEMO_PASSWORD=""

ENV VITE_API_BASE=$VITE_API_BASE \
    VITE_DEMO_USER=$VITE_DEMO_USER \
    VITE_DEMO_PASSWORD=$VITE_DEMO_PASSWORD

RUN npm run build


# ---- 产物搬运工 ----
# 不用 nginx/caddy 作 base：serving 归编排管，这里只负责把文件放到约定位置。
FROM alpine:3.20 AS dist
COPY --from=build /app/dist /dist

# 一次性任务：倒完就退出。compose 里配 restart: "no"，
# 让它在 Caddy 起来之前把命名卷填好。
CMD ["sh", "-c", "rm -rf /srv/* && cp -a /dist/. /srv/ && echo 'atp-frontend: dist 已就位' && ls /srv | head"]
