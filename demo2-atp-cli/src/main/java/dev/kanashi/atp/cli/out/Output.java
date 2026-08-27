package dev.kanashi.atp.cli.out;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.kanashi.atp.cli.model.CaseRow;
import dev.kanashi.atp.cli.model.ExitCode;
import dev.kanashi.atp.cli.model.StoreResult;

import java.io.PrintStream;
import java.util.List;

/**
 * 双通道输出：{@code --json} 给 agent，默认给人。
 *
 * <p><b>信封形状是对 agent 的契约，改它等于改 API</b>：
 * <pre>
 * { "ok": bool, "code": "OK|VERSION_CONFLICT|...", "replayed": bool,
 *   "data": {...}, "violations": [...], "questions": [...] }
 * </pre>
 *
 * <p>⚠️ 返回的退出码才是 agent 的主要分流依据（见 {@link ExitCode}）。
 * 信封里的 {@code code} 是给人和日志看的冗余，两者必须一致。
 */
public final class Output {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final boolean json;
    private final PrintStream out;
    private final PrintStream err;

    public Output(boolean json, PrintStream out, PrintStream err) {
        this.json = json;
        this.out = out;
        this.err = err;
    }

    // ------------------------------------------------------------ store 结果

    public int emit(StoreResult result) {
        if (result.succeeded()) {
            return ok(result.row(), result.replayed(), result.message());
        }
        return fail(result.code(), result.message(), List.of());
    }

    public int ok(CaseRow row, boolean replayed, String note) {
        ObjectNode data = MAPPER.createObjectNode();
        if (row != null) {
            data.put("caseId", row.caseId());
            data.put("caseType", row.caseType().name());
            data.put("status", row.status().name());
            data.put("version", row.version());
            data.put("title", row.title());
            if (row.draftJson() != null) {
                try {
                    data.set("draftJson", MAPPER.readTree(row.draftJson()));
                } catch (Exception ignore) {
                    data.put("draftJson", row.draftJson());
                }
            }
        }
        if (json) {
            return print(envelope(ExitCode.OK, replayed, data, null, null), ExitCode.OK);
        }
        if (row != null) {
            out.printf("%s  status=%s  version=%d%n", row.caseId(), row.status(), row.version());
            if (row.title() != null) {
                out.printf("  %s%n", row.title());
            }
        }
        if (replayed) {
            out.println("  (幂等重放：该操作此前已成功，未产生新的变更)");
        }
        if (note != null && !replayed) {
            out.println("  " + note);
        }
        return ExitCode.OK.code();
    }

    /** 任意 JSON 载荷的成功输出（schema / modules 这类只读命令用）。 */
    public int okRaw(Object payload, String humanText) {
        if (json) {
            ObjectNode env = envelope(ExitCode.OK, false, MAPPER.valueToTree(payload), null, null);
            return print(env, ExitCode.OK);
        }
        out.println(humanText);
        return ExitCode.OK.code();
    }

    // ------------------------------------------------------------------ 失败

    public int fail(ExitCode code, String message, List<String> violations) {
        if (json) {
            ObjectNode env = envelope(code, false, null, violations, null);
            env.put("message", message);
            return print(env, code);
        }
        err.printf("[%s] %s%n", code, message);
        for (String v : violations) {
            err.println("  - " + v);
        }
        return code.code();
    }

    /** 缺信息、机器补不了 —— agent 必须去问用户，不要猜。 */
    public int needsInput(String message, List<String> questions) {
        if (json) {
            ObjectNode env = envelope(ExitCode.NEEDS_INPUT, false, null, null, questions);
            env.put("message", message);
            return print(env, ExitCode.NEEDS_INPUT);
        }
        err.printf("[%s] %s%n", ExitCode.NEEDS_INPUT, message);
        for (String q : questions) {
            err.println("  ? " + q);
        }
        return ExitCode.NEEDS_INPUT.code();
    }

    // ---------------------------------------------------------------- 内部

    private ObjectNode envelope(ExitCode code, boolean replayed,
                                com.fasterxml.jackson.databind.JsonNode data,
                                List<String> violations, List<String> questions) {
        ObjectNode env = MAPPER.createObjectNode();
        env.put("ok", code == ExitCode.OK);
        env.put("code", code.name());
        env.put("replayed", replayed);
        env.set("data", data == null ? MAPPER.nullNode() : data);
        env.set("violations", toArray(violations));
        env.set("questions", toArray(questions));
        return env;
    }

    private ArrayNode toArray(List<String> items) {
        ArrayNode arr = MAPPER.createArrayNode();
        if (items != null) {
            items.forEach(arr::add);
        }
        return arr;
    }

    private int print(ObjectNode env, ExitCode code) {
        try {
            out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(env));
        } catch (Exception e) {
            err.println("序列化输出失败: " + e.getMessage());
            return ExitCode.INFRA_ERROR.code();
        }
        return code.code();
    }

    public PrintStream out() {
        return out;
    }
}
