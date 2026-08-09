package com.atp.rag.retrieve;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 路由的规则部分测试。
 *
 * <p>{@code chatModel} 传 null，于是规则判不出来的一律落到 {@link QueryIntent#BOTH} ——
 * 这正好也是「LLM 不可用时降级成都查」这条路径的测试。
 */
class AtpQueryRouterTest {

    private final AtpQueryRouter router = new AtpQueryRouter(null);

    @Test
    @DisplayName("明确要找案例的问题走 CASES，不花 LLM 调用")
    void caseSignalsRouteToCases() {
        assertEquals(QueryIntent.CASES, router.route("帮我找几个购物车相关的案例参考"));
        assertEquals(QueryIntent.CASES, router.route("ログイン失敗時のテストケースはありますか"));
        assertEquals(QueryIntent.CASES, router.route("有没有类似的用例可以借鉴"));
    }

    @Test
    @DisplayName("明确问规则用法的问题走 DOCS")
    void docSignalsRouteToDocs() {
        assertEquals(QueryIntent.DOCS, router.route("XPath 怎么写才稳定"));
        assertEquals(QueryIntent.DOCS, router.route("为什么规范禁止使用 SLEEP"));
        assertEquals(QueryIntent.DOCS, router.route("ATP 支持 App 自动化吗"));
        assertEquals(QueryIntent.DOCS, router.route("wait_strategy とは何ですか"));
    }

    @Test
    @DisplayName("「选哪一个」类问法也走 DOCS，不落到 LLM")
    void choiceStyleQuestionsRouteToDocs() {
        // 实测漏了这类词的后果：这句话落到 LLM 路由后被稳定判成 CASES，
        // 一个典型知识问答被送去查案例库
        assertEquals(QueryIntent.DOCS, router.route("点击按钮之前应该用哪种等待策略"));
        assertEquals(QueryIntent.DOCS, router.route("wait_strategy 有几种"));
        assertEquals(QueryIntent.DOCS, router.route("定位器有哪些类型"));
        assertEquals(QueryIntent.DOCS, router.route("什么时候该用 PRESENCE"));
    }

    @Test
    @DisplayName("两类信号都有时走 BOTH")
    void mixedSignalsRouteToBoth() {
        // 「购物车的案例怎么写」既要看已有案例，也要看规范
        assertEquals(QueryIntent.BOTH, router.route("购物车的案例怎么写"));
    }

    @Test
    @DisplayName("规则拿不准且没有 LLM 时，倒向 BOTH 而不是猜")
    void ambiguousQueriesFallBackToBoth() {
        // 路由错误的代价不对称：多查一个 collection 只多花几十毫秒，
        // 查漏了就是彻底召回不到，rerank 再强也救不回来
        assertEquals(QueryIntent.BOTH, router.route("购物车"));
        assertEquals(QueryIntent.BOTH, router.route("//div[3]/span"));
    }
}
