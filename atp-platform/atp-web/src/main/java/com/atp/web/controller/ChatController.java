package com.atp.web.controller;

import com.atp.agent.chat.ChatEvent;
import com.atp.agent.chat.ChatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.Executors;

/**
 * 智能 Agent 助手的对话入口。
 *
 * <p>用 SSE 而不是 WebSocket：这是**单向推送**（服务端 → 浏览器），
 * 用户的下一句话走新的 POST。SSE 天然带断线重连，
 * 而 WebSocket 要自己处理重连、心跳、协议升级 —— 为一个单向流付这些成本不划算。
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    /** SSE 连接会挂住整个请求线程，必须放到独立线程池，否则会吃光 Tomcat 的工作线程 */
    private final java.util.concurrent.ExecutorService sseExecutor =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "atp-chat-sse");
                t.setDaemon(true);
                return t;
            });

    @Autowired
    private ChatService chatService;

    /**
     * 发一句话，SSE 流式返回 agent 的思考、工具调用与最终回复。
     *
     * <p>事件类型见 {@link ChatEvent}：thinking / tool / message / done / error。
     */
    @PostMapping(value = "/{conversationId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@PathVariable String conversationId,
                           @RequestBody ChatRequest body) {
        // ⚠️ 超时给足：一条案例要查规范、看样例、校验、改错，正常就要三四十秒。
        //    默认 30 秒会在 agent 干到一半时断开，前端看到的是「莫名其妙断了」
        SseEmitter emitter = new SseEmitter(300_000L);

        sseExecutor.submit(() -> chatService.chat(conversationId, body.message())
                .doOnNext(e -> send(emitter, e))
                .doOnError(e -> emitter.completeWithError(e))
                .doOnComplete(emitter::complete)
                .blockLast());

        return emitter;
    }

    /** 结束会话，释放 agent 实例与它的多轮上下文 */
    @DeleteMapping("/{conversationId}")
    public void close(@PathVariable String conversationId) {
        chatService.close(conversationId);
    }

    private void send(SseEmitter emitter, ChatEvent event) {
        try {
            emitter.send(SseEmitter.event().name(event.type()).data(event));
        } catch (IOException e) {
            // 客户端关掉页面就会走到这里，不是错误
            log.debug("SSE 连接已断开", e);
        }
    }

    public record ChatRequest(String message) {
    }
}
