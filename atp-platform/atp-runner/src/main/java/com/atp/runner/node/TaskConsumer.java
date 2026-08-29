package com.atp.runner.node;

import com.atp.common.enums.TaskStatus;
import com.atp.common.model.TestCase;
import com.atp.platform.entity.ExecRun;
import com.atp.platform.entity.ExecTask;
import com.atp.platform.exec.ExecTaskClaimer;
import com.atp.platform.exec.ExecutionQueue;
import com.atp.platform.exec.ExecutionResultWriter;
import com.atp.platform.mapper.ExecRunMapper;
import com.atp.platform.service.CaseQueryService;
import com.atp.runner.RunnerProperties;
import com.atp.runner.exec.CaseResult;
import com.atp.runner.exec.CaseRunner;
import com.atp.runner.exec.ExecutionContext;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/**
 * 消费循环：取任务 → 认领 → 执行 → 回写。
 *
 * <h3>⚠️ Playwright 必须在同一个线程里创建和使用</h3>
 *
 * 它不是线程安全的（Java 客户端通过管道驱动一个 Node.js driver，
 * 那个连接绑在创建它的线程上）。所以这里用**单线程**跑整个循环，
 * Playwright 与 Browser 都在那个线程里创建，不做成 Spring bean ——
 * 做成 bean 的话由容器线程创建、由消费线程使用，会随机崩在难以复现的地方。
 *
 * <p>这也是「一进程一节点」的另一个理由：想要并发就多起进程，
 * 而不是在一个进程里开多线程去共享一个不能共享的对象。
 */
@Slf4j
@Component
public class TaskConsumer implements ApplicationRunner {

    @Autowired
    private ExecutionQueue queue;
    @Autowired
    private ExecTaskClaimer claimer;
    @Autowired
    private ExecutionResultWriter resultWriter;
    @Autowired
    private CaseQueryService caseQueryService;
    @Autowired
    private ExecRunMapper runMapper;
    @Autowired
    private NodeRegistrar registrar;
    @Autowired
    private RunnerProperties props;

    @Autowired
    private ArtifactUploader uploader;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "atp-runner-worker");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean running = true;

    @Override
    public void run(ApplicationArguments args) {
        worker.submit(this::loop);
        log.info("节点 {} 开始消费执行队列", props.getNodeName());
    }

    @PreDestroy
    public void stop() {
        running = false;
        worker.shutdownNow();
    }

    private void loop() {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setChannel(props.getBrowserChannel())
                    .setHeadless(props.isHeadless()));
            log.info("浏览器就绪：channel={} headless={}", props.getBrowserChannel(), props.isHeadless());

            while (running) {
                try {
                    pollOnce(browser);
                } catch (Exception e) {
                    // ⚠️ 单条任务的失败不能让整个循环退出 —— 节点会变成「在线但不干活」，
                    //    比直接挂掉更难发现。记日志，继续下一条。
                    log.error("处理任务时出错，继续下一条", e);
                }
            }
            browser.close();
        } catch (Exception e) {
            log.error("消费循环异常退出，节点将停止接活", e);
        }
    }

    private void pollOnce(Browser browser) {
        String taskId = queue.take(props.getPollTimeoutSeconds());
        if (taskId == null) {
            // 阻塞超时：借这个机会刷一次心跳，让看板知道我们还活着
            registrar.beat();
            return;
        }

        ExecTask task = claimer.claim(taskId, props.getNodeName());
        if (task == null) {
            // 正常现象：被别的节点抢先，或批次已中止
            return;
        }

        registrar.busy(taskId);
        try {
            execute(browser, task);
        } catch (Exception e) {
            // ⭐ 兜底收尾：**任何**异常路径都必须让任务落地成 FAILED。
            //    否则它会永远挂在 RUNNING —— 批次的
            //    passed+failed+skipped 永远凑不齐 total，整个批次收不了尾，
            //    看板上那个进度条会一直停在 9/10。
            //    这类「少一条」的故障不会报错，只会让人盯着进度条等下去。
            log.error("任务 {} 执行时异常，标记为失败", task.getCaseCode(), e);
            safeFail(task, e);
        } finally {
            registrar.idle();
        }
    }

    private void execute(Browser browser, ExecTask task) {
        ExecRun run = runMapper.selectById(task.getRunId());
        String runCode = run == null ? "unknown" : run.getRunCode();
        log.info("[{}] 开始执行 {} （批次 {}）", props.getNodeName(), task.getCaseCode(), runCode);

        TestCase testCase;
        try {
            testCase = caseQueryService.loadDomain(task.getCaseId());
        } catch (Exception e) {
            // 案例读不出来（被删了、step_json 坏了）→ 记为失败，别让它一直挂着 RUNNING
            resultWriter.finish(task.getTaskId(), TaskStatus.FAILED, 0, null,
                    "读取案例失败：" + e.getMessage(), null, null, List.of());
            return;
        }

        Path runDir = Path.of(props.getArtifactDir(), safe(runCode));
        // 产物子目录带上 taskId 前 8 位：同一条案例重跑时新旧录像不会混在一起
        String subDir = safe(task.getCaseCode()) + "-" + task.getTaskId().substring(0, 8);
        CaseResult result = new CaseRunner(browser, runDir)
                .run(testCase, new ExecutionContext(props.getVariables(), props.getCredentials(),
                        props.getTestdataDir()), subDir);

        // 产物先传回主应用，再把 URL 写进结果 ——
        // 顺序不能反：URL 落库了但文件没传上去的话，详情页点开就是 404
        resultWriter.finish(
                task.getTaskId(), result.status(), result.durationMs(), result.failedSeq(),
                result.errorMsg(),
                uploader.upload(result.videoPath(), relativeOf(result.videoPath())),
                uploader.upload(result.screenshotPath(), relativeOf(result.screenshotPath())),
                result.steps().stream()
                        .map(s -> new ExecutionResultWriter.StepRecord(
                                s.seq(), s.action(), s.status(), s.durationMs(), s.errorMsg(), null))
                        .toList());

        log.info("[{}] {} → {} （{}ms）", props.getNodeName(), task.getCaseCode(),
                result.status(), result.durationMs());
    }

    /** 兜底收尾。这里再抛异常就真没救了，只能记日志 */
    private void safeFail(ExecTask task, Exception cause) {
        try {
            resultWriter.finish(task.getTaskId(), TaskStatus.FAILED, 0, null,
                    "执行器异常：" + cause.getMessage(), null, null, List.of());
        } catch (Exception e) {
            log.error("任务 {} 连失败状态都没写进去，它会一直挂在 RUNNING", task.getTaskId(), e);
        }
    }

    /** 本地绝对路径 → 相对于产物根的路径，用作上传时的资源名 */
    private String relativeOf(String absolutePath) {
        if (absolutePath == null) {
            return null;
        }
        Path root = Path.of(props.getArtifactDir()).toAbsolutePath().normalize();
        Path file = Path.of(absolutePath).toAbsolutePath().normalize();
        // ⚠️ 统一成正斜杠：节点可能跑在 Windows 台式机上，反斜杠拼进 URL 会直接 404
        return root.relativize(file).toString().replace('\\', '/');
    }

    private String safe(String s) {
        return s == null ? "unknown" : s.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
