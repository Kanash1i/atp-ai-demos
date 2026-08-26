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
    /** 编写态。只可能由 AI 编写路径产生，因此也是清理任务唯一的过滤条件。 */
    public static final String STATUS_AI_DRAFT = "AI_DRAFT";
    /** commit 的目标状态：落地成老平台原生的草稿案例，执行器与既有列表页无感知。 */
    public static final String STATUS_DRAFT = "DRAFT";

    /** 执行平台，老平台原有概念。 */
    public static final String TYPE_IOS = "IOS";
    public static final String TYPE_ANDROID = "ANDROID";
    public static final String TYPE_PC_WEB = "PC_WEB";

    public boolean isAiDraft() {
        return STATUS_AI_DRAFT.equals(status);
    }
}
