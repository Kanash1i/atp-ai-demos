package com.atp.rag.ingest;

import com.atp.rag.config.AtpProperties;
import com.atp.rag.config.RagConfig;
import com.atp.rag.ingest.image.AltTextImageDescriber;
import com.atp.rag.ingest.image.ImageDescriber;
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
    /** 图片转文字描述。默认降级实现，Spring 装配时会注入配置好的那个 */
    private final ImageDescriber imageDescriber;

    public CorpusIngestor(RagConfig config, EmbeddingModel embeddingModel,
                          QdrantClient client, AtpProperties props) {
        this(config, embeddingModel, client, props, new AltTextImageDescriber());
    }

    public CorpusIngestor(RagConfig config, EmbeddingModel embeddingModel,
                          QdrantClient client, AtpProperties props,
                          ImageDescriber imageDescriber) {
        this.config = config;
        this.embeddingModel = embeddingModel;
        this.client = client;
        this.corpusRoot = Paths.get(props.getCorpus().getDir());
        this.dimension = props.getEmbedding().getDimension();
        this.imageDescriber = imageDescriber;
    }

    /**
     * 灌一组语料的完整流程。
     *
     * <pre>
     *   1. 算出这组配置对应的 collection 名
     *   2. 删掉重建（全量，不做增量）
     *   3. 灌文档 —— 要切分
     *   4. 灌案例 —— 不切分
     *   5. 数一遍 Qdrant 里实际有多少点
     *   6. 真的查一次，确认检索可用
     * </pre>
     *
     * @return 各 collection 的写入条数与实际点数，交给调用方核对是否一致
     */
    public Result ingestAll() {
        // collection 名不是配置里写死的，是**从当前这组开关算出来的**：
        //   {前缀}_{docs|cases|all}_{fixed|heading}[_{embedding标签}]
        // 所以三组消融配置天然落在三套不同的 collection 上，互不覆盖
        String docsCollection = config.docsCollection();
        String casesCollection = config.casesCollection();

        log.info("入库配置 {}", config.describe());

        // 删掉重建，不做增量 upsert。
        // 原因：切分策略一改，旧 chunk 的边界就不对了，但它们还躺在库里照样会被召回，
        // 表现为「某几个 query 莫名其妙地差」，在评估里极难定位（见 DECISIONS.md D-008）
        QdrantCollections.recreate(client, docsCollection, dimension);

        // ⚠️ SINGLE 模式下这两个名字**是同一个**（都叫 atp_all_xxx）。
        // 不加这个判断的话，第二次 recreate 会把上一行刚建好的库又删一遍 ——
        // 然后文档灌进去、案例灌进去，最后只剩案例
        if (!docsCollection.equals(casesCollection)) {
            QdrantCollections.recreate(client, casesCollection, dimension);
        }

        // 文档和案例走**两条不同的处理路径**，这是整个 M2 的核心设计：
        //   文档要切分（一篇 markdown → 多个 chunk），案例整条不切
        int docChunks = ingestDocuments(docsCollection);
        int caseCount = ingestCases(casesCollection);

        // 回头数一遍 Qdrant 里到底有多少点。
        // 这个数字要和上面两个返回值对上 —— 对不上就说明写入过程中悄悄丢了东西
        long docsPoints = QdrantCollections.countPoints(client, docsCollection);
        long casesPoints = docsCollection.equals(casesCollection)
                // SINGLE 模式下两者是同一个 collection，没必要再查一次
                ? docsPoints : QdrantCollections.countPoints(client, casesCollection);

        // 点数对不代表检索可用，所以真的查一次。理由见下面这个方法的注释
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
        // 这里是「读」，上面灌数据是「写」—— 走的是同一个 EmbeddingStore 抽象，
        // 但读路径会触发 langchain4j 内部的向量反序列化，那正是 D-002 坏掉的地方
        EmbeddingStore<TextSegment> store = QdrantEmbeddingStore.builder()
                .client(client)
                .collectionName(collection)
                .build();

        // 用一个语料里必定有答案的问题。选它不是随便挑的：
        // SLEEP 禁令在中文《等待策略》和日文《待機戦略規約》里都有，
        // 所以无论这个 collection 装的是哪批文档，都该召回到东西
        Embedding query = embeddingModel.embed("为什么规范禁止使用 SLEEP").content();
        List<EmbeddingMatch<TextSegment>> matches = store.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(query)
                .maxResults(3)
                .build()).matches();

        // 情况一：一条都没召回。说明索引没建起来，或者写入根本没落地
        if (matches.isEmpty()) {
            throw new IllegalStateException(collection + " 写入成功但检索为空");
        }

        EmbeddingMatch<TextSegment> top = matches.get(0);
        // 情况二：召回了点，但正文是空的。说明 payload 的 text 字段没写对 ——
        // 这种情况下检索「能用」，但喂给模型的上下文是空白，模型只能编
        if (top.embedded() == null || top.embedded().text().trim().isEmpty()) {
            throw new IllegalStateException(collection + " 召回了点，但正文是空的 —— payload 没写对");
        }
        log.info("检索冒烟 {}：top1 score={} anchor={}", collection,
                String.format("%.4f", top.score()),
                top.embedded().metadata().getString("anchor"));
    }

    // ── 文档 ──────────────────────────────────────────────────

    /**
     * 灌文档：15 篇 markdown → 一堆 chunk。
     *
     * <pre>
     *   遍历 manual/ 和 standards/ 两个目录
     *     └─ 每个 .md：解析出标题层级
     *          └─ 按当前切分策略切成 chunk
     *               └─ 给每个 chunk 挂上 metadata，攒进列表
     *   最后统一算向量 + 写库
     * </pre>
     *
     * @return 切出来的 chunk 总数（78 或 184，取决于切分策略）
     */
    private int ingestDocuments(String collection) {
        // splitter 持有 config，所以它知道该用 FIXED 还是 HEADING_PATH。
        // 同一批文档，两种策略切出来的块数差一倍多（78 vs 184）
        HeadingPathSplitter splitter = new HeadingPathSplitter(config, imageDescriber);

        // 先全部攒进内存再统一写。15 篇文档最多 184 块，量很小；
        // 攒起来的好处是能一次性批量算向量，比逐篇调 TEI 少很多次往返
        List<TextSegment> segments = new ArrayList<TextSegment>();

        // 两个子目录分开遍历，而不是递归扫 docs/ ——
        // 因为 group（manual / standards）本身要作为 metadata 存进去，
        // 检索时可以据此区分「手册」和「规范」
        for (String group : Arrays.asList("manual", "standards")) {
            Path dir = corpusRoot.resolve("docs").resolve(group);

            for (File file : listFiles(dir, ".md")) {
                // sourceId 形如 manual/04-定位器指南.md。
                // 它是这篇文档的稳定标识，评估集的 golden_ids 以它为前缀
                String sourceId = group + "/" + file.getName();

                // 解析 markdown：抽出一级标题当文档名，其余标题切成带层级路径的小节
                MarkdownDocument doc = MarkdownDocument.parse(file.toPath(), sourceId);

                // 按策略切块。HEADING_PATH 下每块带 [文档 > 章 > 节] 前缀，FIXED 下不带
                for (Chunk chunk : splitter.split(doc)) {
                    // metadata 就是 Qdrant 的 payload —— 检索时能拿回来的所有结构化信息
                    Metadata metadata = new Metadata();

                    // kind 区分文档和案例。SINGLE 模式下两者混在一个 collection 里，
                    // 检索结果要靠这个字段才知道召回的是哪一类
                    metadata.put("kind", "doc");
                    metadata.put("source_id", sourceId);
                    metadata.put("doc_title", doc.title());
                    metadata.put("doc_group", group);

                    // 标题路径拼成一行，形如「常见错误 > 绝对路径」。
                    // CLI 展示引用来源时用它，让用户知道这段话出自哪一节
                    metadata.put("heading_path", String.join(" > ", chunk.headingPath()));

                    // 评估集的 golden_ids 比对的就是这个。它定位到「哪一节」而不是「第几块」，
                    // 所以换切分策略时评估集不用重写
                    metadata.put("anchor", chunk.anchor());

                    // 这一块在原文里的顺序号，调试时用来还原上下文
                    metadata.put("ordinal", chunk.ordinal());

                    // ⚠️ 存进 payload 的是 rawText（给人看的），送去算向量的是 embedText（带标题前缀）。
                    // 两者的分离正是这一行消融的全部内容
                    metadata.put("embed_text", chunk.embedText());

                    // 父子切块时，rawText 已经是父块正文了（见 Chunk.withParent）。
                    // 这里额外存父块标识，供检索层去重 —— 同一章节下的多个子块常常一起被召回，
                    // 但父块正文只该交给模型一次
                    if (chunk.hasParent()) {
                        metadata.put("parent_anchor", chunk.parentAnchor());
                    }

                    // TextSegment 的正文用 rawText —— 它会成为 payload 里的展示文本，
                    // 也是最终喂给 LLM 的上下文。带前缀的那份只用来算向量
                    segments.add(TextSegment.from(chunk.rawText(), metadata));
                }
            }
        }

        // true = 算向量时改用 metadata 里的 embed_text，而不是 segment 自己的正文
        embedAndStore(collection, segments, true);
        log.info("文档 → {}：{} 个 chunk", collection, segments.size());
        return segments.size();
    }

    // ── 案例 ──────────────────────────────────────────────────

    /**
     * 灌案例：80 个 JSON → 80 个点，<b>一条案例一个点，不切分</b>。
     *
     * <p>和文档最大的区别就是不切。原因是步骤之间有顺序依赖 ——
     * 切碎之后单看「点击提交按钮」这一步没有任何检索价值，
     * 而且用户问「找个下单流程的案例参考」时，要的是完整案例而不是某个步骤。
     *
     * @return 案例条数（固定 80）
     */
    private int ingestCases(String collection) {
        List<TextSegment> segments = new ArrayList<TextSegment>();

        for (File file : listFiles(corpusRoot.resolve("cases"), ".json")) {
            // 解析 JSON。这一步只是读进来，不做校验 ——
            // 语料的完整性由 CorpusIntegrityTest 在构建期保证，运行时不重复检查
            AtpCase atpCase = AtpCase.parse(file);

            // metadata 里有检索要用的全部结构化字段：
            // module_code / priority / has_violation / violation_codes / actions_used …
            // 其中违规标记是「这条能参考但别照抄」这类回答的唯一依据
            Metadata metadata = atpCase.toMetadata();

            // 把结构化 JSON 渲染成一段人话。
            // 直接 embed 原始 JSON 的话，向量会被字段名和大括号这些结构噪音主导 ——
            // 80 条案例的 JSON 骨架本来就一模一样，那样算出来的向量彼此都很像
            String rendered = atpCase.renderForEmbedding();

            // 案例的 embed 文本和展示文本是同一份，所以这里存的就是 rendered 本身。
            // 存这个字段只是为了和文档路径保持一致（embedAndStore 统一从这里取）
            metadata.put("embed_text", rendered);

            // 案例的展示文本和 embed 文本相同 —— 渲染后的步骤序列本来就是给人看的
            segments.add(TextSegment.from(rendered, metadata));
        }

        // false = 直接用 segment 自己的正文算向量。
        // 文档那边传的是 true，因为它的向量要带标题前缀而展示文本不带
        embedAndStore(collection, segments, false);
        log.info("案例 → {}：{} 条", collection, segments.size());
        return segments.size();
    }

    // ── 公共 ──────────────────────────────────────────────────

    /**
     * 算向量 + 写库，分批进行。文档和案例最后都汇到这个方法。
     *
     * <pre>
     *   按 32 条一批切开
     *     └─ 挑出「要拿去算向量的文本」（可能不是 segment 自己的正文）
     *          └─ 一次请求算一批向量
     *               └─ 校验返回条数
     *                    └─ 连同 metadata 一起写进 Qdrant
     * </pre>
     *
     * @param useEmbedTextMetadata 为 true 时用 metadata 里的 {@code embed_text} 去算向量，
     *                             而不是 segment 自己的文本。文档需要这个 —— 它的向量要带标题路径前缀，
     *                             但展示时不该带
     */
    private void embedAndStore(String collection, List<TextSegment> segments, boolean useEmbedTextMetadata) {
        // 一条都没有，多半是语料目录找错了（相对路径依赖工作目录）。
        // 与其灌一个空 collection 然后在检索时纳闷为什么召回不到，不如现在就失败
        if (segments.isEmpty()) {
            throw new IllegalStateException("没有可入库的内容，检查 " + corpusRoot.toAbsolutePath());
        }

        // EmbeddingStore 是 langchain4j 对向量库的标准抽象。
        // 换成 pgvector 只需换这个实现类，下面的代码一行不动
        EmbeddingStore<TextSegment> store = QdrantEmbeddingStore.builder()
                .client(client)
                .collectionName(collection)
                .build();

        // 分批：TEI 的 max_client_batch_size 是 32，超了会返回 422。
        // 这个数字写在服务的 /info 里，不是猜的
        for (int from = 0; from < segments.size(); from += EMBED_BATCH) {
            int to = Math.min(from + EMBED_BATCH, segments.size());
            List<TextSegment> batch = segments.subList(from, to);

            // 挑出真正要送去算向量的文本。
            // 文档：取 metadata 里带标题前缀的那份；案例：就是 segment 自己。
            // 注意这里构造的是**临时** TextSegment，只为了传给 embedAll，不会被存进库
            List<TextSegment> toEmbed = new ArrayList<TextSegment>(batch.size());
            for (TextSegment segment : batch) {
                toEmbed.add(useEmbedTextMetadata
                        ? TextSegment.from(segment.metadata().getString("embed_text"))
                        : segment);
            }

            // 一次请求算一批向量。批量比逐条快得多 —— TEI 内部有动态 batching，
            // 而且省掉 32 次网络往返
            Response<List<Embedding>> response = embeddingModel.embedAll(toEmbed);
            List<Embedding> embeddings = response.content();

            // 返回条数必须和请求条数一致。不一致就说明向量和文本对不上号了 ——
            // 下一行的 addAll 是**按下标配对**的，错位之后每个 chunk 都会挂上别人的向量。
            // 那种错误不会报错，只会让检索结果毫无道理
            if (embeddings.size() != batch.size()) {
                throw new IllegalStateException("embedding 返回 " + embeddings.size()
                        + " 条，与请求的 " + batch.size() + " 条不符");
            }

            // 写库。注意存进去的是 batch（带 metadata、正文是 rawText），
            // 而不是上面那个临时的 toEmbed —— 向量来自 embedText，展示文本来自 rawText，
            // 这两份在这一行汇合
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
