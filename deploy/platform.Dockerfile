# ATP 平台（atp-web）—— 部署在云服务器
#
# ⚠️ 这个 Dockerfile **不在容器里编译**，只打包已构建好的产物。
#
# 原因是实测出来的：这个项目所在的网络到公网仓库（Maven Central / Go proxy）
# 很不稳，容器内构建反复撞 "Remote host terminated the handshake"，
# 而宿主机上依赖早就齐了。硬要自包含的结果是**构建成功率取决于当次网络运气**。
#
# 分离 build 与 package 本来也是 CI 的常规做法。构建入口：deploy/build.sh
#
# ⚠️ 镜像里必须带 atp CLI 二进制：平台内的 agent 写案例是 exec 它，不是调自己的
#    service。少了它 agent 的写工具全部不可用，而平台照常启动 —— 启动日志里
#    那行 `[CLI] <路径> → atp version x` 就是用来发现这件事的。

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# 由 deploy/build.sh 准备，见该脚本
COPY deploy/build/atp     /usr/local/bin/atp
COPY deploy/build/app.jar app.jar
COPY seed/                /app/seed/

RUN chmod +x /usr/local/bin/atp

# ⚠️ 全部配置走环境变量，镜像里不烧任何地址与密钥（CLAUDE.md 的硬约束）
ENV ATP_CLI_BIN=/usr/local/bin/atp \
    ATP_SEED_DIR=/app/seed \
    JAVA_OPTS="-XX:MaxRAMPercentage=70"

EXPOSE 8080

# ⚠️ exec 形式，让 java 成为 PID 1 —— shell 形式下 SIGTERM 到不了 JVM，
#    容器停止会走超时强杀，而优雅关闭里有节点注销与 SSE 收尾
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
