package com.atp.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.atp.platform.service.ChatHistoryService;
import com.atp.web.auth.StpInterfaceImpl;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import com.atp.agent.chat.ChatEvent;
import com.atp.agent.chat.ChatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
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

    @Autowired
    private ChatHistoryService history;

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

        // ⚠️ userId 必须在**请求线程**里取，不能放进下面的 lambda。
        //    Sa-Token 的 StpUtil 靠 ThreadLocal 拿当前登录态，换到线程池的线程上就没了 ——
        //    实测症状是：SSE 一个事件都不推，卡满 300 秒后抛 AsyncRequestTimeoutException，
        //    而真正的异常（NotLoginException）在 lambda 里被吞掉，日志里一个字都没有。
        String userId = currentUserId();

        sseExecutor.submit(() -> {
            try {
                chatService.chat(conversationId, body.message(), userId)
                        .doOnNext(e -> send(emitter, e))
                        .doOnError(emitter::completeWithError)
                        .doOnComplete(emitter::complete)
                        .blockLast();
            } catch (Exception e) {
                // ⚠️ 不 catch 的话，线程池会静默吞掉异常 —— 前端只看到连接卡死，
                //    后端日志里什么也没有。上面那个 ThreadLocal 问题就是这么难查的
                log.error("会话 {} 的 SSE 任务失败", conversationId, e);
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * 关闭会话：释放 agent 实例（连同 memory），并把会话标记为已删除。
     *
     * <h3>⚠️ 必须是 204，不能是「200 + 空 body」</h3>
     *
     * 返回 {@code void} 时 Spring 给的是 <b>200 加一个零字节的 body</b>，
     * 而调用方拿到 200 的第一反应是去 {@code res.json()} —— 解析空字符串直接抛异常。
     *
     * <p>症状是：<b>后端删干净了，前端却当成删除失败</b>，
     * 于是它后面的刷新逻辑一步都没执行，列表纹丝不动，手动刷新页面才看得到变化。
     * 整条链上没有任何一处报错到用户面前。
     *
     * <p>204 才是「做完了，没有内容给你」的正确说法，
     * 而且任何按 HTTP 规范写的客户端都会跳过读 body。
     */
    @DeleteMapping("/{conversationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void close(@PathVariable String conversationId) {
        chatService.close(conversationId);
        history.delete(conversationId, currentUserId());
    }

    private void send(SseEmitter emitter, ChatEvent event) {
        try {
            emitter.send(SseEmitter.event().name(event.type()).data(event));
        } catch (IOException e) {
            // 客户端关掉页面就会走到这里，不是错误
            log.debug("SSE 连接已断开", e);
        }
    }

    /**
     * 我的会话列表，最近的在前。
     *
     * <p>⚠️ 按 token 里的 userId 过滤，不接受调用方传 userId ——
     * 会话 id 是前端生成的 UUID，靠"猜不到"保护别人的对话是不成立的。
     */
    @GetMapping("/conversations")
    public List<ChatHistoryService.ConversationView> conversations(
            @RequestParam(defaultValue = "30") int limit) {
        return history.list(currentUserId(), limit);
    }

    /** 某个会话的历史消息。不属于当前用户则返回空列表，不报错 —— 不确认 id 是否存在 */
    @GetMapping("/{conversationId}/messages")
    public List<ChatHistoryService.MessageView> messages(@PathVariable String conversationId) {
        return history.messages(conversationId, currentUserId());
    }

    /**
     * 从 token 解出 userId。
     *
     * <p>loginId 形如 {@code user:U001}。机器主体（{@code client:atp-cli}）
     * 走不到这里 —— 对话是人的功能，agent 不需要「历史会话」。
     */
    private String currentUserId() {
        String loginId = String.valueOf(StpUtil.getLoginId());
        if (!loginId.startsWith(StpInterfaceImpl.USER_PREFIX)) {
            throw new IllegalStateException("对话功能只对人开放，当前主体是 " + loginId);
        }
        return loginId.substring(StpInterfaceImpl.USER_PREFIX.length());
    }

    public record ChatRequest(String message) {
    }
}
