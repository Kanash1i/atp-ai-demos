package dev.kanashi.atp.cli.model;

/**
 * 退出码是 agent 唯一可靠的分流依据，必须先定死再写命令层。
 *
 * <p>⚠️ 两条容易翻车的约定：
 * <ol>
 *   <li>幂等重放返回 {@link #OK}。重放在语义上是<b>成功</b> ——
 *       返回非 0 会让 agent 认为没成功而无限重试。</li>
 *   <li>{@link #VALIDATION_FAILED} 和 {@link #NEEDS_INPUT} 必须分开：
 *       前者 agent 自己能改，后者必须去问人。<b>下一步动作不同的，就不能合并成一个码。</b></li>
 * </ol>
 */
public enum ExitCode {

    OK(0),
    /** 版本对不上：用户确认之后内容被改过。重新 show → preview → 确认。 */
    VERSION_CONFLICT(10),
    /** 案例不存在，或草稿已被每月清理任务回收。 */
    NOT_FOUND(11),
    /** 规则校验不通过，agent 读 violations 自己改。 */
    VALIDATION_FAILED(12),
    /** 当前状态不允许该操作（如已 ACTIVE）。停下问用户。 */
    STATE_CONFLICT(13),
    /** 缺必填信息且机器补不出来。去问用户，不要猜。 */
    NEEDS_INPUT(14),
    /** DB 不通等基础设施故障。 */
    INFRA_ERROR(20);

    private final int code;

    ExitCode(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
