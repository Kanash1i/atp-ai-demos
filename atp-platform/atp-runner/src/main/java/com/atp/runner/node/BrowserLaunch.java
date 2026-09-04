package com.atp.runner.node;

import com.atp.runner.RunnerProperties;
import com.microsoft.playwright.BrowserType;

/**
 * 浏览器启动参数。
 *
 * <h3>⚠️ 为什么要把 channel 判空单独拎出来</h3>
 *
 * {@code setChannel("chrome")} 要求机器上真的装了 Google Chrome。在开发机上成立，
 * 在 Playwright 官方容器镜像里<b>不成立</b> —— 那里面装的是 Playwright 自己构建的
 * chromium，没有 Google Chrome。
 *
 * <p>而这个差异的暴露时机很靠后：channel 是在<b>启动浏览器</b>时才校验的，
 * 不是启动进程时。节点会正常注册、正常心跳、在看板上显示为在线，
 * 只是每来一条任务就失败一条 —— 看起来像"案例写错了"，不像"节点装错了"。
 *
 * <p>所以留空是有意义的取值，不是"忘了配"：<b>空 = 用 Playwright 自带的 chromium</b>。
 */
final class BrowserLaunch {

    private BrowserLaunch() {
    }

    static BrowserType.LaunchOptions options(RunnerProperties props, boolean headless) {
        BrowserType.LaunchOptions opts = new BrowserType.LaunchOptions().setHeadless(headless);
        // ⚠️ 只影响观感，不影响判定 —— 自动等待与断言逻辑完全不变
        if (props.getSlowMoMs() > 0) {
            opts.setSlowMo(props.getSlowMoMs());
        }
        String channel = props.getBrowserChannel();
        // ⚠️ 判空而不是直接 setChannel(null)：Playwright 对 null 与"未设置"处理不同
        if (channel != null && !channel.isBlank()) {
            opts.setChannel(channel);
        }
        return opts;
    }
}
