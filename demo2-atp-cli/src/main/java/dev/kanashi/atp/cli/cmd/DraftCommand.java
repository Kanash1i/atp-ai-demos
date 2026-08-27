package dev.kanashi.atp.cli.cmd;

import dev.kanashi.atp.cli.AtpCli;
import dev.kanashi.atp.cli.model.CaseType;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * 建草稿行，拿到 caseId 与 version=0。
 *
 * <p>⭐ <b>{@code --id} 由调用方给，并且在重试时必须复用同一个</b> —— 这是整套幂等的唯一来源。
 * 不给时 CLI 本地生成一个，但那样重试就不幂等了（会产生两条各自合法的草稿）。
 * <b>agent 应当自己 {@code uuidgen} 一次然后固定用它。</b>
 */
@Command(name = "draft", description = "建一条 AI 编写态草稿，返回 caseId 与 version")
public final class DraftCommand implements Callable<Integer> {

    @ParentCommand
    private AtpCli parent;

    @Option(names = "--id",
            description = "案例主键（UUID）。⚠️ 重试时复用同一个 —— 它就是幂等键。不给则本地生成。")
    private String caseId;

    @Option(names = {"-p", "--platform"}, required = true,
            description = "执行平台: ${COMPLETION-CANDIDATES}")
    private CaseType platform;

    @Option(names = {"-t", "--title"}, required = true, description = "案例标题")
    private String title;

    @Option(names = "--by", defaultValue = "agent", description = "发起编写的 agent 身份")
    private String createdBy;

    @Override
    public Integer call() {
        String id = (caseId == null || caseId.isBlank()) ? UUID.randomUUID().toString() : caseId;
        return parent.output().emit(parent.caseStore().draft(id, platform, title, createdBy));
    }
}
