package com.atp.agent.chat;

import com.atp.agent.authoring.CaseAuthoringAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 对话服务：把 agent 的事件流转成前端能渲染的 SSE 事件。
 *
 * <h3>⚠️ 会话与 agent 实例的关系</h3>
 *
 * 每个会话持有一个 agent 实例（{@code prototype} 作用域），因为 ReActAgent
 * 带着 memory —— 多轮对话的上下文在里面。做成单例的话两个用户的对话会串到一起，
 * <b>而且不会报错</b>，只会让 agent 突然引用另一个人说过的话。
 *
 * <p>⚠️ 这个 Map 是<b>进程内</b>的。演示够用；真要多实例部署，
 * agent 状态得走 {@code RedisSession}（AgentScope 自带，见 01-PLATFORM-设计.md §2）。
 * 现在不做是因为单机演示用不上，但这个边界要说清楚，别在面试时说成"已经支持集群"。
 */
@Slf4j
@Service
public class ChatService {

    @Autowired
    private ApplicationContext ctx;

    private final ConcurrentHashMap<String, CaseAuthoringAgent> sessions = new ConcurrentHashMap<>();

    /**
     * 发一句话，拿回一串事件。
     *
     * @param conversationId 会话 id。同一个会话复用同一个 agent 实例，多轮上下文才连得上
     */
    public Flux<ChatEvent> chat(String conversationId, String userMessage) {
        CaseAuthoringAgent agent = sessions.computeIfAbsent(conversationId,
                id -> ctx.getBean(CaseAuthoringAgent.class));

        Msg input = Msg.builder().role(MsgRole.USER).name("user")
                .content(TextBlock.builder().text(userMessage).build()).build();

        StreamOptions options = StreamOptions.builder()
                // ⭐ 三类事件都要：只推最终回复的话，用户会盯着一个转圈等三十秒 ——
                //    而这三十秒里 agent 查了规范、看了样例、跑了校验，那些才是最值得看的
                .eventTypes(EventType.REASONING, EventType.TOOL_RESULT, EventType.AGENT_RESULT)
                .incremental(true)
                // ⚠️ 只要增量 chunk，不要 reasoning 的完整结果。
                //    两个都开的话，同一段思考会被推两遍（增量一遍 + 完整一遍），
                //    前端按增量拼接就会显示重复内容 —— 实测 thinking 拼出来正好是 message 的两倍长
                .includeReasoningChunk(true)
                .includeReasoningResult(false)
                // ⭐ acting 通道 = 工具调用阶段。不开的话工具结果会被并进 reasoning，
                //    前端就没法把「检索命中了哪几条规范」单独渲染成卡片 ——
                //    而那恰恰是这个 demo 最该展示的东西：agent 的依据是什么
                .includeActingChunk(true)
                .build();

        return agent.raw().stream(input, options)
                .map(this::toChatEvent)
                .filter(e -> e.content() != null && !e.content().isBlank())
                .concatWithValues(ChatEvent.done(CaseAuthoringAgent.NAME))
                .onErrorResume(e -> {
                    // ⚠️ 错误也要推给前端。吞掉的话前端会一直等 done，
                    //    表现为「对话卡住了」，而后端日志里其实有完整堆栈
                    log.error("会话 {} 执行失败", conversationId, e);
                    return Flux.just(ChatEvent.error(CaseAuthoringAgent.NAME,
                            e.getClass().getSimpleName() + ": " + e.getMessage()));
                });
    }

    /** 结束会话，释放 agent 实例（连同它的 memory） */
    public void close(String conversationId) {
        sessions.remove(conversationId);
    }

    private ChatEvent toChatEvent(Event event) {
        String text = extractText(event);
        String agent = CaseAuthoringAgent.NAME;
        return switch (event.getType()) {
            case REASONING -> ChatEvent.thinking(agent, text);
            case TOOL_RESULT -> ChatEvent.tool(agent, text);
            default -> ChatEvent.message(agent, text);
        };
    }

    private String extractText(Event event) {
        Msg msg = event.getMessage();
        if (msg == null || msg.getContent() == null) {
            return "";
        }
        return msg.getContent().stream()
                .filter(TextBlock.class::isInstance).map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.joining());
    }
}
