package com.atp.rag.ingest.image;

/**
 * 从 PDF / DOCX 容器里抽出来的一张图，连同它在文档里的位置。
 *
 * <p>位置信息是必需的，不是附赠 —— 一张图必须能归到<b>正确的小节</b>。
 * 归错了比丢掉更糟：报错截图的描述跑到「字段说明」那一节里，
 * 会让那一节的向量被污染，同时真正该有它的那节又缺内容。
 */
public final class ExtractedImage {

    private final byte[] content;
    private final String contentType;
    private final String nameHint;
    private final String altText;

    /** 1-based 页码。DOCX 没有页概念，填 0。 */
    private final int pageNumber;

    /**
     * 图片<b>顶边</b>的 Y 坐标，PDF 坐标系（原点左下角，向上为正）。
     *
     * <p>取顶边而不是底边，因为小节的划分也是按标题顶边算的 ——
     * 两者用同一个基准才不会把跨越切分线的图片归错。
     */
    private final float top;

    public ExtractedImage(byte[] content, String contentType, String nameHint,
                          String altText, int pageNumber, float top) {
        this.content = content;
        this.contentType = contentType;
        this.nameHint = nameHint;
        this.altText = altText == null ? "" : altText;
        this.pageNumber = pageNumber;
        this.top = top;
    }

    public byte[] content() {
        return content;
    }

    public String contentType() {
        return contentType;
    }

    /** 用于生成存储 key、日志、以及降级描述时榨关键词。 */
    public String nameHint() {
        return nameHint;
    }

    /** 文档自带的替代文本。PDF 基本没有；DOCX 看有没有设 {@code docPr/@descr}。 */
    public String altText() {
        return altText;
    }

    public int pageNumber() {
        return pageNumber;
    }

    public float top() {
        return top;
    }

    /** 按 contentType 猜个扩展名，拼存储 key 用。 */
    public String extension() {
        if (contentType == null) {
            return "png";
        }
        if (contentType.contains("jpeg") || contentType.contains("jpg")) {
            return "jpg";
        }
        if (contentType.contains("gif")) {
            return "gif";
        }
        if (contentType.contains("tiff")) {
            return "tiff";
        }
        return "png";
    }
}
