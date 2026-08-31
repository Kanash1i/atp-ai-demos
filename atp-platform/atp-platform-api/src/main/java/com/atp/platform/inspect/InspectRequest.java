package com.atp.platform.inspect;

/**
 * 页面探查请求。
 *
 * <h3>⚠️ 为什么这里把内容放进 Redis，而执行队列只放 taskId</h3>
 *
 * 执行任务的真相在 PG（{@code exec_task} 那一行），Redis 只是「有活儿了」的通知，
 * 丢了可以从 PG 补扫。**探查请求在 PG 里没有对应的行** —— 它是一次性的问答，
 * 不需要留痕、不需要补偿、丢了就是丢了（调用方超时后重试即可）。
 *
 * <p>给它建一张表反而是错的：那会让「一次性的问」和「要留痕的事实」混在同一套设施里。
 *
 * @param requestId 响应回传的地址（{@code atp:inspect:reply:{requestId}}），由平台生成
 * @param url       完整 URL。⚠️ 变量在平台侧就已展开，节点不认识 {@code ${base_url}}
 * @param timeoutMs 页面加载等待上限
 */
public record InspectRequest(
        String requestId,
        String url,
        int timeoutMs) {
}
