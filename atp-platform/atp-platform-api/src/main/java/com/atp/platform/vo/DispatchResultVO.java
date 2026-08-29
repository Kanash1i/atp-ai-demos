package com.atp.platform.vo;

/**
 * 派发结果。
 *
 * <p>⚠️ 刻意只返回这四个字段，而不是把 {@code ExecRun} 实体直接抛出去：
 * 实体里的时间是 JDBC 的原始 {@code OffsetDateTime}（带纳秒与时区偏移），
 * 而本项目其他接口的时间一律是后端格式化好的 {@code yyyy-MM-dd HH:mm}。
 * 让前端为一个接口单独写一套时间解析，是最容易被忽略、也最容易出错的那种不一致。
 *
 * <p>派发是「发起」，进度靠 {@code GET /api/executions/running} 轮询 ——
 * 所以这里只需要够前端确认「发出去了、发了多少条」即可。
 */
public record DispatchResultVO(String runId, String runCode, int totalCount, String status) {
}
