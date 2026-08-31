package com.atp.agent.chat;

/**
 * 推给前端的 SSE 事件。
 *
 * <p>⚠️ 事件类型是**前端契约**，不是内部枚举 —— 前端按 type 决定渲染成
 * 气泡、进度行还是工具卡片。加类型要同步 {@code 02-前端契约.md}。
 *
 * @param type    thinking / tool / message / done / error
 * @param agent   哪个 agent 发出的，前端用它做归属标记
 * @param content 正文
 */
public record ChatEvent(String type, String agent, String content) {

    /** agent 的推理过程。前端渲染成灰色的「正在思考…」行，可折叠 */
    public static ChatEvent thinking(String agent, String text) {
        return new ChatEvent("thinking", agent, text);
    }

    /**
     * 工具调用结果。
     *
     * <p>⭐ 这是这个 demo 最该展示的东西 —— 用户能看见 agent「查了哪条规范、
     * 拿了哪个模块、校验报了什么」。黑盒地吐出一条案例远不如把过程摊开来有说服力。
     */
    public static ChatEvent tool(String agent, String text) {
        return new ChatEvent("tool", agent, text);
    }

    /** 最终回复，流式增量 */
    public static ChatEvent message(String agent, String text) {
        return new ChatEvent("message", agent, text);
    }

    /**
     * 路由结论。前端据此显示「现在是哪个助手在答」——
     * 用户看得见路由，才能在它判错时立刻说"不是这个意思"，而不是等答完一大段才发现跑偏。
     */
    public static ChatEvent route(String agent, String text) {
        return new ChatEvent("route", agent, text);
    }

    public static ChatEvent done(String agent) {
        return new ChatEvent("done", agent, "");
    }

    public static ChatEvent error(String agent, String text) {
        return new ChatEvent("error", agent, text);
    }
}
