package com.atp.rag.cli;

import com.atp.rag.assistant.AtpAssistant;
import com.atp.rag.config.Env;
import com.atp.rag.config.RagConfig;
import com.atp.rag.model.ModelFactory;
import com.atp.rag.model.TeiScoringModel;
import com.atp.rag.retrieve.AtpQueryRouter;
import com.atp.rag.retrieve.AtpRetriever;
import com.atp.rag.retrieve.RetrievalResult;
import com.atp.rag.retrieve.RetrievedItem;
import com.atp.rag.retrieve.XPathLintChannel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.scoring.ScoringModel;
import io.qdrant.client.QdrantClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 交互式 CLI —— 面试演示用。
 *
 * <p>刻意把<b>召回详情默认展示出来</b>：一个只输出答案的 demo 无法说明检索做对了什么，
 * 而这个项目的全部重点恰恰在检索。演示时先让面试官看到召回了哪几条、
 * rerank 把哪条从第几名提到了第几名，再看最终回答，叙事才立得住。
 *
 * <pre>mvn -q compile exec:java -Dexec.mainClass=com.atp.rag.cli.Main</pre>
 */
public final class Main {

    private static final String PROMPT = "\n[36m你问>[0m ";

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        RagConfig config = RagConfig.full();

        System.out.println("ATP 知识助手  ——  " + config.describe());
        System.out.println("collection：" + config.docsCollection() + " / " + config.casesCollection());

        EmbeddingModel embeddingModel = ModelFactory.embeddingModel();
        ChatLanguageModel chatModel = ModelFactory.chatModel();
        QdrantClient client = ModelFactory.qdrantClient();

        ScoringModel scoringModel = null;
        if (config.rerankEnabled()) {
            TeiScoringModel tei = new TeiScoringModel();
            // rerank 坏了不会报错，只会让检索悄悄变差。启动时先验一次，
            // 别等演示到一半才发现精排是乱的
            double ratio = tei.selfCheck();
            System.out.println("rerank 自检：区分度 " + String.format("%.0f", ratio) + " 倍 ✓");
            scoringModel = tei;
        } else {
            System.out.println("⚠️ rerank 已关闭（RERANK_ENABLED=false）");
        }

        AtpRetriever retriever = new AtpRetriever(config, embeddingModel, scoringModel,
                client, new AtpQueryRouter(chatModel));
        AtpAssistant assistant = new AtpAssistant(config, retriever, chatModel);

        System.out.println("\n输入问题开始提问。命令：:detail 切换召回详情，:quit 退出");
        printExamples();

        boolean showDetail = true;
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        try {
            while (true) {
                System.out.print(PROMPT);
                System.out.flush();
                String line = reader.readLine();
                if (line == null || ":quit".equals(line.trim()) || ":q".equals(line.trim())) {
                    break;
                }
                String query = line.trim();
                if (query.isEmpty()) {
                    continue;
                }
                if (":detail".equals(query)) {
                    showDetail = !showDetail;
                    System.out.println("召回详情：" + (showDetail ? "开" : "关"));
                    continue;
                }
                handle(assistant, query, showDetail);
            }
        } finally {
            client.close();
        }
        System.out.println("再见。");
    }

    private static void handle(AtpAssistant assistant, String query, boolean showDetail) {
        long startedAt = System.currentTimeMillis();
        AtpAssistant.Answer answer;
        try {
            answer = assistant.ask(query);
        } catch (RuntimeException e) {
            System.out.println("[31m出错了：" + e.getMessage() + "[0m");
            return;
        }
        long elapsed = System.currentTimeMillis() - startedAt;

        if (showDetail) {
            printRetrievalDetail(answer.retrieval());
        }

        System.out.println("\n[32m助手>[0m " + answer.text());

        printCitations(answer);
        System.out.println("\n[90m耗时 " + elapsed + "ms"
                + (answer.refused() ? "　· 已拒答" : "")
                + "[0m");
    }

    private static void printRetrievalDetail(RetrievalResult retrieval) {
        System.out.println("\n[90m── 召回详情 ──[0m");
        System.out.println("[90m路由：" + retrieval.intent()
                + "　候选 " + retrieval.candidates().size()
                + " 条 → 采用 " + retrieval.topItems().size() + " 条"
                + (retrieval.rerankApplied() ? "（已精排）" : "（未精排）") + "[0m");

        if (!retrieval.lintFindings().isEmpty()) {
            System.out.println("[33m" + XPathLintChannel.summarize(retrieval.lintFindings())
                    + "[0m");
        }

        int index = 1;
        for (RetrievedItem item : retrieval.topItems()) {
            StringBuilder line = new StringBuilder("[90m  [")
                    .append(index++).append("] ");
            if (item.reranked()) {
                line.append(String.format("%.4f", item.rerankScore()))
                        // 向量检索里排第几 —— 这一列是 rerank 价值的直接体现
                        .append("（向量第 ").append(item.vectorRank()).append(" 名，")
                        .append(String.format("%.4f", item.vectorScore())).append("）");
            } else {
                line.append(String.format("%.4f", item.vectorScore()));
            }
            line.append("  ").append(item.citationLabel());
            if (item.hasViolation()) {
                line.append("[0m[33m  ⚠️ 违规 ").append(item.violationCodes());
            }
            System.out.println(line.append("[0m"));
        }
    }

    private static void printCitations(AtpAssistant.Answer answer) {
        if (answer.citedIndices().isEmpty()) {
            return;
        }
        System.out.println("\n[90m引用来源：[0m");
        for (RetrievedItem item : answer.citedItems()) {
            System.out.println("[90m  · " + item.citationLabel() + "[0m");
        }
        if (!answer.citationsAreValid()) {
            // 模型引用了不存在的编号 —— 纯规则可测的幻觉信号
            System.out.println("[31m  ⚠️ 回答引用了不存在的来源编号 "
                    + answer.invalidCitations() + "[0m");
        }
    }

    private static void printExamples() {
        System.out.println("\n[90m可以试试：");
        System.out.println("  点击按钮之前应该用哪种等待策略");
        System.out.println("  //div[3]/span[@id=\"ext-gen1234\"] 这样写有问题吗");
        System.out.println("  帮我找几个购物车相关的案例参考");
        System.out.println("  ATP 支持 App 自动化吗");
        System.out.println("  ログイン失敗時のテストケースはありますか[0m");
    }
}
