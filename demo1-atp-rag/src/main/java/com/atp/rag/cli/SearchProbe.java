package com.atp.rag.cli;

import com.atp.rag.config.RagConfig;
import com.atp.rag.model.ModelFactory;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import io.qdrant.client.QdrantClient;

import java.util.Arrays;
import java.util.List;

/**
 * 检索探针 —— 把同一个 query 打到不同配置的 collection 上，肉眼对比召回。
 *
 * <p><b>这不是评估。</b> 真正的评估在 M4：40 条标注好的评估集、Recall@5 / MRR@10 / nDCG@10，
 * 一条命令跑完全部消融配置。那才是能写进消融表的数字。
 *
 * <p>这个探针存在的意义是<b>开发期的快速反馈</b>：改完切分策略想立刻知道「大概有没有变好」，
 * 不必等整套评估跑完。看几条 query 的 top3 就够形成判断了。
 *
 * <p>⚠️ 别把这里看到的现象当结论 —— 4 条 query 的样本量什么也证明不了。
 *
 * <pre>mvn -q compile exec:java -Dexec.mainClass=com.atp.rag.cli.SearchProbe</pre>
 */
public final class SearchProbe {

    private static final int TOP_K = 3;

    /** 覆盖评估集的几类用例，便于快速看出哪一类出了问题。 */
    private static final List<String> QUERIES = Arrays.asList(
            // A 类知识问答，同时顺带验证跨语言 ——
            // 中文提问，而《待機戦略規約》全文是日文，能命中就说明 bge-m3 的跨语言检索是通的
            "为什么规范禁止使用 SLEEP",
            "点击按钮之前应该用哪种等待策略",                // A 类：考察 STD-005
            "有没有涉及文件上传的案例",                    // B 类：案例检索，应召回 UPLOAD 相关
            "XPathの記述で禁止されている書き方は？");        // 日文提问

    private SearchProbe() {
    }

    public static void main(String[] args) {
        RagConfig baseline = RagConfig.baseline();
        RagConfig headingPath = baseline.toBuilder()
                .chunkStrategy(RagConfig.ChunkStrategy.HEADING_PATH)
                .build();

        EmbeddingModel embeddingModel = ModelFactory.embeddingModel();
        QdrantClient client = ModelFactory.qdrantClient();

        try {
            System.out.println("对比 " + baseline.docsCollection()
                    + "（固定切分） vs " + headingPath.docsCollection() + "（标题路径前缀）");
            System.out.println("两者都是单 collection，唯一变量是切分策略。");

            for (String query : QUERIES) {
                System.out.println();
                System.out.println("─────────────────────────────────────────");
                System.out.println("Q: " + query);
                Embedding vector = embeddingModel.embed(query).content();
                printMatches("  [固定切分]  ", client, baseline.docsCollection(), vector);
                printMatches("  [标题路径]  ", client, headingPath.docsCollection(), vector);
            }
        } finally {
            client.close();
        }
    }

    private static void printMatches(String label, QdrantClient client,
                                     String collection, Embedding vector) {
        List<EmbeddingMatch<TextSegment>> matches = QdrantEmbeddingStore.builder()
                .client(client)
                .collectionName(collection)
                .build()
                .search(EmbeddingSearchRequest.builder()
                        .queryEmbedding(vector)
                        .maxResults(TOP_K)
                        .build())
                .matches();

        System.out.println(label + collection);
        int rank = 1;
        for (EmbeddingMatch<TextSegment> match : matches) {
            TextSegment segment = match.embedded();
            String kind = segment.metadata().getString("kind");
            // 文档看 anchor（定位到哪一节），案例看 case_code
            String identity = "case".equals(kind)
                    ? segment.metadata().getString("case_code") + " " + segment.metadata().getString("title")
                    : segment.metadata().getString("anchor");
            System.out.println(String.format("      %d. %.4f  %s", rank++, match.score(), identity));
        }
    }
}
