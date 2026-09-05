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
    /**
     * 正在跑的那一轮：conversationId → (emitter, subscription)。
     *
     * <h3>⚠️ 两者必须绑在同一个对象里，不能拆成两个 map</h3>
     *
     * 拆开的话，「取消订阅」和「关闭连接」会作用到不同轮次的对象上 ——
     * 实测踩过：打断第 N 轮之后发起第 N+1 轮，新流刚建立就往上一轮那个已关闭的
     * emitter 里写，抛 {@code ResponseBodyEmitter has already completed}，
     * 订阅被取消，前端收到 <b>0 个事件</b>。
     *
     * <p>症状看起来像「打断之后这个会话就废了」，而根因只是两个 map 不同步。
     * 一轮对话的两个句柄是同一个事实的两面，就该原子地一起换。
     */
    private final ConcurrentHashMap<String, Turn> turns = new ConcurrentHashMap<>();

    /** 一轮对话的运行时句柄 */
    private record Turn(SseEmitter emitter, Disposable subscription) {
    }

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
        // ⚠️ 先把上一轮（如果还在）彻底停掉再开新的。
        //    不这么做的话，同一个会话并发两轮，agent 那侧会抛
        //    "Agent is still running" —— 而那个异常在响应式链里很难追
        stopTurn(conversationId, false);

        Disposable subscription = chatService.chat(conversationId, body.message(), userId)
                .subscribe(
                        e -> send(emitter, e),
                        e -> {
                            // ⚠️ 不处理的话异常会被 Reactor 丢到全局错误钩子，
                            //    前端只看到连接卡死，后端日志里什么也没有
                            log.error("会话 {} 的流失败", conversationId, e);
                            turns.remove(conversationId);
                            emitter.completeWithError(e);
                        },
                        () -> {
                            turns.remove(conversationId);
                            emitter.complete();
                        });

        // ⚠️ 记下订阅句柄，interrupt 接口靠它找到「正在跑的那一轮」。
        //    put 之后要再检查一次是否已完成 —— 极快的流可能在 put 之前就结束了，
        //    那样句柄会永远留在 map 里，下一次 interrupt 会作用到一个已死的订阅
        // 原子地登记这一轮。⚠️ 极快的流可能在这之前就结束了，
        //    那样句柄会留在 map 里，下一次打断会作用到一个已死的订阅
        turns.put(conversationId, new Turn(emitter, subscription));
        if (subscription.isDisposed()) {
            turns.remove(conversationId);
        }

        // 客户端主动断开（关页面、网络断）也要停 —— 否则 agent 继续烧 token 没人收
        emitter.onCompletion(() -> turns.remove(conversationId));
        emitter.onTimeout(() -> {
            log.warn("会话 {} 的 SSE 超时，停止 agent", conversationId);
            stopTurn(conversationId, false);
        });
        emitter.onError(e -> stopTurn(conversationId, false));

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
        // ⚠️ 顺序有讲究：先让 agent 停下再取消订阅。
        //    反过来的话，dispose() 触发的 doOnCancel 里那次 interrupt 会先到，
        //    而此刻 activeAgent 还在 —— 结果是 interrupt 被调两次。
        //    调两次本身无害（标志位幂等），但日志里会出现两条「已请求打断」，
        //    排查时会以为发生了两次打断
        // ⚠️ 先取在飞清单再打断 —— 打断之后 ChatService 会清空它
        String pendingTools = chatService.pendingToolsOf(conversationId);
        boolean agentStopped = chatService.interrupt(conversationId);
        boolean streamStopped = stopTurn(conversationId, true, pendingTools);
        log.info("会话 {} 收到打断：agent={} stream={} 在飞工具={}",
                conversationId, agentStopped, streamStopped,
                pendingTools.isEmpty() ? "无" : pendingTools);
        return Map.of(
                "interrupted", agentStopped || streamStopped,
                // ⭐ 把「停下时有哪些操作做了一半」如实告诉调用方。
                //    其中可能有已经写进库的 —— 打断不回滚它们
                "pendingTools", pendingTools);
    }

    /**
     * 停掉某个会话正在跑的那一轮。
     *
     * @param notifyClient true = 推一条 interrupted 事件再关连接（用户主动打断）；
     *                     false = 静默收尾（超时、出错、或被新一轮顶掉）
     * @return 是否真的停掉了什么
     */
    private boolean stopTurn(String conversationId, boolean notifyClient) {
        return stopTurn(conversationId, notifyClient, "");
    }

    private boolean stopTurn(String conversationId, boolean notifyClient, String pendingTools) {
        Turn t = turns.remove(conversationId);
        if (t == null) {
            return false;
        }
        if (!t.subscription().isDisposed()) {
            t.subscription().dispose();
        }
        // ⚠️ dispose() 之后 subscribe 的三个回调一个都不会触发 ——
        //    取消既不是"完成"也不是"出错"。所以 emitter.complete() 永远没人调，
        //    连接会一直挂到 300 秒超时。实测症状：agent 确实停了，浏览器还在转圈
        try {
            if (notifyClient) {
                // ⚠️ 顺序：先说「有哪些没做完」，再说「停了」。
                //    反过来的话前端可能在收到 interrupted 后就关掉了渲染，
                //    而 tool-aborted 才是用户真正需要看到的那条
                if (!pendingTools.isEmpty()) {
                    send(t.emitter(), ChatEvent.toolAborted("system", pendingTools));
                }
                send(t.emitter(), ChatEvent.interrupted("system", "已停止本次生成"));
            }
            t.emitter().complete();
        } catch (Exception ignored) {
            // 连接可能已被客户端关掉，或已经 complete 过 —— 都不是错误
        }
        return true;
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
