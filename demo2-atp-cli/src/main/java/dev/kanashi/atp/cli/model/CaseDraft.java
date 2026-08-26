package dev.kanashi.atp.cli.model;

/**
 * 一次 update 要写入的内容。
 *
 * <p>正式列和 {@code rawJson} 同时写：正式列让 preview / 平台列表页能直接渲染，
 * {@code rawJson} 保留 steps 等尚未投影到子表的部分（tc_step 的投影在 M2）。
 */
public record CaseDraft(
        String caseCode,
        String title,
        String moduleId,
        String priority,
        String author,
        String precondition,
        String browser,
        Integer timeoutSec,
        String rawJson
) {}
