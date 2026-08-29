package com.atp.web;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ATP 平台主应用。
 *
 * <p>⚠️ 执行器（{@code atp-runner}）是**另一个进程**，不在这里启动 ——
 * 它要拉起真实浏览器，资源特征与主应用完全不同，而且演示时要能横向起多个节点。
 */
@SpringBootApplication
@EnableScheduling
@ComponentScan(basePackages = "com.atp")
@MapperScan("com.atp.platform.mapper")
public class AtpPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(AtpPlatformApplication.class, args);
    }
}
