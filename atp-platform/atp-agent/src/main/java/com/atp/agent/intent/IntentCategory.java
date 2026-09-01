package com.atp.agent.intent;

/**
 * 用户意图 —— 路由的目标。
 *
 * <p>⚠️ 分类粒度是按**谁来处理**切的，不是按话题切的。
 * 「帮我写个登录案例」和「登录案例该怎么写才合规」听起来都是登录，
 * 但前者要动数据库、后者只需要查文档，处理它们的是两个 agent。
 * 按话题分类会让路由结果没法直接映射到执行者。
 */
public enum IntentCategory {

    /** 写案例：自然语言 → 案例草稿 → 落库 */
    CASE_AUTHORING("案例编写", true),

    /** 规范/手册问答：只查资料，不改任何东西 */
    KNOWLEDGE_QA("规范问答", true),

    /** 查案例：按模块/优先级/状态找存量案例 */
    CASE_QUERY("案例查询", false),

    /** 派发执行、查执行状态、取录像 */
    EXECUTION("执行相关", true),

    /** 提交/查询审批 */
    APPROVAL("审批相关", false),

    /** 闲聊、打招呼、以及分不出来的 */
    OTHER("其他", true);

    private final String display;

    /** 是否已经有对应的 agent。没有的话路由照常给出结论，但回话要如实说"这块还没做" */
    private final boolean implemented;

    IntentCategory(String display, boolean implemented) {
        this.display = display;
        this.implemented = implemented;
    }

    public String display() {
        return display;
    }

    public boolean implemented() {
        return implemented;
    }

    /** LLM 有时会把枚举名写歪（小写、带引号、多一句解释），宽松解析，认不出就是 OTHER */
    public static IntentCategory parse(String raw) {
        if (raw == null) {
            return OTHER;
        }
        String s = raw.trim().toUpperCase().replaceAll("[^A-Z_]", "");
        for (IntentCategory c : values()) {
            if (s.contains(c.name())) {
                return c;
            }
        }
        return OTHER;
    }
}
