package dev.kanashi.atp.cli.cmd;

import dev.kanashi.atp.cli.AtpCli;
import dev.kanashi.atp.cli.rule.DraftValidator;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

import java.util.concurrent.Callable;

/**
 * ⭐ 这条命令是"左移"的落地：让 agent 在<b>生成阶段</b>就知道该产出什么形状，
 * 而不是生成完再由下游收拾。只提供事后校验，等于放任上游乱生成。
 *
 * <p>零 DB、零网络、零模型。
 */
@Command(name = "schema", description = "输出案例草稿的 JSON Schema（含必填、枚举、不该由调用方产出的字段）")
public final class SchemaCommand implements Callable<Integer> {

    @ParentCommand
    private AtpCli parent;

    @Override
    public Integer call() {
        String json = new DraftValidator().schemaJson();
        // schema 本身就是 JSON，两个通道输出一样 —— 不套信封，方便直接 > schema.json
        parent.output().out().println(json);
        return 0;
    }
}
