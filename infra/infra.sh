#!/usr/bin/env bash
# 两条路线共用的中间件，统一在台式机上起。
#
#   ./infra/infra.sh up      起 PG + Redis，应用全部迁移，把连接串写进仓库根 .env
#   ./infra/infra.sh down    停掉（保留数据卷）
#   ./infra/infra.sh reset   删掉重来（**清空数据**）
#   ./infra/infra.sh psql    开个 psql
#   ./infra/infra.sh ports   打印台式机上已被占用/已配置的端口，加新服务前先看
set -euo pipefail

INFRA_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$INFRA_DIR/.." && pwd)"
COMPOSE="$INFRA_DIR/compose.yaml"

# ⚠️ 显式钉住 remote context —— 中间件一律跑在台式机 192.168.0.101。
#    不依赖「当前 context 恰好是 remote」，那种依赖迟早在某次 docker context use default 之后炸。
DC=(docker --context remote compose -f "$COMPOSE")

SERVICE_HOST=${SERVICE_HOST:-192.168.0.101}
PG_PORT=25432
REDIS_PORT=25379

up() {
  echo "→ 在 $SERVICE_HOST 上起 PG(:$PG_PORT) 与 Redis(:$REDIS_PORT)"
  "${DC[@]}" up -d --wait
  migrate
  write_env
  echo
  echo "✓ 中间件就绪（compose project: atp-infra）"
  echo "  PG    $SERVICE_HOST:$PG_PORT/atp"
  echo "  Redis $SERVICE_HOST:$REDIS_PORT"
}

# 迁移分两处：CLI 的 V0/V1（老平台基线 + AI 草稿状态机）与平台的 V2~V5。
# ⚠️ 顺序不能反，而且必须一起应用 —— 两条路线共用一个库，
#    分开跑的话谁先起库谁的表才在，另一边要到运行时才发现表不存在。
migrate() {
  local applied=0
  for f in "$REPO_ROOT"/demo2-atp-cli/migrations/V*.sql "$REPO_ROOT"/atp-platform/migrations/V*.sql; do
    [[ -e "$f" ]] || continue
    docker --context remote exec -i atp-infra-postgres \
      psql -q -U atp -d atp -v ON_ERROR_STOP=1 < "$f"
    echo "→ 已应用 $(basename "$f" .sql)"
    applied=$((applied + 1))
  done
  [[ $applied -gt 0 ]] || { echo "✗ 一支迁移都没找到" >&2; exit 1; }
}

# 幂等地维护 .env 里这几行 —— 已存在就替换，不存在就追加，不动别的配置
write_env() {
  local env="$REPO_ROOT/.env"
  [[ -f "$env" ]] || cp "$REPO_ROOT/.env.example" "$env"
  local kvs=(
    "ATP_DB_URL=jdbc:postgresql://$SERVICE_HOST:$PG_PORT/atp"
    "ATP_DB_USER=atp"
    "ATP_DB_PASSWORD=atp"
    "REDIS_HOST=$SERVICE_HOST"
    "REDIS_PORT=$REDIS_PORT"
    "REDIS_PASSWORD=atp"
  )
  for kv in "${kvs[@]}"; do
    local key="${kv%%=*}"
    if grep -q "^${key}=" "$env"; then
      sed -i "s|^${key}=.*|${kv}|" "$env"
    else
      printf '%s\n' "$kv" >> "$env"
    fi
  done
}

# 加新中间件之前跑一下。⚠️ 停着的容器不显示端口，但它们配置里写着的端口随时会被拉起来占掉，
# 所以这里查的是 PortBindings 而不是运行中的映射。
ports() {
  echo "── 台式机上所有容器（含已停止）配置的宿主端口 ──"
  docker --context remote ps -aq | xargs -r docker --context remote inspect \
    --format '{{.Name}} {{range $p, $conf := .HostConfig.PortBindings}}{{range $conf}}{{.HostPort}} {{end}}{{end}}' \
    | sed 's|^/||' | grep -E "[0-9]" | sort
  echo
  echo "── Windows 实际监听的端口 ──"
  ssh -o BatchMode=yes "kkaib@$SERVICE_HOST" "netstat -an | findstr LISTENING" 2>/dev/null \
    | awk '{print $2}' | grep -oE ':[0-9]+$' | tr -d ':' | sort -n -u | tr '\n' ' '
  echo
}

case "${1:-up}" in
  up)    up ;;
  down)  "${DC[@]}" down ;;
  reset) "${DC[@]}" down -v && up ;;
  psql)  docker --context remote exec -it atp-infra-postgres psql -U atp -d atp ;;
  redis) docker --context remote exec -it atp-infra-redis redis-cli -a atp ;;
  ports) ports ;;
  *)     echo "用法: $0 [up|down|reset|psql|redis|ports]" >&2; exit 2 ;;
esac
