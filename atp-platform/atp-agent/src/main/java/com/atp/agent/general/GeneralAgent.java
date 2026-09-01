package com.atp.agent.general;

import com.atp.agent.AtpAgent;
import com.atp.agent.intent.IntentCategory;
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
 * 通用对话 —— 接住所有落到 {@link IntentCategory#OTHER} 的消息。
 *
 * <h3>为什么 OTHER 需要一个真的 agent，而不是一句固定话术</h3>
 *
 * 原先 OTHER 回的是「这块还没接上助手」，但那是给**未实现的功能**准备的说辞。
 * 用户问「你是谁」「今天吃什么」并不是在找一个没做的功能 ——
 * 拿"还没接上"去回答，既答非所问，又让人以为平台坏了。
 *
 * <p>更要紧的是：**三层路由的 L3 存在的意义就是兜住这一类**。
 * L3 判出 OTHER 之后没有任何东西处理它，等于这一层只做了分类没做处理，
 * 那还不如不分。
 *
 * <h3>它没有任何工具，这是刻意的</h3>
 *
 * 通用问答不该能碰案例、执行、审批。给它工具的话，
 * 一句「帮我把那个删了」就可能被它当真去执行 ——
 * 而它恰恰是最容易收到模糊指令的那个 agent。
 */
@Slf4j
@Component
@org.springframework.context.annotation.Scope("prototype")
public class GeneralAgent implements AtpAgent {

    public static final String NAME = "GeneralAgent";

    private final ReActAgent agent;

    public GeneralAgent(@Qualifier("fastModel") Model model) {
        this.agent = ReActAgent.builder()
                .name(NAME)
                .description("回答与平台功能无关的一般性问题，并在合适时把用户引回平台能做的事")
                .sysPrompt(loadPrompt())
                // ⚠️ 空工具集 —— 通用问答不该有任何副作用能力
                .toolkit(new Toolkit())
                .model(model)
                .memory(new InMemoryMemory())
                // 没有工具，一轮就该出结果；给多了只是浪费
                .maxIters(3)
                .build();
    }

    @Override
    public IntentCategory handles() {
        return IntentCategory.OTHER;
    }

    @Override
    public ReActAgent raw() {
        return agent;
    }

    private static String loadPrompt() {
        try {
            return new String(new ClassPathResource("prompts/general-agent.md")
                    .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("通用对话 agent 的系统提示词加载失败", e);
        }
    }
}
