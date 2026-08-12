package com.atp.rag.corpus;

import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 找一个能被 PDFBox 2.x 嵌入的中日文字体。
 *
 * <h3>为什么是「探测」而不是「配置一个路径」</h3>
 *
 * 因为能不能用，光看路径和扩展名判断不出来。spike 连踩两个坑：
 *
 * <ul>
 *   <li>{@code NotoSansCJK-Regular.ttc} 看着最正统，实际是 <b>OpenType/CFF</b>，
 *       加载抛 {@code UnsupportedOperationException: OTF fonts do not have a glyf table}
 *       —— PDFBox 2.x 只能嵌 TrueType（glyf），CFF 嵌入要到 3.0，而 3.x 我们上不去</li>
 *   <li>换成 {@code DroidSansFallbackFull.ttf} 能加载了，但它是 fallback 字体，
 *       <b>只有 CJK 字形没有 ASCII</b>，画 {@code CLICK} 会抛
 *       {@code No glyph for U+0043} —— 这个由 {@link MixedFont} 配一个拉丁字体解决</li>
 * </ul>
 *
 * <p>结论：唯一可靠的判据是「真的 load 一次看它抛不抛」。
 * 所以这里逐个候选试，第一个成功的就用。
 *
 * <p>字体路径不写进 {@code .env} —— 它不是配置，是环境探测的结果。
 * 换台机器字体位置就变了，让代码自己找比让人填一个会过期的路径更靠谱。
 * 真要覆盖时可以传 {@code -Datp.corpus.font=/path/to/font.ttf}。
 */
public final class CjkFontLoader {

    /** 允许用系统属性强行指定，绕过探测 —— 换机器排查问题时用得上。 */
    private static final String OVERRIDE_PROPERTY = "atp.corpus.font";

    /**
     * 候选字体。顺序是「最可能被 PDFBox 2.x 嵌入」在前。
     *
     * <p>CFF 系（Noto CJK 的 otf/ttc）放最后：现在必然失败，
     * 但将来若换了 PDF 库就能自动用上，留着不碍事。
     */
    private static final String[] CANDIDATES = {
            // Linux —— 真 TrueType，有 glyf
            "/usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf",
            "/usr/share/fonts/truetype/arphic/uming.ttc",
            "/usr/share/fonts/truetype/vlgothic/VL-Gothic-Regular.ttf",
            "/usr/share/fonts/truetype/fonts-japanese-gothic.ttf",
            // Windows
            "C:/Windows/Fonts/msgothic.ttc",
            "C:/Windows/Fonts/simsun.ttc",
            "C:/Windows/Fonts/YuGothM.ttc",
            // macOS
            "/System/Library/Fonts/Hiragino Sans GB.ttc",
            "/Library/Fonts/Arial Unicode.ttf",
            // CFF 系，PDFBox 2.x 用不了，留作将来
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
    };

    private CjkFontLoader() {
    }

    /**
     * 加载一个可用的 CJK 字体。
     *
     * @return 加载成功的字体
     * @throws IllegalStateException 一个都找不到 —— 这时候不该静默降级成乱码 PDF，
     *                               那样生成出来的语料是坏的，后面所有评估数字都无意义
     */
    public static PDFont load(PDDocument doc) {
        List<String> attempts = new ArrayList<String>();

        String override = System.getProperty(OVERRIDE_PROPERTY);
        if (override != null && !override.isEmpty()) {
            PDFont font = tryPath(doc, Paths.get(override), attempts);
            if (font != null) {
                return font;
            }
            // 显式指定了却用不了，属于配置错误，直接失败而不是偷偷回退去探测 ——
            // 否则人会以为用的是自己指定的那个字体
            throw new IllegalStateException("-D" + OVERRIDE_PROPERTY + " 指定的字体不可用："
                    + String.join("; ", attempts));
        }

        for (String candidate : CANDIDATES) {
            PDFont font = tryPath(doc, Paths.get(candidate), attempts);
            if (font != null) {
                return font;
            }
        }

        throw new IllegalStateException(
                "找不到可嵌入的中日文字体（PDFBox 2.x 只支持 TrueType/glyf，不支持 OTF/CFF）。\n"
                        + "  装一个：sudo apt install fonts-droid-fallback\n"
                        + "  或指定：-D" + OVERRIDE_PROPERTY + "=/path/to/font.ttf\n"
                        + "  已尝试：\n    " + String.join("\n    ", attempts));
    }

    /** 试一个路径，成功返回字体，失败返回 null 并把原因记进 {@code attempts}。 */
    private static PDFont tryPath(PDDocument doc, Path path, List<String> attempts) {
        if (!Files.exists(path)) {
            return null;        // 不存在的候选不值得记，噪音太大
        }
        try {
            PDFont font = doLoad(doc, path);
            System.out.println("[corpus] 使用中日文字体：" + path);
            return font;
        } catch (Exception e) {
            attempts.add(path + " —— " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    private static PDFont doLoad(PDDocument doc, Path path) throws Exception {
        if (!path.toString().toLowerCase().endsWith(".ttc")) {
            InputStream in = Files.newInputStream(path);
            try {
                // embedSubset=true：只嵌真正用到的字形。
                // 整套 CJK 两万多字，全嵌进去每个 PDF 都是 20MB 起步
                return PDType0Font.load(doc, in, true);
            } finally {
                in.close();
            }
        }

        // .ttc 是字体集合，一个文件里塞了 JP/SC/TC/KR 好几套，得先取出一个
        TrueTypeCollection ttc = new TrueTypeCollection(path.toFile());
        final TrueTypeFont[] holder = new TrueTypeFont[1];
        ttc.processAllFonts(new TrueTypeCollection.TrueTypeFontProcessor() {
            @Override
            public void process(TrueTypeFont ttf) {
                if (holder[0] == null) {
                    holder[0] = ttf;
                }
            }
        });
        if (holder[0] == null) {
            throw new IllegalStateException("TTC 里没有字型");
        }
        return PDType0Font.load(doc, holder[0], true);
    }
}
