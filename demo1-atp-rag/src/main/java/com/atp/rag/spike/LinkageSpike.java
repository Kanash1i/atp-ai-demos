package com.atp.rag.spike;

import com.atp.rag.config.Env;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * M0 — Java 8 链路 spike。
 *
 * <p>目的不是实现功能，而是<b>证伪</b>：确认整条链路在 JDK 8 上真的跑得起来，
 * 而不是等业务代码写到一半才发现某个传递依赖是 Java 17 字节码。验证四件事：
 *
 * <ol>
 *   <li>运行时确实是 JDK 8（编译过 ≠ 跑得起来）</li>
 *   <li>TEI 的 OpenAI 兼容端点能返回 1024 维向量</li>
 *   <li>Qdrant 的 gRPC 通道（建 collection / 写 / 检索 / 删）在 Java 8 上可用
 *       —— 这是最大的风险点，grpc-netty-shaded 是整个依赖树里最重的一块</li>
 *   <li>TEI 的 /rerank 打分方向正确（相关项分数显著高于无关项）</li>
 * </ol>
 *
 * <p>任何一步失败都会打印对应的降级方案（交接文档 §2.2 的方案 B/C/D）。
 *
 * <p>跑法：{@code mvn -q compile exec:java -Dexec.mainClass=com.atp.rag.spike.LinkageSpike}
 */
public final class LinkageSpike {

    /** spike 用的临时 collection，跑完即删，不污染 atp_docs / atp_cases。 */
    private static final String SPIKE_COLLECTION = "spike_linkage_check";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<String> RERANK_DOCS = Arrays.asList(
            "XPath 应优先使用 data-testid 等稳定属性，避免绝对路径",
            "今天的天气非常好，适合出门散步",
            "购物车结算流程的测试要点");

    /** 相关项与无关项的分数至少要拉开这么多倍，否则认为 rerank 不可信。 */
    private static final double MIN_SCORE_RATIO = 100.0;

    private LinkageSpike() {
    }

