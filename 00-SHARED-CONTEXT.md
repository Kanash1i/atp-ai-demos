# 共享上下文 — 两个 demo 都必须先读这份

> 这份文档定义了两个 demo 共用的**虚构世界观、模型服务、法律边界**。
> demo1 和 demo2 在各自的 session 里开发，但它们描述的是**同一个平台**。
> 修改本文档中的任何"契约"(平台名、DB schema、Action 枚举)必须同步另一个 demo。

---

## 0. 法律与道德边界（最高优先级，不可协商）

本项目是**面试用的学习 demo**。约束如下：

1. **不使用任何真实公司资产**。平台手册、公司规范、存量案例、DB schema、模块名 —— 全部为本文档现场虚构，与任何真实企业无关。
2. **不复刻真实产品**。虚构平台 `ATP` 是通用 Web UI 自动化测试平台的泛化描述，不模仿任何具体商业产品的 UI、命名或专有流程。
3. **不抓取、不爬取任何站点**。demo 中出现的被测站点全部是本地起的静态 mock 页面。
4. **语料全部由 AI 生成**。不要求、不接受用户提供任何来自前雇主的文档或案例。
5. 面试展示时的话术：明确说明"这是我用虚构语料重做的复现 demo，真实实现涉及公司资产我不能带出来"。**这个说法本身在面试里是加分项**，它证明了职业操守。

---

## 1. 虚构世界观：ATP 平台

**ATP (Automation Test Platform)** — 一个企业内部的 Web UI 自动化测试平台。

### 1.1 它是什么

- 测试工程师通过 Web 界面编写**测试案例**，不写代码
- 案例存在 **MySQL** 里，是**关系型结构**（不是 YAML/脚本文件）
- 独立的**执行器 (Executor)** 服务从 DB 读案例，驱动 Selenium Grid 执行
- 平台已运行多年，技术栈是 **Java 8 + Spring 4 + MySQL 5.7**（这是 demo1 用 Java 8 的现实理由）

### 1.2 案例的领域模型（**这是两个 demo 的共享契约**）

```
tc_case  (测试案例主表)
├── case_id        VARCHAR(32)   PK, 平台生成(雪花ID)
├── case_code      VARCHAR(64)   UNIQUE, 业务编号, 规范: ATP-{MODULE}-{4位序号}
├── title          VARCHAR(200)  NOT NULL
├── module_id      VARCHAR(32)   FK -> tc_module, NOT NULL
├── priority       ENUM          P0|P1|P2|P3, NOT NULL
├── author         VARCHAR(64)   NOT NULL
├── precondition   TEXT          NULLABLE
├── status         ENUM          DRAFT|ACTIVE|DEPRECATED, 默认 DRAFT
├── browser        ENUM          CHROME|FIREFOX|EDGE, 默认 CHROME
├── timeout_sec    INT           默认 30, 范围 5..300
├── created_at     DATETIME
└── updated_at     DATETIME

tc_step  (案例步骤表)
├── step_id        VARCHAR(32)   PK
├── case_id        VARCHAR(32)   FK -> tc_case
├── seq            INT           NOT NULL, 从 1 开始连续无跳号
├── action         ENUM          见下方 Action 枚举, NOT NULL
├── locator_type   ENUM          XPATH|CSS|ID|NAME|LINK_TEXT, NULLABLE
├── locator_value  VARCHAR(512)  NULLABLE
├── input_data     VARCHAR(1024) NULLABLE
├── expected       VARCHAR(1024) NULLABLE
├── wait_strategy  ENUM          NONE|PRESENCE|VISIBLE|CLICKABLE, NOT NULL
├── wait_timeout_sec INT         默认 10, 范围 1..120
├── on_failure     ENUM          ABORT|CONTINUE|RETRY, 默认 ABORT
└── description    VARCHAR(500)  NULLABLE

tc_module  (模块字典表, 只读)
├── module_id      VARCHAR(32)   PK
├── module_code    VARCHAR(32)   UNIQUE, 用于 case_code 前缀
└── module_name    VARCHAR(100)
```

### 1.3 Action 枚举（共享契约，两个 demo 必须一致）

