package com.atp.rag.retrieve;

import com.atp.rag.config.AtpProperties;
import com.atp.rag.config.RagConfig;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import io.qdrant.client.QdrantClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索编排：路由 → 多路召回 → 精排 → topK。
 *
 * <pre>
 *   query
 *     ├─ XPath lint 通道（命中则补充语义化检索词）
 *     ├─ 路由：DOCS / CASES / BOTH
 *     ├─ 向量召回 topK=20（按路由决定查哪些 collection）
 *     ├─ 按 identity 去重（overlap 切分会让同一节出现在多个 chunk 里）
 *     ├─ rerank 精排（bge-reranker-v2-m3）
 *     └─ 截取 top5
 * </pre>
 *
 * <p>每一环都由 {@link RagConfig} 的开关控制，关掉就退回上一行的行为 ——
 * 消融表就是这么一行行跑出来的。
 */
public final class AtpRetriever {

    private static final Logger log = LoggerFactory.getLogger(AtpRetriever.class);

    private final RagConfig config;
    private final EmbeddingModel embeddingModel;
    private final ScoringModel scoringModel;
    private final QdrantClient client;
    private final AtpQueryRouter router;

    private final int candidateTopK;
    private final int finalTopK;
    private final double minScore;
    private final double relativeFloorRatio;

    /**
     * @param scoringModel 传 null 表示不做精排（消融表第 1~3 行）
     * @param router       传 null 表示不做路由，一律查两边（消融表第 1~2 行）
     */
    public AtpRetriever(RagConfig config, EmbeddingModel embeddingModel,
                        ScoringModel scoringModel, QdrantClient client,
                        AtpQueryRouter router, AtpProperties props) {
        this.config = config;
        this.embeddingModel = embeddingModel;
        this.scoringModel = scoringModel;
        this.client = client;
        this.router = router;
        // topK 跟着消融配置走（它俩本身就是可调项），阈值取自 atp.rerank.*
        this.candidateTopK = config.candidateTopK();
        this.finalTopK = config.finalTopK();
        // ⚠️ 这里曾经用绝对阈值 0.01，是个会静默吃掉整类查询的 bug。
        //
        // reranker 对「自然语言 query vs 结构化步骤序列」的打分天然偏低：
        // 实测文档类查询 top1 落在 0.59~0.99，案例类只有 0.008~0.38 —— 差 1~2 个数量级。
        // 绝对阈值对两类不公平，「有没有涉及文件上传的案例」会被整片砍成 0 条召回，
        // 而这恰好是交接文档 §5.1 点名的 B 类用例。
        //
        // 改成「相对 top1 的比例」+ 一个极低的绝对下限：前者对两类分布都成立，
        // 后者只用来挡住分数趋近于 0 的纯噪音。
        this.relativeFloorRatio = props.getRerank().getRelativeFloor();
        this.minScore = props.getRerank().getMinScore();
    }

    public RetrievalResult retrieve(String query) {
        // ── 1. lint 通道 ──
        List<XPathLintChannel.Finding> findings = config.queryRewriteEnabled()
                ? XPathLintChannel.analyze(query)
                : Collections.<XPathLintChannel.Finding>emptyList();

        // ── 2. 路由 ──
        QueryIntent intent;
        boolean routedByRule;
        if (router == null || config.collectionMode() == RagConfig.CollectionMode.SINGLE) {
            // 单 collection 时路由没有意义 —— 两个「库」本来就是同一个
            intent = QueryIntent.BOTH;
            routedByRule = true;
        } else {
            AtpQueryRouter.Decision decision = router.decide(query);
            intent = decision.intent();
            routedByRule = decision.byRule();
        }

        // ── 3. 多路召回 ──
        // 用 LinkedHashMap 去重：overlap 切分会让同一节出现在相邻 chunk 里，
        // 补充检索词也常常召回同一批文档。不去重的话 top5 可能有三条是同一段话，
        // 白白挤掉别的内容
        Map<String, RetrievedItem> deduped = new LinkedHashMap<String, RetrievedItem>();

        collectInto(deduped, query, intent);
        for (String supplementary : XPathLintChannel.supplementaryQueries(findings)) {
            log.debug("lint 补充检索：{}", supplementary);
            collectInto(deduped, supplementary, intent);
        }

        List<RetrievedItem> candidates = new ArrayList<RetrievedItem>(deduped.values());
        Collections.sort(candidates, new Comparator<RetrievedItem>() {
            public int compare(RetrievedItem a, RetrievedItem b) {
                return Double.compare(b.vectorScore(), a.vectorScore());
            }
        });

        // ── 4. 精排 ──
        boolean rerankApplied = false;
        if (config.rerankEnabled() && scoringModel != null && !candidates.isEmpty()) {
            applyRerank(query, candidates);
            rerankApplied = true;
        }

        // ── 5. 截取 ──
        // 阈值相对于 top1：文档类 top1≈0.95 时门槛约 0.019，案例类 top1≈0.008 时
        // 退到绝对下限 0.0005。同一套规则对两种分布都成立
        double threshold = candidates.isEmpty() ? 0
                : Math.max(minScore, candidates.get(0).finalScore() * relativeFloorRatio);

        List<RetrievedItem> top = new ArrayList<RetrievedItem>();
        for (RetrievedItem item : candidates) {
            if (top.size() >= finalTopK) {
                break;
            }
            if (rerankApplied && item.rerankScore() < threshold) {
                // 候选已按精排分降序，后面的只会更低
                break;
            }
            top.add(item);
        }

        return new RetrievalResult(query, intent, routedByRule, findings,
                candidates, top, rerankApplied);
    }

