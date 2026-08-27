package dev.kanashi.atp.cli.rule;

import com.fasterxml.jackson.databind.JsonNode;
import dev.kanashi.atp.cli.model.StepRow;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 把草稿 JSON 里的 {@code steps} 数组拆成 {@code tc_step} 的行。 */
public final class StepProjector {

    private StepProjector() {}

    /**
     * {@code step_id} 在这里生成而不是让 agent 给：子表主键没有幂等诉求
     * —— 幂等由父表主键 + update 的 CAS 保证，整批步骤要么全写要么全不写。
     * 让 agent 编 step_id 只会多一处能出错的地方。
     */
    public static List<StepRow> project(JsonNode draft) {
        JsonNode steps = draft == null ? null : draft.path("steps");
        if (steps == null || !steps.isArray()) {
            return List.of();
        }
        List<StepRow> out = new ArrayList<>(steps.size());
        for (JsonNode step : steps) {
            int seq = step.path("seq").asInt(-1);
            if (seq < 1) {
                throw new IllegalArgumentException("步骤缺少合法的 seq（必须 ≥ 1）: " + step);
            }
            out.add(new StepRow(UUID.randomUUID().toString(), seq, step.toString()));
        }
        return out;
    }
}
