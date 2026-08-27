package com.atp.rag.retrieve;

import dev.langchain4j.data.segment.TextSegment;

/**
 * 一条召回结果。
 *
 * <p>同时保留了 {@link #vectorScore} 和 {@link #rerankScore} —— 不是为了好看，
 * 是因为消融表要回答「rerank 到底把什么从第几名提到了第几名」。
 * 只留最终分数的话，rerank 那一行就只剩一个总分变化，说不清它做了什么。
 */
public final class RetrievedItem {

    private final TextSegment segment;
    private final double vectorScore;
    private final int vectorRank;
    private final String sourceCollection;
    private double rerankScore = Double.NaN;

    public RetrievedItem(TextSegment segment, double vectorScore, int vectorRank,
                         String sourceCollection) {
        this.segment = segment;
        this.vectorScore = vectorScore;
        this.vectorRank = vectorRank;
        this.sourceCollection = sourceCollection;
    }

    public TextSegment segment() {
        return segment;
    }

    public String text() {
        return segment.text();
    }

    /** 向量检索的相似度。 */
    public double vectorScore() {
        return vectorScore;
    }

    /** 向量检索里的名次（从 1 起），用于展示 rerank 的位次变化。 */
    public int vectorRank() {
        return vectorRank;
    }

    public double rerankScore() {
        return rerankScore;
    }

    public void rerankScore(double score) {
        this.rerankScore = score;
    }

    public boolean reranked() {
        return !Double.isNaN(rerankScore);
    }

    /** 排序用的最终分数：精排过就用精排分，否则退回向量分。 */
    public double finalScore() {
        return reranked() ? rerankScore : vectorScore;
    }

    public String sourceCollection() {
        return sourceCollection;
    }

    public boolean isCase() {
        return "case".equals(segment.metadata().getString("kind"));
    }

    /**
     * 评估比对用的标识。
     *
     * <p>文档用 {@code anchor}（{@code manual/04-定位器指南.md#XPath 编写建议}），
     * 案例用 {@code case_code}。评估集的 {@code golden_ids} 里两种混排，
     * 所以这里要统一成同一个概念。
     */
    public String identity() {
        return isCase()
                ? segment.metadata().getString("case_code")
                : segment.metadata().getString("anchor");
    }

    /**
     * 去重用的键。
     *
     * <p>普通情况下就是 {@link #identity()}。但<b>父子切块</b>时不同 ——
     * 同一章节下的多个子块常常一起被召回（它们语义相近，本来就该一起命中），
     * 而它们的 {@code rawText} 是<b>同一份父块正文</b>。
     * 按 identity 去重的话这些子块都会留下，等于把同一段话喂给模型好几遍，
     * 白白挤掉别的内容、也浪费 token。
     *
     * <p>所以有父块时按父块去重：一个章节最多贡献一条上下文。
     */
    public String dedupeKey() {
        String parent = segment.metadata().getString("parent_anchor");
        return parent != null && !parent.isEmpty() ? parent : identity();
    }

    /** 引用展示用的一行标题。 */
    public String citationLabel() {
        if (isCase()) {
            return segment.metadata().getString("case_code") + " "
                    + segment.metadata().getString("title");
        }
        String docTitle = segment.metadata().getString("doc_title");
        String headingPath = segment.metadata().getString("heading_path");
        return (headingPath == null || headingPath.isEmpty())
                ? docTitle : docTitle + " > " + headingPath;
    }

    /** 案例的违规码，非案例或无违规时返回空串。 */
    public String violationCodes() {
        if (!isCase()) {
            return "";
        }
        String codes = segment.metadata().getString("violation_codes");
        return codes == null ? "" : codes.replace(",", " ").trim();
    }

    public boolean hasViolation() {
        return isCase() && "true".equals(segment.metadata().getString("has_violation"));
    }
}
