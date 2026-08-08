package dev.kanashi.atp.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M0 的回归防线：锁死「STATELESS + Streamable HTTP」这条部署形态。
 * <p>
 * 为什么值得为此专门写集成测试 —— 因为 {@code spring.ai.mcp.server.protocol}
 * 的默认值是 {@code STREAMABLE}，它会维持 session。一旦有人删掉或改错这一行配置，
 * 服务在**单副本本地开发时完全正常**，只有上了 k8s 多副本、请求被 LB 打到
 * 另一个 pod 时才会炸成 {@code "Session ID missing"}（M0 已用双实例对照实测复现）。
 * <p>
 * 这类「本地怎么测都对，线上才炸」的配置错误，只能靠断言协议层的可观察行为来防。
 */
class McpStatelessProtocolTest extends McpProtocolTestSupport {

    @Test
    @DisplayName("initialize 不下发 Mcp-Session-Id —— 服务端没有为客户端建立任何会话状态")
    void initializeIssuesNoSession() {
        ResponseEntity<String> response = post("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"%s","capabilities":{},
                  "clientInfo":{"name":"stateless-test","version":"0.0.1"}}}
                """.formatted(PROTOCOL_VERSION));

        // STREAMABLE 模式下这个头一定存在（M0 实测值形如 7e1389af-...），
        // 它的**缺席**正是 STATELESS 生效的证据。
        assertThat(response.getHeaders().headerNames())
                .as("STATELESS 模式不得下发 Mcp-Session-Id")
                .noneMatch(h -> h.equalsIgnoreCase("Mcp-Session-Id"));
    }

    @Test
    @DisplayName("不带 session、且从未 initialize，tools/list 依然可用")
    void toolsListWorksWithoutSession() {
        JsonNode body = postJson("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """);

        // STREAMABLE 模式下这里会是 {"jsonRpcError":{"code":-32601,"message":"Session ID missing"}}
        assertThat(body.has("error")).as("不应返回 JSON-RPC error").isFalse();
        assertThat(body.path("result").path("tools").isArray()).isTrue();
        assertThat(findTool("atp_echo").isMissingNode()).isFalse();
    }

    @Test
    @DisplayName("不带 session，tools/call 能真正执行并原样回显（含中日文）")
    void toolsCallWorksWithoutSession() {
        String message = "M0 疎通確認 / 连通性验证";

        JsonNode payload = callTool("atp_echo", """
                {"message":"%s"}""".formatted(message));

        assertThat(payload.path("echoed").asString())
                .as("多字节字符必须无损往返")
                .isEqualTo(message);
        assertThat(payload.path("servedBy").asString()).isNotBlank();
    }

    @Test
    @DisplayName("连续多次调用互不依赖 —— 等价于请求被 LB 分散到不同副本")
    void consecutiveCallsAreIndependent() {
        for (int i = 0; i < 3; i++) {
            JsonNode body = postJson("""
                    {"jsonrpc":"2.0","id":%d,"method":"tools/call","params":{
                      "name":"atp_echo","arguments":{"message":"req-%d"}}}
                    """.formatted(100 + i, i));

            assertThat(body.has("error"))
                    .as("第 %d 次调用不应因缺少会话而失败", i + 1)
                    .isFalse();
        }
    }
}
