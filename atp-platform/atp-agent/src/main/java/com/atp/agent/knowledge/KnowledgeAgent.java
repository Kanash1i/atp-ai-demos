package com.atp.agent.knowledge;

import com.atp.agent.AtpAgent;
import com.atp.agent.intent.IntentCategory;
import com.atp.agent.tools.CaseCatalogTools;
import com.atp.agent.tools.StandardsTools;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * 规范与手册问答 —— 只查资料，**不改任何东西**。
 *
 * <h3>与 CaseAuthoringAgent 的边界</h3>
 *
 * 「写一条登录案例」→ 编写 agent（要产出案例、要落库）<br>
 * 「登录案例该怎么写才合规」→ 这个 agent（只要答案）
 *
 * <p>两者听起来都在说登录，但处理它们的能力集完全不同 —— 这个 agent
 * **一个写工具都没有**，它想改也改不了。边界靠工具集划分，不靠提示词自律。
 */
@Slf4j
@Component
@org.springframework.context.annotation.Scope("prototype")
public class KnowledgeAgent implements AtpAgent {

    public static final String NAME = "KnowledgeAgent";

    private final ReActAgent agent;

    public KnowledgeAgent(@Qualifier("fastModel") Model model,
                          StandardsTools standardsTools,
                          CaseCatalogTools catalogTools) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(standardsTools);
        // 案例目录是只读的，问「订单模块有哪些规范上的注意点」时经常要看几条真案例
        toolkit.registerTool(catalogTools);

        this.agent = ReActAgent.builder()
                .name(NAME)
                .description("回答 ATP 的规范、写法要求与术语问题，答案必须给出出处")
                .sysPrompt(loadPrompt())
                .model(model)
                .toolkit(toolkit)
                .memory(new InMemoryMemory())
                // 问答比写案例轻得多：检索一两次就该给答案。给太多轮它会反复检索同一个问题
                .maxIters(8)
                .build();
    }

    @Override
    public IntentCategory handles() {
        return IntentCategory.KNOWLEDGE_QA;
    }

    @Override
    public ReActAgent raw() {
        return agent;
    }

    private static String loadPrompt() {
        try {
            return new String(new ClassPathResource("prompts/knowledge-agent.md")
                    .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 没有系统提示词的 agent 会「正常工作」，只是完全不按规矩答 —— 这种失败最难发现
            throw new UncheckedIOException("规范问答 agent 的系统提示词加载失败", e);
        }
    }
}
