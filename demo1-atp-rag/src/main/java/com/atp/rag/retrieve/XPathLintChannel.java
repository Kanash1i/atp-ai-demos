package com.atp.rag.retrieve;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * XPath 规则通道 —— 专治 RAG 的经典弱点：<b>关键词型查询</b>。
 *
 * <h3>问题</h3>
 *
 * 用户贴一段 XPath 问「这样写有问题吗」：
 *
 * <pre>//div[3]/span[@id="ext-gen1234"] 这样写可以吗</pre>
 *
 * 纯向量检索对这种查询召回极差 —— embedding 会把<b>所有 XPath 都算成彼此相似</b>，
 * 而用户真正需要的是「绝对路径禁止」「动态 id 禁止」这两条规范，
 * 那两段文字里可能根本没有出现和用户这段 XPath 相似的字符串。
 *
 * <h3>做法</h3>
 *
 * 检测到 query 里含 XPath 特征时，用<b>纯规则</b>判定它踩了哪几条规范，
 * 再把规范名转成语义化的检索词（「XPath 绝对路径 禁止 规范」）去补充检索。
 *
 * <p>这是交接文档 §3.3(b) 的方案 3。它比方案 2（真正的 hybrid / BM25）便宜得多，
 * 而且对这个具体场景更准 —— 因为违规类型是<b>可枚举</b>的，不需要通用的字面匹配能力。
 *
 * <p>额外的收获是：判定结果本身就能直接回答用户，不必等生成层去总结。
 */
public final class XPathLintChannel {

    /** 判定结果。 */
    public static final class Finding {
        private final String standardCode;
        private final String message;
        private final String searchQuery;

        Finding(String standardCode, String message, String searchQuery) {
            this.standardCode = standardCode;
            this.message = message;
            this.searchQuery = searchQuery;
        }

        public String standardCode() {
            return standardCode;
        }

        /** 直接可读的判定说明，CLI 会原样展示。 */
        public String message() {
            return message;
        }

        /** 转成语义化检索词，用来补一路召回。 */
        public String searchQuery() {
            return searchQuery;
        }
    }

    /**
     * 疑似 XPath 的片段。
     *
     * <p>只认两种开头：{@code //}（相对路径，最常见）和 {@code /html}（绝对路径）。
     *
     * <p><b>刻意不认单斜杠开头的一般路径</b> —— 语料里真实存在
     * {@code /testdata/return/defect_screen.png} 这样的上传路径，
     * 把它判成 XPath 的话，用户问「上传路径怎么填」会收到一条自信但荒唐的规范告警。
     * 漏掉 {@code /div/span} 这种写法的代价小得多：它本来也不会命中任何一条违规规则。
     */
    private static final Pattern XPATH_SHAPE = Pattern.compile(
            // (?<![:\w]) 挡住 URL 的 "http://"；
            // // 要求后面跟标识符起始字符，/html 本身已经够特征化不必再要求
            "(?<![:\\w])(//(?=[\\w*@\\[])|/html\\b)");

    /** 组件库自动生成的 id 前缀，与 STD-002 的清单一致。 */
    private static final Pattern DYNAMIC_ID = Pattern.compile(
            "(ext-gen|ext-comp|mat-input-|cdk-overlay-|el-id-|el-popper-|uid-|auto-)\\w*"
                    + "|:r[0-9a-z]+:");

    /** 位置下标 {@code [3]}，但要排除 {@code [last()]} 和 {@code [@attr=…]}。 */
    private static final Pattern POSITIONAL_INDEX = Pattern.compile("\\[\\s*\\d+\\s*\\]");

    /** {@code @class="a b c"} 这种完全匹配。 */
    private static final Pattern EXACT_CLASS = Pattern.compile("@class\\s*=\\s*[\"'][^\"']*[\"']");

    private XPathLintChannel() {
    }

    /** query 里是否包含疑似 XPath 的片段。 */
    public static boolean looksLikeXPathQuery(String query) {
        return query != null && XPATH_SHAPE.matcher(query).find();
    }

