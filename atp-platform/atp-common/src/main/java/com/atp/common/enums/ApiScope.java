package com.atp.common.enums;

/**
 * 窄 token 的权限项。
 *
 * <h3>⭐ 为什么「跑单条自验」与「派发批次」必须是两个 scope</h3>
 *
 * 两者底层都是执行，但性质不同：
 * <ul>
 *   <li>{@link #EXEC_RUN_ONCE} 是**编写闭环的一环** —— 写完这条，跑一下看看。
 *       发给客户端是合理的</li>
 *   <li>{@link #EXEC_DISPATCH} 是**平台调度** —— 涉及排队、配额、优先级。
 *       发到每台客户电脑上，等于把执行机资源池的调度权也发出去了，
 *       客户写个循环就能把执行机占满</li>
 * </ul>
 *
 * <p>合成一个 scope 的话，想给前者就必须一并给后者 —— 权限的粒度决定了能不能只给该给的。
 */
public enum ApiScope {

    /** 写案例：draft / update / commit */
    CASE_WRITE("case:write"),

    /** 跑单条自验。⚠️ 不含派发批次 */
    EXEC_RUN_ONCE("exec:run-once"),

    /** 派发批次 —— 平台调度权，默认不发给客户端 */
    EXEC_DISPATCH("exec:dispatch"),

    /** 页面探查 */
    INSPECT("inspect"),

    /**
     * 审批决策（批准 / 退回 / 挂起）。
     *
     * <p>⚠️ **只发给人，不发给机器**。agent 可以写案例、可以自验，
     * 但「这条变更该不该放行」是人的判断 —— 把它发给机器，
     * 等于让 agent 自己批准自己提交的东西。
     */
    APPROVAL_DECIDE("approval:decide"),

    /**
     * 签发与吊销机器凭证。
     *
     * <p>⚠️ **只发给 ADMIN，且只发给人**。这是唯一一个能凭空造出新主体的权限 ——
     * 拿到它就能给自己签一个带任意 scope 的 client，其余所有权限控制都被绕过。
     *
     * <p>机器主体永远不该有它：一个 agent 能给自己发凭证，
     * 等于「窄 token」这件事从一开始就不成立。
     */
    CLIENT_MANAGE("client:manage");

    private final String code;

    ApiScope(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
