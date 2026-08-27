package com.atp.rag.ingest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import com.atp.rag.ingest.image.ImageDescriber;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析后的 Markdown 文档：一级标题当文档标题，其余标题切成带层级路径的小节。
 *
 * <p>只处理 ATX 风格（{@code # 标题}）。语料是自己生成的，不需要支持 Setext 那种下划线标题。
 */
public final class MarkdownDocument implements ParsedDocument {

    private final String sourceId;
    private final String title;
    private final String fullText;
    private final List<DocSection> sections;

    private MarkdownDocument(String sourceId, String title, String fullText, List<DocSection> sections) {
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
    private static List<DocSection> extractSections(String text) {
        List<DocSection> sections = new ArrayList<DocSection>();

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

    /**
     * markdown 图片语法 {@code ![alt](path)}。
     *
     * <p>只匹配这一种。HTML 的 {@code <img>} 标签不管 —— 语料是自己生成的 markdown，
     * 不会混 HTML；真遇到了也是漏掉一张图，不是错误。
     */
    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)]\\(([^)\\s]+)[^)]*\\)");

    /**
     * 把正文里的图片引用替换成文字描述，让图里的信息也能被检索到。
     *
     * <p>纯文本 embedding 模型（bge-m3）喂不进图片，所以一张截图不转文字就等于不存在。
     * 而 ATP 的手册里图往往<b>就是答案</b>（报错长什么样、字段填在哪）。
     *
     * <p>替换而不是追加：图片语法 {@code ![](img/x.png)} 本身对检索是纯噪音，
     * 路径里的斜杠和扩展名会被分词器切成一堆无意义的 token。
     *
     * @param describer 转描述的实现。返回空串时该图片引用被整个删掉
     * @param context   当前小节的标题路径，传给 VLM 当提示
     */
    static String replaceImages(String body, ImageDescriber describer, String context) {
        Matcher matcher = IMAGE.matcher(body);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String alt = matcher.group(1);
            String path = matcher.group(2);
            String description = describer.describe(path, alt, context);
            // 描述不出来就把这段图片语法删掉（替换成空），别留下 ![](…) 污染向量
            matcher.appendReplacement(out, Matcher.quoteReplacement(description));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** 正文里有没有图片引用 —— 用来决定要不要走描述流程，省掉无谓的正则替换。 */
    static boolean hasImage(String body) {
        return IMAGE.matcher(body).find();
    }

    /** 把攒着的正文连同当前标题路径存成一个 section，然后清空缓冲。 */
    private static void flush(List<DocSection> sections, List<String> stack, StringBuilder body) {
        String content = body.toString().trim();

        // 只有正文为空时才不产出（连续两个标题之间没内容）。
        //
        // ⚠️ 这里曾经还有一个 `&& !stack.isEmpty()` 的条件，把「# 标题之后、
        // 第一个 ## 之前」的<b>文档引言</b>整段丢掉了 —— 15 篇文档共 1464 字符，
        // 而且丢的都是高价值内容：每篇规范的「制定日／最終改訂／管理チーム」元信息，
        // 和每篇手册的「本篇讲什么」主旨段。
        //
        // 后果是「STD-001 是谁维护的」「什么时候改的」这类问题在库里根本没有答案，
        // 且不报错 —— 只表现为检索莫名召回不到。发现它是因为生成 PDF 语料时
        // 比对 md 与 PDF 文本层，看到一批普通汉字凭空消失（DECISIONS.md D-023）。
        //
        // 引言的 headingPath 是空列表，这是<b>正确</b>的：它确实不属于任何小节。
        // 下游对空 path 早有处理 —— anchor() 退化成 sourceId、
        // 前缀只剩 [文档标题]、父子切块把它归入 "(前言)" 组
        if (!content.isEmpty()) {
            // 传 stack 进去时构造函数会拷贝一份，所以后续修改 stack 不影响已存的 section
            sections.add(new DocSection(stack, content));
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

    @Override
    public String sourceId() {
        return sourceId;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public String fullText() {
        return fullText;
    }

    @Override
    public List<DocSection> sections() {
        return sections;
    }
}
