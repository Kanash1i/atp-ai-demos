package dev.kanashi.atp.cli.cmd;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kanashi.atp.cli.AtpCli;
import dev.kanashi.atp.cli.rule.DraftHeader;
import dev.kanashi.atp.cli.model.StoreResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.util.concurrent.Callable;

/**
 * 给人看的渲染。
 *
 * <p>⭐ <b>它读的是库里的行，不是本地文件</b> —— 用户确认的对象和最终提交的对象
 * 因此在物理上就是同一行。本地 JSON 只是编辑面。
 *
 * <p>输出里的 {@code version} 是用户确认时的版本号，
 * {@code atp commit} 要原样带回去；中间任何人改一下 version 就跳，commit 必失败。
 */
@Command(name = "preview", description = "渲染草稿供用户确认，并打印要带回 commit 的 version")
public final class PreviewCommand implements Callable<Integer> {

    @ParentCommand
    private AtpCli parent;

    @Parameters(index = "0", paramLabel = "CASE_ID")
    private String caseId;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String nz(String v) {
        return v == null ? "—" : v;
    }

    private static String prettySteps(String draftJson) {
        try {
            var steps = MAPPER.readTree(draftJson).path("steps");
            if (!steps.isArray() || steps.isEmpty()) {
                return "  (还没写入步骤，先跑 atp update)";
            }
            var sb = new StringBuilder();
            for (var step : steps) {
                sb.append("  %2d. %-16s %s%n".formatted(
                        step.path("seq").asInt(),
                        step.path("action").asText("?"),
                        step.path("description").asText(step.path("locator_value").asText(""))));
            }
            return sb.toString().stripTrailing();
        } catch (Exception e) {
            return "  (步骤解析失败: " + e.getMessage() + ")";
        }
    }

    @Override
    public Integer call() {
        StoreResult r = parent.caseStore().show(caseId);
        // --json 时只出信封，人类模式才渲染下面这块（两个通道不要混着打）
        if (!r.succeeded() || parent.jsonMode()) {
            return parent.output().emit(r);
        }
        var out = parent.output().out();
        var h = DraftHeader.parse(r.row().draftJson());
        out.println("──────── 待确认的案例（来自数据库，不是本地文件）────────");
        out.printf("caseId  : %s%n", r.row().caseId());
        out.printf("平台    : %s   状态: %s%n", r.row().caseType(), r.row().status());
        out.printf("编号    : %s%n", nz(h.caseCode()));
        out.printf("标题    : %s%n", nz(h.title()));
        out.printf("模块    : %s   优先级: %s   作者: %s%n",
                nz(h.moduleId()), h.priority() == null ? "—" : h.priority(), nz(h.author()));
        if (h.precondition() != null) {
            out.printf("前置    : %s%n", h.precondition());
        }
        out.println("步骤    :");
        out.println(prettySteps(r.row().draftJson()));
        out.println("────────────────────────────────────────────────");
        out.printf("确认无误后执行：atp commit %s --version %d%n", r.row().caseId(), r.row().version());
        return dev.kanashi.atp.cli.model.ExitCode.OK.code();
    }
}
