package com.atp.rag.ingest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 解析后的 Markdown 文档：一级标题当文档标题，其余标题切成带层级路径的小节。
 *
 * <p>只处理 ATX 风格（{@code # 标题}）。语料是自己生成的，不需要支持 Setext 那种下划线标题。
 */
public final class MarkdownDocument {

    /** 一个最小的可检索单元：一段正文，加上它所处的标题层级。 */
    public static final class Section {

        private final List<String> headingPath;
        private final String body;

        Section(List<String> headingPath, String body) {
            this.headingPath = Collections.unmodifiableList(new ArrayList<String>(headingPath));
            this.body = body;
        }

        /** 从二级标题往下的路径，不含文档标题。 */
        public List<String> headingPath() {
            return headingPath;
        }

        public String body() {
            return body;
        }
    }

    private final String sourceId;
    private final String title;
    private final String fullText;
    private final List<Section> sections;

    private MarkdownDocument(String sourceId, String title, String fullText, List<Section> sections) {
        this.sourceId = sourceId;
        this.title = title;
        this.fullText = fullText;
        this.sections = Collections.unmodifiableList(sections);
    }

    /**
     * @param sourceId 相对语料根的路径，如 {@code manual/04-定位器指南.md}。
     *                 评估集的 golden_ids 用它做前缀，所以必须稳定
     */
    public static MarkdownDocument parse(Path file, String sourceId) {
        String text = read(file);
        String title = extractTitle(text, sourceId);
        return new MarkdownDocument(sourceId, title, text, extractSections(text));
    }

    private static String extractTitle(String text, String fallbackId) {
        for (String line : text.split("\n", -1)) {
            if (line.startsWith("# ")) {
                return line.substring(2).trim();
            }
        }
        // 没有一级标题就退回文件名。CorpusIntegrityTest 会拦住这种情况，
        // 但解析器不该因为语料不规范就崩掉
        int slash = fallbackId.lastIndexOf('/');
        String name = slash >= 0 ? fallbackId.substring(slash + 1) : fallbackId;
        return name.endsWith(".md") ? name.substring(0, name.length() - 3) : name;
    }

    /**
     * 扫描标题行，维护一个层级栈，把正文归到当前最深的标题下。
     *
     * <p>正文归属的是<b>最深</b>的标题，所以 {@code ## 章} 下面、{@code ### 节} 之前的
     * 那段引言会单独成为一个 path 只有一层的 section。这是对的 —— 那段话确实属于章而不属于任何节。
     */
    private static List<Section> extractSections(String text) {
        List<Section> sections = new ArrayList<Section>();

        // 当前所处的标题层级，形如 ["常见错误", "绝对路径"]。
        // 遇到同级或更浅的标题时会被截断，遇到更深的标题时压栈
        List<String> stack = new ArrayList<String>();

        // 攒当前这段正文，遇到下一个标题时结算
        StringBuilder body = new StringBuilder();

        // 是否正处在 ``` 代码块内部
        boolean inFence = false;

        // split("\n", -1) 的 -1 表示保留末尾空串，避免丢掉文件最后的空行
        for (String line : text.split("\n", -1)) {
            // ``` 围栏内的 # 是注释或 shell 提示符，不是标题
            if (line.startsWith("```")) {
                inFence = !inFence;     // 进入或离开代码块
                body.append(line).append('\n');
                continue;
            }

            // 在代码块里就一律当正文，不去判断标题
            int level = inFence ? 0 : headingLevel(line);

            // 不是标题行 → 累积进当前正文
            if (level == 0) {
                body.append(line).append('\n');
                continue;
            }

            // 走到这里说明遇到了新标题。先把上一段正文结算掉，
            // 否则它会被错误地归到新标题名下
            flush(sections, stack, body);

            // 一级标题是文档名（已经被 extractTitle 单独取走），不参与层级路径。
            // 清空栈是因为 # 之后如果又出现 ##，那是新的一章
            if (level == 1) {
                stack.clear();
                continue;
            }

            // level 2 -> 栈深 1，level 3 -> 栈深 2，依此类推。
            // 先砍到父级深度再压入，天然处理了跳级（## 直接到 ####）的情况
            int depth = level - 2;
            while (stack.size() > depth) {
                stack.remove(stack.size() - 1);
            }
            // line.substring(level + 1) 跳过 "### " 这几个字符，只留标题文字
            stack.add(line.substring(level + 1).trim());
        }

        // 循环结束时手上还攒着最后一段正文，收掉
        flush(sections, stack, body);
        return sections;
    }

    /** 把攒着的正文连同当前标题路径存成一个 section，然后清空缓冲。 */
    private static void flush(List<Section> sections, List<String> stack, StringBuilder body) {
        String content = body.toString().trim();

        // 两种情况不产出 section：
        //   正文是空的 —— 连续两个标题之间没内容
        //   栈是空的   —— 还没遇到任何 ## 标题，这是文档开头的引言，没有层级路径可挂
        if (!content.isEmpty() && !stack.isEmpty()) {
            // 传 stack 进去时构造函数会拷贝一份，所以后续修改 stack 不影响已存的 section
            sections.add(new Section(stack, content));
        }

        // 清空缓冲，准备攒下一段
        body.setLength(0);
    }

    /** 返回 ATX 标题的级数，不是标题返回 0。 */
    private static int headingLevel(String line) {
        int hashes = 0;
        while (hashes < line.length() && line.charAt(hashes) == '#') {
            hashes++;
        }
        // 必须是 "#{1,6} " 后面跟空格，否则 "#!/bin/sh" 之类会被误判成标题
        if (hashes == 0 || hashes > 6 || hashes >= line.length() || line.charAt(hashes) != ' ') {
            return 0;
        }
        return hashes;
    }

    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 " + file + " 失败", e);
        }
    }

    public String sourceId() {
        return sourceId;
    }

    public String title() {
        return title;
    }

    public String fullText() {
        return fullText;
    }

    public List<Section> sections() {
        return sections;
    }
}
