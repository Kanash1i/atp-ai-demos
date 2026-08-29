package com.atp.platform.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 批次计数的原子更新。
 *
 * <p>⚠️ 必须是 {@code SET x = x + 1} 这种数据库侧自增，**不能**「读出来 +1 再写回去」——
 * 多个执行节点同时收尾时，读-改-写会丢更新，看板上的 PASS 数就会比实际少，
 * 而且少多少完全取决于并发时机，事后根本对不上账。
 */
@Mapper
public interface ExecCounterMapper {

    /**
     * 一条任务收尾时把对应的计数 +1。
     *
     * @param column 只接受四个固定列名，由调用方用枚举映射，不接受外部输入
     */
    @Update("UPDATE exec_run SET ${column} = ${column} + 1 WHERE run_id = #{runId}")
    int increment(@Param("runId") String runId, @Param("column") String column);

    /**
     * 全部任务跑完就把批次标记为 DONE。
     *
     * <p>⭐ 「跑完」的判断放在 SQL 里做，而不是应用层查一次再判断：
     * 最后两条任务同时收尾时，两边都会看到「还差一条」，于是谁都不去收尾批次，
     * 批次就永远挂在 RUNNING 上。交给数据库在一条语句里完成判断与更新，这个竞态就不存在。
     */
    @Update("""
            UPDATE exec_run
               SET status = 3, finished_at = now()
             WHERE run_id = #{runId}
               AND status = 2
               AND passed_count + failed_count + skipped_count >= total_count
            """)
    int finishIfComplete(@Param("runId") String runId);
}
