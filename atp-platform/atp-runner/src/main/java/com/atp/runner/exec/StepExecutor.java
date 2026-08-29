package com.atp.runner.exec;

import com.atp.common.enums.ActionType;
import com.atp.common.enums.StepStatus;
import com.atp.common.enums.WaitStrategy;
import com.atp.common.model.Step;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.List;

/**
 * 把一个 {@link Step} 翻译成 Playwright 调用。
 *
 * <p>13 个 Action 全部实现 —— 包括规范禁止的 {@code SLEEP}：
 * 存量案例里有 3 条在用它，跑不起来的话「脏案例也能执行、只是被校验器标红」这条演示线就断了。
 * <b>执行器忠实执行，校验器负责拦新案例</b>，这两件事必须分开。
 */
@Slf4j
public class StepExecutor {

    /** 等待策略的默认超时。步骤自带 wait_timeout_sec 时用它自己的 */
    private static final int DEFAULT_TIMEOUT_SEC = 10;

    /**
     * 当前操作的页面。
     *
     * <p>⚠️ **不是 final** —— {@code SWITCH_WINDOW} 之后，后续所有步骤都要落到新窗口上。
     * 只把新窗口 {@code bringToFront()} 而不换这个引用的话，
     * 断言仍然在老窗口上找元素，报出来的是「元素找不到」——
     * 而真正的原因是「你根本没切过去」。何况 headless 下 bringToFront 几乎没有意义。
     */
    private Page current;
    private final ExecutionContext ctx;

    public StepExecutor(Page page, ExecutionContext ctx) {
        this.current = page;
        this.ctx = ctx;
    }

    /** 当前页面。失败截图要截的是**切换之后**的那个窗口 */
    public Page currentPage() {
        return current;
    }

    public StepResult execute(Step step) {
        long start = System.nanoTime();
        // ⚠️ describe 也放进 try：它内部要解析占位符，本身也可能抛。
        //    放在外面的话，一个「变量未定义」会直接逃出这个方法，
        //    让整条任务连 FAILED 都记不上，永远挂在 RUNNING。
        String detail = null;
        try {
            detail = describe(step);
            perform(step);
            return new StepResult(step.seq(), name(step), StepStatus.PASSED,
                    elapsedMs(start), detail, null);
        } catch (Exception e) {
            // ⚠️ 错误信息要过 describe —— 异常里可能带着刚填进去的值
            String msg = ctx.describe(rootMessage(e));
            return new StepResult(step.seq(), name(step), StepStatus.FAILED,
                    elapsedMs(start), detail, msg);
        }
    }

    private void perform(Step step) {
        ActionType action = step.action();
        if (action == null) {
            throw new IllegalStateException("步骤 " + step.seq() + " 没有 action");
        }
        switch (action) {
            case OPEN_URL -> current.navigate(ctx.resolve(step.inputData()));
            case CLICK -> locator(step).click();
            case INPUT -> locator(step).fill(ctx.resolve(step.inputData()));
            case SELECT -> locator(step).selectOption(ctx.resolve(step.inputData()));
            case ASSERT_TEXT -> assertText(step);
            case ASSERT_VISIBLE -> assertVisible(step);
            case ASSERT_NOT_EXIST -> assertNotExist(step);
            case WAIT_FOR -> locator(step);   // locator() 内部已按 wait_strategy 等过了
            case SCROLL_TO -> locator(step).scrollIntoViewIfNeeded();
            case SWITCH_FRAME -> throw new UnsupportedOperationException(
                    "SWITCH_FRAME 需要在 CaseRunner 层切换 frame 上下文，当前案例集未用到");
            case SWITCH_WINDOW -> switchWindow(step);
            case UPLOAD -> locator(step).setInputFiles(Path.of(ctx.resolveFile(step.inputData())));
            // ⚠️ 规范禁止，但必须能跑 —— 理由见类注释
            case SLEEP -> sleep(step);
        }
    }

