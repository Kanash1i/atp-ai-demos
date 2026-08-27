package dev.kanashi.atp.cli.cmd;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kanashi.atp.cli.AtpCli;
import dev.kanashi.atp.cli.model.ExitCode;
import dev.kanashi.atp.cli.rule.DraftValidator;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * 纯本地校验：<b>零网络、零 DB、零模型调用</b>，毫秒级。
 *
 * <p>agent 可以放心高频调、并发调 —— 它是纯函数，没有任何副作用。
 * 这也是 CLI 相对 MCP 最直接的收益：批量校验不需要 N 次来回过模型。
 */
@Command(name = "validate", description = "校验草稿 JSON 的形状（纯本地，无副作用）")
public final class ValidateCommand implements Callable<Integer> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParentCommand
    private AtpCli parent;

    @Option(names = {"-f", "--file"}, required = true, description = "草稿 JSON 文件")
    private File file;

    @Override
    public Integer call() throws Exception {
        if (!file.isFile()) {
            return parent.output().fail(ExitCode.NOT_FOUND, "文件不存在: " + file, List.of());
        }
        DraftValidator.Result r = new DraftValidator().validate(MAPPER.readTree(file));

        if (r.passed()) {
            return parent.output().okRaw(Map.of("file", file.getPath(), "violations", List.of()),
                    "✓ 校验通过: " + file);
        }
        // ⭐ 缺信息优先于值非法 —— 人不补信息，agent 改了也白改
        if (r.needsUserInput()) {
            return parent.output().needsInput(
                    "必填字段缺失，机器补不出来，请向用户确认后再填", r.missing());
        }
        return parent.output().fail(ExitCode.VALIDATION_FAILED,
                "字段值不合法，请按下列 violations 修正后重试", r.invalid());
    }
}
