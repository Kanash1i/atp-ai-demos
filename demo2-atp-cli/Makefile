# atp CLI —— Go
#
# ⚠️ bin/atp 是构建产物（已 gitignore）。opencode.json 与 SKILL.md 里引用的
#    就是这个路径，所以改完代码记得 make build。
GO      ?= go
BIN     := bin/atp
PKG     := ./cmd/atp

.PHONY: build test lint clean db-up db-down

build:            ## 构建单文件二进制
	$(GO) build -trimpath -ldflags="-s -w" -o $(BIN) $(PKG)

test:             ## 跑全部测试（会起真 PostgreSQL 容器）
	DOCKER_HOST=$${DOCKER_HOST:-unix:///var/run/docker.sock} $(GO) test ./... -count=1

lint:
	$(GO) vet ./...
	gofmt -l . | grep -v '^$$' && exit 1 || true

# 中间件统一由 infra/ 管，跑在台式机 192.168.0.101 上（两条路线共用一个库）
db-up:            ## 起 PG + Redis + 跑全部迁移 + 写 .env
	../infra/infra.sh up

db-down:
	../infra/infra.sh down

clean:
	rm -rf $(BIN)
