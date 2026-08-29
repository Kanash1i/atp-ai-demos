package com.atp.common.validation;

import com.atp.common.enums.StdCode;
import com.atp.common.model.TestCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拿 80 条种子案例当校验器的验收集。
 *
 * <p><b>为什么这个测试比自己写断言可靠</b>：种子 JSON 里的 {@code violation_codes}
 * 是造语料时独立标注的标准答案 —— 它先于校验器存在。
 * 自己再写一遍期望值，等于用同一套理解验证同一套理解，错了也发现不了。
 *
 * <p>⚠️ 只比对 ERROR 与 WARN。STD-003 是 INFO 档的改进建议，
 * 存量案例里几乎条条都有（XPath 而非 data-testid），它不是「违规」。
 */
class StandardsValidatorSeedTest {

    private static final Path SEED = Path.of("../../seed/cases");

    private final StandardsValidator validator = new StandardsValidator();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("校验器的判定与种子标注完全一致（80 条）")
    void matchesSeedAnnotations() throws IOException {
        List<String> mismatches = new ArrayList<>();
        int total = 0;
        int flagged = 0;

        try (Stream<Path> files = Files.list(SEED)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                TestCase testCase = mapper.readValue(file.toFile(), TestCase.class);
                total++;

                Set<String> expected = new TreeSet<>(
                        testCase.violationCodes() == null ? List.of() : testCase.violationCodes());
                if (!expected.isEmpty()) {
                    flagged++;
                }

                Set<String> actual = new TreeSet<>(
                        validator.validate(testCase).findings().stream()
                                .filter(f -> f.severity() != StdCode.Severity.INFO)
                                .map(f -> f.std().display())
                                .distinct()
                                .toList());

                if (!expected.equals(actual)) {
                    mismatches.add("%s 标注=%s 实测=%s".formatted(testCase.caseCode(), expected, actual));
                }
            }
        }

        assertEquals(80, total, "种子案例应有 80 条");
        assertEquals(15, flagged, "其中 15 条带违规标注");
        assertEquals(List.of(), mismatches,
                "校验器与标注不一致的案例：\n  " + String.join("\n  ", mismatches));
    }

    @Test
    @DisplayName("干净的案例不会被误报为不可保存")
    void cleanCasesPass() throws IOException {
        try (Stream<Path> files = Files.list(SEED)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                TestCase testCase = mapper.readValue(file.toFile(), TestCase.class);
                boolean annotatedClean = testCase.violationCodes() == null || testCase.violationCodes().isEmpty();
                if (annotatedClean) {
                    ValidationResult result = validator.validate(testCase);
                    assertEquals(0, result.count(StdCode.Severity.ERROR),
                            testCase.caseCode() + " 未被标注违规，却报出了 ERROR："
                                    + result.of(StdCode.Severity.ERROR));
                }
            }
        }
    }

    @Test
    @DisplayName("STD-003 建议档确实在报，但不拦人")
    void adviceDoesNotBlock() throws IOException {
        int withAdvice = 0;
        int blockedByAdvice = 0;

        try (Stream<Path> files = Files.list(SEED)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                TestCase testCase = mapper.readValue(file.toFile(), TestCase.class);
                ValidationResult result = validator.validate(testCase);
                if (!result.of(StdCode.Severity.INFO).isEmpty()) {
                    withAdvice++;
                    // 只有 INFO 的案例必须仍然可保存 —— 建议不该拦人
                    if (result.count(StdCode.Severity.ERROR) == 0 && !result.passed()) {
                        blockedByAdvice++;
                    }
                }
            }
        }

        assertTrue(withAdvice > 0, "存量案例里有大量裸 XPath，应该有 data-testid 的改进建议");
        assertEquals(0, blockedByAdvice, "INFO 档的建议不该让案例变成不可保存");
    }
}
