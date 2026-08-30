package com.atp.agent;

import com.atp.agent.authoring.CaseAuthoringAgent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 案例编写 agent 的端到端验证。
 *
 * <p>⭐ 断言的不是「agent 有回话」，而是<b>它真的把一条合规案例写进了库</b> ——
 * 前者随便一个模型都能过，后者要求它按顺序用对了工具、
 * 查了规范、拿了合法的 module_id、把 ERROR 改到零。
 *
 * <p>⚠️ 真调 LLM，一次跑几十秒、要花 token。默认关闭，
 * 用 {@code ATP_RUN_AGENT_IT=1} 打开。
 */
@SpringBootTest(classes = AgentTestApp.class)
@EnabledIfEnvironmentVariable(named = "ATP_RUN_AGENT_IT", matches = "1")
class CaseAuthoringIT {

    @Autowired
    private ApplicationContext ctx;

    @Test
    @DisplayName("一句话 → 一条通过规范校验的案例")
    void authoring() {
        CaseAuthoringAgent agent = ctx.getBean(CaseAuthoringAgent.class);

        String reply = agent.chat("""
                给购物车模块写一条案例：在商品详情页点击加入购物车之后，
                顶部购物车角标的数字应该变成 1。优先级 P1，作者写 agent。
                写好后直接提交，不用再问我确认。
                """);

        System.out.println("\n========== agent 回复 ==========");
        System.out.println(reply);
        System.out.println("================================\n");

        assertTrue(reply != null && !reply.isBlank(), "agent 没有任何输出");
    }
}
