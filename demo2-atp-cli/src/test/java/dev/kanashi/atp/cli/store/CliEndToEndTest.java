package dev.kanashi.atp.cli.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kanashi.atp.cli.AtpCli;
import dev.kanashi.atp.cli.config.CliConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 命令层的端到端：走一遍演示脚本的七步，并锁定退出码契约。 */
@DisplayName("atp CLI 端到端")
class CliEndToEndTest extends PgTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tmp;

    @BeforeAll
    static void wireConfig() {
        // 系统属性优先级最高 —— 测试靠它把 CLI 指向 Testcontainers 起的那个库
        System.setProperty(CliConfig.DB_URL, PG.getJdbcUrl());
        System.setProperty(CliConfig.DB_USER, PG.getUsername());
        System.setProperty(CliConfig.DB_PASSWORD, PG.getPassword());
    }

    @Test
    @DisplayName("完整七步：draft → show → validate → update → preview → commit → 重放")
    void fullFlow() throws Exception {
        String id = UUID.randomUUID().toString();

        Run draft = run("draft", "--json", "--id", id, "-p", "PC_WEB", "-t", "购物车结算");
        assertThat(draft.code()).isZero();
        assertThat(draft.json().at("/data/status").asText()).isEqualTo("AI_DRAFT");
        assertThat(draft.json().at("/data/version").asInt()).isZero();

        assertThat(run("show", "--json", id).code()).isZero();

        Path file = writeDraft("购物车结算");
        assertThat(run("validate", "--json", "-f", file.toString()).code()).isZero();

        Run update = run("update", "--json", id, "--version", "0", "-f", file.toString());
        assertThat(update.code()).isZero();
        assertThat(update.json().at("/data/version").asInt()).isEqualTo(1);

        Run preview = run("preview", id);
        assertThat(preview.code()).isZero();
        assertThat(preview.out())
                .as("preview 必须把要带回 commit 的 version 打出来")
                .contains("atp commit " + id + " --version 1");

        Run commit = run("commit", "--json", id, "--version", "1");
        assertThat(commit.code()).isZero();
        assertThat(commit.json().at("/data/status").asText())
                .as("落地为老平台原生的 DRAFT，执行器无感知").isEqualTo("DRAFT");
        assertThat(commit.json().at("/replayed").asBoolean()).isFalse();

        Run replay = run("commit", "--json", id, "--version", "1");
        assertThat(replay.code()).as("幂等重放必须是退出码 0").isZero();
        assertThat(replay.json().at("/replayed").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("确认后内容被改过 → commit 退出码 10")
    void staleVersionExitsTen() throws Exception {
        String id = UUID.randomUUID().toString();
        run("draft", "--json", "--id", id, "-p", "PC_WEB", "-t", "登录成功");
        run("update", "--json", id, "--version", "0", "-f", writeDraft("登录成功").toString());
        // 用户 preview 拿到 version=1；确认之前 agent 又改了一版
        run("update", "--json", id, "--version", "1", "-f", writeDraft("登录成功（被改过）").toString());

        assertThat(run("commit", "--json", id, "--version", "1").code()).isEqualTo(10);
    }

    @Test
    @DisplayName("必填缺失 → 14 NEEDS_INPUT（去问人），值非法 → 12 VALIDATION_FAILED（自己改）")
    void missingVsInvalidAreDifferentCodes() throws Exception {
        Path missing = tmp.resolve("missing.json");
        Files.writeString(missing, "{\"title\":\"只有标题\"}", StandardCharsets.UTF_8);
        Run r1 = run("validate", "--json", "-f", missing.toString());
        assertThat(r1.code()).isEqualTo(14);
        assertThat(r1.json().at("/questions").size()).isPositive();

        Path invalid = tmp.resolve("invalid.json");
        Files.writeString(invalid, MAPPER.writeValueAsString(draftNode("标题")).replace("\"P1\"", "\"P9\""),
                StandardCharsets.UTF_8);
        Run r2 = run("validate", "--json", "-f", invalid.toString());
        assertThat(r2.code()).isEqualTo(12);
        assertThat(r2.json().at("/violations").size()).isPositive();
    }

    @Test
    @DisplayName("不存在的 id → 11；只读命令零副作用")
    void notFoundAndReadOnly() {
        assertThat(run("commit", "--json", UUID.randomUUID().toString(), "--version", "0").code())
                .isEqualTo(11);
        assertThat(run("schema").code()).isZero();
        assertThat(run("modules", "--json", "-p", "ECSHOP").code()).isZero();
    }

    @Test
    @DisplayName("schema 输出可直接被 validate 消费 —— 契约自洽")
    void schemaIsSelfConsistent() throws Exception {
        Run r = run("schema");
        JsonNode schema = MAPPER.readTree(r.out());
        assertThat(schema.at("/required").toString())
                .doesNotContain("browser").doesNotContain("timeout_sec").doesNotContain("status");
        assertThat(schema.at("/x-not-produced-by-this-service/fields/status").asText())
                .contains("状态机");
    }

    // ------------------------------------------------------------------ 夹具

    private Path writeDraft(String title) throws Exception {
        Path f = tmp.resolve("draft-" + UUID.randomUUID() + ".json");
        Files.writeString(f, MAPPER.writeValueAsString(draftNode(title)), StandardCharsets.UTF_8);
        return f;
    }

    private static JsonNode draftNode(String title) {
        var root = MAPPER.createObjectNode();
        root.put("case_code", "ATP-CART-0001");
        root.put("title", title);
        root.put("module_id", "M003");
        root.put("priority", "P1");
        root.put("author", "qa.kanashi");
        var step = MAPPER.createObjectNode();
        step.put("seq", 1);
        step.put("action", "OPEN_URL");
        step.put("input_data", "http://localhost:8080/cart");
        step.put("wait_strategy", "PRESENCE");
        step.put("wait_timeout_sec", 10);
        step.put("on_failure", "ABORT");
        root.putArray("steps").add(step);
        return root;
    }

    private record Run(int code, String out, String err) {
        JsonNode json() {
            try {
                return MAPPER.readTree(out.isBlank() ? err : out);
            } catch (Exception e) {
                throw new IllegalStateException("输出不是 JSON:\nout=" + out + "\nerr=" + err, e);
            }
        }
    }

    private static Run run(String... args) {
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        var bo = new ByteArrayOutputStream();
        var be = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bo, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(be, true, StandardCharsets.UTF_8));
            int code = AtpCli.run(args);
            return new Run(code, bo.toString(StandardCharsets.UTF_8), be.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }
}
