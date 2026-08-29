package com.atp.runner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.ComponentScan;
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
 */
@SpringBootApplication
@EnableScheduling
@ComponentScan(basePackages = "com.atp")
@MapperScan("com.atp.platform.mapper")
public class RunnerApplication {

    public static void main(String[] args) {
        SpringApplication.run(RunnerApplication.class, args);
    }
}
