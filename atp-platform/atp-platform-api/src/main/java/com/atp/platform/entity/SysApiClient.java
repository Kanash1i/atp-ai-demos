package com.atp.platform.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 机器主体 —— CLI 这类程序的身份。人的账号在 {@code sys_user}。
 *
 * <p>两者不合表：机器主体没有姓名/角色/部门，却需要 secret 轮换、scope 白名单、吊销，
 * 字段几乎不重合。混在一张表里，「这一行是人还是程序」就得靠一个 type 列去猜。
 */
@Data
@TableName("sys_api_client")
public class SysApiClient {

    @TableId
    private String clientId;
    private String clientName;

    /** SHA-256(salt + secret)。⚠️ 明文只在创建时返回一次，平台自己也读不出来 */
    private String secretHash;
    private String secretSalt;

    /** 权限白名单，逗号分隔。这里是「窄」token 的落点 */
    private String scopes;

    private Boolean enabled;
    /** 吊销不删行 —— 删了就查不出它曾经存在过、做过什么 */
    private OffsetDateTime revokedAt;
    private OffsetDateTime lastUsedAt;
    private OffsetDateTime createdAt;
    private String createdBy;
}