| action | locator 必填 | input_data | expected | 说明 |
|---|---|---|---|---|
| `OPEN_URL` | ✗ | ✓ (URL) | ✗ | 打开页面 |
| `CLICK` | ✓ | ✗ | ✗ | 点击元素 |
| `INPUT` | ✓ | ✓ | ✗ | 输入文本 |
| `SELECT` | ✓ | ✓ | ✗ | 下拉选择 |
| `ASSERT_TEXT` | ✓ | ✗ | ✓ | 断言文本内容 |
| `ASSERT_VISIBLE` | ✓ | ✗ | ✗ | 断言元素可见 |
| `ASSERT_NOT_EXIST` | ✓ | ✗ | ✗ | 断言元素不存在 |
| `WAIT_FOR` | ✓ | ✗ | ✗ | 显式等待元素 |
| `SCROLL_TO` | ✓ | ✗ | ✗ | 滚动到元素 |
| `SWITCH_FRAME` | ✓ | ✗ | ✗ | 切换 iframe |
| `SWITCH_WINDOW` | ✗ | ✓ (索引/标题) | ✗ | 切换窗口 |
| `UPLOAD` | ✓ | ✓ (文件路径) | ✗ | 文件上传 |
| `SLEEP` | ✗ | ✓ (秒) | ✗ | **规范禁止使用**，仅历史案例存在 |

### 1.4 模块字典（固定 8 个）

| module_id | module_code | module_name |
|---|---|---|
| M001 | LOGIN | ログイン / 登录认证 |
| M002 | SEARCH | 検索 / 商品搜索 |
| M003 | CART | カート / 购物车 |
| M004 | ORDER | 注文 / 订单管理 |
| M005 | USER | ユーザー管理 / 用户中心 |
| M006 | PAYMENT | 決済 / 支付 |
| M007 | REPORT | レポート / 报表导出 |
| M008 | ADMIN | 管理画面 / 后台管理 |

> 模块名故意做成**日中双语**。理由见 §3 —— 这是为了演示 bge-m3 的跨语言检索能力，
> 也贴合"新加坡人在日本公司，文档中日英混杂"的真实处境。

### 1.5 ATP 公司规范（demo1 的语料来源，demo2 的校验规则来源）

这 8 条规范同时是 demo1 要检索的知识、demo2 要执行的校验：

| 编号 | 规范 | demo2 中的处理 |
|---|---|---|
| STD-001 | XPath 禁止使用绝对路径（`/html/body/...`） | 校验器 ERROR |
| STD-002 | XPath 禁止依赖自动生成的动态 id（如 `id="ext-gen1234"`） | 校验器 WARN |
| STD-003 | XPath 优先使用稳定属性：`data-testid` > `name` > `class` > 文本 | 校验器 INFO 建议 |
| STD-004 | 禁止 `SLEEP`，必须用 `wait_strategy` 显式等待 | 校验器 ERROR |
| STD-005 | `CLICK` 的 wait_strategy 必须是 `CLICKABLE` | 规则自动填充 |
| STD-006 | `ASSERT_*` 的 wait_strategy 必须是 `VISIBLE` | 规则自动填充 |
| STD-007 | case_code 必须符合 `ATP-{MODULE}-{4位序号}` | 规则自动生成 |
| STD-008 | 每条案例至少 1 个断言步骤（`ASSERT_*`） | 校验器 ERROR |

---

## 2. 机器与网络拓扑

```
┌─────────────────────────────┐        ┌───────────────────────────────────────┐
│  开发机 (笔记本)              │        │  服务机 (台式机 192.168.0.101)          │
│  Linux                      │  LAN   │  ⚠️ Windows 11 Pro (日文环境)          │
│  JDK 8/17/21 + Maven        │ ─────► │  9800X3D / 48G DDR5 / RTX5080 16G     │
│  demo1 / demo2 代码          │  SSH   │  driver 610.88 (Blackwell sm_120)     │
└─────────────────────────────┘        │                                       │
              │                        │  ├ TEI tei-embed (bge-m3)       :8081 │
              │                        │  ├ TEI tei-rerank (reranker)    :8082 │
              │ HTTPS                  │  └ Qdrant @ Docker Desktop      :6333 │
              ▼                        └───────────────────────────────────────┘
    ┌──────────────────────┐
    │  DeepSeek API        │  ← 只有"生成/补全"这一步走外网
    │  (后期可切 Kimi K3)   │     开发期用 DeepSeek 省成本
    └──────────────────────┘
```

