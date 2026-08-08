package com.atp.rag.ingest;

import com.atp.rag.config.RagConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切分器的行为测试。
 *
 * <p>切分是消融表第 1、2 行的全部差别所在，切错了整张表就是错的，
 * 而且这类错误<b>不会报错</b> —— 它只会让某一行的数字看起来「优化效果不明显」。
 */
class SplitterTest {

    private static final String SAMPLE = String.join("\n",
            "# 定位器指南",
            "",
            "开篇导言，不属于任何二级标题。",
            "",
            "## 属性选择的优先级",
            "",
            "这段属于章，不属于任何节。",
            "",
            "### 稳定性排序",
            "",
            "优先使用 data-testid 属性，其次是 name，避免依赖 class。",
            "",
            "### 不要依赖 class",
            "",
            "class 是给样式用的，改个配色就可能变。",
            "",
            "## 常见错误",
            "",
            "### 绝对路径",
            "",
            "```bash",
            "# 这是代码块里的井号，不是标题",
            "echo hello",
            "```",
            "",
            "浏览器复制出来的就是这种。");

    private MarkdownDocument parse(Path dir, String content) throws IOException {
        Path file = dir.resolve("04-定位器指南.md");
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return MarkdownDocument.parse(file, "manual/04-定位器指南.md");
    }

    @Test
    @DisplayName("一级标题作为文档标题，不进入标题路径")
    void titleIsExtractedAndExcludedFromPath(@TempDir Path dir) throws IOException {
        MarkdownDocument doc = parse(dir, SAMPLE);
        assertEquals("定位器指南", doc.title());
        for (MarkdownDocument.Section section : doc.sections()) {
            assertFalse(section.headingPath().contains("定位器指南"),
                    "文档标题不该出现在 section 的标题路径里，它由前缀单独拼");
        }
    }

    @Test
    @DisplayName("正文归属最深的标题，章级引言单独成段")
    void bodyBelongsToDeepestHeading(@TempDir Path dir) throws IOException {
        MarkdownDocument doc = parse(dir, SAMPLE);
        List<String> paths = new ArrayList<String>();
        for (MarkdownDocument.Section s : doc.sections()) {
            paths.add(String.join(" > ", s.headingPath()));
        }
        assertTrue(paths.contains("属性选择的优先级"),
                "## 下、### 之前的引言应单独成段，实际路径有：" + paths);
        assertTrue(paths.contains("属性选择的优先级 > 稳定性排序"), "实际路径有：" + paths);
        assertTrue(paths.contains("常见错误 > 绝对路径"), "实际路径有：" + paths);
    }

    @Test
    @DisplayName("代码块里的 # 不被当成标题")
    void hashInsideFenceIsNotHeading(@TempDir Path dir) throws IOException {
        MarkdownDocument doc = parse(dir, SAMPLE);
        for (MarkdownDocument.Section section : doc.sections()) {
            for (String heading : section.headingPath()) {
                assertFalse(heading.contains("代码块里的井号"),
                        "``` 围栏内的 # 被误判成了标题：" + heading);
            }
        }
    }

    @Test
    @DisplayName("HEADING_PATH 策略：embedText 带前缀，rawText 不带")
    void headingPathPrefixIsOnlyInEmbedText(@TempDir Path dir) throws IOException {
        MarkdownDocument doc = parse(dir, SAMPLE);
        List<Chunk> chunks = new HeadingPathSplitter(heading()).split(doc);

        Chunk target = null;
        for (Chunk c : chunks) {
            if (c.rawText().contains("data-testid")) {
                target = c;
            }
        }
        assertTrue(target != null, "没有切出含 data-testid 的 chunk");
        assertEquals("[定位器指南 > 属性选择的优先级 > 稳定性排序]\n"
                        + "优先使用 data-testid 属性，其次是 name，避免依赖 class。",
                target.embedText());
        assertFalse(target.rawText().startsWith("["),
                "rawText 是展示给人看的，不该带前缀");
        assertEquals("manual/04-定位器指南.md#稳定性排序", target.anchor());
    }

    @Test
    @DisplayName("FIXED 策略：不带任何标题信息")
    void fixedStrategyHasNoHeadingContext(@TempDir Path dir) throws IOException {
        MarkdownDocument doc = parse(dir, SAMPLE);
        for (Chunk c : new HeadingPathSplitter(fixed()).split(doc)) {
            assertTrue(c.headingPath().isEmpty(), "baseline 不该有标题路径");
            assertEquals(c.rawText(), c.embedText(), "baseline 的两种文本应当相同");
        }
    }

