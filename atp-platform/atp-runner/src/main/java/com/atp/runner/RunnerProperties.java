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

    /** 浏览器渠道。chrome = 用系统已装的 Chrome，省掉 450MB 下载 */
    private String browserChannel = "chrome";

    private boolean headless = true;

    /** 执行环境的变量，如 base_url / test_user */
    private Map<String, String> variables = new LinkedHashMap<>();

    /** 执行环境的凭据，如 test_user_password */
    private Map<String, String> credentials = new LinkedHashMap<>();
}
