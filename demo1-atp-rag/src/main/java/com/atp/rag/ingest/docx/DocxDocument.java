package com.atp.rag.ingest.docx;

import com.atp.rag.ingest.DocSection;
import com.atp.rag.ingest.ParsedDocument;
import com.atp.rag.ingest.image.ImageDescriber;
import com.atp.rag.storage.ObjectStorage;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 DOCX：按段落样式（{@code Heading1}~{@code Heading9}）切成带标题层级的小节。
 *
 * <h3>为什么不用 Tika</h3>
 *
 * Tika（也就是 Spring AI 的 {@code TikaDocumentReader} 走的路）把 docx 抽成一坨扁平文本，
 * <b>段落样式全丢</b>。而 docx 的标题层级恰恰就存在样式里：
 *
 * <pre>
 * &lt;w:pPr&gt;&lt;w:pStyle w:val="Heading2"/&gt;&lt;w:outlineLvl w:val="1"/&gt;&lt;/w:pPr&gt;
 * </pre>
 *
 * 丢了它，{@code HEADING_PATH} 和 {@code PARENT_CHILD} 两个策略在 docx 上就退化成 FIXED。
 * POI 能直接读到，所以这里走 POI。
 *
 * <h3>比 PDF 可靠得多</h3>
 *
 * PDF 那边要靠 outline 书签的 Y 坐标去切矩形，坐标系换算、书签缺坐标、
 * 有的 PDF 干脆没书签 —— 一堆边界情况。docx 的层级是<b>结构化、显式</b>的，
 * 读出来就是几级，没有猜的成分。
 *
 * <h3>表格转回 markdown 语法</h3>
 *
 * {@link XWPFTable} 被还原成 {@code | a | b |} 形式。这样同一份内容的
 * md 版和 docx 版，section 正文文本几乎逐字一致 ——
 * 三格式对照才是干净的单变量对比，否则「格式的影响」里会混进「表格表示法的影响」。
 */
public final class DocxDocument implements ParsedDocument {

    /**
     * 样式名 → 层级。Word 写 {@code Heading2}，但有些工具写 {@code heading 2}
     * （中间带空格、小写），两种都要认。
     */
    private static final Pattern HEADING_STYLE =
            Pattern.compile("^heading\\s*([1-9])$", Pattern.CASE_INSENSITIVE);

    private final String sourceId;
    private final String title;
    private final String fullText;
    private final List<DocSection> sections;

    private DocxDocument(String sourceId, String title, String fullText,
                         List<DocSection> sections) {
        this.sourceId = sourceId;
        this.title = title;
        this.fullText = fullText;
        this.sections = Collections.unmodifiableList(sections);
    }

    /** 只解析文本，不处理内嵌图片。 */
    public static DocxDocument parse(Path file, String sourceId) throws IOException {
        return parse(file, sourceId, null, null);
    }

    /**
     * 解析文本<b>并处理内嵌图片</b>：抽出 → 转文字描述 → 原图存进对象存储。
     *
     * <p>比 PDF 那边简单得多：docx 的图片就嵌在某个 {@link XWPFRun} 里，
     * 而 run 属于哪个段落是明确的，所以「这张图属于哪一节」不需要靠坐标推算。
     *
     * @param describer 图片转描述。传 null 表示不处理图片
     * @param storage   原图存放处。传 null 表示不存原图（只转描述）
     */
    public static DocxDocument parse(Path file, String sourceId,
                                     ImageDescriber describer, ObjectStorage storage)
            throws IOException {
        InputStream in = Files.newInputStream(file);
        try {
            XWPFDocument doc = new XWPFDocument(in);
            try {
                return build(doc, sourceId, describer, storage);
            } finally {
                doc.close();
            }
        } finally {
            in.close();
        }
    }

