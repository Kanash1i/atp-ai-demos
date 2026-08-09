package dev.kanashi.atp.mcp.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 规范化后的测试案例（tc_case 一行 + 其下的 tc_step）。
 * <p>
 * <b>刻意缺席的字段</b>：{@code case_id} / {@code created_at} / {@code updated_at} 不在这里 ——
 * 它们由平台在入库时生成。本服务不碰 DB，凭空填一个雪花 ID 只会制造假象。
 * 同理 {@code caseCode} 的 4 位序号需要全局唯一性，而全局唯一性要求有状态；
 * 本服务只产出符合 {@code ATP-{MODULE}-{4位}} 形状的模板，序号由平台分配
 * （交接文档 §11 Q7 —— 这是无状态边界的直接体现，不是偷懒）。
 *
 * @param caseCode     形如 {@code ATP-CART-0001}，STD-007
 * @param moduleId     外键 → tc_module，**必须对照字典校验**，这是防模型编造的关键一环
 * @param timeoutSec   范围 5..300，默认 30
 * @param steps        seq 从 1 连续；STD-008 要求其中至少一个是断言步骤
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NormalizedCase(
        @JsonProperty("case_code") String caseCode,
        @JsonProperty("title") String title,
        @JsonProperty("module_id") String moduleId,
        @JsonProperty("priority") Priority priority,
        @JsonProperty("author") String author,
        @JsonProperty("precondition") String precondition,
        @JsonProperty("status") CaseStatus status,
        @JsonProperty("browser") Browser browser,
        @JsonProperty("timeout_sec") Integer timeoutSec,
        @JsonProperty("steps") List<TestStep> steps) {

    public static final int DEFAULT_TIMEOUT_SEC = 30;
    public static final int MIN_TIMEOUT_SEC = 5;
    public static final int MAX_TIMEOUT_SEC = 300;

    public static final int MAX_TITLE_LENGTH = 200;
    public static final int MAX_AUTHOR_LENGTH = 64;
    public static final int MAX_CASE_CODE_LENGTH = 64;

    /** STD-007：case_code 的形状。序号由平台分配，本服务只保证形状。 */
    public static final String CASE_CODE_PATTERN = "^ATP-[A-Z]+-\\d{4}$";
}
