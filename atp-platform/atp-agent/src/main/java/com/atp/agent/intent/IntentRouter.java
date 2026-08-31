package com.atp.agent.intent;

import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 三层意图路由，命中即短路。
 *
 * <pre>
 *   L1 规则   零成本、零延迟   只放**不可能误判**的说法
 *   L2 向量   一次 embedding   自然表达的近似匹配
 *   L3 模型   一次 LLM 调用    兜底，什么都能分
 * </pre>
 *
 * <h3>⚠️ 为什么 L1 的规则要写得很保守</h3>
 *
 * 三层是**短路**的：L1 一旦命中，后面两层根本不跑。所以 L1 误判的代价不是"少一层保险"，
 * 而是**直接把请求送错 agent**。宁可不命中让 L2 去判，也不要为了提高命中率放宽规则。
 *
 * <p>典型陷阱：「写案例」和「案例怎么写才合规」都含"案例"和"写"，
 * 但前者要动数据库、后者只需查文档。按关键词匹配必然搞混，这种就该交给 L2/L3。
 *
 * <h3>降级</h3>
 *
 * L2 依赖 TEI（在另一台机器上），起不来或超时都不该让路由失效 ——
 * 直接跳到 L3。L3 再失败就返回 OTHER，由上层如实告诉用户，**不猜**。
 */
@Slf4j
@Component
public class IntentRouter {

    /** L2 判定阈值。低于它说明"哪个都不太像"，交给 L3 而不是硬选一个最高的 */
    private static final double L2_THRESHOLD = 0.62;

    /** L2/L3 的等待上限。路由是对话的第一步，卡在这里用户会以为服务挂了 */
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    @Qualifier("fastModel")
    private Model fastModel;

    /** L2 的样例向量。启动时算一次，之后只读 */
    private final List<Sample> samples = new ArrayList<>();

    private volatile boolean l2Ready = false;

    // ── L1：规则 ──────────────────────────────────────────────
    // 只收「说了这个就不可能是别的意思」的表达。含糊的一律不放。
    private static final Map<IntentCategory, List<Pattern>> RULES = new LinkedHashMap<>();

    static {
        // ⚠️ 中间允许有内容（"写个【用了 SLEEP 的】案例"），但用负向先行断言排除求教句式：
        //    "写...案例" 后面若紧跟"怎么/如何/该"，那是在问写法而不是让你写。
        //    实测教训：原来要求"案例"紧跟量词，导致「帮我写个用了 SLEEP 的案例」漏到 L2，
        //    而 L2 被句子里的 SLEEP 拽向规范样例，判成了问答。
        RULES.put(IntentCategory.CASE_AUTHORING, compile(
                "(帮我|给我|请)?(写|生成|新建|创建)(一)?(条|个|份)?[^，。？?！!]{0,24}案例(?!.{0,8}(怎么|如何|该))",
                "把.{0,20}变成案例"));
        RULES.put(IntentCategory.KNOWLEDGE_QA, compile(
                "STD-?\\d{3}",
                "(规范|标准|规定).{0,10}(是什么|怎么|要求|说明|允许|禁止)",
                "(允许|可以|能不能|要不要).{0,12}(吗|么)\\s*[?？]?$"));
        // ⚠️ 刻意不收「查一下 / 看一下」—— 它们根本不表意图：
        //    「看一下这条案例该怎么写」是问写法，「看一下订单模块的案例」才是查询。
        //    实测这条正则误判过前者。分不清的交给 L2，L1 只留真正无歧义的。
        RULES.put(IntentCategory.CASE_QUERY, compile(
                "(有哪些|列出|列一?下).{0,12}案例",
                "案例.{0,8}(列表|清单|有多少|几条)"));
        RULES.put(IntentCategory.EXECUTION, compile(
                "(派发|执行|跑一?下|运行).{0,12}(案例|批次|用例)",
                "(执行|运行).{0,8}(结果|状态|进度|失败|成功)",
                "(录像|回放|视频).{0,8}(在哪|看|查)"));
        RULES.put(IntentCategory.APPROVAL, compile(
                "(审批|审核).{0,8}(中心|列表|状态|流程|一?下)",
                "(批准|通过|驳回|退回).{0,10}(申请|审批|请求)"));
    }

