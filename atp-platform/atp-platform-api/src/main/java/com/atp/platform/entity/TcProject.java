package com.atp.platform.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 项目字典。前端案例中心顶部的三个 pill。 */
@Data
@TableName("tc_project")
public class TcProject {

    @TableId
    private String projectId;
    private String projectCode;
    private String projectName;
}
