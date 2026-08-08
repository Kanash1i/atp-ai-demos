package dev.kanashi.atp.mcp.profile;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 模块字典一条（tc_module，只读）。
 * <p>
 * <b>这是外键的取值范围，也是本服务防模型编造的第一道闸门。</b>
 * 模型把 module_id 填成不存在的 {@code "M009"} 时，schema 校验会放行（它确实是字符串、
 * 格式也像），只有对照这份字典才能拦住 —— 交接文档 §2.1 的那个故事就是这么来的。
 * <p>
 * 字段名用 snake_case 输出，与 DB 列名和 tc_case.schema.json 保持一致；
 * 调用方 agent 不该在两种命名风格之间做翻译。
 */
public record ModuleEntry(
        @JsonProperty("module_id") String moduleId,
        @JsonProperty("module_code") String moduleCode,
        @JsonProperty("module_name") String moduleName) {
}