    @Test
    @DisplayName("两种策略共用同一个大小上限，比较才公平")
    void bothStrategiesRespectSameSizeLimit(@TempDir Path dir) throws IOException {
        StringBuilder longDoc = new StringBuilder("# 长文档\n\n## 章节\n\n");
        for (int i = 0; i < 200; i++) {
            longDoc.append("这是第 ").append(i).append(" 句用来把文档撑长的话。\n");
        }
        MarkdownDocument doc = parse(dir, longDoc.toString());

        int limit = heading().chunkSizeChars();
        for (Chunk c : new HeadingPathSplitter(heading()).split(doc)) {
            assertTrue(c.rawText().length() <= limit,
                    "HEADING_PATH 切出了超长块：" + c.rawText().length());
        }
        for (Chunk c : new HeadingPathSplitter(fixed()).split(doc)) {
            assertTrue(c.rawText().length() <= limit,
                    "FIXED 切出了超长块：" + c.rawText().length());
        }
    }

    @Test
    @DisplayName("overlap 接近 chunk 大小时不会死循环")
    void aggressiveOverlapTerminates(@TempDir Path dir) throws IOException {
        StringBuilder text = new StringBuilder("# 文档\n\n## 章\n\n");
        for (int i = 0; i < 100; i++) {
            text.append("填充内容填充内容填充内容。\n");
        }
        MarkdownDocument doc = parse(dir, text.toString());

        RagConfig extreme = RagConfig.builder()
                .chunkStrategy(RagConfig.ChunkStrategy.FIXED)
                .chunkSizeChars(100)
                .chunkOverlapChars(99)     // 只差 1，切分必须仍然前进
                .build();

        List<Chunk> chunks = new HeadingPathSplitter(extreme).split(doc);
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.size() < 5000, "块数异常，可能是切分没有前进");
    }

    @Test
    @DisplayName("overlap 不小于 chunk 大小时直接拒绝构建")
    void invalidOverlapIsRejected() {
        try {
            RagConfig.builder().chunkSizeChars(100).chunkOverlapChars(100).build();
            org.junit.jupiter.api.Assertions.fail("overlap == size 应当被拒绝");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("chunkOverlapChars"));
        }
    }

    @Test
    @DisplayName("真实语料：每篇都能切出块，且 anchor 可用作评估锚点")
    void realCorpusSplitsCleanly() {
        Path root = Paths.get("corpus/docs");
        assertTrue(Files.isDirectory(root), "找不到 corpus/docs，测试需在模块根目录运行");

        HeadingPathSplitter splitter = new HeadingPathSplitter(heading());
        int totalChunks = 0;
        for (String sub : Arrays.asList("manual", "standards")) {
            for (Path file : listMarkdown(root.resolve(sub))) {
                String sourceId = sub + "/" + file.getFileName();
                List<Chunk> chunks = splitter.split(MarkdownDocument.parse(file, sourceId));
                assertFalse(chunks.isEmpty(), sourceId + " 没有切出任何块");
                for (Chunk c : chunks) {
                    assertFalse(c.rawText().trim().isEmpty(), sourceId + " 切出了空块");
                    assertTrue(c.anchor().startsWith(sourceId), "anchor 前缀应为 sourceId");
                    assertTrue(c.embedText().startsWith("["), "HEADING_PATH 的块应带前缀");
                }
                totalChunks += chunks.size();
            }
        }
        assertTrue(totalChunks > 50, "15 篇文档只切出 " + totalChunks + " 块，明显偏少");
    }

    private static RagConfig heading() {
        return RagConfig.builder().chunkStrategy(RagConfig.ChunkStrategy.HEADING_PATH).build();
    }

    private static RagConfig fixed() {
        return RagConfig.builder().chunkStrategy(RagConfig.ChunkStrategy.FIXED).build();
    }

    private static List<Path> listMarkdown(Path dir) {
        java.io.File[] files = dir.toFile().listFiles(new java.io.FilenameFilter() {
            public boolean accept(java.io.File d, String name) {
                return name.endsWith(".md");
            }
        });
        List<Path> paths = new ArrayList<Path>();
        if (files != null) {
            Arrays.sort(files);
            for (java.io.File f : files) {
                paths.add(f.toPath());
            }
        }
        return paths;
    }
}
