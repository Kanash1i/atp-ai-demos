package dev.kanashi.atp.mcp.domain;

/** 案例状态（tc_case.status，默认 DRAFT）。 */
public enum CaseStatus {

    /**
     * 草稿。规范化服务产出的案例默认落这个状态 ——
     * 本服务不决定案例能否生效，那是平台方的职责。
     */
    DRAFT,

    ACTIVE,

    DEPRECATED
}
