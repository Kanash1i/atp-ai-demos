#!/usr/bin/env bash
# 一条命令起好演示用的 PostgreSQL 并应用两支迁移。
#
#   ./scripts/demo-db.sh up     起库 + 迁移 + 把连接串写进仓库根 .env
#   ./scripts/demo-db.sh down   删掉容器
#   ./scripts/demo-db.sh psql   开个 psql 进去看数据
set -euo pipefail

MODULE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "$MODULE_DIR/.." && pwd)"
NAME=atp-demo-db
PORT=${ATP_DEMO_PORT:-55433}

# ⚠️ 笔记本上 docker context 默认可能指向远程台式机（见 CLAUDE.md）。
#    演示库要跑在本机，显式钉住本地 socket。
export DOCKER_HOST=${DOCKER_HOST:-unix:///var/run/docker.sock}

up() {
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  echo "→ 起 postgres:17 于 127.0.0.1:$PORT"
  docker run -d --name "$NAME" -p "$PORT:5432" \
    -e POSTGRES_USER=atp -e POSTGRES_PASSWORD=atp -e POSTGRES_DB=atp \
    postgres:17 >/dev/null

  for _ in $(seq 1 60); do
    docker exec "$NAME" pg_isready -U atp >/dev/null 2>&1 && break
    sleep 1
  done
  docker exec "$NAME" pg_isready -U atp >/dev/null 2>&1 || {
    echo "✗ PG 没起来"; docker logs "$NAME" | tail -20; exit 1; }

  # V0 = 老平台现状基线，V1 = 我们的改造。顺序不能反。
  for f in V0__baseline_legacy V1__ai_draft_state; do
    docker exec -i "$NAME" psql -q -U atp -d atp -v ON_ERROR_STOP=1 \
      < "$MODULE_DIR/src/main/resources/db/migration/$f.sql"
    echo "→ 已应用 $f"
  done

  write_env
  echo
  echo "✓ 演示库就绪。连接串已写入 $REPO_ROOT/.env"
  echo "  下一步：cd $MODULE_DIR && opencode"
}

# 幂等地维护 .env 里那三行 —— 已存在就替换，不存在就追加，不动别的配置
write_env() {
  local env="$REPO_ROOT/.env"
  [[ -f "$env" ]] || cp "$REPO_ROOT/.env.example" "$env"
  local url="jdbc:postgresql://127.0.0.1:$PORT/atp"
  for kv in "ATP_DB_URL=$url" "ATP_DB_USER=atp" "ATP_DB_PASSWORD=atp"; do
    local key="${kv%%=*}"
    if grep -q "^${key}=" "$env"; then
      sed -i "s|^${key}=.*|${kv}|" "$env"
    else
      printf '%s\n' "$kv" >> "$env"
    fi
  done
}

case "${1:-up}" in
  up)   up ;;
  down) docker rm -f "$NAME" >/dev/null 2>&1 && echo "✓ 已删除 $NAME" ;;
  psql) docker exec -it "$NAME" psql -U atp -d atp ;;
  *)    echo "用法: $0 [up|down|psql]" >&2; exit 2 ;;
esac
