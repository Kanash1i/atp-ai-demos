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
import reactor.core.Disposable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
    /**
     * 正在跑的订阅：conversationId → Disposable。
     *
     * <p>⚠️ 这是<b>单实例内</b>的。平台多副本时 interrupt 请求可能被负载均衡打到
     * 另一个实例，那边的 map 里没有这个句柄 —— 需要 Redis pub/sub 广播。
     * 当前是单实例部署，先不引入那一层。
     */
    private final ConcurrentHashMap<String, Disposable> running = new ConcurrentHashMap<>();

    /** 同一个会话的 emitter —— 打断时要用它推 interrupted 事件并关闭连接 */
    private final ConcurrentHashMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();

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

        // ⚠️ 用 subscribe() 而不是 blockLast()：blockLast 把线程一直阻塞到流结束，
        //    **拿不到任何句柄**，也就没法从外面取消 —— 停止按钮做不出来。
        //    subscribe() 返回 Disposable，dispose() 会向上游传播取消信号，
        //    ChatService 的 doOnCancel 收到后再去 interrupt agent。
        Disposable subscription = chatService.chat(conversationId, body.message(), userId)
                .subscribe(
                        e -> send(emitter, e),
                        e -> {
                            // ⚠️ 不处理的话异常会被 Reactor 丢到全局错误钩子，
                            //    前端只看到连接卡死，后端日志里什么也没有
                            log.error("会话 {} 的流失败", conversationId, e);
                            running.remove(conversationId);
                            emitter.completeWithError(e);
                        },
                        () -> {
                            running.remove(conversationId);
                            emitter.complete();
                        });

        // ⚠️ 记下订阅句柄，interrupt 接口靠它找到「正在跑的那一轮」。
        //    put 之后要再检查一次是否已完成 —— 极快的流可能在 put 之前就结束了，
        //    那样句柄会永远留在 map 里，下一次 interrupt 会作用到一个已死的订阅
        running.put(conversationId, subscription);
        emitters.put(conversationId, emitter);
        if (subscription.isDisposed()) {
            running.remove(conversationId);
        }

        // 客户端主动断开（关页面、网络断）也要停 —— 否则 agent 继续烧 token 没人收
        emitter.onCompletion(() -> {
            running.remove(conversationId);
            emitters.remove(conversationId);
        });
        emitter.onTimeout(() -> {
            log.warn("会话 {} 的 SSE 超时，停止 agent", conversationId);
            cancel(conversationId);
        });
        emitter.onError(e -> cancel(conversationId));

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

    /**
     * 打断正在生成的那一轮。
     *
     * <h3>⚠️ 语义是「不再继续下一步」，不是「撤销已做的」</h3>
     *
     * 两件事同时做：
     * <ol>
     *   <li>{@code dispose()} 取消订阅 —— 停止向前端推送，并让 ChatService 的
     *       {@code doOnCancel} 触发</li>
     *   <li>{@code chatService.interrupt()} 置 agent 的中断标志 —— 它在下一个
     *       检查点停下，不再发起新的模型调用与工具调用</li>
     * </ol>
     *
     * <p>只做第一件是<b>假打断</b>：前端不听了，agent 还在后台跑完整轮，
     * token 照烧、工具照调。只做第二件则前端会一直等到超时。
     */
    @PostMapping("/{conversationId}/interrupt")
    public Map<String, Object> interrupt(@PathVariable String conversationId) {
        boolean agentStopped = chatService.interrupt(conversationId);
        SseEmitter emitter = emitters.get(conversationId);
        boolean streamStopped = cancel(conversationId);
        if (emitter != null) {
            finishAsInterrupted(emitter, conversationId);
            emitters.remove(conversationId);
        }
        log.info("会话 {} 收到打断：agent={} stream={}", conversationId, agentStopped, streamStopped);
        return Map.of("interrupted", agentStopped || streamStopped);
    }

    /**
     * 取消订阅并清理句柄。返回是否真的取消了什么。
     *
     * <h3>⚠️ dispose() 之后必须自己关 emitter</h3>
     *
     * {@code subscribe(next, error, complete)} 的三个回调，在订阅被 {@code dispose()}
     * 之后<b>一个都不会触发</b> —— 取消不是"完成"，也不是"出错"。
     *
     * <p>所以 {@code emitter.complete()} 永远没人调，SSE 连接会一直挂着，
     * 直到 300 秒超时。实测症状：打断后 agent 确实停了（事件不再增加），
     * 但浏览器那侧连接不关，转圈继续转 —— 用户会以为没打断成功。
     */
    private boolean cancel(String conversationId) {
        Disposable d = running.remove(conversationId);
        if (d == null || d.isDisposed()) {
            return false;
        }
        d.dispose();
        return true;
    }

    /** 打断时给前端一个明确的收尾：先说停了，再关连接 */
    private void finishAsInterrupted(SseEmitter emitter, String conversationId) {
        send(emitter, ChatEvent.interrupted("system", "已停止本次生成"));
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // 连接可能已经被客户端关掉了，这不是错误
        }
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
