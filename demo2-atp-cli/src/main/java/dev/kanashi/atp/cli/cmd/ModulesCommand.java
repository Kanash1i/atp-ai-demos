package dev.kanashi.atp.cli.cmd;

import dev.kanashi.atp.cli.AtpCli;
import dev.kanashi.atp.cli.model.ExitCode;
import dev.kanashi.atp.cli.store.DictStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/** 模块字典 —— agent 用它确认 module_id 的取值范围。**防编造靠这条，不靠外键**（见 D-109）。 */
@Command(name = "modules", description = "列出项目与模块字典（module_id 的合法取值范围）")
public final class ModulesCommand implements Callable<Integer> {

    @ParentCommand
    private AtpCli parent;

    @Option(names = {"-p", "--project"}, description = "只看某个项目（project_code，如 ECSHOP）")
    private String projectCode;

    @Override
    public Integer call() throws Exception {
        List<DictStore.ModuleEntry> all = parent.dictStore().listModules();
        List<DictStore.ModuleEntry> rows = projectCode == null ? all
                : all.stream().filter(m -> projectCode.equalsIgnoreCase(m.projectCode())).toList();

        if (rows.isEmpty()) {
            return parent.output().fail(ExitCode.NOT_FOUND,
                    "没有匹配的模块" + (projectCode == null ? "" : "（project=" + projectCode + "）"),
                    List.of());
        }
        String human = rows.stream()
                .map(m -> "  %-8s %-10s %-9s %-8s %s".formatted(
                        m.projectCode(), m.projectId(), m.moduleId(), m.moduleCode(), m.moduleName()))
                .collect(Collectors.joining("\n",
                        "  %-8s %-10s %-9s %-8s %s%n".formatted(
                                "项目", "project_id", "module_id", "code", "名称"), ""));
        return parent.output().okRaw(rows, human);
    }
}
