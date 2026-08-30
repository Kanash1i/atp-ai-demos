package com.atp.agent;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 模型链路冒烟：AgentScope 的 {@code OpenAIChatModel} 能不能驱动 DeepSeek。
 *
 * <p>⚠️ 这是 M3 的**第一个技术风险点** —— 在此之前只看过 javap 的方法签名，
 * 没有实际跑过。链路不通的话，后面所有 agent 都是空中楼阁。
 *
 * <p>需要 .env 里的 LLM_API_KEY。没有就跳过，不让它红一片掩盖真正的回归。
 */
class ModelSmokeTest {

    private static final String API_KEY = System.getenv("LLM_API_KEY");
    private static final String BASE_URL =
            System.getenv().getOrDefault("LLM_BASE_URL", "https://api.deepseek.com/v1");
    private static final String MODEL =
            System.getenv().getOrDefault("LLM_MODEL", "deepseek-v4-flash");

    @Test
    @DisplayName("AgentScope 的 OpenAIChatModel 能驱动 DeepSeek")
    void chat() {
        assumeTrue(API_KEY != null && !API_KEY.isBlank(), "LLM_API_KEY 未设置，跳过");

        Model model = OpenAIChatModel.builder()
                .apiKey(API_KEY).modelName(MODEL).baseUrl(BASE_URL).stream(true).build();

        List<Msg> messages = List.of(
                Msg.builder().role(MsgRole.SYSTEM).name("system")
                        .content(TextBlock.builder()
                                .text("你是 ATP 测试平台的助手。回答要极简。").build()).build(),
                Msg.builder().role(MsgRole.USER).name("user")
                        .content(TextBlock.builder()
                                .text("CLICK 步骤的 wait_strategy 应该设成什么？只回答枚举值本身。").build()).build());

        List<ChatResponse> responses = model.stream(messages, null, null).collectList().block();
        assertFalse(responses == null || responses.isEmpty(), "模型没有返回任何内容");

        String text = responses.stream()
                .flatMap(r -> r.getContent() == null ? java.util.stream.Stream.empty() : r.getContent().stream())
                .filter(TextBlock.class::isInstance).map(TextBlock.class::cast)
                .map(TextBlock::getText).reduce("", String::concat);

        System.out.println("  模型回复: " + text.trim());
        assertFalse(text.isBlank(), "回复是空的");
    }
}
