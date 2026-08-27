package dev.kanashi.atp.cli.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kanashi.atp.cli.model.CaseHeader;
import dev.kanashi.atp.cli.model.Priority;

/** 从草稿 JSON 里取出表头字段。JSON 解析留在 rule 包，SQL 留在 store 包。 */
public final class DraftHeader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DraftHeader() {}

    public static CaseHeader parse(String draftJson) {
        if (draftJson == null || draftJson.isBlank()) {
            return new CaseHeader(null, null, null, null, null, null);
        }
        JsonNode n;
        try {
            n = MAPPER.readTree(draftJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("草稿 JSON 解析失败：" + e.getMessage(), e);
        }
        return new CaseHeader(
                text(n, "case_code"), text(n, "title"), text(n, "module_id"),
                n.hasNonNull("priority") ? Priority.valueOf(n.get("priority").asText()) : null,
                text(n, "author"), text(n, "precondition"));
    }

    /** 建草稿时的初始内容：只有标题和一个空步骤数组。 */
    public static String initial(String title) {
        var root = MAPPER.createObjectNode();
        root.put("title", title);
        root.putArray("steps");
        return root.toString();
    }

    private static String text(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asText() : null;
    }
}
