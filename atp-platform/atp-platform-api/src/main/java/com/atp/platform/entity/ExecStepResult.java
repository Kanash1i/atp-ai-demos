package com.atp.platform.entity;

import com.atp.common.enums.StepStatus;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 步骤级结果。失败详情页按 seq 展开的就是它 */
@Data
@TableName("exec_step_result")
public class ExecStepResult {

    @TableId
    private String resultId;
    private String taskId;
    private Integer seq;
    private String action;
    private StepStatus status;
    private Integer durationMs;
    private String errorMsg;
    private String screenshotUrl;
}
