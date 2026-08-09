package com.atp.rag.ingest;

import com.atp.rag.config.AtpProperties;
import com.atp.rag.config.RagConfig;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import io.qdrant.client.QdrantClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FilenameFilter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 把 {@code corpus/} 下的语料灌进 Qdrant。
 *
 * <p>文档与案例的处理方式刻意不同（交接文档 §3.1）：
 *
 * <table border="1">
 *   <tr><th></th><th>文档</th><th>案例</th></tr>
 *   <tr><td>切分</td><td>按标题层级 / 固定大小</td><td><b>整条不切</b></td></tr>
 *   <tr><td>embed 文本</td><td>标题路径前缀 + 正文</td><td>渲染成人话的步骤序列</td></tr>
 *   <tr><td>payload</td><td>来源、标题路径</td><td>模块、优先级、违规标记…</td></tr>
 * </table>
 *
 * <p>{@link RagConfig.CollectionMode#SINGLE} 下两者写进同一个 collection ——
 * 那是消融表第 1、2 行的 baseline，用来量化「分库到底值多少」。
 */
public final class CorpusIngestor {

    private static final Logger log = LoggerFactory.getLogger(CorpusIngestor.class);

    /** 一次 embed 多少条。TEI 侧有动态 batching，这里主要是控制单次请求体大小。 */
    private static final int EMBED_BATCH = 32;

    private final RagConfig config;
    private final EmbeddingModel embeddingModel;
    private final QdrantClient client;
    private final Path corpusRoot;
    private final int dimension;

    public CorpusIngestor(RagConfig config, EmbeddingModel embeddingModel,
                          QdrantClient client, AtpProperties props) {
        this.config = config;
        this.embeddingModel = embeddingModel;
        this.client = client;
        this.corpusRoot = Paths.get(props.getCorpus().getDir());
        this.dimension = props.getEmbedding().getDimension();
    }

    /** 建 collection 并灌入全部语料。返回各 collection 的实际点数，供调用方核对。 */
    public Result ingestAll() {
        String docsCollection = config.docsCollection();
        String casesCollection = config.casesCollection();

        log.info("入库配置 {}", config.describe());
        QdrantCollections.recreate(client, docsCollection, dimension);
        // SINGLE 模式下两个名字相同，重建第二次会把刚写的文档删掉
        if (!docsCollection.equals(casesCollection)) {
            QdrantCollections.recreate(client, casesCollection, dimension);
        }

        int docChunks = ingestDocuments(docsCollection);
        int caseCount = ingestCases(casesCollection);

        long docsPoints = QdrantCollections.countPoints(client, docsCollection);
        long casesPoints = docsCollection.equals(casesCollection)
                ? docsPoints : QdrantCollections.countPoints(client, casesCollection);

        verifySearchable(docsCollection);

        return new Result(docsCollection, casesCollection, docChunks, caseCount, docsPoints, casesPoints);
    }

    /**
     * 入库后立刻做一次真实检索。
     *
     * <p>点数对、维度对、payload 对，<b>都不能证明检索可用</b> —— D-002 那个坑就是
     * 写入完全正常（走 upsert，proto 没变），只有读回时向量是空的。
     * 所以「灌完就算成功」是不够的，必须真的查一次。
     *
     * <p>这一步顺带也验证了 embedding 服务没有在中途退化：
     * 如果 TEI 悄悄换到了 CPU 或返回了错误维度，这里会立刻失败，
     * 而不是等到 M4 跑评估时才发现整张消融表都是错的。
     */
    private void verifySearchable(String collection) {
        EmbeddingStore<TextSegment> store = QdrantEmbeddingStore.builder()
                .client(client)
                .collectionName(collection)
                .build();

        Embedding query = embeddingModel.embed("为什么规范禁止使用 SLEEP").content();
        List<EmbeddingMatch<TextSegment>> matches = store.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(query)
                .maxResults(3)
                .build()).matches();

        if (matches.isEmpty()) {
            throw new IllegalStateException(collection + " 写入成功但检索为空");
        }
        EmbeddingMatch<TextSegment> top = matches.get(0);
        if (top.embedded() == null || top.embedded().text().trim().isEmpty()) {
            throw new IllegalStateException(collection + " 召回了点，但正文是空的 —— payload 没写对");
        }
        log.info("检索冒烟 {}：top1 score={} anchor={}", collection,
                String.format("%.4f", top.score()),
                top.embedded().metadata().getString("anchor"));
    }

    // ── 文档 ──────────────────────────────────────────────────

    private int ingestDocuments(String collection) {
        HeadingPathSplitter splitter = new HeadingPathSplitter(config);
        List<TextSegment> segments = new ArrayList<TextSegment>();

        for (String group : Arrays.asList("manual", "standards")) {
            Path dir = corpusRoot.resolve("docs").resolve(group);
            for (File file : listFiles(dir, ".md")) {
                String sourceId = group + "/" + file.getName();
                MarkdownDocument doc = MarkdownDocument.parse(file.toPath(), sourceId);

                for (Chunk chunk : splitter.split(doc)) {
                    Metadata metadata = new Metadata();
                    metadata.put("kind", "doc");
                    metadata.put("source_id", sourceId);
                    metadata.put("doc_title", doc.title());
                    metadata.put("doc_group", group);
                    metadata.put("heading_path", String.join(" > ", chunk.headingPath()));
                    // 评估集的 golden_ids 比对的就是这个。它定位到「哪一节」而不是「第几块」，
                    // 所以换切分策略时评估集不用重写
                    metadata.put("anchor", chunk.anchor());
                    metadata.put("ordinal", chunk.ordinal());
                    // ⚠️ 存进 payload 的是 rawText（给人看的），送去算向量的是 embedText（带标题前缀）。
                    // 两者的分离正是这一行消融的全部内容
                    metadata.put("embed_text", chunk.embedText());

                    segments.add(TextSegment.from(chunk.rawText(), metadata));
                }
            }
        }

        embedAndStore(collection, segments, true);
        log.info("文档 → {}：{} 个 chunk", collection, segments.size());
        return segments.size();
    }

    // ── 案例 ──────────────────────────────────────────────────

    private int ingestCases(String collection) {
        List<TextSegment> segments = new ArrayList<TextSegment>();

        for (File file : listFiles(corpusRoot.resolve("cases"), ".json")) {
            AtpCase atpCase = AtpCase.parse(file);
            Metadata metadata = atpCase.toMetadata();
            String rendered = atpCase.renderForEmbedding();
            metadata.put("embed_text", rendered);
            // 案例的展示文本和 embed 文本相同 —— 渲染后的步骤序列本来就是给人看的
            segments.add(TextSegment.from(rendered, metadata));
        }

        embedAndStore(collection, segments, false);
        log.info("案例 → {}：{} 条", collection, segments.size());
        return segments.size();
    }

    // ── 公共 ──────────────────────────────────────────────────

    /**
     * @param useEmbedTextMetadata 为 true 时用 metadata 里的 {@code embed_text} 去算向量，
     *                             而不是 segment 自己的文本。文档需要这个 —— 它的向量要带标题路径前缀，
     *                             但展示时不该带
     */
    private void embedAndStore(String collection, List<TextSegment> segments, boolean useEmbedTextMetadata) {
        if (segments.isEmpty()) {
            throw new IllegalStateException("没有可入库的内容，检查 " + corpusRoot.toAbsolutePath());
        }
        EmbeddingStore<TextSegment> store = QdrantEmbeddingStore.builder()
                .client(client)
                .collectionName(collection)
                .build();

        for (int from = 0; from < segments.size(); from += EMBED_BATCH) {
            int to = Math.min(from + EMBED_BATCH, segments.size());
            List<TextSegment> batch = segments.subList(from, to);

            List<TextSegment> toEmbed = new ArrayList<TextSegment>(batch.size());
            for (TextSegment segment : batch) {
                toEmbed.add(useEmbedTextMetadata
                        ? TextSegment.from(segment.metadata().getString("embed_text"))
                        : segment);
            }

            Response<List<Embedding>> response = embeddingModel.embedAll(toEmbed);
            List<Embedding> embeddings = response.content();
            if (embeddings.size() != batch.size()) {
                throw new IllegalStateException("embedding 返回 " + embeddings.size()
                        + " 条，与请求的 " + batch.size() + " 条不符");
            }
            store.addAll(embeddings, batch);
        }
    }

    private static List<File> listFiles(Path dir, final String suffix) {
        File[] files = dir.toFile().listFiles(new FilenameFilter() {
            public boolean accept(File d, String name) {
                return name.endsWith(suffix);
            }
        });
        if (files == null) {
            throw new IllegalStateException("找不到语料目录 " + dir.toAbsolutePath()
                    + "（当前工作目录 " + Paths.get("").toAbsolutePath() + "）");
        }
        Arrays.sort(files);
        return Arrays.asList(files);
    }

    /** 入库结果，用于打印与断言。 */
    public static final class Result {
        public final String docsCollection;
        public final String casesCollection;
        public final int docChunks;
        public final int caseCount;
        public final long docsPoints;
        public final long casesPoints;

        Result(String docsCollection, String casesCollection, int docChunks, int caseCount,
               long docsPoints, long casesPoints) {
            this.docsCollection = docsCollection;
            this.casesCollection = casesCollection;
            this.docChunks = docChunks;
            this.caseCount = caseCount;
            this.docsPoints = docsPoints;
            this.casesPoints = casesPoints;
        }

        public boolean isSingleCollection() {
            return docsCollection.equals(casesCollection);
        }

        /** 写入条数与 Qdrant 实际点数是否对得上 —— 对不上说明有静默丢失。 */
        public long expectedTotalPoints() {
            return isSingleCollection() ? docChunks + caseCount : docChunks;
        }
    }
}
