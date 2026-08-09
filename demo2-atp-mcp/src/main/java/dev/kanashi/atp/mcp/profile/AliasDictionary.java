package dev.kanashi.atp.mcp.profile;

import java.util.Optional;

/**
 * 字段别名字典，服务于 L0（输入规整）。
 * <p>
 * 存在的理由：请求方是<b>任意团队的任意 agent</b>，我们无权要求它们先学会我们的字段名。
 * 与其让每个调用方去猜 {@code input_data} 还是 {@code value}，
 * 不如在服务端接住这些差异 —— 这属于"能用规则做的绝不给模型"里最廉价的一类：
 * 一次 Map 查找就能解决的事，没有任何理由交给 LLM 去理解。
 * <p>
 * 匹配应当对大小写与分隔符不敏感（{@code caseName} / {@code case_name} / {@code Case-Name} 等价）。
 */
public interface AliasDictionary {

    /** 把案例级字段名归一到标准名（snake_case，与 schema 一致）；无法识别返回空。 */
    Optional<String> canonicalCaseField(String rawName);

    /** 把步骤级字段名归一到标准名；无法识别返回空。 */
    Optional<String> canonicalStepField(String rawName);

    /** 该字段名是否是"步骤数组"的容器（steps / actions / 操作步骤 / 手順 …）。 */
    boolean isStepsContainer(String rawName);

    /**
     * 某些字段名本身就泄露了 locator_type，例如请求方直接写 {@code "xpath": "//div"}。
     * <p>
     * 这类线索<b>比推断更可靠</b>（推断只能看值的形状，这里是调用方明说的），
     * L1 应优先采用。识别不出返回空。
     */
    Optional<String> locatorTypeHintFromFieldName(String rawName);
}
