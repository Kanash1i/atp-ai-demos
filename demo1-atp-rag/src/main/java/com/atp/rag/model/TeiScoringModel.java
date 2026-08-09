package com.atp.rag.model;

import com.atp.rag.config.Env;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * bge-reranker-v2-m3 @ TEI 的适配器。
 *
 * <p>embedding 和生成都能直接用 langchain4j 的 OpenAI 客户端（TEI 和 DeepSeek 都兼容那套协议），
 * <b>只有 rerank 不行</b> —— TEI 的 {@code /rerank} 不是 OpenAI 标准，所以这个接口得自己实现。
 *
 * <h3>两个会静默出错的地方</h3>
 *
 * <b>1. 请求字段是 {@code texts}，不是 {@code documents}。</b>
 * Cohere / Jina 那套 rerank API 用的是 {@code documents}，照着写会得到 422，这个还算好排查。
 *
 * <b>2. 响应是 {@code [{index, score}, ...]} 且未按 score 排序。</b>
 * 这个才要命 —— {@link ScoringModel#scoreAll} 的契约是<b>返回与输入顺序一一对应的分数列表</b>。
 * 如果直接把响应数组按顺序读成分数，就等于把分数配错了文档：
 * 第 1 名的分数会被安到第 1 个输入文档头上，而它可能排在第 7。
 *
 * <p>结果是 rerank 照常运行、分数分布看起来也正常，只是<b>排序悄悄错乱</b>。
 * 消融表里这一行会表现为「rerank 提升不明显」甚至「反而变差」，
 * 而面试官只要追问一句「为什么 rerank 增益这么小，你查过吗」就会当场穿帮。
 *
 * <p>所以这里严格按 {@code index} 回填，并且校验 index 的完整性 —— 见 {@link #scoreAll}。
 */
public final class TeiScoringModel implements ScoringModel {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient http;
    private final String endpoint;
    /** TEI 服务端的 batch 上限，默认 32（对应 TEI 的 --max-batch-requests）。 */
    private final int maxBatchSize;

    public TeiScoringModel() {
        this(Env.require("RERANK_BASE_URL"));
    }

    public TeiScoringModel(String baseUrl) {
        this.maxBatchSize = Env.getInt("RERANK_MAX_BATCH", 32);
        String trimmed = baseUrl;
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        this.endpoint = trimmed + "/rerank";
        // 用 okhttp 而不是 HttpURLConnection：它已经在 classpath 上
        // （langchain4j-open-ai 的传递依赖），且评估阶段要跑
        // 40 query × 6 配置 × 40 pair 的量，连接复用是有意义的
        this.http = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(Env.getInt("RERANK_TIMEOUT_SEC", 60), TimeUnit.SECONDS)
                .build();
    }

    /**
     * @return 与 {@code segments} <b>顺序一致</b>的分数列表
     */
    @Override
    public Response<List<Double>> scoreAll(List<TextSegment> segments, String query) {
        if (segments == null || segments.isEmpty()) {
            // TEI 对空 texts 会返回 422，短路掉。
            // 召回为空是正常情况（比如 D 类应拒答的问题），不该在这里炸
            return Response.from(new ArrayList<Double>());
        }

        // ⚠️ TEI 有 batch 上限（默认 32），超了会返回
        // 422 "batch size 39 > maximum allowed batch size 32"。
        // 双 collection 各召回 20 条，去重后常常正好超过 32，所以必须分批。
        List<Double> allScores = new ArrayList<Double>(segments.size());
        for (int from = 0; from < segments.size(); from += maxBatchSize) {
            int to = Math.min(from + maxBatchSize, segments.size());
            allScores.addAll(scoreBatch(segments.subList(from, to), query));
        }
        return Response.from(allScores);
    }

    /**
     * 打一批分。
     *
     * <p>注意 TEI 返回的 {@code index} 是<b>批内相对下标</b>。
     * 这里让每批各自回填成一个完整的小列表再按顺序拼接，
     * 从而不需要在批与全局之间做下标换算 —— 那种换算最容易在边界上错一位，
     * 而错位之后分数照样有值、照样能排序，只是全都配错了文档。
     */
    private List<Double> scoreBatch(List<TextSegment> batch, String query) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("query", query);
        ArrayNode texts = body.putArray("texts");   // ⚠️ 不是 documents
        for (TextSegment segment : batch) {
            texts.add(segment.text());
        }
        return reorderByIndex(post(body.toString()), batch.size());
    }

    /**
     * 把 {@code [{index, score}]} 回填成与输入同序的列表。
     *
     * <p>顺带校验 index 的完整性：每个位置必须被恰好填一次。
     * 少填、重复填、越界都说明响应与请求对不上 —— 与其带着错位的分数继续跑，
     * 不如当场失败。这类错误一旦流进消融表，是查不出来的。
     */
    // package-private：这是本类最容易出错也最该被单测钉住的一段，
    // 让测试能不依赖 TEI 服务就覆盖它的边界情况
    static List<Double> reorderByIndex(JsonNode response, int expectedSize) {
        Double[] scores = new Double[expectedSize];

        for (JsonNode item : response) {
            JsonNode indexNode = item.get("index");
            JsonNode scoreNode = item.get("score");
            if (indexNode == null || scoreNode == null) {
                throw new IllegalStateException(
                        "TEI /rerank 响应缺少 index 或 score 字段：" + item);
            }
            int index = indexNode.asInt();
            if (index < 0 || index >= expectedSize) {
                throw new IllegalStateException("TEI /rerank 返回越界的 index " + index
                        + "（请求了 " + expectedSize + " 条）");
            }
            if (scores[index] != null) {
                throw new IllegalStateException("TEI /rerank 返回重复的 index " + index);
            }
            scores[index] = scoreNode.asDouble();
        }

        List<Double> ordered = new ArrayList<Double>(expectedSize);
        for (int i = 0; i < expectedSize; i++) {
            if (scores[i] == null) {
                throw new IllegalStateException("TEI /rerank 没有返回第 " + i + " 条的分数，"
                        + "请求 " + expectedSize + " 条，响应 " + response.size() + " 条");
            }
            ordered.add(scores[i]);
        }
        return ordered;
    }

    private JsonNode post(String json) {
        Request request = new Request.Builder()
                .url(endpoint)
                .post(RequestBody.create(json, JSON))
                .build();
        okhttp3.Response response = null;
        try {
            response = http.newCall(request).execute();
            ResponseBody responseBody = response.body();
            String text = responseBody == null ? "" : responseBody.string();
            if (!response.isSuccessful()) {
                throw new IllegalStateException("TEI /rerank 返回 HTTP "
                        + response.code() + "：" + text
                        + "（若是 422，先确认请求字段是 texts 而不是 documents）");
            }
            return MAPPER.readTree(text);
        } catch (IOException e) {
            throw new IllegalStateException("调用 " + endpoint + " 失败", e);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    /**
     * 自检：用一组已知答案的文档确认打分方向没坏。
     *
     * <p>reranker 坏掉<b>不会报错</b>，只会让检索悄悄变差。所以在跑评估前必须过这一关 ——
     * 拿一个坏掉的 rerank 去跑消融表，比没有 rerank 严重得多：它会污染整张表，
     * 而且看起来完全正常。
     *
     * @return 相关项分数与最高的无关项分数之比。基线约 4 个数量级
     */
    public double selfCheck() {
        List<TextSegment> probe = Arrays.asList(
                TextSegment.from("XPath 应优先使用 data-testid 等稳定属性，避免绝对路径"),
                TextSegment.from("今天的天气非常好，适合出门散步"),
                TextSegment.from("购物车结算流程的测试要点"));

        List<Double> scores = scoreAll(probe, "如何编写稳定的 XPath 定位器").content();
        double relevant = scores.get(0);
        double bestIrrelevant = Math.max(scores.get(1), scores.get(2));

        if (relevant <= bestIrrelevant) {
            throw new IllegalStateException("rerank 排序方向错误：相关项 " + relevant
                    + " 不高于无关项 " + bestIrrelevant + "。设 RERANK_ENABLED=false，"
                    + "并在消融表里如实标注该行缺失");
        }
        return bestIrrelevant == 0 ? Double.POSITIVE_INFINITY : relevant / bestIrrelevant;
    }
}
