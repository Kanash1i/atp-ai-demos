package com.atp.runner.exec;

import com.atp.common.enums.TaskStatus;
import com.atp.common.model.TestCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 拿真实的存量案例跑真实的浏览器 —— M2 的核心验证。
 *
 * <p>⚠️ 需要 mock-shop 在跑（{@code mvn -pl mock-shop spring-boot:run}）。
 * 不在跑时整个类跳过，而不是失败 —— 它依赖外部进程，红一片会掩盖真正的回归。
 *
 * <p>⭐ 用系统 Chrome（{@code setChannel("chrome")}）而不是 Playwright 自带的浏览器：
 * 省掉 450MB 下载。被测页面是我们自己写的静态页，不碰边缘 API，版本差异的风险接近于零。
 */
class CaseRunnerPlaywrightTest {

    private static final String BASE_URL = System.getProperty("mockShopUrl", "http://localhost:8088");
    private static final Path SEED = Path.of("../../seed/cases");

    private static Playwright playwright;
    private static Browser browser;
    private static Path artifactDir;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    static void setUp() throws IOException {
        assumeTrue(reachable(BASE_URL + "/login"),
                "mock-shop 未启动（" + BASE_URL + "），跳过。启动：mvn -pl mock-shop spring-boot:run");
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setChannel("chrome")
                .setHeadless(true));
        artifactDir = Files.createTempDirectory("atp-artifacts");
    }

    @AfterAll
    static void tearDown() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    /** 执行环境。⚠️ 口令走 credentials，不进变量表 —— 它不该出现在任何日志里 */
    private ExecutionContext context() {
        return new ExecutionContext(
                Map.of("base_url", BASE_URL,
                        "test_user", "tanaka@example.jp",
                        "test_user_nickname", "田中 直樹",
                        "lockout_test_user", "locked@example.jp",
                        "pending_order_no", "ORD-20260830-0001",
                        "delivered_order_no", "ORD-20260829-0002"),
                Map.of("test_user_password", "Passw0rd!",
                        "lockout_wrong_pwd", "whatever"));
    }

    private TestCase load(String caseCode) throws IOException {
        return json.readValue(SEED.resolve(caseCode + ".json").toFile(), TestCase.class);
    }

    @Test
    @DisplayName("ATP-LOGIN-0002 正常登录 —— 应当通过，并录下视频")
    void happyPath() throws IOException {
        CaseResult result = new CaseRunner(browser, artifactDir).run(load("ATP-LOGIN-0002"), context());

        result.steps().forEach(s -> System.out.printf("  %d. %-16s %-8s %4dms  %s%n",
                s.seq(), s.action(), s.status(), s.durationMs(),
                s.errorMsg() == null ? s.detail() : s.errorMsg()));

        assertEquals(TaskStatus.PASSED, result.status(), "正常登录应当通过");
        assertNotNull(result.videoPath(), "应当录到视频");
        assertTrue(Files.exists(Path.of(result.videoPath())), "录像文件应当真的存在");
        System.out.printf("录像 %s（%.1f KB）%n", result.videoPath(),
                Files.size(Path.of(result.videoPath())) / 1024.0);
    }

    @Test
    @DisplayName("ATP-LOGIN-0003 密码错误 —— 断言错误提示，应当通过")
    void wrongPassword() throws IOException {
        CaseResult result = new CaseRunner(browser, artifactDir).run(load("ATP-LOGIN-0003"), context());
        result.steps().forEach(s -> System.out.printf("  %d. %-16s %-8s  %s%n",
                s.seq(), s.action(), s.status(), s.errorMsg() == null ? s.detail() : s.errorMsg()));
        assertEquals(TaskStatus.PASSED, result.status());
    }

    @Test
    @DisplayName("ATP-ORDER-0009 —— 用执行器自己的代码路径复现列表页点击")
    void orderPay() throws IOException {
        CaseResult result = new CaseRunner(browser, artifactDir).run(load("ATP-ORDER-0009"), context());
        result.steps().forEach(s -> System.out.printf("  %d. %-14s %-8s %5dms  %s%n",
                s.seq(), s.action(), s.status(), s.durationMs(),
                s.errorMsg() == null ? s.detail() : s.errorMsg().split("\n")[0]));
        assertEquals(TaskStatus.PASSED, result.status());
    }

    @Test
    @DisplayName("凭据不进日志：步骤描述里口令必须是 ***")
    void credentialsAreMasked() throws IOException {
        CaseResult result = new CaseRunner(browser, artifactDir).run(load("ATP-LOGIN-0002"), context());
        String allDetails = result.steps().stream().map(StepResult::detail).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(allDetails.contains("***"), "口令步骤应当渲染成 ***");
        assertFalse(allDetails.contains("Passw0rd!"), "明文口令绝不能出现在步骤描述里");
    }

    private static boolean reachable(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(1500);
            return c.getResponseCode() < 500;
        } catch (Exception e) {
            return false;
        }
    }

    private static void assertTrue(boolean cond, String msg) {
        org.junit.jupiter.api.Assertions.assertTrue(cond, msg);
    }

    private static void assertFalse(boolean cond, String msg) {
        org.junit.jupiter.api.Assertions.assertFalse(cond, msg);
    }
}
