package com.atp.platform.inspect;

import java.util.List;

/**
 * 探查结果。
 *
 * <h3>⭐ {@code code} 必须区分「你查错了」和「环境坏了」</h3>
 *
 * 两者 agent 的下一步动作完全不同：
 *
 * <ul>
 *   <li>{@code NOT_FOUND}（404）→ 路径写错了，换一个或问用户</li>
 *   <li>{@code INFRA_ERROR}（浏览器起不来、站点不通）→ 重试，或如实报告，**不要改案例**</li>
 * </ul>
 *
 * <p>如果两种都返回"探查失败"，agent 分不清是自己错了还是环境错了，
 * **大概率退回编造** —— 而编造正是这个工具要消灭的东西。
 * 这跟 CLI 退出码里 {@code VALIDATION_FAILED} 与 {@code INFRA_ERROR} 必须分开是同一条道理。
 */
public record InspectResponse(
        String requestId,
        boolean ok,
        /** OK / NOT_FOUND / INFRA_ERROR */
        String code,
        int httpStatus,
        String url,
        String title,
        List<LocatorCandidate> candidates,
        String error) {

    public static InspectResponse ok(String requestId, int status, String url, String title,
                                     List<LocatorCandidate> candidates) {
        return new InspectResponse(requestId, true, "OK", status, url, title, candidates, null);
    }

    public static InspectResponse notFound(String requestId, int status, String url) {
        return new InspectResponse(requestId, false, "NOT_FOUND", status, url, null,
                List.of(), "页面不存在（HTTP " + status + "）");
    }

    public static InspectResponse infra(String requestId, String url, String error) {
        return new InspectResponse(requestId, false, "INFRA_ERROR", 0, url, null, List.of(), error);
    }
}
