package dev.kanashi.atp.cli.cmd;

import dev.kanashi.atp.cli.AtpCli;
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

    private static String nz(String v) {
        return v == null ? "—" : v;
    }

    @Override
    public Integer call() {
        StoreResult r = parent.caseStore().show(caseId);
        // --json 时只出信封，人类模式才渲染下面这块（两个通道不要混着打）
        if (!r.succeeded() || parent.jsonMode()) {
            return parent.output().emit(r);
        }
        var out = parent.output().out();
        out.println("──────── 待确认的案例（来自数据库，不是本地文件）────────");
        out.printf("caseId  : %s%n", r.row().caseId());
        out.printf("平台    : %s%n", r.row().caseType());
        out.printf("状态    : %s%n", r.row().status());
        out.printf("标题    : %s%n", r.row().title());
        out.printf("编号    : %s%n", nz(r.row().caseCode()));
        out.printf("模块    : %s   优先级: %s   作者: %s%n",
                nz(r.row().moduleId()),
                r.row().priority() == null ? "—" : r.row().priority(),
                nz(r.row().author()));
        if (r.row().precondition() != null) {
            out.printf("前置    : %s%n", r.row().precondition());
        }
        var steps = r.row().steps();
        if (steps.isEmpty()) {
            out.println("步骤    : (还没写入内容，先跑 atp update)");
        } else {
            out.printf("步骤    : 共 %d 步%n", steps.size());
            for (var step : steps) {
                out.printf("  %2d. %s%n", step.seq(), step.stepJson());
            }
        }
        out.println("────────────────────────────────────────────────");
        out.printf("确认无误后执行：atp commit %s --version %d%n", r.row().caseId(), r.row().version());
        return dev.kanashi.atp.cli.model.ExitCode.OK.code();
    }
}
