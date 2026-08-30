package com.atp.agent.cli;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 一次 {@code atp} 命令的执行结果。
 *
 * <p>字段直接对应 CLI 的 JSON 信封 {@code {ok, code, replayed, data, violations, questions}}，
 * 外加进程层面的退出码与原始输出。
 *
 * <p>⚠️ {@code exitCode} 与 {@code ok} 是两个层面的东西，不要合并：
 * 进程可能根本没跑起来（二进制不存在、超时被杀），那时压根没有信封。
 */
public record CliResult(
        int exitCode,
        /** 退出码的符号名，与 CLI 的 model.ExitCode.String() 一致 */
        String code,
        boolean ok,
        /** 幂等重放 —— 语义上是成功，不要当失败重试 */
        boolean replayed,
        JsonNode data,
        List<String> violations,
        List<String> questions,
        /** CLI 给人看的一句话说明。失败时往往比 violations 更点题 */
        String message,
        String stdout,
        String stderr) {

    public boolean success() {
        return exitCode == 0;
    }

    /** 取 data 里的字符串字段，缺失返回 null */
    public String str(String field) {
        JsonNode n = data == null ? null : data.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    /** 取 data 里的整数字段，缺失返回 fallback */
    public int intOr(String field, int fallback) {
        JsonNode n = data == null ? null : data.get(field);
        return n == null || !n.isNumber() ? fallback : n.asInt();
    }

    /**
     * 把退出码翻译成**给模型看的下一步动作**。
     *
     * <p>这段映射不是我发明的 —— CLI 的 {@code exitcode.go} 注释里已经为每个码
     * 写明了调用方该做什么。两处必须保持一致，改动前先同步 CLI 侧。
     *
     * <p>关键是 VALIDATION_FAILED 与 NEEDS_INPUT 不能合并：
     * 前者 agent 自己能改，后者必须停下来问人。合并了模型就会开始猜。
     */
    public String nextAction() {
        return switch (code == null ? "" : code) {
            case "VERSION_CONFLICT" -> "版本对不上：内容在你拿到 version 之后被改过。"
                    + "重新读一次案例拿最新 version，确认改动仍然成立，再重试。";
            case "NOT_FOUND" -> "案例不存在（或草稿已被清理）。不要重试，先确认 caseId 对不对。";
            case "VALIDATION_FAILED" -> "内容不合法。按下面的 violations 逐条改，改完重新保存。";
            case "STATE_CONFLICT" -> "当前状态不允许这个操作。停下，把情况说给用户听，不要绕过。";
            case "NEEDS_INPUT" -> "缺必填信息且无法推断。⚠️ 去问用户，不要自己编。";
            case "INFRA_ERROR" -> "环境问题（配置缺失或数据库不通），不是你能改的。"
                    + "如实告诉用户失败原因，不要重试。";
            default -> "";
        };
    }
}
