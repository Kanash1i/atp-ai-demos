package com.atp.rag.task;

import com.atp.rag.config.AtpProperties;
import com.atp.rag.config.RagConfig;
import com.atp.rag.ingest.CorpusIngestor;
import com.atp.rag.ingest.image.ImageDescriber;
import com.atp.rag.storage.ObjectStorage;
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
    private final ImageDescriber imageDescriber;
    private final ObjectStorage objectStorage;

    public IngestTask(AtpProperties props, EmbeddingModel embeddingModel,
                      QdrantClient qdrantClient, ImageDescriber imageDescriber,
                      ObjectStorage objectStorage) {
        this.props = props;
        this.embeddingModel = embeddingModel;
        this.qdrantClient = qdrantClient;
        this.imageDescriber = imageDescriber;
        this.objectStorage = objectStorage;
    }

    /**
     * 入库任务的主流程。
     *
     * <pre>
     *   决定要灌哪几组配置
     *     └─ 逐组：新建 CorpusIngestor → ingestAll() → 核对点数
     *          └─ 任何一组失败都记下来继续跑，最后汇总
     * </pre>
     */
    @Override
    public void run(ApplicationArguments args) {
        // 命令行带 --atp.ingest.only-default 时只灌「当前配置」这一组。
        // 平时改完切分策略想快速验一下用得上，省掉另外两组的时间
        boolean onlyDefault = args.containsOption("atp.ingest.only-default");

        // 默认灌消融表需要的全部三组；only-default 时退化成一组。
        // RagConfig.from(props) = application.yml 里 atp.rag.* 的当前值
        List<RagConfig> configs = onlyDefault
                ? java.util.Collections.singletonList(RagConfig.from(props))
                : ablationConfigs();

        // 收集失败信息而不是遇错即停：三组之间互相独立，
        // 第一组挂了不该妨碍后两组 —— 一次跑完能看到全部问题，比修一个跑一次快
        List<String> failures = new ArrayList<String>();

        for (RagConfig config : configs) {
            System.out.println();
            // describe() 打印这一组的全部开关，是「当前在灌哪一组」的唯一标识
            System.out.println("=== " + config.describe() + " ===");
            long startedAt = System.currentTimeMillis();
            try {
                // 每组配置都新建一个 CorpusIngestor：它持有 config，
                // 而 config 决定了 collection 名、切分策略这些一组一变的东西。
                // 模型客户端和 Qdrant 连接是复用的（容器管的单例），只有编排对象是一次性的
                CorpusIngestor.Result result = new CorpusIngestor(
                        config, embeddingModel, qdrantClient, props,
                        imageDescriber, objectStorage).ingestAll();

                // 核对写入条数与 Qdrant 实际点数，对不上就往 failures 里记
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
            // 抛异常让进程以非零码退出 —— 入库是后续所有步骤的前提，
            // 半成功的状态不能被当成成功糊弄过去
            throw new IllegalStateException("入库未全部成功");
        }
    }

    /**
     * 消融表要用到的<b>数据</b>有几组。
     *
     * <h4>为什么只有 3 组，而消融表有 7 行</h4>
     *
     * 因为只有<b>改变了向量或 chunk 边界</b>的配置才需要单独灌一份数据：
     *
     * <table border="1">
     *   <tr><th>消融表</th><th>变的是什么</th><th>要重灌吗</th></tr>
     *   <tr><td>第 1 行 baseline</td><td>固定切分 + 单库</td><td>✅ 第 1 组</td></tr>
     *   <tr><td>第 2 行 +标题路径</td><td><b>chunk 边界变了</b></td><td>✅ 第 2 组</td></tr>
     *   <tr><td>第 3 行 +双库路由</td><td><b>数据分到两个 collection</b></td><td>✅ 第 3 组</td></tr>
     *   <tr><td>第 4 行 +rerank</td><td>只改检索时的排序</td><td>❌ 复用第 3 组</td></tr>
     *   <tr><td>第 5 行 +query 改写</td><td>只改查询侧</td><td>❌ 复用第 3 组</td></tr>
     *   <tr><td>第 6 行 +拒答约束</td><td>只改 prompt</td><td>❌ 复用第 3 组</td></tr>
     *   <tr><td>第 7 行 换 embedding</td><td><b>向量空间整个变了</b></td><td>✅ 但要先设 EMBEDDING_TAG，留到 M5</td></tr>
     * </table>
     *
     * <p>三组数据落在三套<b>不同名字</b>的 collection 上（名字由 RagConfig 按配置派生），
     * 所以互不覆盖，可以反复重跑。
     */
    private List<RagConfig> ablationConfigs() {
        List<RagConfig> configs = new ArrayList<RagConfig>();

        // 第 1 组 = 消融表第 1 行：什么优化都不开。
        // baseline() 内部做的事：拿 application.yml 的当前值，然后把
        // 切分改成 FIXED、分库改成 SINGLE、三个检索增强全关
        // → collection 名算出来是 atp_all_fixed
        configs.add(RagConfig.baseline(props));

        // 第 2 组 = 消融表第 2 行：在 baseline 基础上**只**换切分策略。
        // 单变量对照 —— 除了 chunkStrategy，其余开关和第 1 组完全一致，
        // 这样两行数字之差才能归因于「标题路径前缀」这一项
        // → collection 名算出来是 atp_all_heading
        configs.add(RagConfig.baseline(props).toBuilder()
                .chunkStrategy(RagConfig.ChunkStrategy.HEADING_PATH)
                .build());

        // 第 3 组 = 消融表第 3 行及以后：application.yml 里的默认值，
        // 也就是「全部优化都开」（标题路径 + 双库）
        // → collection 名算出来是 atp_docs_heading + atp_cases_heading
        configs.add(RagConfig.from(props));

        return configs;
    }

    /**
     * 打印一组的结果，并<b>核对写入条数与 Qdrant 实际点数是否一致</b>。
     *
     * <p>核对这一步不是走形式。写入是分批发出去的，中间任何一批悄悄失败
     * （网络抖动、维度不匹配被服务端拒收），代码这边看起来仍然是「跑完了」。
     * 而缺几条数据在检索阶段只会表现为「某些内容莫名召回不到」——
     * 那时候没人会想到是入库丢了东西。
     *
     * @param failures 发现不一致就往这里记，由调用方在最后统一汇总并让进程失败
     */
    private void report(CorpusIngestor.Result result, long elapsedMs, List<String> failures) {
        // SINGLE 模式下文档和案例在同一个 collection 里，打印格式不同：
        // 点数 = chunk 数 + 案例数，而 DUAL 模式下两个 collection 各报各的
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
