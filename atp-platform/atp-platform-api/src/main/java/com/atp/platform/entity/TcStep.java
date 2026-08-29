package com.atp.platform.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 步骤表 —— 与 {@link TcCase} **一比一**，不是一步一行。
 *
 * <p>{@code stepJson} 是全量步骤数组。老平台的执行器读整份步骤跑，不会按 seq 逐条查库。
 *
 * <p>⭐ 编辑期的状态机与乐观锁在**这张表**，不在 tc_case ——
 * 于是「反复改草稿」这条最高频的路径是单表单行 CAS，不跨表。
 * tc_case 只在 commit 那一刻被写一次。
 *
 * <p>⚠️ {@code stepJson} 用 String 而不是对象：JDBC url 带了 {@code stringtype=unspecified}，
 * PG 会把字符串隐式转成 jsonb。这样两条路线写进去的字节完全一致，
 * 不会因为一边走 Jackson、一边走 Go 的 encoding/json 而产生格式差异。
 */
@Data
@TableName("tc_step")
public class TcStep {

    @TableId
    private String stepId;
    private String caseId;
    private String stepJson;
    /** 编辑期状态 4=AI_DRAFT（编写中）1=DRAFT（已提交） */
    private Short status;
    /** 编辑期乐观锁 —— preview 给用户看的、commit 要带回来的就是它 */
    private Integer version;
    private OffsetDateTime updatedAt;
}
