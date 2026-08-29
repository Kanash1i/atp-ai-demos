package com.atp.common.util;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 时间的展示格式化。
 *
 * <h3>⚠️ 为什么必须过这一层，不能直接 {@code OffsetDateTime.format(...)}</h3>
 *
 * 列是 {@code TIMESTAMPTZ}，JDBC 取回来的 {@link OffsetDateTime} 带的是 <b>UTC 偏移</b>，
 * 直接格式化就会把 19:49 打成 10:49 —— <b>差 9 小时，而且不报任何错</b>。
 * 前端照样渲染，审批的 SLA 倒计时照样算（那个用的是 Instant，是对的），
 * 只有人眼看那一行时间时才会发现不对。这个项目栽过一次同类的坑
 * （服务 health 200、向量维度也对，实际却跑在 CPU 上），判据一样：
 * <b>看起来对不算对，要么显式转换，要么就会静默错。</b>
 *
 * <h3>为什么钉死东京而不是 systemDefault()</h3>
 *
 * 演示要部署到服务器上，服务器时区大概率是 UTC。用 {@code systemDefault()} 的话，
 * 同一条数据在笔记本上显示 19:49、在服务器上显示 10:49 —— 演示时没法解释。
 * 语料本身是日文的、场景是日本公司，钉死东京最省心。
 */
public final class DisplayTime {

    /** 展示时区。⚠️ 刻意不用 systemDefault()，理由见类注释 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter SECOND = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private DisplayTime() {
    }

    /** 到分钟，列表与详情页用 */
    public static String toMinute(OffsetDateTime t) {
        return t == null ? null : t.atZoneSameInstant(ZONE).format(MINUTE);
    }

    /** 到秒，执行记录用 —— 一个批次里几十条任务的完成时刻只差几秒 */
    public static String toSecond(OffsetDateTime t) {
        return t == null ? null : t.atZoneSameInstant(ZONE).format(SECOND);
    }
}
