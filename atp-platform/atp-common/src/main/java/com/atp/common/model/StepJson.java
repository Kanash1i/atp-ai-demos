package com.atp.common.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * {@code tc_step.step_json} 的两种形态。
 *
 * <h3>⚠️ 同一列上真的有两种格式，这不是 bug 是历史</h3>
 *
 * <pre>
 * 老平台存量（种子导入）  [ {seq:1,...}, {seq:2,...} ]        ← 纯步骤数组
 * 编辑期草稿（CLI/agent） { case_code, title, …, steps:[…] }  ← 对象，表头暂存在里面
 * </pre>
 *
 * 草稿之所以是对象：编辑期 {@code tc_case} 的 case_code / title / module_id 等列
 * 都还是 NULL（V1 迁移为此放宽了 NOT NULL），表头没地方放，只能先存进 step_json，
 * commit 那一刻再投影到正式列。
 *
 * <h3>⭐ 但落地之后必须规整回数组</h3>
 *
 * 保守路线的核心主张是「落库格式与人工案例完全一致，老执行器无感知照跑」。
 * 老平台的执行器读的是**数组**——如果 commit 之后 step_json 仍是对象，
 * 那句主张就不成立了：老执行器一读就崩，而它崩的时候没人知道是谁写进去的。
 *
 * <p>所以：<b>读的时候两种都认（防御既有数据），写的时候只写数组（保证兼容）。</b>
 */
public final class StepJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StepJson() {
    }

    /** 解析步骤，两种格式都认 */
    public static List<Step> parseSteps(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = MAPPER.readTree(raw);
            JsonNode steps = node.isArray() ? node : node.path("steps");
            if (!steps.isArray()) {
                return List.of();
            }
            return MAPPER.convertValue(steps, new TypeReference<List<Step>>() {
            });
        } catch (IOException e) {
            // ⚠️ 不吞：step_json 解析不了意味着这条案例根本没法执行，
            //    静默返回空列表会让它在 UI 上显示成「一步都没有的案例」，比报错难查得多
            throw new UncheckedIOException("step_json 解析失败", e);
        }
    }

    /** 解析草稿的表头。数组格式没有表头，返回空对象 */
    public static ObjectNode parseHeader(String raw) {
        if (raw == null || raw.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            JsonNode node = MAPPER.readTree(raw);
            if (node.isArray()) {
                return MAPPER.createObjectNode();
            }
            ObjectNode header = ((ObjectNode) node).deepCopy();
            header.remove("steps");
            return header;
        } catch (IOException e) {
            throw new UncheckedIOException("step_json 解析失败", e);
        }
    }

    /**
     * 规整成**老平台格式**（纯步骤数组）。commit 落地时用。
     *
     * <p>表头此时已经投影进 tc_case 的正式列，留在 step_json 里就是同一份数据存两遍 ——
     * 而两份必然会不一致，只是时间问题。
     */
    public static String toLegacyArray(String raw) {
        try {
            JsonNode node = MAPPER.readTree(raw == null || raw.isBlank() ? "[]" : raw);
            ArrayNode steps = node.isArray() ? (ArrayNode) node
                    : (node.path("steps").isArray() ? (ArrayNode) node.get("steps") : MAPPER.createArrayNode());
            return MAPPER.writeValueAsString(steps);
        } catch (IOException e) {
            throw new UncheckedIOException("step_json 规整失败", e);
        }
    }

    /** 编辑期格式：表头 + steps 合成一个对象 */
    public static String toDraftObject(ObjectNode header, List<Step> steps) {
        ObjectNode root = header == null ? MAPPER.createObjectNode() : header.deepCopy();
        root.set("steps", MAPPER.valueToTree(steps == null ? List.of() : steps));
        try {
            return MAPPER.writeValueAsString(root);
        } catch (IOException e) {
            throw new UncheckedIOException("step_json 序列化失败", e);
        }
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
