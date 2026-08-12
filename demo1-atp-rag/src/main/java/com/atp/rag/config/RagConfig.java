package com.atp.rag.config;

/**
 * 消融实验的开关集合 —— 一个不可变值对象。
 *
 * <p><b>这个类的存在本身就是设计意图</b>：交接文档 §5.3 的消融表要逐项叠加优化，
 * 一条命令跑完全部配置。如果每组配置靠改代码来切换，面试官现场说「加一组试试」就没法演示了。
 *
 * <p>它<b>不是</b> Spring bean，而是从 {@link AtpProperties} 派生出来的值对象。
 * 原因是消融实验要在一次进程里造出六七个不同的配置轮流跑，
 * 而容器里的 bean 是单例 —— 配置必须能自由复制和修改，不能被容器托管。
 *
 * <p>collection 名由配置派生而不是写死：切分策略和 embedding 模型一变，向量就不能复用了。
 * 若所有配置共用一个 collection，跑第 2 组会覆盖第 1 组的数据，
 * 消融表就只剩最后一行是真的 —— 而且不会报错。
 */
public final class RagConfig {

    /** 切分策略。消融表第 1 行 vs 第 2 行的区别就在这里。 */
    public enum ChunkStrategy {
        /** baseline：固定大小硬切，不管标题结构。 */
        FIXED("fixed"),
        /** 按 Markdown 标题层级切，并给每个 chunk 加 {@code [文档 > 章 > 节]} 前缀。 */
        HEADING_PATH("heading"),
        /**
         * 父子切块（small-to-big）：<b>用小块检索，用大块回答</b>。
         *
         * <p>子块是标题路径切出来的小节（本项目中位数 174 字符），
         * 父块是它所属的整个二级章节。检索命中子块，但喂给模型的是父块。
         *
         * <p>解决的问题：小节短、语义精准，所以检索命中率高；
         * 但只有那一小段的话，模型缺少回答所需的上下文
         * （比如命中「优先使用 data-testid」，却看不到同章节里的反例和理由）。
         *
         * <p>与 {@link #HEADING_PATH} 的关系：那个用<b>标题前缀</b>给小块补上下文，
         * 只补了「它在讲什么」；这个用<b>父块正文</b>补，补的是「完整的论述」。
         * 两者解决同一个问题，深度不同 —— 所以它们该是消融表相邻的两行。
         */
        PARENT_CHILD("parent");

        private final String tag;

