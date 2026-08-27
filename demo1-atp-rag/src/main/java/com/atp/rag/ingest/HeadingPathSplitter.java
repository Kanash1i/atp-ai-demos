package com.atp.rag.ingest;

import com.atp.rag.config.RagConfig;
import com.atp.rag.ingest.image.AltTextImageDescriber;
import com.atp.rag.ingest.image.ImageDescriber;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * 图片转文字描述的实现。默认用永远可用的 alt 降级版 ——
     * 配了 VLM 的话由调用方注入，切分器本身不关心用的是哪种。
     */
    private final ImageDescriber imageDescriber;

    public HeadingPathSplitter(RagConfig config) {
        this(config, new AltTextImageDescriber());
    }

    public HeadingPathSplitter(RagConfig config, ImageDescriber imageDescriber) {
        this.config = config;
        this.imageDescriber = imageDescriber;
    }

    /**
     * 把小节正文里的图片引用换成文字描述。
     *
     * <p>在切分之前做，而不是切完再做 —— 因为描述会改变文本长度，
     * 先替换才能保证「一块不超过 size 字符」这个约束算的是最终文本。
     */
    private String withImagesDescribed(DocSection section) {
        String body = section.body().trim();
        if (!MarkdownDocument.hasImage(body)) {
            return body;    // 绝大多数小节没有图，直接返回省掉正则
        }
        return MarkdownDocument.replaceImages(
                body, imageDescriber, String.join(" > ", section.headingPath())).trim();
    }

    public List<Chunk> split(ParsedDocument doc) {
        switch (config.chunkStrategy()) {
            case FIXED:
                return splitFixed(doc);
            case PARENT_CHILD:
                return splitParentChild(doc);
            default:
                return splitByHeading(doc);
        }
    }

    // ── PARENT_CHILD ─────────────────────────────────────────

    /**
     * 父子切块：<b>子块进向量，父块进上下文</b>。
     *
     * <p>为什么这个策略在本项目上有理由存在 —— 语料的实测数据：
     *
     * <pre>
     *   小节长度：中位数 174、p95 = 407 字符
     * </pre>
     *
     * 小节<b>非常短</b>。短对检索是好事（语义集中、命中精准），
     * 对回答却是坏事：命中「优先使用 data-testid 属性，其次是 name」这一句，
     * 模型看不到同章节里的反例、理由和适用边界。
     *
     * <p>{@link RagConfig.ChunkStrategy#HEADING_PATH} 用标题前缀缓解了这个问题，
     * 但前缀只告诉模型「这段在讲什么」，没给出「完整的论述」。
     * 父子切块给的是后者。
     *
     * <p>父块取<b>二级章节</b>（{@code ## } 那一层）而不是整篇文档：
     * 整篇太长会把无关内容一起塞进 prompt，二级章节是「一个完整论点」的天然边界。
     */
    private List<Chunk> splitParentChild(ParsedDocument doc) {
        // 先按二级标题分组，把同一章节下的所有小节正文拼成父块。
        // LinkedHashMap 保持文档原本的章节顺序，拼出来的父块读起来才是连贯的
        Map<String, StringBuilder> parents = new LinkedHashMap<String, StringBuilder>();

        // 父块的图片地址 = 它下面所有子小节的图片地址之和。
        // 因为交出去的正文是整个父块，里面所有图都该能点开
        Map<String, List<String>> parentImages = new LinkedHashMap<String, List<String>>();

        for (DocSection section : doc.sections()) {
            String key = parentKeyOf(section);
            if (!parents.containsKey(key)) {
                parents.put(key, new StringBuilder());
                parentImages.put(key, new ArrayList<String>());
            }
            parentImages.get(key).addAll(section.imageUrls());
            // 拼进父块时带上小节标题，否则父块会变成一大坨没有结构的文字
            StringBuilder parent = parents.get(key);
            if (parent.length() > 0) {
                parent.append("\n\n");
            }
            List<String> path = section.headingPath();
            if (path.size() > 1) {
                parent.append("### ").append(path.get(path.size() - 1)).append('\n');
            }
            parent.append(withImagesDescribed(section));
        }

        List<Chunk> chunks = new ArrayList<Chunk>();
        int ordinal = 0;
        for (DocSection section : doc.sections()) {
            String body = withImagesDescribed(section);
            if (body.isEmpty()) {
                continue;
            }
            String key = parentKeyOf(section);
            String parentText = parents.get(key).toString();

            // 子块仍然按大小上限切 —— 虽然本项目语料下几乎切不开，
            // 但换一份长文档语料时这一层要顶得住
            for (String piece : sliceBySize(body)) {
                chunks.add(Chunk.withParent(doc.sourceId(), doc.title(), section.headingPath(),
                        piece, ordinal++, doc.sourceId() + "#" + key, parentText,
                        parentImages.get(key)));
            }
        }
        return chunks;
    }

    /** 父块的分组键 = 二级标题。没有二级标题的（文档开头引言）自成一组。 */
    private static String parentKeyOf(DocSection section) {
        List<String> path = section.headingPath();
        return path.isEmpty() ? "(前言)" : path.get(0);
    }

    // ── HEADING_PATH ─────────────────────────────────────────

    /**
     * 按标题层级切：每个最深层小节自成一块，过长的再按大小拆开（拆出来的每块都保留同一份标题路径）。
     */
    private List<Chunk> splitByHeading(ParsedDocument doc) {
        List<Chunk> chunks = new ArrayList<Chunk>();

        // 这一块在本篇文档里的序号，从 0 递增。存进 payload，调试时用来还原上下文
        int ordinal = 0;

        // 遍历解析阶段切好的小节 —— 一个 section = 一段正文 + 它的标题路径
        for (DocSection section : doc.sections()) {
            // 图片引用在这一步变成文字描述，之后的切分只面对纯文本
            String body = withImagesDescribed(section);

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
                // 图片地址跟着 section 走。一个 section 被按大小切成多块时，
                // 每块都带同一份地址 —— 宁可重复，也别让某块丢掉原图链接
                chunks.add(Chunk.withHeadingPath(
                        doc.sourceId(), doc.title(), section.headingPath(), piece, ordinal++,
                        section.imageUrls()));
            }
        }
        return chunks;
    }

    // ── FIXED（baseline）──────────────────────────────────────

    /** 无视标题结构，把全文当成一整条字符流硬切。 */
    private List<Chunk> splitFixed(ParsedDocument doc) {
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
        //
        // ⚠️⚠️ 但要清楚它在两种策略下的作用完全不同（实测数据，见 DECISIONS.md D-017）：
        //
        //   本项目语料的小节长度：中位数 174、p95 = 407、最长 610 字符
        //
        //   size 从 700 加到 1200：
        //     HEADING_PATH → 89 块 → 89 块   （纹丝不动）
        //     FIXED        → 41 块 → 25 块   （直接减半）
        //
        // 也就是说 HEADING_PATH 下 **size 和 overlap 基本是死参数** ——
        // 所有小节都短于上限，下面那个 `length() <= size` 分支直接 return，
        // 连 overlap 那段循环都进不去。
        //
        // 这不是 bug，而是这个策略的**本意**：让 chunk 边界由文档的语义结构决定，
        // 而不是由一个拍出来的数字决定。参数只在 baseline（FIXED）那一行真正生效。
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
