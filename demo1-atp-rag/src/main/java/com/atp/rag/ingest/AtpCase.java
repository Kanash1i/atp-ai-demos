package com.atp.rag.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 一条存量案例，以及它转成「可检索文本」的方式。
 *
 * <h3>为什么不直接 embed JSON</h3>
 *
 * 把原始 JSON 丢进 embedding 模型，向量会被字段名、引号、大括号这些结构噪音主导，
 * 80 条案例互相之间的相似度会高得离谱 —— 它们的 JSON 骨架本来就一模一样。
 * 所以要先渲染成一段人话，让向量真正反映「这条案例在测什么」。
 *
 * <h3>为什么整条不切</h3>
 *
 * 文档按标题切，案例<b>整条入库</b>。步骤之间是有顺序依赖的，
 * 切碎之后单看「点击提交按钮」这一步没有任何检索价值，
 * 而且用户问「找个下单流程的案例参考」时，要的是完整案例而不是某个步骤。
 */
public final class AtpCase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonNode node;

    private AtpCase(JsonNode node) {
        this.node = node;
    }

    public static AtpCase parse(File file) {
        try {
            return new AtpCase(MAPPER.readTree(file));
        } catch (IOException e) {
            throw new UncheckedIOException("解析案例 " + file + " 失败", e);
        }
    }

    public String caseCode() {
        return node.path("case_code").asText();
    }

    public String moduleCode() {
        return node.path("module_code").asText();
    }

    /**
     * 渲染成送去 embedding 的文本。
     *
     * <p>标题和模块放最前面 —— 它们信息密度最高，而且 chunk 开头的内容对向量影响更大。
     * 步骤展开成「动作 + 定位器 + 数据」的自然序列，让「有没有涉及文件上传的案例」
     * 这类问法能靠语义命中 {@code UPLOAD} 步骤，而不必依赖 metadata 过滤。
     */
    public String renderForEmbedding() {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(caseCode()).append("] ").append(node.path("title").asText()).append('\n');
        sb.append("模块：").append(moduleCode())
                .append("　优先级：").append(node.path("priority").asText())
                .append("　状态：").append(node.path("status").asText()).append('\n');

        String precondition = node.path("precondition").asText("");
        if (!precondition.isEmpty()) {
            sb.append("前置条件：").append(precondition).append('\n');
        }

        sb.append("测试步骤：\n");
        for (JsonNode step : node.path("steps")) {
            sb.append("  ").append(step.path("seq").asInt()).append(". ")
                    .append(step.path("action").asText());

            String locator = step.path("locator_value").asText("");
            if (!locator.isEmpty()) {
                sb.append(' ').append(step.path("locator_type").asText()).append('=').append(locator);
            }
            String input = step.path("input_data").asText("");
            if (!input.isEmpty()) {
                sb.append("　输入：").append(input);
            }
            String expected = step.path("expected").asText("");
            if (!expected.isEmpty()) {
                sb.append("　期望：").append(expected);
            }
            sb.append("　等待：").append(step.path("wait_strategy").asText());

            String description = step.path("description").asText("");
            if (!description.isEmpty()) {
                sb.append("\n     说明：").append(description);
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    /**
     * 转成 Qdrant payload。
     *
     * <p>⚠️ langchain4j 0.35 的 {@code Metadata} 只接受 String / int / long / float / double
     * —— <b>不支持 List，也不支持 boolean</b>。所以数组字段存成两端带逗号的字符串
     * （{@code ,CLICK,INPUT,}），这样按子串匹配时 {@code ,CLICK,} 不会误命中
     * 假想中的 {@code DOUBLE_CLICK}；布尔存成 {@code "true"} / {@code "false"} 字符串。
     */
    public Metadata toMetadata() {
        Metadata metadata = new Metadata();
        metadata.put("kind", "case");
        metadata.put("case_code", caseCode());
        metadata.put("case_id", node.path("case_id").asText());
        metadata.put("title", node.path("title").asText());
        metadata.put("module_code", moduleCode());
        metadata.put("module_id", node.path("module_id").asText());
        metadata.put("priority", node.path("priority").asText());
        metadata.put("status", node.path("status").asText());
        metadata.put("step_count", node.path("step_count").asInt());
        metadata.put("actions_used", joinAsSearchableList(node.path("actions_used")));

        // 这两项是「这条案例可以参考，但它违反了 STD-004，别照抄」这类回答的唯一依据。
        // M1 的 CorpusIntegrityTest 已经双向核对过它们与案例内容一致
        metadata.put("has_violation", String.valueOf(node.path("has_violation").asBoolean()));
        metadata.put("violation_codes", joinAsSearchableList(node.path("violation_codes")));
        return metadata;
    }

    /** 空数组返回空串，非空返回 {@code ,A,B,} —— 两端的逗号是为了让子串匹配不会命中前后缀。 */
    private static String joinAsSearchableList(JsonNode array) {
        if (!array.isArray() || array.size() == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(",");
        for (JsonNode item : array) {
            sb.append(item.asText()).append(',');
        }
        return sb.toString();
    }

    /** 违规码列表，供 CLI 展示与 M3 的「别照抄」提示使用。 */
    public List<String> violationCodes() {
        List<String> codes = new ArrayList<String>();
        for (JsonNode item : node.path("violation_codes")) {
            codes.add(item.asText());
        }
        return codes;
    }

    public boolean hasViolation() {
        return node.path("has_violation").asBoolean();
    }
}