> 所有连接参数集中在仓库根目录 `.env`（从 `.env.example` 复制）。
> **代码里不要出现任何硬编码的 IP、端口、key。**

### 2.1 需要在服务机上搭的东西

> ⚠️ **服务机是 Windows 11 Pro（日文环境），不是 Linux。** 下面的命令都是 PowerShell。
> 从笔记本可以 `ssh kkaib@192.168.0.101` 进去，但**默认 shell 是 cmd.exe**。
>
> 通过 SSH 跑 PowerShell 时，bash → ssh → powershell 三层引号转义极易崩。
> **可靠做法是用 `-EncodedCommand` 传 base64**：
> ```bash
> psrun() { printf '%s' "$1" | iconv -f UTF-8 -t UTF-16LE | base64 -w0; }
> ssh kkaib@192.168.0.101 "powershell -NoProfile -EncodedCommand $(psrun "$SCRIPT") 2>nul"
> ```
> 另外日文 Windows 的错误信息是 Shift-JIS，直接读会 mojibake；
> 递归扫盘等产生大量 stderr 时 PowerShell 会输出 `#< CLIXML`，需要 `2>nul` 丢掉。

**已知环境**（已探查确认）：

| 项 | 值 |
|---|---|
| Docker | Docker Desktop **29.6.2**（WSL2 backend），`nvidia-container-runtime` 已配置 |
| GPU 直通 | ✅ 已实测：容器内 `cuInit` 与 `cudaGetDeviceCount` 均返回成功，driver 13030 (CUDA 13.3) |
| TEI 镜像 | `ghcr.io/huggingface/text-embeddings-inference:120-1.9.3`（已拉取，3.16 GB） |
| WSL | 只有 `docker-desktop`，**没有通用 Linux 发行版** |
| Docker | Docker Desktop 运行中，笔记本已配好 `docker context remote` |
| C 盘剩余 | 约 503 GB |

**(a) TEI (Text Embeddings Inference) —— 两个容器，已部署验证**

> ✅ **本节命令已在台式机上实际执行并验证通过**，不是纸面方案。
> 选 TEI 而非 llama.cpp 的理由见 §2.3。

**镜像**（⚠️ tag 必须是 `120-`，这是 Blackwell sm_120 专用构建）：

```
ghcr.io/huggingface/text-embeddings-inference:120-1.9.3
```

