package com.atp.rag.model;

import com.atp.rag.config.Env;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * rerank 适配器的测试。
 *
 * <p>纯逻辑部分（按 index 回填）不依赖服务，一定会跑；
 * 需要真实 TEI 的部分在服务不可用时{@link Assumptions 跳过}而不是失败 ——
 * 否则没连服务机的时候连 {@code mvn test} 都过不了。
 */
class TeiScoringModelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── 纯逻辑：不依赖 TEI ────────────────────────────────────

    @Test
    @DisplayName("响应未排序时，分数按 index 回填到正确位置")
    void scoresAreMappedBackByIndex() throws IOException {
        // TEI 实际返回的样子：按 score 降序，不是按 index 顺序
        JsonNode response = MAPPER.readTree("["
                + "{\"index\":2,\"score\":0.9},"
                + "{\"index\":0,\"score\":0.1},"
                + "{\"index\":1,\"score\":0.5}]");

        List<Double> scores = TeiScoringModel.reorderByIndex(response, 3);

        // 若实现是「照响应顺序读」，这里会得到 [0.9, 0.1, 0.5] —— 分数配错了文档。
        // 那种错误不会抛异常，只会让 rerank 悄悄排错序
        assertEquals(Arrays.asList(0.1, 0.5, 0.9), scores);
    }

    @Test
    @DisplayName("响应缺了某一条的分数时直接失败")
    void missingIndexIsRejected() throws IOException {
        JsonNode response = MAPPER.readTree("[{\"index\":0,\"score\":0.9}]");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> TeiScoringModel.reorderByIndex(response, 3));
        assertTrue(e.getMessage().contains("没有返回第 1 条"), e.getMessage());
    }

    @Test
    @DisplayName("响应出现重复 index 时直接失败")
    void duplicateIndexIsRejected() throws IOException {
        JsonNode response = MAPPER.readTree("["
                + "{\"index\":0,\"score\":0.9},{\"index\":0,\"score\":0.2}]");
        assertThrows(IllegalStateException.class,
                () -> TeiScoringModel.reorderByIndex(response, 2));
    }

    @Test
    @DisplayName("响应出现越界 index 时直接失败")
    void outOfRangeIndexIsRejected() throws IOException {
        JsonNode response = MAPPER.readTree("[{\"index\":7,\"score\":0.9}]");
        assertThrows(IllegalStateException.class,
                () -> TeiScoringModel.reorderByIndex(response, 2));
    }

    // ── 需要真实 TEI ─────────────────────────────────────────

    @Test
    @DisplayName("真实服务：相关文档排在中间时，分数仍落在它自己的位置上")
    void realServiceKeepsScoreAlignedWithInput() {
        assumeRerankAvailable();

        // 相关的那条刻意放在 index 1。
        // 如果回填逻辑写错，最高分会跑到 index 0 上 —— 这个测试就是为了抓那种错位
        List<TextSegment> segments = Arrays.asList(
                TextSegment.from("购物车结算流程的测试要点"),
                TextSegment.from("XPath 应优先使用 data-testid 等稳定属性，避免绝对路径"),
                TextSegment.from("今天的天气非常好，适合出门散步"));

        List<Double> scores = new TeiScoringModel()
                .scoreAll(segments, "如何编写稳定的 XPath 定位器").content();

        assertEquals(3, scores.size());
        assertTrue(scores.get(1) > scores.get(0),
                "index 1 才是相关文档，分数应高于 index 0，实际 " + scores);
        assertTrue(scores.get(1) > scores.get(2),
                "index 1 才是相关文档，分数应高于 index 2，实际 " + scores);
    }

    @Test
    @DisplayName("真实服务：超过 batch 上限时分批，且分数仍与输入对齐")
    void oversizedInputIsBatchedAndStaysAligned() {
        assumeRerankAvailable();

        // TEI 默认 batch 上限 32。双 collection 各召回 20 条、去重后常常正好超过，
        // 所以这条路径在真实查询里是会走到的
        int size = 39;
        int relevantAt = 35;    // 相关的那条刻意放在第二批，跨批错位会被这条抓到
        List<TextSegment> segments = new java.util.ArrayList<TextSegment>();
        for (int i = 0; i < size; i++) {
            segments.add(TextSegment.from(i == relevantAt
                    ? "XPath 应优先使用 data-testid 等稳定属性，避免绝对路径"
                    : "第 " + i + " 段与提问无关的填充文本，讲的是购物车结算流程"));
        }

        List<Double> scores = new TeiScoringModel()
                .scoreAll(segments, "如何编写稳定的 XPath 定位器").content();

        assertEquals(size, scores.size(), "分批后总数应保持不变");
        int argmax = 0;
        for (int i = 1; i < scores.size(); i++) {
            if (scores.get(i) > scores.get(argmax)) {
                argmax = i;
            }
        }
        assertEquals(relevantAt, argmax,
                "最高分应落在第 " + relevantAt + " 条上，实际落在第 " + argmax
                        + " 条 —— 说明跨批回填时下标错位了");
    }

    @Test
    @DisplayName("真实服务：区分度自检达到基线量级")
    void selfCheckMeetsBaseline() {
        assumeRerankAvailable();

        double ratio = new TeiScoringModel().selfCheck();
        // 实测基线约 4 个数量级（0.735 vs 1.6e-5）。放宽到 100 倍是为了容忍模型小版本差异，
        // 但仍然能挡住「三个分数挤在一起」这种坏掉的情况
        assertTrue(ratio > 100,
                "rerank 区分度只有 " + ratio + " 倍，基线约 4 个数量级。"
                        + "区分度不足时应设 RERANK_ENABLED=false 并在消融表标注缺失，"
                        + "绝不能拿坏掉的 rerank 去跑评估");
    }

    @Test
    @DisplayName("空输入短路，不打服务")
    void emptyInputIsShortCircuited() {
        // 召回为空是正常情况（比如 D 类应拒答的问题），不该炸。
        // 这条不需要服务：正确实现会在发请求之前就返回
        List<Double> scores = new TeiScoringModel("http://127.0.0.1:1")
                .scoreAll(java.util.Collections.<TextSegment>emptyList(), "任意问题")
                .content();
        assertTrue(scores.isEmpty());
    }

    private static void assumeRerankAvailable() {
        Assumptions.assumeTrue(Env.getBoolean("RERANK_ENABLED", true),
                "RERANK_ENABLED=false，跳过");
        Assumptions.assumeTrue(healthy(Env.get("RERANK_BASE_URL", "") + "/health"),
                "TEI rerank 服务不可用，跳过");
    }

    private static boolean healthy(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
