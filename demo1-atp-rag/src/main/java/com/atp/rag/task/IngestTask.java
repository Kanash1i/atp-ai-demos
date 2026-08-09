package com.atp.rag.task;

import com.atp.rag.config.AtpProperties;
import com.atp.rag.config.RagConfig;
import com.atp.rag.ingest.CorpusIngestor;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.qdrant.client.QdrantClient;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 语料入库。
 *
 * <p>默认把消融实验需要的<b>全部</b> collection 一次建好，而不是每组配置临跑临灌 ——
 * 否则一轮评估里会混进 embedding 服务的波动和入库耗时。
 *
 * <pre>
 * mvn spring-boot:run -Dspring-boot.run.arguments=--atp.task=ingest
 * mvn spring-boot:run -Dspring-boot.run.arguments="--atp.task=ingest --atp.ingest.only-default=true"
 * </pre>
 */
@Component
@ConditionalOnProperty(name = "atp.task", havingValue = "ingest")
public class IngestTask implements ApplicationRunner {

    private final AtpProperties props;
    private final EmbeddingModel embeddingModel;
    private final QdrantClient qdrantClient;

    public IngestTask(AtpProperties props, EmbeddingModel embeddingModel, QdrantClient qdrantClient) {
        this.props = props;
        this.embeddingModel = embeddingModel;
        this.qdrantClient = qdrantClient;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean onlyDefault = args.containsOption("atp.ingest.only-default");
        List<RagConfig> configs = onlyDefault
                ? java.util.Collections.singletonList(RagConfig.from(props))
                : ablationConfigs();

        List<String> failures = new ArrayList<String>();
        for (RagConfig config : configs) {
            System.out.println();
            System.out.println("=== " + config.describe() + " ===");
            long startedAt = System.currentTimeMillis();
            try {
                CorpusIngestor.Result result = new CorpusIngestor(
                        config, embeddingModel, qdrantClient, props).ingestAll();
                report(result, System.currentTimeMillis() - startedAt, failures);
            } catch (RuntimeException e) {
                System.out.println("  ❌ " + e.getMessage());
                failures.add(config.describe() + " — " + e.getMessage());
            }
        }

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("=== 入库完成，" + configs.size() + " 组配置全部就绪 ===");
        } else {
            System.out.println("=== 失败 " + failures.size() + " 组 ===");
            for (String f : failures) {
                System.out.println("  - " + f);
            }
            throw new IllegalStateException("入库未全部成功");
        }
    }

    /**
     * 消融表需要的三组数据。
     *
     * <p>第 4~6 行（rerank / query 改写 / 拒答约束）只影响检索与生成、不改变向量，
     * 所以复用第 3 行的 collection，不必单独入库。
     * 第 7 行换 embedding 模型，向量空间变了必须重灌 —— 那时设 {@code EMBEDDING_TAG}。
     */
    private List<RagConfig> ablationConfigs() {
        List<RagConfig> configs = new ArrayList<RagConfig>();
        // 第 1 行 baseline：固定切分 + 单 collection
        configs.add(RagConfig.baseline(props));
        // 第 2 行：只把切分策略换成标题路径，其余不变 —— 单变量对照
        configs.add(RagConfig.baseline(props).toBuilder()
                .chunkStrategy(RagConfig.ChunkStrategy.HEADING_PATH)
                .build());
        // 第 3 行及以后：标题路径 + 双 collection
        configs.add(RagConfig.from(props));
        return configs;
    }

    private void report(CorpusIngestor.Result result, long elapsedMs, List<String> failures) {
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

        // 写入条数与 Qdrant 数出来的点数必须一致。对不上说明有静默丢失 ——
        // 这类问题在检索阶段只会表现为「某些内容莫名召回不到」
        long expected = result.expectedTotalPoints();
        if (expected != result.docsPoints) {
            String message = "点数不符：期望 " + expected + "，实际 " + result.docsPoints;
            System.out.println("  ❌ " + message);
            failures.add(result.docsCollection + " — " + message);
            return;
        }
        if (!result.isSingleCollection() && result.casesPoints != result.caseCount) {
            String message = "案例点数不符：期望 " + result.caseCount
                    + "，实际 " + result.casesPoints;
            System.out.println("  ❌ " + message);
            failures.add(result.casesCollection + " — " + message);
            return;
        }
        System.out.println("  ✅ 点数核对通过");
    }
}
