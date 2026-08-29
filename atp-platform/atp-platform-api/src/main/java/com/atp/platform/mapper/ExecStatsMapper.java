package com.atp.platform.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 执行看板的统计查询。
 *
 * <p>⚠️ 这些数字**从表里算，不硬编码** —— 演示时面试官问「这个 94.2% 是怎么来的」，
 * 答案得是一条能当场跑给他看的 SQL。
 *
 * <p>用 PG 的 {@code count(*) FILTER (WHERE ...)} 一次扫表出全部分档，
 * 而不是四条 count 各扫一遍。
 */
@Mapper
public interface ExecStatsMapper {

    /**
     * 一个时间窗内的执行统计。
     *
     * <p>{@code avg_duration_ms} 只统计真正跑完的（PASSED/FAILED）——
     * SKIPPED 的耗时是 null，混进去会把平均值算低。
     */
    @Select("""
            SELECT count(*)                                        AS total,
                   count(*) FILTER (WHERE status = 3)              AS passed,
                   count(*) FILTER (WHERE status = 4)              AS failed,
                   count(*) FILTER (WHERE status = 5)              AS skipped,
                   avg(duration_ms) FILTER (WHERE status IN (3,4)) AS avg_duration_ms
            FROM exec_task
            WHERE finished_at >= #{from} AND finished_at < #{to}
            """)
    Map<String, Object> statsBetween(@Param("from") OffsetDateTime from,
                                     @Param("to") OffsetDateTime to);

    /**
     * 失败案例里有几条是 P0。
     *
     * <p>⚠️ 要 join 回 tc_case 取优先级 —— exec_task 里没有冗余 priority。
     * 冗余它没意义：优先级会随案例调整，而"这次失败的是不是 P0"要按**当前**的优先级看，
     * 不是执行那一刻的。这跟 case_code 快照的取舍正好相反（那个要的是当时的事实）。
     */
    @Select("""
            SELECT count(*)
            FROM exec_task t
            JOIN tc_case c ON c.case_id = t.case_id
            WHERE t.status = 4
              AND t.finished_at >= #{from} AND t.finished_at < #{to}
              AND c.priority = 0
            """)
    long failedP0Between(@Param("from") OffsetDateTime from,
                         @Param("to") OffsetDateTime to);
}
