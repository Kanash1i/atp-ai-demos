package com.atp.rag.ingest;

import java.util.List;

/**
 * 一篇解析完成的文档，与来源格式无关。
 *
 * <h3>为什么要这层接口</h3>
 *
 * 企业里手册和规范是 PDF / DOCX，markdown 只有开发者看。三种格式的<b>解析</b>
 * 差别极大（markdown 数 {@code #}、DOCX 读 {@code w:pStyle}、PDF 遍历 outline 书签
 * 再按 Y 坐标切矩形），但<b>切分和入库</b>应该完全一样。
 *
 * <p>所以在这里把差异收口：解析器负责把各自格式变成
 * 「文档标题 + 一串带标题路径的小节」，之后的三种切分策略、向量化、payload
 * 构造全都只认这个接口。
 *
 * <p>直接好处是 {@code golden_ids} 三种格式通用 —— {@link Chunk#anchor()} 由
 * {@code sourceId + 小节标题} 组成，与格式无关，所以同一套评估集能横跨三种语料，
 * 消融表里才能加「只换格式」的单变量对照。
 */
public interface ParsedDocument {

    /**
     * 相对语料根的稳定标识，如 {@code manual/04-定位器指南.md}。
     *
     * <p>评估集的 {@code golden_ids} 以它为前缀，所以<b>必须稳定</b> ——
     * 换切分策略、换文件格式都不该让它变。
     */
    String sourceId();

    /** 文档标题。markdown 取一级标题，DOCX 取 Heading1，PDF 取 outline 根或元数据。 */
    String title();

    /**
     * 整篇的纯文本。
     *
     * <p>只有 FIXED（baseline）策略用得上 —— 它无视一切结构，
     * 把全文当一条字符流硬切。其余策略走 {@link #sections()}。
     */
    String fullText();

    /** 按标题层级切好的小节，保持文档原本的顺序。 */
    List<DocSection> sections();
}