    /**
     * 判定 query 里的 XPath 踩了哪些规范。
     *
     * <p>没有 XPath 片段就返回空列表 —— 这个通道不参与普通查询。
     */
    public static List<Finding> analyze(String query) {
        List<Finding> findings = new ArrayList<Finding>();
        if (!looksLikeXPathQuery(query)) {
            return findings;
        }

        // 只在 XPath 片段内部判定，避免把问题描述里的文字算进去。
        // 比如「//div[3] 和第 3 个元素有什么区别」，那个「第 3 个」不该触发索引下标告警
        String xpath = extractXPathFragment(query);

        if (xpath.contains("/html")) {
            findings.add(new Finding("STD-001",
                    "使用了绝对路径（以 /html 开头）。中间任何一层多个元素就会失效，"
                            + "而前端加包裹层是极常见的改动。规范判定为 ERROR，保存会被拒绝。",
                    "XPath 绝对路径 禁止 规范 是正方法"));
        }
        Matcher dynamicId = DYNAMIC_ID.matcher(xpath);
        if (dynamicId.find()) {
            findings.add(new Finding("STD-002",
                    "依赖了自动生成的 id「" + dynamicId.group()
                            + "」。这类 id 按渲染顺序采番，页面上多一个组件就会整体错位。"
                            + "规范判定为 WARN —— 保存得了，但评审会要求在 description 里"
                            + "写明「已确认该 id 稳定」的根据。",
                    "動的 ID 依存 禁止 自動採番 コンポーネント"));
        }
        if (POSITIONAL_INDEX.matcher(xpath).find()) {
            findings.add(new Finding("STD-003",
                    "使用了位置下标。列表顺序会随排序规则、数据变化、分页而改变，"
                            + "今天的第 3 行明天可能是第 5 行。建议改成按内容定位。",
                    "XPath 索引下标 列表 按内容定位 建议"));
        }
        if (EXACT_CLASS.matcher(xpath).find()) {
            findings.add(new Finding("STD-003",
                    "对 class 做了完全匹配。class 属性是多值的且顺序不保证，"
                            + "增删任何一个无关类名都会失效。应改用 contains 部分匹配。",
                    "class 属性 部分一致 contains 完全一致 避免"));
        }
        return findings;
    }

    /**
     * 取出 query 中第一段疑似 XPath 的连续片段。
     *
     * <p>遇空白即停，<b>但引号内的空白除外</b> ——
     * {@code //button[@class="btn btn-primary"]} 里那个空格属于属性值，
     * 在那里截断会把 class 判定规则整个绕过去（截出来的 {@code @class="btn} 引号不闭合，匹配不上）。
     */
    private static String extractXPathFragment(String query) {
        Matcher matcher = XPATH_SHAPE.matcher(query);
        if (!matcher.find()) {
            return "";
        }
        int start = matcher.start();
        int end = start;
        char quote = 0;
        while (end < query.length()) {
            char ch = query.charAt(end);
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                }
            } else if (ch == '"' || ch == '\'') {
                quote = ch;
            } else if (Character.isWhitespace(ch)) {
                break;
            }
            end++;
        }
        return query.substring(start, end);
    }

    /** 判定结果对应的补充检索词，已去重。 */
    public static List<String> supplementaryQueries(List<Finding> findings) {
        Set<String> queries = new LinkedHashSet<String>();
        for (Finding finding : findings) {
            queries.add(finding.searchQuery());
        }
        return new ArrayList<String>(queries);
    }

    /** 命中的规范编号，已去重且保持判定顺序。 */
    public static List<String> standardCodes(List<Finding> findings) {
        Set<String> codes = new LinkedHashSet<String>();
        for (Finding finding : findings) {
            codes.add(finding.standardCode());
        }
        return new ArrayList<String>(codes);
    }

    /** 供 CLI 与 prompt 使用的可读摘要。没有判定结果时返回空串。 */
    public static String summarize(List<Finding> findings) {
        if (findings.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("规则检查发现 ")
                .append(findings.size()).append(" 处问题：\n");
        for (Finding finding : findings) {
            sb.append("  · [").append(finding.standardCode()).append("] ")
                    .append(finding.message()).append('\n');
        }
        return sb.toString().trim();
    }

    /** 已知的规范编号，供测试与文档引用。 */
    public static List<String> knownStandards() {
        return Arrays.asList("STD-001", "STD-002", "STD-003");
    }
}
