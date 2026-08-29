package com.atp.platform.entity;

import com.atp.common.enums.CaseStatus;
import com.atp.common.enums.CaseType;
import com.atp.common.enums.Priority;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 案例主表。
 *
 * <p>⚠️ 这张表由**两条路线共同写入**：CLI（保守路线）与本平台（激进路线）。
 * 所以枚举码值必须与 {@code demo2-atp-cli/internal/model/enums.go} 一致 ——
 * 见 {@link com.atp.common.enums.CodedEnum}。
 */
@Data
@TableName("tc_case")
public class TcCase {

    @TableId
    private String caseId;
    private CaseType caseType;
    private String caseCode;
    private String title;
    private String moduleId;
    private Priority priority;
    private String author;
    private String precondition;
    private CaseStatus status;
    // ⚠️ 这里没有 browser 与 timeout_sec，是设计决定不是遗漏：
    //    browser 是执行参数（正位在 exec_run / exec_task），timeout_sec 没有消费方。
    //    理由写在 migrations/V2__platform_extend.sql 里。
    /** 乐观锁。⚠️ 这是**落地后**平台侧修改用的；编辑期的乐观锁在 tc_step 上，两个生命周期互不干扰 */
    private Integer version;
    private String createdBy;
    private OffsetDateTime createdAt;
    /** ⚠️ PG 没有 MySQL 的 ON UPDATE CURRENT_TIMESTAMP —— 每条 UPDATE 都要显式赋值 */
    private OffsetDateTime updatedAt;
}
