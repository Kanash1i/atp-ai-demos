#!/usr/bin/env bash
# 起一个执行节点。一进程一节点 —— 要几个节点就跑几次。
#
#   ./scripts/run-node.sh node-01          前台
#   ./scripts/run-node.sh node-01 -d       后台（日志到 /tmp/atp-node-{name}.log）
#   ./scripts/run-node.sh stop             停掉全部节点
set -euo pipefail

PLATFORM_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "$PLATFORM_DIR/.." && pwd)"
JAR="$PLATFORM_DIR/atp-runner/target/atp-runner-1.0.0-SNAPSHOT.jar"

if [[ "${1:-}" == "stop" ]]; then
  # ⚠️ 按 jar 名杀，不要按 "atp-runner" 这种宽泛的词 ——
  #    那会连正在执行这条命令的 shell 一起匹配上。
  pkill -f "/tmp/atp-node-.*\.jar" 2>/dev/null || true
  pkill -f "atp-runner-1.0.0-SNAPSHOT.jar" 2>/dev/null || true
  sleep 1
  echo "✓ 已停止全部执行节点"
  exit 0
fi

NODE_NAME="${1:-node-01}"
[[ -f "$JAR" ]] || { echo "✗ 找不到 $JAR，先跑 mvn -f $PLATFORM_DIR/pom.xml install -DskipTests" >&2; exit 1; }
[[ -f "$REPO_ROOT/.env" ]] || { echo "✗ 找不到 $REPO_ROOT/.env" >&2; exit 1; }

set -a; source "$REPO_ROOT/.env"; set +a
export MOCK_SHOP_URL="${MOCK_SHOP_URL:-http://localhost:8088}"

if [[ -d "$HOME/.sdkman/candidates/java/21.0.12+1.1-tem" ]]; then
  export JAVA_HOME="$HOME/.sdkman/candidates/java/21.0.12+1.1-tem"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

# ⚠️⚠️ 跑 jar 的**副本**，不直接跑 target 下那个。
#
# 原因：JVM 惰性加载类 —— 进程启动后再 `mvn install` 覆盖同一个 jar，
# 之后需要加载新类时会抛 ClassNotFoundException（实测是 logback 的 ThrowableProxy），
# 表现为**进程还在、心跳却停了**，节点静默不再干活。
# 这个故障不报错、不退出，只是看板上那个节点慢慢变灰，极难联想到是构建覆盖了 jar。
RUN_JAR="/tmp/atp-node-$NODE_NAME.jar"
cp -f "$JAR" "$RUN_JAR"

# -Xmx256m：单节点的 JVM 堆。真正的大头是浏览器进程（约 300~400MB），不在这个限额里
ARGS=(-Xmx256m -jar "$RUN_JAR" "--atp.runner.node-name=$NODE_NAME")

if [[ "${2:-}" == "-d" ]]; then
  LOG="/tmp/atp-node-$NODE_NAME.log"
  nohup java "${ARGS[@]}" > "$LOG" 2>&1 &
  echo "→ $NODE_NAME 已后台启动，日志 $LOG"
else
  exec java "${ARGS[@]}"
fi