    /**
     * 遍历文档体，维护标题层级栈 —— 和 {@code MarkdownDocument.extractSections}
     * 是同一套算法，只是「什么算标题」的判据不同。
     */
    private static DocxDocument build(XWPFDocument doc, String sourceId,
                                      ImageDescriber describer, ObjectStorage storage) {
        String docTitle = null;

        // 当前小节累积的原图地址。与 body 一起在 flush 时结算 ——
        // 两者必须同步清空，否则图片会漏到下一节去
        List<String> imageUrls = new ArrayList<String>();

        // 图片序号，用来生成稳定的存储 key（消融实验会反复重跑，key 不稳就堆副本）
        int[] imageCounter = {0};

        // 当前所处的标题层级，形如 ["等待策略", "NONE"]
        List<String> stack = new ArrayList<String>();

        StringBuilder body = new StringBuilder();
        StringBuilder fullText = new StringBuilder();
        List<DocSection> sections = new ArrayList<DocSection>();

        // ⚠️ 必须走 getBodyElements() 而不是 getParagraphs() ——
        // 后者只给段落，表格会被整个跳过，而且拿不到「表格夹在哪两段之间」的顺序
        for (IBodyElement element : doc.getBodyElements()) {
            if (element instanceof XWPFTable) {
                String table = renderTable((XWPFTable) element);
                if (!table.isEmpty()) {
                    appendLine(body, table);
                    appendLine(fullText, table);
                }
                continue;
            }
            if (!(element instanceof XWPFParagraph)) {
                continue;
            }

            XWPFParagraph paragraph = (XWPFParagraph) element;
            String text = paragraph.getText();
            int level = headingLevelOf(paragraph);

            if (level == 0) {
                // 普通段落 → 累积进当前正文
                if (text != null && !text.trim().isEmpty()) {
                    appendLine(body, text.trim());
                    appendLine(fullText, text.trim());
                }
                // 段落里可能嵌着图。描述文本就地插入（位置和原文一致，
                // 比 PDF 那边只能追加到小节末尾更精确）
                if (describer != null) {
                    String context = String.join(" > ", stack);
                    for (String description : handleImages(paragraph, sourceId, describer,
                            storage, imageUrls, imageCounter, context)) {
                        appendLine(body, description);
                        appendLine(fullText, description);
                    }
                }
                continue;
            }

            // 遇到标题：先结算上一段正文，否则它会被归到新标题名下
            flush(sections, stack, body, imageUrls);
            appendLine(fullText, text == null ? "" : text.trim());

            String headingText = text == null ? "" : text.trim();

            // Heading1 是文档标题，不进层级路径（与 markdown 的 # 一致）
            if (level == 1) {
                if (docTitle == null) {
                    docTitle = headingText;
                }
                stack.clear();
                continue;
            }

            // Heading2 → 栈深 1，Heading3 → 栈深 2。
            // 先砍到父级深度再压入，天然处理跳级（Heading2 直接到 Heading4）
            int depth = level - 2;
            while (stack.size() > depth) {
                stack.remove(stack.size() - 1);
            }
            stack.add(headingText);
        }

        flush(sections, stack, body, imageUrls);

        if (docTitle == null || docTitle.isEmpty()) {
            docTitle = fileBaseName(sourceId);
        }
        return new DocxDocument(sourceId, docTitle, fullText.toString().trim(), sections);
    }

    /**
     * 判断一个段落是不是标题，是的话返回几级。
     *
     * <p>两个判据，缺一不可：
     * <ol>
     *   <li><b>样式名</b> {@code Heading2} —— 最常见，Word 内置标题样式</li>
     *   <li><b>{@code outlineLvl}</b> —— 有些文档用自定义样式名（企业模板里
     *       「章标题」「节标题」之类），样式名匹配不上，但大纲级别是设了的。
     *       Word 的导航窗格也是靠它，所以它是更本质的判据</li>
     * </ol>
     *
     * @return 1~9；不是标题返回 0
     */
    private static int headingLevelOf(XWPFParagraph paragraph) {
        String style = paragraph.getStyle();
        if (style != null) {
            Matcher matcher = HEADING_STYLE.matcher(style.trim());
            if (matcher.matches()) {
                return Integer.parseInt(matcher.group(1));
            }
        }

        // 退回大纲级别。outlineLvl 从 0 开始，Heading1 对应 0
        CTPPr ppr = paragraph.getCTP().getPPr();
        if (ppr != null && ppr.isSetOutlineLvl() && ppr.getOutlineLvl().getVal() != null) {
            int outlineLevel = ppr.getOutlineLvl().getVal().intValue();
            // 9 是 Word 里「正文」的大纲级别，不是标题
            if (outlineLevel >= 0 && outlineLevel <= 8) {
                return outlineLevel + 1;
            }
        }
        return 0;
    }

