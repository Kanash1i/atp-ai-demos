package dev.kanashi.atp.cli.cmd;

import dev.kanashi.atp.cli.AtpCli;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.util.concurrent.Callable;

/**
 * 提交：{@code AI_DRAFT → DRAFT}。
 *
 * <p>⭐ <b>只收 caseId 和 version，不收任何案例内容</b> —— 它是一次纯状态迁移。
 * 用户 preview 的和最终落库的，物理上就是同一行，
 * 从结构上消灭了内容漂移，而不是靠提示词约束 agent 别乱改。
 *
 * <p>⭐ <b>幂等重放返回退出码 0</b>：重放在语义上是成功。
 * 返回非 0 会让 agent 以为没成功而无限重试。
 */
@Command(name = "commit", description = "提交草稿，落地为老平台原生的 DRAFT 案例（执行器无感知）")
public final class CommitCommand implements Callable<Integer> {

    @ParentCommand
    private AtpCli parent;

    @Parameters(index = "0", paramLabel = "CASE_ID", description = "案例主键")
    private String caseId;

    @Option(names = "--version", required = true,
            description = "用户确认时看到的版本号（来自 atp preview）。对不上就是内容被改过，拒绝提交。")
    private int expectedVersion;

    @Override
    public Integer call() {
        return parent.output().emit(parent.caseStore().commit(caseId, expectedVersion));
    }
}
