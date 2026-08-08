package dev.kanashi.atp.mcp.tool;

import dev.kanashi.atp.mcp.domain.Action;
import dev.kanashi.atp.mcp.profile.PlatformProfile;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 描述类 tool —— 让调用方在<b>生成阶段</b>就知道该产出什么形状。
 * <p>
 * 交接文档 §5.1：只提供 normalize 等于放任上游乱生成、下游收拾；
 * 先给出 schema 才是把错误率从源头压下去（"左移"）。
 * <p>
 * 本类是薄层，只做参数转换与 DTO 组装，业务事实全部来自 {@link PlatformProfile}。
 *
 * <h2>⚠️ 关于 annotations 为什么全部显式声明</h2>
 * MCP 规范对 tool annotations 的默认值是
 * {@code readOnlyHint=false, destructiveHint=true, idempotentHint=false, openWorldHint=true}。
 * M0 实测确认 Spring AI 会原样下发这组默认值 —— 对这两个纯读取、毫秒级、无副作用的
 * tool 来说全是错的。被标成"破坏性"的实际后果是：调用方 agent 可能在调用前
 * 向用户请求确认，甚至干脆回避调用。<b>服务本身完全正常，只是被下游误解了</b>，
 * 而这种错误不会有任何报错。详见 DECISIONS.md M0-D4。
 */
@Component
public class AtpSchemaTools {

    private final PlatformProfile profile;

    public AtpSchemaTools(PlatformProfile profile) {
        this.profile = profile;
    }

    @McpTool(
            name = "atp_describe_schema",
            title = "查询 ATP 目标 schema 与规范",
            description = """
                    返回 ATP 平台测试案例的目标 JSON Schema、枚举取值、每个 action 的字段契约、\
                    以及平台规范摘要。**在生成或改写测试案例之前应当先调用本 tool**，\
                    据此产出的案例能显著提高 atp_normalize_case 的通过率。\
                    纯读取，无副作用，结果稳定。""",
            annotations = @McpTool.McpAnnotations(
                    title = "查询 ATP 目标 schema 与规范",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    // 数据全部来自本进程内的 profile，不查 DB、不出网
                    openWorldHint = false))
    public SchemaDescription describeSchema() {
        return new SchemaDescription(
                profile.id(),
                profile.displayName(),
                profile.targetSchema(),
                profile.enumDictionary(),
                Arrays.stream(Action.values()).map(ActionContract::of).toList(),
                profile.standards(),
                List.of("case_id", "step_id", "created_at", "updated_at"),
                List.of(
                        "字段名一律 snake_case，与 DB 列名一致。",
                        "module_id 必须取自 atp_list_modules 返回的字典；"
                      + "编造一个格式正确但不存在的值会被直接拒绝。",
                        "wait_strategy 不需要你提供 —— 本服务按 action 确定性填充（见 action_contracts）。",
                        "case_code 的 4 位序号由平台分配，你只需保证 ATP-{MODULE}-{4位} 的形状。",
                        "每条案例至少要有一个 ASSERT_* 步骤，否则视为无效用例（STD-008）。",
                        "禁止使用 SLEEP（STD-004），用 wait_strategy 表达等待条件。"));
    }

    @McpTool(
            name = "atp_list_modules",
            title = "列出 ATP 模块字典",
            description = """
                    返回 module_id 的**全集**（tc_module 字典）。module_id 是外键，\
                    取值必须来自这份字典 —— 本服务会逐条校验，不在字典中的值一律拒绝。\
                    纯读取，无副作用，结果稳定。""",
            annotations = @McpTool.McpAnnotations(
                    title = "列出 ATP 模块字典",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false))
    public ModuleList listModules() {
        List<dev.kanashi.atp.mcp.profile.ModuleEntry> modules = profile.modules();
        return new ModuleList(
                modules,
                modules.size(),
                "这是 module_id 的全集。不在此列表中的 module_id 一律会被拒绝，"
              + "请勿按命名规律推断新值。若都不匹配，请选择语义最接近的模块，"
              + "规范化结果会标注该字段来源与置信度供人工复核。");
    }
}
