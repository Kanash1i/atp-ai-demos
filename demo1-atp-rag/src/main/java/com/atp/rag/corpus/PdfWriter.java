package com.atp.rag.corpus;

import com.atp.rag.ingest.DocSection;
import com.atp.rag.ingest.MarkdownDocument;
import com.atp.rag.ingest.image.ImagePathResolver;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把一篇 Markdown 写成<b>带导航书签</b>的 PDF —— 用来造多格式测试语料。
 *
 * <h3>为什么要自己生成而不是找现成 PDF</h3>
 *
 * 项目红线：不抓取任何真实站点、不使用任何真实公司资产。所以语料只能自己造。
 * 而现成的转换工具（pandoc / libreoffice）本机没有，装了也多一层环境依赖 ——
 * PDFBox 本来就是解析侧的依赖，拿它顺手写 PDF，零外部工具。
 *
 * <h3>为什么必须带 outline</h3>
 *
 * 「按导航切块」这条链路的输入就是 PDF 的 outline（书签 / TOC）。
 * 没有书签的 PDF 只能退化成按页切或按字号猜标题 —— 那是另一条降级路径。
 * 这里生成的是<b>规整 PDF</b>（企业里 Word / LaTeX 导出的手册通常都带书签），
 * 用来验证主路径。无书签的场景由 {@code stripOutline} 单独造。
 *
 * <h3>书签必须带 Y 坐标</h3>
 *
 * 只记页码是不够的：一页上通常有好几个小节，只有页码的话它们会被切成同一块。
 * 所以每个书签存成 {@link PDPageXYZDestination} 并写入 {@code top} ——
 * 解析侧（Spring AI 那套算法）正是靠这个坐标把同页的多个小节分开的。
 */
public final class PdfWriter {

    // ── 版面参数 ──────────────────────────────────────────────
    // 都是排版常识值，不需要可配置：这是造语料，不是排版引擎

    private static final PDRectangle PAGE_SIZE = PDRectangle.A4;    // 595 x 842 pt
    private static final float MARGIN = 60;
    private static final float BODY_SIZE = 10.5f;
    private static final float LINE_SPACING = 1.7f;

    /** 标题字号按层级递减。索引 0 是文档标题，1 是 {@code ##}，依此类推。 */
    private static final float[] HEADING_SIZES = {20, 15.5f, 12.5f, 11.5f};

