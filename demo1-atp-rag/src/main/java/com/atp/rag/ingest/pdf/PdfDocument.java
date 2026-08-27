package com.atp.rag.ingest.pdf;

import com.atp.rag.ingest.DocSection;
import com.atp.rag.ingest.ParsedDocument;
import com.atp.rag.ingest.image.ExtractedImage;
import com.atp.rag.ingest.image.ImageDescriber;
import com.atp.rag.storage.ObjectStorage;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.PDFTextStripperByArea;

import java.awt.Rectangle;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 解析 PDF：按 outline 书签切成带标题层级的小节。
 *
 * <h3>切分粒度靠什么决定</h3>
 *
 * 靠书签的<b>页码 + 页内 Y 坐标</b>。两个相邻书签之间的矩形区域，就是前一个书签的正文。
 * 只用页码是不够的 —— 手册里一页放三四个小节是常态，那样它们会粘成一块。
 *
 * <h3>⚠️ 坐标系换算：移植时最容易错的地方</h3>
 *
 * 两套坐标系原点不在同一个角：
 *
 * <pre>
 *   书签的 top（PDPageXYZDestination）  原点在**左下角**，Y 向上增长
 *   PDFTextStripperByArea 的 Rectangle  原点在**左上角**，Y 向下增长
 * </pre>
 *
 * 所以 {@code Rectangle.y = pageHeight - pdfTop}，不能直接把 top 填进去。
 * Spring AI 原版是直接填的，照抄会把区域整个搬到页面的另一半，
 * 抽出来的文本张冠李戴 —— 而且<b>不报错</b>，只是内容对不上。
 * 这套换算由 {@code PdfSpikeTest} 的第 3 个用例钉死。
 *
 * <h3>拿不到 outline 怎么办</h3>
 *
 * 抛异常，由调用方决定降级到哪条路径（按字号猜标题 / 按页切）。
 * 不静默返回空文档 —— 那会让一整篇内容凭空消失且无人察觉。
 */
public final class PdfDocument implements ParsedDocument {

    private final String sourceId;
    private final String title;
    private final String fullText;
    private final List<DocSection> sections;

    private PdfDocument(String sourceId, String title, String fullText, List<DocSection> sections) {
        this.sourceId = sourceId;
        this.title = title;
        this.fullText = fullText;
        this.sections = Collections.unmodifiableList(sections);
    }

    /**
     * 只解析文本，不处理内嵌图片。
     *
     * @param sourceId 相对语料根的稳定标识，如 {@code manual/05-等待策略.pdf}
     * @throws IllegalStateException PDF 没有 outline
     */
    public static PdfDocument parse(Path file, String sourceId) throws IOException {
        return parse(file, sourceId, null, null);
    }

