package com.atp.agent.execution;

import com.atp.agent.AtpAgent;
import com.atp.agent.intent.IntentCategory;
import com.atp.agent.tools.ExecutionQueryTools;
import com.atp.agent.tools.ExecutionTools;
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
 * 执行相关的问答与自验。
 *
 * <h3>它的存在解决了一处真实的错配</h3>
 *
 * {@code run_case_once} 原先只挂在 {@code CaseAuthoringAgent} 上（编写流程的第 9 步）。
 * 于是「写完顺手跑一下」能用，但用户单独说「把这条案例跑一次」时，
 * 三层路由会判成 {@code EXECUTION} → 那时没有对应 agent → 直接回绝，
 * **而工具就在隔壁 agent 手里**。
 *
 * <p>能力归属和意图路由必须对得上：用户怎么说，就该由谁来接。
 *
 * <h3>它没有的能力</h3>
 *
 * **不能派发整批**。它只能跑单条自验（{@code run_case_once}）。
 * 派发一个测试计划涉及排队、配额、优先级，那是平台调度，
 * 不该由一句自然语言触发 —— 说错一个词就可能占满整个执行机资源池。
 */
@Slf4j
@Component
@org.springframework.context.annotation.Scope("prototype")
public class ExecutionAgent implements AtpAgent {

    public static final String NAME = "ExecutionAgent";

    private final ReActAgent agent;

    public ExecutionAgent(@Qualifier("fastModel") Model model,
                          ExecutionQueryTools queryTools,
                          ExecutionTools executionTools) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(queryTools);
        // 单条自验 —— 与编写 agent 共用同一个工具实例，它内部走 CLI
        toolkit.registerTool(executionTools);

        this.agent = ReActAgent.builder()
                .name(NAME)
                .description("查执行状态、看失败原因与录像、跑单条案例自验")
                .sysPrompt(loadPrompt())
                .model(model)
                .toolkit(toolkit)
                .memory(new InMemoryMemory())
                // 查询比写案例轻：通常一两次工具调用就能答。给太多轮它会反复查同一个东西
                .maxIters(8)
                .build();
    }

    @Override
    public IntentCategory handles() {
        return IntentCategory.EXECUTION;
    }

    @Override
    public ReActAgent raw() {
        return agent;
    }

    private static String loadPrompt() {
        try {
            return new String(new ClassPathResource("prompts/execution-agent.md")
                    .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 没有系统提示词的 agent 会「正常工作」，只是完全不按规矩做事 —— 这种失败最难发现
            throw new UncheckedIOException("执行 agent 的系统提示词加载失败", e);
        }
    }
}
