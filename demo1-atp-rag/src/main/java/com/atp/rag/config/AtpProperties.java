package com.atp.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConstructorBinding;

/**
 * 全部配置的类型安全绑定，替代原先手写的 {@code Env} 工具类。
 *
 * <p>手写那版做了四件事：找 {@code .env}、解析、展开 {@code ${VAR}}、类型转换。
 * 这四件 Spring Boot 全都原生支持 ——
 * {@code spring.config.import} 负责加载，占位符解析器负责展开，
 * {@code @ConfigurationProperties} 负责绑定与类型转换。
 *
 * <p>用构造器绑定而不是 setter，好处是<b>字段可以是 final，且缺失必填项时在启动阶段就失败</b>。
 * 这正是原先 {@code Env.require()} 想达到的效果，只是现在由框架保证，
 * 不需要每个调用点自己记得调 {@code require} 而不是 {@code get}。
 */
@ConfigurationProperties(prefix = "atp")
@ConstructorBinding
public class AtpProperties {

    private final Embedding embedding;
    private final Rerank rerank;
    private final Llm llm;
    private final Qdrant qdrant;
    private final Corpus corpus;
    private final Rag rag;

    public AtpProperties(Embedding embedding, Rerank rerank, Llm llm,
                         Qdrant qdrant, Corpus corpus, Rag rag) {
        this.embedding = embedding;
        this.rerank = rerank;
        this.llm = llm;
        this.qdrant = qdrant;
        this.corpus = corpus;
        this.rag = rag;
    }

    public Embedding getEmbedding() {
        return embedding;
    }

    public Rerank getRerank() {
        return rerank;
    }

    public Llm getLlm() {
        return llm;
    }

    public Qdrant getQdrant() {
        return qdrant;
    }

    public Corpus getCorpus() {
        return corpus;
    }

    public Rag getRag() {
        return rag;
    }

    // ── embedding ────────────────────────────────────────────

    public static class Embedding {
        private final String baseUrl;
        private final String model;
        private final int dimension;
        private final int timeoutSeconds;
        private final int maxRetries;
        private final String tag;