        ChunkStrategy(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    /**
     * 语料的<b>来源格式</b>。
     *
     * <h4>为什么这是一个消融维度而不是实现细节</h4>
     *
     * 企业里手册和规范是 PDF / DOCX，markdown 只有开发者看。所以「链路能吃 PDF」
     * 是基本要求，但更要紧的问题是<b>吃得一样好吗</b> ——
     * PDF 抽出来的文本会不会串行、标题层级还认不认得出来、表格会不会塌掉。
     *
     * <p>这些只有对照才看得出来。三种格式的语料是<b>同一份内容</b>
     * （由 {@code gen-corpus} 从 md 生成），小节标题相同，所以评估集的
     * {@code golden_ids} 三边通用 —— 消融表里就能加一组
     * 「同样的问题、同样的策略，只换语料格式」的单变量对照。
     */
    public enum CorpusFormat {

        /** markdown。层级来自 {@code ##} 的个数，最直接。 */
        MARKDOWN("corpus/docs", ".md", ""),

        /**
         * PDF。层级来自 outline 书签 + 页内 Y 坐标。
         *
         * <p>最脆的一档：没书签就没层级，书签没带坐标就切不开同页的多个小节。
         */
        PDF("corpus/docs-pdf", ".pdf", "pdf"),

        /**
         * DOCX。层级来自段落样式 {@code Heading1..9} 与 {@code outlineLvl}。
         *
         * <p>结构化、显式，没有猜的成分 —— 比 PDF 可靠得多。
         */
        DOCX("corpus/docs-docx", ".docx", "docx");

        private final String defaultDir;
        private final String suffix;
        private final String tag;

        CorpusFormat(String defaultDir, String suffix, String tag) {
            this.defaultDir = defaultDir;
            this.suffix = suffix;
            this.tag = tag;
        }

        /** 相对模块根的语料目录。 */
        public String defaultDir() {
            return defaultDir;
        }

        /** 文件后缀，用来筛目录里的文件。 */
        public String suffix() {
            return suffix;
        }

        /**
         * collection 名的格式段。
         *
         * <p>MARKDOWN 是空串 —— 让 md 的 collection 名保持原样
         * （{@code atp_docs_heading} 而不是 {@code atp_docs_heading_md}），
         * 这样之前入的库和已有的评估脚本都不用动。
         */
        public String tag() {
            return tag;
        }
    }

    /** collection 划分方式。消融表第 2 行 vs 第 3 行的区别。 */
    public enum CollectionMode {
        /** baseline：文档和案例混在一个 collection 里。 */
        SINGLE,
        /** 文档与案例分库，检索时按查询意图路由。 */
        DUAL
    }

    private final ChunkStrategy chunkStrategy;
    private final CollectionMode collectionMode;
    private final int chunkSizeChars;
    private final int chunkOverlapChars;
    private final boolean rerankEnabled;
    private final boolean queryRewriteEnabled;
    private final boolean refusalPromptEnabled;
    private final int candidateTopK;
    private final int finalTopK;
    private final String collectionPrefix;
    private final String embeddingTag;
    private final CorpusFormat corpusFormat;

    private RagConfig(Builder b) {
        this.chunkStrategy = b.chunkStrategy;
        this.collectionMode = b.collectionMode;
        this.chunkSizeChars = b.chunkSizeChars;
        this.chunkOverlapChars = b.chunkOverlapChars;
        this.rerankEnabled = b.rerankEnabled;
        this.queryRewriteEnabled = b.queryRewriteEnabled;
        this.refusalPromptEnabled = b.refusalPromptEnabled;
        this.candidateTopK = b.candidateTopK;
        this.finalTopK = b.finalTopK;
        this.collectionPrefix = b.collectionPrefix;
        this.embeddingTag = b.embeddingTag;
        this.corpusFormat = b.corpusFormat;
    }

    /** 按 {@code application.yml} 里 {@code atp.rag.*} 的值构造。也就是「全部优化都开」。 */
    public static RagConfig from(AtpProperties props) {
        AtpProperties.Rag rag = props.getRag();
        return builder()
                .chunkStrategy(rag.getChunkStrategy())
                .collectionMode(rag.getCollectionMode())
                .chunkSizeChars(rag.getChunkSizeChars())
                .chunkOverlapChars(rag.getChunkOverlapChars())
                .rerankEnabled(props.getRerank().isEnabled())
                .queryRewriteEnabled(rag.isQueryRewriteEnabled())
                .refusalPromptEnabled(rag.isRefusalPromptEnabled())
                .candidateTopK(rag.getCandidateTopK())
                .finalTopK(rag.getFinalTopK())
                .collectionPrefix(props.getQdrant().getCollectionPrefix())
                .embeddingTag(props.getEmbedding().getTag())
                .corpusFormat(rag.getCorpusFormat())
                .build();
    }

    /** 消融表第 1 行：什么优化都不开。 */
    public static RagConfig baseline(AtpProperties props) {
        return from(props).toBuilder()
                .chunkStrategy(ChunkStrategy.FIXED)
                .collectionMode(CollectionMode.SINGLE)
                .rerankEnabled(false)
                .queryRewriteEnabled(false)
                .refusalPromptEnabled(false)
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 以当前配置为基础改几项，用于消融表逐行叠加。 */
    public Builder toBuilder() {
        return new Builder()
                .chunkStrategy(chunkStrategy)
                .collectionMode(collectionMode)
                .chunkSizeChars(chunkSizeChars)
                .chunkOverlapChars(chunkOverlapChars)
                .rerankEnabled(rerankEnabled)
                .queryRewriteEnabled(queryRewriteEnabled)
                .refusalPromptEnabled(refusalPromptEnabled)
                .candidateTopK(candidateTopK)
                .finalTopK(finalTopK)
                .collectionPrefix(collectionPrefix)
                .embeddingTag(embeddingTag)
                .corpusFormat(corpusFormat);
    }

    // ── collection 命名 ───────────────────────────────────────

    /**
     * 文档 collection 名。{@link CollectionMode#SINGLE} 下与案例共用一个。
     *
     * <p>命名规则 {@code {prefix}_{scope}_{chunk}[_{embedTag}]}，例如
     * {@code atp_docs_heading}、{@code atp_all_fixed}、{@code atp_docs_heading_qwen3}。
     */
    public String docsCollection() {
        return collectionName(collectionMode == CollectionMode.SINGLE ? "all" : "docs");
    }

    /** 案例 collection 名。SINGLE 模式下与文档是同一个。 */
    public String casesCollection() {
        return collectionName(collectionMode == CollectionMode.SINGLE ? "all" : "cases");
    }

    private String collectionName(String scope) {
        StringBuilder name = new StringBuilder(collectionPrefix)
                .append('_').append(scope)
                .append('_').append(chunkStrategy.tag());
        // 语料格式变了，chunk 边界和正文都会变，向量不能复用。
        // MARKDOWN 的 tag 是空串，所以 md 的 collection 名保持原样
        if (!corpusFormat.tag().isEmpty()) {
            name.append('_').append(corpusFormat.tag());
        }
        // embedding 模型换了，向量空间就变了，旧 collection 里的向量不能混用。
        // 消融表第 7 行（Qwen3）靠这个后缀和前 6 行隔离开
        if (embeddingTag != null && !embeddingTag.isEmpty()) {
            name.append('_').append(embeddingTag);
        }
        return name.toString();
    }

    // ── getters ──────────────────────────────────────────────

    public ChunkStrategy chunkStrategy() {
        return chunkStrategy;
    }

    public CollectionMode collectionMode() {
        return collectionMode;
    }

    public CorpusFormat corpusFormat() {
        return corpusFormat;
    }

    public int chunkSizeChars() {
        return chunkSizeChars;
    }

    public int chunkOverlapChars() {
        return chunkOverlapChars;
    }

    public boolean rerankEnabled() {
        return rerankEnabled;
    }

    public boolean queryRewriteEnabled() {
        return queryRewriteEnabled;
    }

    public boolean refusalPromptEnabled() {
        return refusalPromptEnabled;
    }

    public int candidateTopK() {
        return candidateTopK;
    }

    public int finalTopK() {
        return finalTopK;
    }

    public String embeddingTag() {
        return embeddingTag;
    }

    /** 一行摘要，写进消融表的「配置」列。 */
    public String describe() {
        return "format=" + corpusFormat.name().toLowerCase()
                + " chunk=" + chunkStrategy.tag()
                + " collections=" + collectionMode.name().toLowerCase()
                + " rerank=" + rerankEnabled
                + " rewrite=" + queryRewriteEnabled
                + " refusal=" + refusalPromptEnabled
                + (embeddingTag == null || embeddingTag.isEmpty() ? "" : " embed=" + embeddingTag);
    }

    @Override
    public String toString() {
        return "RagConfig{" + describe() + "}";
    }

    public static final class Builder {
        private ChunkStrategy chunkStrategy = ChunkStrategy.HEADING_PATH;
        private CollectionMode collectionMode = CollectionMode.DUAL;
        private int chunkSizeChars = 700;
        private int chunkOverlapChars = 80;
        private boolean rerankEnabled = true;
        private boolean queryRewriteEnabled = true;
        private boolean refusalPromptEnabled = true;
        private int candidateTopK = 20;
        private int finalTopK = 5;
        private String collectionPrefix = "atp";
        private String embeddingTag = "";
        private CorpusFormat corpusFormat = CorpusFormat.MARKDOWN;

        public Builder chunkStrategy(ChunkStrategy v) {
            this.chunkStrategy = v;
            return this;
        }

        public Builder collectionMode(CollectionMode v) {
            this.collectionMode = v;
            return this;
        }

        public Builder chunkSizeChars(int v) {
            this.chunkSizeChars = v;
            return this;
        }

        public Builder chunkOverlapChars(int v) {
            this.chunkOverlapChars = v;
            return this;
        }

        public Builder rerankEnabled(boolean v) {
            this.rerankEnabled = v;
            return this;
        }

        public Builder queryRewriteEnabled(boolean v) {
            this.queryRewriteEnabled = v;
            return this;
        }

        public Builder refusalPromptEnabled(boolean v) {
            this.refusalPromptEnabled = v;
            return this;
        }

        public Builder candidateTopK(int v) {
            this.candidateTopK = v;
            return this;
        }

        public Builder finalTopK(int v) {
            this.finalTopK = v;
            return this;
        }

        public Builder collectionPrefix(String v) {
            this.collectionPrefix = v;
            return this;
        }

        public Builder corpusFormat(CorpusFormat v) {
            this.corpusFormat = v == null ? CorpusFormat.MARKDOWN : v;
            return this;
        }

        public Builder embeddingTag(String v) {
            this.embeddingTag = v == null ? "" : v;
            return this;
        }

        public RagConfig build() {
            if (chunkSizeChars <= 0) {
                throw new IllegalStateException("chunkSizeChars 必须为正数");
            }
            if (chunkOverlapChars < 0 || chunkOverlapChars >= chunkSizeChars) {
                throw new IllegalStateException(
                        "chunkOverlapChars 必须在 [0, chunkSizeChars) 内，"
                                + "否则切分会原地打转切不完");
            }
            return new RagConfig(this);
        }
    }
}
