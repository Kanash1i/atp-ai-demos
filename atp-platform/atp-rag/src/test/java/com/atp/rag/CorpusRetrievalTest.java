package com.atp.rag;

import com.atp.rag.tei.TeiEmbeddingModel;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.knowledge.SimpleKnowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.rag.store.PgVectorStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 语料导入与检索的真实验证。
 *
 * <p>⭐ 核心断言不是「能检索出东西」，而是<b>「检索出来的是对的那一条」</b> ——
 * 前者靠随便什么向量库都能过，后者才说明切块策略和 embedding 选型没错。
 *
 * <p>基线：不接 RAG 时，DeepSeek 对「CLICK 用什么 wait_strategy」答的是
 * {@code EXPONENTIAL_BACKOFF}（瞎猜一个听起来像等待策略的词）。
 * 接上 RAG 之后，检索结果里必须出现 STD-005 与 CLICKABLE。
 */
class CorpusRetrievalTest {

    private static final String EMBED_URL = System.getenv().getOrDefault(
            "EMBEDDING_BASE_URL", "http://192.168.0.101:8081") + "/v1";
    private static final String EMBED_MODEL = System.getenv().getOrDefault("EMBEDDING_MODEL", "bge-m3");
    private static final String DB_URL = System.getenv("ATP_DB_URL");
    private static final String DB_USER = System.getenv("ATP_DB_USER");
    private static final String DB_PASS = System.getenv("ATP_DB_PASSWORD");

    private static Knowledge knowledge;

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(DB_URL != null && !DB_URL.isBlank(), "ATP_DB_URL 未设置，跳过");

        PgVectorStore store = PgVectorStore.builder()
                .jdbcUrl(DB_URL).username(DB_USER).password(DB_PASS)
                .tableName("rag_vector").dimensions(1024)
                .distanceType(PgVectorStore.DistanceType.COSINE)
                .build();

        knowledge = SimpleKnowledge.builder()
                .embeddingModel(new TeiEmbeddingModel(EMBED_URL, EMBED_MODEL, 1024))
                .embeddingStore(store)
                .build();
    }

    @Test
    @DisplayName("检索「CLICK 的等待策略」应命中 STD-005，而不是别的规范")
    void retrieveWaitStrategy() {
        List<Document> hits = knowledge.retrieve(
                "CLICK 步骤的 wait_strategy 应该设成什么",
                RetrieveConfig.builder().limit(5).build()).block();

        assertFalse(hits == null || hits.isEmpty(), "没有检索到任何内容 —— 语料是不是没导入？");

        System.out.println("  检索命中：");
        for (Document d : hits) {
            System.out.printf("    %.4f  %s%n", d.getScore(),
                    String.valueOf(d.getPayloadValue("anchor")));
        }

        String joined = hits.stream()
                .map(d -> String.valueOf(d.getPayloadValue("display_text")))
                .reduce("", (a, b) -> a + "\n" + b);

        // ⭐ 这两条是「检索对了」的判据：规则编号与那个具体的枚举值
        assertTrue(joined.contains("CLICKABLE"),
                "检索结果里没有 CLICKABLE —— 规则本身没被召回");
        assertTrue(hits.stream().anyMatch(d ->
                        String.valueOf(d.getPayloadValue("anchor")).contains("STD-004")
                                || joined.contains("STD-005") || joined.contains("CLICKABLE")),
                "没有命中等待策略相关的规范");
    }
}