    /** markdown 图片语法，与 {@link MarkdownDocument} 用的是同一套。 */
    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)]\\(([^)\\s]+)[^)]*\\)");

    private final PDDocument doc = new PDDocument();
    private final MixedFont font;

    /** 当前页与画笔。换页时一起换。 */
    private PDPage page;
    private PDPageContentStream stream;

    /** 当前基线的 Y 坐标（PDF 坐标系，原点左下角，向上为正）。 */
    private float cursorY;

    /**
     * outline 的层级栈。{@code stack.get(n)} 是深度 n 上最近一个书签，
     * 新书签挂到 {@code stack.get(depth-1)} 底下 —— 和 markdown 解析时
     * 维护标题栈是同一个套路。
     */
    private final List<PDOutlineItem> outlineStack = new ArrayList<PDOutlineItem>();

    private final PDDocumentOutline outline = new PDDocumentOutline();

    /**
     * 解析图片相对路径时依次尝试的根目录。
     *
     * <p>两个而不是一个 —— 语料里的图片路径相对的是 docs 根（{@code img/manual/x.png}），
     * 但别的文档可能相对自己所在目录写。都试一遍最省事，见 {@link ImagePathResolver}
     */
    private final Path[] imageRoots;

    private PdfWriter(Path... imageRoots) {
        this.imageRoots = imageRoots;
        PDFont cjk = CjkFontLoader.load(doc);
        // 拉丁字符走内置 Helvetica —— 外挂的 CJK fallback 字体多半没有 ASCII 字形
        this.font = new MixedFont(cjk, PDType1Font.HELVETICA);
        doc.getDocumentCatalog().setDocumentOutline(outline);
        newPage();
    }

    /**
     * 把一篇解析好的 markdown 写成 PDF。
     *
     * @param mdFile   源文件路径，用来定位图片
     * @param docsRoot 语料 docs 根目录，图片路径的另一个候选起点
     * @return 两个字体都画不出来、被丢弃的字符种类数。正常应该是 0；
     *         非 0 说明语料里有当前字体覆盖不到的符号（emoji 之类），
     *         调用方该报出来让人知道生成的 PDF 和 md 不是逐字一致的
     */
    public static int write(MarkdownDocument md, Path mdFile, Path target, Path docsRoot)
            throws IOException {
        PdfWriter writer = new PdfWriter(mdFile.getParent(), docsRoot);
        try {
            writer.render(md);
            // ⚠️ 必须先关掉最后一页的画笔再 save —— PDFBox 不允许在有未关闭的
            // PDPageContentStream 时序列化文档，否则抛
            // "Cannot read while there is an open stream writer"
            writer.closeStream();
            Files.createDirectories(target.getParent());
            writer.doc.save(target.toFile());
            return writer.droppedCharCount();
        } finally {
            writer.close();
        }
    }

    private void render(MarkdownDocument md) throws IOException {
        // 文档标题：既画出来，也作为 outline 的根书签
        heading(md.title(), 0);

        // 上一个 section 的标题路径，用来判断这次要新打印哪几级标题。
        // sections() 是保序的，相邻两节的 path 差异就是「中间隔了哪些标题」
        List<String> previousPath = new ArrayList<String>();

        for (DocSection section : md.sections()) {
            List<String> path = section.headingPath();

            // 从第一个不同的层级开始，把新出现的标题依次打印。
            // 例：上一节 [等待策略, NONE] → 这一节 [等待策略, PRESENCE]，
            // 共同前缀是 [等待策略]，所以只需要打印 "PRESENCE"
            int common = commonPrefixLength(previousPath, path);
            for (int depth = common; depth < path.size(); depth++) {
                // depth 0 对应 markdown 的 ##，所以标题层级是 depth+1
                heading(path.get(depth), depth + 1);
            }
            previousPath = path;

            body(section.body());
        }
    }

    private static int commonPrefixLength(List<String> a, List<String> b) {
        int i = 0;
        while (i < a.size() && i < b.size() && a.get(i).equals(b.get(i))) {
            i++;
        }
        return i;
    }

    // ── 绘制 ─────────────────────────────────────────────────

    /**
     * 画一个标题，同时在 outline 里挂一个书签。
     *
     * @param level 0 = 文档标题，1 = {@code ##}，2 = {@code ###}
     */
    private void heading(String text, int level) throws IOException {
        float size = HEADING_SIZES[Math.min(level, HEADING_SIZES.length - 1)];

        // 标题上方留白，让它和上一段拉开距离。
        // 顺便保证标题不会孤零零落在页脚 —— 剩余空间不够标题加两行正文就先换页
        cursorY -= size * 0.8f;
        ensureSpace(size * LINE_SPACING + BODY_SIZE * LINE_SPACING * 2);

        // ⚠️ 必须在 drawLine **之前**记下基线 —— drawLine 会把 cursorY
        // 往下推一整行（size × LINE_SPACING）。
        //
        // 曾经在 drawLine 之后才算书签坐标，结果书签指到了标题文字的**下方**：
        // 解析侧从那个 Y 往下切，标题自己不在区域内，反而把下一节的标题吃了进去。
        // 表现是 PDF 比 md 多出 4 个 section（那些「有标题无正文」的过渡层级
        // 抽到的正文正好是下一节的标题）。由 PdfDocumentTest 钉死
        float baseline = cursorY;

        drawLine(text, size);

        // 基线 + 一个字号 ≈ 文字顶端（ascender 通常是 0.8 字号，取满一个字号更安全）。
        // 解析侧按这个 Y 往下切，标题会落在自己那一节的区域里
        addBookmark(text, level, baseline + size);

        cursorY -= size * 0.4f;     // 标题下方也留一点
    }

    /** 画一段正文，处理换行、分页和内嵌图片。 */
    private void body(String text) throws IOException {
        for (String line : text.split("\n", -1)) {
            Matcher imageMatch = IMAGE.matcher(line);
            if (imageMatch.find()) {
                // 图片单独占一行。真的把图嵌进 PDF —— 后面「从 PDF 抽图转文字」
                // 那条链路需要它是真图，不是占位文字
                drawImage(imageMatch.group(2), imageMatch.group(1));
                continue;
            }
            if (line.trim().isEmpty()) {
                cursorY -= BODY_SIZE * LINE_SPACING * 0.5f;     // 空行 = 半行间距
                continue;
            }
            // 一行 markdown 可能宽于版心，按可用宽度折成若干行
            for (String wrapped : wrap(line, BODY_SIZE)) {
                ensureSpace(BODY_SIZE * LINE_SPACING);
                drawLine(wrapped, BODY_SIZE);
            }
        }
        cursorY -= BODY_SIZE * LINE_SPACING * 0.5f;
    }

    /**
     * 按版心宽度折行。
     *
     * <p>CJK 可以在任意字符间断开，拉丁词最好别断在词中间 ——
     * 所以超宽时先回退到最近的空格，回退不动（比如一个超长的 URL）才硬断。
     */
    private List<String> wrap(String line, float size) throws IOException {
        float maxWidth = PAGE_SIZE.getWidth() - MARGIN * 2;
        List<String> lines = new ArrayList<String>();

        StringBuilder current = new StringBuilder();
        int lastSpace = -1;         // current 里最后一个空格的位置

        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            current.append(ch);
            if (ch == ' ') {
                lastSpace = current.length() - 1;
            }

            if (font.widthOf(current.toString(), size) <= maxWidth) {
                continue;
            }

            // 超宽了。优先在空格处断，且断点不能太靠前（否则一行只剩几个字）
            if (lastSpace > current.length() / 2) {
                lines.add(current.substring(0, lastSpace));
                String rest = current.substring(lastSpace + 1);
                current = new StringBuilder(rest);
            } else {
                // 没有合适的空格：把刚加进来的这个字符退回去，从它开始下一行
                current.deleteCharAt(current.length() - 1);
                lines.add(current.toString());
                current = new StringBuilder(String.valueOf(ch));
            }
            lastSpace = -1;
        }

        if (current.length() > 0) {
            lines.add(current.toString());
        }
        return lines;
    }

    private void drawLine(String text, float size) throws IOException {
        stream.beginText();
        stream.newLineAtOffset(MARGIN, cursorY);
        font.showText(stream, text, size);
        stream.endText();
        cursorY -= size * LINE_SPACING;
    }

    /**
     * 把 markdown 引用的图片真的嵌进 PDF。
     *
     * <p>找不到文件时退化成一行斜体说明而不是报错 —— 语料里图片缺失是内容问题，
     * 不该让整个生成流程挂掉。
     */
    private void drawImage(String relativePath, String alt) throws IOException {
        Path imageFile = ImagePathResolver.resolve(relativePath, imageRoots);
        if (imageFile == null) {
            for (String wrapped : wrap("[图片缺失：" + relativePath + "]", BODY_SIZE)) {
                ensureSpace(BODY_SIZE * LINE_SPACING);
                drawLine(wrapped, BODY_SIZE);
            }
            return;
        }

        PDImageXObject image = PDImageXObject.createFromFile(imageFile.toString(), doc);

        // 等比缩放到版心宽度以内，同时不超过半页高 —— 太高的图会把整页占满，
        // 排版难看且不像真实手册
        float maxWidth = PAGE_SIZE.getWidth() - MARGIN * 2;
        float maxHeight = PAGE_SIZE.getHeight() * 0.45f;
        float scale = Math.min(maxWidth / image.getWidth(), maxHeight / image.getHeight());
        scale = Math.min(scale, 1f);        // 小图不放大
        float width = image.getWidth() * scale;
        float height = image.getHeight() * scale;

        ensureSpace(height + BODY_SIZE * LINE_SPACING * 2);

        cursorY -= height;
        stream.drawImage(image, MARGIN, cursorY, width, height);
        cursorY -= BODY_SIZE * 0.6f;

        // 图注：让 alt 文本也进 PDF 文本层。
        // 真实手册的图下面本来就有图注，而且这给「抽图转文字」那条链路留了个对照 ——
        // 可以比对 VLM 描述和图注说的是不是一回事
        if (alt != null && !alt.isEmpty()) {
            for (String wrapped : wrap("图：" + alt, BODY_SIZE - 1)) {
                ensureSpace((BODY_SIZE - 1) * LINE_SPACING);
                drawLine(wrapped, BODY_SIZE - 1);
            }
        }
        cursorY -= BODY_SIZE * LINE_SPACING * 0.5f;
    }

    // ── 分页 ─────────────────────────────────────────────────

    /** 剩余高度不够 {@code needed} 就换页。 */
    private void ensureSpace(float needed) throws IOException {
        if (cursorY - needed < MARGIN) {
            newPage();
        }
    }

    private void newPage() {
        try {
            if (stream != null) {
                stream.close();
            }
            page = new PDPage(PAGE_SIZE);
            doc.addPage(page);
            stream = new PDPageContentStream(doc, page);
            cursorY = PAGE_SIZE.getHeight() - MARGIN;
        } catch (IOException e) {
            throw new IllegalStateException("换页失败", e);
        }
    }

    // ── outline ──────────────────────────────────────────────

    /**
     * 挂一个书签。层级由 {@code level} 决定，靠一个栈维护父子关系。
     *
     * @param top 书签指向的 Y 坐标（PDF 坐标系）。解析侧就是拿它切文本的
     */
    private void addBookmark(String title, int level, float top) {
        PDPageXYZDestination dest = new PDPageXYZDestination();
        dest.setPage(page);
        dest.setTop((int) top);
        dest.setLeft(0);

        PDOutlineItem item = new PDOutlineItem();
        item.setTitle(title);
        item.setDestination(dest);

        // 栈砍到父级深度。跳级（## 直接到 ####）时栈可能比 level 浅，
        // 那就挂到现有的最深一级，不去补空节点
        while (outlineStack.size() > level) {
            outlineStack.remove(outlineStack.size() - 1);
        }
        if (outlineStack.isEmpty()) {
            outline.addLast(item);
        } else {
            outlineStack.get(outlineStack.size() - 1).addLast(item);
        }
        outlineStack.add(item);
    }

    /** 关掉当前页的画笔。save() 之前必须调用，见 {@link #write}。 */
    private void closeStream() throws IOException {
        if (stream != null) {
            stream.close();
            stream = null;
        }
    }

    private void close() {
        try {
            closeStream();
        } catch (IOException ignored) {
            // 保存已经完成或已经失败，这里再抛只会盖掉真正的异常
        }
        try {
            doc.close();
        } catch (IOException ignored) {
            // 同上
        }
    }

    /** 生成过程中两个字体都画不出来的字符数，调用方可以据此报告。 */
    public int droppedCharCount() {
        return font.droppedCodePoints().size();
    }
}