    // ── L2：样例 ──────────────────────────────────────────────
    // 覆盖同一意图的**不同说法**，而不是同一说法的变体 —— 后者对向量匹配没有增益。
    private static final Map<IntentCategory, List<String>> SAMPLES = new LinkedHashMap<>();

    static {
        SAMPLES.put(IntentCategory.CASE_AUTHORING, List.of(
                "帮我做一个验证登录失败提示的测试",
                "购物车满减的场景需要覆盖一下",
                "我想测下单流程里库存不足会怎么样",
                "补一条商品搜索为空的用例"));
        SAMPLES.put(IntentCategory.KNOWLEDGE_QA, List.of(
                "定位器应该怎么写才符合要求",
                "等待策略有哪几种，分别什么时候用",
                "为什么不能用 SLEEP",
                "案例编号的命名规则是什么",
                // ⚠️ 上面四条全是**泛问**。补两条**指代式提问** ——
                //    实测「看一下这条案例该怎么写」被判给了 EXECUTION，
                //    因为那边有「失败的那条是在第几步挂的」，而这边没有任何带"这条/这一步"的样例。
                //    样例要覆盖同一意图的不同**说法形态**，不只是不同话题。
                "这条案例这么写符合要求吗",
                "这一步该用哪种等待策略"));
        SAMPLES.put(IntentCategory.CASE_QUERY, List.of(
                "订单模块下面都有什么用例",
                "P0 的案例现在有多少条",
                "找一下跟支付相关的那几条",
                "哪些案例还是草稿状态"));
        SAMPLES.put(IntentCategory.EXECUTION, List.of(
                "把这批用例跑一遍",
                "刚才那个批次跑完了没",
                "失败的那条是在第几步挂的",
                "我想看看执行的录像"));
        SAMPLES.put(IntentCategory.APPROVAL, List.of(
                "还有多少单子等着我处理",
                "这个变更申请我同意了",
                "帮我把那个例外申请退回去",
                "谁提交的这个发布请求"));
        SAMPLES.put(IntentCategory.OTHER, List.of(
                "你好",
                "你能做什么",
                "今天天气怎么样",
                "谢谢"));
    }

    private static List<Pattern> compile(String... regexes) {
        List<Pattern> out = new ArrayList<>(regexes.length);
        for (String r : regexes) {
            out.add(Pattern.compile(r));
        }
        return out;
    }

    /**
     * 预热 L2 的样例向量。
     *
     * <p>⚠️ 不能让它拖慢启动，也不能让它失败就把应用带崩 ——
     * TEI 在另一台机器上，它没起来的时候平台照样该能用（只是路由降级到 L3）。
     */
    @PostConstruct
    void warmUp() {
        Thread.ofVirtual().start(() -> {
            try {
                SAMPLES.forEach((intent, texts) -> texts.forEach(t -> {
                    double[] v = embed(t);
                    if (v != null) {
                        samples.add(new Sample(intent, t, v));
                    }
                }));
                l2Ready = !samples.isEmpty();
                log.info("[ROUTE] L2 样例向量就绪：{} 条", samples.size());
            } catch (Exception e) {
                log.warn("[ROUTE] L2 预热失败，路由将降级到 L3：{}", e.getMessage());
            }
        });
    }

    /** 判定用户这句话该交给谁 */
    public RouteResult route(String message) {
        String msg = message == null ? "" : message.trim();
        if (msg.isEmpty()) {
            return RouteResult.l1(IntentCategory.OTHER, "空消息");
        }

        RouteResult r = byRule(msg);
        if (r == null) {
            r = byVector(msg);
        }
        if (r == null) {
            r = byModel(msg);
        }
        log.info("[ROUTE] {} → {} ({}{})", abbreviate(msg), r.intent(), r.layer(),
                r.score() < 0 ? "" : " %.3f".formatted(r.score()));
        return r;
    }

