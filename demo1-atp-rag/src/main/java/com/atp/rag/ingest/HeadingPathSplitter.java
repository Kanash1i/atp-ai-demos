package com.atp.rag.ingest;

import com.atp.rag.config.RagConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Markdown 切分器，两种策略共存 —— 消融表第 1 行和第 2 行的差别全在这里。
 *
 * <h3>为什么标题路径前缀有用</h3>
 *
 * 从手册里切出来的这么一段：
 *
 * <pre>优先使用 data-testid 属性，其次是 name，避免依赖 class。</pre>
 *
 * 单看它，检索时无从判断它在讲什么 —— 「优先使用」什么场景下的优先级？
 * 把标题层级拼进去再 embed，语义就完整了：
 *
 * <pre>[定位器指南 &gt; 属性选择的优先级 &gt; 稳定性排序]
 * 优先使用 data-testid 属性，其次是 name，避免依赖 class。</pre>
 *
 * <p>交接文档 §3.3(a) 预期这一项能带来 Recall@5 提升 5~10pt。真实数字由 M4 的评估跑出来 ——
 * 这里不预设结果。
 *
 * <h3>两种策略的公平性</h3>
 *
 * 两者用同一个 {@code chunkSizeChars} 上限。否则「标题路径更好」就可能只是
 * 「chunk 更小所以更容易命中」的假象，那样消融表这一行就白做了。
 */
public final class HeadingPathSplitter {

    private final RagConfig config;

    public HeadingPathSplitter(RagConfig config) {
        this.config = config;
    }

    public List<Chunk> split(MarkdownDocument doc) {
        if (config.chunkStrategy() == RagConfig.ChunkStrategy.FIXED) {
            return splitFixed(doc);
        }
        return splitByHeading(doc);
    }

    // ── HEADING_PATH ─────────────────────────────────────────

    /**
     * 按标题层级切：每个最深层小节自成一块，过长的再按大小拆开（拆出来的每块都保留同一份标题路径）。
     */
    private List<Chunk> splitByHeading(MarkdownDocument doc) {
        List<Chunk> chunks = new ArrayList<Chunk>();
        int ordinal = 0;

        for (MarkdownDocument.Section section : doc.sections()) {
            String body = section.body().trim();
            if (body.isEmpty()) {
                // 只有标题没有正文的过渡性小节（比如 "## 常见错误" 下面直接是 "### 绝对路径"）。
                // 切出来会是一段只有前缀的空 chunk，纯噪音
                continue;
            }
            for (String piece : sliceBySize(body)) {
                chunks.add(Chunk.withHeadingPath(
                        doc.sourceId(), doc.title(), section.headingPath(), piece, ordinal++));
            }
        }
        return chunks;
    }

    // ── FIXED（baseline）──────────────────────────────────────

    /** 无视标题结构，把全文当成一整条字符流硬切。 */
    private List<Chunk> splitFixed(MarkdownDocument doc) {
        List<Chunk> chunks = new ArrayList<Chunk>();
        int ordinal = 0;
        for (String piece : sliceBySize(doc.fullText().trim())) {
            chunks.add(Chunk.plain(doc.sourceId(), doc.title(), piece, ordinal++));
        }
        return chunks;
    }

    // ── 公共切分 ──────────────────────────────────────────────

    /**
     * 按字符数切，尽量在段落或句子边界断开。
     *
     * <p>overlap 是为了避免答案正好横跨切点。代价是同一段话会出现在两个 chunk 里，
     * 召回时可能重复 —— 这个重复由检索层去重，不在切分这一层处理。
     */
    private List<String> sliceBySize(String text) {
        List<String> pieces = new ArrayList<String>();
        int size = config.chunkSizeChars();
        int overlap = config.chunkOverlapChars();

        if (text.length() <= size) {
            pieces.add(text);
            return pieces;
        }

        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + size, text.length());
            if (end < text.length()) {
                end = preferBoundary(text, start, end);
            }
            String piece = text.substring(start, end).trim();
            if (!piece.isEmpty()) {
                pieces.add(piece);
            }
            if (end >= text.length()) {
                break;
            }
            // 回退 overlap 个字符作为下一块的起点。
            // Math.max 保证 start 一定前进，否则 overlap 接近 size 时会死循环
            start = Math.max(end - overlap, start + 1);
        }
        return pieces;
    }

    /**
     * 在 {@code end} 附近找一个更自然的断点，优先段落边界，其次句末标点。
     *
     * <p>硬切会把一句话劈成两半，两边都变得难以理解。只在后四分之一的窗口内找，
     * 找不到就接受硬切 —— 否则块大小会失控，两种策略之间就不可比了。
     */
    private int preferBoundary(String text, int start, int end) {
        int floor = start + (end - start) * 3 / 4;

        int paragraph = text.lastIndexOf("\n\n", end);
        if (paragraph > floor) {
            return paragraph;
        }
        for (int i = end - 1; i > floor; i--) {
            char ch = text.charAt(i);
            // 中日文的句末标点 + 换行。ASCII 句点没列进来 ——
            // 语料里的 "." 多半出现在 data-testid、文件名、版本号里，从那里断开更糟
            if (ch == '。' || ch == '！' || ch == '？' || ch == '\n') {
                return i + 1;
            }
        }
        return end;
    }
}
