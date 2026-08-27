package dev.kanashi.atp.cli.cmd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kanashi.atp.cli.AtpCli;
import dev.kanashi.atp.cli.model.CaseDraft;
import dev.kanashi.atp.cli.model.ExitCode;
import dev.kanashi.atp.cli.model.Priority;
import dev.kanashi.atp.cli.rule.DraftValidator;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.File;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * 把本地草稿 JSON 写进库里那一行。
 *
 * <p><b>{@code --version} 是 CAS 的比较值</b>：库里的版本号必须与它一致才写得进去。
 * 对不上说明有人在你之前改过 —— 退出码 10，重新 {@code show} 再来。
 *
 * <p>⚠️ <b>写库前先本地校验</b>：形状不对就不该占用一次数据库往返，
 * 而且 validate 是零成本的（纯本地、毫秒级）。
 */
@Command(name = "update", description = "校验并把草稿 JSON 写入库中的案例行（CAS）")
public final class UpdateCommand implements Callable<Integer> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParentCommand
    private AtpCli parent;

    @Parameters(index = "0", paramLabel = "CASE_ID", description = "案例主键")
    private String caseId;

    @Option(names = "--version", required = true,
            description = "你手上那份的版本号（来自 draft / show / preview）。CAS 比较值。")
    private int expectedVersion;

    @Option(names = {"-f", "--file"}, required = true, description = "草稿 JSON 文件")
    private File file;

    @Option(names = "--skip-validate", description = "跳过本地校验（不建议，仅用于排查）")
    private boolean skipValidate;

    @Override
    public Integer call() throws Exception {
        if (!file.isFile()) {
            return parent.output().fail(ExitCode.NOT_FOUND, "文件不存在: " + file, List.of());
        }
        JsonNode draft = MAPPER.readTree(file);

        if (!skipValidate) {
            DraftValidator.Result r = new DraftValidator().validate(draft);
            if (r.needsUserInput()) {
                return parent.output().needsInput(
                        "必填字段缺失，机器补不出来，请向用户确认后再填", r.missing());
            }
            if (!r.passed()) {
                return parent.output().fail(ExitCode.VALIDATION_FAILED,
                        "字段值不合法，请按下列 violations 修正后重试", r.invalid());
            }
        }
        return parent.output().emit(parent.caseStore().update(caseId, expectedVersion, toDraft(draft)));
    }

    private CaseDraft toDraft(JsonNode n) {
        return new CaseDraft(
                text(n, "case_code"), text(n, "title"), text(n, "module_id"),
                n.hasNonNull("priority") ? Priority.valueOf(n.get("priority").asText()) : null,
                text(n, "author"), text(n, "precondition"),
                n.toString());
    }

    private static String text(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asText() : null;
    }
}
