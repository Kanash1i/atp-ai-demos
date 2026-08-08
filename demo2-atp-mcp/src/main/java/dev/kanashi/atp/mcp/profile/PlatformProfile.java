package dev.kanashi.atp.mcp.profile;

import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * 目标平台的描述。<b>这是本 demo 的可扩展性支点</b>（交接文档 §5.3）。
 * <p>
 * 核心流水线 L0~L5 完全不认识 ATP，只认这个接口 ——
 * 接一个新平台只需实现一个 profile，不改流水线一行代码。
 * <p>
 * <b>关于这个接口目前的规模</b>：交接文档 §5.3 还列了 {@code aliases()} / {@code mappers()} /
 * {@code validators()} 三个方法，它们分别服务于 L0 / L1 / L4，而这三层要到 M2 才存在。
 * 这里刻意<b>只声明当前有实现的方法</b> —— 提前声明一批没人调用、只能返回空列表的方法，
 * 除了让 profile 看起来"完整"之外没有任何好处，反而会掩盖"哪些能力真的可用"。
 * M2 落地 L0~L2 时按需扩展。
 */
public interface PlatformProfile {

    /** 平台标识，如 {@code "atp"} / {@code "generic-junit"}。 */
    String id();

    /** 给人看的平台名。 */
    String displayName();

    /**
     * 目标 JSON Schema。L4 用它做本地校验 —— <b>无论 LLM 走哪种结构化输出策略，这一步永不跳过</b>。
     */
    JsonNode targetSchema();

    /** 外键取值范围。 */
    List<ModuleEntry> modules();

    /**
     * 外键校验。
     * <p>
     * 单独作为方法而不是让调用方自己遍历 {@link #modules()}，是为了让
     * "外键必须对照字典校验"成为接口层面的强制动作，而不是一句写在文档里的约定。
     */
    boolean isKnownModuleId(String moduleId);

    /** 平台规范摘要，随 describe_schema 返回，供调用方在生成阶段就对齐。 */
    List<StandardRule> standards();

    /**
     * 枚举字典：字段名 → 合法取值。
     * <p>
     * 应当由代码中的枚举类型反射生成，而不是手写一份 —— 手写的那份迟早和枚举对不上，
     * 而它对不上的后果是：调用方按字典生成了一个服务其实不接受的值。
     */
    Map<String, List<String>> enumDictionary();
}
