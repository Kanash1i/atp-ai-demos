package com.atp.common.validation;

import com.atp.common.enums.ActionType;
import com.atp.common.enums.LocatorType;
import com.atp.common.enums.StdCode;
import com.atp.common.enums.WaitStrategy;
import com.atp.common.model.Step;
import com.atp.common.model.TestCase;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * ATP 公司规范（STD-001 ~ STD-008）的校验器。
 *
 * <h3>为什么这个类住在 atp-common</h3>
 *
 * 它是**两条 AI 赋能路线的共同守门人**：
 * <ul>
 *   <li>激进路线：agent 生成案例后由 {@code StandardsGateHook} 调用，ERROR 直接拦下 ——
 *       不给 LLM 绕过的机会。<b>规则是硬的，LLM 只在规则之内自由。</b></li>
 *   <li>保守路线：{@code demo2-atp-cli} 里有一份 Go 的等价实现，
 *       opencode 生成的案例在 CLI 侧被同样的规则拦一次。</li>
 * </ul>
 *
 * <p>⚠️ 两处实现必须同码同义。它们校验的是同一张表里的同一批数据 ——
 * 一边放行、另一边拦下，是最难解释的那类不一致。
 *
 * <h3>无状态</h3>
 * 没有字段，方法可重入。所以可以做成单例 Bean，也可以直接 new。
 */
public final class StandardsValidator {

    /** STD-007：{@code ATP-{MODULE}-{4位序号}}。⚠️ 末尾必须收口，否则 ATP-ADMIN-0011-V2 会被放过 */
    private static final Pattern CASE_CODE = Pattern.compile("^ATP-[A-Z]+-\\d{4}$");

    /** STD-001：绝对路径 XPath —— 从文档根一级级点下来，页面结构一变就失效 */
    private static final Pattern ABSOLUTE_XPATH = Pattern.compile("^/html(/|$)", Pattern.CASE_INSENSITIVE);

    /**
     * STD-002：UI 框架自动生成的 id。
     *
     * <p>三种在存量案例里真实出现过的：ExtJS 的 {@code ext-gen1234}、
     * Angular Material 的 {@code mat-input-7}、Element UI 的 {@code el-id-8237-14}。
     * 它们的共同点是「框架前缀 + 递增数字」—— 数字随页面渲染顺序变，
     * 今天能跑，明天加个组件就错位。
     */
    private static final Pattern FRAMEWORK_ID = Pattern.compile(
            "(?i)(ext-gen|mat-[a-z]+|el-id|gwt-uid|yui_|ember\\d|ng-[a-z]+|uid)[-_]?\\d+");

    /** 兜底：id 值以「分隔符 + 数字」收尾，同样是自动生成的典型特征 */
    private static final Pattern TRAILING_SEQ_ID = Pattern.compile("^[A-Za-z]+[-_][A-Za-z-_]*[-_]\\d+$");

    /** 从 XPath / CSS 里抠出 id 属性的值 */
    private static final Pattern ID_ATTR = Pattern.compile("@id\\s*=\\s*[\"']([^\"']+)[\"']|#([A-Za-z][\\w-]*)");

    public ValidationResult validate(TestCase testCase) {
        List<Finding> findings = new ArrayList<>();

        checkCaseCode(testCase, findings);
        checkHasAssertion(testCase, findings);

        for (Step step : testCase.stepsOrEmpty()) {
            checkRequiredFields(step, findings);
            checkBannedAction(step, findings);
            checkWaitStrategy(step, findings);
            checkLocator(step, findings);
        }
        return new ValidationResult(List.copyOf(findings));
    }

    // ── 案例级 ────────────────────────────────────────────────

    /** STD-007 编号规范 */
    private void checkCaseCode(TestCase testCase, List<Finding> out) {
        String code = testCase.caseCode();
        if (code == null || code.isBlank()) {
            // 编写中的草稿还没有编号，这不算违规 —— commit 时数据库的 ck_case_complete 会拦
            return;
        }
        if (!CASE_CODE.matcher(code).matches()) {
            out.add(new Finding(StdCode.STD_007, null,
                    "案例编号 " + code + " 不符合 ATP-{MODULE}-{4位序号}，平台的编号规则不认它"));
        }
    }

    /** STD-008 至少一个断言 */
    private void checkHasAssertion(TestCase testCase, List<Finding> out) {
        boolean hasAssertion = testCase.stepsOrEmpty().stream()
                .anyMatch(s -> s.action() != null && s.action().isAssertion());
        if (!hasAssertion) {
            out.add(new Finding(StdCode.STD_008, null,
                    "整条案例没有任何 ASSERT_* 步骤 —— 它只是在点页面，跑通了也证明不了什么"));
        }
    }

