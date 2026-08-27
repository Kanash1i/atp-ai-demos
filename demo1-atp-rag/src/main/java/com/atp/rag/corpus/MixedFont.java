package com.atp.rag.corpus;

import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 中日文 + ASCII 混排的字体切换器。
 *
 * <h3>为什么需要它</h3>
 *
 * 生成 PDF 语料时踩到的两个坑（spike 实测，见 DECISIONS.md D-022）：
 *
 * <ol>
 *   <li>PDFBox 内置的 14 种标准字体全是 Latin-1，写中文直接抛
 *       {@code IllegalArgumentException}，必须外挂字体文件</li>
 *   <li>但系统上唯一能被 PDFBox 2.x 嵌入的 CJK 字体是 {@code DroidSansFallbackFull.ttf}，
 *       而它是 <b>fallback 字体 —— 只有 CJK 字形，连大写 C 都没有</b>：
 *       <pre>No glyph for U+0043 (C) in font DroidSansFallback</pre>
 *       偏偏语料里满是 {@code CLICK} / {@code CLICKABLE} / {@code data-testid}</li>
 * </ol>
 *
 * <p>所以一个字体不够用，得两个配合：CJK 走外挂字体，拉丁走内置 Helvetica。
 *
 * <h3>为什么按 encode 探测而不是按 Unicode 范围猜</h3>
 *
 * 「U+4E00 以上算 CJK」这种范围判断在真实语料上会碎：全角标点（{@code ：。「」}）、
 * 破折号 {@code —}、各种符号散落在十几个 Unicode 区段里，列不全。
 * 而唯一权威的判据是<b>这个字体到底有没有这个字形</b> —— 那就直接 encode 一次问它。
 * 结果缓存起来，字符种类有限，开销可以忽略。
 */
public final class MixedFont {

    /** 首选字体，通常是外挂的 CJK TrueType。 */
    private final PDFont primary;

    /** 兜底字体，通常是 PDFBox 内置的 Helvetica，覆盖 ASCII 与 Latin-1。 */
    private final PDFont fallback;

    /** 每个 code point 该用哪个字体的缓存。null 值表示两个字体都没有这个字形。 */
    private final Map<Integer, PDFont> resolved = new HashMap<Integer, PDFont>();

    /** 两个字体都画不出来的字符，去重后留着最后统一报告。 */
    private final Set<Integer> dropped = new HashSet<Integer>();

    public MixedFont(PDFont primary, PDFont fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    /** 一段用同一个字体画的文本。 */
    public static final class Run {

        public final PDFont font;
        public final String text;

        Run(PDFont font, String text) {
            this.font = font;
            this.text = text;
        }
    }

    /**
     * 把一行文本切成若干段，每段用一个字体。
     *
     * <p>两个字体都没有的字符<b>直接丢弃</b>（比如语料里的 ⚠️ ✅ ❌）。
     * 丢弃而不是报错，是因为这些符号在真实的企业手册 PDF 里本来就不该出现，
     * 而它们的缺失不影响检索 —— 文本层留下的仍是可搜索的 Unicode 正文。
     */
    public List<Run> split(String text) {
        List<Run> runs = new ArrayList<Run>();
        StringBuilder buffer = new StringBuilder();
        PDFont currentFont = null;

        int i = 0;
        while (i < text.length()) {
            int codePoint = text.codePointAt(i);
            int charCount = Character.charCount(codePoint);
            String ch = text.substring(i, i + charCount);
            i += charCount;

            PDFont font = fontFor(codePoint, ch);
            if (font == null) {
                dropped.add(codePoint);     // 两个字体都画不出来，跳过这个字符
                continue;
            }

            // 字体变了就结算上一段
            if (font != currentFont) {
                if (buffer.length() > 0) {
                    runs.add(new Run(currentFont, buffer.toString()));
                    buffer.setLength(0);
                }
                currentFont = font;
            }
            buffer.append(ch);
        }

        if (buffer.length() > 0) {
            runs.add(new Run(currentFont, buffer.toString()));
        }
        return runs;
    }

    /**
     * 这个字符该用哪个字体 —— 靠真的 encode 一次来判断，不靠 Unicode 范围猜。
     *
     * @return 能画出它的字体；两个都不行时返回 null
     */
    private PDFont fontFor(int codePoint, String ch) {
        if (resolved.containsKey(codePoint)) {
            return resolved.get(codePoint);
        }
        PDFont chosen = null;
        if (canEncode(primary, ch)) {
            chosen = primary;
        } else if (fallback != null && canEncode(fallback, ch)) {
            chosen = fallback;
        }
        resolved.put(codePoint, chosen);
        return chosen;
    }

    private static boolean canEncode(PDFont font, String ch) {
        try {
            font.encode(ch);
            return true;
        } catch (IOException e) {
            return false;           // 字体没有这个字形
        } catch (IllegalArgumentException e) {
            return false;           // PDFBox 对缺字形抛的就是这个
        }
    }

    /** 计算一段文本按混合字体排出来的宽度（单位 pt），用于换行。 */
    public float widthOf(String text, float fontSize) throws IOException {
        float total = 0;
        for (Run run : split(text)) {
            total += run.font.getStringWidth(run.text) / 1000 * fontSize;
        }
        return total;
    }

    /**
     * 在当前位置画一行文本，中途按需切换字体。
     *
     * <p>调用方负责 {@code beginText()} / {@code newLineAtOffset()} / {@code endText()} ——
     * 这里只负责「同一行里换字体」这件事。
     */
    public void showText(PDPageContentStream cs, String text, float fontSize) throws IOException {
        for (Run run : split(text)) {
            cs.setFont(run.font, fontSize);
            cs.showText(run.text);
        }
    }

    /** 被丢弃的字符（两个字体都没有），生成结束后打一次报告用。 */
    public Set<Integer> droppedCodePoints() {
        return dropped;
    }
}
