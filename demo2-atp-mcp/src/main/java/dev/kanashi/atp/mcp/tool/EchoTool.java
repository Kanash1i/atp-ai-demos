package dev.kanashi.atp.mcp.tool;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * M0 的连通性验证 tool —— 唯一目的是证明 Streamable HTTP + STATELESS 这条链路真的能被调用。
 * <p>
 * 返回体里带上 pod 标识（{@code HOSTNAME}，k8s 会注入 pod 名），
 * 这样 M6 起 2 副本时，连续调用能直接看到响应来自不同 pod ——
 * 这就是 STATELESS 生效的可视证据。业务 tool 从 M1 开始加。
 */
@Component
public class EchoTool {

    @McpTool(
            name = "atp_echo",
            title = "连通性自检",
            description = "连通性自检：原样回显输入，并返回处理该请求的服务实例标识。"
                        + "用于确认 MCP server 可达；不涉及任何业务逻辑。",
            // M0 时这里吃了 MCP 的默认值，结果本 tool 对外自称 destructive、非幂等 ——
            // 一个纯回显的自检接口谎称自己有破坏性，会让调用方 agent 无谓地要求用户确认。
            // 见 DECISIONS.md M0-D4。
            annotations = @McpTool.McpAnnotations(
                    title = "连通性自检",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false))
    public EchoResult echo(
            @McpToolParam(description = "任意文本，将被原样回显", required = true)
            String message) {

        return new EchoResult(message, instanceId());
    }

    private static String instanceId() {
        // k8s 下 HOSTNAME 即 pod 名；本地跑时回落到主机名，再不行给个占位符。
        String host = System.getenv("HOSTNAME");
        if (host == null || host.isBlank()) {
            host = System.getProperty("atp.instance.id", "local");
        }
        return host;
    }

    public record EchoResult(String echoed, String servedBy) {
    }
}
