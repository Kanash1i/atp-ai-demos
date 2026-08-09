package com.atp.rag.assistant;

import com.atp.rag.retrieve.RetrievedItem;
import com.atp.rag.retrieve.XPathLintChannel;

import java.util.List;

/**
 * prompt 组装。
 *
 * <h3>为什么拒答约束值得单独作为一行消融</h3>
 *
 * 新人问「XPath 怎么写」，模型编一个看起来合理但实际定位不到的表达式 ——
 * 这比回答「手册里没写，建议问 XX」<b>危害大得多</b>：
 * 前者会被直接抄进案例，跑挂了还得反过来查半天，最后发现是助手编的。
 *
 * <p>所以 D 类评估用例（应拒答 7 条）专门测这个。而拒答能不能被<b>纯规则检测</b>，
 * 取决于 prompt 有没有要求模型输出一个固定标记 —— 这就是 {@link #REFUSAL_MARKER} 的用途。
 * 靠 LLM-as-judge 去判断「这算不算拒答」又慢又不稳，在 40 条的规模上不值当。
 */
public final class PromptTemplates {

    /**
     * 拒答标记。
     *
     * <p>要求模型在无法回答时<b>原样输出这个字符串</b>，评估侧就能用 {@code contains} 判定拒答，
     * 不需要 LLM 参与。标记选得刻意生僻，避免正常回答里碰巧出现。
     *
     * <p><b>它的语义必须精确到只有一件事</b>：「我没有资料，答不了」。
     * 不包括「答案是否定的」——
     * 「ATP 支持 App 自动化吗」在 FAQ 里明确写着不支持，那是<b>有依据的答案</b>，
     * 该正常回答并引用来源。
     *
     * <p>这个区分不是咬文嚼字。prompt 最初写的是「资料不足<b>或者</b>该功能不支持」，
     * 结果标记同时表达了两件事，于是「拒答率」这个指标变得无法解释 ——
     * 分子里混着「我不知道」和「我知道答案是否」，而后者答对了反而被算成失败。
     * 实测踩到了这个坑，详见 DECISIONS.md D-012。
     */
    public static final String REFUSAL_MARKER = "[资料不足]";

    private static final String BASE_ROLE =
            "你是 ATP 平台的知识助手。ATP 是一个企业内部的 Web UI 自动化测试平台，"
                    + "测试工程师通过界面编写测试案例，不写代码。\n"
                    + "你的使用者多为刚接触平台的新人。";

    private static final String CITATION_RULES =
            "回答规则：\n"
                    + "1. 只依据下面提供的「参考资料」作答。资料里没有的内容，一律不要写。\n"
                    + "2. 每个结论后面标注来源编号，形如 [1]、[2]。可以一句话标多个来源。\n"
                    + "3. 不要编造 XPath、字段名、action 名称或配置项。"
                    + "资料里没出现过的 action 名字就是不存在的。\n"
                    + "4. 回答用提问所用的语言。中文提问就用中文答，日文提问就用日文答，"
                    + "即使参考资料是另一种语言。\n";

    private static final String REFUSAL_RULES =
            "5. 如果参考资料**不足以**回答，必须在回答开头原样输出 " + REFUSAL_MARKER
                    + " 这个标记，然后说明你不知道。\n"
                    + "   宁可说不知道，也不要给一个看似合理的猜测 —— "
                    + "错误的 XPath 会被直接抄进案例，代价远大于一句「我不确定」。\n"
                    + "6. ⚠️ 注意区分：如果资料**明确说明**某功能不存在、不支持、被禁止，"
                    + "那是一个<b>有依据的答案</b>，应当正常回答「不支持」并标注来源编号，"
                    + "**不要**输出 " + REFUSAL_MARKER + "。\n"
                    + "   这个标记只表示「我没有资料所以答不了」，不表示「答案是否定的」。\n"
                    + "7. 如果资料显示某条案例存在规范违规，推荐它时必须提醒："
                    + "可以参考结构，但违规的部分不要照抄。\n";

    private PromptTemplates() {
    }

    /** @param refusalEnabled 对应消融表第 6 行的开关 */
    public static String systemPrompt(boolean refusalEnabled) {
        StringBuilder sb = new StringBuilder(BASE_ROLE).append("\n\n").append(CITATION_RULES);
        if (refusalEnabled) {
            sb.append(REFUSAL_RULES);
        }
        return sb.toString();
    }

    /**
     * 拼参考资料。
     *
     * <p>编号从 1 开始且与展示给用户的引用列表<b>严格一致</b> ——
     * 引用准确率就是靠比对这两者算出来的，编号错位会让指标失去意义。
     */
    public static String userPrompt(String query, List<RetrievedItem> items,
                                   List<XPathLintChannel.Finding> lintFindings) {
        StringBuilder sb = new StringBuilder();

        String lintSummary = XPathLintChannel.summarize(lintFindings);
        if (!lintSummary.isEmpty()) {
            // 规则判定的结论比检索来的资料更可靠，放在最前面，并说明它的来源，
            // 免得模型把它当成又一段需要引用编号的参考资料
            sb.append("【规则检查结果】这部分是平台的静态检查器直接给出的判定，"
                            + "可信度高于下面的检索资料，回答时可直接引用其结论（不需要标注编号）：\n")
                    .append(lintSummary).append("\n\n");
        }

        if (items.isEmpty()) {
            sb.append("【参考资料】（无 —— 检索没有找到相关内容）\n\n");
        } else {
            sb.append("【参考资料】\n");
            int index = 1;
            for (RetrievedItem item : items) {
                sb.append("[").append(index++).append("] ").append(item.citationLabel());
                if (item.hasViolation()) {
                    sb.append("　⚠️ 该案例存在规范违规：").append(item.violationCodes());
                }
                sb.append('\n').append(item.text()).append("\n\n");
            }
        }

        sb.append("【用户问题】\n").append(query);
        return sb.toString();
    }
}
