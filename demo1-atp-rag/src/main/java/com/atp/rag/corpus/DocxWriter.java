package com.atp.rag.corpus;

import com.atp.rag.ingest.DocSection;
import com.atp.rag.ingest.MarkdownDocument;
import com.atp.rag.ingest.image.ImagePathResolver;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTDecimalNumber;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

/**
 * 把一篇 Markdown 写成 DOCX —— 多格式测试语料的另一半。
 *
 * <h3>层级信息存在哪</h3>
 *
 * DOCX 里标题不是靠字号，而是靠<b>段落样式</b>（{@code w:pStyle}，值形如
 * {@code Heading1}/{@code Heading2}）和<b>大纲级别</b>（{@code w:outlineLvl}）。
 * 这两样是结构化的、明确的，比 PDF 那套「靠字号和坐标猜标题」可靠得多。
 *
 * <p>所以解析 docx 时<b>不该走 Tika</b> —— Tika 把内容抽成一坨扁平文本，
 * 这些样式信息全丢了。POI 的 {@code XWPFParagraph.getStyle()} 能直接读到。
 * Spring AI 的 {@code TikaDocumentReader} 就是这么丢掉层级的。
 *
 * <h3>表格是真表格</h3>
 *
 * markdown 里的 {@code | a | b |} 会转成真正的 {@link XWPFTable} 而不是文本行。
 * 因为「表格被切碎、下半截没有表头」是 chunk 策略的一个真实风险，
 * 语料里必须有真表格才测得到它。
 */