RTX 5080 是 Blackwell（compute capability **120**）。TEI 按算力分 tag，
`latest` / `89-` / `hopper-` 都**不适用**。sm_120 支持由
[PR #735](https://github.com/huggingface/text-embeddings-inference/pull/735) 引入。

**启动命令**（笔记本上执行即可，`docker context remote` 已指向台式机）：

```bash
# Embedding —— bge-m3，端口 8081
docker run -d --name tei-embed --restart unless-stopped --gpus all \
  -p 8081:80 -v tei_data:/data \
  --tmpfs /usr/local/cuda-12.9/compat \
  ghcr.io/huggingface/text-embeddings-inference:120-1.9.3 \
  --model-id BAAI/bge-m3

# Rerank —— bge-reranker-v2-m3，端口 8082
docker run -d --name tei-rerank --restart unless-stopped --gpus all \
  -p 8082:80 -v tei_data:/data \
  --tmpfs /usr/local/cuda-12.9/compat \
  ghcr.io/huggingface/text-embeddings-inference:120-1.9.3 \
  --model-id BAAI/bge-reranker-v2-m3
```

模型由 TEI 自动从 HuggingFace 拉取，缓存在 named volume `tei_data`（两容器共享）。

---

**⚠️⚠️ `--tmpfs /usr/local/cuda-12.9/compat` 这一行是整个部署的关键，删了就静默退化成 CPU**

这是本项目踩到的最有价值的坑，**面试值得专门讲**。

**现象**：容器正常启动、`/health` 返回 200、API 正常返回 1024 维向量 ——
**一切看起来都对**，只有日志里一行 WARN，和 CPU 风扇的声音不对。

```
WARN Could not find a compatible CUDA device on host: CUDA is not available
     DriverError(CUDA_ERROR_NO_DEVICE, "no CUDA-capable device is detected")
WARN Using CPU instead
INFO Starting Bert model on Cpu          ← 注意是 Cpu
```

实测 CPU 模式下 `tei-embed` 吃掉 **1421% CPU**（14 核满载），GPU 利用率 0%。

**根因**：CUDA 官方镜像自带 forward-compatibility 库：

```
/usr/local/cuda-12.9/compat/libcuda.so.575.57.08
```

这是**原生 Linux 版**的 `libcuda`，设计用于宿主驱动过旧时向前兼容。
但 WSL2 **没有真正的 NVIDIA 内核模块**，只有 `/dev/dxg`，
CUDA 调用必须走转发到 Windows `nvcuda.dll` 的那个特殊 `libcuda`。
一旦加载了 compat 版本，就枚举不到任何设备 → `CUDA_ERROR_NO_DEVICE`。

用 tmpfs 把该目录盖空，强制回落到宿主注入的正确 `libcuda`，问题即解。

**修复后的正确日志**（务必确认这一行）：

```
INFO Starting FlashBert model on Cuda(CudaDevice(DeviceId(1)))
```

注意不仅是 `Cuda`，还是 **FlashBert**（Flash Attention 优化实现）。

**诊断这个坑的过程本身就是面试素材** —— 我最初的验证是错的：

> 我跑了 `docker run --gpus all nvidia/cuda:... nvidia-smi`，看到 GPU 就以为通了。
> 但 **`nvidia-smi` 能跑只证明 driver 的 utility 能力可用，不代表 CUDA compute 可用**。
> 在 WSL 下 `nvidia-smi` 是转发到 Windows 的，它成功和 `cuInit` 成功是两件事。
> 更讽刺的是：手动 `--entrypoint bash` 进容器测 `cuInit` **是成功的**，
> 因为交互式测试和 TEI 二进制的库搜索路径不同 —— 这让误判又多藏了一层。

**教训**：验证 GPU 是否真的在用，唯一可靠的判据是
**看框架日志里的 device 字段 + 看 `nvidia-smi` 的显存增量**，
而不是"容器里能不能看到 GPU"。

**验证服务是否真在 GPU 上**：

```bash
docker logs tei-embed 2>&1 | grep -i "model on"     # 必须出现 Cuda，不能是 Cpu
ssh kkaib@192.168.0.101 "nvidia-smi --query-gpu=memory.used --format=csv,noheader"
```

模型加载后显存应比桌面基线（约 1950 MiB）**增加约 1.6~1.9 GB/模型**。
两个模型都加载后实测约 **5165 MiB**。

---

**API 形状**（⚠️ 与 llama.cpp 不同，写适配器时注意）

| 用途 | 端点 | 请求体 | 说明 |
|---|---|---|---|
| Embedding | `POST /embed` | `{"inputs": "文本"}` | TEI 原生，返回 `[[...1024 维...]]` |
| Embedding | `POST /v1/embeddings` | `{"input": "文本", "model": "bge-m3"}` | **OpenAI 兼容**，langchain4j 可直接用 |
| Rerank | `POST /rerank` | `{"query": "...", "texts": [...]}` | ⚠️ 字段是 **`texts`**，不是 `documents` |
| 健康 | `GET /health` | — | 200 = 就绪 |

返回的 rerank 结果是 `[{index, score}, ...]`，**未排序**，需自己按 score 降序。

**停止 / 重启**：

```bash
docker stop tei-embed tei-rerank
docker start tei-embed tei-rerank
```

**(b) Qdrant — 向量库**

笔记本上已配好 `docker context remote`（指向 `ssh://kkaib@192.168.0.101`），
所以**直接在笔记本敲 docker 命令，容器就跑在台式机上**：

```bash
docker context use remote          # 确认当前 context
docker run -d --name qdrant --restart unless-stopped \
  -p 6333:6333 -p 6334:6334 \
  -v qdrant_storage:/qdrant/storage \
  qdrant/qdrant:v1.11.5
```

> ⚠️⚠️ **tag 必须钉死 `v1.11.5`，不要用 `latest`。**
>
> Qdrant **1.12 起**把 dense 向量从 `Vector.data`(field 1) 挪进了 oneof 的 `dense`(field 101)。
> langchain4j-qdrant **0.35.0** 传递依赖的 qdrant-client 1.11.0 不认识 field 101，
> 会把它当 unknown field 丢掉 → 检索时拿到**空向量**。
>
> 而这个故障**只坏一半**：命中、score、payload 全部正常，只有向量是空的。
> langchain4j 又要拿召回向量在客户端重算 cosine，最终报的是
> `Length of vector a (0) must be equal to the length of vector b (1024)`
> —— 完全指不到「server 版本太新」这个根因上。
>
> 升级 client 也救不了：1.14.1 把 `ScoredPoint.getVectors()` 的返回类型
> 改成了 `VectorsOutput`，langchain4j 编译期绑的旧签名会变成 `NoSuchMethodError`。
> 而 0.35.0 是最后一个 Java 8 字节码版本（demo1 §2 的硬约束），不能升。
>
> 完整定位过程与三种组合的实测结果见 `demo1-atp-rag/DECISIONS.md` **D-002**。
> demo1 的 M0 spike 里已加了 server 版本的前置检查，命中不兼容版本会直接报根因。
>
> **两个端口都要**：6333 是 REST / Web UI，**6334 是 gRPC** —— langchain4j 走的是后者。

> ⚠️ **用 named volume（`qdrant_storage`），不要用 bind mount。**
> 宿主是 Windows + Docker Desktop（WSL2 backend），
> bind mount 一个 Windows 路径会遇到路径转换和权限问题，而且 IO 明显更慢。
> named volume 由 Docker 管在 WSL2 内部，干净且快。

- Web UI: `http://192.168.0.101:6333/dashboard`
  ← **面试演示时可以点开给面试官看召回了哪几条**
- `--restart unless-stopped` 让它随 Docker Desktop 自启，省得每次手动拉
- 选它的理由：langchain4j 0.35 有官方集成、一条命令起、UI 可视化。
  面试被问"为什么不用 pgvector/ES"时的回答见 demo1 文档 §决策记录。

**(c) Windows 防火墙 —— 实测无需配置**

✅ **已验证：Docker Desktop 的端口转发已让 8081 / 8082 / 6333 在局域网直接可达**，
不需要手动加防火墙规则。原因是这三个服务都以容器形式运行，
端口由 Docker Desktop 的后端进程代理，它安装时已注册好规则。

如果换成在 Windows 上**原生**跑服务（非容器），才需要手动放行 ——
以管理员身份开 PowerShell：

```powershell
New-NetFirewallRule -DisplayName "tei-embed" -Direction Inbound `
  -Protocol TCP -LocalPort 8081 -Action Allow -Profile Private
```

并确认网络位置是"プライベート"而非"パブリック"（`Get-NetConnectionProfile`），
否则 Private 规则不生效。
**(d) 从笔记本验证连通**

```bash
curl -s http://192.168.0.101:8081/health && echo " ← embedding OK"
curl -s http://192.168.0.101:8082/health && echo " ← rerank OK"
curl -s http://192.168.0.101:6333/       && echo " ← qdrant OK"
```

**(e) ⚠️ 三个必做的冒烟测试 —— 防的都是「静默失败」**

服务起来了不等于结果是对的。下面三项**开工前必须跑**，
它们防的是同一类问题：**系统照常运行，只是结果悄悄错了**。

**e-1. 确认真的在 GPU 上（最重要，最容易被忽略）**

```bash
docker logs tei-embed  2>&1 | grep -i "model on"
docker logs tei-rerank 2>&1 | grep -i "model on"
```

**期望**：`Starting FlashBert model on Cuda(CudaDevice(...))`
**如果出现 `on Cpu`** → CUDA compat 那个坑，见 §2.1(a)，必须加 `--tmpfs`。

再对一下显存（桌面基线约 1950 MiB，两个模型都加载后实测约 **5165 MiB**）：

```bash
ssh kkaib@192.168.0.101 "nvidia-smi --query-gpu=memory.used --format=csv,noheader"
```

> 本项目**实际踩过这个坑**：服务 health 200、API 正常返回 1024 维向量，
> 一切看起来都对，实际却在 CPU 上跑，14 核满载。
> 最后是靠**CPU 风扇的声音**发现的 —— 这就是"静默失败"的可怕之处。

**e-2. Embedding 维度必须是 1024**

```bash
curl -s http://192.168.0.101:8081/embed \
  -H 'Content-Type: application/json' \
  -d '{"inputs":"XPath 定位器编写规范"}' | jq '.[0] | length'
```

**期望 `1024`**（实测通过）。OpenAI 兼容端点同样可验：

```bash
curl -s http://192.168.0.101:8081/v1/embeddings \
  -H 'Content-Type: application/json' \
  -d '{"input":"ログイン画面のテストケース","model":"bge-m3"}' | jq '.data[0].embedding | length'
```

日文输入也返回 1024（实测通过）—— 顺带验证了 bge-m3 的多语言能力。

**e-3. Rerank 打分方向必须正确**

⚠️ TEI 的字段是 **`texts`**，不是 `documents`；返回**未排序**，需自己降序。

```bash
curl -s http://192.168.0.101:8082/rerank -H 'Content-Type: application/json' -d '{
  "query": "如何编写稳定的 XPath 定位器",
  "texts": [
    "XPath 应优先使用 data-testid 等稳定属性，避免绝对路径",
    "今天的天气非常好，适合出门散步",
    "购物车结算流程的测试要点"
  ]}' | jq -c 'sort_by(-.score) | .[] | {index, score}'
