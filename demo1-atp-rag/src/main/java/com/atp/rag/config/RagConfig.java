package com.atp.rag.config;

/**
 * 消融实验的开关集合。
 *
 * <p><b>这个类的存在本身就是设计意图</b>：交接文档 §5.3 的消融表要逐项叠加优化，
 * 一条命令跑完全部配置。如果每组配置靠改代码来切换，面试官现场说「加一组试试」就没法演示了。
 *
 * <p>所以每一项优化都是一个开关，配置对象决定：
 * <ul>
 *   <li>语料怎么切（{@link ChunkStrategy}）</li>
 *   <li>存进哪个 / 哪几个 collection（{@link CollectionMode}）</li>
 *   <li>检索时开哪些增强（rerank、query 改写、拒答约束）</li>
 * </ul>
 *
 * <p><b>collection 名由配置派生</b>，不是写死在 .env 里的。原因是切分策略和 embedding 模型
 * 一变，向量就不能复用了 —— 如果所有配置共用一个 collection，跑第 2 组会覆盖第 1 组的数据，
 * 消融表就只剩最后一行是真的。让配置决定名字，各组数据天然隔离，也能反复重跑。
 */
public final class RagConfig {

    /** 切分策略。消融表第 1 行 vs 第 2 行的区别就在这里。 */
    public enum ChunkStrategy {
        /** baseline：固定大小硬切，不管标题结构。 */
        FIXED("fixed"),
        /** 按 Markdown 标题层级切，并给每个 chunk 加 {@code [文档 > 章 > 节]} 前缀。 */
        HEADING_PATH("heading");

        private final String tag;

        ChunkStrategy(String tag) {
            this.tag = tag;
        }

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
    private final String embeddingTag;

    private RagConfig(Builder b) {
        this.chunkStrategy = b.chunkStrategy;
        this.collectionMode = b.collectionMode;
        this.chunkSizeChars = b.chunkSizeChars;
        this.chunkOverlapChars = b.chunkOverlapChars;
        this.rerankEnabled = b.rerankEnabled;
        this.queryRewriteEnabled = b.queryRewriteEnabled;
        this.refusalPromptEnabled = b.refusalPromptEnabled;
        this.embeddingTag = b.embeddingTag;
    }

    /** 消融表第 1 行：什么优化都不开。 */
    public static RagConfig baseline() {
        return builder()
                .chunkStrategy(ChunkStrategy.FIXED)
                .collectionMode(CollectionMode.SINGLE)
                .rerankEnabled(false)
                .queryRewriteEnabled(false)
                .refusalPromptEnabled(false)
                .build();
    }

    /** 消融表最后一行：全部优化都开。也是 CLI 演示用的配置。 */
    public static RagConfig full() {
        return builder()
                .chunkStrategy(ChunkStrategy.HEADING_PATH)
                .collectionMode(CollectionMode.DUAL)
                .rerankEnabled(Env.getBoolean("RERANK_ENABLED", true))
                .queryRewriteEnabled(true)
                .refusalPromptEnabled(true)
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
                .embeddingTag(embeddingTag);
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
        String prefix = Env.get("QDRANT_COLLECTION_PREFIX", "atp");
        StringBuilder name = new StringBuilder(prefix)
                .append('_').append(scope)
                .append('_').append(chunkStrategy.tag());
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

    public String embeddingTag() {
        return embeddingTag;
    }

    /** 一行摘要，写进消融表的「配置」列。 */
    public String describe() {
        return "chunk=" + chunkStrategy.tag()
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

        /**
         * 用字符数而非 token 数。
         *
         * <p>Java 8 这边没有 bge-m3 的 tokenizer，硬凑一个（比如借 tiktoken）反而会给出
         * 一个精确但错误的数字。而 bge-m3 的上限是 8192 token，我们的 chunk 离它很远，
         * 所以精确 token 数在这里并不重要 —— 重要的是两种切分策略之间的对比是公平的。
         *
         * <p>700 字符对中日文约合 500~700 token，接近交接文档说的 512。
         */
        private int chunkSizeChars = Env.getInt("CHUNK_SIZE_CHARS", 700);
        private int chunkOverlapChars = Env.getInt("CHUNK_OVERLAP_CHARS", 80);

        private boolean rerankEnabled = Env.getBoolean("RERANK_ENABLED", true);
        private boolean queryRewriteEnabled = true;
        private boolean refusalPromptEnabled = true;
        private String embeddingTag = Env.get("EMBEDDING_TAG", "");

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

        public Builder embeddingTag(String v) {
            this.embeddingTag = v;
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
