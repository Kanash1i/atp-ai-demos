package com.atp.rag.ingest;

import com.atp.rag.config.RagConfig;
import com.atp.rag.model.ModelFactory;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.qdrant.client.QdrantClient;

import java.util.ArrayList;
import java.util.List;

/**
 * 入库入口。
 *
 * <p>默认把消融实验需要的<b>全部</b> collection 一次建好：
 *
 * <pre>
 * mvn -q compile exec:java -Dexec.mainClass=com.atp.rag.ingest.IngestMain
 * mvn -q compile exec:java -Dexec.mainClass=com.atp.rag.ingest.IngestMain -Dexec.args=full
 * </pre>
 *
 * <p>为什么一次全建：消融表要跑 6 组配置，如果每组临跑临灌，
 * 一轮评估里会混进 embedding 服务的波动和入库耗时。数据先备齐，评估阶段就只剩检索本身。
 */
public final class IngestMain {

    private IngestMain() {
    }

    public static void main(String[] args) {
        List<RagConfig> configs = resolveConfigs(args);

        EmbeddingModel embeddingModel = ModelFactory.embeddingModel();
        QdrantClient client = ModelFactory.qdrantClient();

        List<String> failures = new ArrayList<String>();
        try {
            for (RagConfig config : configs) {
                System.out.println();
                System.out.println("=== " + config.describe() + " ===");
                long startedAt = System.currentTimeMillis();
                try {
                    CorpusIngestor.Result result = new CorpusIngestor(config, embeddingModel, client)
                            .ingestAll();
                    report(result, System.currentTimeMillis() - startedAt, failures);
                } catch (RuntimeException e) {
                    System.out.println("  ❌ " + e.getMessage());
                    failures.add(config.describe() + " — " + e.getMessage());
                }
            }
        } finally {
            client.close();
        }

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("=== 入库完成，" + configs.size() + " 组配置全部就绪 ===");
        } else {
            System.out.println("=== 失败 " + failures.size() + " 组 ===");
            for (String f : failures) {
                System.out.println("  - " + f);
            }
            System.exit(1);
        }
    }

    /**
     * 消融表需要的三组数据。
     *
     * <p>第 4~6 行（rerank / query 改写 / 拒答约束）只影响检索与生成，不改变向量，
     * 所以复用第 3 行的 collection，不必单独入库。
     * 第 7 行换 embedding 模型，那时向量空间变了必须重灌，留到 M5。
     */
    private static List<RagConfig> resolveConfigs(String[] args) {
        List<RagConfig> configs = new ArrayList<RagConfig>();

        if (args.length > 0 && "full".equalsIgnoreCase(args[0])) {
            configs.add(RagConfig.full());
            return configs;
        }

        // 第 1 行 baseline：固定切分 + 单 collection
        configs.add(RagConfig.baseline());
        // 第 2 行：只把切分策略换成标题路径，其余不变 —— 单变量对照
        configs.add(RagConfig.baseline().toBuilder()
                .chunkStrategy(RagConfig.ChunkStrategy.HEADING_PATH)
                .build());
        // 第 3 行及以后：标题路径 + 双 collection
        configs.add(RagConfig.full());
        return configs;
    }

    private static void report(CorpusIngestor.Result result, long elapsedMs, List<String> failures) {
        if (result.isSingleCollection()) {
            System.out.println("  collection " + result.docsCollection
                    + "：文档 " + result.docChunks + " chunk + 案例 " + result.caseCount
                    + " 条 = " + result.docsPoints + " 点");
        } else {
            System.out.println("  collection " + result.docsCollection
                    + "：" + result.docChunks + " chunk（实际 " + result.docsPoints + " 点）");
            System.out.println("  collection " + result.casesCollection
                    + "：" + result.caseCount + " 条（实际 " + result.casesPoints + " 点）");
        }
        System.out.println("  耗时 " + elapsedMs + "ms");

        // 写进去的条数和 Qdrant 数出来的点数必须一致。
        // 对不上说明有静默丢失 —— 这类问题在检索阶段只会表现为「某些内容莫名召回不到」
        long expected = result.expectedTotalPoints();
        long actual = result.docsPoints;
        if (expected != actual) {
            String message = "点数不符：期望 " + expected + "，实际 " + actual;
            System.out.println("  ❌ " + message);
            failures.add(result.docsCollection + " — " + message);
            return;
        }
        if (!result.isSingleCollection() && result.casesPoints != result.caseCount) {
            String message = "案例点数不符：期望 " + result.caseCount + "，实际 " + result.casesPoints;
            System.out.println("  ❌ " + message);
            failures.add(result.casesCollection + " — " + message);
            return;
        }
        System.out.println("  ✅ 点数核对通过");
    }
}
