package com.atp.rag.spike;

import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import com.atp.rag.corpus.MixedFont;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.text.PDFTextStripperByArea;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Rectangle;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Java 8 + PDFBox 2.0.30 的多格式链路 spike。
 *
 * <p>照 CLAUDE.md 的规矩：写业务代码之前先证明链路能通。这里要证的是三件事 ——
 * 每一件失败都会让「PDF 按导航切块」整个方案作废：
 *
 * <ol>
 *   <li><b>CJK 字体能嵌进 PDF</b> —— 语料含中日文。PDFBox 内置的 14 种标准字体全是
 *       Latin-1，写中文会直接抛 {@code IllegalArgumentException}，必须外挂 TTF/TTC</li>
 *   <li><b>outline 书签能带 Y 坐标</b> —— Spring AI 那套算法的命脉是
 *       {@link PDPageXYZDestination#getTop()}。如果生成的书签只有页码没有坐标，
 *       同一页上的两个小节就分不开，切块粒度退化成「一页一块」</li>
 *   <li><b>能按矩形区域读回文本</b> —— 有了 Y 坐标还要能用它切，靠的是
 *       {@link PDFTextStripperByArea}</li>
 * </ol>
 *
 * <p>⚠️ PDF 坐标系原点在<b>左下角</b>，Y 向上增长；而 outline 的 top 和
 * {@link Rectangle} 用的是<b>左上角</b>原点、Y 向下。这套换算是本 spike 最容易错的地方，
 * 所以下面第 3 个用例专门验证「按坐标切出来的确实是预期那一段」。
 */
class PdfSpikeTest {

    /** 语料里会出现的三种文字，全塞进去测字体覆盖。 */
    private static final String CJK = "等待策略：CLICK 步骤必须用 CLICKABLE。待機戦略の規約。";

    /**
     * 候选 CJK 字体。<b>顺序不是偏好，是能力排序</b> —— 见下面 {@link #loadFont}。
     *
     * <p>⚠️ spike 踩到的坑：{@code NotoSansCJK-Regular.ttc} 加载会抛
     * {@code UnsupportedOperationException: OTF fonts do not have a glyf table}。
     * 因为它是 <b>OpenType/CFF</b>（PostScript 轮廓），而 <b>PDFBox 2.x 只能嵌入
     * TrueType（glyf）字体</b> —— OTF/CFF 嵌入是 PDFBox 3.0 才支持的，
     * 而 3.x 我们上不去（API 全变，且本项目锁 2.0.30）。
     *
     * <p>所以不能按「哪个字体好看」挑，得按「哪个能嵌进去」挑。
     */
    private static final String[] FONT_CANDIDATES = {
            // 真 TrueType，有 glyf 表，覆盖 CJK 统一汉字 + 假名
            "/usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf",
            "/usr/share/fonts/truetype/arphic/uming.ttc",
            "C:/Windows/Fonts/msgothic.ttc",
            "C:/Windows/Fonts/simsun.ttc",
            "/System/Library/Fonts/ヒラギノ角ゴシック W3.ttc",
            // CFF 的放最后：多半会失败，但万一将来换了 PDFBox 版本就能用上
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"
    };

    /**
     * 按<b>能力</b>探测字体：逐个候选真的去 load 一次，第一个成功的就用它。
     *
     * <p>不靠文件名或扩展名判断 —— {@code .ttc} 里可能是 TrueType 也可能是 CFF，
     * 光看路径分不出来。唯一可靠的判据是「PDFBox 能不能把它嵌进去」，
     * 那就直接试。失败的候选静默跳过。
     *
     * @return 加载成功的字体，全部候选都失败时返回 null（调用方跳过测试）
     */
    private static PDType0Font loadFont(PDDocument doc) {
        for (String candidate : FONT_CANDIDATES) {
            Path path = Paths.get(candidate);
            if (!Files.exists(path)) {
                continue;
            }
            try {
                return tryLoad(doc, path);
            } catch (Exception e) {
                // 典型就是上面说的 "OTF fonts do not have a glyf table"。
                // 打出来但不失败 —— 换台机器字体不一样是常态
                System.out.println("[spike] 字体不可用，跳过 " + path.getFileName()
                        + " —— " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        return null;
    }

    /** {@code .ttc} 是字体集合（一个文件塞了 JP/SC/TC/KR 好几套），得先取出其中一个。 */
    private static PDType0Font tryLoad(PDDocument doc, Path fontPath) throws Exception {
        if (!fontPath.toString().endsWith(".ttc")) {
            // embedSubset=true：只嵌用到的字形。整套 CJK 两万多字，
            // 全嵌进去每个 PDF 都是 20MB 起步
            return PDType0Font.load(doc, Files.newInputStream(fontPath), true);
        }
        TrueTypeCollection ttc = new TrueTypeCollection(fontPath.toFile());
        final TrueTypeFont[] holder = new TrueTypeFont[1];
        ttc.processAllFonts(new TrueTypeCollection.TrueTypeFontProcessor() {
            @Override
            public void process(TrueTypeFont ttf) {
                if (holder[0] == null) {
                    holder[0] = ttf;
                }
            }
        });
        assertNotNull(holder[0], "TTC 里一个字型都没有");
        return PDType0Font.load(doc, holder[0], true);
    }

    @Test
    @DisplayName("spike 1+2：CJK 能写进 PDF，且 outline 书签带得上 Y 坐标")
    void writesCjkWithOutline(@TempDir Path tmp) throws Exception {
        Path pdf = tmp.resolve("spike.pdf");
        PDDocument doc = new PDDocument();
        try {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDType0Font font = loadFont(doc);
            org.junit.jupiter.api.Assumptions.assumeTrue(font != null, "系统无可嵌入的 CJK 字体，跳过");

            // 页高 842pt（A4）。两段文字分别放在 y=780 和 y=600 —— 同一页上的两节，
            // 正是「只有页码切不开、必须靠 Y 坐标」的场景
            // ⚠️ 不能直接 cs.showText —— Droid Sans Fallback 只有 CJK 字形，
            // 遇到 CLICK 里的 'C' 会抛 "No glyph for U+0043"。
            // MixedFont 按字符探测，CJK 走外挂字体、拉丁走内置 Helvetica
            MixedFont mixed = new MixedFont(font, PDType1Font.HELVETICA);

            PDPageContentStream cs = new PDPageContentStream(doc, page);
            cs.beginText();
            cs.newLineAtOffset(50, 780);
            mixed.showText(cs, "第一节 " + CJK, 14);
            cs.endText();

            cs.beginText();
            cs.newLineAtOffset(50, 600);
            mixed.showText(cs, "第二节 これはテストです。", 14);
            cs.endText();
            cs.close();

            System.out.println("[spike] 两个字体都缺的字符数 = "
                    + mixed.droppedCodePoints().size());

            // 建 outline：两个书签，各自指向自己那一段的 Y 坐标
            PDDocumentOutline outline = new PDDocumentOutline();
            doc.getDocumentCatalog().setDocumentOutline(outline);
            outline.addLast(bookmark(page, "第一节", 800));
            outline.addLast(bookmark(page, "第二节", 620));

            doc.save(pdf.toFile());
        } finally {
            doc.close();
        }

        assertTrue(Files.size(pdf) > 0, "PDF 是空的");

        // 读回来验证
        PDDocument read = PDDocument.load(pdf.toFile());
        try {
            PDDocumentOutline outline = read.getDocumentCatalog().getDocumentOutline();
            assertNotNull(outline, "outline 丢了 —— 按导航切块的前提不成立");

            PDOutlineItem first = outline.getFirstChild();
            assertEquals("第一节", first.getTitle());

            // 这一步是整个方案的命脉：destination 必须是 XYZ 类型且 top 拿得到
            assertTrue(first.getDestination() instanceof PDPageXYZDestination,
                    "destination 不是 XYZ 类型，拿不到 Y 坐标");
            assertEquals(800, ((PDPageXYZDestination) first.getDestination()).getTop());
            assertEquals(620, ((PDPageXYZDestination) first.getNextSibling()
                    .getDestination()).getTop());
        } finally {
            read.close();
        }
    }

    @Test
    @DisplayName("spike 3：按 Y 坐标矩形抽文本，能把同一页上的两节分开")
    void extractsByRegion(@TempDir Path tmp) throws Exception {
        Path pdf = tmp.resolve("region.pdf");
        PDDocument doc = new PDDocument();
        try {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDType0Font font = loadFont(doc);
            org.junit.jupiter.api.Assumptions.assumeTrue(font != null, "系统无可嵌入的 CJK 字体，跳过");
            MixedFont mixed = new MixedFont(font, PDType1Font.HELVETICA);
            PDPageContentStream cs = new PDPageContentStream(doc, page);
            cs.beginText();
            cs.newLineAtOffset(50, 780);
            mixed.showText(cs, "上半部分的内容", 14);
            cs.endText();
            cs.beginText();
            cs.newLineAtOffset(50, 300);
            mixed.showText(cs, "下半部分的内容", 14);
            cs.endText();
            cs.close();
            doc.save(pdf.toFile());
        } finally {
            doc.close();
        }

        PDDocument read = PDDocument.load(pdf.toFile());
        try {
            PDPage page = read.getPage(0);
            float pageHeight = page.getMediaBox().getHeight();     // 842

            PDFTextStripperByArea stripper = new PDFTextStripperByArea();
            stripper.setSortByPosition(true);

            // ⚠️ 坐标换算：文字画在 PDF 坐标 y=780（从下往上量），
            // 换成 Rectangle 的左上角坐标就是 842-780=62。
            // 取 y=0..200 这个带子，只该框住上半部分
            stripper.addRegion("top", new Rectangle(0, 0, (int) page.getMediaBox().getWidth(), 200));
            // 下半部分在 PDF y=300 → Rectangle y=542，取 500..842 这个带子
            stripper.addRegion("bottom", new Rectangle(0, 500,
                    (int) page.getMediaBox().getWidth(), (int) pageHeight - 500));
            stripper.extractRegions(page);

            String top = stripper.getTextForRegion("top").trim();
            String bottom = stripper.getTextForRegion("bottom").trim();

            System.out.println("[spike] top   = " + top);
            System.out.println("[spike] bottom= " + bottom);

            assertTrue(top.contains("上半部分"), "上半区域没抽到上半内容，实际=" + top);
            assertTrue(!top.contains("下半部分"), "上半区域串进了下半内容，实际=" + top);
            assertTrue(bottom.contains("下半部分"), "下半区域没抽到下半内容，实际=" + bottom);
        } finally {
            read.close();
        }
    }

    /** 建一个指向 {@code page} 上 {@code top} 高度的书签。 */
    private static PDOutlineItem bookmark(PDPage page, String title, int top) {
        PDPageXYZDestination dest = new PDPageXYZDestination();
        dest.setPage(page);
        dest.setTop(top);          // 就是 Spring AI 拿来当切分位置的那个值
        dest.setLeft(0);
        PDOutlineItem item = new PDOutlineItem();
        item.setTitle(title);
        item.setDestination(dest);
        return item;
    }
}