    /**
     * 取定位器，并按 {@code wait_strategy} 等到该等的状态。
     *
     * <p>⭐ 等待做在这里而不是每个 action 里：13 个 action 有 10 个要定位元素，
     * 分散写必然漏掉几个，而漏掉的表现是「偶发失败」—— 最难查的那种。
     */
    private Locator locator(Step step) {
        if (!step.hasLocator()) {
            throw new IllegalStateException("步骤 " + step.seq() + " 的 " + step.action() + " 需要定位器");
        }
        // 存量案例几乎全是 XPath；CSS 的写法也支持
        String raw = ctx.resolve(step.locatorValue());
        Locator locator = switch (step.locatorType()) {
            case CSS -> current.locator(raw);
            case ID -> current.locator("#" + raw);
            case NAME -> current.locator("[name='" + raw + "']");
            case LINK_TEXT -> current.getByText(raw, new Page.GetByTextOptions().setExact(true));
            case null, default -> current.locator("xpath=" + raw);
        };

        double timeout = timeoutMs(step);
        WaitStrategy strategy = step.waitStrategy() == null ? WaitStrategy.NONE : step.waitStrategy();
        switch (strategy) {
            case NONE -> { }
            case PRESENCE -> locator.first().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.ATTACHED).setTimeout(timeout));
            case VISIBLE -> locator.first().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE).setTimeout(timeout));
            // ⚠️ Playwright 没有 CLICKABLE 这个状态：可见 ≠ 可点。
            //    遮罩层还没消失、按钮还 disabled 的时候点击会被吃掉，
            //    所以可见之后还要轮询 isEnabled —— 这正是 STD-005 要求 CLICK 用 CLICKABLE 的原因。
            case CLICKABLE -> {
                locator.first().waitFor(new Locator.WaitForOptions()
                        .setState(WaitForSelectorState.VISIBLE).setTimeout(timeout));
                waitEnabled(locator.first(), (long) timeout);
            }
        }
        return locator.first();
    }

    private void waitEnabled(Locator locator, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (locator.isEnabled()) {
                return;
            }
            current.waitForTimeout(100);
        }
        throw new IllegalStateException("元素在 " + timeoutMs + "ms 内始终不可点击（disabled）");
    }

    /**
     * 断言文本。
     *
     * <p>⚠️ {@code <input>} 上没有 textContent，要读 value ——
     * 存量案例真有这种写法（ATP-LOGIN-0009 断言 {@code input[data-testid="redirect-hint"]}）。
     * 不特判的话拿到空字符串，报「期望 /mypage 实际空」，看着像页面没渲染，其实是读错了属性。
     */
    /** 捕获语法：expected 写成 {@code ->varName} 时不断言，把实际值记进变量 */
    private static final String CAPTURE_PREFIX = "->";

    private void assertText(Step step) {
        Locator locator = locator(step);
        String tag = locator.evaluate("el => el.tagName.toLowerCase()").toString();
        String actual = List.of("input", "textarea", "select").contains(tag)
                ? locator.inputValue()
                : locator.textContent();
        actual = actual == null ? "" : actual.trim();

        // ⭐ 捕获而非断言
        String raw = step.expected();
        if (raw != null && raw.startsWith(CAPTURE_PREFIX)) {
            ctx.capture(raw.substring(CAPTURE_PREFIX.length()).trim(), actual);
            return;
        }

        String expected = ctx.resolve(raw).trim();
        // 包含而非全等：页面上常有「ようこそ、田中 直樹 さん」这类包裹文案，
        // 而案例的期望值是其中的核心部分
        if (!actual.contains(expected)) {
            throw new AssertionError("期望文本「%s」，实际「%s」".formatted(expected, actual));
        }
    }

    private void assertVisible(Step step) {
        if (!locator(step).isVisible()) {
            throw new AssertionError("元素存在但不可见：" + ctx.describe(step.locatorValue()));
        }
    }

    /**
     * 断言元素不存在。
     *
     * <p>⚠️ 这里**不能**走 {@link #locator} —— 那会按 wait_strategy 等元素出现，
     * 而我们要的恰恰是它不出现。等待超时会抛异常，断言就永远失败了。
     */
    private void assertNotExist(Step step) {
        String raw = ctx.resolve(step.locatorValue());
        Locator locator = step.locatorType() == com.atp.common.enums.LocatorType.CSS
                ? current.locator(raw) : current.locator("xpath=" + raw);
        int count = locator.count();
        if (count > 0 && locator.first().isVisible()) {
            throw new AssertionError("元素本不该存在，却找到 " + count + " 个且可见");
        }
    }

    /**
     * 切换窗口。
     *
     * <p>⚠️ 要轮询等待：{@code target=_blank} 的新窗口在 click 返回时往往还没加载完标题，
     * 立刻遍历 pages() 会扑空。这不是偶发 —— 是必然的时序问题，只是有时候机器快就蒙对了。
     */
    private void switchWindow(Step step) {
        String target = ctx.resolve(step.inputData());
        long deadline = System.currentTimeMillis() + (long) timeoutMs(step);

        while (System.currentTimeMillis() < deadline) {
            for (Page candidate : current.context().pages()) {
                String title;
                try {
                    title = candidate.title();
                } catch (Exception ignored) {
                    continue;   // 页面还在导航中，下一轮再看
                }
                if (title != null && title.contains(target)) {
                    candidate.bringToFront();
                    current = candidate;   // ⭐ 关键：后续步骤都落到这个窗口上
                    return;
                }
            }
            current.waitForTimeout(200);
        }
        String titles = current.context().pages().stream()
                .map(p -> {
                    try {
                        return "「" + p.title() + "」";
                    } catch (Exception e) {
                        return "「?」";
                    }
                })
                .reduce("", (a, b) -> a.isEmpty() ? b : a + ", " + b);
        throw new IllegalStateException(
                "找不到标题包含「%s」的窗口。当前打开的窗口：%s".formatted(target, titles));
    }

    private void sleep(Step step) {
        double seconds = Double.parseDouble(ctx.resolve(step.inputData()).trim());
        log.debug("步骤 {} 使用了 SLEEP {}s —— 规范禁止，仅历史案例", step.seq(), seconds);
        current.waitForTimeout(seconds * 1000);
    }

    private double timeoutMs(Step step) {
        int sec = step.waitTimeoutSec() == null ? DEFAULT_TIMEOUT_SEC : step.waitTimeoutSec();
        return sec * 1000.0;
    }

    /** 步骤描述。凭据已脱敏 */
    private String describe(Step step) {
        StringBuilder sb = new StringBuilder(name(step));
        if (step.hasLocator()) {
            sb.append(' ').append(ctx.describe(step.locatorValue()));
        }
        if (step.hasInput()) {
            sb.append(" ← ").append(ctx.describe(step.inputData()));
        }
        if (step.hasExpected()) {
            String raw = step.expected();
            if (raw.startsWith(CAPTURE_PREFIX)) {
                sb.append(" 捕获→").append(raw.substring(CAPTURE_PREFIX.length()).trim());
            } else {
                sb.append(" 期望「").append(ctx.describe(raw)).append('」');
            }
        }
        return sb.toString();
    }

    private String name(Step step) {
        return step.action() == null ? "UNKNOWN" : step.action().name();
    }

    private long elapsedMs(long startNano) {
        return (System.nanoTime() - startNano) / 1_000_000;
    }

    private String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getMessage() == null) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        String type = cur.getClass().getSimpleName();
        // Playwright 的异常信息很长（带整段 selector 和调用栈），截断到能看懂即可
        if (msg != null && msg.length() > 300) {
            msg = msg.substring(0, 300) + "…";
        }
        return type + ": " + msg;
    }
}
