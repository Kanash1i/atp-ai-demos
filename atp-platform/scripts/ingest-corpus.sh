#!/usr/bin/env bash
# 把 seed/docs 的规范与手册灌进向量库（pgvector）。
#
# ⚠️ 开工前先确认 TEI 真的在 GPU 上：
#     docker logs tei-embed | grep "model on"   → 必须是 Cuda
#   这个项目栽过一次：服务 health 200、维度也对，实际在 CPU 上跑。
set -euo pipefail
PLATFORM_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "$PLATFORM_DIR/.." && pwd)"
set -a; source "$REPO_ROOT/.env"; set +a
export ATP_SEED_DIR="${ATP_SEED_DIR:-$REPO_ROOT/seed}"
if [[ -d "$HOME/.sdkman/candidates/java/21.0.12+1.1-tem" ]]; then
  export JAVA_HOME="$HOME/.sdkman/candidates/java/21.0.12+1.1-tem"
  export PATH="$JAVA_HOME/bin:$PATH"
fi
exec mvn -q -f "$PLATFORM_DIR/pom.xml" -pl atp-rag test -Dtest=CorpusIngestRunTest -DfailIfNoTests=false
