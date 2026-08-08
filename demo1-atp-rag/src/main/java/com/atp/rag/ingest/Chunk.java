package com.atp.rag.ingest;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个待入库的文档片段。
 *
 * <p>关键区分是 {@link #embedText()} 与 {@link #rawText()}：
 * <b>拿去算向量的文本，和给模型看的文本，不必是同一份。</b>
 * 标题路径前缀要参与 embedding（它承载了「这段话在讲什么」的上下文），
 * 但引用来源时展示的是不带前缀的正文。
 */
public final class Chunk {

    private final String sourceId;
    private final String docTitle;
    private final List<String> headingPath;
    private final String rawText;
    private final String embedText;
    private final int ordinal;

    private Chunk(String sourceId, String docTitle, List<String> headingPath,
                  String rawText, String embedText, int ordinal) {
        this.sourceId = sourceId;
        this.docTitle = docTitle;
        this.headingPath = headingPath;
        this.rawText = rawText;
        this.embedText = embedText;
        this.ordinal = ordinal;
    }

    /** 带标题路径前缀 —— {@code [ATP平台手册 > 定位器指南 > XPath 编写建议]\n正文…} */
    public static Chunk withHeadingPath(String sourceId, String docTitle,
                                        List<String> headingPath, String rawText, int ordinal) {
        return new Chunk(sourceId, docTitle, new ArrayList<String>(headingPath),
                rawText, buildPrefix(docTitle, headingPath) + "\n" + rawText, ordinal);
    }

    /** baseline 用：正文即全部，不知道自己在哪一章。 */
    public static Chunk plain(String sourceId, String docTitle, String rawText, int ordinal) {
        return new Chunk(sourceId, docTitle, new ArrayList<String>(), rawText, rawText, ordinal);
    }

    private static String buildPrefix(String docTitle, List<String> headingPath) {
        StringBuilder sb = new StringBuilder("[").append(docTitle);
        for (String heading : headingPath) {
            sb.append(" > ").append(heading);
        }
        return sb.append(']').toString();
    }

    /** 供人阅读与引用展示的正文，不含前缀。 */
    public String rawText() {
        return rawText;
    }

    /** 真正送去算向量的文本。 */
    public String embedText() {
        return embedText;
    }

    public String sourceId() {
        return sourceId;
    }

    public String docTitle() {
        return docTitle;
    }

    public List<String> headingPath() {
        return headingPath;
    }

    public int ordinal() {
        return ordinal;
    }

    /**
     * 供评估集比对的锚点，形如 {@code manual/04-定位器指南.md#XPath 编写建议}。
     *
     * <p>评估集 {@code questions.jsonl} 里的 {@code golden_ids} 用的就是这个格式 ——
     * 定位到「哪一节」，而不是「第几个 chunk」。chunk 序号会随切分策略变化，
     * 用它当锚点的话，换个切分策略整份评估集就废了。
     */
    public String anchor() {
        if (headingPath.isEmpty()) {
            return sourceId;
        }
        return sourceId + "#" + headingPath.get(headingPath.size() - 1);
    }
}
