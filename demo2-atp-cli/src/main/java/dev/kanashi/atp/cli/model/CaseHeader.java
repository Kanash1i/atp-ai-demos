package dev.kanashi.atp.cli.model;

/**
 * 草稿里的表头字段。
 *
 * <p>编辑期它只存在于 {@code tc_step.step_json} 里；
 * <b>commit 那一刻才投影到 {@code tc_case} 的正式列上</b> ——
 * 所以编辑期完全不用碰 tc_case，也就没有跨表写。
 *
 * <p>投影的输入是<b>库里那一行</b>而不是调用方传来的内容，
 * 因此不违反「commit 只收 id + version」：它展开的是用户已经确认过的那份快照。
 */
public record CaseHeader(
        String caseCode,
        String title,
        String moduleId,
        Priority priority,
        String author,
        String precondition
) {}
