package dev.kanashi.atp.cli.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 草稿的形状校验：纯本地、零网络、零模型调用。
 *
 * <p>⭐ <b>它把校验失败分成两类，因为两类的下一步动作完全不同</b>：
 * <ul>
 *   <li><b>必填字段压根没给</b> → {@code NEEDS_INPUT}(14)：机器补不出来，
 *       agent 必须<b>去问用户</b>，猜一个填进去就是在制造假象</li>
 *   <li><b>给了但值不合法</b> → {@code VALIDATION_FAILED}(12)：agent 自己按 violations 改</li>
 * </ul>
 *
 * <p>两类同时存在时<b>优先报 NEEDS_INPUT</b> —— 人不补信息，agent 改了也白改。
 *
 * <p>这条分流是从已废弃的 MCP 方案里继承下来的唯一结构性设计
 * （原三态状态机的 {@code NEEDS_INPUT} / {@code NEEDS_REVISION}）。
 * 压成一个"校验失败"，调用方就只能靠读诊断文本猜下一步 —— 那是在赌。
 */
public final class DraftValidator {

    public static final String SCHEMA_RESOURCE = "schema/tc_case.schema.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonSchema schema;

    public DraftValidator() {
        this.schema = JsonSchemaFactory
                .getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(readSchema());
    }

    public Result validate(JsonNode draft) {
        Set<ValidationMessage> messages = schema.validate(draft);

        List<String> missing = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        for (ValidationMessage m : messages) {
            // networknt 对"必填缺失"报的 type 就是 required
            if ("required".equals(m.getType())) {
                missing.add(m.getMessage());
            } else {
                invalid.add(m.getMessage());
            }
        }
        return new Result(missing, invalid);
    }

    public String schemaJson() {
        try (InputStream in = open()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读不到 " + SCHEMA_RESOURCE, e);
        }
    }

    private static JsonNode readSchema() {
        try (InputStream in = open()) {
            return MAPPER.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("读不到 " + SCHEMA_RESOURCE, e);
        }
    }

    private static InputStream open() {
        InputStream in = DraftValidator.class.getClassLoader().getResourceAsStream(SCHEMA_RESOURCE);
        if (in == null) {
            throw new IllegalStateException("classpath 里找不到 " + SCHEMA_RESOURCE);
        }
        return in;
    }

    /**
     * @param missing 必填字段缺失 → 去问用户
     * @param invalid 值不合法    → agent 自己改
     */
    public record Result(List<String> missing, List<String> invalid) {

        public boolean passed() {
            return missing.isEmpty() && invalid.isEmpty();
        }

        /** 两类同时存在时优先 NEEDS_INPUT —— 人不补信息，agent 改了也白改。 */
        public boolean needsUserInput() {
            return !missing.isEmpty();
        }

        public List<String> all() {
            List<String> out = new ArrayList<>(missing);
            out.addAll(invalid);
            return out;
        }
    }
}
