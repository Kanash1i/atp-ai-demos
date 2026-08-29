#!/usr/bin/env bash
# 起 mock-shop（被测站点）。
#
#   ./scripts/run-mock.sh        重新打包并后台启动
#   ./scripts/run-mock.sh stop   停掉
#
# ⚠️ 每次都重新打包：mock-shop 的页面是 src/main/resources/static 下的静态资源，
#    它们会被打进 jar。改完页面直接重启旧 jar 的话，跑的还是旧页面 ——
#    表现是「明明加了按钮却点不到」，然后你会去查执行器、查定位器、查等待策略，
#    唯独想不到是资源没打包。这个坑值得用一个脚本封死。
set -euo pipefail

PLATFORM_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT=${MOCK_SHOP_PORT:-8088}

if [[ -d "$HOME/.sdkman/candidates/java/21.0.12+1.1-tem" ]]; then
  export JAVA_HOME="$HOME/.sdkman/candidates/java/21.0.12+1.1-tem"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

stop() {
  # 按端口杀，不用 pkill -f —— 那匹配不到 mvn 派生的子进程，今天已经栽过三次
  ss -tlnp 2>/dev/null | grep ":$PORT" | grep -oP 'pid=\K[0-9]+' | sort -u | xargs -r kill -9 2>/dev/null || true
  sleep 1
}

if [[ "${1:-}" == "stop" ]]; then
  stop; echo "✓ mock-shop 已停止"; exit 0
fi

echo "→ 重新打包 mock-shop"
mvn -q -B -f "$PLATFORM_DIR/pom.xml" -pl mock-shop install -DskipTests

stop
LOG=/tmp/atp-mock-shop.log
nohup java -jar "$PLATFORM_DIR/mock-shop/target/mock-shop-1.0.0-SNAPSHOT.jar" > "$LOG" 2>&1 &

for _ in $(seq 1 40); do
  if curl -sf --max-time 2 "http://localhost:$PORT/login" >/dev/null 2>&1; then
    echo "✓ mock-shop 就绪 http://localhost:$PORT （日志 $LOG）"; exit 0
  fi
  sleep 2
done
echo "✗ mock-shop 没起来，看 $LOG" >&2; exit 1
