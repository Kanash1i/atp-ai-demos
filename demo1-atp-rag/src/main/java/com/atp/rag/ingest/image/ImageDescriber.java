package com.atp.rag.ingest.image;

/**
 * 把文档里的图片转成一段文字描述，好让它能落进向量库。
 *
 * <h3>为什么图片必须转文字</h3>
 *
 * 向量库存的是文本的 embedding。bge-m3 是<b>纯文本</b>模型，喂不进图片。
 * 所以一张截图在入库时只有三种下场：
 *
 * <ol>
 *   <li><b>被忽略</b> —— 图里的信息彻底检索不到（现状，也是最常见的做法）</li>
 *   <li><b>只留 alt 文本</b> —— 聊胜于无，取决于原作者写没写 alt</li>
 *   <li><b>VLM 转成描述</b> —— 图里的内容变成可检索的文字</li>
 * </ol>
 *
 * <p>ATP 的手册天然图很多（平台截图、报错弹窗、字段填写示意），
 * 而且<b>图里往往就是答案</b> —— 「这个报错长什么样」「这个字段填在哪」，
 * 用文字描述半天不如一张图。忽略它们等于丢掉一部分语料。
 *
 * <h3>为什么做成接口</h3>
 *
 * 转描述这一步的质量、成本、可用性差别极大：本地 VLM 要显存，
 * 云端 VLM 要花钱且图片要出网（这个项目的合规前提是内部文档不出网），
 * 而 alt 文本零成本但信息量低。
 *
 * <p>做成接口之后，<b>入库流程不关心用的是哪一种</b>，
 * 换实现不用动 {@code CorpusIngestor}。同时降级路径永远可用 ——
 * 没配 VLM 时不会让整个入库失败，只是那部分信息弱一些。
 */
public interface ImageDescriber {

    /**
     * @param imagePath markdown 里写的图片路径，相对于语料根目录
     * @param altText   markdown 的 alt 文本，可能为空
     * @param context   图片所在小节的标题路径，给 VLM 当提示用
     *                  —— 同一张表格截图，在「字段说明」章节和「报错排查」章节里
     *                  该被描述成不同的重点
     * @return 一段可以直接拼进 chunk 的文字描述；无法描述时返回空串
     */
    String describe(String imagePath, String altText, String context);

    /** 这个实现当前是否真的可用。不可用时调用方应退回降级实现。 */
    boolean isAvailable();

    /** 出现在日志和 payload 里，用于标明某段描述是怎么来的。 */
    String name();
}
