package dev.kanashi.atp.cli.model;

/** tc_case 行的最小投影 —— 状态机只关心这几列。枚举在这里是类型化的，不是裸 int。 */
public record CaseRow(
        String caseId,
        CaseType caseType,
        CaseStatus status,
        int version,
        String title,
        String draftJson
) {
    public boolean isAiDraft() {
        return status == CaseStatus.AI_DRAFT;
    }
}
