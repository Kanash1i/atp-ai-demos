package com.atp.web;

import com.atp.platform.seed.ExecutionSeed;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 让执行看板的演示数据永远停在「今天」。
 *
 * <h3>为什么需要这个</h3>
 *
 * 执行历史是按「造它那一刻的今天」生成的。跨过零点之后，
 * 「今日执行」这张卡片就变成 0、「最近执行结果」的时间戳停在昨天 ——
 * 看板一夜之间空掉。演示当天早上打开一看是空的，比没有数据更糟。
 *
 * <p>所以两个时机都刷：
 * <ul>
 *   <li><b>每次启动</b>：演示前肯定会重启一次，这是最可靠的一道</li>
 *   <li><b>每天 00:05</b>：应用连着跑几天也不会过期</li>
 * </ul>
 *
 * <p>⚠️ 刷新**只重造种子**（{@code exec_run.is_seed = 1}）。
 * M2 起 Playwright 真跑出来的记录一条都不动 —— 那些是真实发生过的事。
 *
 * <p>⚠️ {@code @Order(2)}：必须排在 {@link SeedRunner} 后面，
 * 执行历史要按真实的 case_id / case_code 造，案例得先在库里。
 */
@Slf4j
@Order(2)
@Component
public class ExecutionRefreshTask implements ApplicationRunner {

    @Autowired
    private ExecutionSeed executionSeed;

    @Override
    public void run(ApplicationArguments args) {
        refresh("启动");
    }

    /** 每天 00:05 —— 错开零点整，避开跨日那一瞬间可能的时间边界问题 */
    @Scheduled(cron = "0 5 0 * * *")
    public void daily() {
        refresh("每日定时");
    }

    private void refresh(String trigger) {
        try {
            int count = executionSeed.refresh();
            log.info("[{}] 执行看板演示数据已刷新到当天：{} 条", trigger, count);
        } catch (Exception e) {
            // ⚠️ 不让它掀翻应用：演示数据刷新失败顶多是看板难看，
            //    而启动失败就是整个平台起不来。记日志，继续。
            log.error("[{}] 刷新执行看板演示数据失败，看板可能显示过期数据", trigger, e);
        }
    }
}
