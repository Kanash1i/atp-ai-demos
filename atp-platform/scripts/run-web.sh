#!/usr/bin/env bash
# 起平台主应用。配置一律来自仓库根 .env —— 代码里不得出现硬编码的 key / URL / IP。
#
#   ./scripts/run-web.sh              正常启动
#   ./scripts/run-web.sh --seed       启动并导入 seed/ 里的 80 条案例与 3 个用户
set -euo pipefail

PLATFORM_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "$PLATFORM_DIR/.." && pwd)"

# ⚠️ .env 里有 ${SERVICE_HOST} 这类自引用变量，必须让 shell 展开，所以用 source 而不是逐行读
[[ -f "$REPO_ROOT/.env" ]] || { echo "✗ 找不到 $REPO_ROOT/.env（从 .env.example 复制）" >&2; exit 1; }
set -a; source "$REPO_ROOT/.env"; set +a

# JDK 21：AgentScope 与 Spring Boot 3.4 都要
if [[ -d "$HOME/.sdkman/candidates/java/21.0.12+1.1-tem" ]]; then
  export JAVA_HOME="$HOME/.sdkman/candidates/java/21.0.12+1.1-tem"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

# ⚠️ spring-boot:run 的工作目录是**模块目录**（atp-web/），不是工程根。
#    种子目录用绝对路径喂进去，省得数 ../ 的层数 —— 数错了要等到启动才报错。
export ATP_SEED_DIR="$REPO_ROOT/seed"

# 同一个坑：agent 写案例要 exec 的 atp 二进制也不能用相对路径 ——
# 它在 .env 里是相对仓库根写的，而 JVM 的 cwd 是 atp-web/。这里统一解析成绝对路径。
ATP_CLI_BIN="${ATP_CLI_BIN:-demo2-atp-cli/bin/atp}"
[[ "$ATP_CLI_BIN" != /* ]] && ATP_CLI_BIN="$REPO_ROOT/${ATP_CLI_BIN#./}"
export ATP_CLI_BIN
[[ -x "$ATP_CLI_BIN" ]] || echo "⚠ 找不到可执行的 atp（$ATP_CLI_BIN）—— agent 将无法写案例。构建：cd demo2-atp-cli && go build -o bin/atp ./cmd/atp" >&2

ARGS=()
[[ "${1:-}" == "--seed" ]] && ARGS+=("-Dspring-boot.run.arguments=--atp.seed.enabled=true")

cd "$PLATFORM_DIR"

# ⚠️ 两步走，不能合成 `-pl atp-web -am spring-boot:run`：
#    -am 会把 parent 也选进来，而 spring-boot:run 这个 CLI goal 会在每个选中的项目上执行 ——
#    parent 是 packaging=pom，没有 main class，直接报「Unable to find a suitable main class」。
mvn -q -B -pl atp-web -am install -DskipTests
exec mvn -q -pl atp-web spring-boot:run "${ARGS[@]}"
