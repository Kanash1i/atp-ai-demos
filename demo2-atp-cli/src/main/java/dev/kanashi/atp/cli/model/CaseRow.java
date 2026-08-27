package dev.kanashi.atp.cli.model;

import java.util.List;

/**
 * tc_case 行 + 它的 tc_step 子行。
 *
 * <p>⚠️ tc_case 上<b>没有</b>整包 JSON 列：步骤的唯一存放处是 {@code tc_step.step_json}。
 * 父表再存一份 blob 就是同一份数据存两遍，必然要同步。
 *
 * <p>表头字段全带上，是为了让 {@code atp show} 能把草稿<b>原样还原成一个可编辑的 JSON</b>，
 * agent 改完直接喂回 {@code atp update} —— 少了任何一个字段，这条来回就断了。
 */
public record CaseRow(
        String caseId,
        CaseType caseType,
        CaseStatus status,
        int version,
        String caseCode,
        String title,
        String moduleId,
        Priority priority,
        String author,
        String precondition,
        List<StepRow> steps
) {
    public boolean isAiDraft() {
        return status == CaseStatus.AI_DRAFT;
    }
}