        public Embedding(String baseUrl, String model, int dimension,
                         int timeoutSeconds, int maxRetries, String tag) {
            this.baseUrl = baseUrl;
            this.model = model;
            this.dimension = dimension;
            this.timeoutSeconds = timeoutSeconds;
            this.maxRetries = maxRetries;
            this.tag = tag == null ? "" : tag;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        /**
         * TEI 同时提供原生端点（{@code /embed}）和 OpenAI 兼容端点（{@code /v1/embeddings}），
         * 所以配的是不带路径的根。langchain4j 的 OpenAI 客户端会在 baseUrl 后拼
         * {@code embeddings}，因此这里补 {@code /v1}。
         */
        public String getOpenAiCompatibleBaseUrl() {
            String trimmed = baseUrl;
            while (trimmed.endsWith("/")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            return trimmed.endsWith("/v1") ? trimmed : trimmed + "/v1";
        }

        public String getModel() {
            return model;
        }

        public int getDimension() {
            return dimension;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public String getTag() {
            return tag;
        }
    }

    // ── rerank ───────────────────────────────────────────────

    public static class Rerank {
        private final String baseUrl;
        private final String model;
        private final boolean enabled;
        private final int timeoutSeconds;
        private final int maxBatch;
        private final double relativeFloor;
        private final double minScore;

        public Rerank(String baseUrl, String model, boolean enabled, int timeoutSeconds,
                      int maxBatch, double relativeFloor, double minScore) {
            this.baseUrl = baseUrl;
            this.model = model;
            this.enabled = enabled;
            this.timeoutSeconds = timeoutSeconds;
            this.maxBatch = maxBatch;
            this.relativeFloor = relativeFloor;
            this.minScore = minScore;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public String getRerankEndpoint() {
            String trimmed = baseUrl;
            while (trimmed.endsWith("/")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            return trimmed + "/rerank";
        }

        public String getHealthEndpoint() {
            String trimmed = baseUrl;
            while (trimmed.endsWith("/")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            return trimmed + "/health";
        }

        public String getModel() {
            return model;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public int getMaxBatch() {
            return maxBatch;
        }

        /** 截断阈值相对 top1 的比例。 */
        public double getRelativeFloor() {
            return relativeFloor;
        }

        /** 绝对下限，只用来挡分数趋近 0 的纯噪音。 */
        public double getMinScore() {
            return minScore;
        }
    }

    // ── llm ──────────────────────────────────────────────────

    public static class Llm {
        private final String baseUrl;
        private final String model;
        private final String apiKey;
        private final double temperature;
        private final int timeoutSeconds;

        public Llm(String baseUrl, String model, String apiKey,
                   double temperature, int timeoutSeconds) {
            this.baseUrl = baseUrl;
            this.model = model;
            this.apiKey = apiKey == null ? "" : apiKey;
            this.temperature = temperature;
            this.timeoutSeconds = timeoutSeconds;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public String getModel() {
            return model;
        }

        public String getApiKey() {
            return apiKey;
        }

        /**
         * key 是否可用。
         *
         * <p>刻意<b>不</b>把它做成必填项：检索链路完全不需要 LLM，
         * M4 的检索指标也不需要。缺 key 时应该降级成只跑检索并明确提示，
         * 而不是让整个应用起不来。
         */
        public boolean isConfigured() {
            return !apiKey.trim().isEmpty();
        }

        public double getTemperature() {
            return temperature;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }
    }

    // ── qdrant ───────────────────────────────────────────────

    public static class Qdrant {
        private final String host;
        private final int restPort;
        private final int grpcPort;
        private final String collectionPrefix;

        public Qdrant(String host, int restPort, int grpcPort, String collectionPrefix) {
            this.host = host;
            this.restPort = restPort;
            this.grpcPort = grpcPort;
            this.collectionPrefix = collectionPrefix;
        }

        public String getHost() {
            return host;
        }

        public int getRestPort() {
            return restPort;
        }

        /** ⚠️ langchain4j-qdrant 走的是这个端口，不是 REST 的 6333。 */
        public int getGrpcPort() {
            return grpcPort;
        }

        public String getCollectionPrefix() {
            return collectionPrefix;
        }

        /** 管理操作（建 collection、查点数、校验版本）用 REST 更方便。 */
        public String getRestBaseUrl() {
            return "http://" + host + ":" + restPort;
        }
    }

    // ── corpus ───────────────────────────────────────────────

    public static class Corpus {
        private final String dir;

        public Corpus(String dir) {
            this.dir = dir;
        }

        public String getDir() {
            return dir;
        }
    }

    // ── rag（消融开关）────────────────────────────────────────

    public static class Rag {
        private final RagConfig.ChunkStrategy chunkStrategy;
        private final RagConfig.CollectionMode collectionMode;
        private final int chunkSizeChars;
        private final int chunkOverlapChars;
        private final boolean queryRewriteEnabled;
        private final boolean refusalPromptEnabled;
        private final int candidateTopK;
        private final int finalTopK;

        public Rag(RagConfig.ChunkStrategy chunkStrategy, RagConfig.CollectionMode collectionMode,
                   int chunkSizeChars, int chunkOverlapChars, boolean queryRewriteEnabled,
                   boolean refusalPromptEnabled, int candidateTopK, int finalTopK) {
            this.chunkStrategy = chunkStrategy;
            this.collectionMode = collectionMode;
            this.chunkSizeChars = chunkSizeChars;
            this.chunkOverlapChars = chunkOverlapChars;
            this.queryRewriteEnabled = queryRewriteEnabled;
            this.refusalPromptEnabled = refusalPromptEnabled;
            this.candidateTopK = candidateTopK;
            this.finalTopK = finalTopK;
        }

        public RagConfig.ChunkStrategy getChunkStrategy() {
            return chunkStrategy;
        }

        public RagConfig.CollectionMode getCollectionMode() {
            return collectionMode;
        }

        public int getChunkSizeChars() {
            return chunkSizeChars;
        }

        public int getChunkOverlapChars() {
            return chunkOverlapChars;
        }

        public boolean isQueryRewriteEnabled() {
            return queryRewriteEnabled;
        }

        public boolean isRefusalPromptEnabled() {
            return refusalPromptEnabled;
        }

        public int getCandidateTopK() {
            return candidateTopK;
        }

        public int getFinalTopK() {
            return finalTopK;
        }
    }
}
