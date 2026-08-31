package com.atp.agent.tools;

import com.atp.agent.cli.AtpCliClient;
import com.atp.agent.cli.CliResult;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 看一眼被测系统 —— 让 agent 不必猜页面长什么样。
 *
 * <h3>为什么要有这个工具</h3>
 *
 * 同一个错误已经复现两次：agent 把商品详情页写成 {@code /product/p001}，
 * 而真实路由是 {@code /products/{id}}。规范校验全绿，一跑就挂在第 2 步。
 *
 * <blockquote>
 * <b>agent 编造，通常是因为它没有查询的工具，而不是因为它不老实。</b>
 * 加约束（"不要编造 URL，不确定就问用户"）只会让它换个方式绕；加工具才是把路堵上。
 * </blockquote>
 *
 * <h3>为什么绕一圈走 CLI</h3>
 *
 * CLI 是两个 agent（opencode 与平台内的这个）**唯一的工具层**。
 * 直接注入平台的 service 会让两边的工具集又分叉 —— 而这正是写侧统一走 CLI 之后
 * 已经消灭掉的那类问题。加一个工具只改 CLI 一处，两个 agent 同时获得。
 */
@Slf4j
@Component
public class PageInspectTools {

    @Autowired
    private AtpCliClient cli;

    @Tool(name = "inspect_page",
            description = "打开被测系统的一个页面看一眼，返回页面标题与**可以直接填进案例的定位器**。"
                    + "⚠️ 写任何带 URL 或定位器的步骤之前都应该先调它确认 —— "
                    + "路径和元素靠猜必然出错。path 可以写 /products/p001，"
                    + "也可以写案例里那种 ${base_url}/products/p001。")
    public String inspectPage(
            @ToolParam(name = "path", description = "页面路径或完整 URL") String path) {
        CliResult r = cli.run("inspect", path);

        if (!r.success()) {
            // ⚠️ 「路径不存在」与「探查服务坏了」必须让模型分辨得出来：
            //    前者该换路径或问用户，后者该如实报告 —— 合并成"探查失败"它就会退回编造
            log.info("[TOOL][inspect_page] {} {}", path, r.code());
            StringBuilder sb = new StringBuilder("探查失败（%s）".formatted(r.code()));
            if (!r.message().isBlank()) {
                sb.append('：').append(r.message());
            }
            r.violations().forEach(v -> sb.append('\n').append(v));
            String next = r.nextAction();
            if (!next.isEmpty()) {
                sb.append("\n\n下一步：").append(next);
            }
            return sb.toString();
        }

        return render(path, r);
    }

    /**
     * 把候选定位器渲染成模型能直接抄的形式。
     *
     * <p>带上 {@code text} 是必要的 —— 光给定位器，模型无法判断"这是不是我要的那个按钮"。
     */
    private String render(String path, CliResult r) {
        JsonNode data = r.data();
        StringBuilder sb = new StringBuilder();
        sb.append("页面存在：").append(r.str("url"));
        String title = r.str("title");
        if (title != null) {
            sb.append("\n标题：").append(title);
        }

        JsonNode candidates = data == null ? null : data.get("candidates");
        if (candidates == null || !candidates.isArray() || candidates.isEmpty()) {
            sb.append("\n\n⚠️ 页面上没有找到可用的定位器。可能是内容要登录后才出现，"
                    + "或者这个页面本来就没有可交互元素。");
            return sb.toString();
        }

        sb.append("\n\n可用的定位器（可直接填进步骤，都已符合 STD 规范）：\n");
        candidates.forEach(c -> {
            sb.append("  [").append(text(c, "kind")).append("] ")
                    .append(text(c, "locatorType")).append(" = ").append(text(c, "locatorValue"));
            String t = text(c, "text");
            if (t != null && !t.isBlank()) {
                sb.append("   ← \"").append(t).append('"');
            }
            String note = text(c, "note");
            if (note != null && !note.isBlank()) {
                sb.append("  (").append(note).append(')');
            }
            sb.append('\n');
        });
        log.info("[TOOL][inspect_page] {} → {} 个候选", path, candidates.size());
        return sb.toString();
    }

    private String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
