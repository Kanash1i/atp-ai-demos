package com.atp.rag.retrieve;

import dev.langchain4j.model.chat.ChatLanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 查询路由：判断这个问题该查文档库还是案例库。
 *
 * <h3>为什么不是纯 LLM 路由</h3>
 *
 * langchain4j 有现成的 {@code LanguageModelQueryRouter}，但直接用它有两个问题：
 *
 * <ol>
 *   <li><b>每次提问多一次 LLM 调用</b>，而路由是检索链路的第一步，它的延迟加在所有查询上</li>
 *   <li><b>LLM 会不稳定</b>。同一个问题偶尔分到不同的类，评估时就会看到无法复现的波动</li>
 * </ol>
 *
 * <p>所以这里是<b>规则短路 + LLM 兜底 + 不确定就都查</b>：
 * 明显的信号（「案例」「ケース」「参考」这类词）直接规则判定，不花 LLM 调用；
 * 规则拿不准才问 LLM；LLM 也拿不准就返回 {@link QueryIntent#BOTH}。
 *
 * <h3>路由错了会怎样</h3>
 *
 * 代价是<b>不对称</b>的：多查一个 collection 只多花几十毫秒，
 * 查漏了则是彻底召回不到 —— 后面 rerank 再强也救不回来。
 * 所以所有不确定的情况一律倒向 {@code BOTH}，宁可多查。
 */
public final class AtpQueryRouter {

    private static final Logger log = LoggerFactory.getLogger(AtpQueryRouter.class);

    /** 出现这些词，基本可以确定用户要找的是存量案例。 */
    private static final List<String> CASE_SIGNALS = Arrays.asList(
            "案例", "ケース", "テストケース", "用例", "case",
            "参考", "参照", "借鉴", "类似的", "似たような", "サンプル");

    /**
     * 出现这些词，基本可以确定用户在问规则 / 用法。
     *
     * <p>⚠️ 这里放的是<b>连续子串</b>，别写「支持吗」这种中间会被隔开的组合 ——
     * 「ATP 支持 App 自动化吗」就匹配不上，白白退化成 BOTH。
     * 这类 D 类问题走 BOTH 会召回一堆不相关案例，反而增加模型编造的机会。
     */
    private static final List<String> DOC_SIGNALS = Arrays.asList(
            "怎么写", "怎么用", "如何", "为什么", "为何", "什么是", "区别", "规范", "規約",
            "支持", "能不能", "可以吗", "手册", "文档",
            // 「选哪一个」类问法。实测「点击按钮之前应该用哪种等待策略」漏了这类词之后
            // 落到 LLM 路由，被稳定判成 CASES —— 一个典型知识问答被送去查案例库
            "应该用", "用哪", "哪种", "哪个", "什么时候", "有几种", "有哪些",
            "どうやって", "なぜ", "とは", "違い", "できます", "サポート",
            "どれ", "どの", "いくつ");

    private final ChatLanguageModel chatModel;

    /** @param chatModel 传 null 表示只用规则，不做 LLM 兜底（评估时可用来排除 LLM 波动） */
    public AtpQueryRouter(ChatLanguageModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 路由决策，带来源。
     *
     * <p>记录来源不是为了好看 —— M4 分析路由错误时，「错的是规则还是 LLM」
     * 决定了该去改信号词表还是改 prompt。只返回 intent 的话这两种情况无法区分。
     */
    public static final class Decision {
        private final QueryIntent intent;
        private final boolean byRule;

        Decision(QueryIntent intent, boolean byRule) {
            this.intent = intent;
            this.byRule = byRule;
        }

        public QueryIntent intent() {
            return intent;
        }

        /** true = 信号词规则判定，false = 走了 LLM（含 LLM 失败后的降级）。 */
        public boolean byRule() {
            return byRule;
        }
    }

    public Decision decide(String query) {
        QueryIntent byRule = routeByRule(query);
        if (byRule != null) {
            log.debug("路由（规则）{} → {}", query, byRule);
            return new Decision(byRule, true);
        }
        if (chatModel == null) {
            // 没有 LLM 可用时，宁可都查。这仍然算「非规则判定」——
            // 是因为规则没判出来才落到这里的
            return new Decision(QueryIntent.BOTH, false);
        }
        QueryIntent byLlm = routeByLlm(query);
        log.debug("路由（LLM）{} → {}", query, byLlm);
        return new Decision(byLlm, false);
    }

    /** 只要意图，不关心来源。 */
    public QueryIntent route(String query) {
        return decide(query).intent();
    }

    /**
     * 规则判定。两类信号都命中或都没命中时返回 null，交给 LLM。
     *
     * <p>「有没有涉及文件上传的案例」同时含「案例」和疑问句式，
     * 但案例信号更强 —— 这种明确的情况不必浪费一次 LLM 调用。
     */
    private QueryIntent routeByRule(String query) {
        String lower = query.toLowerCase(Locale.ROOT);
        boolean hasCaseSignal = containsAny(lower, CASE_SIGNALS);
        boolean hasDocSignal = containsAny(lower, DOC_SIGNALS);

        if (hasCaseSignal && !hasDocSignal) {
            return QueryIntent.CASES;
        }
        if (hasDocSignal && !hasCaseSignal) {
            return QueryIntent.DOCS;
        }
        if (hasCaseSignal) {
            // 两类信号都有，例如「购物车的案例怎么写」—— 既要案例也要规范
            return QueryIntent.BOTH;
        }
        return null;    // 都没命中，交给 LLM
    }

    private static boolean containsAny(String text, List<String> signals) {
        for (String signal : signals) {
            if (text.contains(signal)) {
                return true;
            }
        }
        return false;
    }

    private QueryIntent routeByLlm(String query) {
        String prompt = "你是一个检索路由器。判断下面这个问题应该查哪个知识库。\n\n"
                + "DOCS = 平台手册与公司规范（怎么用、规则是什么、为什么这样规定）\n"
                + "CASES = 存量测试案例库（想找已有案例作参考）\n"
                + "BOTH = 两者都需要，或你无法确定\n\n"
                + "只回答 DOCS、CASES、BOTH 三个词之一，不要解释。\n\n"
                + "问题：" + query;
        try {
            String answer = chatModel.generate(prompt).trim().toUpperCase(Locale.ROOT);
            if (answer.contains("CASES")) {
                return QueryIntent.CASES;
            }
            if (answer.contains("DOCS")) {
                return QueryIntent.DOCS;
            }
            return QueryIntent.BOTH;
        } catch (RuntimeException e) {
            // 路由失败不该让整个查询失败 —— 降级成「都查」，用户感知不到差别，只是慢一点
            log.warn("LLM 路由失败，降级为 BOTH：{}", e.getMessage());
            return QueryIntent.BOTH;
        }
    }
}
