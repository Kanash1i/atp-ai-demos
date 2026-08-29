package com.atp.runner.exec;

import com.atp.common.enums.OnFailure;
import com.atp.common.enums.TaskStatus;
import com.atp.common.model.Step;
import com.atp.common.model.TestCase;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.RecordVideoSize;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 跑一条案例，产出结果与录像。
 *
 * <h3>录像的两个约束</h3>
 *
 * <b>尺寸压到 800×600。</b> 云服务器只有 2Mbps 出站带宽 ——
 * 按默认 viewport（1280×720）录，一条 40 秒的案例能出 2~5MB，面试官点开要等十几秒。
 * 压到 800×600 之后单条 200~500KB，两秒内出画面。
 *
 * <p><b>录像文件要等 context 关闭才写完整。</b> Playwright 是边跑边缓冲，
 * {@code close()} 之前去读那个路径，拿到的是残缺文件甚至空文件 ——
 * 而它不会报错，只是播放器打不开。所以 {@link #run} 里录像路径一定在 close 之后才取。
 */
@Slf4j
public class CaseRunner {

    private static final int VIDEO_WIDTH = 800;
    private static final int VIDEO_HEIGHT = 600;

    private final Browser browser;
    private final Path artifactDir;

    public CaseRunner(Browser browser, Path artifactDir) {
        this.browser = browser;
        this.artifactDir = artifactDir;
    }

    public CaseResult run(TestCase testCase, ExecutionContext ctx) {
        return run(testCase, ctx, safeName(testCase.caseCode()));
    }

    /**
     * @param subDir 本次执行的产物子目录。
     *               ⚠️ 必须**每次执行唯一**（调用方用 taskId 拼）。
     *               只按 caseCode 分的话，同一条案例重跑时新旧录像会落在同一个目录，
     *               而 {@link #findVideo} 取的是第一个 .webm —— 详情页可能播出上一次的录像。
     *               这不会报错，只会让人看着一段对不上的视频怀疑自己。
     */
    public CaseResult run(TestCase testCase, ExecutionContext ctx, String subDir) {
        long start = System.nanoTime();
        List<StepResult> results = new ArrayList<>();
        Path videoDir = artifactDir.resolve(subDir);

        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(VIDEO_WIDTH, VIDEO_HEIGHT)
                .setRecordVideoDir(videoDir)
                .setRecordVideoSize(new RecordVideoSize(VIDEO_WIDTH, VIDEO_HEIGHT))
                // 演示环境别让浏览器弹权限框、别记住密码
                .setIgnoreHTTPSErrors(true));

        Page page = context.newPage();
        StepExecutor executor = new StepExecutor(page, ctx);

        Integer failedSeq = null;
        String errorMsg = null;
        String screenshot = null;

        for (Step step : testCase.stepsOrEmpty()) {
            // 前面已经失败且 on_failure=ABORT 时，剩下的步骤记为 SKIPPED 而不是不记 ——
            // 失败详情页要能显示「后面这些没跑」，空白会让人以为案例只有那么几步
            if (failedSeq != null) {
                results.add(new StepResult(step.seq(), step.action() == null ? "UNKNOWN" : step.action().name(),
                        com.atp.common.enums.StepStatus.SKIPPED, 0,
                        "前一步失败，未执行", null));
                continue;
            }

            StepResult r = executor.execute(step);
            results.add(r);

            if (r.failed()) {
                log.warn("案例 {} 第 {} 步失败：{}", testCase.caseCode(), step.seq(), r.errorMsg());
                // 失败当场截图 —— 页面状态转瞬即逝，等跑完再截就已经不是现场了
                // ⚠️ 用执行器的当前页面，不是最初那个 —— SWITCH_WINDOW 之后现场在新窗口上
                screenshot = capture(executor.currentPage(), videoDir, step.seq());
                if (step.onFailure() == OnFailure.CONTINUE) {
                    // 记下第一处失败，但继续跑完 —— 有些案例故意要看后续步骤的表现
                    if (errorMsg == null) {
                        errorMsg = r.errorMsg();
                        failedSeq = null;   // 不中止
                    }
                    continue;
                }
                failedSeq = step.seq();
                errorMsg = r.errorMsg();
            }
        }

        // ⚠️ 先关 context 再取录像路径，理由见类注释
        context.close();

        boolean anyFailed = results.stream().anyMatch(StepResult::failed);
        TaskStatus status = anyFailed ? TaskStatus.FAILED : TaskStatus.PASSED;
        if (failedSeq == null && anyFailed) {
            // on_failure=CONTINUE 跑完全程但中间失败过：整体仍算失败，定位到第一处
            failedSeq = results.stream().filter(StepResult::failed).findFirst().map(StepResult::seq).orElse(null);
        }

        return new CaseResult(status, elapsedMs(start), failedSeq, errorMsg,
                findVideo(videoDir), screenshot, results);
    }

    private String capture(Page page, Path dir, int seq) {
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve("fail-step-%d.png".formatted(seq));
            page.screenshot(new Page.ScreenshotOptions().setPath(file));
            return file.toString();
        } catch (Exception e) {
            // 截图失败不该让整条案例的结果丢掉 —— 记一笔，继续
            log.warn("失败截图没能保存", e);
            return null;
        }
    }

    /** Playwright 用随机名写录像，跑完去目录里找那个 .webm */
    private String findVideo(Path dir) {
        try (var files = Files.list(dir)) {
            return files.filter(p -> p.toString().endsWith(".webm"))
                    .findFirst().map(Path::toString).orElse(null);
        } catch (Exception e) {
            log.warn("没找到录像文件：{}", dir, e);
            return null;
        }
    }

    private String safeName(String caseCode) {
        return caseCode == null ? "unknown" : caseCode.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private long elapsedMs(long startNano) {
        return (System.nanoTime() - startNano) / 1_000_000;
    }
}
