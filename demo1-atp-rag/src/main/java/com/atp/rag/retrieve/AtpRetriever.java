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
    /** 被路由判为「不相关」的那一侧仍然查这么多条，作为保底。 */
    private final int fallbackTopK;
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
        this.fallbackTopK = props.getRag().getFallbackTopK();
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
        List<RetrievedItem> top = selectTop(candidates, intent, rerankApplied);

        return new RetrievalResult(query, intent, routedByRule, findings,
                candidates, top, rerankApplied);
    }

    /**
     * 选出最终结果。<b>分组配额，而不是全局按分数排。</b>
     *
     * <h4>为什么不能全局排</h4>
     *
     * 因为<b>rerank 分数在文档和案例之间不可比</b>：reranker 对
     * 「自然语言 query vs 结构化步骤序列」的打分天然偏低，实测文档类 top1 落在 0.59~0.99，
     * 案例类只有 0.008~0.38（见 D-013）。全局排序等于让案例和文档比绝对分 ——
     * 案例必输。
     *
     * <p>实测后果：「帮我找几个购物车相关的案例参考」路由正确判成 CASES、
     * 案例也正常召回了，但 top5 里只有 2 条案例，3 条文档凭高分挤了进来。
     * <b>一个明确要案例的问题，返回的多数是文档。</b>
     *
     * <p>这是同一个根因（分数尺度不可比）在第三个位置咬人：
     * D-013 是绝对阈值把案例砍空，D-016 是路由开关让案例查不到，这里是排序把案例挤掉。
     * 前两次都是打补丁，这次直接改成按组分配名额 —— 让「哪一类该占多数」由<b>路由意图</b>
     * 决定，而不是由两个不可比的分数决定。
     */
    private List<RetrievedItem> selectTop(List<RetrievedItem> candidates,
                                          QueryIntent intent, boolean rerankApplied) {
        // 阈值相对 top1：文档类 top1≈0.95 时门槛约 0.019，案例类 top1≈0.008 时
        // 退到绝对下限 0.0005。同一套规则对两种分布都成立（D-013）
        double threshold = candidates.isEmpty() ? 0
                : Math.max(minScore, candidates.get(0).finalScore() * relativeFloorRatio);

        // 单 collection 下没有分组这回事，退回按分数取
        if (config.collectionMode() == RagConfig.CollectionMode.SINGLE) {
            return takeByScore(candidates, finalTopK, rerankApplied, threshold);
        }

        List<RetrievedItem> docs = new ArrayList<RetrievedItem>();
        List<RetrievedItem> cases = new ArrayList<RetrievedItem>();
        for (RetrievedItem item : candidates) {
            (item.isCase() ? cases : docs).add(item);
        }

        // 主类占多数，次类留一席之地。
        // BOTH 时给文档多一席 —— 「既要规范也要案例」的问法里，规范是判断依据，
        // 案例是参考，判断依据优先
        int caseQuota;
        switch (intent) {
            case CASES:
                caseQuota = finalTopK - 2;
                break;
            case DOCS:
                caseQuota = 1;
                break;
            default:
                caseQuota = finalTopK / 2;
                break;
        }
        int docQuota = finalTopK - caseQuota;

        List<RetrievedItem> pickedCases = takeByScore(cases, caseQuota, rerankApplied, threshold);
        List<RetrievedItem> pickedDocs = takeByScore(docs, docQuota, rerankApplied, threshold);

        // 一侧配额用不满时，剩下的名额让给另一侧 —— 不能因为凑不够配额就少给结果
        int spare = finalTopK - pickedCases.size() - pickedDocs.size();
        if (spare > 0) {
            pickedCases.addAll(takeByScore(
                    cases.subList(Math.min(pickedCases.size(), cases.size()), cases.size()),
                    spare, rerankApplied, threshold));
            spare = finalTopK - pickedCases.size() - pickedDocs.size();
        }
        if (spare > 0) {
            pickedDocs.addAll(takeByScore(
                    docs.subList(Math.min(pickedDocs.size(), docs.size()), docs.size()),
                    spare, rerankApplied, threshold));
        }

        // 合并后按分数排一次，让引用编号的顺序看起来合理。
        // 组内相对顺序不受影响，因为配额已经决定了各组进来几条
        List<RetrievedItem> top = new ArrayList<RetrievedItem>(pickedCases);
        top.addAll(pickedDocs);
        Collections.sort(top, new Comparator<RetrievedItem>() {
            public int compare(RetrievedItem a, RetrievedItem b) {
                return Double.compare(b.finalScore(), a.finalScore());
            }
        });
        return top;
    }

    /** 从已按分数降序的列表里取前 n 条，跳过低于阈值的。 */
    private List<RetrievedItem> takeByScore(List<RetrievedItem> from, int n,
                                            boolean rerankApplied, double threshold) {
        List<RetrievedItem> picked = new ArrayList<RetrievedItem>();
        for (RetrievedItem item : from) {
            if (picked.size() >= n) {
                break;
            }
            if (rerankApplied && item.rerankScore() < threshold) {
                // 已按精排分降序，后面的只会更低
                break;
            }
            picked.add(item);
        }
        return picked;
    }

    // ── 召回 ──────────────────────────────────────────────────

    /**
     * 多路召回。<b>路由决定配额，不决定开关。</b>
     *
     * <p>原先的实现是开关式的：判成 {@code CASES} 就完全不查文档库。
     * 这违背了 {@link AtpQueryRouter} 自己写下的原则 —— 既然「路由错误的代价不对称」，
     * 就不该给任何一次路由错误留下「彻底召回不到」的可能。
     *
     * <p>实测撞到了：「点击按钮之前应该用哪种等待策略」是典型的知识问答，
     * 但这种问法没命中信号词表，落到 LLM 路由后<b>偶尔被判成 CASES</b>，
     * 于是 top5 全是案例、一条文档都没有。表现为集成测试<b>间歇性失败</b>，
     * 而在评估里会表现为某几行 Recall 的随机跳水 —— 那种波动最容易被误读成「优化有效/无效」。
     *
     * <p>改成配额制之后，路由判错的后果从「召回不到」降级为「配额不理想」，
     * 而配额不理想由 rerank 兜住：真正相关的内容仍会被精排提到前面。
     */
    private void collectInto(Map<String, RetrievedItem> target, String query, QueryIntent intent) {
        Embedding vector = embeddingModel.embed(query).content();
        String docs = config.docsCollection();
        String cases = config.casesCollection();

        // SINGLE 模式下两个名字相同，查两次等于白跑一趟
        if (docs.equals(cases)) {
            search(target, docs, vector, candidateTopK);
            return;
        }
        // 被路由判为「不相关」的那一侧仍然查，只是配额小 —— 这是保底，不是浪费：
        // 案例库只有 80 条，多一次 Qdrant 查询的开销远小于一次彻底召回失败的代价。
        // 而 embedding 只算了一次，没有额外的模型调用
        search(target, docs, vector,
                intent == QueryIntent.CASES ? fallbackTopK : candidateTopK);
        search(target, cases, vector,
                intent == QueryIntent.DOCS ? fallbackTopK : candidateTopK);
    }

    private void search(Map<String, RetrievedItem> target, String collection,
                        Embedding vector, int topK) {
        EmbeddingStore<TextSegment> store = QdrantEmbeddingStore.builder()
                .client(client)
                .collectionName(collection)
                .build();

        List<EmbeddingMatch<TextSegment>> matches = store.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(vector)
                .maxResults(topK)
                .build()).matches();

        int rank = 1;
        for (EmbeddingMatch<TextSegment> match : matches) {
            RetrievedItem item = new RetrievedItem(
                    match.embedded(), match.score(), rank++, collection);
            if (item.identity() == null) {
                continue;   // payload 缺 anchor / case_code，跳过而不是让整次检索崩掉
            }
            // 用 dedupeKey 而不是 identity：父子切块下同一章节的多个子块
            // 携带的是同一份父块正文，只该留一条（见 RetrievedItem.dedupeKey）
            String key = item.dedupeKey();
            RetrievedItem existing = target.get(key);
            // 同一节被多路召回时保留分数最高的那次
            if (existing == null || item.vectorScore() > existing.vectorScore()) {
                target.put(key, item);
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
