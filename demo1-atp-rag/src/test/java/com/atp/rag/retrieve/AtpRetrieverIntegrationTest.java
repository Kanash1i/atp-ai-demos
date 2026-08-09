package com.atp.rag.retrieve;

import com.atp.rag.config.Env;
import com.atp.rag.config.RagConfig;
import com.atp.rag.model.ModelFactory;
import com.atp.rag.model.TeiScoringModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.qdrant.client.QdrantClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检索链路的集成测试，需要 Qdrant 与 TEI，<b>但不需要 LLM</b>。
 *
 * <p>不需要 LLM 这一点是刻意的：检索指标和生成无关，M4 跑 40 条评估集也不该烧 token。
 * 这里的测试跑得通，就说明 M4 的评估能在没有 LLM key 的情况下先跑起来。
 *
 * <p>服务不可用时跳过而非失败 —— 没连服务机时 {@code mvn test} 仍要能过。
 */
class AtpRetrieverIntegrationTest {

    private static RagConfig config;
    private static EmbeddingModel embeddingModel;
    private static QdrantClient client;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(healthy(Env.get("EMBEDDING_BASE_URL", "") + "/health"),
                "TEI embedding 不可用，跳过");
        Assumptions.assumeTrue(qdrantReachable(), "Qdrant 不可用，跳过");

        config = RagConfig.full();
        embeddingModel = ModelFactory.embeddingModel();
        client = ModelFactory.qdrantClient();