    // ── 召回 ──────────────────────────────────────────────────

    private void collectInto(Map<String, RetrievedItem> target, String query, QueryIntent intent) {
        Embedding vector = embeddingModel.embed(query).content();

        if (intent == QueryIntent.DOCS || intent == QueryIntent.BOTH) {
            search(target, config.docsCollection(), vector);
        }
        if (intent == QueryIntent.CASES || intent == QueryIntent.BOTH) {
            String cases = config.casesCollection();
            // SINGLE 模式下两个名字相同，查两次等于白跑一趟
            if (!cases.equals(config.docsCollection()) || intent == QueryIntent.CASES) {
                search(target, cases, vector);
            }
        }
    }

    private void search(Map<String, RetrievedItem> target, String collection, Embedding vector) {
        EmbeddingStore<TextSegment> store = QdrantEmbeddingStore.builder()
                .client(client)
                .collectionName(collection)
                .build();

        List<EmbeddingMatch<TextSegment>> matches = store.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(vector)
                .maxResults(candidateTopK)
                .build()).matches();

        int rank = 1;
        for (EmbeddingMatch<TextSegment> match : matches) {
            RetrievedItem item = new RetrievedItem(
                    match.embedded(), match.score(), rank++, collection);
            String identity = item.identity();
            if (identity == null) {
                continue;   // payload 缺 anchor / case_code，跳过而不是让整次检索崩掉
            }
            RetrievedItem existing = target.get(identity);
            // 同一节被多路召回时保留分数最高的那次
            if (existing == null || item.vectorScore() > existing.vectorScore()) {
                target.put(identity, item);
            }
        }
    }

    // ── 精排 ──────────────────────────────────────────────────

    /**
     * 用 rerank 分数重排候选。
     *
     * <p>⚠️ {@link ScoringModel#scoreAll} 返回的是<b>与输入同序</b>的分数列表，
     * 不是排好序的结果。这里按下标一一对应地回填，然后自己排序。
     * 把它当成「已排序的结果」是这个接口最容易犯的错。
     */
    private void applyRerank(String query, List<RetrievedItem> candidates) {
        List<TextSegment> segments = new ArrayList<TextSegment>(candidates.size());
        for (RetrievedItem item : candidates) {
            segments.add(item.segment());
        }

        List<Double> scores = scoringModel.scoreAll(segments, query).content();
        if (scores.size() != candidates.size()) {
            throw new IllegalStateException("rerank 返回 " + scores.size()
                    + " 个分数，与候选数 " + candidates.size() + " 不符");
        }
        for (int i = 0; i < candidates.size(); i++) {
            candidates.get(i).rerankScore(scores.get(i));
        }
        Collections.sort(candidates, new Comparator<RetrievedItem>() {
            public int compare(RetrievedItem a, RetrievedItem b) {
                return Double.compare(b.rerankScore(), a.rerankScore());
            }
        });
    }
}
