package dev.kanashi.atp.cli.model;

/** tc_case 行的最小投影 —— 状态机只关心这几列。 */
public record CaseRow(
        String caseId,
        String caseType,
        String status,
        int version,
        String title,
        String draftJson
) {
    public static final String STATUS_AI_DRAFT = "AI_DRAFT";
    /** commit 的目标状态：落地成一条普通的 DRAFT 案例，既有流程完全无感知。 */
    public static final String STATUS_DRAFT = "DRAFT";
    public static final String TYPE_AI = "AI";

    public boolean isAiDraft() {
        return STATUS_AI_DRAFT.equals(status);
    }
}
