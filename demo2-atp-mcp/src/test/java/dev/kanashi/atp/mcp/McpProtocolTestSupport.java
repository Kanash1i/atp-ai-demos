package dev.kanashi.atp.mcp;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 走真实 MCP 协议的测试基类。
 * <p>
 * 所有请求都<b>刻意不带 {@code Mcp-Session-Id}</b> —— 这既是 STATELESS 的验证手段，
 * 也让每个测试天然等价于"这个请求落到了一个从没见过该客户端的 pod 上"。
 * <p>
 * 用协议层而非直接调 Java 方法来测 tool，是因为要覆盖的东西恰恰在方法签名之外：
 * inputSchema 生成、annotations 下发、返回值序列化 —— M0-D4 那个 bug 就藏在这一层，
 * 单元测试 100% 看不到它。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class McpProtocolTestSupport {

    protected static final String PROTOCOL_VERSION = "2025-11-25";

    @LocalServerPort
    protected int port;

    @Autowired
    protected ObjectMapper objectMapper;

    protected RestClient client() {
        return RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    protected ResponseEntity<String> post(String jsonRpcBody) {
        return client().post()
                .uri("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Accept", "application/json, text/event-stream")
                .body(jsonRpcBody)
                .retrieve()
                .toEntity(String.class);
    }

    protected JsonNode postJson(String jsonRpcBody) {
        return objectMapper.readTree(post(jsonRpcBody).getBody());
    }

    /** 取 tools/list 的结果。 */
    protected JsonNode listTools() {
        return postJson("""
                {"jsonrpc":"2.0","id":900,"method":"tools/list","params":{}}
                """).path("result").path("tools");
    }

    /** 按名字取某个 tool 的描述；不存在则返回 missing node。 */
    protected JsonNode findTool(String name) {
        for (JsonNode tool : listTools()) {
            if (name.equals(tool.path("name").asString())) {
                return tool;
            }
        }
        return objectMapper.missingNode();
    }

    /**
     * 调一个 tool，并把它包在 {@code content[0].text} 里的 JSON 载荷解析出来。
     * <p>
     * MCP 的 tool 返回值是"一段文本"，结构化数据以 JSON 字符串形式嵌在里面，
     * 所以这里要解两层 —— 测试里反复手写这个解包很容易出错。
     */
    protected JsonNode callTool(String name, String argumentsJson) {
        JsonNode body = postJson("""
                {"jsonrpc":"2.0","id":901,"method":"tools/call","params":{
                  "name":"%s","arguments":%s}}
                """.formatted(name, argumentsJson));

        if (body.has("error")) {
            throw new AssertionError("tool 调用返回 JSON-RPC error: " + body.path("error"));
        }
        String text = body.path("result").path("content").get(0).path("text").asString();
        return objectMapper.readTree(text);
    }
}
