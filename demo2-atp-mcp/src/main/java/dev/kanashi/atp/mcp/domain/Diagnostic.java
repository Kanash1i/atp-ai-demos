package dev.kanashi.atp.mcp.domain;

/**
 * 一条结构化诊断。
 * <p>
 * 刻意做成结构化而非一句人话的错误信息：调用方是 agent，不是人。
 * agent 需要靠 {@code code} 分支决策、靠 {@code path} 定位到具体字段去修，
 * 一个 "第 3 步有问题" 的字符串对它毫无用处。
 *
 * @param severity    分级，决定最终 status
 * @param code        机器可判定的错误码，如 {@code FK_MODULE_NOT_FOUND}
 * @param path        字段路径，如 {@code steps[2].locator_value}；案例级问题为 {@code null}
 * @param message     给人看的说明
 * @param standardRef 触发的规范编号，如 {@code STD-001}；非规范类问题为 {@code null}
 */
public record Diagnostic(
        Severity severity,
        String code,
        String path,
        String message,
        String standardRef) {

    public static Diagnostic error(String code, String path, String message, String standardRef) {
        return new Diagnostic(Severity.ERROR, code, path, message, standardRef);
    }

    public static Diagnostic warn(String code, String path, String message, String standardRef) {
        return new Diagnostic(Severity.WARN, code, path, message, standardRef);
    }

    public static Diagnostic info(String code, String path, String message, String standardRef) {
        return new Diagnostic(Severity.INFO, code, path, message, standardRef);
    }
}