```

**实测基线**（可直接对照）：

```
{"index":0,"score":0.7352616}
{"index":1,"score":0.00001631454}
{"index":2,"score":0.000016187581}
```

相关文档 `0.735`，无关的两条都在 `1e-5` 量级 —— **区分度 4 个数量级**，非常健康。
若三个分数挤在一起或排序错乱，说明 rerank 不可用，
应设 `RERANK_ENABLED=false` 并在消融表中如实标注该行数据缺失。
**绝不能拿一个坏掉的 rerank 去跑评估。**

> 这三项加起来是同一个主题的三个实例。面试被问"你怎么保证检索质量"时，
> **"我把所有会静默失败的地方都变成了显式检查"** 是个比罗列技术栈有力得多的回答。
### 2.2 LLM Provider（生成 / 补全）

**开发期用 DeepSeek，后期切 Kimi K3。** 配置全在根目录 `.env`，切换不改代码。

| | DeepSeek | Kimi K3 |
|---|---|---|
| base_url | `https://api.deepseek.com/v1` | `https://api.moonshot.cn/v1`（国际站 `.ai`） |
| model | `deepseek-v4-flash` / `deepseek-v4-pro` | `kimi-k3`（确切名以控制台为准） |
| OpenAI 兼容 | ✓ | ✓ |
| `response_format: json_object` | ✓ | ✓ |
| `response_format: json_schema` + `strict` | ✗ **不支持** | ✓ 支持 |
| function calling `strict` | beta，需 `/beta` 端点，有已知 bug | ✓ |
| 定位 | 开发期，便宜 | 上线期，结构化输出更强 |

