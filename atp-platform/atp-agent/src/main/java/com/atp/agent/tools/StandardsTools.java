package com.atp.agent.tools;

import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 规范与手册的检索工具。
 *
 * <h3>⭐ 为什么这是 CaseAuthoringAgent 的第一个工具</h3>
 *
 * 实测过：不给检索的话，模型对「CLICK 用什么 wait_strategy」的回答是
 * {@code AUTO} 或 {@code EXPONENTIAL_BACKOFF} —— 编一个听起来像等待策略的词，
 * 而且语气笃定、格式正确。它写出来的案例会一路通过 JSON 校验，
 * 直到执行时才失败，那时已经很难追回是哪一步开始错的。
 *
 * <p>所以写案例之前必须先查规范，这不是"最好查一下"，是**前置条件**。
 */
@Slf4j
@Component
public class StandardsTools {

    @Autowired
    private Knowledge knowledge;

    @Tool(name = "search_standards",
            description = "检索 ATP 的内部规范与操作手册。写案例前必须先用它确认规范要求，"
                    + "尤其是等待策略、定位器写法、断言要求这类容易凭经验想当然的地方。")
    public String searchStandards(
            @ToolParam(name = "query",
                    description = "要查的问题，用自然语言。例：CLICK 步骤的等待策略 / XPath 的书写规范") String query,
            @ToolParam(name = "top_k",
                    description = "返回条数，默认 3，最多 8") Integer topK) {

        int limit = topK == null ? 3 : Math.min(Math.max(topK, 1), 8);
        List<Document> hits = knowledge.retrieve(query,
                RetrieveConfig.builder().limit(limit).build()).block();

        if (hits == null || hits.isEmpty()) {
            // ⚠️ 明确说"没查到"，而不是返回空串 —— 空串会被模型当成"这里没有规定"，
            //    于是它就自由发挥了。说清楚是"检索没命中"，它才知道该换个问法再查
            return "没有检索到相关规范。换个说法再查一次，或者明确说明你不确定。";
        }

        StringBuilder sb = new StringBuilder("检索到 ").append(hits.size()).append(" 条规范：\n");
        for (Document d : hits) {
            sb.append("\n【").append(d.getPayloadValue("anchor")).append("】\n")
              .append(d.getPayloadValue("display_text")).append('\n');
        }
        log.info("[TOOL][search_standards] {} → {} 条", query, hits.size());
        return sb.toString();
    }
}