    public static void main(String[] args) {
        System.out.println("=== M0 Java 8 链路 spike ===\n");
        List<String> failures = new ArrayList<String>();

        runStep("0. 运行时环境", failures, new Step() {
            public void run() {
                checkRuntime();
            }
        });
        runStep("1. TEI embedding (bge-m3)", failures, new Step() {
            public void run() {
                checkEmbedding();
            }
        });
        runStep("2. Qdrant gRPC 读写", failures, new Step() {
            public void run() {
                checkQdrant();
            }
        });
        runStep("3. TEI rerank 打分方向", failures, new Step() {
            public void run() {
                checkRerank();
            }
        });

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("=== 全部通过，M0 链路打通，可以进 M1 ===");
        } else {
            System.out.println("=== 失败 " + failures.size() + " 项 ===");
            for (String f : failures) {
                System.out.println("  - " + f);
            }
            System.exit(1);
        }
    }

    // ── step 0 ────────────────────────────────────────────────────────────

    private static void checkRuntime() {
        String version = System.getProperty("java.version");
        System.out.println("   java.version = " + version
                + " (" + System.getProperty("java.vendor") + ")");
        System.out.println("   .env         = " + Env.dotEnvPath());

        // 编译期 target 1.8 只保证语法，真正跑在哪个 JVM 上得看运行时。
        // spike 要是在 JDK 17 上跑通了，对这个 demo 毫无意义。
        if (!version.startsWith("1.8")) {
            throw new IllegalStateException(
                    "运行时不是 JDK 8（实际 " + version + "）。"
                            + "spike 必须在 JDK 8 上跑才有意义：source "
                            + "\"$HOME/.sdkman/bin/sdkman-init.sh\" && sdk env");
        }
    }

    // ── step 1 ────────────────────────────────────────────────────────────

    private static void checkEmbedding() {
        EmbeddingModel model = OpenAiEmbeddingModel.builder()
                .baseUrl(openAiCompatibleBaseUrl())
                // TEI 没设 api-key，但 builder 要求非空 —— 传占位符
                .apiKey("dummy")
                .modelName(Env.get("EMBEDDING_MODEL", "bge-m3"))
                .build();

        // 中日双语各测一条：ATP 的语料是中日混排的，
        // 只测中文的话，日文侧的问题要到 M4 评估时才会暴露。
        int zhDim = model.embed("XPath 定位器编写规范").content().dimension();
        int jaDim = model.embed("ログイン画面のテストケース").content().dimension();
        System.out.println("   中文 → " + zhDim + " 维，日文 → " + jaDim + " 维");

        int expected = Env.getInt("EMBEDDING_DIM", 1024);
        if (zhDim != expected || jaDim != expected) {
            throw new IllegalStateException("维度不是 " + expected
                    + "，与 Qdrant collection 维度不一致，入库会失败");
        }
    }

    // ── step 2 ────────────────────────────────────────────────────────────

    private static void checkQdrant() {
        String host = Env.require("QDRANT_HOST");
        int grpcPort = Env.getInt("QDRANT_GRPC_PORT", 6334);
        int dim = Env.getInt("EMBEDDING_DIM", 1024);

        checkQdrantVersion(host);
        System.out.println("   gRPC " + host + ":" + grpcPort);

        QdrantClient client = new QdrantClient(
                QdrantGrpcClient.newBuilder(host, grpcPort, false).build());
        try {
            // 先删再建：spike 可能反复跑，残留的旧 collection 会让 create 报 already exists
            deleteQuietly(client, SPIKE_COLLECTION);
            client.createCollectionAsync(SPIKE_COLLECTION, VectorParams.newBuilder()
                    .setSize(dim)
                    .setDistance(Distance.Cosine)
                    .build()).get();
            System.out.println("   建 collection " + SPIKE_COLLECTION
                    + " (dim=" + dim + ", Cosine) ✓");

            QdrantEmbeddingStore store = QdrantEmbeddingStore.builder()
                    .client(client)
                    .collectionName(SPIKE_COLLECTION)
                    .build();

            // 用确定性的假向量，避免这一步的成败依赖 embedding 服务 ——
            // 这里只想测 gRPC 通道本身。
            store.add(unitVector(dim, 0), TextSegment.from("第一条 spike 文本"));
            store.add(unitVector(dim, 1), TextSegment.from("第二条 spike 文本"));

            List<EmbeddingMatch<TextSegment>> matches = store.search(
                    EmbeddingSearchRequest.builder()
                            .queryEmbedding(unitVector(dim, 0))
                            .maxResults(2)
                            .build()).matches();

            if (matches.isEmpty()) {
                throw new IllegalStateException("写入成功但检索为空 —— 可能是索引尚未就绪");
            }
            EmbeddingMatch<TextSegment> top = matches.get(0);
            System.out.println("   写 2 条 → 检索命中 " + matches.size() + " 条，"
                    + "top1 score=" + String.format("%.4f", top.score())
                    + " text=\"" + top.embedded().text() + "\"");

            // 查询向量和第一条完全相同，cosine 应该命中它。
            // 命中另一条说明维度或距离度量配错了。
            if (!"第一条 spike 文本".equals(top.embedded().text())) {
                throw new IllegalStateException(
                        "top1 不是预期的那条，检查 collection 的距离度量是否为 Cosine");
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Qdrant gRPC 链路失败。"
                    + "先确认不是 server 版本问题（见 DECISIONS.md D-002）；"
                    + "若根因确为 Java 8 不兼容，按交接文档 §2.2 退到方案 B（Qdrant REST）"
                    + "→ C（pgvector）→ D（InMemory），并记进 DECISIONS.md", e);
        } finally {
            deleteQuietly(client, SPIKE_COLLECTION);
            client.close();
        }
    }

    /**
     * 前置检查 Qdrant server 版本 —— 这道检查存在的唯一理由是那个报错太不像根因。
     *
     * <p>Qdrant 1.12 起把 dense 向量从 {@code Vector.data}(field 1) 挪进了
     * oneof 的 {@code dense}(field 101)。langchain4j-qdrant 0.35.0 绑定的
     * qdrant-client 1.11.0 不认识 101，会把它当 unknown field 丢掉，
     * 于是 {@code getDataList()} 返回<b>空</b>向量。
     *
     * <p>恶心的地方在于它只坏一半：命中、score、payload 全是对的，
     * 只有向量是空的。而 langchain4j 不信任服务端 score、要拿召回向量重算一遍 cosine，
     * 最终抛出的是 {@code Length of vector a (0) must be equal to the length of vector b (1024)}
     * —— 这个报错完全指不到「server 版本太新」这个根因上。
     *
     * <p>升级 client 也救不了：1.14.1 把 {@code ScoredPoint.getVectors()} 的返回类型
     * 改成了 {@code VectorsOutput}，langchain4j 编译期绑的旧签名会变成 NoSuchMethodError。
     */
    private static void checkQdrantVersion(String host) {
        int restPort = Env.getInt("QDRANT_PORT", 6333);
        String version;
        try {
            version = getJson("http://" + host + ":" + restPort + "/")
                    .path("version").asText("");
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "连不上 Qdrant REST (" + host + ":" + restPort + ")，服务没起？", e);
        }

        System.out.println("   server version = " + version);
        String[] parts = version.split("\\.");
        if (parts.length < 2) {
            System.out.println("   ⚠️ 版本号解析不了，跳过兼容性检查");
            return;
        }
        int major = Integer.parseInt(parts[0]);
        int minor = Integer.parseInt(parts[1]);
        if (major > 1 || minor > 11) {
            throw new IllegalStateException("Qdrant " + version
                    + " 与 langchain4j-qdrant 0.35.0 不兼容（1.12+ 把 dense 向量挪到了"
                    + " oneof field 101，旧 proto 读出来是空向量）。"
                    + "降回 v1.11.5，或按 DECISIONS.md D-002 自己实现 EmbeddingStore.search");
        }
    }

    /** 先查存在再删 —— 直接删不存在的 collection，qdrant client 会自己打一条 ERROR 日志，吵。 */
    private static void deleteQuietly(QdrantClient client, String collection) {
        try {
            if (client.collectionExistsAsync(collection).get()) {
                client.deleteCollectionAsync(collection).get();
            }
        } catch (Exception ignored) {
            // 清理失败不该让 spike 失败：残留的 collection 下次跑会被重新删掉
        }
    }

    /** 造一个第 {@code axis} 维为 1、其余为 0 的单位向量。 */
    private static Embedding unitVector(int dim, int axis) {
        float[] v = new float[dim];
        v[axis] = 1.0f;
        return Embedding.from(v);
    }

    // ── step 3 ────────────────────────────────────────────────────────────

    private static void checkRerank() {
        if (!Env.getBoolean("RERANK_ENABLED", true)) {
            System.out.println("   RERANK_ENABLED=false，跳过");
            return;
        }
        String url = Env.require("RERANK_BASE_URL") + "/rerank";

        ObjectNode body = MAPPER.createObjectNode();
        body.put("query", "如何编写稳定的 XPath 定位器");
        // ⚠️ TEI 的字段是 texts，不是 OpenAI/Cohere 那套 documents
        ArrayNode texts = body.putArray("texts");
        for (String doc : RERANK_DOCS) {
            texts.add(doc);
        }

        JsonNode response = postJson(url, body.toString());

        // ⚠️ TEI 返回的是 [{index, score}, ...] 且**未排序**，得自己按 score 降序
        double[] scores = new double[RERANK_DOCS.size()];
        for (JsonNode item : response) {
            scores[item.get("index").asInt()] = item.get("score").asDouble();
        }
        for (int i = 0; i < scores.length; i++) {
            System.out.println("   [" + i + "] score=" + String.format("%.8f", scores[i])
                    + "  " + RERANK_DOCS.get(i));
        }

        // 第 0 条是唯一与 query 相关的。相关项没有显著胜出，就说明 rerank 不可信。
        double relevant = scores[0];
        double bestIrrelevant = Math.max(scores[1], scores[2]);
        if (relevant <= bestIrrelevant) {
            throw new IllegalStateException("rerank 排序方向错误：相关项("
                    + relevant + ") 不高于无关项(" + bestIrrelevant + ")");
        }
        if (relevant < bestIrrelevant * MIN_SCORE_RATIO) {
            throw new IllegalStateException("rerank 区分度不足（相关/无关 = "
                    + String.format("%.1f", relevant / bestIrrelevant)
                    + " 倍，基线约 4 个数量级）。"
                    + "宁可设 RERANK_ENABLED=false 并在消融表里标注缺失，"
                    + "也不要拿一个坏掉的 rerank 去跑评估");
        }
        System.out.println("   区分度 = " + String.format("%.0f", relevant / bestIrrelevant)
                + " 倍（基线约 4 个数量级）✓");
    }

    /**
     * 用 JDK 自带的 HttpURLConnection 发 POST。
     *
     * <p>Java 8 没有 {@code java.net.http.HttpClient}（那是 11 才有的）。
     * classpath 上虽然有 okhttp（langchain4j-open-ai 的传递依赖），
     * 但 spike 阶段刻意不依赖它 —— 免得 rerank 这一步的失败和 langchain4j 的问题混在一起。
     * 正式的 TeiScoringModel 再考虑换。
     */
    private static JsonNode getJson(String url) {
        return request(url, null);
    }

    private static JsonNode postJson(String url, String json) {
        return request(url, json);
    }

    /** {@code body} 为 null 时发 GET，否则发 POST。 */
    private static JsonNode request(String url, String body) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod(body == null ? "GET" : "POST");
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(30_000);

            if (body != null) {
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                OutputStream out = conn.getOutputStream();
                try {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                } finally {
                    out.close();
                }
            }

            int status = conn.getResponseCode();
            // 错误响应要从 errorStream 读，从 inputStream 读会直接抛 IOException，
            // 吞掉服务端返回的原因（比如字段名写成了 documents）
            InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String responseBody = readAll(in);
            if (status >= 400) {
                throw new IllegalStateException("HTTP " + status + " from " + url
                        + " → " + responseBody);
            }
            return MAPPER.readTree(responseBody);
        } catch (IOException e) {
            throw new IllegalStateException("请求 " + url + " 失败", e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        try {
            while ((n = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, n);
            }
        } finally {
            in.close();
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    // ── 公共 ──────────────────────────────────────────────────────────────

    /**
     * TEI 同时提供原生端点（/embed、/rerank）和 OpenAI 兼容端点（/v1/embeddings），
     * 所以 {@code EMBEDDING_BASE_URL} 配的是不带路径的根，用哪个端点由代码决定。
     * langchain4j 的 OpenAI 客户端会在 baseUrl 后面拼 {@code embeddings}，因此这里补 {@code /v1}。
     */
    private static String openAiCompatibleBaseUrl() {
        String base = Env.require("EMBEDDING_BASE_URL");
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base.endsWith("/v1") ? base : base + "/v1";
    }

    private interface Step {
        void run();
    }

    private static void runStep(String name, List<String> failures, Step step) {
        System.out.println("[ " + name + " ]");
        long startedAt = System.currentTimeMillis();
        try {
            step.run();
            System.out.println("   ✅ 通过 (" + (System.currentTimeMillis() - startedAt) + "ms)\n");
        } catch (RuntimeException e) {
            System.out.println("   ❌ 失败: " + e.getMessage());
            if (e.getCause() != null) {
                System.out.println("      cause: " + e.getCause());
            }
            System.out.println();
            failures.add(name + " — " + e.getMessage());
        }
    }
}
