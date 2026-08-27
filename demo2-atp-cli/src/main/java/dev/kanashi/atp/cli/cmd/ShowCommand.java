package dev.kanashi.atp.cli.cmd;

import dev.kanashi.atp.cli.AtpCli;
import dev.kanashi.atp.cli.model.StoreResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.util.concurrent.Callable;

/**
 * 读回草稿当前内容与 version。
 *
 * <p>agent 的典型用法：{@code atp show <id> --json | jq -r .data.draftJson > draft.json}，
 * 改完再 {@code atp update <id> --version <data.version> -f draft.json}。
 */
@Command(name = "show", description = "输出草稿当前的 draft_json 与 version")
public final class ShowCommand implements Callable<Integer> {

    @ParentCommand
    private AtpCli parent;

    @Parameters(index = "0", paramLabel = "CASE_ID", description = "案例主键")
    private String caseId;

    @Override
    public Integer call() {
        StoreResult r = parent.caseStore().show(caseId);
        return parent.output().emit(r);
    }
}
