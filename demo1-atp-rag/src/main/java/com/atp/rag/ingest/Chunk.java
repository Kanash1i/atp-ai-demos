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
    private final String parentAnchor;
    private final String parentText;

    /**
     * 本块引用的原图地址。
     *
     * <p><b>不参与 embedding</b> —— 图片的可检索形式是它的文字描述（已经在
     * {@link #embedText} 里）。URL 只进 payload，供引用展示时让人点开看原图。
     * 描述是有损的，用户想确认「界面到底长什么样」时需要原件。
     */
    private final List<String> imageUrls;

    private Chunk(String sourceId, String docTitle, List<String> headingPath,
                  String rawText, String embedText, int ordinal,
                  String parentAnchor, String parentText, List<String> imageUrls) {
        this.sourceId = sourceId;
        this.docTitle = docTitle;
        this.headingPath = headingPath;
        this.rawText = rawText;
        this.embedText = embedText;
        this.ordinal = ordinal;
        this.parentAnchor = parentAnchor;
        this.parentText = parentText;
        this.imageUrls = imageUrls == null
                ? java.util.Collections.<String>emptyList()
                : java.util.Collections.unmodifiableList(new ArrayList<String>(imageUrls));
    }

    /** 带标题路径前缀 —— {@code [ATP平台手册 > 定位器指南 > XPath 编写建议]\n正文…} */
    public static Chunk withHeadingPath(String sourceId, String docTitle,
                                        List<String> headingPath, String rawText, int ordinal) {
        return withHeadingPath(sourceId, docTitle, headingPath, rawText, ordinal, null);
    }

    /** 带原图地址的版本 —— PDF / DOCX 里抽出图片时用。 */
    public static Chunk withHeadingPath(String sourceId, String docTitle,
                                        List<String> headingPath, String rawText, int ordinal,
                                        List<String> imageUrls) {
        return new Chunk(sourceId, docTitle, new ArrayList<String>(headingPath),
                rawText, buildPrefix(docTitle, headingPath) + "\n" + rawText, ordinal,
                null, null, imageUrls);
    }

    /**
     * 父子切块（small-to-big）：向量算<b>子块</b>，但检索命中后交出<b>父块</b>。
     *
     * @param parentAnchor 父块标识，检索层按它去重 —— 同一章节下的多个子块被同时召回时，
     *                     父块正文只该喂给模型一次
     * @param parentText   父块正文（整个二级章节），命中后实际交出去的内容
     */
    public static Chunk withParent(String sourceId, String docTitle, List<String> headingPath,
                                   String childText, int ordinal,
                                   String parentAnchor, String parentText) {
        return withParent(sourceId, docTitle, headingPath, childText, ordinal,
                parentAnchor, parentText, null);
    }

    /** 带原图地址的版本。 */
    public static Chunk withParent(String sourceId, String docTitle, List<String> headingPath,
                                   String childText, int ordinal,
                                   String parentAnchor, String parentText,
                                   List<String> imageUrls) {
        return new Chunk(sourceId, docTitle, new ArrayList<String>(headingPath),
                // rawText 存父块 —— 它是最终展示、最终喂给模型的那份
                parentText,
                // embedText 存子块（带前缀）—— 只用来算向量，追求检索精度
                buildPrefix(docTitle, headingPath) + "\n" + childText,
                ordinal, parentAnchor, parentText, imageUrls);
    }

    /** baseline 用：正文即全部，不知道自己在哪一章。 */
    public static Chunk plain(String sourceId, String docTitle, String rawText, int ordinal) {
        return new Chunk(sourceId, docTitle, new ArrayList<String>(), rawText, rawText, ordinal,
                null, null, null);
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
     * 父块标识，非父子切块时为 null。
     *
     * <p>检索层按它去重：同一章节下的多个子块被同时召回是常态
     * （它们语义相近所以一起命中），但父块正文只该交出去一次。
     */
    public String parentAnchor() {
        return parentAnchor;
    }

    /**
     * 本块引用的原图地址，可能为空。
     *
     * <p>存进 payload 供引用展示 —— 不参与 embedding。
     */
    public List<String> imageUrls() {
        return imageUrls;
    }

    /** 有没有原图，用来省掉无谓的 payload 字段。 */
    public boolean hasImages() {
        return !imageUrls.isEmpty();
    }

    /** 是否走了父子切块。 */
    public boolean hasParent() {
        return parentAnchor != null;
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
