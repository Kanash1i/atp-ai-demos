package com.atp.platform.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 模块字典。
 *
 * <p>⚠️ {@code moduleCode} 全局唯一 —— case_code 的规范 {@code ATP-{MODULE}-{4位}}
 * 直接取它，两个项目复用同一个 code 会让案例编号撞车。
 */
@Data
@TableName("tc_module")
public class TcModule {

    @TableId
    private String moduleId;
    private String projectId;
    private String moduleCode;
    private String moduleName;
}
