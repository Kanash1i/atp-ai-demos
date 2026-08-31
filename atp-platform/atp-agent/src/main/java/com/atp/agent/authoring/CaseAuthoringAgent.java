package com.atp.agent.authoring;

import com.atp.agent.AtpAgent;
import com.atp.agent.intent.IntentCategory;
import com.atp.agent.tools.CaseCatalogTools;
import com.atp.agent.tools.CaseDraftTools;
import com.atp.agent.tools.ExecutionTools;
import com.atp.agent.tools.PageInspectTools;
import com.atp.agent.tools.StandardsTools;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * 案例编写 Agent —— 自然语言 → 可执行的 ATP 案例。
 *
 * <h3>它凭什么能写对</h3>
 *
 * 不是因为模型强，而是因为**它被工具约束着**：
 * <ul>
 *   <li>写之前必须 {@code search_standards} —— 实测裸模型对「CLICK 用什么等待策略」
 *       会答 AUTO / EXPONENTIAL_BACKOFF，编得笃定又工整</li>
 *   <li>{@code module_id} 只能从 {@code list_modules} 取，编的会被外键拦下</li>
 *   <li>每次 {@code save_draft} 都返回规范校验结果，ERROR 不清零就 {@code commit_case} 不了</li>
 * </ul>
 *
 * <p>⭐ <b>agent 不是特权用户</b>：它走的是与人完全相同的写入路径
 * （{@code CaseWriteService}），受完全相同的约束。人做不了的事它也做不了。
 *
 * <h3>为什么是 prototype 作用域</h3>
 *
 * ReActAgent 持有 memory（多轮对话上下文）与运行状态。做成单例的话，
 * 两个用户的会话会串到一起 —— 而且这种串扰不会报错，只会让 agent
 * 突然引用另一个人说过的话。
 */
@Slf4j
@Component
@org.springframework.context.annotation.Scope("prototype")
public class CaseAuthoringAgent implements AtpAgent {

    public static final String NAME = "CaseAuthoringAgent";

    private final ReActAgent agent;

    public CaseAuthoringAgent(@Qualifier("strongModel") Model model,
                              StandardsTools standardsTools,
                              CaseCatalogTools catalogTools,
                              CaseDraftTools draftTools,
                              PageInspectTools inspectTools,
                              ExecutionTools executionTools) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(standardsTools);
        toolkit.registerTool(catalogTools);
        toolkit.registerTool(draftTools);
        toolkit.registerTool(inspectTools);
        toolkit.registerTool(executionTools);

        this.agent = ReActAgent.builder()
                .name(NAME)
                .description("把自然语言描述变成符合 ATP 规范的可执行测试案例")
                .sysPrompt(loadPrompt())
                .model(model)
                .toolkit(toolkit)
                .memory(new InMemoryMemory())
                // ⚠️ 上限不能太小：查规范 → 查模块 → 看样例 → 取编号 → 建草稿 →
                //    存 → 校验不过再改再存，一条案例正常就要七八轮。
                //    给太少的话它会在还没校验通过时就被截断，交出一条不合规的案例
                .maxIters(20)
                .build();
    }

    /** 单轮对话。多轮由 memory 承接 */
    public String chat(String userMessage) {
        Msg reply = agent.call(Msg.builder()
                .role(MsgRole.USER).name("user")
                .content(TextBlock.builder().text(userMessage).build())
                .build()).block();
        return extractText(reply);
    }

    @Override
    public IntentCategory handles() {
        return IntentCategory.CASE_AUTHORING;
    }

    @Override
    public ReActAgent raw() {
        return agent;
    }

    private static String extractText(Msg msg) {
        if (msg == null || msg.getContent() == null) {
            return "";
        }
        return msg.getContent().stream()
                .filter(TextBlock.class::isInstance).map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .reduce("", String::concat);
    }

    private static String loadPrompt() {
        try {
            return new String(new ClassPathResource("prompts/case-authoring-agent.md")
                    .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // ⚠️ 提示词加载不了就该起不来：一个没有系统提示词的 agent 会"正常工作"，
            //    只是完全不按规范做事 —— 这种失败比崩溃难发现得多
            throw new UncheckedIOException("案例编写 agent 的系统提示词加载失败", e);
        }
    }
}
