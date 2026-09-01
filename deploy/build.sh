#!/usr/bin/env bash
# 构建部署产物与镜像。
#
# 分两步是**故意的**：容器内构建在这个网络环境下成功率不稳
# （Maven Central / Go proxy 反复 TLS 握手失败），而宿主机的依赖早就齐了。
#
#   ./deploy/build.sh            构建产物 + 镜像
#   ./deploy/build.sh --jar-only 只构建产物，不打镜像
set -euo pipefail

# ⚠️ 锁定本机 daemon。这个脚本用 -v 把 $REPO_ROOT 挂进构建容器，
#    挂载路径是 **daemon 那一侧**的路径 —— 而 docker context 是全局状态，
#    本仓库的笔记本平时指向台式机（context `remote`）。
#    context 没锁时，挂上去的是台式机上并不存在的目录，报出来是：
#
#      go: go.mod file not found in current directory or any parent directory
#
#    文件明明在，错误却指向"没有 go.mod"，根因隔着一层看不见。实测踩过。
export DOCKER_CONTEXT=default

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$REPO_ROOT/deploy/build"
cd "$REPO_ROOT"

if [[ -d "$HOME/.sdkman/candidates/java/21.0.12+1.1-tem" ]]; then
  export JAVA_HOME="$HOME/.sdkman/candidates/java/21.0.12+1.1-tem"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

mkdir -p "$OUT"

echo "→ 1/3 构建平台与 mock-shop"
# ⚠️ 跳过测试：并发测试要连真 PG，构建机上未必有。测试单独跑（mvn -pl atp-platform-api test）
(cd atp-platform && mvn -B -q -pl atp-web,mock-shop,atp-runner -am package -DskipTests)
cp atp-platform/atp-web/target/atp-web-*.jar       "$OUT/app.jar"
cp atp-platform/mock-shop/target/mock-shop-*.jar   "$OUT/mock-shop.jar"
# 执行节点。⚠️ 它部署在台式机上，与平台不同机 —— 见 deploy/runner-compose.yaml
cp atp-platform/atp-runner/target/atp-runner-*.jar "$OUT/runner.jar"

echo "→ 2/3 构建 atp CLI"
# ⚠️ 两个都不能省：
#    --user      不加的话产物属主是 root，宿主机上覆盖不了也删不掉。本仓库踩过
#    CGO_ENABLED=0  运行镜像是 alpine（musl），而 golang:1.25 是 glibc ——
#                动态链接的二进制拷进去会「文件在、但 not found」，
#                因为找不到的是解释器不是文件本身。实测撞过
docker run --rm --user "$(id -u):$(id -g)" \
  -e GOCACHE=/tmp/gocache -e GOPATH=/tmp/go \
  -v "$REPO_ROOT/demo2-atp-cli":/src -w /src golang:1.25 \
  sh -c "CGO_ENABLED=0 go build -trimpath -ldflags='-s -w' -o /src/bin/atp ./cmd/atp"
cp demo2-atp-cli/bin/atp "$OUT/atp"

if [[ "${1:-}" == "--jar-only" ]]; then
  echo "✓ 产物就绪：$OUT"
  exit 0
fi

echo "→ 3/3 打镜像"
docker build -f deploy/platform.Dockerfile   -t atp-platform:latest  .
docker build -f deploy/mock-shop.Dockerfile  -t atp-mock-shop:latest .

echo "✓ 完成"
docker images --format '  {{.Repository}}:{{.Tag}}  {{.Size}}' | grep -E "^  atp-(platform|mock-shop):latest"
