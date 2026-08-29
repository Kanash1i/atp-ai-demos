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

db-up:            ## 起演示库 + 跑迁移 + 写 .env
	./scripts/demo-db.sh up

db-down:
	./scripts/demo-db.sh down

clean:
	rm -rf $(BIN)
