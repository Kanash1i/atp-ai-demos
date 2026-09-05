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
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.plan.model.Plan;
import io.agentscope.core.plan.model.SubTask;
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
     * 每个会话「已经发起但还没拿到结果」的工具调用。
     *
     * <h3>为什么需要它</h3>
     *
     * 打断时用户最需要知道的不是「停了」，而是<b>停下时有哪些操作已经做了一半</b> ——
     * 因为其中可能有已经写进库的（{@code commit_case} 发出去就是提交了）。
     *
     * <p>AgentScope 的 {@code InterruptContext.pendingToolCalls} 给的就是这份清单，
     * 但 {@code handleInterrupt} 是 protected 而 {@code ReActAgent} 的构造函数是 private，
     * 继承这条路走不通。所以改成在事件流里自己跟踪：
     * 看到工具调用发起就记上，看到结果就划掉，打断时剩下的就是「在飞的」。
     *
     * <p>⚠️ 用 LinkedHashSet 保持顺序 —— 用户读这份清单时，
     * 「先调了什么再调了什么」比字母序有意义得多。
     */
    private final ConcurrentHashMap<String, java.util.Set<String>> inFlightTools = new ConcurrentHashMap<>();

    /**
     * 上一次推给前端的任务清单指纹。
     *
     * <p>⚠️ 不记的话每个事件都会推一份完整清单 —— 一轮几百个 thinking 事件，
     * 就是几百份一模一样的 JSON。前端要么自己去重，要么把固定区域刷爆。
     * 变了才推，是把「什么时候算变了」这个判断留在产生端。
     */
    private final ConcurrentHashMap<String, String> lastPlanDigest = new ConcurrentHashMap<>();

    /**
     * 此刻真的有一轮在跑的会话。
     *
     * <p>与 {@link #activeAgent} 的区别：那个是<b>会话级</b>的（上次由谁接管，
     * 用于会话粘性，跨轮保留），这个是<b>轮次级</b>的（这一轮跑没跑完）。
     * 判断「能不能打断」只能看后者。
     */
    private final java.util.Set<String> running =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

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
        running.add(conversationId);
        StringBuilder answer = new StringBuilder();

        return agent.raw().stream(input, options)
                .doOnNext(e -> trackTools(conversationId, e))
                // ⭐ 每个事件后看一眼计划有没有变，变了就多推一条 plan 事件。
                //    用 concatMap 而不是 map，因为一个上游事件可能产出两条下游事件
                .concatMap(e -> {
                    ChatEvent base = toChatEvent(e, agentName);
                    ChatEvent planEvent = planSnapshotIfChanged(conversationId, agent, agentName);
                    return planEvent == null ? Flux.just(base) : Flux.just(planEvent, base);
                })
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
                    inFlightTools.remove(conversationId);
                    lastPlanDigest.remove(conversationId);
                    running.remove(conversationId);
                })
                .doOnCancel(() -> {
                    // ⚠️ 订阅被取消 = 下游不要了（SSE 断开、超时、或我们主动 dispose）。
                    //    这时必须把 agent 也停掉 —— 否则它会继续烧 token、继续调工具，
                    //    而没有任何人在接收结果。**这是「假打断」最常见的形态**：
                    //    前端不听了，后端还在跑。
                    log.info("会话 {} 的流被取消，停止 agent", conversationId);
                    agent.raw().interrupt();
                    persistPartial(conversationId, answer, agentName, route);
                    inFlightTools.remove(conversationId);
                    lastPlanDigest.remove(conversationId);
                    running.remove(conversationId);
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
        inFlightTools.remove(conversationId);
        lastPlanDigest.remove(conversationId);
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
        // ⚠️ 先看这一轮是不是真的在跑。
        //
        //    不能只看 activeAgent —— 它是**会话级**的（记住「上次由谁接管」，
        //    给会话粘性用），一轮结束后不会清。所以对已经跑完的会话打断，
        //    这里照样取得到 agent 然后返回 true，而实际什么都没停。
        //
        //    实测（前端报的）：连着打断两次，第二次仍是 {"interrupted":true}，
        //    调用方据此以为「刚才那轮被我停了」，而它早就自己跑完了。
        //    这个布尔要表达的是「这次停了什么」，不是「这个会话曾经有过 agent」。
        if (!running.contains(conversationId)) {
            return false;
        }
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
        String pending = inFlightOf(conversationId);
        log.info("会话 {} 已请求打断（agent={}，在飞工具：{}）",
                conversationId, agent.name(), pending.isEmpty() ? "无" : pending);
        return true;
    }

    /**
     * 取当前任务清单的快照，只在变化时返回事件，否则返回 null。
     *
     * <p>⚠️ 用 {@code toMarkdown} 的输出做指纹而不是对象身份 ——
     * {@code PlanNotebook} 是就地改的（{@code updateSubtaskState} 直接改字段），
     * 对象引用永远相同，靠 {@code ==} 判断不出任何变化。
     */
    private ChatEvent planSnapshotIfChanged(String conversationId, AtpAgent agent, String agentName) {
        PlanNotebook notebook = agent.raw().getPlanNotebook();
        if (notebook == null) {
            return null;
        }
        Plan plan = notebook.getCurrentPlan();
        if (plan == null) {
            return null;
        }
        String digest = plan.toMarkdown(true);
        String prev = lastPlanDigest.get(conversationId);
        if (digest.equals(prev)) {
            return null;
        }
        lastPlanDigest.put(conversationId, digest);
        return ChatEvent.plan(agentName, planJson(plan));
    }

    /**
     * 把计划序列化成前端好渲染的 JSON。
     *
     * <p>不直接推 {@code toMarkdown} 的原因：那是给模型看的格式，
     * 前端要的是结构化数据 —— 它得按状态给每个子任务上不同的颜色，
     * 而从 Markdown 里正则出状态是倒着来的。
     */
    private String planJson(Plan plan) {
        StringBuilder sb = new StringBuilder("{\"name\":").append(quote(plan.getName()))
                .append(",\"subtasks\":[");
        java.util.List<SubTask> subs = plan.getSubtasks() == null ? java.util.List.<SubTask>of() : plan.getSubtasks();
        for (int i = 0; i < subs.size(); i++) {
            SubTask t = subs.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"name\":").append(quote(t.getName()))
              .append(",\"state\":").append(quote(t.getState() == null ? "TODO" : t.getState().name()))
              .append(",\"outcome\":").append(quote(t.getOutcome()))
              .append('}');
        }
        return sb.append("]}").toString();
    }

    /** 最小的 JSON 字符串转义 —— 子任务名里可能有引号和换行 */
    private String quote(String raw) {
        if (raw == null) {
            return "null";
        }
        return '"' + raw.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "") + '"';
    }

    /** 打断时还在飞的工具名（给 controller 推 tool-aborted 事件用） */
    public String pendingToolsOf(String conversationId) {
        return inFlightOf(conversationId);
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

    /**
     * 从事件里认出工具调用的开始与结束，维护「在飞」清单。
     *
     * <p>⚠️ 只在事件流里做，不去碰 agent 内部状态 —— 那是框架的东西，
     * 我们只观察它对外推的事件。
     */
    private void trackTools(String conversationId, Event event) {
        Msg msg = event.getMessage();
        if (msg == null || msg.getContent() == null) {
            return;
        }
        java.util.Set<String> flight = inFlightTools
                .computeIfAbsent(conversationId, k -> java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>()));
        for (var block : msg.getContent()) {
            if (block instanceof ToolUseBlock use && isRealToolName(use.getName())) {
                // 发起：记上
                flight.add(use.getName());
            } else if (block instanceof ToolResultBlock res) {
                // 有结果了：划掉。⚠️ 按 name 划而不是 id ——
                //    同名工具连续调两次时会误划一次，但清单是给人看的，
                //    「commit_case 可能没做完」比精确到第几次调用更重要
                if (isRealToolName(res.getName())) {
                    flight.remove(res.getName());
                }
            }
        }
    }

    /**
     * 是不是一个真实的工具名。
     *
     * <h3>⚠️ 流式增量里会出现内部占位名</h3>
     *
     * AgentScope 在推增量 chunk 时，工具块的 name 可能是 {@code __fragment__} ——
     * 那是"这一片还没拼完"的标记，不是工具。实测把它当工具名记下来之后，
     * 打断时报给用户的是「在飞工具：__fragment__」，
     * <b>看起来像系统内部泄漏，而用户完全不知道那是什么</b>。
     *
     * <p>过滤规则保守：只认小写字母加下划线的形式（我们的工具名都是
     * {@code search_standards} / {@code commit_case} 这种），
     * 认不出来的一律不报 —— 少报一个工具，好过报一个用户看不懂的词。
     */
    private boolean isRealToolName(String name) {
        return name != null && name.matches("[a-z][a-z0-9_]{2,}");
    }

    /** 打断时还在飞的工具名，逗号分隔。没有就返回空串 */
    private String inFlightOf(String conversationId) {
        java.util.Set<String> flight = inFlightTools.get(conversationId);
        if (flight == null || flight.isEmpty()) {
            return "";
        }
        synchronized (flight) {
            return String.join("、", flight);
        }
    }

    /**
     * 从事件里取出要推给前端的文本。
     *
     * <h3>⚠️ 工具块的文本是嵌套的，不在顶层</h3>
     *
     * 原来只在顶层找 {@code TextBlock}，而工具调用的内容分别在
     * {@code ToolUseBlock.getInput()} 和 {@code ToolResultBlock.getOutput()} 里 ——
     * 顶层一个 TextBlock 都没有，所以 {@code extractText} 返回空串，
     * 又被下游的 {@code filter(不为空)} 整个丢掉。
     *
     * <p>结果是 <b>tool 事件从来没推出去过</b>：前端那个「工具调用可视化」
     * 一直没有数据，而这恰恰是这个 demo 最该展示的东西 ——
     * 用户能看见 agent 查了哪条规范、拿了哪个模块、校验报了什么。
     *
     * <p>症状极隐蔽：没有报错，没有空白面板，只是那一类事件根本不出现，
     * 看起来就像「agent 这次没调工具」。
     */
    private String extractText(Event event) {
        Msg msg = event.getMessage();
        if (msg == null || msg.getContent() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (var block : msg.getContent()) {
            if (block instanceof TextBlock t && t.getText() != null) {
                sb.append(t.getText());
            } else if (block instanceof ToolUseBlock use) {
                // ⚠️ 流式增量里的 __fragment__ 是「这一片还没拼完」的内部标记，不是工具。
                //    实测漏掉它时，一轮里推了 180 条「▸ __fragment__」给前端 ——
                //    真正的工具调用被淹在噪音里
                if (!isRealToolName(use.getName())) {
                    continue;
                }
                // 「正在调用哪个工具、参数是什么」—— 前端渲染成工具卡片的标题
                sb.append("▸ ").append(use.getName());
                if (use.getInput() != null && !use.getInput().isEmpty()) {
                    sb.append(' ').append(abbreviate(String.valueOf(use.getInput()), 160));
                }
            } else if (block instanceof ToolResultBlock res) {
                if (!isRealToolName(res.getName())) {
                    continue;
                }
                // 结果本身还是一层 ContentBlock 列表，要再剥一层
                sb.append("✓ ").append(res.getName()).append(' ')
                        .append(abbreviate(flattenOutput(res), 400));
            }
        }
        return sb.toString();
    }

    /** 剥开 ToolResultBlock 的嵌套内容 */
    private String flattenOutput(ToolResultBlock res) {
        if (res.getOutput() == null) {
            return "";
        }
        return res.getOutput().stream()
                .filter(TextBlock.class::isInstance).map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.joining(" "));
    }

    /**
     * 截断长文本。
     *
     * <p>⚠️ 工具返回可能很长（{@code atp schema} 就有 4000 多字符），
     * 原样推给前端会把对话区淹掉，而用户要看的只是「调了什么、大概返回了什么」。
     * 完整内容在服务端日志里。
     */
    private String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        // ⚠️ 工具返回里常常带着**字面量的** \n（工具把结果 JSON 序列化过一道），
        //    而不是真换行。不还原的话前端拿到的是一串「\n」字符 ——
        //    它要么显示成乱码，要么得自己反转义，而反转义 400 字截断后的文本
        //    很容易踩到半个转义序列。在源头还原掉最省事。
        String unescaped = text.replace("\\n", " ").replace("\\t", " ").replace("\\\"", "\"");
        String flat = unescaped.replaceAll("\\s+", " ").trim();
        if (flat.length() <= max) {
            return flat;
        }
        // ⚠️ 截断点不能落在转义序列或多字节字符中间。
        //    往前退到最近一个空格，退不动就直接截 —— 宁可短几个字，
        //    也不要给出一个解析不了的片段
        String cut = flat.substring(0, max);
        int lastSpace = cut.lastIndexOf(' ');
        return (lastSpace > max - 40 ? cut.substring(0, lastSpace) : cut) + "…";
    }
}
