package com.atp.platform.entity;

import com.atp.common.enums.Browser;
import com.atp.common.enums.TaskStatus;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 单条案例的一次执行。
 *
 * <p>{@code caseCode} 与 {@code caseTitle} 是**快照** —— 案例后来被改名或删除，
 * 历史执行记录仍要显示当时跑的是什么。执行记录是不可变的事实。
 */
@Data
@TableName("exec_task")
public class ExecTask {

    @TableId
    private String taskId;
    private String runId;
    private String caseId;
    private String caseCode;
    private String caseTitle;
    private Browser browser;
    private String nodeName;
    private TaskStatus status;
    private Integer durationMs;
    private String errorMsg;
    /** 失败落在第几步。失败详情页靠它直接定位，不用扫全部步骤结果 */
    private Integer failedSeq;
    private String videoUrl;
    private String screenshotUrl;
    private String traceUrl;
    private OffsetDateTime queuedAt;
    private OffsetDateTime startedAt;
    private OffsetDateTime finishedAt;
}
