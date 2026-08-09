package com.atp.rag.task;

import com.atp.rag.config.AtpProperties;
import com.atp.rag.model.TeiScoringModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.scoring.ScoringModel;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 环境自检（原 M0 的 LinkageSpike）。
 *
 * <p>四项检查，每一项都对应一个<b>会静默失败</b>的地方。进 M1 之前必须全绿，
 * 服务重启之后也应该先跑这个。
 *
 * <p>比原来的独立 main 版本少了一步：<b>不再需要自己验证「运行时是不是 JDK 8」</b> ——
 * Spring Boot 2.7 在 Java 9+ 上照样能跑，所以那条检查仍然有意义，保留。
 *
 * <pre>mvn spring-boot:run -Dspring-boot.run.arguments=--atp.task=spike</pre>
 */
@Component
@ConditionalOnProperty(name = "atp.task", havingValue = "spike")
public class SpikeTask implements ApplicationRunner {

    private static final String SPIKE_COLLECTION = "spike_linkage_check";
    private static final double MIN_SCORE_RATIO = 100.0;

    private final AtpProperties props;
    private final EmbeddingModel embeddingModel;
    private final QdrantClient qdrantClient;
    private final ObjectProvider<ScoringModel> scoringModelProvider;

    public SpikeTask(AtpProperties props, EmbeddingModel embeddingModel,
                     QdrantClient qdrantClient, ObjectProvider<ScoringModel> scoringModelProvider) {
        this.props = props;
        this.embeddingModel = embeddingModel;
        this.qdrantClient = qdrantClient;
        this.scoringModelProvider = scoringModelProvider;
    }

    @Override
    public void run(ApplicationArguments args) {
        System.out.println("=== 环境自检 ===\n");
        List<String> failures = new ArrayList<String>();

        check("0. 运行时环境", failures, new Step() {
            public void run() {
                checkRuntime();
            }
        });
        check("1. TEI embedding (bge-m3)", failures, new Step() {
            public void run() {
                checkEmbedding();
            }
        });
        check("2. Qdrant gRPC 读写", failures, new Step() {
            public void run() {
                checkQdrant();
            }
        });
        check("3. TEI rerank 打分方向", failures, new Step() {
            public void run() {
                checkRerank();
            }
        });

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("=== 全部通过，链路可用 ===");
        } else {
            System.out.println("=== 失败 " + failures.size() + " 项 ===");
            for (String f : failures) {
                System.out.println("  - " + f);
            }
            throw new IllegalStateException("环境自检未通过");
        }
    }

    private void checkRuntime() {
        String version = System.getProperty("java.version");
        System.out.println("   java.version = " + version
                + " (" + System.getProperty("java.vendor") + ")");
        // 编译期 target 1.8 只保证语法，真正跑在哪个 JVM 上得看运行时。
        // Spring Boot 2.7 在 Java 9+ 上也能跑，所以这条检查依然必要 ——
        // 这个 demo 的前提就是「在 Java 8 上落地」
        if (!version.startsWith("1.8")) {
            throw new IllegalStateException("运行时不是 JDK 8（实际 " + version
                    + "）。这个 demo 的前提是 Java 8：source \"$HOME/.sdkman/bin/sdkman-init.sh\" && sdk env");
        }
    }

    private void checkEmbedding() {
        // 中日双语各测一条：语料是中日混排的，只测中文的话日文侧的问题要到评估时才暴露
        int zh = embeddingModel.embed("XPath 定位器编写规范").content().dimension();
        int ja = embeddingModel.embed("ログイン画面のテストケース").content().dimension();
        System.out.println("   中文 → " + zh + " 维，日文 → " + ja + " 维");

        int expected = props.getEmbedding().getDimension();
        if (zh != expected || ja != expected) {
            throw new IllegalStateException("维度不是 " + expected
                    + "，与 Qdrant collection 维度不一致，入库会失败");
        }
    }

    private void checkQdrant() {
        int dim = props.getEmbedding().getDimension();
        System.out.println("   gRPC " + props.getQdrant().getHost()
                + ":" + props.getQdrant().getGrpcPort()
                + "（版本兼容性已在 bean 构造时校验过）");
        try {
            dropQuietly();
            qdrantClient.createCollectionAsync(SPIKE_COLLECTION, VectorParams.newBuilder()
                    .setSize(dim).setDistance(Distance.Cosine).build()).get();

            QdrantEmbeddingStore store = QdrantEmbeddingStore.builder()
                    .client(qdrantClient).collectionName(SPIKE_COLLECTION).build();

            // 用确定性的假向量，把 gRPC 通道本身和 embedding 服务的成败拆开
            store.add(unitVector(dim, 0), TextSegment.from("第一条 spike 文本"));
            store.add(unitVector(dim, 1), TextSegment.from("第二条 spike 文本"));

            List<EmbeddingMatch<TextSegment>> matches = store.search(
                    EmbeddingSearchRequest.builder()
                            .queryEmbedding(unitVector(dim, 0)).maxResults(2).build()).matches();

            if (matches.isEmpty()) {
                throw new IllegalStateException("写入成功但检索为空");
            }
            EmbeddingMatch<TextSegment> top = matches.get(0);
            System.out.println("   写 2 条 → 命中 " + matches.size()
                    + " 条，top1 score=" + String.format("%.4f", top.score()));
            if (!"第一条 spike 文本".equals(top.embedded().text())) {
                throw new IllegalStateException("top1 不是预期那条，检查距离度量是否为 Cosine");
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Qdrant gRPC 链路失败", e);
        } finally {
            dropQuietly();
        }
    }

    /** 先查存在再删 —— 直接删不存在的 collection，qdrant client 会自己打一条 ERROR 日志。 */
    private void dropQuietly() {
        try {
            if (qdrantClient.collectionExistsAsync(SPIKE_COLLECTION).get()) {
                qdrantClient.deleteCollectionAsync(SPIKE_COLLECTION).get();
            }
        } catch (Exception ignored) {
            // 清理失败不该让自检失败
        }
    }

    private static Embedding unitVector(int dim, int axis) {
        float[] v = new float[dim];
        v[axis] = 1.0f;
        return Embedding.from(v);
    }

    private void checkRerank() {
        ScoringModel scoringModel = scoringModelProvider.getIfAvailable();
        if (scoringModel == null) {
            System.out.println("   rerank 已关闭（atp.rerank.enabled=false），跳过");
            return;
        }
        double ratio = ((TeiScoringModel) scoringModel).selfCheck();
        System.out.println("   区分度 = " + String.format("%.0f", ratio)
                + " 倍（基线约 4 个数量级）");
        if (ratio < MIN_SCORE_RATIO) {
            throw new IllegalStateException("rerank 区分度不足（" + ratio + " 倍）。"
                    + "宁可设 atp.rerank.enabled=false 并在消融表里标注缺失，"
                    + "也不要拿一个坏掉的 rerank 去跑评估");
        }
    }

    private interface Step {
        void run();
    }

    private static void check(String name, List<String> failures, Step step) {
        System.out.println("[ " + name + " ]");
        long startedAt = System.currentTimeMillis();
        try {
            step.run();
            System.out.println("   ✅ 通过 (" + (System.currentTimeMillis() - startedAt) + "ms)\n");
        } catch (RuntimeException e) {
            System.out.println("   ❌ 失败: " + e.getMessage());
            if (e.getCause() != null) {
                System.out.println("      cause: " + e.getCause());
            }
            System.out.println();
            failures.add(name + " — " + e.getMessage());
        }
    }
}
