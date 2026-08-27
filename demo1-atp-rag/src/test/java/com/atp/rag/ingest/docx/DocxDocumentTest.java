package com.atp.rag.ingest.docx;

import com.atp.rag.ingest.DocSection;
import com.atp.rag.ingest.MarkdownDocument;
import org.junit.jupiter.api.Assumptions;
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
 * DOCX 解析链路的验收，与 {@code PdfDocumentTest} 同一套判据：
 * <b>同一份内容，docx 版和 md 版必须切出一样的层级</b>。
 */
class DocxDocumentTest {

    private static final Path CORPUS = Paths.get("corpus");
    private static final String SAMPLE = "manual/05-等待策略";

    @Test
    @DisplayName("DOCX 的标题路径与 md 完全一致")
    void headingPathsMatchMarkdown() throws Exception {
        Path docx = CORPUS.resolve("docs-docx").resolve(SAMPLE + ".docx");
        Path md = CORPUS.resolve("docs").resolve(SAMPLE + ".md");
        Assumptions.assumeTrue(Files.exists(docx), "语料未生成，先跑 --atp.task=gen-corpus");

        MarkdownDocument mdDoc = MarkdownDocument.parse(md, SAMPLE + ".md");
        DocxDocument docxDoc = DocxDocument.parse(docx, SAMPLE + ".docx");

        Set<String> mdPaths = pathsOf(mdDoc.sections());
        Set<String> docxPaths = pathsOf(docxDoc.sections());

        System.out.println("=== md   " + mdDoc.sections().size() + " 节");
        System.out.println("=== docx " + docxDoc.sections().size() + " 节");

        List<String> onlyInMd = new ArrayList<String>(mdPaths);
        onlyInMd.removeAll(docxPaths);
        List<String> onlyInDocx = new ArrayList<String>(docxPaths);
        onlyInDocx.removeAll(mdPaths);

        if (!onlyInMd.isEmpty()) {
            System.out.println("只在 md 里有：" + onlyInMd);
        }
        if (!onlyInDocx.isEmpty()) {
            System.out.println("只在 docx 里有：" + onlyInDocx);
        }
        assertTrue(onlyInMd.isEmpty() && onlyInDocx.isEmpty(),
                "标题路径不一致 —— 只在 md：" + onlyInMd + "；只在 docx：" + onlyInDocx);
    }

    @Test
    @DisplayName("表格被还原成 markdown 语法，表头和分隔行都在")
    void tablesComeBackAsMarkdown() throws Exception {
        Path docx = CORPUS.resolve("docs-docx").resolve(SAMPLE + ".docx");
        Assumptions.assumeTrue(Files.exists(docx), "语料未生成");

        DocxDocument doc = DocxDocument.parse(docx, SAMPLE + ".docx");

        // 05-等待策略 里有「两个层级的超时」那张表，含 tc_case.timeout_sec 字段
        String all = doc.fullText();
        assertTrue(all.contains("| tc_case.timeout_sec |") || all.contains("tc_case.timeout_sec"),
                "表格内容丢了");
        assertTrue(all.contains("|---|"), "markdown 表格分隔行没补上");
    }

    @Test
    @DisplayName("文档引言（Heading1 之后、第一个 Heading2 之前）没有被丢掉")
    void keepsDocumentIntro() throws Exception {
        Path docx = CORPUS.resolve("docs-docx").resolve("standards/STD-001-XPath編集規約.docx");
        Assumptions.assumeTrue(Files.exists(docx), "语料未生成");

        DocxDocument doc = DocxDocument.parse(docx, "standards/STD-001-XPath編集規約.docx");

        // 规范的「制定日／最終改訂／管理」这一段挂在空标题路径下 ——
        // 它是「这份规范谁维护、什么时候改的」的唯一答案来源
        boolean hasIntro = false;
        for (DocSection section : doc.sections()) {
            if (section.headingPath().isEmpty() && section.body().contains("制定日")) {
                hasIntro = true;
            }
        }
        assertTrue(hasIntro, "文档引言丢了 —— 规范的制定日/管理部门检索不到");
    }

    @Test
    @DisplayName("文档标题不重复出现在每条标题路径里")
    void documentTitleIsNotInEveryPath() throws Exception {
        Path docx = CORPUS.resolve("docs-docx").resolve(SAMPLE + ".docx");
        Assumptions.assumeTrue(Files.exists(docx), "语料未生成");

        DocxDocument doc = DocxDocument.parse(docx, SAMPLE + ".docx");
        assertTrue(doc.title().contains("等待策略"), "文档标题不对：" + doc.title());
        for (DocSection section : doc.sections()) {
            assertFalse(section.headingPath().contains(doc.title()),
                    "文档标题混进了标题路径：" + section.headingPath());
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
