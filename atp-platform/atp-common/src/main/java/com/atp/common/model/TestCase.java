package com.atp.common.model;

import com.atp.common.enums.Browser;
import com.atp.common.enums.CaseStatus;
import com.atp.common.enums.Priority;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

/**
 * 一条完整案例：{@code tc_case} 的表头 + {@code tc_step.step_json} 的全量步骤。
 *
 * <p>这个形状同时是**种子 JSON 的形状**（{@code seed/cases/*.json}），
 * 所以导入不需要额外的 DTO 转换。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record TestCase(
        String caseId,
        String caseCode,
        String title,
        String moduleId,
        String moduleCode,
        Priority priority,
        String author,
        String precondition,
        CaseStatus status,
        Browser browser,
        Integer timeoutSec,
        String createdAt,
        String updatedAt,
        List<Step> steps,

        // ── 以下三个只在种子 JSON 里出现，不进表 ──────────────────
        // 它们是造语料时标注的**标准答案**：这条案例故意违反了哪几条规范。
        // ⭐ 校验器跑完 80 条的结果应该与它们完全吻合 —— 这是校验器的自验证手段，
        //    比自己再写一遍断言可靠（那等于用同一套理解验证同一套理解）。
        Boolean hasViolation,
        List<String> violationCodes,
        Integer stepCount
) {

    public List<Step> stepsOrEmpty() {
        return steps == null ? List.of() : steps;
    }
}
