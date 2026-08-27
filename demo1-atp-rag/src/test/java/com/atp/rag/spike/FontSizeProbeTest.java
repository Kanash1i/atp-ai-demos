package com.atp.rag.spike;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * spike：<b>PDFBox 能不能拿到字号</b> —— 决定「无 outline 的 PDF」能不能纯 Java 降级。
 *
 * <p>PyMuPDF 一行 {@code page.get_text("dict")} 就给出每个 span 的
 * size / flags / font，用来按字号猜标题非常直接。问题是它是 Python 库、
 * 且是 AGPL。所以先确认一件事：<b>同样的信息 PDFBox 有没有？</b>
 *
 * <p>答案是有 —— {@link TextPosition#getFontSizeInPt()} 和
 * {@link TextPosition#getFont()}。只是 PDFBox 不做聚合，
 * 要自己覆盖 {@link PDFTextStripper#writeString} 收集。
 *
 * <p>这个 spike 的意义：如果 PDFBox 拿不到字号，「无 outline 降级」就只能靠外部工具，
 * 那才需要认真权衡引入 Python 的代价。拿得到的话，这个理由就不成立了。
 */
class FontSizeProbeTest {

    /** 一行文本的字号统计。PDF 里一行会被拆成多个 span（换字体就断开）。 */
    private static final class Line {

        final String text;
        final float size;
        final String font;

        Line(String text, float size, String font) {
            this.text = text;
            this.size = size;
            this.font = font;
        }
    }

    /**
     * 收集每一行的主导字号。
     *
     * <p>按行聚合而不是按 span —— 因为混合字体排版会把
     * 「为什么 CLICK 必须用 CLICKABLE」拆成三段（中文 / 拉丁 / 中文）。
     * 只看 span 的话一个标题会变成三个候选，判起来全是碎片。
     */
    private static final class SizeCollector extends PDFTextStripper {

        final List<Line> lines = new ArrayList<Line>();

        SizeCollector() throws IOException {
            setSortByPosition(true);
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) throws IOException {
            if (text.trim().isEmpty() || positions.isEmpty()) {
                super.writeString(text, positions);
                return;
            }
            // 取这一行里出现字符数最多的那个字号当主导字号
            Map<Float, Integer> weight = new LinkedHashMap<Float, Integer>();
            String font = null;
            for (TextPosition p : positions) {
                float size = Math.round(p.getFontSizeInPt() * 10) / 10f;
                Integer n = weight.get(size);
                weight.put(size, n == null ? 1 : n + 1);
                if (font == null && p.getFont() != null) {
                    font = p.getFont().getName();
                }
            }
            float dominant = 0;
            int best = -1;
            for (Map.Entry<Float, Integer> e : weight.entrySet()) {
                if (e.getValue() > best) {
                    best = e.getValue();
                    dominant = e.getKey();
                }
            }
            lines.add(new Line(text.trim(), dominant, font));
            super.writeString(text, positions);
        }
    }

    @Test
    @DisplayName("PDFBox 拿得到字号 —— 无 outline 降级不必依赖外部工具")
    void pdfBoxExposesFontSize() throws Exception {
        Path pdf = Paths.get("corpus/docs-pdf/manual/05-等待策略.pdf");
        Assumptions.assumeTrue(Files.exists(pdf), "语料未生成，先跑 --atp.task=gen-corpus");

        PDDocument doc = PDDocument.load(pdf.toFile());
        SizeCollector collector = new SizeCollector();
        try {
            collector.getText(doc);
        } finally {
            doc.close();
        }

        // 正文字号 = 出现字符数最多的那个
        Map<Float, Integer> chars = new LinkedHashMap<Float, Integer>();
        for (Line line : collector.lines) {
            Integer n = chars.get(line.size);
            chars.put(line.size, (n == null ? 0 : n) + line.text.length());
        }
        float body = 0;
        int best = -1;
        for (Map.Entry<Float, Integer> e : chars.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                body = e.getKey();
            }
        }

        System.out.println("[pdfbox] 字号分布（按字符数）= " + chars);
        System.out.println("[pdfbox] 正文字号 = " + body);
        System.out.println("[pdfbox] 判为标题的行（size > " + body + "）：");
        int headings = 0;
        for (Line line : collector.lines) {
            if (line.size > body) {
                headings++;
                System.out.println("    size=" + line.size + "  " + line.font + "  "
                        + line.text.substring(0, Math.min(40, line.text.length())));
            }
        }

        assertTrue(chars.size() >= 3, "只看到 " + chars.size() + " 种字号，分不出层级");
        assertTrue(headings >= 5, "只判出 " + headings + " 个标题，启发式没有可用信号");
    }
}
