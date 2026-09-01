package com.atp.platform.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** 会话里的一条消息 */
@Data
@TableName("ag_message")
public class AgMessage {

    @TableId
    private String messageId;
    private String conversationId;
    /** 1=用户 2=助手 */
    private Short role;
    private String content;
    /**
     * 这一轮的过程轨迹（路由结论、工具调用），JSON。
     *
     * <p>⚠️ **不存 thinking 全文**：一轮能有几百个增量片段、几十 KB，
     * 而回看历史时没人要重读一遍思考过程 —— 要的是「它当时怎么判的、调了什么」。
     */
    private String timelineJson;
    private String agentName;
    private OffsetDateTime createdAt;
}
