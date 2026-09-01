package com.atp.runner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

import com.atp.platform.service.CaseQueryService;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 执行节点。
 *
 * <h3>为什么是独立进程</h3>
 *
 * 它要拉起真实浏览器，内存占用与主应用完全不是一个量级（单节点约 600MB，
 * 其中浏览器占一多半）。而且演示时「当场 kill 一个节点、看板显示掉线、任务被其他节点接管」
 * 这个动作，只有一进程一节点才做得出来 —— 线程池做不到，进程一死全都掉。
 *
 * <h3>部署位置</h3>
 *
 * 跟 mock-shop 一起跑在**家里的台式机**，云服务器上只有前端与主应用，
 * 两边通过 Tailscale 组网。云服务器 4C4G 扛不住三个浏览器实例，
 * 而台式机资源宽裕、环境现成。
 *
 * <p>⚠️ 录像产物必须**回传到云服务器** —— 面试官打开的是公网地址，
 * 他的浏览器不在 Tailscale 网络里，直接访问不到台式机。
 *
 * <h3>⚠️ 为什么组件扫描是白名单，不是 {@code com.atp}</h3>
 *
 * 执行节点和平台共用 {@code atp-platform-api}，但它<b>只需要其中一小块</b>：
 * 认领任务、写回结果、探查页面、读案例。扫整个 {@code com.atp} 会把平台侧的
 * service 一并实例化到节点进程里，后果不是"多占点内存"那么轻：
 *
 * <ul>
 *   <li>{@code UserAuthService} 有 {@code @Value("${atp.auth.demo-password}")} ——
 *       节点的 yml 里没有这项，于是<b>节点直接启动失败</b>：
 *       {@code Could not resolve placeholder 'atp.auth.demo-password'}</li>
 *   <li>它还带 {@code @PostConstruct}，会往库里种演示账号口令 ——
 *       <b>每起一个节点就种一遍</b>，而种口令根本不是执行节点该做的事</li>
 * </ul>
 *
 * 这个故障有很强的隐蔽性：节点进程跑的是 jar 的副本，只要不重启就一直活着。
 * 平台侧加登录功能那天节点就已经起不来了，但在下一次重启节点之前，
 * 看板上一切正常。<b>发现它的方式是"想多起几个节点"，不是任何报警。</b>
 *
 * <p>所以这里列白名单而不是加一条排除规则：排除规则只挡住今天这一个类，
 * 平台以后每加一个带 {@code @Value} 的 service，节点就会再崩一次。
 */
@SpringBootApplication
@EnableScheduling
@ComponentScan(basePackages = {
        "com.atp.runner",
        // 认领任务、写回结果 —— 与平台共用同一套队列语义
        "com.atp.platform.exec",
        // 页面探查：平台把请求转派过来，由节点这侧真的打开浏览器
        "com.atp.platform.inspect",
})
// 读案例与步骤。只要这一个 service，不要 service 包里的其余九个
@Import(CaseQueryService.class)
@MapperScan("com.atp.platform.mapper")
public class RunnerApplication {

    public static void main(String[] args) {
        SpringApplication.run(RunnerApplication.class, args);
    }
}
