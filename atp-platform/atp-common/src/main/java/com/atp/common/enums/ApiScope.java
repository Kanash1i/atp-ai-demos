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
    INSPECT("inspect");

    private final String code;

    ApiScope(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
