package dev.kanashi.atp.mcp.pipeline;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 一个待填的空。
 *
 * @param path        字段路径，如 {@code module_id} / {@code steps[2].locator_type}
 * @param fillability 该找谁补，见 {@link GapFillability}
 * @param hint        给填空者的提示。对 MODEL 类缺口，这段会进 prompt；
 *                    对 REQUESTER 类缺口，这段是给人看的拒绝理由
 */
public record FieldGap(
        @JsonProperty("path") String path,
        @JsonProperty("fillability") GapFillability fillability,
        @JsonProperty("hint") String hint) {

    public boolean modelFillable() {
        return fillability == GapFillability.MODEL;
    }
}
