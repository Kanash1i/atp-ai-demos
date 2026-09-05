package com.atp.runner;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 执行节点的配置。
 *
 * <p>⚠️ {@code credentials} 里是口令 —— 它从环境变量注入（{@code .env}），
 * 不写进 yml、不进版本库。执行日志里也只会出现 {@code ***}（见 ExecutionContext）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "atp.runner")
public class RunnerProperties {

    /**
     * 节点名，如 node-01。
     * ⚠️ 一进程一节点，起多个节点就用不同的值启动多次 ——
     * 演示时 kill 掉其中一个，看板会显示它掉线，任务由其余节点接管。
     */
    private String nodeName = "node-01";

    /**
     * 测试数据目录。案例里的 {@code /testdata/xxx} 会映射到这里。
     * ⚠️ 案例不该写死某台机器上的绝对路径 —— 它要在 Linux 笔记本和 Windows 台式机上都能跑。
     */
    private String testdataDir = "/tmp/atp-testdata";

    /** 产物根目录（录像、失败截图） */
    private String artifactDir = "/tmp/atp-artifacts";

    /**
     * 是否把产物上传到主应用。
     *
     * ⚠️ 跨机部署时**必须开** —— 面试官的浏览器不在 Tailscale 网里，读不到台式机磁盘。
     * 同机调试时可以关掉，省一次网络往返。
     */
    private boolean uploadEnabled = true;

    /** 主应用地址，产物往这里传 */
    private String platformUrl = "http://localhost:8080";

    /** 心跳间隔（秒）。看板判定在线的阈值是 2 分钟，留足余量 */
    private int heartbeatSeconds = 30;

    /** 取任务的阻塞时长（秒）。到点返回 null，让循环有机会检查停止标志与刷心跳 */
    private int pollTimeoutSeconds = 5;

    /**
     * 浏览器渠道。{@code chrome} = 用系统已装的 Chrome，省掉 450MB 下载。
     *
     * <p>⚠️ <b>留空 = 用 Playwright 自带的 chromium。</b> 容器里必须留空 ——
     * Playwright 官方镜像自带的是它自己构建的 chromium，并没有 Google Chrome，
     * 而 {@code setChannel("chrome")} 在那种环境下会在<b>启动浏览器时</b>才失败，
     * 不是在启动进程时：节点能正常注册、心跳正常、看板显示在线，
     * 只是每来一条任务就失败一条。
     */
    private String browserChannel = "chrome";

    private boolean headless = true;

    /**
     * 每个浏览器操作之间的停顿（毫秒）。0 = 不停顿。
     *
     * <h3>为什么需要它</h3>
     *
     * 被测系统与执行节点在同一个 docker 网络里，一条案例常常 200ms 就跑完了 ——
     * 录下来是不到一秒的视频，人眼根本看不清发生了什么。
     *
     * <p>⚠️ 它只影响**观感**，不影响结果：Playwright 的自动等待照常工作，
     * 断言该失败还是失败。所以调大它不会把不稳定的案例「等」成通过。
     *
     * <h3>⚠️ 默认 0 —— 它会污染耗时数据</h3>
     *
     * 一开始默认给了 250ms，实测发现代价比预想大得多：
     *
     * <pre>
     *                        ATP-LOGIN-0001(SLEEP)  vs  0002(显式等待)
     *   slowMo=0              3.53s                     0.31s      12.9x
     *   slowMo=250           28.79s                    24.04s       1.8x
     * </pre>
     *
     * slowMo 对<b>每一个 Playwright 操作</b>生效 —— 不只是步骤数，
     * 还有内部的等待与断言。所以它不是给每条案例加一个固定基数，
     * 而是<b>按操作次数放大</b>，把「SLEEP 比显式等待慢一个量级」这个
     * 本来很干净的对照稀释成了 1.8 倍。
     *
     * <p>耗时是这个平台的一等数据（看板的平均耗时、案例间的对照都靠它），
     * 不能为了录像好看去动它。要录好看的录像就单跑一条时显式开：
     * {@code ATP_SLOWMO_MS=300}。
     */
    private int slowMoMs = 0;

    /** 执行环境的变量，如 base_url / test_user */
    private Map<String, String> variables = new LinkedHashMap<>();

    /** 执行环境的凭据，如 test_user_password */
    private Map<String, String> credentials = new LinkedHashMap<>();
}
