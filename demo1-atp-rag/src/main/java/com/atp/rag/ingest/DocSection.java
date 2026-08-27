package com.atp.rag.ingest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一个最小的可检索单元：一段正文，加上它所处的标题层级。
 *
 * <p><b>格式无关</b> —— 不管来源是 markdown 的 {@code ##}、DOCX 的
 * {@code Heading2} 样式，还是 PDF 的 outline 书签，最后都归到这一个模型。
 * 三种切分策略（FIXED / HEADING_PATH / PARENT_CHILD）因此只需要写一遍。
 *
 * <p>这一层抽象是「PDF 和 DOCX 也能用上标题路径前缀和父子切块」的关键：
 * 如果每种格式各写一套切分，消融表里的策略对照就不可能跨格式成立。
 */
public final class DocSection {

    private final List<String> headingPath;
    private final String body;
    private final List<String> imageUrls;

    public DocSection(List<String> headingPath, String body) {
        this(headingPath, body, Collections.<String>emptyList());
    }

    /**
     * @param imageUrls 本节里的图片在对象存储上的地址。
     *                  <b>不参与 embedding</b> —— 图片的可检索形式是它的文字描述
     *                  （已经拼进 {@code body}），URL 只在引用展示时给出，
     *                  让人能点开看原图。见 {@code ObjectStorage} 的注释
     */
    public DocSection(List<String> headingPath, String body, List<String> imageUrls) {
        this.headingPath = Collections.unmodifiableList(new ArrayList<String>(headingPath));
        this.body = body;
        this.imageUrls = Collections.unmodifiableList(new ArrayList<String>(imageUrls));
    }

    /**
     * 从二级标题往下的路径，不含文档标题。
     *
     * <p>空列表是合法的，表示这段正文不属于任何小节 —— 典型是文档开头
     * 「标题之后、第一个二级标题之前」的引言。曾经这种 section 被整个丢弃，
     * 见 {@link MarkdownDocument} 里 flush 方法的注释。
     */
    public List<String> headingPath() {
        return headingPath;
    }

    public String body() {
        return body;
    }

    /**
     * 本节图片的原图地址，可能为空。
     *
     * <p>存进 payload 供引用展示 —— 描述文本是有损的，用户想确认「界面到底长什么样」
     * 时需要原件。
     */
    public List<String> imageUrls() {
        return imageUrls;
    }

    /** 有没有图片，用来省掉无谓的 payload 字段。 */
    public boolean hasImages() {
        return !imageUrls.isEmpty();
    }
}
