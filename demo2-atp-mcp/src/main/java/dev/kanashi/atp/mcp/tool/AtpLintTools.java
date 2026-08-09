package dev.kanashi.atp.mcp.tool;

import dev.kanashi.atp.mcp.domain.Diagnostic;
import dev.kanashi.atp.mcp.domain.LocatorType;
import dev.kanashi.atp.mcp.domain.Severity;
import dev.kanashi.atp.mcp.lint.LocatorLinter;
import dev.kanashi.atp.mcp.profile.PlatformProfile;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 定位器规范检查 tool。
 * <p>
 * 单独暴露的价值在于<b>可以在写案例的过程中随时调用</b>，而不必等整条案例组装完再提交 ——
 * 调用方 agent 生成一个 XPath 就能立刻知道它合不合规，这比事后拿着一堆诊断回去改便宜得多。
 */
@Component
public class AtpLintTools {

    private final LocatorLinter linter;
    private final PlatformProfile profile;

    public AtpLintTools(LocatorLinter linter, PlatformProfile profile) {
        this.linter = linter;
        this.profile = profile;
    }

    @McpTool(
            name = "atp_lint_locator",
            title = "检查定位器是否符合规范",
            description = """
                    对单个 XPath / CSS 定位器做静态规范检查，返回分级诊断：\
                    STD-001 禁止绝对路径（ERROR）、STD-002 禁止依赖动态生成的 id（WARN）、\
                    STD-003 建议优先使用 data-testid 等稳定属性（INFO）。\
                    纯字符串分析，毫秒级，不访问被测页面，也不调用模型。\
                    编写案例时可随时调用以尽早发现问题。""",
            annotations = @McpTool.McpAnnotations(
                    title = "检查定位器是否符合规范",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false))
    public LintResult lintLocator(
            @McpToolParam(description = "定位器表达式，如 //*[@data-testid='submit'] 或 #login-btn",
                          required = true)
            String locatorValue,

            @McpToolParam(description = "定位方式：XPATH / CSS / ID / NAME / LINK_TEXT。"
                                      + "省略时按表达式形状推断（仅能识别 XPATH 与 CSS）",
                          required = false)
            String locatorType) {

        Optional<LocatorType> declared = Optional.ofNullable(locatorType)
                .flatMap(raw -> profile.enumNormalizer().byName(LocatorType.class, raw));

        boolean inferred = declared.isEmpty();
        LocatorType effective = declared
                .or(() -> LocatorLinter.inferType(locatorValue))
                .orElse(null);

        List<Diagnostic> findings = linter.lint(effective, locatorValue, "locator_value");
        boolean compliant = findings.stream().noneMatch(d -> d.severity() == Severity.ERROR);

        return new LintResult(
                locatorValue,
                effective == null ? null : effective.name(),
                inferred && effective != null,
                compliant,
                findings,
                buildNote(effective, inferred, compliant, findings));
    }

    private static String buildNote(LocatorType effective, boolean inferred,
                                    boolean compliant, List<Diagnostic> findings) {
        if (effective == null) {
            return "未能推断定位方式（仅 XPATH 与 CSS 可由形状识别），"
                 + "本次只做了与类型无关的检查。建议显式提供 locator_type 以获得完整检查。";
        }
        if (findings.isEmpty()) {
            return "未发现问题。";
        }
        String prefix = compliant
                ? "可以使用，但有改进建议（WARN/INFO 不阻止入库）："
                : "存在 ERROR 级问题，包含该定位器的案例会被拒绝：";
        return prefix + findings.size() + " 条诊断"
             + (inferred ? "（locator_type 为推断值，如推断有误请显式指定）" : "") + "。";
    }
}
