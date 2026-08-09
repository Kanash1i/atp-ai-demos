package com.atp.rag.task;

import com.atp.rag.assistant.AtpAssistant;
import com.atp.rag.config.RagConfig;
import com.atp.rag.retrieve.RetrievalResult;
import com.atp.rag.retrieve.RetrievedItem;
import com.atp.rag.retrieve.RetrieverFactory;
import com.atp.rag.retrieve.XPathLintChannel;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 非交互跑批 —— 覆盖评估集的四类用例各一到两条。
 *
 * <p>存在的理由：{@link CliTask} 是交互式的没法在验证脚本里跑；
 * 而且演示前跑一遍能确认整条链路是活的，不必现场手敲。
 *
 * <p>这<b>不是评估</b>。不算指标、不比对 golden，只是把链路走通并打出结果。
 *
 * <pre>mvn spring-boot:run -Dspring-boot.run.arguments=--atp.task=demo</pre>
 */
@Component
@ConditionalOnProperty(name = "atp.task", havingValue = "demo")
public class DemoTask implements ApplicationRunner {

    /**
     * 每条对应评估集里的一类，出问题时能立刻看出是哪一类坏了。
     *
     * <p>D 类刻意放了三条，它们落在一条<b>光谱</b>的三个位置上（见 DECISIONS.md D-012）：
     * <ul>
     *   <li>「支持 App 吗」—— FAQ 明写不支持，<b>有依据的否定</b>。
     *       该正常回答并引用，不输出拒答标记。实测行为稳定</li>
     *   <li>「ASSERT_JSON」—— <b>边界地带</b>。语料声明了 action 集合封闭
     *       （「不在这张表里的 action 就是不存在」），算不算「明确否定的依据」，
     *       模型判断<b>不稳定</b> —— 实测两轮一轮拒答一轮不拒答</li>
     *   <li>「执行器线程池」—— 语料<b>完全没覆盖</b>，真正该拒答的。实测行为稳定</li>
     * </ul>
     *
     * <p>⚠️ 中间那条对 M4 是个警告：<b>评估集的 D 类必须选光谱两端，避开边界地带</b>，
     * 否则拒答率会带上不可复现的波动，而那种波动看起来像是「优化有效/无效」。
     */
    private static final List<String> QUERIES = Arrays.asList(
            "点击按钮之前应该用哪种等待策略",                       // A 类：知识问答
            "//div[3]/span[@id=\"ext-gen1234\"] 这样写有问题吗",   // 关键词型 + lint 通道
            "帮我找几个购物车相关的案例参考",                       // B 类：案例检索
            "ATP 支持 App 自动化吗",                              // D 类：有依据的否定
            "ASSERT_JSON 这个 action 怎么用",                     // D 类：有依据的否定（action 集合封闭）
            "执行器的线程池大小怎么配，默认几个并发",                // D 类：真正无依据 → 应拒答
            "ログイン失敗時のテストケースはありますか");             // 日文提问

    private final RetrieverFactory retrieverFactory;

    public DemoTask(RetrieverFactory retrieverFactory) {
        this.retrieverFactory = retrieverFactory;
    }

    @Override
    public void run(ApplicationArguments args) {
        RagConfig config = RagConfig.from(retrieverFactory.properties());
        System.out.println("配置：" + config.describe());

        boolean generationAvailable = retrieverFactory.isGenerationAvailable();
        if (!generationAvailable) {
            // 检索链路本来就不需要 LLM，缺 key 时降级而不是报错退出
            System.out.println("⚠️ 未配置 atp.llm.api-key，本次只跑检索，不做生成。");
        }

        AtpAssistant assistant = new AtpAssistant(config,
                retrieverFactory.create(config), retrieverFactory.chatModelOrNull());

        int invalidCitationCount = 0;
        for (String query : QUERIES) {
            System.out.println();
            System.out.println("═══════════════════════════════════════════");
            System.out.println("Q: " + query);

            long startedAt = System.currentTimeMillis();
            AtpAssistant.Answer answer = generationAvailable ? assistant.ask(query) : null;
            RetrievalResult retrieval = answer != null
                    ? answer.retrieval() : assistant.retrieveOnly(query);
            long elapsed = System.currentTimeMillis() - startedAt;

            System.out.println("路由 " + retrieval.intent()
                    + (retrieval.routedByRule() ? "（规则）" : "（LLM）")
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

        System.out.println();
        System.out.println("═══════════════════════════════════════════");
        if (invalidCitationCount == 0) {
            System.out.println("全部问题回答完毕，没有出现越界引用。");
        } else {
            // 引用了不存在的编号 = 纯规则可测的幻觉信号，不该被静默放过
            throw new IllegalStateException("有 " + invalidCitationCount
                    + " 个回答引用了不存在的来源编号");
        }
    }
}
