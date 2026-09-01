# 执行节点镜像 —— 跑在台式机上（Docker Desktop / WSL2）
#
# ⚠️ 基础镜像用 Playwright 官方的 java 变体，不是 eclipse-temurin + 自己装浏览器。
#    浏览器的系统依赖（字体、libnss、libasound…）在 headless 下缺一个都跑不起来，
#    而缺的表现常常是 launch() 超时，不是"缺少 xxx.so"。官方镜像已经把这套配齐。
#
# ⚠️ 和 platform.Dockerfile 一样：不在容器里编译，只打包已构建好的产物。
FROM mcr.microsoft.com/playwright/java:v1.45.0-jammy

WORKDIR /app

# 产物由 deploy/build.sh 生成
COPY deploy/build/runner.jar /app/app.jar

# 录像与截图落在这里，再由节点上传到平台。
# ⚠️ 与平台的 artifact-dir 必须是不同的目录：同机同目录时"上传"会变成原地写自己
ENV ATP_ARTIFACT_DIR=/app/artifacts \
    ATP_TESTDATA_DIR=/app/testdata \
    ATP_HEADLESS=true \
    # ⭐ 留空 = 用 Playwright 自带的 chromium。
    #    这个镜像里没有 Google Chrome，设成 chrome 的话节点照样注册成功、
    #    心跳正常、看板显示在线，只是每条任务都失败。
    ATP_BROWSER_CHANNEL=""

RUN mkdir -p /app/artifacts /app/testdata

# -Xmx256m：JVM 堆。浏览器进程约 350MB，不在这个限额里
ENTRYPOINT ["java", "-Xmx256m", "-jar", "/app/app.jar"]
