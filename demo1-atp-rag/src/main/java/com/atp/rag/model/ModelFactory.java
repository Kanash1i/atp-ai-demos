package com.atp.rag.model;

import com.atp.rag.config.Env;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;

import java.time.Duration;

/**
 * 统一构造模型客户端与 Qdrant 连接。
 *
 * <p>集中在一处的理由不只是「避免重复」——更重要的是**代码里不得出现硬编码的 key / URL / IP**，
 * 只要构造入口唯一，这条约束就容易守住。
 *
 * <p>TEI 与 DeepSeek 都是 OpenAI 兼容的，所以 embedding 和生成用的是同一个 langchain4j
 * 客户端实现，只是 baseUrl 不同。唯一不兼容的是 rerank —— 那个得自己写适配器，见
 * {@code TeiScoringModel}。
 */
public final class ModelFactory {

    private ModelFactory() {
    }

    /**
     * TEI 的 embedding 模型（bge-m3）。
     *
     * <p>TEI 没设 api-key，但 langchain4j 的 builder 要求非空，所以传占位符。
     */
    public static EmbeddingModel embeddingModel() {
        return OpenAiEmbeddingModel.builder()
                .baseUrl(openAiCompatibleBaseUrl(Env.require("EMBEDDING_BASE_URL")))
                .apiKey("dummy")
                .modelName(Env.get("EMBEDDING_MODEL", "bge-m3"))
                .timeout(Duration.ofSeconds(Env.getInt("EMBEDDING_TIMEOUT_SEC", 60)))
                .maxRetries(Env.getInt("EMBEDDING_MAX_RETRIES", 2))
                .build();
    }

    /** DeepSeek（OpenAI 兼容）。temperature 默认 0 —— 检索问答不需要创造性。 */
    public static ChatLanguageModel chatModel() {
        return OpenAiChatModel.builder()
                .baseUrl(Env.require("LLM_BASE_URL"))
                .apiKey(Env.require("LLM_API_KEY"))
                .modelName(Env.require("LLM_MODEL"))
                .temperature(Double.parseDouble(Env.get("LLM_TEMPERATURE", "0")))
                .timeout(Duration.ofSeconds(Env.getInt("LLM_TIMEOUT_SEC", 120)))
                .build();
    }

    /**
     * Qdrant 的 gRPC 客户端。
     *
     * <p>⚠️ 端口是 <b>6334</b>（gRPC）不是 6333（REST）。而且 server 版本必须是 1.11.x ——
     * 1.12+ 把 dense 向量挪到了新的 proto 字段，langchain4j 0.35 读出来是空向量。
     * 完整分析见 DECISIONS.md D-002。
     */
    public static QdrantClient qdrantClient() {
        String host = Env.require("QDRANT_HOST");
        requireCompatibleQdrant(host);
        return new QdrantClient(QdrantGrpcClient.newBuilder(
                host,
                Env.getInt("QDRANT_GRPC_PORT", 6334),
                false).build());
    }

    /**
     * 拒绝连上不兼容的 Qdrant。
     *
     * <p>这道检查最初只在 M0 的 spike 里，后来挪到了这里 —— 因为服务重启时很容易
     * 又拉成 {@code latest}，而<b>入库阶段完全不会报错</b>：写入走 upsert，proto 没变。
     * 问题要到检索时才以「向量长度 0」的形式爆出来，那个报错还指不到根因上。
     *
     * <p>放在客户端构造这一步，入库、检索、评估就都绕不过去了。
     * 详见 DECISIONS.md D-002。
     */
    private static void requireCompatibleQdrant(String host) {
        String version = fetchQdrantVersion(host);
        String[] parts = version.split("\\.");
        if (parts.length < 2) {
            return;     // 版本号读不出来就不拦，别让检查本身成为故障源
        }
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            if (major > 1 || minor > 11) {
                throw new IllegalStateException("Qdrant " + version
                        + " 与 langchain4j-qdrant 0.35.0 不兼容："
                        + "1.12+ 把 dense 向量挪进了 oneof field 101，旧 proto 读出来是空向量。"
                        + "入库不会报错，问题会在检索时才暴露。"
                        + "请改用 qdrant/qdrant:v1.11.5（不要用 latest），详见 DECISIONS.md D-002");
            }
        } catch (NumberFormatException ignored) {
            // 同上：版本号格式意外时放行
        }
    }

    private static String fetchQdrantVersion(String host) {
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(
                    qdrantRestBaseUrl() + "/").openConnection();
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(5_000);
            java.io.InputStream in = conn.getInputStream();
            try {
                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int n;
                while ((n = in.read(chunk)) != -1) {
                    buffer.write(chunk, 0, n);
                }
                return new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(buffer.toByteArray()).path("version").asText("");
            } finally {
                in.close();
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("连不上 Qdrant REST (" + qdrantRestBaseUrl()
                    + ")，服务没起？", e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** Qdrant 的 REST 根地址，用于建 collection、查点数这类管理操作。 */
    public static String qdrantRestBaseUrl() {
        return "http://" + Env.require("QDRANT_HOST") + ":" + Env.getInt("QDRANT_PORT", 6333);
    }

    /**
     * TEI 同时提供原生端点（{@code /embed}、{@code /rerank}）和 OpenAI 兼容端点
     * （{@code /v1/embeddings}），所以 {@code *_BASE_URL} 配的是不带路径的根，
     * 用哪个端点由代码决定。langchain4j 的 OpenAI 客户端会在 baseUrl 后拼 {@code embeddings}，
     * 因此这里补上 {@code /v1}。
     */
    private static String openAiCompatibleBaseUrl(String base) {
        String trimmed = base;
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.endsWith("/v1") ? trimmed : trimmed + "/v1";
    }
}
