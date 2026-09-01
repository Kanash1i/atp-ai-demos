package com.atp.platform.service;

import com.atp.common.util.DisplayTime;
import com.atp.platform.entity.AgConversation;
import com.atp.platform.entity.AgMessage;
import com.atp.platform.mapper.AgConversationMapper;
import com.atp.platform.mapper.AgMessageMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 对话历史。
 *
 * <h3>为什么要落库，而不是只留在前端内存里</h3>
 *
 * 用户从 agent 面板切到执行面板再切回来，前端组件卸载、内存里的对话就没了 ——
 * 而那一轮对话里可能有 agent 刚写好、还没确认的案例。
 *
 * <p>前端可以把状态提到全局 store 来扛住"切面板"，但扛不住刷新、扛不住换设备、
 * 也扛不住"三天后回来看当初那条案例是怎么写出来的"。**那三件都需要落库。**
 */
@Slf4j
@Service
public class ChatHistoryService {

    /** 用户消息 */
    public static final short ROLE_USER = 1;
    /** 助手消息 */
    public static final short ROLE_ASSISTANT = 2;

    /** 标题取首条消息的前若干字。够认出是哪次对话就行 */
    private static final int TITLE_MAX = 30;

    @Autowired
    private AgConversationMapper conversationMapper;

    @Autowired
    private AgMessageMapper messageMapper;

    /**
     * 记一条用户消息，必要时顺带建会话。
     *
     * <p>⚠️ 会话的创建是**幂等**的：conversationId 由前端生成，同一个 id 再来一次
     * 只是追加消息。这与案例写侧的幂等键是同一个思路 ——
     * 「响应丢了重试」不该产生第二个会话。
     */
    public void recordUser(String conversationId, String userId, String message) {
        AgConversation conv = conversationMapper.selectById(conversationId);
        if (conv == null) {
            conv = new AgConversation();
            conv.setConversationId(conversationId);
            conv.setUserId(userId);
            conv.setTitle(title(message));
            conv.setDeleted((short) 0);
            conversationMapper.insert(conv);
        } else {
            conv.setUpdatedAt(OffsetDateTime.now());
            conversationMapper.updateById(conv);
        }
        insert(conversationId, ROLE_USER, message, null, null);
    }

    /** 记一条助手回复 */
    public void recordAssistant(String conversationId, String content, String agentName, String timelineJson) {
        insert(conversationId, ROLE_ASSISTANT, content, agentName, timelineJson);
    }

    /**
     * 某个用户的会话列表，最近的在前。
     *
     * <p>⚠️ 按 userId 过滤，不是按 conversationId 猜 —— 会话 id 是前端生成的 UUID，
     * 拿到别人的 id 就能读别人的对话，那不是"猜不到"能挡住的。
     */
    public List<ConversationView> list(String userId, int limit) {
        return conversationMapper.selectList(new LambdaQueryWrapper<AgConversation>()
                        .eq(AgConversation::getUserId, userId)
                        .eq(AgConversation::getDeleted, (short) 0)
                        .orderByDesc(AgConversation::getUpdatedAt)
                        .last("LIMIT " + Math.min(Math.max(limit, 1), 100))).stream()
                .map(c -> new ConversationView(c.getConversationId(), c.getTitle(),
                        DisplayTime.toMinute(c.getCreatedAt()), DisplayTime.toMinute(c.getUpdatedAt())))
                .toList();
    }

    /** 某个会话的消息。⚠️ 校验归属，不能靠 id 不可猜 */
    public List<MessageView> messages(String conversationId, String userId) {
        AgConversation conv = conversationMapper.selectById(conversationId);
        if (conv == null || !userId.equals(conv.getUserId()) || conv.getDeleted() != 0) {
            return List.of();
        }
        return messageMapper.selectList(new LambdaQueryWrapper<AgMessage>()
                        .eq(AgMessage::getConversationId, conversationId)
                        .orderByAsc(AgMessage::getCreatedAt)).stream()
                .map(m -> new MessageView(m.getMessageId(),
                        m.getRole() == ROLE_USER ? "user" : "assistant",
                        m.getContent(), m.getAgentName(), m.getTimelineJson(),
                        DisplayTime.toMinute(m.getCreatedAt())))
                .toList();
    }

    /** 软删除。会话里记着 agent 写过哪条案例，那是排查线索，不物理删 */
    public boolean delete(String conversationId, String userId) {
        AgConversation conv = conversationMapper.selectById(conversationId);
        if (conv == null || !userId.equals(conv.getUserId())) {
            return false;
        }
        conv.setDeleted((short) 1);
        conversationMapper.updateById(conv);
        return true;
    }

    private void insert(String conversationId, short role, String content,
                        String agentName, String timelineJson) {
        AgMessage m = new AgMessage();
        m.setMessageId(UUID.randomUUID().toString());
        m.setConversationId(conversationId);
        m.setRole(role);
        m.setContent(content);
        m.setAgentName(agentName);
        m.setTimelineJson(timelineJson);
        messageMapper.insert(m);
    }

    private String title(String firstMessage) {
        if (firstMessage == null || firstMessage.isBlank()) {
            return "新对话";
        }
        String t = firstMessage.strip().replaceAll("\\s+", " ");
        return t.length() <= TITLE_MAX ? t : t.substring(0, TITLE_MAX) + "…";
    }

    public record ConversationView(String conversationId, String title,
                                   String createdAt, String updatedAt) {
    }

    public record MessageView(String messageId, String role, String content,
                              String agentName, String timelineJson, String createdAt) {
    }
}
