package com.atp.rag.retrieve;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * lint 通道的测试。
 *
 * <p>重点在<b>误报</b>那几条：这个通道会抢在向量检索之前给出判定，
 * 误报的话用户会收到一条自信但错误的「你违反了 STD-001」。
 * 宁可漏报（退回普通检索），不可误报。
 */
class XPathLintChannelTest {

    @Test
    @DisplayName("识别绝对路径")
    void detectsAbsolutePath() {
        List<XPathLintChannel.Finding> findings =
                XPathLintChannel.analyze("/html/body/div[2]/form/input 这样写可以吗");
        assertTrue(XPathLintChannel.standardCodes(findings).contains("STD-001"),
                "应判定为绝对路径，实际 " + XPathLintChannel.standardCodes(findings));
    }

    @Test
    @DisplayName("识别自动生成的 id")
    void detectsDynamicId() {
        assertTrue(XPathLintChannel.standardCodes(
                XPathLintChannel.analyze("//*[@id=\"ext-gen1234\"] 有问题吗")).contains("STD-002"));
        assertTrue(XPathLintChannel.standardCodes(
                XPathLintChannel.analyze("//div[@id=\"mat-input-7\"]//input")).contains("STD-002"));
    }

    @Test
    @DisplayName("识别位置下标与 class 完全匹配")
    void detectsPositionalIndexAndExactClass() {
        assertTrue(XPathLintChannel.standardCodes(
                XPathLintChannel.analyze("//table//tr[3]/td[2]")).contains("STD-003"));
        assertTrue(XPathLintChannel.standardCodes(
                XPathLintChannel.analyze("//button[@class=\"btn btn-primary\"]")).contains("STD-003"));
    }

    @Test
    @DisplayName("一段 XPath 可以同时命中多条规范")
    void detectsMultipleViolationsAtOnce() {
        List<String> codes = XPathLintChannel.standardCodes(
                XPathLintChannel.analyze("/html/body/div[3]/span[@id=\"el-id-88\"] 这样写有问题吗"));
        assertTrue(codes.contains("STD-001"), codes.toString());
        assertTrue(codes.contains("STD-002"), codes.toString());
        assertTrue(codes.contains("STD-003"), codes.toString());
    }

    @Test
    @DisplayName("合规的 XPath 不产生任何判定")
    void cleanXPathProducesNoFinding() {
        assertTrue(XPathLintChannel.analyze(
                "//form[@name=\"login\"]//input[@data-testid=\"username\"] 这样写可以吗").isEmpty());
        assertTrue(XPathLintChannel.analyze(
                "//button[contains(@class,\"submit\")] 行不行").isEmpty());
    }

    @Test
    @DisplayName("普通提问不触发这个通道")
    void plainQuestionsAreNotTreatedAsXPath() {
        for (String query : new String[]{
                "XPath 怎么写才稳定",
                "wait_strategy 有几种",
                "ATP 支持 App 自动化吗",
                "点击按钮之前应该用哪种等待策略"}) {
            assertFalse(XPathLintChannel.looksLikeXPathQuery(query),
                    "不该被当成 XPath 查询：" + query);
        }
    }

    @Test
    @DisplayName("文件路径与 URL 不被误判为 XPath")
    void filePathsAndUrlsAreNotXPath() {
        // 语料里真实存在这些字符串，误判的话用户问「上传路径怎么填」会收到一条 XPath 告警
        assertFalse(XPathLintChannel.looksLikeXPathQuery(
                "上传文件要放在 /testdata/return/defect_screen.png 吗"),
                "文件路径被误判为 XPath");
        assertFalse(XPathLintChannel.looksLikeXPathQuery(
                "base_url 要配成 http://localhost:8080/mock/login 吗"),
                "URL 被误判为 XPath");
    }

    @Test
    @DisplayName("问题描述里的数字不触发索引下标告警")
    void numbersOutsideXPathDoNotTriggerIndexWarning() {
        // 判定只在 XPath 片段内进行。这里的 [3] 属于 XPath，但「第 5 个」不属于
        List<String> codes = XPathLintChannel.standardCodes(
                XPathLintChannel.analyze("//div[@data-testid=\"panel\"] 和第 5 个元素有什么区别"));
        assertTrue(codes.isEmpty(), "不该产生判定，实际 " + codes);
    }

    @Test
    @DisplayName("每条判定都带可读说明和补充检索词")
    void findingsCarryMessageAndSearchQuery() {
        List<XPathLintChannel.Finding> findings =
                XPathLintChannel.analyze("/html/body/div[2]/input");
        assertFalse(findings.isEmpty());
        for (XPathLintChannel.Finding finding : findings) {
            assertFalse(finding.message().trim().isEmpty(), "判定说明不该为空");
            assertFalse(finding.searchQuery().trim().isEmpty(), "补充检索词不该为空");
        }
        assertFalse(XPathLintChannel.summarize(findings).isEmpty());
        assertFalse(XPathLintChannel.supplementaryQueries(findings).isEmpty());
    }

    @Test
    @DisplayName("补充检索词去重")
    void supplementaryQueriesAreDeduplicated() {
        // 位置下标和 class 完全匹配都归 STD-003，但检索词不同，不该被错误合并；
        // 同一条检索词重复出现时才去重
        List<String> queries = XPathLintChannel.supplementaryQueries(
                XPathLintChannel.analyze("//tr[1]/td[2]"));
        assertEquals(queries.size(), new java.util.HashSet<String>(queries).size(),
                "补充检索词有重复：" + queries);
    }
}
