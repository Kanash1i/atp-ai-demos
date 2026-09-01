package com.atp.platform.entity;

import com.atp.common.enums.UserRole;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 人的账号。机器主体在 {@code sys_api_client}。
 *
 * <p>两者不合表的理由见 {@code SysApiClient} 的类注释：字段几乎不重合，
 * 混在一起「这一行是人还是程序」就得靠一个 type 列去猜。
 */
@Data
@TableName("sys_user")
public class SysUser {

    @TableId
    private String userId;
    private String username;
    private String displayName;

    /** SHA-256(salt + 明文)。与 sys_api_client 同一套算法 */
    private String passwordHash;
    /** 每个用户独立 —— 共用盐的话，两个人密码相同 hash 也相同 */
    private String passwordSalt;

    private UserRole role;
    private String avatarText;
    private OffsetDateTime createdAt;
}
