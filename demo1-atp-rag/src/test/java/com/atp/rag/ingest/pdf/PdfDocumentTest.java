package com.atp.rag.ingest.pdf;

import com.atp.rag.ingest.DocSection;
import com.atp.rag.ingest.MarkdownDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PDF 解析链路的验收：<b>同一份内容，md 版和 PDF 版必须切出一样的层级</b>。
 *
 * <p>这不是锦上添花的测试，而是整条多格式链路成立的判据。如果 PDF 抽出来的
 * 标题路径和 md 对不上，那么：
 * <ul>
 *   <li>{@code HEADING_PATH} 和 {@code PARENT_CHILD} 两个策略在 PDF 上就是坏的</li>
 *   <li>评估集的 {@code golden_ids}（{@code sourceId#小节标题}）三格式不通用，
 *       「只换格式」的单变量对照就做不成</li>
 * </ul>
 */
class PdfDocumentTest {

    private static final Path CORPUS = Paths.get("corpus");

    /** 三格式对照用的样本：含表格、图片、中日文混排，覆盖面最全。 */
    private static final String SAMPLE = "manual/05-等待策略";

    @Test
    @DisplayName("PDF 的标题路径与 md 完全一致（跨格式 anchor 可复用的前提）")
    void headingPathsMatchMarkdown() throws Exception {
        Path pdf = CORPUS.resolve("docs-pdf").resolve(SAMPLE + ".pdf");
        Path md = CORPUS.resolve("docs").resolve(SAMPLE + ".md");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(pdf),
                "语料未生成，先跑 --atp.task=gen-corpus");

        MarkdownDocument mdDoc = MarkdownDocument.parse(md, SAMPLE + ".md");
        PdfDocument pdfDoc = PdfDocument.parse(pdf, SAMPLE + ".pdf");

        Set<String> mdPaths = pathsOf(mdDoc.sections());
        Set<String> pdfPaths = pathsOf(pdfDoc.sections());

        System.out.println("=== md  " + mdDoc.sections().size() + " 节");
        System.out.println("=== pdf " + pdfDoc.sections().size() + " 节");

        List<String> onlyInMd = new ArrayList<String>(mdPaths);
        onlyInMd.removeAll(pdfPaths);
        List<String> onlyInPdf = new ArrayList<String>(pdfPaths);
        onlyInPdf.removeAll(mdPaths);

        if (!onlyInMd.isEmpty()) {
            System.out.println("只在 md 里有：" + onlyInMd);
        }
        if (!onlyInPdf.isEmpty()) {
            System.out.println("只在 pdf 里有：" + onlyInPdf);
        }

        assertTrue(onlyInMd.isEmpty() && onlyInPdf.isEmpty(),
                "标题路径不一致 —— 只在 md：" + onlyInMd + "；只在 pdf：" + onlyInPdf);
    }

    @Test
    @DisplayName("PDF 的文档标题取自 outline 顶层，不重复出现在每条标题路径里")
    void documentTitleIsNotInEveryPath() throws Exception {
        Path pdf = CORPUS.resolve("docs-pdf").resolve(SAMPLE + ".pdf");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(pdf), "语料未生成");

        PdfDocument doc = PdfDocument.parse(pdf, SAMPLE + ".pdf");

        assertTrue(doc.title().contains("等待策略"), "文档标题不对：" + doc.title());
        for (DocSection section : doc.sections()) {
            assertFalse(section.headingPath().contains(doc.title()),
                    "文档标题混进了标题路径：" + section.headingPath());
        }
    }

    @Test
    @DisplayName("正文按 Y 坐标切开，同一页的相邻小节不串内容")
    void sectionsDoNotBleedIntoEachOther() throws Exception {
        Path pdf = CORPUS.resolve("docs-pdf").resolve(SAMPLE + ".pdf");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(pdf), "语料未生成");

        PdfDocument doc = PdfDocument.parse(pdf, SAMPLE + ".pdf");

        // 「PRESENCE」这一节讲的是文件上传，不该混进「NONE」那一节的正文。
        // 两节在 md 里紧挨着，PDF 里多半落在同一页 —— 正是坐标切分要解决的场景
        for (DocSection section : doc.sections()) {
            String last = section.headingPath().isEmpty()
                    ? "" : section.headingPath().get(section.headingPath().size() - 1);
            if ("NONE".equals(last)) {
                assertFalse(section.body().contains("文件上传"),
                        "NONE 一节串进了 PRESENCE 的内容：" + section.body());
            }
        }
    }

    private static Set<String> pathsOf(List<DocSection> sections) {
        Set<String> paths = new LinkedHashSet<String>();
        for (DocSection section : sections) {
            paths.add(String.join(" > ", section.headingPath()));
        }
        return paths;
    }
}
