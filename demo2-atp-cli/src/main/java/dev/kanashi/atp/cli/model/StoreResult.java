package dev.kanashi.atp.cli.model;

/**
 * store 层的统一返回。
 *
 * @param replayed 本次调用是幂等重放（上一次其实成功了，只是响应丢了）。
 *                 注意 {@code replayed=true} 时 {@code code} 仍是 {@link ExitCode#OK}。
 */
public record StoreResult(ExitCode code, boolean replayed, CaseRow row, String message) {

    public static StoreResult ok(CaseRow row) {
        return new StoreResult(ExitCode.OK, false, row, null);
    }

    public static StoreResult replayed(CaseRow row) {
        return new StoreResult(ExitCode.OK, true, row, "幂等重放：该操作此前已成功");
    }

    public static StoreResult fail(ExitCode code, String message) {
        return new StoreResult(code, false, null, message);
    }

    public boolean succeeded() {
        return code == ExitCode.OK;
    }
}
