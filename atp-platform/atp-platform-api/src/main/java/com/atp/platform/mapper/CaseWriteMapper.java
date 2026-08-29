package com.atp.platform.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 案例编辑期的写操作。
 *
 * <h3>⭐ 为什么全是手写 SQL，不用 MyBatis-Plus 的方法</h3>
 *
 * 这几条语句的正确性**全在 WHERE 里**：状态和版本必须和 SET 在同一条 UPDATE 中，
 * 受影响行数为 1 才算成功。写成「先 SELECT 检查、再 UPDATE」的话，
 * 检查通过之后、UPDATE 执行之前那一小段窗口里内容被人改掉，
 * 就会**静默地提交错的版本** —— 这就是 TOCTOU（检查与使用之间的时间差）。
 *
 * <p>MyBatis-Plus 的 updateById 之类做不到把条件压进 WHERE，所以这里退回手写。
 *
 * <p>⚠️ 语义与 {@code demo2-atp-cli/internal/store/case_store.go} 严格一致 ——
 * 两条路线写的是同一张表，一边允许的另一边必须也允许，否则「哪个才是对的」永远说不清。
 */
@Mapper
public interface CaseWriteMapper {

    /**
     * 建草稿的骨架行。
     *
     * <p>⭐ {@code caseId} 由**调用方**生成并在重试时复用 —— 这是幂等的全部来源。
     * 若改由数据库生成，「INSERT 成功但响应丢失 → 重试」会产生两条各自合法的草稿，
     * 而版本号救不了它们（那是两行不同的记录）。
     */
    @Update("""
            INSERT INTO tc_case (case_id, case_type, status, version, created_by, created_at, updated_at)
            VALUES (#{caseId}, #{caseType}, 4, 0, #{createdBy}, now(), now())
            ON CONFLICT (case_id) DO NOTHING
            """)
    int insertDraftCase(@Param("caseId") String caseId,
                        @Param("caseType") short caseType,
                        @Param("createdBy") String createdBy);

    @Update("""
            INSERT INTO tc_step (step_id, case_id, step_json, status, version, updated_at)
            VALUES (#{stepId}, #{caseId}, #{stepJson}::jsonb, 4, 0, now())
            ON CONFLICT (case_id) DO NOTHING
            """)
    int insertDraftStep(@Param("stepId") String stepId,
                        @Param("caseId") String caseId,
                        @Param("stepJson") String stepJson);

    /**
     * 更新草稿内容 —— **单表单行 CAS**。
     *
     * <p>编辑期的高频写全部落在 tc_step 这一行上，不跨表。
     * tc_case 只在 commit 那一刻被写一次。
     */
    @Update("""
            UPDATE tc_step
               SET step_json = #{stepJson}::jsonb, version = version + 1, updated_at = now()
             WHERE case_id = #{caseId} AND status = 4 AND version = #{expectedVersion}
            """)
    int updateDraft(@Param("caseId") String caseId,
                    @Param("stepJson") String stepJson,
                    @Param("expectedVersion") int expectedVersion);

    /**
     * 提交：AI_DRAFT(4) → DRAFT(1)，同时把 step_json 规整成老平台的纯步骤数组。
     *
     * <p>⭐ 规整这一步不能省：保守路线的主张是「落库格式与人工案例完全一致，老执行器照跑」，
     * 而老执行器读的是数组。留成对象的话，那句主张就是假的。
     */
    @Update("""
            UPDATE tc_step
               SET status = 1, step_json = #{legacyStepJson}::jsonb,
                   version = version + 1, updated_at = now()
             WHERE case_id = #{caseId} AND status = 4 AND version = #{expectedVersion}
            """)
    int commitStep(@Param("caseId") String caseId,
                   @Param("legacyStepJson") String legacyStepJson,
                   @Param("expectedVersion") int expectedVersion);

    /**
     * 把冻结快照里的表头投影到 tc_case 的正式列，并翻状态。
     *
     * <p>⚠️ {@code ck_case_complete} 正好在这一刻校验必填 ——
     * 编辑期允许残缺，一离开 AI_DRAFT 就必须完整，**数据库直接守门**，
     * 不需要在应用层再写一遍「提交前检查必填」。
     *
     * <p>⚠️ 加锁顺序固定 tc_step → tc_case。清理任务（M5）必须同序，
     * 否则两者撞在同一条边界草稿上会死锁。
     */
    @Update("""
            UPDATE tc_case
               SET case_code = #{caseCode}, title = #{title}, module_id = #{moduleId},
                   priority = #{priority}, author = #{author}, precondition = #{precondition},
                   status = 1, version = version + 1, updated_at = now()
             WHERE case_id = #{caseId}
            """)
    int projectHeader(@Param("caseId") String caseId,
                      @Param("caseCode") String caseCode,
                      @Param("title") String title,
                      @Param("moduleId") String moduleId,
                      @Param("priority") Short priority,
                      @Param("author") String author,
                      @Param("precondition") String precondition);
}
