package dev.kanashi.atp.mcp.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.kanashi.atp.mcp.profile.StandardRule;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * {@code atp_describe_schema} 的返回体。
 *
 * @param targetSchema     JSON Schema 本体，L4 校验用的就是这一份
 * @param enums            枚举字典，由枚举类型反射生成
 * @param actionContracts  action ↔ 字段契约，JSON Schema 表达不了的那部分
 * @param standards        平台规范摘要
 * @param platformAssigned 本服务不产出、由平台入库时生成的字段
 * @param guidance         给调用方 agent 的使用要点
 */
public record SchemaDescription(
        @JsonProperty("platform") String platform,
        @JsonProperty("platform_name") String platformName,
        @JsonProperty("target_schema") JsonNode targetSchema,
        @JsonProperty("enums") Map<String, List<String>> enums,
        @JsonProperty("action_contracts") List<ActionContract> actionContracts,
        @JsonProperty("standards") List<StandardRule> standards,
        @JsonProperty("platform_assigned_fields") List<String> platformAssigned,
        @JsonProperty("guidance") List<String> guidance) {
}