> ⚠️ `deepseek-chat` / `deepseek-reasoner` 已于 **2026-07-24 废弃**，不要再用这两个名字。
> ⚠️ DeepSeek V4 **默认开启 thinking 模式**，更慢更贵且响应带 reasoning 字段。
> 规范化是确定性任务，建议关掉（`LLM_THINKING=false`）。

**这个能力差异是 demo2 的核心设计约束，不是小事**：

DeepSeek 只有 `json_object`（保证是合法 JSON，**不保证符合你的 schema**）；
Kimi 才有 `json_schema` + `strict`。而且 DeepSeek 的 function calling strict 模式
有 [返回 malformed JSON](https://github.com/deepseek-ai/DeepSeek-V3/issues/1069) 和
[拒绝 tool_choice=required](https://github.com/deepseek-ai/DeepSeek-V3/issues/1376) 两个已知缺陷。

**结论：demo2 不依赖任何 provider 的结构化输出特性。**
用最低公分母 `json_object` + 自己做 JSON Schema 校验 + 带错误重试。
provider 支持 strict 时作为**额外保险**开启，但本地校验永不跳过。
详见 `02-HANDOFF-demo2-mcp.md` §3-L3。

### 2.3 为什么是 TEI，不是 llama.cpp / Ollama / Infinity

**llama.cpp 这条路已经放弃** —— 调研发现它对 bge 系列（尤其 reranker）的支持不完善，
`/v1/rerank` 对部分模型存在[打分错误](https://github.com/ggml-org/llama.cpp/issues/16407)。
embedding 勉强能用，但 reranker 不可靠，而 **reranker 坏了不会报错，只会悄悄让检索变差**。

**先澄清一个常见误解：Ollama 慢，但不是"本地部署慢"。** Ollama 慢有三个具体根因：

1. embedding 是后期补的功能，未针对 encoder 模型优化
2. 默认不做真正的批处理，并发请求实际串行
3. `keep_alive` 到期会卸载模型，下次请求要冷启动重新加载

**但对本项目而言，速度根本不是选型依据 —— 因为负载压不到瓶颈**：

| 环节 | 实际负载 | 5080 上的耗时 |
|---|---|---|
| 语料入库 | 约 400 个 chunk，一次性 | 数秒 |
| 单次查询 | 1 次 embed + 40 pair rerank | < 100ms |
| **消融实验（最重）** | 40 query × 6 组配置 ≈ 9600 pair rerank | 十几秒 |

bge-reranker-v2-m3 只有 568M 参数、encoder-only、单次前向。这个量级在 5080 上不构成压力。
**真正的延迟瓶颈是 DeepSeek 的生成调用（秒级），不在本地模型这一侧。**

**所以选 TEI 的实际理由是**：

| 维度 | 说明 |
|---|---|
| **正确性** | 官方支持 bge 全系。实测 rerank 区分度达 **4 个数量级**（0.735 vs 0.000016），llama.cpp 那条路做不到 |
| **标准协议** | 原生 `/embed` `/rerank`，另有 **OpenAI 兼容** `/v1/embeddings`，langchain4j 可直接对接 |
| **专为此设计** | Rust 实现 + 动态 batching + FlashBert 内核，不是把生成模型服务凑合来做 embedding |
| **部署一致** | 走 Docker，`docker context remote` 已通，笔记本一条命令部署到台式机 |
| **架构支持** | 有 sm_120 专用 tag，Blackwell 原生支持 |

**代价**：镜像 3.16 GB，且踩了 CUDA compat 那个坑（§2.1(a)）。
但那个坑一次解决，且本身成了很好的面试素材。

> 面试提示：被问"为什么本地跑推理"时，如果只答"快"会显得没想清楚。
> 正确答法是 §3 那张表 —— **合规、成本、以及把 16G 显存用在刀刃上**，速度反而是最次要的理由。
## 3. 为什么 Embedding/Rerank 本地跑，只有生成走 API

这是**面试必被追问的架构决策**，理由要能脱口而出：

| 维度 | 理由 |
|---|---|
| **成本** | 语料全量入库要跑上万次 embed；每次检索还要跑 rerank。这两个走 API 的调用量是生成的几十倍，但单价并不低。本地跑边际成本为零。 |
| **延迟** | rerank 在检索链路的关键路径上，走外网多 200~500ms。本地 GPU 只要几十 ms。 |
| **数据合规** | 企业 RAG 的硬约束 —— **内部手册和案例不出网**。只有用户的问题和最终召回的片段进 API，这在多数公司的合规审查里是能过的边界。 |
| **显存现实** | 两个都是 568M 参数的 encoder，**Q8_0 量化下各约 600MB**，合计 2~3GB（含 KV cache 与运行时开销）。真正吃显存的是生成模型（要 30B+ 才有可用质量），那才需要走 API。**16G 显存不是"不够用"，是要用在刀刃上。** |
| **模型选型** | bge-m3 支持 100+ 语言且中日英表现均衡。ATP 的手册中日混杂，用纯中文 embedding 模型会在日文查询上崩掉。这是选它而不是选 `text-embedding-3` 的实质理由（不只是省钱）。 |

### 3.1 关于"要不要换更强的 embedding"

**Qwen3-Embedding-8B 目前是 MTEB multilingual 榜首（70.58）**，0.6B 版本很轻量、TEI 可直接加载，
支持 instruction 定制和 32~1024 的可变维度（MRL）。所以确实有更强的选择。

**但建议不要直接替换，而是把它做成消融表的一行**（demo1 §5.3）。理由：

- 评估集只有 40 条，SOTA 与次优的差距**很可能被样本噪声淹没**
- 面试官不会因为你用了榜一模型加分，只会因为**"你怎么知道它对你的语料更好"**加分
- 能说"我在自己的中日混排语料上实测过两个 embedding 模型"，比"我用了榜一"高一个层次

**⚠️ 换 Qwen3-Embedding 有一个实打实的工程坑，正好是好素材**：

bge-m3 是**对称**模型（query 和 document 同样处理）；
Qwen3-Embedding 是**非对称**的 —— query 侧要加 instruction prefix，document 侧不加，**用错会掉点**。
而 langchain4j 0.35 的 `EmbeddingModel` 接口里 query 和 document 走同一个方法，
**没有地方区分这两者**。要么自己实现接口，要么绕开 langchain4j 的抽象。
这个约束值得写进 `DECISIONS.md`。

**⚠️ Reranker 不要换。** bge-reranker-v2-m3 在 TEI 上**已实测可用**，
区分度达 4 个数量级（§2.1(e) 有基线数值）。换模型意味着这套验证要重做一遍，
而 **reranker 坏了不报错，只会悄悄让检索变差**。
**embedding 可以冒险，reranker 不要 —— 它坏了你不一定看得出来。**

---

## 4. 两个 demo 的关系（面试叙事主线）

不要把它们讲成两个孤立的 demo。它们是**同一个遗留平台上，AI 的两次不同介入**：

```
        ┌──────────────────────────────────────────────┐
        │              ATP 遗留测试平台                  │
        │           Java 8 / Spring 4 / MySQL          │
        └──────────────────────────────────────────────┘
                 ▲                          ▲
                 │                          │
    ┌────────────┴──────────┐   ┌───────────┴─────────────┐
    │  demo1: 知识侧          │   │  demo2: 生产侧            │
    │  RAG 知识助手           │   │  MCP 规范化服务            │
    │                       │   │                          │
    │  让人更快学会用平台      │   │  让 agent 产出能进平台     │
    │  Java 8 + langchain4j │   │  Java 17 + MCP SDK       │
    │  "读"                  │   │  "写"                    │
    └───────────────────────┘   └──────────────────────────┘
```

**一句话主线**：
> "我们的老平台有两个瓶颈：新人上手慢，以及 AI 生成的案例进不了库。
> 第一个是**检索问题**，我用 RAG 解决；第二个是**结构化输出的可靠性问题**，我用 MCP + schema 约束解决。
> 它们看起来都是'接大模型'，但工程重点完全不同 ——
> 前者的难点是**召回质量怎么量化**，后者的难点是**怎么让模型少做事**。"

这句话就是你整场面试的骨架。两个 demo 都是为了支撑它。

---

## 5. 目录约定

```
/home/kanashi/Applications/interview-demos/
├── 00-SHARED-CONTEXT.md          ← 本文档
├── 01-HANDOFF-demo1-rag.md       ← demo1 session 的入口文档
├── 02-HANDOFF-demo2-mcp.md       ← demo2 session 的入口文档
├── demo1-atp-rag/                ← demo1 session 在此目录启动
└── demo2-atp-mcp/                ← demo2 session 在此目录启动
```

两个 session 各自 `git init`，互不干扰。共享契约（§1.2 领域模型、§1.3 Action 枚举）
如需变更，**先改本文档，再通知另一个 session**。
