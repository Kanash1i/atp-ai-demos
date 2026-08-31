package com.atp.agent.config;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.JdkHttpTransport;

import java.net.http.HttpClient;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 模型接入。
 *
 * <h3>为什么绕开 starter 的 provider 枚举</h3>
 *
 * {@code agentscope-spring-boot-starter} 的 {@code ModelProviderType} 只有
 * DASHSCOPE / OPENAI / GEMINI / ANTHROPIC 四种，而我们用的是 **DeepSeek** ——
 * 它兼容 OpenAI 协议但不是 OpenAI。直接构造 {@link OpenAIChatModel} 并指定
 * {@code baseUrl} 即可，参考实现 gogo-agent 的 test-env 已经验证过这条路。
 *
 * <h3>三档模型</h3>
 *
 * fast / strong / stable 是 AgentScope 生态里的惯例分工（快而便宜 / 强推理 / 输出稳定）。
 * DeepSeek 目前只暴露一个 {@code deepseek-v4-flash}，所以三档默认指向同一个模型 ——
 * <b>但接口先按三档留出来</b>：后期要把 MasterAgent 换成更强的模型、
 * 或把意图识别换成更便宜的模型时，改的是配置而不是代码。
 *
 * <p>⚠️ key 与 base-url 一律从仓库根 {@code .env} 注入，代码与 yml 里不得出现硬编码。
 */
@Slf4j
@Configuration
public class AtpModelConfig {

    /**
     * 空闲连接保留多久。
     *
     * <h3>⚠️ 为什么要特意调短</h3>
     *
     * ReAct 的一轮里，「调工具」和「把工具结果交给模型」之间可能隔很久 ——
     * {@code run_case_once} 要等一条案例真跑完，几十秒起步。
     * 这段时间里模型服务端（或中间网络设备）会把 keep-alive 连接关掉，
     * 而客户端并不知道，下次请求复用这条陈旧连接才发现：
     *
     * <pre>
     *   java.io.IOException: closed
     *   java.io.IOException: BUFFER_UNDERFLOW with EOF, 955 bytes non decrypted.
     *   → reactor.core.Exceptions$RetryExhaustedException: Retries exhausted: 2/2
     * </pre>
     *
     * <p>实测规律很干净：**工具跑得越久，这一轮越容易挂** ——
     * 一次快查询就成功，两次查询开始挂，等案例跑完必挂。
     *
     * <p>把保留时间调到工具调用的典型时长以下，长工具跑完后连接已被主动丢弃，
     * 下次请求新建一条，撞不上陈旧连接。代价是多一次 TLS 握手（约 100ms），
     * 在对话场景里完全不值一提。
     */
    private static final int KEEP_ALIVE_SECONDS = 15;

    static {
        // ⚠️ 这一行才是真正起作用的那个：JDK 的 HttpClient 用系统属性管连接池的空闲超时，
        //    默认 1200 秒 —— 它不看 HttpTransportConfig 里的 keepAliveDuration
        //    （那个是给 OkHttp 实现用的）。两处都设，是因为将来换传输层实现时不必再想起这件事。
        //    必须在任何 HttpClient 被创建之前设置，所以放静态块。
        if (System.getProperty("jdk.httpclient.keepalive.timeout") == null) {
            System.setProperty("jdk.httpclient.keepalive.timeout", String.valueOf(KEEP_ALIVE_SECONDS));
        }
    }

    @Value("${atp.model.api-key}")
    private String apiKey;

    @Value("${atp.model.base-url}")
    private String baseUrl;

    @Value("${atp.model.fast-name}")
    private String fastName;

    @Value("${atp.model.strong-name}")
    private String strongName;

    @Value("${atp.model.stable-name}")
    private String stableName;

    /** 高频、低成本：意图识别兜底、会话标题 */
    @Bean("fastModel")
    public Model fastModel() {
        return build(fastName);
    }

    /** 主力：MasterAgent 与各子 Agent 的推理 */
    @Bean("strongModel")
    @Primary
    public Model strongModel() {
        return build(strongName);
    }

    /** 要求输出稳定、少发挥：问题改写、结构化生成 */
    @Bean("stableModel")
    public Model stableModel() {
        return build(stableName);
    }

    private Model build(String modelName) {
        log.info("模型就绪 {} @ {}（keep-alive {}s）", modelName, baseUrl, KEEP_ALIVE_SECONDS);
        return OpenAIChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .baseUrl(baseUrl)
                // ⚠️ 流式必开：agent 的进度要实时推给前端，
                //    非流式的话用户会盯着一个转圈等十几秒
                .stream(true)
                .httpTransport(new JdkHttpTransport(
                        HttpClient.newBuilder()
                                .connectTimeout(Duration.ofSeconds(15))
                                .build(),
                        HttpTransportConfig.builder()
                                .keepAliveDuration(Duration.ofSeconds(KEEP_ALIVE_SECONDS))
                                .maxIdleConnections(2)
                                .connectTimeout(Duration.ofSeconds(15))
                                // 一次流式回复可能持续一分钟以上（长案例的完整报告）
                                .readTimeout(Duration.ofMinutes(5))
                                .build()))
                .build();
    }
}
