package com.atp.rag.cli;

import com.atp.rag.assistant.AtpAssistant;
import com.atp.rag.config.RagConfig;
import com.atp.rag.model.ModelFactory;
import com.atp.rag.model.TeiScoringModel;
import com.atp.rag.retrieve.AtpQueryRouter;
import com.atp.rag.retrieve.AtpRetriever;
import com.atp.rag.retrieve.RetrievedItem;
import com.atp.rag.retrieve.XPathLintChannel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.scoring.ScoringModel;
import io.qdrant.client.QdrantClient;

import java.util.Arrays;
import java.util.List;

/**
 * 非交互的端到端跑批 —— 覆盖评估集的四类用例各一到两条。
 *
 * <p>存在的理由有两个：{@link Main} 是交互式的，没法在验证脚本里跑；
 * 而且演示前跑一遍能确认整条链路是活的，不必现场手敲。
 *
 * <p>这<b>不是评估</b>。它不算指标、不比对 golden，只是把链路走通并把结果打出来看。
 * 真正的评估是 M4。
 *
 * <pre>mvn -q compile exec:java -Dexec.mainClass=com.atp.rag.cli.DemoRun</pre>
 */
public final class DemoRun {

    /** 每条都对应评估集里的一类，出问题时能立刻看出是哪一类坏了。 */
    private static final List<String> QUERIES = Arrays.asList(
            "点击按钮之前应该用哪种等待策略",                       // A 类：知识问答
            "//div[3]/span[@id=\"ext-gen1234\"] 这样写有问题吗",   // 关键词型 + lint 通道
            "帮我找几个购物车相关的案例参考",                       // B 类：案例检索
            "ATP 支持 App 自动化吗",                              // D 类：应拒答（功能不存在）
            "ASSERT_JSON 这个 action 怎么用",                     // D 类：应拒答（action 不存在）
            "ログイン失敗時のテストケースはありますか");             // 日文提问

    private DemoRun() {
    }

    public static void main(String[] args) {
        RagConfig config = RagConfig.full();
        System.out.println("配置：" + config.describe());

        // 没配 LLM key 时降级成只跑检索，而不是直接报错退出。
        // 检索层本来就不需要 LLM —— M4 的检索指标同样不需要，
        // 这个降级路径顺带也证明了那一点
        boolean generationAvailable = !com.atp.rag.config.Env.get("LLM_API_KEY", "").isEmpty();
        if (!generationAvailable) {
            System.out.println("⚠️ 未配置 LLM_API_KEY，本次只跑检索，不做生成。"
                    + "填好 .env 里的 LLM_API_KEY 后重跑可看完整问答。");
        }

        EmbeddingModel embeddingModel = ModelFactory.embeddingModel();
        ChatLanguageModel chatModel = generationAvailable ? ModelFactory.chatModel() : null;
        QdrantClient client = ModelFactory.qdrantClient();

        ScoringModel scoringModel = null;
        if (config.rerankEnabled()) {
            TeiScoringModel tei = new TeiScoringModel();
            System.out.println("rerank 自检：区分度 "
                    + String.format("%.0f", tei.selfCheck()) + " 倍 ✓");
            scoringModel = tei;
        }

        // router 传 chatModel；为 null 时 AtpQueryRouter 退回纯规则路由
        AtpRetriever retriever = new AtpRetriever(config, embeddingModel, scoringModel,
                client, new AtpQueryRouter(chatModel));
        AtpAssistant assistant = new AtpAssistant(config, retriever, chatModel);

        int invalidCitationCount = 0;
        try {
            for (String query : QUERIES) {
                System.out.println();
                System.out.println("═══════════════════════════════════════════");
                System.out.println("Q: " + query);

                long startedAt = System.currentTimeMillis();
                com.atp.rag.retrieve.RetrievalResult retrieval = generationAvailable
                        ? null : assistant.retrieveOnly(query);
                AtpAssistant.Answer answer = generationAvailable ? assistant.ask(query) : null;
                if (answer != null) {
                    retrieval = answer.retrieval();
                }
                long elapsed = System.currentTimeMillis() - startedAt;

                System.out.println("路由 " + retrieval.intent()
                        + "　候选 " + retrieval.candidates().size()
                        + " → 采用 " + retrieval.topItems().size()
                        + (retrieval.rerankApplied() ? "（精排）" : "")
                        + "　" + elapsed + "ms");

                if (!retrieval.lintFindings().isEmpty()) {
                    System.out.println("lint 命中 "
                            + XPathLintChannel.standardCodes(retrieval.lintFindings()));
                    System.out.println(XPathLintChannel.summarize(retrieval.lintFindings()));
                }

                int index = 1;
                for (RetrievedItem item : retrieval.topItems()) {
                    System.out.println(String.format("  [%d] %.4f%s  %s%s",
                            index++, item.finalScore(),
                            item.reranked() ? "（向量第 " + item.vectorRank() + " 名）" : "",
                            item.citationLabel(),
                            item.hasViolation() ? "  ⚠️ " + item.violationCodes() : ""));
                }

                if (answer == null) {
                    continue;
                }
                System.out.println("--- 回答 ---");
                System.out.println(answer.text());
                System.out.println("--- 引用 " + answer.citedIndices()
                        + (answer.refused() ? "　[已拒答]" : "")
                        + (answer.citationsAreValid() ? "" : "　⚠️ 越界引用 "
                                + answer.invalidCitations())
                        + " ---");

                if (!answer.citationsAreValid()) {
                    invalidCitationCount++;
                }
            }
        } finally {
            client.close();
        }

        System.out.println();
        System.out.println("═══════════════════════════════════════════");
        if (invalidCitationCount == 0) {
            System.out.println("全部问题回答完毕，没有出现越界引用。");
        } else {
            // 引用了不存在的编号 = 纯规则可测的幻觉信号，不该被静默放过
            System.out.println("⚠️ 有 " + invalidCitationCount + " 个回答引用了不存在的来源编号");
            System.exit(1);
        }
    }
}
