package dev.kanashi.atp.cli;

import dev.kanashi.atp.cli.cmd.CommitCommand;
import dev.kanashi.atp.cli.cmd.DraftCommand;
import dev.kanashi.atp.cli.cmd.ModulesCommand;
import dev.kanashi.atp.cli.cmd.PreviewCommand;
import dev.kanashi.atp.cli.cmd.SchemaCommand;
import dev.kanashi.atp.cli.cmd.ShowCommand;
import dev.kanashi.atp.cli.cmd.UpdateCommand;
import dev.kanashi.atp.cli.cmd.ValidateCommand;
import dev.kanashi.atp.cli.config.CliConfig;
import dev.kanashi.atp.cli.model.ExitCode;
import dev.kanashi.atp.cli.out.Output;
import dev.kanashi.atp.cli.store.CaseStore;
import dev.kanashi.atp.cli.store.ConnectionFactory;
import dev.kanashi.atp.cli.store.DictStore;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code atp} —— ATP 案例编写 CLI。
 *
 * <p>⚠️ 刻意不用 Spring：这个命令被 agent 高频反复调用，冷启动是真实成本
 * （Spring Boot ≈ 1.5s / picocli fat jar ≈ 300ms）。见 DECISIONS D-103。
 */
@Command(
        name = "atp",
        mixinStandardHelpOptions = true,
        version = "atp 0.1.0",
        description = "ATP 案例编写 CLI —— 幂等键做成平台案例表的主键，唯一约束 + CAS 当并发仲裁点。",
        subcommands = {
                SchemaCommand.class, ModulesCommand.class, ValidateCommand.class,
                DraftCommand.class, ShowCommand.class, UpdateCommand.class,
                PreviewCommand.class, CommitCommand.class
        })
public final class AtpCli implements Runnable {

    @Option(names = "--json",
            description = "输出结构化信封（给 agent 用）。默认输出人类可读文本。",
            scope = CommandLine.ScopeType.INHERIT)
    boolean json;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(System.out);
    }

    // ------------------------------------------------------------ 供子命令用

    public boolean jsonMode() {
        return json;
    }

    public Output output() {
        return new Output(json, System.out, System.err);
    }

    /** 需要打库的子命令调它。配置缺失时 fail fast，不给默认值。 */
    public CaseStore caseStore() {
        return new CaseStore(connections());
    }

    public DictStore dictStore() {
        return new DictStore(connections());
    }

    private ConnectionFactory connections() {
        CliConfig cfg = CliConfig.load();
        return ConnectionFactory.of(
                cfg.require(CliConfig.DB_URL),
                cfg.require(CliConfig.DB_USER),
                cfg.optional(CliConfig.DB_PASSWORD));
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    /** 与 {@link #main} 同逻辑但不退出 JVM —— 测试用。 */
    public static int run(String... args) {
        AtpCli root = new AtpCli();
        return new CommandLine(root)
                .setExecutionExceptionHandler((ex, cmd, parseResult) -> {
                    // 配置缺失、DB 不通这类问题统一归 INFRA_ERROR(20)，
                    // 不要让 picocli 打一整页 stack trace 给 agent 看。
                    System.err.println("[" + ExitCode.INFRA_ERROR + "] " + ex.getMessage());
                    return ExitCode.INFRA_ERROR.code();
                })
                .setParameterExceptionHandler((ex, argv) -> {
                    System.err.println("[" + ExitCode.VALIDATION_FAILED + "] " + ex.getMessage());
                    ex.getCommandLine().usage(System.err);
                    return ExitCode.VALIDATION_FAILED.code();
                })
                .execute(args);
    }
}
