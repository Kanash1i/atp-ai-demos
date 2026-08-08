package dev.kanashi.atp.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M0 的回归防线：锁死「STATELESS + Streamable HTTP」这条部署形态。
 * <p>
 * 为什么值得为一个 echo tool 写集成测试 —— 因为 {@code spring.ai.mcp.server.protocol}
 * 的默认值是 {@code STREAMABLE}，它会维持 session。一旦有人删掉或改错这一行配置，
 * 服务在**单副本本地开发时完全正常**，只有上了 k8s 多副本、请求被 LB 打到
 * 另一个 pod 时才会炸成 {@code "Session ID missing"}。
 * <p>
 * 这类「本地怎么测都对，线上才炸」的配置错误，只能靠断言协议层的可观察行为来防：
 * 下面每个请求都**不带任何 session 上下文**，等价于模拟「这个请求落到了一个
 * 从没见过该客户端的 pod 上」。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpStatelessProtocolTest {

    private static final String PROTOCOL_VERSION = "2025-11-25";

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper objectMapper;

    private RestClient client() {
        return RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** 发一个 JSON-RPC 请求，刻意不带 Mcp-Session-Id。 */
    private ResponseEntity<String> post(String jsonRpcBody) {
        return client().post()
                .uri("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                // Streamable HTTP 要求客户端同时接受这两种响应形态
                .header("Accept", "application/json, text/event-stream")
                .body(jsonRpcBody)
                .retrieve()
                .toEntity(String.class);
    }

    private JsonNode postJson(String jsonRpcBody) {
        return objectMapper.readTree(post(jsonRpcBody).getBody());
    }

    @Test
    @DisplayName("initialize 不下发 Mcp-Session-Id —— 服务端没有为客户端建立任何会话状态")
    void initializeIssuesNoSession() {
        ResponseEntity<String> response = post("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"%s","capabilities":{},
                  "clientInfo":{"name":"stateless-test","version":"0.0.1"}}}
                """.formatted(PROTOCOL_VERSION));

        // STREAMABLE 模式下这个头一定存在（实测值形如 7e1389af-...），
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

        JsonNode tools = body.path("result").path("tools");
        assertThat(tools.isArray()).isTrue();

        boolean echoExposed = false;
        for (JsonNode tool : tools) {
            if ("atp_echo".equals(tool.path("name").asString())) {
                echoExposed = true;
            }
        }
        assertThat(echoExposed).as("atp_echo 应出现在 tools/list 中").isTrue();
    }

    @Test
    @DisplayName("不带 session，tools/call 能真正执行并原样回显（含中日文）")
    void toolsCallWorksWithoutSession() {
        String message = "M0 疎通確認 / 连通性验证";

        JsonNode body = postJson("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
                  "name":"atp_echo","arguments":{"message":"%s"}}}
                """.formatted(message));

        assertThat(body.has("error")).isFalse();
        assertThat(body.path("result").path("isError").asBoolean()).isFalse();

        // tool 的返回被包成 content[0].text，内容本身是一段 JSON 字符串
        String text = body.path("result").path("content").get(0).path("text").asString();
        JsonNode payload = objectMapper.readTree(text);

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
