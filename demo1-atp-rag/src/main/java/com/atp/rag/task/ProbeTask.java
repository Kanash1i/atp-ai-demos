package com.atp.rag.task;

import com.atp.rag.config.RagConfig;
import com.atp.rag.retrieve.RetrievalResult;
import com.atp.rag.retrieve.RetrievedItem;
import com.atp.rag.retrieve.RetrieverFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 检索探针 —— 把同一个 query 打到不同配置上，肉眼对比召回。
 *
 * <p><b>这不是评估。</b> 真正的评估在 M4：40 条标注好的评估集、Recall@5 / MRR@10 / nDCG@10。
 * 这个探针的用途是<b>开发期的快速反馈</b>：改完切分策略想立刻知道「大概有没有变好」，
 * 不必等整套评估跑完。
 *
 * <p>⚠️ 别把这里看到的现象当结论 —— 几条 query 的样本量什么也证明不了。
 *
 * <pre>mvn spring-boot:run -Dspring-boot.run.arguments=--atp.task=probe</pre>
 */
@Component
@ConditionalOnProperty(name = "atp.task", havingValue = "probe")
public class ProbeTask implements ApplicationRunner {

    private static final int TOP_K = 3;

    private static final List<String> QUERIES = Arrays.asList(
            // A 类知识问答，同时顺带验证跨语言 ——
            // 中文提问，而《待機戦略規約》全文是日文，能命中就说明跨语言检索是通的
            "为什么规范禁止使用 SLEEP",
            "点击按钮之前应该用哪种等待策略",
            "有没有涉及文件上传的案例",
            "XPathの記述で禁止されている書き方は？");

    private final RetrieverFactory retrieverFactory;

    public ProbeTask(RetrieverFactory retrieverFactory) {
        this.retrieverFactory = retrieverFactory;
    }

    @Override
    public void run(ApplicationArguments args) {
        RagConfig baseline = RagConfig.baseline(retrieverFactory.properties());
        RagConfig headingPath = baseline.toBuilder()
                .chunkStrategy(RagConfig.ChunkStrategy.HEADING_PATH)
                .build();

        System.out.println("对比 " + baseline.docsCollection()
                + "（固定切分） vs " + headingPath.docsCollection() + "（标题路径前缀）");
        System.out.println("两者都是单 collection、都不精排，唯一变量是切分策略。");

        for (String query : QUERIES) {
            System.out.println();
            System.out.println("─────────────────────────────────────────");
            System.out.println("Q: " + query);
            print("  [固定切分]  ", baseline, query);
            print("  [标题路径]  ", headingPath, query);
        }
    }

    private void print(String label, RagConfig config, String query) {
        RetrievalResult result = retrieverFactory.create(config).retrieve(query);
        System.out.println(label + config.docsCollection());
        int rank = 1;
        for (RetrievedItem item : result.topItems()) {
            if (rank > TOP_K) {
                break;
            }
            String identity = item.isCase()
                    ? item.segment().metadata().getString("case_code") + " "
                            + item.segment().metadata().getString("title")
                    : item.segment().metadata().getString("anchor");
            System.out.println(String.format("      %d. %.4f  %s",
                    rank++, item.finalScore(), identity));
        }
    }
}
