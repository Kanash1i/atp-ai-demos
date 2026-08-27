package dev.kanashi.atp.cli.model;

/**
 * 一份案例的当前样子 —— 由 {@code tc_case} 与 {@code tc_step} 各取所需拼成。
 *
 * <p>⭐ <b>两张表各有自己的 version，管的是两个不同的生命周期</b>：
 * <ul>
 *   <li>{@link #version} 来自 {@code tc_step} —— <b>编辑期</b>的乐观锁。
 *       preview 给用户看的、commit 要带回来的就是它。</li>
 *   <li>{@link #platformVersion} 来自 {@code tc_case} —— 案例落地后<b>平台侧</b>修改用的。
 *       编辑期它一动不动。</li>
 * </ul>
 * 编辑期的高频写因此全部落在 tc_step 一张表一行上，tc_case 只在 commit 那一刻被写一次。
 */
public record CaseRow(
        String caseId,
        CaseType caseType,
        /** tc_step.status —— 编辑期状态机 */
        CaseStatus status,
        /** tc_step.version —— 编辑期乐观锁 */
        int version,
        /** tc_case.status —— 平台侧状态 */
        CaseStatus platformStatus,
        int platformVersion,
        /** tc_step.step_json —— 编辑期是完整草稿（表头 + steps），提交后仍留作快照 */
        String draftJson
) {
    public boolean isAiDraft() {
        return status == CaseStatus.AI_DRAFT;
    }
}