public final class DocxWriter {

    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)]\\(([^)\\s]+)[^)]*\\)");

    /** markdown 表格的分隔行：{@code |---|:--:|---|} */
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\s*\\|[\\s:|-]+\\|\\s*$");

    private final XWPFDocument doc = new XWPFDocument();

    /** 图片路径的候选根目录，与 PdfWriter 用同一套规则。 */
    private final Path[] imageRoots;

    private DocxWriter(Path... imageRoots) {
        this.imageRoots = imageRoots;
    }

    public static void write(MarkdownDocument md, Path mdFile, Path target, Path docsRoot)
            throws IOException {
        DocxWriter writer = new DocxWriter(mdFile.getParent(), docsRoot);
        try {
            writer.render(md);
            Files.createDirectories(target.getParent());
            OutputStream out = Files.newOutputStream(target);
            try {
                writer.doc.write(out);
            } finally {
                out.close();
            }
        } finally {
            writer.doc.close();
        }
    }

    private void render(MarkdownDocument md) throws IOException {
        heading(md.title(), 1);

        List<String> previousPath = new ArrayList<String>();
        for (DocSection section : md.sections()) {
            List<String> path = section.headingPath();

            // 与 PdfWriter 完全相同的逻辑：只打印相对上一节新出现的那几级标题。
            // 两个生成器必须一致，否则同一篇 md 生成的 pdf 和 docx 层级会不一样，
            // 三格式对照就失去意义了
            int common = commonPrefixLength(previousPath, path);
            for (int depth = common; depth < path.size(); depth++) {
                // depth 0 是 markdown 的 ##，对应 docx 的 Heading2
                heading(path.get(depth), depth + 2);
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

    /**
     * 写一个标题段落。
     *
     * <p>两样东西都要设，缺一不可：
     * <ul>
     *   <li>{@code pStyle=HeadingN} —— 解析侧读的就是它</li>
     *   <li>{@code outlineLvl=N-1} —— Word 的导航窗格靠它建目录树，
     *       不设的话用 Word 打开看不到导航结构，语料就不像真的了</li>
     * </ul>
     *
     * @param level 1~9，与 Word 的 Heading1~9 对应
     */
    private void heading(String text, int level) {
        XWPFParagraph p = doc.createParagraph();
        p.setStyle("Heading" + level);

        // outlineLvl 从 0 开始：Heading1 → outlineLvl 0
        CTPPr ppr = p.getCTP().isSetPPr() ? p.getCTP().getPPr() : p.getCTP().addNewPPr();
        CTDecimalNumber lvl = ppr.isSetOutlineLvl() ? ppr.getOutlineLvl() : ppr.addNewOutlineLvl();
        lvl.setVal(BigInteger.valueOf(level - 1L));

        XWPFRun run = p.createRun();
        run.setText(text);
        run.setBold(true);
        // 字号也按层级递减 —— 不影响解析，但让文档看起来是正常的手册
        run.setFontSize(Math.max(11, 20 - level * 2));
    }

    /** 写一段正文，中途遇到表格和图片分流处理。 */
    private void body(String text) throws IOException {
        String[] lines = text.split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];

            // 表格：连续的 | 开头行算一张表，一次吃掉
            if (isTableRow(line)) {
                int end = i;
                while (end < lines.length && isTableRow(lines[end])) {
                    end++;
                }
                writeTable(lines, i, end);
                i = end;
                continue;
            }

            Matcher imageMatch = IMAGE.matcher(line);
            if (imageMatch.find()) {
                writeImage(imageMatch.group(2), imageMatch.group(1));
                i++;
                continue;
            }

            if (!line.trim().isEmpty()) {
                XWPFParagraph p = doc.createParagraph();
                XWPFRun run = p.createRun();
                run.setText(line);
                run.setFontSize(11);
            }
            i++;
        }
    }

    private static boolean isTableRow(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.length() > 1;
    }

    /**
     * 把 markdown 表格转成真正的 docx 表格。
     *
     * @param from 表格第一行（表头）在 {@code lines} 里的下标
     * @param to   表格结束的下一行下标
     */
    private void writeTable(String[] lines, int from, int to) {
        List<String[]> rows = new ArrayList<String[]>();
        for (int i = from; i < to; i++) {
            // |---|---| 这种分隔行在 docx 里没有对应物，跳过
            if (TABLE_SEPARATOR.matcher(lines[i]).matches()) {
                continue;
            }
            rows.add(splitCells(lines[i]));
        }
        if (rows.isEmpty()) {
            return;
        }

        int columns = 0;
        for (String[] row : rows) {
            columns = Math.max(columns, row.length);
        }

        XWPFTable table = doc.createTable(rows.size(), columns);
        for (int r = 0; r < rows.size(); r++) {
            XWPFTableRow row = table.getRow(r);
            String[] cells = rows.get(r);
            for (int c = 0; c < columns; c++) {
                row.getCell(c).setText(c < cells.length ? cells[c] : "");
            }
        }
    }

    /** 拆 {@code | a | b | c |} 成 {@code [a, b, c]}。 */
    private static String[] splitCells(String line) {
        String trimmed = line.trim();
        // 去掉首尾的 |，否则 split 会在两端各多出一个空串
        trimmed = trimmed.substring(1, trimmed.length() - 1);
        String[] cells = trimmed.split("\\|", -1);
        for (int i = 0; i < cells.length; i++) {
            cells[i] = cells[i].trim();
        }
        return cells;
    }

    /**
     * 把图片真的嵌进 docx。
     *
     * <p>和 PDF 那边同理：后面「从 docx 抽图转文字」需要它是真图。
     * POI 用 EMU 作单位（1 pt = 12700 EMU），{@link Units} 负责换算。
     */
    private void writeImage(String relativePath, String alt) throws IOException {
        Path imageFile = ImagePathResolver.resolve(relativePath, imageRoots);
        XWPFParagraph p = doc.createParagraph();
        XWPFRun run = p.createRun();

        if (imageFile == null) {
            run.setText("[图片缺失：" + relativePath + "]");
            return;
        }

        BufferedImage image = ImageIO.read(imageFile.toFile());
        // 版心宽度约 460pt（A4 减去左右各 1 英寸边距），超宽的等比缩小
        double scale = Math.min(1.0, 460.0 / image.getWidth());
        int width = (int) (image.getWidth() * scale);
        int height = (int) (image.getHeight() * scale);

        InputStream in = Files.newInputStream(imageFile);
        try {
            XWPFPicture picture = run.addPicture(in, pictureType(relativePath),
                    imageFile.getFileName().toString(),
                    Units.toEMU(width), Units.toEMU(height));

            // ⚠️ 把 alt 写进 docPr/@descr —— 这是 Word 的「替代文字」字段。
            //
            // 不写的话，解析侧只能拿到我们自己编的文件名（05-等待策略-img-1.png），
            // 榨出来是「05 等待策略 img 1」这种没有语义的词，
            // 于是没配 VLM 时图片信息等于全丢。
            //
            // PDF 那边没救（格式本身不存 alt），但 docx 存得下，就该存 ——
            // 真实的企业 docx 也确实会带替代文字（无障碍要求）
            if (alt != null && !alt.isEmpty()) {
                picture.getCTPicture().getNvPicPr().getCNvPr().setDescr(alt);
            }
        } catch (Exception e) {
            throw new IOException("嵌入图片失败：" + imageFile, e);
        } finally {
            in.close();
        }

        if (alt != null && !alt.isEmpty()) {
            XWPFParagraph caption = doc.createParagraph();
            XWPFRun captionRun = caption.createRun();
            captionRun.setText("图：" + alt);
            captionRun.setItalic(true);
            captionRun.setFontSize(10);
        }
    }

    private static int pictureType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return Document.PICTURE_TYPE_JPEG;
        }
        if (lower.endsWith(".gif")) {
            return Document.PICTURE_TYPE_GIF;
        }
        return Document.PICTURE_TYPE_PNG;
    }
}