    private RouteResult byRule(String msg) {
        for (Map.Entry<IntentCategory, List<Pattern>> e : RULES.entrySet()) {
            for (Pattern p : e.getValue()) {
                if (p.matcher(msg).find()) {
                    return RouteResult.l1(e.getKey(), p.pattern());
                }
            }
        }
        return null;
    }

    private RouteResult byVector(String msg) {
        if (!l2Ready) {
            return null;
        }
        double[] q = embed(msg);
        if (q == null) {
            return null;
        }
        Sample best = null;
        double bestScore = -1;
        for (Sample s : samples) {
            double score = cosine(q, s.vector());
            if (score > bestScore) {
                bestScore = score;
                best = s;
            }
        }
        // ⚠️ 低于阈值不要硬选最高的那个 —— "都不太像"和"像这个"是两回事
        if (best == null || bestScore < L2_THRESHOLD) {
            return null;
        }
        return RouteResult.l2(best.intent(), bestScore, best.text());
    }

    private RouteResult byModel(String msg) {
        try {
            List<Msg> prompt = List.of(
                    Msg.builder().role(MsgRole.SYSTEM)
                            .content(TextBlock.builder().text(L3_PROMPT).build()).build(),
                    Msg.builder().role(MsgRole.USER)
                            .content(TextBlock.builder().text(msg).build()).build());

            ChatResponse resp = fastModel.stream(prompt, null, null)
                    .timeout(TIMEOUT).blockLast();
            String raw = resp == null ? "" : text(resp);
            return RouteResult.l3(IntentCategory.parse(raw), raw.trim());

        } catch (Exception e) {
            // 分不出来就承认分不出来。猜一个送错 agent，比说"我不确定"糟得多
            log.warn("[ROUTE] L3 失败：{}", e.getMessage());
            return new RouteResult(IntentCategory.OTHER, "L3", -1, "分类失败：" + e.getMessage());
        }
    }

    private static final String L3_PROMPT = """
            你是一个意图分类器。判断用户这句话属于下面哪一类，**只输出类别名，不要任何解释**。

            CASE_AUTHORING  想让你写一条新的测试案例
            KNOWLEDGE_QA    问测试规范、写法要求、术语解释 —— 只需查资料，不改任何东西
            CASE_QUERY      想找已经存在的案例
            EXECUTION       派发执行、问执行状态、要录像
            APPROVAL        审批相关
            OTHER           打招呼、闲聊、以及以上都不是的

            关键区分：「写一条登录案例」是 CASE_AUTHORING，
            「登录案例该怎么写才合规」是 KNOWLEDGE_QA —— 前者要产出案例，后者只要答案。
            """;

    private String text(ChatResponse resp) {
        if (resp.getContent() == null) {
            return "";
        }
        return resp.getContent().stream()
                .filter(TextBlock.class::isInstance).map(TextBlock.class::cast)
                .map(TextBlock::getText).filter(java.util.Objects::nonNull)
                .reduce("", String::concat);
    }

    private double[] embed(String text) {
        try {
            return embeddingModel.embed(TextBlock.builder().text(text).build())
                    .timeout(TIMEOUT).block();
        } catch (Exception e) {
            log.debug("[ROUTE] embedding 失败：{}", e.getMessage());
            return null;
        }
    }

    /** 余弦相似度。TEI 返回的已经是归一化向量，但不假设 —— 除数算全 */
    private double cosine(double[] a, double[] b) {
        if (a.length != b.length) {
            return -1;
        }
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return (na == 0 || nb == 0) ? -1 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private String abbreviate(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "…";
    }

    private record Sample(IntentCategory intent, String text, double[] vector) {
    }
}
