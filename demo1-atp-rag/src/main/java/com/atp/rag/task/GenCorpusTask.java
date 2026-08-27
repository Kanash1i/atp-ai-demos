package com.atp.rag.task;

import com.atp.rag.config.AtpProperties;
import com.atp.rag.corpus.DocxWriter;
import com.atp.rag.corpus.PdfWriter;
import com.atp.rag.ingest.MarkdownDocument;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FilenameFilter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 把 markdown 语料转成 PDF 和 DOCX —— 造多格式测试语料。
 *
 * <h3>为什么同一份内容要有三种格式</h3>
 *
 * 企业里手册和规范是 PDF / DOCX，markdown 只有开发者看。链路必须能吃前两种。
 * 但「能吃」不等于「吃得一样好」—— PDF 抽出来的文本会不会串行、
 * 标题层级还认不认得出来、表格会不会塌掉，这些只有对照才看得出来。
 *
 * <p>所以刻意保持<b>同一份内容</b>：三种格式的 {@code sourceId} 前缀相同、
 * 小节标题相同，因此评估集的 {@code golden_ids} 三边通用。
 * 消融表里就能加一组「同样的问题、同样的策略，只换语料格式」的对照 ——
 * 这才是能说明问题的数字。
 *
 * <pre>
 * mvn spring-boot:run -Dspring-boot.run.arguments=--atp.task=gen-corpus
 * </pre>
 */
@Component
@ConditionalOnProperty(name = "atp.task", havingValue = "gen-corpus")
public class GenCorpusTask implements ApplicationRunner {

    private final AtpProperties props;

    public GenCorpusTask(AtpProperties props) {
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Path corpusRoot = Paths.get(props.getCorpus().getDir());
        Path docsRoot = corpusRoot.resolve("docs");

        // 输出到平级目录，不覆盖 md —— md 仍然是「源」，
        // 改内容只改 md，两种格式重新生成即可
        Path pdfRoot = corpusRoot.resolve("docs-pdf");
        Path docxRoot = corpusRoot.resolve("docs-docx");

        List<String> failures = new ArrayList<String>();
        int pdfCount = 0;
        int docxCount = 0;
        int droppedTotal = 0;

        // 与 CorpusIngestor 一样按 group 遍历，保证 sourceId 的构成方式一致
        for (String group : Arrays.asList("manual", "standards")) {
            Path dir = docsRoot.resolve(group);
            for (File file : listMarkdown(dir)) {
                String sourceId = group + "/" + file.getName();
                String baseName = stripExtension(file.getName());

                MarkdownDocument md = MarkdownDocument.parse(file.toPath(), sourceId);

                try {
                    Path pdf = pdfRoot.resolve(group).resolve(baseName + ".pdf");
                    int dropped = PdfWriter.write(md, file.toPath(), pdf, docsRoot);
                    droppedTotal += dropped;
                    pdfCount++;
                    System.out.println("  ✅ " + pdf + "  (" + Files.size(pdf) / 1024 + " KB"
                            + (dropped > 0 ? "，丢弃 " + dropped + " 种字符" : "") + ")");
                } catch (Exception e) {
                    System.out.println("  ❌ PDF " + sourceId + " —— " + e.getMessage());
                    failures.add("PDF " + sourceId + ": " + e.getMessage());
                }

                try {
                    Path docx = docxRoot.resolve(group).resolve(baseName + ".docx");
                    DocxWriter.write(md, file.toPath(), docx, docsRoot);
                    docxCount++;
                    System.out.println("  ✅ " + docx + "  (" + Files.size(docx) / 1024 + " KB)");
                } catch (Exception e) {
                    System.out.println("  ❌ DOCX " + sourceId + " —— " + e.getMessage());
                    failures.add("DOCX " + sourceId + ": " + e.getMessage());
                }
            }
        }

        System.out.println();
        System.out.println("=== 生成完成：PDF " + pdfCount + " 篇，DOCX " + docxCount + " 篇 ===");
        if (droppedTotal > 0) {
            // 不是错误但必须说 —— 意味着 PDF 的文本层和 md 不是逐字一致的，
            // 三格式对照时如果发现召回差异，这是第一个要排除的原因
            System.out.println("⚠️  共有 " + droppedTotal
                    + " 处字符因字体缺字形被丢弃（通常是 emoji），PDF 文本层与 md 不完全一致");
        }
        if (!failures.isEmpty()) {
            System.out.println("=== 失败 " + failures.size() + " 项 ===");
            for (String f : failures) {
                System.out.println("  - " + f);
            }
            throw new IllegalStateException("语料生成未全部成功");
        }
    }

    private static List<File> listMarkdown(Path dir) {
        File[] files = dir.toFile().listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File d, String name) {
                return name.endsWith(".md");
            }
        });
        if (files == null) {
            throw new IllegalStateException("找不到语料目录 " + dir.toAbsolutePath());
        }
        Arrays.sort(files);
        return Arrays.asList(files);
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}