    /**
     * 解析文本<b>并处理内嵌图片</b>：抽出 → 转文字描述 → 原图存进对象存储。
     *
     * <p>两件事都做，缺一不可：描述文本拼进正文参与 embedding（让图里的内容可检索），
     * 原图地址存进 {@link DocSection#imageUrls()} 供引用展示（描述是有损的）。
     *
     * @param describer 图片转描述。传 null 表示不处理图片
     * @param storage   原图存放处。传 null 表示不存原图（只转描述）
     */
    public static PdfDocument parse(Path file, String sourceId,
                                    ImageDescriber describer, ObjectStorage storage)
            throws IOException {
        PDDocument pdf = PDDocument.load(file.toFile());
        try {
            PdfOutlineTree tree = new PdfOutlineTree(pdf);

            PDFTextStripper plain = new PDFTextStripper();
            plain.setSortByPosition(true);
            String fullText = plain.getText(pdf);

            List<PdfOutlineTree.Node> flat = tree.flatten();

            // 顶层只有一个书签、且它下面还有子书签 —— 那它是「文档标题」那一层，
            // 不该出现在每一节的标题路径里（否则每条路径都以书名开头，纯冗余）。
            // 企业 PDF 两种写法都常见，所以这里判断而不是假定
            boolean topIsDocTitle = tree.topLevel().size() == 1
                    && !tree.topLevel().get(0).children().isEmpty();

            String title = topIsDocTitle
                    ? tree.topLevel().get(0).title()
                    : documentTitleOf(pdf, sourceId);

            // 文档标题那一层不进 headingPath，所以从 level 1 开始收
            int skipLevelsAbove = topIsDocTitle ? 1 : 0;

            // 图片只抽一次，之后按 Y 坐标分配给各个小节 ——
            // 每节都重新解析一遍内容流的话，一篇文档要解析几十遍
            List<ExtractedImage> images = (describer == null)
                    ? Collections.<ExtractedImage>emptyList()
                    : PdfImageExtractor.extract(pdf, fileBaseNameOf(sourceId));

            List<DocSection> sections = new ArrayList<DocSection>();
            for (int i = 0; i < flat.size(); i++) {
                PdfOutlineTree.Node node = flat.get(i);
                PdfOutlineTree.Node next = (i + 1 < flat.size()) ? flat.get(i + 1) : null;

                RegionContent content = extractBetween(pdf, node, next, images);

                // 抽出来的区域是从标题顶端开始的，所以第一行就是标题自己。
                // markdown 那边的 section.body 不含标题行，这里去掉保持一致 ——
                // 否则同一份内容两种格式的 chunk 文本不同，跨格式对照就不干净了
                String body = stripLeadingTitle(content.text, node.title());

                List<String> headingPath = node.headingPath(skipLevelsAbove);

                // 处理落在本节区域内的图片：转文字描述拼进正文，原图存起来留地址
                List<String> imageUrls = new ArrayList<String>();
                if (describer != null) {
                    String context = String.join(" > ", headingPath);
                    for (ExtractedImage image : content.images) {
                        String description =
                                describer.describeBytes(image.content(), image.nameHint(),
                                        image.altText(), context);
                        if (!description.isEmpty()) {
                            body = body.isEmpty() ? description : body + "\n" + description;
                        }
                        if (storage != null) {
                            imageUrls.add(storage.put(storageKeyOf(sourceId, image),
                                    image.content(), image.contentType()));
                        }
                    }
                }

                if (body.isEmpty() && imageUrls.isEmpty()) {
                    continue;       // 只有标题没有正文的过渡层级，跳过
                }

                sections.add(new DocSection(headingPath, body, imageUrls));
            }

            return new PdfDocument(sourceId, title, fullText, sections);
        } finally {
            pdf.close();
        }
    }

    /** 没有可用的标题层时，退回 PDF 元数据里的 title，再不行用文件名。 */
    private static String documentTitleOf(PDDocument pdf, String sourceId) {
        String metaTitle = pdf.getDocumentInformation() == null
                ? null : pdf.getDocumentInformation().getTitle();
        if (metaTitle != null && !metaTitle.trim().isEmpty()) {
            return metaTitle.trim();
        }
        int slash = sourceId.lastIndexOf('/');
        String name = slash >= 0 ? sourceId.substring(slash + 1) : sourceId;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** 一个小节区域里的内容：文本，以及落在这个区域里的图片。 */
    private static final class RegionContent {

        final String text;
        final List<ExtractedImage> images;

        RegionContent(String text, List<ExtractedImage> images) {
            this.text = text;
            this.images = images;
        }
    }

    /**
     * 抽出 {@code from} 到 {@code to} 之间的文本，同时挑出落在这个区域里的图片。
     *
     * <p>文本和图片<b>用同一套区域计算</b> —— 这不是顺手为之：如果图片按另一套逻辑
     * （比如只按页码）归属，就会出现「文本切在这一节、图片归到那一节」的错位，
     * 而这种错位在检索结果里表现为「描述和上下文说的不是一回事」，极难定位。
     *
     * <p>{@code to} 为 null 表示这是最后一节，一直抽到文档结束。
     */
    private static RegionContent extractBetween(PDDocument pdf, PdfOutlineTree.Node from,
                                                PdfOutlineTree.Node to,
                                                List<ExtractedImage> allImages) throws IOException {
        List<ExtractedImage> matched = new ArrayList<ExtractedImage>();
        int startPage = from.startPage();
        if (startPage < 1) {
            // 书签指不到任何页，这一节没法定位
            return new RegionContent("", matched);
        }

        int endPage = (to != null && to.startPage() >= 1) ? to.startPage() : pdf.getNumberOfPages();
        if (endPage < startPage) {
            endPage = startPage;
        }

        StringBuilder text = new StringBuilder();
        for (int pageNumber = startPage; pageNumber <= endPage; pageNumber++) {
            PDPage page = pdf.getPage(pageNumber - 1);      // getPage 是 0-based
            float pageHeight = page.getMediaBox().getHeight();
            float pageWidth = page.getMediaBox().getWidth();

            // ⚠️ 这里就是坐标换算。top 是 PDF 坐标（左下原点），
            // Rectangle 要的是左上原点，所以取 pageHeight - top
            float regionTop;        // 区域上边（左上原点）
            float regionBottom;     // 区域下边（左上原点）

            if (pageNumber == startPage) {
                // 本节从标题所在高度开始。top<0 表示书签没带坐标，
                // 那就从页顶开始 —— 宁可多抽一点，也别把内容漏掉
                regionTop = from.top() < 0 ? 0 : pageHeight - from.top();
            } else {
                regionTop = 0;      // 中间页和末页都从页顶开始
            }

            if (pageNumber == endPage && to != null) {
                // 到下一节的标题为止。同理，没坐标就取页顶（等于本页不抽）
                regionBottom = to.top() < 0 ? 0 : pageHeight - to.top();
            } else {
                regionBottom = pageHeight;
            }

            float height = regionBottom - regionTop;
            if (height <= 0) {
                continue;       // 空区域，常见于「下一节就在本页标题正下方」
            }

            // 挑出本页落在这个 Y 区间里的图片。
            // ⚠️ image.top() 是 PDF 坐标（左下原点），regionTop/Bottom 是左上原点，
            // 所以要换算成同一套再比 —— 和文本区域用的是同一个换算
            for (ExtractedImage image : allImages) {
                if (image.pageNumber() != pageNumber) {
                    continue;
                }
                float imageTop = pageHeight - image.top();
                if (imageTop >= regionTop && imageTop < regionBottom) {
                    matched.add(image);
                }
            }

            PDFTextStripperByArea stripper = new PDFTextStripperByArea();
            stripper.setSortByPosition(true);
            stripper.addRegion("section",
                    new Rectangle((int) page.getMediaBox().getLowerLeftX(), (int) regionTop,
                            (int) pageWidth, (int) height));
            stripper.extractRegions(page);

            String pageText = stripper.getTextForRegion("section");
            if (pageText != null && !pageText.trim().isEmpty()) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(pageText.trim());
            }
        }
        return new RegionContent(text.toString().trim(), matched);
    }

