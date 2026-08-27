package com.atp.rag.ingest.image;

import com.atp.rag.ingest.DocSection;
import com.atp.rag.ingest.docx.DocxDocument;
import com.atp.rag.ingest.pdf.PdfDocument;
import com.atp.rag.storage.LocalFileStorage;
import com.atp.rag.storage.ObjectStorage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PDF / DOCX 内嵌图片链路的验收：<b>抽出 → 转文字 → 原图留地址</b>。
 *
 * <p>用 {@link AltTextImageDescriber} 而不是 VLM —— 这条链路的正确性判据是
 * 「图有没有被抽出来、有没有归到对的小节、原图地址有没有留下」，
 * 跟描述质量无关。用降级实现测，就不需要显卡也不会因为模型输出波动而 flaky。
 *
 * <p>语料里那张图在 {@code 05-等待策略.md} 的 {@code CLICKABLE} 一节：
 * <pre>
 * ### CLICKABLE
 * …
 * ![案例编辑页里 CLICK 步骤的 wait_strategy 字段…](img/manual/case-edit-wait-strategy.png)
 * </pre>
 * 所以「归属正确」的判据很明确：描述必须落在 CLICKABLE 那一节，不能跑到别处。
 */
class EmbeddedImageChainTest {

    private static final Path CORPUS = Paths.get("corpus");
    private static final String SAMPLE = "manual/05-等待策略";

    /** 图片所在小节的最深一级标题 —— 归属正确性的判据。 */
    private static final String EXPECTED_SECTION = "CLICKABLE";

    @Test
    @DisplayName("PDF：内嵌图片被抽出、转描述、归到正确小节，原图落盘")
    void pdfImageChain(@TempDir Path tmp) throws Exception {
        Path pdf = CORPUS.resolve("docs-pdf").resolve(SAMPLE + ".pdf");
        Assumptions.assumeTrue(Files.exists(pdf), "语料未生成，先跑 --atp.task=gen-corpus");

        ObjectStorage storage = new LocalFileStorage(tmp.toString(), "http://localhost:8000/");
        PdfDocument doc = PdfDocument.parse(pdf, SAMPLE + ".pdf",
                new AltTextImageDescriber(), storage);

        List<String> allUrls = new ArrayList<String>();
        DocSection withImage = null;
        for (DocSection section : doc.sections()) {
            allUrls.addAll(section.imageUrls());
            if (section.hasImages()) {
                withImage = section;
            }
        }

        assertFalse(allUrls.isEmpty(), "PDF 里的图一张都没抽出来");
        System.out.println("[pdf] 原图地址 = " + allUrls);
        System.out.println("[pdf] 所在小节 = " + withImage.headingPath());

        // 归属正确性
        assertEquals(EXPECTED_SECTION,
                withImage.headingPath().get(withImage.headingPath().size() - 1),
                "图片归到了错误的小节：" + withImage.headingPath());

        // URL 是 URL 而不是本地路径 —— 换成 OSS 时 payload 结构才不用变
        for (String url : allUrls) {
            assertTrue(url.startsWith("http://localhost:8000/"), "不是 URL：" + url);
        }

        // 原图真的落盘了，且不是空文件
        Path stored = tmp.resolve(allUrls.get(0).replace("http://localhost:8000/", ""));
        assertTrue(Files.exists(stored), "原图没落盘：" + stored);
        assertTrue(Files.size(stored) > 0, "原图是空文件");
    }

    @Test
    @DisplayName("DOCX：内嵌图片被抽出、转描述、归到正确小节，原图落盘")
    void docxImageChain(@TempDir Path tmp) throws Exception {
        Path docx = CORPUS.resolve("docs-docx").resolve(SAMPLE + ".docx");
        Assumptions.assumeTrue(Files.exists(docx), "语料未生成");

        ObjectStorage storage = new LocalFileStorage(tmp.toString(), "http://localhost:8000/");
        DocxDocument doc = DocxDocument.parse(docx, SAMPLE + ".docx",
                new AltTextImageDescriber(), storage);

        DocSection withImage = null;
        for (DocSection section : doc.sections()) {
            if (section.hasImages()) {
                withImage = section;
            }
        }

        assertTrue(withImage != null, "DOCX 里的图一张都没抽出来");
        System.out.println("[docx] 原图地址 = " + withImage.imageUrls());
        System.out.println("[docx] 所在小节 = " + withImage.headingPath());

        assertEquals(EXPECTED_SECTION,
                withImage.headingPath().get(withImage.headingPath().size() - 1),
                "图片归到了错误的小节：" + withImage.headingPath());

        Path stored = tmp.resolve(
                withImage.imageUrls().get(0).replace("http://localhost:8000/", ""));
        assertTrue(Files.exists(stored) && Files.size(stored) > 0, "原图没落盘：" + stored);
    }

    @Test
    @DisplayName("存储 key 稳定：重复入库不会堆副本")
    void storageKeysAreStable(@TempDir Path tmp) throws Exception {
        Path pdf = CORPUS.resolve("docs-pdf").resolve(SAMPLE + ".pdf");
        Assumptions.assumeTrue(Files.exists(pdf), "语料未生成");

        ObjectStorage storage = new LocalFileStorage(tmp.toString(), "http://x/");

        // 同一份 PDF 解析两遍 —— 消融实验会反复重跑，key 不稳定的话
        // 存储里会堆出成倍的副本
        List<String> first = urlsOf(PdfDocument.parse(pdf, SAMPLE + ".pdf",
                new AltTextImageDescriber(), storage));
        List<String> second = urlsOf(PdfDocument.parse(pdf, SAMPLE + ".pdf",
                new AltTextImageDescriber(), storage));

        assertEquals(first, second, "两次解析得到的原图地址不一致 —— key 不稳定");

        long fileCount = Files.walk(tmp).filter(Files::isRegularFile).count();
        assertEquals(first.size(), fileCount,
                "落盘文件数比图片数多，说明产生了副本");
    }

    @Test
    @DisplayName("不传 describer 时完全不碰图片 —— 纯文本解析仍然可用")
    void imagesAreOptional() throws Exception {
        Path pdf = CORPUS.resolve("docs-pdf").resolve(SAMPLE + ".pdf");
        Assumptions.assumeTrue(Files.exists(pdf), "语料未生成");

        PdfDocument doc = PdfDocument.parse(pdf, SAMPLE + ".pdf");
        for (DocSection section : doc.sections()) {
            assertFalse(section.hasImages(), "没传 describer 却处理了图片");
        }
    }

    private static List<String> urlsOf(PdfDocument doc) {
        List<String> urls = new ArrayList<String>();
        for (DocSection section : doc.sections()) {
            urls.addAll(section.imageUrls());
        }
        return urls;
    }
}
