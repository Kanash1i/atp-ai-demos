package dev.kanashi.atp.cli.rule;

import dev.kanashi.atp.cli.model.Diagnostic;
import dev.kanashi.atp.cli.model.LocatorType;
import dev.kanashi.atp.cli.rule.DiagnosticCodes;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 定位器静态检查：STD-001 / STD-002 / STD-003。
 * <p>
 * <b>为什么这三条要分成三个等级，而不是一律拒绝</b>：
 * 全判 ERROR 会让服务挑剔到没人愿意用，全判 WARN 则等于没有守门。
 * 分级的判据是「后果的确定性」——
 * <ul>
 *   <li>绝对路径 <b>一定</b> 会因为页面改版而失效 → ERROR</li>
 *   <li>动态 id <b>很可能</b>失效，但也存在真的是稳定 id 却恰好带数字的情况 → WARN</li>
 *   <li>用 class 或文本定位<b>可能</b>不够稳，属于写法偏好 → INFO</li>
 * </ul>
 * 这一整层是纯字符串分析，毫秒级、完全确定，不需要任何模型参与。
 */
public class LocatorLinter {

    /**
     * 前端框架自动生成的 id 模式。
     * <p>
     * 这些不是臆想出来的正则，是各框架实际的生成规则：
     * ExtJS 的 {@code ext-gen1234}、GWT 的 {@code gwt-uid-3}、YUI 的 {@code yui_3_18_1_1}、
     * Ember 的 {@code ember123}、React 18 {@code useId} 的 {@code :r0:}、
     * Angular 的 {@code _ngcontent-xxx}。
     * 它们的共同点是<b>每次渲染都可能变</b>，所以依赖它们的定位器随时会失效。
     */
    private static final List<Pattern> DYNAMIC_ID_PATTERNS = List.of(
            Pattern.compile("ext-gen\\d+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("gwt-uid-\\d+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("yui_[\\d_]+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("ember\\d+", Pattern.CASE_INSENSITIVE),
            Pattern.compile(":r[0-9a-z]+:", Pattern.CASE_INSENSITIVE),
            Pattern.compile("_ngcontent-\\w+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bid_?\\d{4,}\\b", Pattern.CASE_INSENSITIVE));

    /** 从 XPath 的 {@code @id='x'} / {@code @id="x"} 里取出 id 值。 */
    private static final Pattern XPATH_ID = Pattern.compile("@id\\s*=\\s*['\"]([^'\"]+)['\"]");

    /** 从 CSS 的 {@code #x} 里取出 id 值。 */
    private static final Pattern CSS_ID = Pattern.compile("#([A-Za-z0-9_:\\-]+)");

    private static final Pattern POSITIONAL_INDEX = Pattern.compile("\\[\\s*\\d+\\s*]");

    /**
     * 按表达式形状推断定位方式。<b>只认两条毫无歧义的规则</b>，其余一律返回空。
     * <p>
     * 克制是刻意的：{@code ID} / {@code NAME} / {@code LINK_TEXT} 三者的值都是"一个普通字符串"，
     * 没有任何形状特征能区分。强行按"看起来像 id"去猜，错了以后执行器会用错误的方式找元素，
     * 报出来的却是"元素未找到"，指不回猜错的这一行。
     * <p>
     * L1 与 {@code atp_lint_locator} 共用这一份实现 —— 两处各写一份推断规则，
     * 迟早会出现"校验时按 CSS 判、填充时按 XPath 填"的不一致。
     */
    public static java.util.Optional<LocatorType> inferType(String value) {
        if (value == null || value.isBlank()) {
            return java.util.Optional.empty();
        }
        String expr = value.trim();
        if (expr.startsWith("/") || expr.startsWith("(/")) {
            return java.util.Optional.of(LocatorType.XPATH);
        }
        if (expr.startsWith("#") || expr.startsWith(".")) {
            return java.util.Optional.of(LocatorType.CSS);
        }
        return java.util.Optional.empty();
    }

    /**
     * 检查一个定位器。
     *
     * @param type  定位方式；为 null 时只做与类型无关的检查
     * @param value 定位器表达式
     * @param path  诊断中的字段路径，如 {@code steps[0].locator_value}
     */
    public List<Diagnostic> lint(LocatorType type, String value, String path) {
        List<Diagnostic> findings = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return findings;
        }
        String expr = value.trim();

        if (type == LocatorType.XPATH) {
            checkAbsoluteXPath(expr, path, findings);
        }
        checkDynamicId(type, expr, path, findings);
        checkStability(type, expr, path, findings);

        return findings;
    }

    /**
     * STD-001：禁止绝对路径 XPath。
     * <p>
     * 判据是「以单个 {@code /} 开头」—— {@code //div} 是 descendant-or-self（相对），
     * {@code /html/body/div} 才是绝对。也处理 {@code (/html/body)[1]} 这种带分组的写法。
     */
    private void checkAbsoluteXPath(String expr, String path, List<Diagnostic> findings) {
        String stripped = expr.startsWith("(") ? expr.substring(1).trim() : expr;
        boolean absolute = stripped.startsWith("/") && !stripped.startsWith("//");
        if (!absolute) {
            return;
        }
        findings.add(Diagnostic.error(DiagnosticCodes.STD_001_ABSOLUTE_XPATH, path,
                "绝对路径 XPath '" + expr + "' 依赖完整的 DOM 层级，"
              + "页面上任何一处结构调整都会让它失效。请改用基于稳定属性的相对路径，"
              + "例如 //*[@data-testid='...']。",
                "STD-001"));
    }

    /** STD-002：依赖自动生成的动态 id。 */
    private void checkDynamicId(LocatorType type, String expr, String path,
                                List<Diagnostic> findings) {
        String id = extractId(type, expr);
        if (id == null) {
            return;
        }
        for (Pattern pattern : DYNAMIC_ID_PATTERNS) {
            if (pattern.matcher(id).find()) {
                findings.add(Diagnostic.warn(DiagnosticCodes.STD_002_DYNAMIC_ID, path,
                        "id '" + id + "' 看起来是前端框架自动生成的，每次渲染都可能变化，"
                      + "依赖它的定位器会间歇性失效。若确认该 id 是后端稳定输出的，可忽略本条。",
                        "STD-002"));
                return;
            }
        }
    }

    private String extractId(LocatorType type, String expr) {
        if (type == LocatorType.ID) {
            return expr;
        }
        Matcher xpath = XPATH_ID.matcher(expr);
        if (xpath.find()) {
            return xpath.group(1);
        }
        if (type == LocatorType.CSS) {
            Matcher css = CSS_ID.matcher(expr);
            if (css.find()) {
                return css.group(1);
            }
        }
        return null;
    }

    /**
     * STD-003：优先使用稳定属性，优先级 {@code data-testid} &gt; {@code name} &gt; {@code class} &gt; 文本。
     * <p>
     * 已经用了 {@code data-testid} 或 {@code name} 就不再啰嗦 —— 建议类诊断一旦太吵，
     * 使用方就会开始整体忽略 INFO，连带真正有用的那些也被无视。
     */
    private void checkStability(LocatorType type, String expr, String path,
                                List<Diagnostic> findings) {
        String lower = expr.toLowerCase();

        boolean hasTestId = lower.contains("data-testid") || lower.contains("data-test")
                || lower.contains("data-cy");
        boolean hasName = lower.contains("@name") || lower.contains("[name")
                || type == LocatorType.NAME;
        if (hasTestId || hasName) {
            return;
        }

        boolean byText = lower.contains("text()") || type == LocatorType.LINK_TEXT;
        if (byText) {
            findings.add(Diagnostic.info(DiagnosticCodes.STD_003_UNSTABLE_LOCATOR, path,
                    "按文本定位在多语言界面下尤其脆弱：文案调整或切换语言就会失效。"
                  + "建议改用 data-testid。",
                    "STD-003"));
            return;
        }

        boolean byClass = lower.contains("@class") || (type == LocatorType.CSS
                && expr.matches(".*(^|[\\s>+~])\\.[A-Za-z].*"));
        if (byClass) {
            findings.add(Diagnostic.info(DiagnosticCodes.STD_003_UNSTABLE_LOCATOR, path,
                    "class 通常服务于样式，改版时经常被重命名或被 CSS-in-JS 生成为哈希值。"
                  + "建议改用 data-testid。",
                    "STD-003"));
            return;
        }

        if (POSITIONAL_INDEX.matcher(expr).find()) {
            findings.add(Diagnostic.info(DiagnosticCodes.STD_003_UNSTABLE_LOCATOR, path,
                    "位置索引对同级元素的增删非常敏感 —— 页面多一个元素就会指向别的目标。"
                  + "建议改用 data-testid。",
                    "STD-003"));
        }
    }
}
