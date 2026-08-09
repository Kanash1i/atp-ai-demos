package com.atp.rag.assistant;

import com.atp.rag.config.RagConfig;
import com.atp.rag.retrieve.AtpRetriever;
import com.atp.rag.retrieve.RetrievalResult;
import com.atp.rag.retrieve.RetrievedItem;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 问答装配：检索 → 拼 prompt → 生成 → 解析引用。
 *
 * <p>没有用 langchain4j 的 {@code AiServices}。原因是 {@code AiServices} 把整条 RAG 链路
 * 包成了一个返回 String 的方法，而这个 demo 需要的恰恰是<b>被它藏起来的东西</b>：
 * 召回了哪些片段、路由走了哪条、rerank 改变了什么位次、模型引用的编号是否真的存在。
 * 没有这些就写不出消融表，也没法算引用准确率。
 *
 * <p>换句话说，这里放弃的是几行样板代码，换来的是可观测性。
 */
public final class AtpAssistant {

    /** 匹配回答里的 {@code [1]} {@code [2,3]} {@code [1][2]} 等引用标注。 */
    private static final Pattern CITATION = Pattern.compile("\\[(\\d+(?:\\s*[,，]\\s*\\d+)*)]");

    private final RagConfig config;
    private final AtpRetriever retriever;
    private final ChatLanguageModel chatModel;

    public AtpAssistant(RagConfig config, AtpRetriever retriever, ChatLanguageModel chatModel) {
        this.config = config;
        this.retriever = retriever;
        this.chatModel = chatModel;
    }

    public Answer ask(String query) {
        RetrievalResult retrieval = retriever.retrieve(query);

        String systemPrompt = PromptTemplates.systemPrompt(config.refusalPromptEnabled());
        String userPrompt = PromptTemplates.userPrompt(
                query, retrieval.topItems(), retrieval.lintFindings());

        String text = chatModel.generate(
                new SystemMessage(systemPrompt), new UserMessage(userPrompt)).content().text();

        return new Answer(query, text, retrieval);
    }

    /** 只跑检索，不生成 —— M4 的检索指标不需要 LLM，也不该为它烧 token。 */
    public RetrievalResult retrieveOnly(String query) {
        return retriever.retrieve(query);
    }

    /** 一次问答的完整结果。 */
    public static final class Answer {

        private final String query;
        private final String text;
        private final RetrievalResult retrieval;
        private final List<Integer> citedIndices;

        Answer(String query, String text, RetrievalResult retrieval) {
            this.query = query;
            this.text = text;
            this.retrieval = retrieval;
            this.citedIndices = parseCitations(text);
        }

        public String query() {
            return query;
        }

        public String text() {
            return text;
        }

        public RetrievalResult retrieval() {
            return retrieval;
        }

        /** 回答里出现过的引用编号（1 起），已去重且保持出现顺序。 */
        public List<Integer> citedIndices() {
            return citedIndices;
        }

        /**
         * 是否触发了拒答。
         *
         * <p>纯规则判定 —— 只看有没有那个标记。这是 D 类用例的评估依据，
         * 用 LLM-as-judge 去判断「这算不算拒答」又慢又不稳，40 条的规模上不值当。
         */
        public boolean refused() {
            return text.contains(PromptTemplates.REFUSAL_MARKER);
        }

        /**
         * 引用是否都对得上召回结果。
         *
         * <p>模型引用了 {@code [7]} 但只给了 5 条资料，说明它在编 —— 这是
         * <b>纯规则可测</b>的幻觉信号，比 faithfulness 那种 LLM 评分可靠得多，也便宜得多。
         */
        public boolean citationsAreValid() {
            int available = retrieval.topItems().size();
            for (int index : citedIndices) {
                if (index < 1 || index > available) {
                    return false;
                }
            }
            return true;
        }

        /** 越界的引用编号，用于 CLI 提示与评估细分。 */
        public List<Integer> invalidCitations() {
            int available = retrieval.topItems().size();
            List<Integer> invalid = new ArrayList<Integer>();
            for (int index : citedIndices) {
                if (index < 1 || index > available) {
                    invalid.add(index);
                }
            }
            return invalid;
        }

        /** 被引用到的那几条召回结果，按引用顺序。 */
        public List<RetrievedItem> citedItems() {
            List<RetrievedItem> items = new ArrayList<RetrievedItem>();
            List<RetrievedItem> top = retrieval.topItems();
            for (int index : citedIndices) {
                if (index >= 1 && index <= top.size()) {
                    items.add(top.get(index - 1));
                }
            }
            return items;
        }

        /** 回答里是否提醒了违规案例别照抄 —— 召回含违规案例时才有意义。 */
        public boolean warnedAboutViolation() {
            boolean hasViolatingCase = false;
            for (RetrievedItem item : retrieval.topItems()) {
                if (item.hasViolation()) {
                    hasViolatingCase = true;
                    break;
                }
            }
            if (!hasViolatingCase) {
                return true;    // 没有违规案例，无需提醒
            }
            return text.contains("STD-") || text.contains("违规") || text.contains("照抄")
                    || text.contains("違反") || text.contains("規約");
        }
    }

    private static List<Integer> parseCitations(String text) {
        Set<Integer> indices = new LinkedHashSet<Integer>();
        Matcher matcher = CITATION.matcher(text);
        while (matcher.find()) {
            // [1,2] 这种一次标多个来源的写法要拆开
            for (String part : matcher.group(1).split("[,，]")) {
                try {
                    indices.add(Integer.parseInt(part.trim()));
                } catch (NumberFormatException ignored) {
                    // 正则已经限定是数字，走不到这里；真走到了也不该让整次回答失败
                }
            }
        }
        return new ArrayList<Integer>(indices);
    }
}
