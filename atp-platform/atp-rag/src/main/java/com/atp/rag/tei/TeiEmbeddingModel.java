package com.atp.rag.tei;

import io.agentscope.core.embedding.EmbeddingException;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 直连本地 TEI 的 embedding 实现。
 *
 * <h3>⚠️ 为什么不用框架自带的 OpenAITextEmbedding</h3>
 *
 * 它引用了 {@code com.openai.client.okhttp.OpenAIOkHttpClient}，
 * 而 <b>agentscope 1.0.12 的 pom 里根本没有声明 openai-java 这个依赖</b>（14 个依赖全查过）。
 * 运行期直接 {@code ClassNotFoundException}。
 * 与其去猜一个框架自己都没声明的包该用哪个版本，不如自己实现 ——
 * {@link EmbeddingModel} 一共只有三个方法，而 TEI 的接口比 OpenAI 的还简单。
 *
 * <p>而且 rerank 反正也得自己写（框架完全没有本地 rerank 抽象），
 * 两者共用一套 HTTP 调用方式，比引一个半残的适配层清楚。
 *
 * <h3>用 /embed 而不是 /v1/embeddings</h3>
 *
 * TEI 的原生端点直接返回 {@code [[0.1, 0.2, …]]}，
 * OpenAI 兼容端点要多包一层 {@code {data:[{embedding:[…]}]}}。
 * 既然自己写，就用最省事的那个。
 */
@Slf4j
public class TeiEmbeddingModel implements EmbeddingModel {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final String baseUrl;
    private final String modelName;
    private final int dimensions;

    public TeiEmbeddingModel(String baseUrl, String modelName, int dimensions) {
        // 容忍配置里带不带尾斜杠、带不带 /v1
        this.baseUrl = baseUrl.replaceAll("/+$", "").replaceAll("/v1$", "");
        this.modelName = modelName;
        this.dimensions = dimensions;
    }

    @Override
    public Mono<double[]> embed(ContentBlock content) {
        String text = content instanceof TextBlock tb ? tb.getText() : String.valueOf(content);
        if (text == null || text.isBlank()) {
            // 空文本不该发给模型 —— TEI 会 422，而调用方看到的是一堆无关的报错
            return Mono.error(new EmbeddingException("待编码的文本为空", modelName, "tei"));
        }
        return Mono.fromCallable(() -> call(text));
    }

    private double[] call(String text) {
        try {
            String body = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                    .writeValueAsString(java.util.Map.of("inputs", text));

            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/embed"))
                            .timeout(Duration.ofSeconds(30))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() / 100 != 2) {
                throw new EmbeddingException(
                        "TEI 返回 HTTP %d：%s".formatted(resp.statusCode(), truncate(resp.body())),
                        modelName, "tei");
            }

            var root = com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(resp.body());
            var vec = root.isArray() && root.size() > 0 ? root.get(0) : root;
            double[] out = new double[vec.size()];
            for (int i = 0; i < vec.size(); i++) {
                out[i] = vec.get(i).asDouble();
            }

            // ⚠️ 维度对不上要当场失败。放过去的话，向量会被存进一张维度不符的表，
            //    PgVectorStore 报的是 SQL 层的错，根因（换了 embedding 模型）就找不回来了
            if (out.length != dimensions) {
                throw new EmbeddingException(
                        "维度不符：期望 %d，实际 %d —— 是不是换了 embedding 模型？".formatted(dimensions, out.length),
                        modelName, "tei");
            }
            return out;
        } catch (EmbeddingException e) {
            throw e;
        } catch (Exception e) {
            throw new EmbeddingException("调用 TEI 失败：" + baseUrl, e, modelName, "tei");
        }
    }

    private String truncate(String s) {
        return s == null ? "" : (s.length() > 200 ? s.substring(0, 200) + "…" : s);
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    @Override
    public int getDimensions() {
        return dimensions;
    }
}
