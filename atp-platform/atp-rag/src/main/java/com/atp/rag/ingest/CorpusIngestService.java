package com.atp.rag.ingest;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 把 {@code seed/docs} 的规范与手册灌进向量库。
 *
 * <h3>⭐ 为什么自己切块，不用框架的 TextChunker</h3>
 *
 * AgentScope 的 {@code SplitStrategy} 只有 CHARACTER / PARAGRAPH / TOKEN / SEMANTIC，
 * 都是**按长度或段落**切。而我们的语料是规范文档，它的意义单位是<b>小节</b>：
 *
 * <pre>
 * ## STD-005 CLICK 的等待策略
 * CLICK アクションの wait_strategy は CLICKABLE を必須とする。
 * 要素が DOM に存在していても…（理由）
 * </pre>
 *
 * 按长度切会把「规则」和「理由」拆到两个块里 —— 检索命中理由那块时，
 * 答案里就没有 CLICKABLE 这个词；命中规则那块时，又解释不了为什么。
 * <b>按标题切，一条规则连同它的理由永远在同一个块里。</b>
 *
 * <h3>标题路径进正文</h3>
 *
 * 每块前面加 {@code [文档 > 章 > 节]} 前缀再去算向量。
 * 因为小节正文里往往不重复出现主语（「本規約では…」），
 * 单看那段话根本不知道它在讲 XPath 还是等待策略。前缀把这个上下文补回来。
 */
@Slf4j
@Service
public class CorpusIngestService {

    /** Markdown 标题 */
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");

    /** 太短的块没有检索价值，多半是标题下面的一句过渡语 */
    private static final int MIN_CHUNK_CHARS = 40;

    @Autowired
    private Knowledge knowledge;

    @Value("${atp.rag.corpus.dir}")
    private String corpusDir;

    /**
     * 全量导入。
     *
     * <p>⚠️ 每次都是**全量重建**语义（PgVectorStore 按 chunkId 去重）——
     * 语料是固定的一批文件，增量更新会让旧块残留：
     * 切块策略一改，旧块的边界就不对了，但它们还躺在库里照样被召回，
     * 表现为「某几个问题莫名其妙地答不对」，在检索里极难定位。
     */
    public int ingestAll() {
        Path root = Path.of(corpusDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("语料目录不存在：" + root);
        }

        List<Document> docs = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".md")).sorted().toList()) {
                docs.addAll(readMarkdown(root, file));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("扫描语料目录失败", e);
        }

        if (docs.isEmpty()) {
            log.warn("语料目录 {} 下没有 .md 文件", root);
            return 0;
        }

        // addDocuments 内部会调 embedding 逐批算向量，再写进 pgvector
        knowledge.addDocuments(docs).block();
        log.info("语料导入完成：{} 个切块", docs.size());
        return docs.size();
    }

    /** 按标题层级切块，每块带上标题路径前缀 */
    private List<Document> readMarkdown(Path root, Path file) throws IOException {
        String relative = root.relativize(file).toString().replace('\\', '/');
        String docTitle = file.getFileName().toString().replaceFirst("\\.md$", "");

        List<Document> out = new ArrayList<>();
        List<String> headingPath = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        String currentHeading = null;

        for (String line : Files.readAllLines(file)) {
            Matcher m = HEADING.matcher(line);
            if (m.matches()) {
                // 遇到新标题 → 把上一节收成一个块
                flush(out, relative, docTitle, headingPath, currentHeading, body);
                int level = m.group(1).length();
                // 维护标题路径：退到父级，再压入当前标题
                while (headingPath.size() >= level) {
                    headingPath.remove(headingPath.size() - 1);
                }
                currentHeading = m.group(2).trim();
                headingPath.add(currentHeading);
                body.setLength(0);
            } else {
                body.append(line).append('\n');
            }
        }
        flush(out, relative, docTitle, headingPath, currentHeading, body);
        return out;
    }

    private void flush(List<Document> out, String sourceId, String docTitle,
                       List<String> headingPath, String heading, StringBuilder body) {
        String text = body.toString().strip();
        if (text.length() < MIN_CHUNK_CHARS) {
            return;
        }
        String prefix = "[" + docTitle + (headingPath.isEmpty() ? "" : " > " + String.join(" > ", headingPath)) + "]";
        String embedText = prefix + "\n" + text;

        // ⭐ anchor 定位到「哪一节」而不是「第几块」——
        //    切块策略一改，块序号全变，而小节标题是稳定的。引用展示与评估集都靠它
        String anchor = sourceId + (heading == null ? "" : "#" + heading);

        out.add(new Document(DocumentMetadata.builder()
                .docId(sourceId)
                .chunkId(UUID.randomUUID().toString())
                .content(TextBlock.builder().text(embedText).build())
                .addPayload("source_id", sourceId)
                .addPayload("doc_title", docTitle)
                .addPayload("heading_path", String.join(" > ", headingPath))
                .addPayload("anchor", anchor)
                // ⚠️ 展示用的正文**不带前缀** —— 前缀是给向量看的，
                //    引用给人看时带着一串标题路径反而碍事
                .addPayload("display_text", text)
                .build()));
    }
}
