package com.atp.agent.config;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
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
        log.info("模型就绪 {} @ {}", modelName, baseUrl);
        return OpenAIChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .baseUrl(baseUrl)
                // ⚠️ 流式必开：agent 的进度要实时推给前端，
                //    非流式的话用户会盯着一个转圈等十几秒
                .stream(true)
                .build();
    }
}
