package com.atp.rag.config;

import com.atp.rag.model.TeiScoringModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.scoring.ScoringModel;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;

/**
 * 模型客户端与 Qdrant 连接的装配。
 *
 * <p>替代了原先手写的 {@code ModelFactory} 静态工厂。差别不只是写法 ——
 * {@code QdrantClient} 的生命周期原来靠每个调用方自己记得 {@code close()}
 * （`IngestMain`、`DemoRun`、`Main`、各个测试都写了一遍 try-finally），
 * 现在交给容器的 {@code destroyMethod}，漏关的可能性从设计上消除了。
 */
@Configuration
public class ModelConfig {

    private static final Logger log = LoggerFactory.getLogger(ModelConfig.class);

    /** TEI 的 embedding 模型（bge-m3）。TEI 没设 api-key，但 builder 要求非空，传占位符。 */
    @Bean
    public EmbeddingModel embeddingModel(AtpProperties props) {
        AtpProperties.Embedding cfg = props.getEmbedding();
        log.info("embedding: {} @ {}", cfg.getModel(), cfg.getBaseUrl());
        return OpenAiEmbeddingModel.builder()
                .baseUrl(cfg.getOpenAiCompatibleBaseUrl())
                .apiKey("dummy")
                .modelName(cfg.getModel())
                .timeout(Duration.ofSeconds(cfg.getTimeoutSeconds()))
                .maxRetries(cfg.getMaxRetries())
                .build();
    }

    /**
     * DeepSeek（OpenAI 兼容）。
     *
     * <p>{@code api-key} 为空时<b>不注册这个 bean</b> —— 检索链路完全不需要 LLM，
     * M4 的检索指标也不需要。缺 key 应该降级成只跑检索，而不是让应用起不来。
     * 注入方用 {@code ObjectProvider} 接收，自行处理缺失。
     */
    @Bean
    @ConditionalOnExpression("!'${atp.llm.api-key:}'.trim().isEmpty()")
    public ChatLanguageModel chatModel(AtpProperties props) {
        AtpProperties.Llm cfg = props.getLlm();
        log.info("llm: {} @ {}", cfg.getModel(), cfg.getBaseUrl());
        return OpenAiChatModel.builder()
                .baseUrl(cfg.getBaseUrl())
                .apiKey(cfg.getApiKey())
                .modelName(cfg.getModel())
                .temperature(cfg.getTemperature())
                .timeout(Duration.ofSeconds(cfg.getTimeoutSeconds()))
                .build();
    }

    /** rerank 适配器。关掉时不注册，注入方按缺失处理。 */
    @Bean
    @ConditionalOnProperty(name = "atp.rerank.enabled", havingValue = "true", matchIfMissing = true)
    public ScoringModel scoringModel(AtpProperties props) {
        AtpProperties.Rerank cfg = props.getRerank();
        log.info("rerank: {} @ {}", cfg.getModel(), cfg.getBaseUrl());
        return new TeiScoringModel(cfg);
    }

    /**
     * Qdrant 的 gRPC 客户端。
     *
     * <p>{@code destroyMethod} 让容器负责关闭，不再依赖调用方的 try-finally。
     *
     * <p>⚠️ 端口是 <b>6334</b>（gRPC）不是 6333（REST）。而且 server 版本必须是 1.11.x ——
     * 1.12+ 把 dense 向量挪到了新的 proto 字段，langchain4j 0.35 读出来是空向量。
     * 版本校验放在这里，是因为**入库阶段不会因为版本不对而报错**
     * （写入走 upsert，proto 没变），问题要到检索时才暴露。
     * 放在 bean 构造这一步，入库、检索、评估就都绕不过去了。见 DECISIONS.md D-002。
     */
    @Bean(destroyMethod = "close")
    public QdrantClient qdrantClient(AtpProperties props) {
        AtpProperties.Qdrant cfg = props.getQdrant();
        requireCompatibleVersion(cfg);
        log.info("qdrant: {}:{} (gRPC)", cfg.getHost(), cfg.getGrpcPort());
        return new QdrantClient(QdrantGrpcClient.newBuilder(
                cfg.getHost(), cfg.getGrpcPort(), false).build());
    }

    private void requireCompatibleVersion(AtpProperties.Qdrant cfg) {
        String version = fetchVersion(cfg.getRestBaseUrl());
        String[] parts = version.split("\\.");
        if (parts.length < 2) {
            log.warn("Qdrant 版本号解析不出来（{}），跳过兼容性检查", version);
            return;
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
            log.warn("Qdrant 版本号格式意外（{}），跳过兼容性检查", version);
        }
    }

    private String fetchVersion(String restBaseUrl) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(restBaseUrl + "/").openConnection();
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(5_000);
            InputStream in = conn.getInputStream();
            try {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int n;
                while ((n = in.read(chunk)) != -1) {
                    buffer.write(chunk, 0, n);
                }
                return new ObjectMapper().readTree(buffer.toByteArray())
                        .path("version").asText("");
            } finally {
                in.close();
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "连不上 Qdrant REST (" + restBaseUrl + ")，服务没起？", e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
