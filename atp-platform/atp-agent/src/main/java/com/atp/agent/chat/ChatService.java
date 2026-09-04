package com.atp.agent.chat;

import com.atp.agent.AtpAgent;
import com.atp.agent.authoring.CaseAuthoringAgent;
import com.atp.agent.execution.ExecutionAgent;
import com.atp.agent.general.GeneralAgent;
import com.atp.agent.intent.IntentCategory;
import com.atp.agent.intent.IntentRouter;
import com.atp.agent.intent.RouteResult;
import com.atp.platform.service.ChatHistoryService;
import com.atp.agent.knowledge.KnowledgeAgent;
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

    @Autowired
    private IntentRouter router;

    @Autowired
    private ChatHistoryService history;

    /**
     * 会话 → 该会话下每种意图各自的 agent 实例。
     *
     * <p>⚠️ 按意图分开持有，是因为它们的 memory 不该混：
     * 编写 agent 的上下文里全是案例草稿的中间状态，问答 agent 拿到只会被干扰。
     *
     * <p>代价是**跨意图的上下文会断**（先写案例、再问规范，问答 agent 不知道刚才写了什么）。
     * 这是当前实现的真实边界，别说成"多 agent 共享上下文"。
     * 要接上得让 MasterAgent 做编排、子 agent 当工具挂载 —— 那是下一步。
     */
    private final ConcurrentHashMap<String, ConcurrentHashMap<IntentCategory, AtpAgent>> sessions =
            new ConcurrentHashMap<>();

    /**
     * 会话 → 当前接管它的意图（active agent）。
     *
     * <h3>⭐ 为什么需要它：每轮都重新路由会打断正在进行的任务</h3>
     *
     * 用户让 agent 写了一版案例，接着说「第二步的定位器改成 data-testid」——
     * 这句话单独看不像「写案例」，会被路由到别处，于是**上一轮的上下文全丢了**，
     * 新 agent 既不知道有个草稿，也不知道「第二步」指什么。
     *
     * <p>有了 active 之后，默认继续交给上一轮那个 agent，只有明确切换话题才换人。
     *
     * <h3>为什么不用 ag_active_agent 表</h3>
     *
     * agent 实例本身就在内存里（{@code sessions}），进程一重启它们连同 memory 一起没了。
     * 把 active 单独持久化，恢复出来的只是一个指向已消失实例的名字 ——
     * 那比没有更糟，因为它看起来是有效的。要持久化就得连 memory 一起（RedisSession），
     * 那是另一件事。
     */
    private final ConcurrentHashMap<String, IntentCategory> activeAgent = new ConcurrentHashMap<>();

    /**
     * 发一句话，拿回一串事件。
     *
     * @param conversationId 会话 id。同一个会话复用同一个 agent 实例，多轮上下文才连得上
     */
    public Flux<ChatEvent> chat(String conversationId, String userMessage, String userId) {
        // ⚠️ 先落库再开始跑。反过来的话，agent 中途出错这条提问就丢了 ——
        //    而用户看到的是「我明明问了」，回看历史却没有
        history.recordUser(conversationId, userId, userMessage);

        RouteResult routed = router.route(userMessage);
        RouteResult route = applyStickiness(conversationId, routed);

        // 没实现的意图**如实说**，不要让某个 agent 硬答。
        // 让编写 agent 去答审批问题，它会一本正经地编一套审批流程出来 —— 那比"还没做"糟得多
        if (!route.intent().implemented()) {
            String reply = unimplemented(route.intent());
            history.recordAssistant(conversationId, reply, "router", timeline(route, null));
            return Flux.just(
                    ChatEvent.route("router", describe(route)),
                    ChatEvent.message("router", reply),
                    ChatEvent.done("router"));
        }

        AtpAgent agent = sessions
                .computeIfAbsent(conversationId, id -> new ConcurrentHashMap<>())
                .computeIfAbsent(route.intent(), this::createAgent);
        String agentName = agent.name();
        activeAgent.put(conversationId, route.intent());

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

        // ⚠️ 只收集最终回复（message），不收集 thinking ——
        //    一轮能有几百个增量片段、几十 KB，而回看历史时没人要重读思考过程
        StringBuilder answer = new StringBuilder();

        return agent.raw().stream(input, options)
                .map(e -> toChatEvent(e, agentName))
                .filter(e -> e.content() != null && !e.content().isBlank())
                .doOnNext(e -> {
                    if ("message".equals(e.type())) {
                        answer.append(e.content());
                    }
                })
                .startWith(ChatEvent.route(agentName, describe(route)))
                .concatWithValues(ChatEvent.done(agentName))
                .doOnComplete(() -> {
                    // ⚠️ 在 doOnComplete 里落库而不是 doOnNext：message 事件可能分多次到，
                    //    而且只有跑完了才知道这一轮的最终回复是什么
                    if (!answer.isEmpty()) {
                        history.recordAssistant(conversationId, answer.toString(),
                                agentName, timeline(route, agentName));
                    }
                })
                .doOnCancel(() -> {
                    // ⚠️ 订阅被取消 = 下游不要了（SSE 断开、超时、或我们主动 dispose）。
                    //    这时必须把 agent 也停掉 —— 否则它会继续烧 token、继续调工具，
                    //    而没有任何人在接收结果。**这是「假打断」最常见的形态**：
                    //    前端不听了，后端还在跑。
                    log.info("会话 {} 的流被取消，停止 agent", conversationId);
                    agent.raw().interrupt();
                    persistPartial(conversationId, answer, agentName, route);
                })
                .onErrorResume(e -> {
                    // ⚠️ 错误也要推给前端。吞掉的话前端会一直等 done，
                    //    表现为「对话卡住了」，而后端日志里其实有完整堆栈
                    log.error("会话 {} 执行失败", conversationId, e);
                    String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                    // 失败也要留痕 —— 回看历史时「这里报过一次错」比一段空白有用
                    history.recordAssistant(conversationId, "（执行失败）" + msg,
                            agentName, timeline(route, agentName));
                    return Flux.just(ChatEvent.error(agentName, msg));
                });
    }

    /** 结束会话，释放 agent 实例（连同它的 memory） */
    public void close(String conversationId) {
        sessions.remove(conversationId);
        activeAgent.remove(conversationId);
    }

    /**
     * 打断当前会话正在跑的那一轮。
     *
     * <h3>⚠️ 打断的语义：不再继续下一步，不撤销已做的</h3>
     *
     * AgentScope 的 {@code interrupt()} 是<b>置一个标志位</b>，agent 在下一个检查点
     * （每次 reasoning、每次工具调用前后）才会真正停下。所以它不是"立刻掐断"：
     *
     * <ul>
     *   <li>正在等模型返回的那次请求会跑完 —— token 已经在路上了</li>
     *   <li>已经发出去的工具调用会执行完 —— {@code commit_case} 发出去就是提交了，
     *       数据库那侧没有回滚，也不该回滚（那是一次合法的、已完成的写入）</li>
     * </ul>
     *
     * <p>把它渲染成「撤销」是危险的：用户会以为数据回到了打断前。
     * 停止按钮的含义只能是「别再往下做了」。
     *
     * @return true = 确实有一轮在跑并已请求停止；false = 这个会话此刻空闲
     */
    public boolean interrupt(String conversationId) {
        IntentCategory active = activeAgent.get(conversationId);
        if (active == null) {
            return false;
        }
        ConcurrentHashMap<IntentCategory, AtpAgent> byIntent = sessions.get(conversationId);
        AtpAgent agent = byIntent == null ? null : byIntent.get(active);
        if (agent == null) {
            return false;
        }
        // 带上一条 Msg：AgentScope 把它作为 InterruptContext.userMessage 传给
        // handleInterrupt，agent 可以据此决定收尾时说什么
        agent.raw().interrupt(Msg.builder().role(MsgRole.USER).name("user")
                .content(TextBlock.builder().text("用户请求停止本次生成").build()).build());
        log.info("会话 {} 已请求打断（agent={}）", conversationId, agent.name());
        return true;
    }

    /**
     * 把打断时已经生成的半截回复落库。
     *
     * <p>不落的话历史里那一轮是<b>空白</b>，而用户明明在屏幕上看到过内容。
     * 「什么都没有」和「说到一半被停了」是两种不同的事实，历史应该能区分。
     */
    private void persistPartial(String conversationId, StringBuilder answer,
                                String agentName, RouteResult route) {
        String text = answer.isEmpty()
                ? "（已被用户停止，本轮没有产出）"
                : answer + "\n\n（已被用户停止）";
        history.recordAssistant(conversationId, text, agentName, timeline(route, agentName));
    }

    /**
     * ⭐ 会话粘性：默认继续交给上一轮那个 agent，除非有**明确**的切换信号。
     *
     * <h3>判据，以及为什么不照搬参考实现的做法</h3>
     *
     * 参考实现的 {@code isContinuation} 是关键词完全匹配 —— 消息等于「继续」「好的」
     * 才算续接。那只能识别最明显的那一类，而**用户提修改意见时同样续不上**：
     * 「第二步的定位器改成 data-testid」既不等于任何信号词，也不像「写案例」。
     *
     * <p>这里反过来：**默认粘住，需要证据才切换**。证据分两档：
     *
     * <ul>
     *   <li>L1 命中另一个意图 —— 规则层只收「说了这个就不可能是别的意思」的表达，
     *       所以它命中就是强信号，直接切</li>
     *   <li>L2 命中另一个意图且相似度 ≥ {@value #SWITCH_THRESHOLD} ——
     *       比路由自身的阈值（0.62）高一截。理由是「切换正在进行的任务」
     *       比「首次分类」的代价大得多：分错了顶多答偏一句，切错了会丢掉整个上下文</li>
     * </ul>
     *
     * <p>L3 判出的意图**不足以切换**：它是兜底层，本身就意味着「前两层都没把握」，
     * 拿一个没把握的判断去打断正在进行的任务，方向反了。
     */
    private RouteResult applyStickiness(String conversationId, RouteResult routed) {
        IntentCategory active = activeAgent.get(conversationId);
        if (active == null || active == routed.intent()) {
            return routed;
        }

        boolean strongSwitch = "L1".equals(routed.layer())
                || ("L2".equals(routed.layer()) && routed.score() >= SWITCH_THRESHOLD);

        if (strongSwitch) {
            log.info("[ROUTE] 会话 {} 切换 {} → {}（{}{}）", conversationId, active, routed.intent(),
                    routed.layer(), routed.score() < 0 ? "" : " %.3f".formatted(routed.score()));
            return routed;
        }

        // 证据不足 —— 粘住上一轮那个 agent。
        // ⚠️ layer 标成 STICKY 而不是伪装成正常路由：前端会把它显示出来，
        //    用户看到「还在案例编写」才知道自己的修改意见被接住了
        log.info("[ROUTE] 会话 {} 粘住 {}（路由建议 {} {}，证据不足）",
                conversationId, active, routed.intent(), routed.layer());
        return new RouteResult(active, "STICKY", -1,
                "延续上一轮（路由建议 %s，证据不足以切换）".formatted(routed.intent()));
    }

    /**
     * 切换正在进行的任务所需的 L2 相似度。
     *
     * <p>比路由自身的阈值（0.62）高一截 —— 打断一个进行中的任务，
     * 代价比首次分类错误大得多。
     */
    private static final double SWITCH_THRESHOLD = 0.85;

    /**
     * 这一轮的过程轨迹。存路由结论，不存 thinking 全文。
     *
     * <p>回看历史时要的是「它当时怎么判的、谁接的」，不是重读一遍思考过程。
     */
    private String timeline(RouteResult route, String agentName) {
        return """
                {"intent":"%s","layer":"%s","score":%s,"reason":"%s","agent":"%s"}"""
                .formatted(route.intent(), route.layer(),
                        route.score() < 0 ? "null" : "%.3f".formatted(route.score()),
                        route.reason() == null ? "" : route.reason().replace("\"", "'"),
                        agentName == null ? "" : agentName);
    }

    private AtpAgent createAgent(IntentCategory intent) {
        return switch (intent) {
            case CASE_AUTHORING -> ctx.getBean(CaseAuthoringAgent.class);
            case KNOWLEDGE_QA -> ctx.getBean(KnowledgeAgent.class);
            case EXECUTION -> ctx.getBean(ExecutionAgent.class);
            case OTHER -> ctx.getBean(GeneralAgent.class);
            // implemented() 已经在上面挡掉了，走到这里说明枚举加了新值却忘了接 —— 直接崩，别静默
            default -> throw new IllegalStateException("没有 agent 处理意图：" + intent);
        };
    }

    /** 路由结论给用户看的一行。带上判定层，出错时用户的反馈才有定位价值 */
    private String describe(RouteResult r) {
        return r.score() < 0
                ? "%s · %s".formatted(r.intent().display(), r.layer())
                : "%s · %s %.2f".formatted(r.intent().display(), r.layer(), r.score());
    }

    private String unimplemented(IntentCategory intent) {
        return "「%s」这块还没接上助手。当前能对话处理的是**案例编写**和**规范问答**；%s"
                .formatted(intent.display(), switch (intent) {
                    case CASE_QUERY -> "查案例请用左侧的案例中心。";
                    case EXECUTION -> "派发执行与看录像请用执行状态面板。";
                    case APPROVAL -> "审批请用审批中心。";
                    default -> "换个说法试试，或者直接说你想做什么。";
                });
    }

    private ChatEvent toChatEvent(Event event, String agent) {
        String text = extractText(event);
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
