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

        // 这一块在本篇文档里的序号，从 0 递增。存进 payload，调试时用来还原上下文
        int ordinal = 0;

        // 遍历解析阶段切好的小节 —— 一个 section = 一段正文 + 它的标题路径
        for (MarkdownDocument.Section section : doc.sections()) {
            String body = section.body().trim();

            if (body.isEmpty()) {
                // 只有标题没有正文的过渡性小节（比如 "## 常见错误" 下面直接是 "### 绝对路径"）。
                // 切出来会是一段只有前缀的空 chunk，纯噪音
                continue;
            }

            // 小节本身可能超过 700 字符上限，所以还要再按大小拆一次。
            // 拆出来的每一块**共用同一份标题路径** —— 它们本来就属于同一节
            for (String piece : sliceBySize(body)) {
                // withHeadingPath 会把 [文档 > 章 > 节] 前缀拼进 embedText，
                // 但 rawText 保持原样。这两份文本的分离就是消融表第 2 行的全部内容
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

        // 注意这里用的是 fullText() 而不是 sections() —— 完全不看解析出来的标题结构，
        // 把整篇 markdown（连 ## 符号一起）当成一条长字符串从头切到尾。
        // 这就是 baseline：切点落在哪全看字数，可能把一句话劈成两半
        for (String piece : sliceBySize(doc.fullText().trim())) {
            // plain 表示这一块不知道自己属于哪一节：headingPath 为空，
            // embedText 就等于 rawText，没有前缀可加
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

        // ⚠️ 单位是**字符**不是 token。两种策略共用这同一个上限 ——
        // 不然「标题路径更好」可能只是「chunk 更小所以更容易命中」的假象，
        // 那样消融表第 2 行就白做了。这条由 SplitterTest 钉死
        int size = config.chunkSizeChars();
        int overlap = config.chunkOverlapChars();

        // 短于上限就整段返回，不用切。绝大多数小节走这条路
        if (text.length() <= size) {
            pieces.add(text);
            return pieces;
        }

        int start = 0;
        while (start < text.length()) {
            // 本块的理论终点：起点 + 上限，但不能越过文本末尾
            int end = Math.min(start + size, text.length());

            // 还没到末尾的话，往回找一个更自然的断点（段落 > 句末标点）。
            // 已经是最后一块就不用找了，硬切到末尾即可
            if (end < text.length()) {
                end = preferBoundary(text, start, end);
            }

            String piece = text.substring(start, end).trim();
            // trim 后可能变空（整段都是空白），空块没有检索价值，丢掉
            if (!piece.isEmpty()) {
                pieces.add(piece);
            }

            // 已经切到末尾，收工
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
