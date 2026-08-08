package dev.kanashi.atp.mcp.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.kanashi.atp.mcp.profile.ModuleEntry;

import java.util.List;

/**
 * {@code atp_list_modules} 的返回体。
 *
 * @param note 明确告诉调用方：这是 module_id 的<b>全集</b>，不在其中的值一律会被拒绝。
 *             不写这句的话，模型很可能以为自己可以按命名规律"推出"一个新的 module_id。
 */
public record ModuleList(
        @JsonProperty("modules") List<ModuleEntry> modules,
        @JsonProperty("count") int count,
        @JsonProperty("note") String note) {
}