    /**
     * 把 docx 表格还原成 markdown 语法。
     *
     * <p>补上 {@code |---|---|} 分隔行 —— 它在 docx 里没有对应物（表头是靠样式区分的），
     * 但补上之后 md 版和 docx 版的正文文本才对得上。
     */
    private static String renderTable(XWPFTable table) {
        List<XWPFTableRow> rows = table.getRows();
        if (rows.isEmpty()) {
            return "";
        }

        StringBuilder out = new StringBuilder();
        for (int r = 0; r < rows.size(); r++) {
            List<XWPFTableCell> cells = rows.get(r).getTableCells();
            StringBuilder line = new StringBuilder("|");
            for (XWPFTableCell cell : cells) {
                // 单元格里可能有多段，用空格连起来 —— markdown 表格一格放不了换行
                line.append(' ').append(cellText(cell)).append(" |");
            }
            out.append(line);

            // 第一行之后补分隔行，还原成 markdown 表格的样子
            if (r == 0) {
                out.append("\n|");
                for (int c = 0; c < cells.size(); c++) {
                    out.append("---|");
                }
            }
            if (r < rows.size() - 1) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static String cellText(XWPFTableCell cell) {
        StringBuilder text = new StringBuilder();
        for (XWPFParagraph paragraph : cell.getParagraphs()) {
            String value = paragraph.getText();
            if (value == null || value.trim().isEmpty()) {
                continue;
            }
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(value.trim());
        }
        return text.toString();
    }

    /**
     * 抽出一个段落里的图片：转描述、存原图。
     *
     * @param imageUrls   原图地址收集到这里（会被 flush 结算进 section）
     * @return 每张图的文字描述，供调用方插进正文
     */
    private static List<String> handleImages(XWPFParagraph paragraph, String sourceId,
                                             ImageDescriber describer, ObjectStorage storage,
                                             List<String> imageUrls, int[] counter,
                                             String context) {
        List<String> descriptions = new ArrayList<String>();
        for (XWPFRun run : paragraph.getRuns()) {
            for (XWPFPicture picture : run.getEmbeddedPictures()) {
                byte[] content = picture.getPictureData().getData();
                if (content == null || content.length == 0) {
                    continue;
                }
                counter[0]++;

                String extension = picture.getPictureData().suggestFileExtension();
                String nameHint = fileBaseName(sourceId) + "-img-" + counter[0]
                        + "." + (extension == null || extension.isEmpty() ? "png" : extension);

                // docx 的图可以带替代文本（docPr/@descr）。有就用上 ——
                // 它是作者写的，往往比 VLM 猜的更准
                String alt = picture.getDescription();

                String description = describer.describeBytes(content, nameHint, alt, context);
                if (!description.isEmpty()) {
                    descriptions.add(description);
                }
                if (storage != null) {
                    imageUrls.add(storage.put("images/" + baseName(sourceId) + "/" + nameHint,
                            content, mimeOf(extension)));
                }
            }
        }
        return descriptions;
    }

    private static String mimeOf(String extension) {
        if (extension == null) {
            return "image/png";
        }
        String lower = extension.toLowerCase();
        if (lower.equals("jpg") || lower.equals("jpeg")) {
            return "image/jpeg";
        }
        if (lower.equals("gif")) {
            return "image/gif";
        }
        return "image/png";
    }

    /** {@code manual/05-等待策略.docx} → {@code manual/05-等待策略}（保留目录，用于 key 前缀） */
    private static String baseName(String sourceId) {
        int dot = sourceId.lastIndexOf('.');
        return dot > 0 ? sourceId.substring(0, dot) : sourceId;
    }

    /** 把攒着的正文连同当前标题路径、原图地址存成一个 section。 */
    private static void flush(List<DocSection> sections, List<String> stack,
                              StringBuilder body, List<String> imageUrls) {
        String content = body.toString().trim();
        // 与 markdown 那边一致：只有正文为空才不产出。
        // 标题栈为空（文档引言）也要留 —— 那一段是规范的制定日、管理部门等元信息
        //
        // 但「只有图片没有文字」的小节要留 —— 图注型小节是真实存在的
        if (!content.isEmpty() || !imageUrls.isEmpty()) {
            sections.add(new DocSection(stack, content, imageUrls));
        }
        body.setLength(0);
        imageUrls.clear();
    }

    private static void appendLine(StringBuilder buffer, String line) {
        if (buffer.length() > 0) {
            buffer.append('\n');
        }
        buffer.append(line);
    }

    /**
     * {@code manual/05-等待策略.docx} → {@code 05-等待策略}（去目录、去扩展名）。
     *
     * <p>两处在用：文档标题的兜底，以及图片名字 —— 图片名不带目录，
     * 否则拼进存储 key 会让目录段重复一遍。
     */
    private static String fileBaseName(String sourceId) {
        int slash = sourceId.lastIndexOf('/');
        String name = slash >= 0 ? sourceId.substring(slash + 1) : sourceId;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
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
