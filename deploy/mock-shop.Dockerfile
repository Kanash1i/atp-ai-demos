# 被测系统（mock-shop）
#
# ⚠️ 按生产拓扑它该跑在**执行机能连到的那一侧**（见 00-SHARED-CONTEXT §2.0）：
#    只有执行机能访问被测系统，平台和测试人员的机器都连不到。
#    demo 里放哪都行，但部署时别下意识丢进平台那台机器。
#
# 同 platform.Dockerfile：只打包，不在容器里编译。构建入口 deploy/build.sh
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY deploy/build/mock-shop.jar app.jar
ENV JAVA_OPTS="-Xmx256m"
EXPOSE 8088
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