    /**
     * 图片在对象存储上的 key。
     *
     * <p>必须<b>稳定</b>（同一张图重复入库得到同一个 key），否则消融实验跑六七轮
     * 会在存储里堆出六七份副本。所以 key 只由「文档标识 + 图片序号」决定，
     * 不掺时间戳或随机数。
     */
    private static String storageKeyOf(String sourceId, ExtractedImage image) {
        return "images/" + baseNameOf(sourceId) + "/" + image.nameHint();
    }

    /** {@code manual/05-等待策略.pdf} → {@code manual/05-等待策略}（保留目录，用于 key 前缀） */
    private static String baseNameOf(String sourceId) {
        int dot = sourceId.lastIndexOf('.');
        return dot > 0 ? sourceId.substring(0, dot) : sourceId;
    }

    /**
     * {@code manual/05-等待策略.pdf} → {@code 05-等待策略}（去掉目录）。
     *
     * <p>图片名字里不该再带目录 —— 否则拼进 key 会得到
     * {@code images/manual/05-等待策略/manual/05-等待策略-img-1.png}，目录段重复一遍。
     */
    private static String fileBaseNameOf(String sourceId) {
        String base = baseNameOf(sourceId);
        int slash = base.lastIndexOf('/');
        return slash >= 0 ? base.substring(slash + 1) : base;
    }

    /**
     * 去掉正文开头重复的标题行。
     *
     * <p>抽取区域从标题顶端起算，所以标题文字必然在第一行。宽松比较（去空白）——
     * PDF 抽出来的文本可能因为字距被插入额外空格。
     */
    private static String stripLeadingTitle(String body, String title) {
        if (body.isEmpty() || title.isEmpty()) {
            return body;
        }
        int newline = body.indexOf('\n');
        String firstLine = newline < 0 ? body : body.substring(0, newline);

        if (normalize(firstLine).equals(normalize(title))) {
            return newline < 0 ? "" : body.substring(newline + 1).trim();
        }
        return body;
    }

    private static String normalize(String value) {
        return value.replaceAll("\\s+", "");
    }

    @Override
    public String sourceId() {
        return sourceId;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public String fullText() {
        return fullText;
    }

    @Override
    public List<DocSection> sections() {
        return sections;
    }

    /** 供调用方在 {@code IOException} 不方便传播时使用。 */
    public static PdfDocument parseUnchecked(Path file, String sourceId) {
        try {
            return parse(file, sourceId);
        } catch (IOException e) {
            throw new UncheckedIOException("解析 PDF 失败：" + file, e);
        }
    }
}
