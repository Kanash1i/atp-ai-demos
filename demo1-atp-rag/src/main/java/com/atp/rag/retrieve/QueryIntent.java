package com.atp.rag.retrieve;

/** 查询意图 —— 决定去哪个 / 哪几个 collection 检索。 */
public enum QueryIntent {

    /** 知识问答：平台怎么用、规范怎么规定。语料是文档。 */
    DOCS,

    /** 案例检索：想找存量案例参考。语料是结构化案例。 */
    CASES,

    /**
     * 两者都要。
     *
     * <p>不只是「问题跨了两类」时用 —— <b>路由拿不准时也一律走这里</b>。
     * 路由错误的代价是不对称的：多查一个 collection 只是多花几十毫秒，
     * 而查漏了就是彻底召回不到，后面的 rerank 再强也救不回来。
     */
    BOTH
}
