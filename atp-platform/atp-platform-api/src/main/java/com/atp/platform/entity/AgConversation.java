package com.atp.platform.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** 一次对话会话。消息在 {@link AgMessage} */
@Data
@TableName("ag_conversation")
public class AgConversation {

    @TableId
    private String conversationId;
    private String userId;
    /** 标题。取首条用户消息的前若干字 —— 不额外调模型生成，那不值一次调用 */
    private String title;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    /**
     * 软删除。
     *
     * <p>⚠️ 不物理删：会话里可能记录着 agent 写过哪条案例、跑过哪次执行，
     * 那是排查「这条案例当初是怎么来的」的线索。
     */
    private Short deleted;
}
