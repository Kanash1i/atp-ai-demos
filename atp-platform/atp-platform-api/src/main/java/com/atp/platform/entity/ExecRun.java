package com.atp.platform.entity;

import com.atp.common.enums.Browser;
import com.atp.common.enums.RunStatus;
import com.atp.common.enums.TriggerSource;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 执行批次。
 *
 * <p>计数列（passed/failed/…）是**冗余存的**，由执行器在每条任务收尾时增量更新 ——
 * 看板是高频读、低频写，每次都对 exec_task 做聚合不划算。
 */
@Data
@TableName("exec_run")
public class ExecRun {

    @TableId
    private String runId;
    private String runCode;
    private String projectId;
    private String suiteName;
    /** ⭐ browser 在这里，不在 tc_case —— 它是执行参数，同一条案例可以在不同浏览器上各跑一遍 */
    private Browser browser;
    private RunStatus status;
    private Integer totalCount;
    private Integer passedCount;
    private Integer failedCount;
    private Integer skippedCount;
    private Integer runningCount;
    /** ⭐ 两条 AI 赋能路线在这一列上分叉，看板可以按它分组对比 */
    private TriggerSource triggerSource;
    private String createdBy;
    private OffsetDateTime startedAt;
    private OffsetDateTime finishedAt;
    private OffsetDateTime createdAt;
}
