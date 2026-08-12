package com.atp.rag.ingest.image;

/**
 * 降级实现：不调任何模型，只把 alt 文本和文件名整理成一句话。
 *
 * <p><b>它的价值不是「好」，而是「永远可用」。</b>
 * 没配 VLM、VLM 挂了、图片文件丢了 —— 任何情况下入库都不该因此失败，
 * 大不了那张图的信息弱一点。
 *
 * <p>能提取的信息其实比想象中多，因为技术文档的图片命名通常有规律：
 *
 * <pre>
 *   ![案例编辑页的等待策略下拉框](img/manual/case-edit-wait-strategy.png)
 *   → 「图片：案例编辑页的等待策略下拉框（case edit wait strategy）」
 * </pre>
 *
 * 文件名里的 {@code case-edit-wait-strategy} 拆开之后本身就是关键词，
 * 对检索是有贡献的 —— 尤其当原作者偷懒没写 alt 时，文件名往往是唯一线索。
 */
public final class AltTextImageDescriber implements ImageDescriber {

    @Override
    public String describe(String imagePath, String altText, String context) {
        String alt = altText == null ? "" : altText.trim();
        String fromFileName = keywordsFromFileName(imagePath);

        // 两个来源都空 —— 这张图确实没有任何可提取的信息，返回空串让调用方跳过。
        // 塞一句「图片：」进去只会稀释 chunk 的向量
        if (alt.isEmpty() && fromFileName.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("［图片］");
        if (!alt.isEmpty()) {
            sb.append(alt);
        }
        // 文件名关键词只在和 alt 不重复时才追加，避免同样的词出现两遍
        if (!fromFileName.isEmpty() && !alt.toLowerCase().contains(fromFileName.toLowerCase())) {
            sb.append(alt.isEmpty() ? "" : "（").append(fromFileName).append(alt.isEmpty() ? "" : "）");
        }
        return sb.toString();
    }

    /**
     * 字节版直接委托给路径版 —— 这个实现<b>本来就不看图片内容</b>，
     * 只从名字和 alt 里榨关键词，所以字节参数用不上。
     *
     * <p>这不是偷懒：降级实现的意义就是「零成本、永远可用」。
     * 它给出的信息量本来就只有名字和 alt 这么多。
     */
    @Override
    public String describeBytes(byte[] content, String nameHint, String altText, String context) {
        return describe(nameHint, altText, context);
    }

    /**
     * 从文件名里榨出关键词：{@code img/case-edit-wait.png} → {@code case edit wait}。
     *
     * <p>去掉目录、扩展名，把连字符下划线换成空格。纯数字的名字（{@code 001.png}）
     * 没有信息量，返回空串。
     */
    private static String keywordsFromFileName(String imagePath) {
        if (imagePath == null || imagePath.isEmpty()) {
            return "";
        }
        String name = imagePath;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        String words = name.replace('-', ' ').replace('_', ' ').trim();
        // 形如 "001"、"screenshot 2024 01 01" 这种全是数字的，提取不出语义
        return words.matches("[\\d\\s]*") ? "" : words;
    }

    /** 永远可用 —— 这正是它存在的理由。 */
    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String name() {
        return "alt-text";
    }
}