        Assumptions.assumeTrue(collectionExists(config.docsCollection()),
                "collection " + config.docsCollection() + " 不存在，先跑 IngestMain");
    }

    @AfterAll
    static void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    private AtpRetriever retriever(boolean rerank) {
        RagConfig c = config.toBuilder().rerankEnabled(rerank).build();
        return new AtpRetriever(c, embeddingModel,
                rerank ? new TeiScoringModel() : null, client, new AtpQueryRouter(null));
    }

    @Test
    @DisplayName("知识问答能召回到具体章节，不是整篇文档")
    void knowledgeQueryReturnsSectionLevelAnchors() {
        RetrievalResult result = retriever(true).retrieve("点击按钮之前应该用哪种等待策略");

        assertFalse(result.isEmpty(), "不该召回为空");
        // 标题路径切分的价值就在这里：anchor 带 # 说明定位到了节，而不是只知道在哪个文件
        boolean hasSectionAnchor = false;
        for (RetrievedItem item : result.topItems()) {
            if (!item.isCase() && item.identity().contains("#")) {
                hasSectionAnchor = true;
            }
        }
        assertTrue(hasSectionAnchor, "应召回到章节级 anchor，实际 " + result.topIdentities());
    }

    @Test
    @DisplayName("案例检索走 CASES，召回的全是案例")
    void caseQueryRoutesToCaseCollection() {
        RetrievalResult result = retriever(true).retrieve("帮我找几个购物车相关的案例参考");

        assertEquals(QueryIntent.CASES, result.intent());
        assertFalse(result.isEmpty());
        for (RetrievedItem item : result.topItems()) {
            assertTrue(item.isCase(), "路由到 CASES 却召回了非案例：" + item.identity());
            assertTrue(item.identity().startsWith("ATP-"),
                    "案例的 identity 应是 case_code，实际 " + item.identity());
        }
    }

    @Test
    @DisplayName("召回结果按 identity 去重")
    void resultsAreDeduplicatedByIdentity() {
        // overlap 切分会让同一节出现在相邻 chunk 里，lint 的补充检索也常召回同一批文档。
        // 不去重的话 top5 可能有三条是同一段话，白白挤掉别的内容
        RetrievalResult result = retriever(true)
                .retrieve("/html/body/div[3]/span[@id=\"ext-gen1234\"] 这样写有问题吗");

        List<String> identities = result.topIdentities();
        Set<String> unique = new HashSet<String>(identities);
        assertEquals(identities.size(), unique.size(), "top 结果有重复：" + identities);

        List<String> candidates = result.candidateIdentities();
        assertEquals(candidates.size(), new HashSet<String>(candidates).size(),
                "候选集有重复");
    }

    @Test
    @DisplayName("lint 通道命中时会补充召回，候选集明显变大")
    void lintChannelWidensCandidatePool() {
        AtpRetriever r = retriever(true);
        RetrievalResult withLint = r.retrieve("/html/body/div[3]/span[@id=\"ext-gen1234\"] 这样写有问题吗");
        RetrievalResult plain = r.retrieve("这样写有问题吗");

        assertFalse(withLint.lintFindings().isEmpty(), "应命中 lint 规则");
        assertTrue(withLint.candidates().size() > plain.candidates().size(),
                "补充检索应扩大候选集，实际 " + withLint.candidates().size()
                        + " vs " + plain.candidates().size());
    }

    @Test
    @DisplayName("rerank 确实改变了排序，而不是原样透传")
    void rerankActuallyReorders() {
        String query = "点击按钮之前应该用哪种等待策略";
        RetrievalResult withRerank = retriever(true).retrieve(query);
        RetrievalResult withoutRerank = retriever(false).retrieve(query);

        assertTrue(withRerank.rerankApplied());
        assertFalse(withoutRerank.rerankApplied());

        // 至少有一条的最终名次与向量名次不同 —— 否则 rerank 等于没生效，
        // 而消融表里那一行会显示成「rerank 无增益」，误导性极强
        boolean reordered = false;
        int rank = 1;
        for (RetrievedItem item : withRerank.topItems()) {
            assertTrue(item.reranked(), "开了 rerank 却有条目没有精排分");
            if (item.vectorRank() != rank++) {
                reordered = true;
            }
        }
        assertTrue(reordered, "rerank 没有改变任何位次，可疑");
    }

    @Test
    @DisplayName("案例类查询不会被 rerank 阈值整片砍空")
    void caseQueriesAreNotWipedOutByScoreThreshold() {
        // 这是 M3 实测出来的真实 bug：曾用绝对阈值 0.01，而 reranker 对
        // 「自然语言 query vs 结构化步骤序列」打分天然偏低（案例类 top1 只有 0.008~0.38，
        // 文档类是 0.59~0.99）。结果「有没有涉及文件上传的案例」召回 0 条 ——
        // 而那恰好是交接文档 §5.1 点名的 B 类用例，M4 的 B 类 Recall 会直接归零，
        // 还会被误读成「检索能力差」。
        AtpRetriever r = retriever(true);
        for (String query : new String[]{
                "有没有涉及文件上传的案例",
                "帮我找几个购物车相关的案例参考",
                "找几个支付失败的案例",
                "报表导出的案例有哪些"}) {
            RetrievalResult result = r.retrieve(query);
            assertFalse(result.candidates().isEmpty(), query + "：候选为空");
            assertFalse(result.isEmpty(),
                    query + "：候选有 " + result.candidates().size()
                            + " 条却一条都没采用 —— 阈值把整类查询砍空了");
        }
    }

    @Test
    @DisplayName("路由决策带来源，规则判定与 LLM 判定可区分")
    void routingDecisionCarriesItsOrigin() {
        // 曾经这个字段两个分支都写死 true，等于永远无法区分。
        // M4 分析路由错误时，「错的是规则还是 LLM」决定该改信号词表还是改 prompt
        AtpQueryRouter ruleOnly = new AtpQueryRouter(null);
        assertTrue(ruleOnly.decide("帮我找几个购物车相关的案例参考").byRule(),
                "命中信号词应标记为规则判定");
        assertFalse(ruleOnly.decide("购物车").byRule(),
                "规则判不出来时不该标记为规则判定");
    }

    @Test
    @DisplayName("跨语言：日文提问能召回中日文语料")
    void crossLingualRetrievalWorks() {
        RetrievalResult result = retriever(true)
                .retrieve("ログイン失敗時のテストケースはありますか");
        assertFalse(result.isEmpty(), "日文提问召回为空");
        assertTrue(result.topItems().get(0).identity().startsWith("ATP-LOGIN"),
                "应召回登录相关案例，实际 " + result.topIdentities());
    }

    @Test
    @DisplayName("违规案例带着违规标记被召回，助手才能提醒别照抄")
    void violatingCasesCarryTheirMarkers() {
        RetrievalResult result = retriever(true).retrieve("历史遗留的登录案例用了固定等待");

        boolean foundViolating = false;
        for (RetrievedItem item : result.candidates()) {
            if (item.hasViolation()) {
                foundViolating = true;
                assertFalse(item.violationCodes().trim().isEmpty(),
                        item.identity() + " 标了 has_violation 却没有违规码");
            }
        }
        assertTrue(foundViolating, "候选里应该出现违规案例，实际 " + result.candidateIdentities());
    }

    private static boolean qdrantReachable() {
        return healthy("http://" + Env.get("QDRANT_HOST", "") + ":"
                + Env.getInt("QDRANT_PORT", 6333) + "/");
    }

    private static boolean collectionExists(String name) {
        return healthy(ModelFactory.qdrantRestBaseUrl() + "/collections/" + name);
    }

    private static boolean healthy(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(3000);
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
