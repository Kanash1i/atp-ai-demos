# atp CLI —— Go
#
# ⚠️ bin/atp 是构建产物（已 gitignore）。opencode.json 与 SKILL.md 里引用的
#    就是这个路径，所以改完代码记得 make build。
GO      ?= go
# 交叉编译时五个容器共用的缓存目录（本机路径，属主与 --user 一致才写得进去）
GOMOD_CACHE     ?= /tmp/atp-gomod
GO_BUILD_CACHE  ?= /tmp/atp-gocache
BIN     := bin/atp
PKG     := ./cmd/atp

.PHONY: build cross pack test lint clean db-up db-down

build:            ## 构建单文件二进制
	$(GO) build -trimpath -ldflags="-s -w" -o $(BIN) $(PKG)

cross:            ## 交叉编译五个平台，产物进 packages/opencode-atp/bin/
	@# ⚠️ DOCKER_CONTEXT=default 是必须的：-v 挂的是本机路径，
	@#    而这台机器的 docker context 平时指向台式机（见 deploy/build.sh 同款坑）。
	@#
	@# ⚠️ 五个平台共用一份 GOMODCACHE。不共享的话每个容器都要把依赖重下一遍 ——
	@#    五次下载里只要有一次网络抖动，整个 cross 就失败，而失败信息是
	@#    「proxy.golang.org ... EOF」，看起来像依赖有问题而不是重复下载太多。
	@mkdir -p packages/opencode-atp/bin $(GOMOD_CACHE)
	@for t in "linux amd64" "linux arm64" "darwin amd64" "darwin arm64" "windows amd64"; do \
		set -- $$t; ext=""; [ "$$1" = windows ] && ext=".exe"; \
		DOCKER_CONTEXT=default docker run --rm --user "$$(id -u):$$(id -g)" \
			-e GOOS=$$1 -e GOARCH=$$2 -e CGO_ENABLED=0 \
			-e GOCACHE=/gocache -e GOMODCACHE=/gomod \
			-v "$(GOMOD_CACHE)":/gomod -v "$(GO_BUILD_CACHE)":/gocache \
			-v "$$PWD":/src -w /src golang:1.25 \
			go build -trimpath -ldflags='-s -w' -o "/src/packages/opencode-atp/bin/atp-$$1-$$2$$ext" ./cmd/atp || exit 1; \
		echo "  $$1/$$2"; \
	done

pack: cross     ## 打出可给 opencode 安装的 npm 包
	@# SKILL.md 的唯一来源是 .opencode/skills/ —— 包里那份是复制过去的，
	@# 不要两边各维护一份，那种重复迟早会漂。
	@mkdir -p packages/opencode-atp/skills/atp-case-authoring
	@cp .opencode/skills/atp-case-authoring/SKILL.md packages/opencode-atp/skills/atp-case-authoring/
	@cd packages/opencode-atp && npm pack

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
