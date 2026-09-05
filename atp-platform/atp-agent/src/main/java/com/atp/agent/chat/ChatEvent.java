package com.atp.agent.chat;

/**
 * 推给前端的 SSE 事件。
 *
 * <p>⚠️ 事件类型是**前端契约**，不是内部枚举 —— 前端按 type 决定渲染成
 * 气泡、进度行还是工具卡片。加类型要同步 {@code 02-前端契约.md}。
 *
 * @param type    thinking / tool / plan / message / done / error / interrupted / tool-aborted
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
     * 任务清单快照。
     *
     * <h3>为什么单独一个事件，而不是塞进 thinking</h3>
     *
     * thinking 是流水账 —— 一轮几百条增量，用户滚到最后就找不到开头了。
     * 而任务清单是<b>一个会变的状态</b>：前端应该把它钉在固定位置，
     * 每次更新覆盖上一份，而不是追加成第 N 条消息。
     *
     * <p>⚠️ 这也正是打断时最该展示的东西：停下来的那一刻，
     * 哪些子任务 DONE、哪个 IN_PROGRESS、剩下几个 TODO ——
     * 比一句「已停止」有用得多，用户据此决定要不要接着做。
     *
     * <p>content 是 JSON：{@code {"name":"...","subtasks":[{"name":"","state":"DONE"}]}}
     */
    public static ChatEvent plan(String agent, String json) {
        return new ChatEvent("plan", agent, json);
    }

    /**
     * 用户主动打断，agent 已停下。
     *
     * <h3>⚠️ 打断的语义是「不再继续下一步」，不是「撤销已做的」</h3>
     *
     * 打断时如果 {@code commit_case} 的 HTTP 已经发出去，那条案例就是提交了 ——
     * 数据库那侧没有回滚，也不该回滚（它是一次合法的、已完成的写入）。
     *
     * <p>所以前端必须把这件事说清楚：停止按钮的含义是「别再往下做了」，
     * 不是「当作什么都没发生」。把它渲染成撤销，用户会以为数据回到了打断前，
     * 而那是错的 —— 这种误解比不给停止按钮更危险。
     */
    public static ChatEvent interrupted(String agent, String reason) {
        return new ChatEvent("interrupted", agent, reason);
    }

    /**
     * 打断时有工具调用正在飞，它们的结果被丢弃了。
     *
     * <p>⭐ 单独一个事件类型，而不是并进 {@link #interrupted}：
     * 「停下了」和「停下时有半截操作」是两件事，后者用户需要知道具体是哪些 ——
     * 因为其中可能有已经写进库的。AgentScope 的 {@code InterruptContext.pendingToolCalls}
     * 给的就是这份清单。
     */
    public static ChatEvent toolAborted(String agent, String toolNames) {
        return new ChatEvent("tool-aborted", agent, toolNames);
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
