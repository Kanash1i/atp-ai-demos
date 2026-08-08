package dev.kanashi.atp.mcp.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.stream.Stream;

import static dev.kanashi.atp.mcp.domain.FieldRequirement.FORBIDDEN;
import static dev.kanashi.atp.mcp.domain.FieldRequirement.REQUIRED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 把共享契约 §1.3 的表格<b>再独立誊写一遍</b>，逐行与 {@link Action} 枚举对照。
 * <p>
 * 这看起来是重复，但重复正是目的：期望值必须有一个<b>独立于实现的来源</b>。
 * 如果测试反过来从枚举读期望值，那它证明的只是"枚举等于它自己"。
 * 有了这张表，任何人改动 Action 的契约都必须同时改这里 ——
 * 而改这里时会被迫回去看共享契约文档，那正是我们想要的摩擦。
 * <p>
 * ⚠️ 这张表是 demo1 与 demo2 的<b>共享契约</b>。改它必须同步
 * {@code 00-SHARED-CONTEXT.md} 并通知另一个 demo。
 */
class ActionContractTableTest {

    /** 共享契约 §1.3 的一行。 */
    record ContractRow(
            Action action,
            FieldRequirement locator,
            FieldRequirement inputData,
            FieldRequirement expected,
            boolean assertion) {
    }

    /** 誊自 00-SHARED-CONTEXT.md §1.3 的表格，✓ → REQUIRED，✗ → FORBIDDEN。 */
    static Stream<ContractRow> sharedContractTable() {
        return Stream.of(
                new ContractRow(Action.OPEN_URL,         FORBIDDEN, REQUIRED,  FORBIDDEN, false),
                new ContractRow(Action.CLICK,            REQUIRED,  FORBIDDEN, FORBIDDEN, false),
                new ContractRow(Action.INPUT,            REQUIRED,  REQUIRED,  FORBIDDEN, false),
                new ContractRow(Action.SELECT,           REQUIRED,  REQUIRED,  FORBIDDEN, false),
                new ContractRow(Action.ASSERT_TEXT,      REQUIRED,  FORBIDDEN, REQUIRED,  true),
                new ContractRow(Action.ASSERT_VISIBLE,   REQUIRED,  FORBIDDEN, FORBIDDEN, true),
                new ContractRow(Action.ASSERT_NOT_EXIST, REQUIRED,  FORBIDDEN, FORBIDDEN, true),
                new ContractRow(Action.WAIT_FOR,         REQUIRED,  FORBIDDEN, FORBIDDEN, false),
                new ContractRow(Action.SCROLL_TO,        REQUIRED,  FORBIDDEN, FORBIDDEN, false),
                new ContractRow(Action.SWITCH_FRAME,     REQUIRED,  FORBIDDEN, FORBIDDEN, false),
                new ContractRow(Action.SWITCH_WINDOW,    FORBIDDEN, REQUIRED,  FORBIDDEN, false),
                new ContractRow(Action.UPLOAD,           REQUIRED,  REQUIRED,  FORBIDDEN, false),
                new ContractRow(Action.SLEEP,            FORBIDDEN, REQUIRED,  FORBIDDEN, false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sharedContractTable")
    @DisplayName("每个 action 的字段契约与共享契约 §1.3 逐行一致")
    void matchesSharedContract(ContractRow row) {
        Action action = row.action();
        assertThat(action.locator()).as("%s 的 locator", action).isEqualTo(row.locator());
        assertThat(action.inputData()).as("%s 的 input_data", action).isEqualTo(row.inputData());
        assertThat(action.expected()).as("%s 的 expected", action).isEqualTo(row.expected());
        assertThat(action.isAssertion()).as("%s 是否断言步骤", action).isEqualTo(row.assertion());
    }

    @Test
    @DisplayName("表格覆盖全部 action —— 新增 action 时不能漏掉契约声明")
    void tableCoversEveryAction() {
        assertThat(sharedContractTable().map(ContractRow::action).toList())
                .containsExactlyInAnyOrder(Action.values());
    }

    @ParameterizedTest
    @EnumSource(Action.class)
    @DisplayName("每个 action 都有确定的 wait_strategy —— 该字段 NOT NULL，不能留给模型猜")
    void everyActionHasMandatedWaitStrategy(Action action) {
        assertThat(action.mandatedWaitStrategy())
                .as("%s 缺少强制等待策略，L1 将无法确定性填充", action)
                .isNotNull();
    }

    @ParameterizedTest
    @EnumSource(Action.class)
    @DisplayName("requiresLocator() 与契约表保持自洽")
    void requiresLocatorAgreesWithContract(Action action) {
        assertThat(action.requiresLocator()).isEqualTo(action.locator() == REQUIRED);
    }

    @Test
    @DisplayName("STD-004：SLEEP 是唯一被规范禁止的 action")
    void sleepIsTheOnlyForbiddenAction() {
        assertThat(Arrays.stream(Action.values()).filter(Action::isForbiddenByStandard).toList())
                .containsExactly(Action.SLEEP);
    }

    @Test
    @DisplayName("STD-008 的判据：断言类 action 恰好是三个 ASSERT_*")
    void assertionActionsAreExactlyTheAssertFamily() {
        assertThat(Arrays.stream(Action.values()).filter(Action::isAssertion).toList())
                .containsExactlyInAnyOrder(
                        Action.ASSERT_TEXT, Action.ASSERT_VISIBLE, Action.ASSERT_NOT_EXIST);
    }

    @Test
    @DisplayName("STD-005/006 的强制值确实落在枚举上")
    void standardMandatedWaitStrategiesAreEncoded() {
        assertThat(Action.CLICK.mandatedWaitStrategy()).isEqualTo(WaitStrategy.CLICKABLE);
        assertThat(Action.CLICK.standardRef()).isEqualTo("STD-005");

        assertThat(Action.ASSERT_TEXT.mandatedWaitStrategy()).isEqualTo(WaitStrategy.VISIBLE);
        assertThat(Action.ASSERT_VISIBLE.mandatedWaitStrategy()).isEqualTo(WaitStrategy.VISIBLE);
    }

    @Test
    @DisplayName("ASSERT_NOT_EXIST 是唯一偏离规范字面的 action，且偏离必须带说明")
    void onlyAssertNotExistDeviatesAndItExplainsWhy() {
        assertThat(Arrays.stream(Action.values())
                .filter(a -> a.waitStrategyDeviation() != null)
                .toList())
                .as("偏离越多，这个字段的警示作用越被稀释")
                .containsExactly(Action.ASSERT_NOT_EXIST);

        // 等一个"不该存在"的元素变为可见，必然空耗到超时 —— 所以取 NONE
        assertThat(Action.ASSERT_NOT_EXIST.mandatedWaitStrategy()).isEqualTo(WaitStrategy.NONE);
        assertThat(Action.ASSERT_NOT_EXIST.waitStrategyDeviation()).contains("STD-006");
    }

    @Test
    @DisplayName("引用了规范编号的 action，编号必须是真实存在的 STD-XXX")
    void standardRefsAreWellFormed() {
        Arrays.stream(Action.values())
                .map(Action::standardRef)
                .filter(java.util.Objects::nonNull)
                .forEach(ref -> assertThat(ref)
                        .as("诊断信息里引错规范编号比不引更糟")
                        .matches("STD-00[1-8]"));
    }
}
