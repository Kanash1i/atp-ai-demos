package dev.kanashi.atp.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ⭐ 机械地守住一条架构不变式：<b>SQL 只出现在 {@code store} 包</b>。
 *
 * <p>面试时"翻一个包就能把并发设计讲完"这个说法，靠的就是这条不变式。
 * 但**靠约定守不住** —— 下一个人图方便在某个 command 里直接拼一句 SQL，
 * 约定就破了，而且没有任何东西会提醒他。所以把它写成测试。
 *
 * <p>这跟本项目反复出现的那条判据是同一件事：
 * <b>机器能判定的规则，就该交给机器强制，而不是写在文档里指望人自觉。</b>
 */
@DisplayName("架构不变式")
class SqlContainmentTest {

    private static final Path SRC = Path.of("src/main/java");
    private static final String STORE_PACKAGE = "dev/kanashi/atp/cli/store/";

    private static final Pattern SQL = Pattern.compile(
            "\\b(SELECT\\s+\\w|INSERT\\s+INTO|UPDATE\\s+\\w+\\s+SET|DELETE\\s+FROM|ALTER\\s+TABLE|CREATE\\s+TABLE)\\b",
            Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("store 包之外不得出现 SQL 语句")
    void sqlOnlyInStorePackage() throws IOException {
        List<String> offenders = new ArrayList<>();

        try (Stream<Path> files = Files.walk(SRC)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = SRC.relativize(f).toString().replace('\\', '/');
                if (rel.startsWith(STORE_PACKAGE)) {
                    continue;
                }
                int lineNo = 0;
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    lineNo++;
                    String code = stripComment(line);
                    if (SQL.matcher(code).find()) {
                        offenders.add("%s:%d  %s".formatted(rel, lineNo, line.strip()));
                    }
                }
            }
        }

        assertThat(offenders)
                .as("SQL 必须留在 store 包里；上面这些地方漏出来了")
                .isEmpty();
    }

    /** 去掉行注释与块注释正文，避免 javadoc 里提一句 SELECT 就误报。 */
    private static String stripComment(String line) {
        String t = line.strip();
        if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) {
            return "";
        }
        int slash = line.indexOf("//");
        return slash >= 0 ? line.substring(0, slash) : line;
    }
}
