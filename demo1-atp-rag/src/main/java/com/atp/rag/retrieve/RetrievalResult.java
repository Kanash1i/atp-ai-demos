package com.atp.rag.retrieve;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次检索的完整结果，含中间过程。
 *
 * <p>之所以不直接返回一个 {@code List<Content>} 交给 langchain4j 的 RAG 编排，
 * 就是为了这些中间信息：<b>M4 的评估要算 Recall@5 / MRR@10，需要拿到召回列表；
 * 消融表要说明每项优化改变了什么，需要拿到路由决策和 rerank 前后的位次。</b>
 * 把检索包成黑盒之后，这些都拿不到了。
 *
 * <p>另一个好处是评估不必经过生成 —— 检索指标和 LLM 无关，
 * 跑 40 条评估集不需要烧 40 次 token。
 */
public final class RetrievalResult {

    private final String originalQuery;
    private final QueryIntent intent;
    private final boolean routedByRule;
    private final List<XPathLintChannel.Finding> lintFindings;
    private final List<RetrievedItem> candidates;
    private final List<RetrievedItem> topItems;
    private final boolean rerankApplied;

    RetrievalResult(String originalQuery, QueryIntent intent, boolean routedByRule,
                    List<XPathLintChannel.Finding> lintFindings,
                    List<RetrievedItem> candidates, List<RetrievedItem> topItems,
                    boolean rerankApplied) {
        this.originalQuery = originalQuery;
        this.intent = intent;
        this.routedByRule = routedByRule;
        this.lintFindings = Collections.unmodifiableList(
                new ArrayList<XPathLintChannel.Finding>(lintFindings));
        this.candidates = Collections.unmodifiableList(new ArrayList<RetrievedItem>(candidates));
        this.topItems = Collections.unmodifiableList(new ArrayList<RetrievedItem>(topItems));
        this.rerankApplied = rerankApplied;
    }

    public String originalQuery() {
        return originalQuery;
    }

    public QueryIntent intent() {
        return intent;
    }

    /** 路由是规则判定的还是 LLM 判定的 —— 用来分析路由错误时该改规则还是改 prompt。 */
    public boolean routedByRule() {
        return routedByRule;
    }

    public List<XPathLintChannel.Finding> lintFindings() {
        return lintFindings;
    }

    /** 精排前的全部候选，按向量分降序。 */
    public List<RetrievedItem> candidates() {
        return candidates;
    }

    /** 最终交给生成层的片段。 */
    public List<RetrievedItem> topItems() {
        return topItems;
    }

    public boolean rerankApplied() {
        return rerankApplied;
    }

    public boolean isEmpty() {
        return topItems.isEmpty();
    }

    /** 按最终排序的标识列表，评估算 Recall / MRR 直接用它。 */
    public List<String> topIdentities() {
        List<String> identities = new ArrayList<String>(topItems.size());
        for (RetrievedItem item : topItems) {
            identities.add(item.identity());
        }
        return identities;
    }

    /** 候选阶段的标识列表，用来区分「没召回」和「召回了但排太后」。 */
    public List<String> candidateIdentities() {
        List<String> identities = new ArrayList<String>(candidates.size());
        for (RetrievedItem item : candidates) {
            identities.add(item.identity());
        }
        return identities;
    }
}