    // ── 步骤级 ────────────────────────────────────────────────

    /**
     * 动作的必填字段。
     *
     * <p>这一条不属于 STD-001~008，但它是执行器能不能跑的前提，
     * 归到 STD-003（定位器规约）这一档下报告。
     */
    private void checkRequiredFields(Step step, List<Finding> out) {
        ActionType action = step.action();
        if (action == null) {
            return;
        }
        if (action.requiresLocator() && !step.hasLocator()) {
            out.add(new Finding(StdCode.STD_003, step.seq(),
                    "第 " + step.seq() + " 步 " + action + " 需要定位器，但没有填"));
        }
        if (action.requiresInput() && !step.hasInput()) {
            out.add(new Finding(StdCode.STD_003, step.seq(),
                    "第 " + step.seq() + " 步 " + action + " 需要 input_data，但没有填"));
        }
        if (action.requiresExpected() && !step.hasExpected()) {
            out.add(new Finding(StdCode.STD_003, step.seq(),
                    "第 " + step.seq() + " 步 " + action + " 需要 expected，否则断言不出结果"));
        }
    }

    /** STD-004 禁止 SLEEP */
    private void checkBannedAction(Step step, List<Finding> out) {
        if (step.action() != null && step.action().isBanned()) {
            out.add(new Finding(StdCode.STD_004, step.seq(),
                    "第 " + step.seq() + " 步用了 SLEEP。固定等待在慢的时候不够、在快的时候纯浪费，"
                            + "而且失败会看起来像时序问题，根因就追不下去了 —— 换成显式等待"));
        }
    }

    /** STD-005 / STD-006 等待策略 */
    private void checkWaitStrategy(Step step, List<Finding> out) {
        ActionType action = step.action();
        if (action == null) {
            return;
        }
        WaitStrategy wait = step.waitStrategy();

        if (action == ActionType.CLICK && wait != WaitStrategy.CLICKABLE) {
            out.add(new Finding(StdCode.STD_005, step.seq(),
                    "第 " + step.seq() + " 步是 CLICK，wait_strategy 必须是 CLICKABLE（当前 " + wait + "）。"
                            + "元素在 DOM 里存在不代表能点 —— 遮罩层还没消失时点击会被吃掉"));
        }
        if (action.isAssertion() && wait != WaitStrategy.VISIBLE) {
            out.add(new Finding(StdCode.STD_006, step.seq(),
                    "第 " + step.seq() + " 步是断言，wait_strategy 必须是 VISIBLE（当前 " + wait + "）"));
        }
    }

    /** STD-001 / STD-002 / STD-003 定位器 */
    private void checkLocator(Step step, List<Finding> out) {
        if (!step.hasLocator()) {
            return;
        }
        String locator = step.locatorValue();

        if (step.locatorType() == LocatorType.XPATH && ABSOLUTE_XPATH.matcher(locator).find()) {
            out.add(new Finding(StdCode.STD_001, step.seq(),
                    "第 " + step.seq() + " 步的 XPath 是从 /html 开始的绝对路径，"
                            + "页面上任何一处结构调整都会让它指向别的元素"));
        }

        String id = extractId(locator);
        if (id != null && isDynamicId(id)) {
            out.add(new Finding(StdCode.STD_002, step.seq(),
                    "第 " + step.seq() + " 步依赖了 id=\"" + id + "\"，"
                            + "它是组件库自动生成的，数字会随渲染顺序变"));
        }

        // STD-003 是建议档：有更稳的选择时提一句，不拦人
        if (step.locatorType() == LocatorType.XPATH && !locator.contains("data-testid")) {
            out.add(new Finding(StdCode.STD_003, step.seq(),
                    "第 " + step.seq() + " 步可以改用 data-testid 定位，比结构和文本都稳"));
        }
    }

    /** 从定位器里抠出 id 值；没有 id 返回 null */
    private String extractId(String locator) {
        var m = ID_ATTR.matcher(locator);
        if (!m.find()) {
            return null;
        }
        return m.group(1) != null ? m.group(1) : m.group(2);
    }

    private boolean isDynamicId(String id) {
        return FRAMEWORK_ID.matcher(id).find() || TRAILING_SEQ_ID.matcher(id).matches();
    }
}
