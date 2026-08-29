package com.atp.platform.entity;

import com.atp.common.enums.UserRole;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** 平台用户。 */
@Data
@TableName("sys_user")
public class SysUser {

    @TableId
    private String userId;
    private String username;
    private String displayName;
    private String passwordHash;
    private UserRole role;
    /** 头像里显示的两个字母 */
    private String avatarText;
    private OffsetDateTime createdAt;
}
