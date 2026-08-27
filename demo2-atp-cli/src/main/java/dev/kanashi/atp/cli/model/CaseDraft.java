package dev.kanashi.atp.cli.model;

import java.util.List;

/**
 * 一次 update 要写入的完整内容 —— 表头字段 + 步骤。
 *
 * <p>表头进 {@code tc_case} 的正式列，步骤整批替换 {@code tc_step}。
 * <b>两者必须在同一个事务里写</b>，否则会出现"表头更新了但步骤还是旧的"这种半截状态。
 */
public record CaseDraft(
        String caseCode,
        String title,
        String moduleId,
        Priority priority,
        String author,
        String precondition,
        List<StepRow> steps
) {}
