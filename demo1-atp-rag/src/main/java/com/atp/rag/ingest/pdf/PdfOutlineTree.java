package com.atp.rag.ingest.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageTree;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 PDF 的 outline（书签 / 目录）建出标题树 —— 「按导航切块」的第一步。
 *
 * <h3>出处</h3>
 *
 * 算法移植自 Spring AI 的 {@code ParagraphManager}（Apache License 2.0，
 * 版权归 Spring AI 原作者 Christian Tzolov）。移植而不是直接依赖，原因有两个：
 * Spring AI 要求 Java 17，本项目锁 Java 8；且它按 PDFBox 3.x 的 API 写，
 * 我们锁 PDFBox 2.0.30。
 *
 * <p>改动点（除了语言层面的 record→class、var→显式类型）：
 * <ul>
 *   <li><b>补上 headingPath</b> —— 原版只存 {@code level} 这个整数，
 *       父子关系虽然在树里但从没被用来生成标题路径。而标题路径正是本项目
 *       {@code HEADING_PATH} 策略的输入，所以这里沿 parent 链把它拼出来</li>
 *   <li><b>坐标换算</b> —— 见 {@link PdfDocument}，原版把 PDF 坐标直接当
 *       {@code Rectangle} 坐标用，两者的原点不在同一个角</li>
 * </ul>
 *
 * <h3>为什么书签必须带 Y 坐标</h3>
 *
 * 只有页码的话，同一页上的几个小节会被切成同一块 —— 而手册里一页放三四个小节是常态。
 * {@link PDPageXYZDestination#getTop()} 给出书签在页内的垂直位置，
 * 有了它才能把一页切成几段。
 */
public final class PdfOutlineTree {

    /** 树上的一个节点：一个书签，加上它在文档里的位置范围。 */
    public static final class Node {

        private final Node parent;
        private final String title;
        private final int level;
        private final int startPage;
        private final int top;
        private final List<Node> children = new ArrayList<Node>();

        Node(Node parent, String title, int level, int startPage, int top) {
            this.parent = parent;
            this.title = title;
            this.level = level;
            this.startPage = startPage;
            this.top = top;
        }

        public String title() {
            return title;
        }

        /** 根的子节点是 0，往下递增。 */
        public int level() {
            return level;
        }

        /** 1-based 页码。取不到目标页时是 -1。 */
        public int startPage() {
            return startPage;
        }

        /** 书签在页内的垂直位置，<b>PDF 坐标系</b>（原点左下角，向上为正）。 */
        public int top() {
            return top;
        }

        public List<Node> children() {
            return children;
        }

        public Node parent() {
            return parent;
        }

        /**
         * 从祖先链拼出标题路径，<b>不含</b>指定的那一层以上。
         *
         * <p>这是原版没有的。{@code level} 只是个整数，说明不了「这一节挂在哪一章下面」，
         * 而 {@code HEADING_PATH} 策略要的恰恰是完整路径。
         *
         * @param skipLevelsAbove 低于这个 level 的祖先不进路径。
         *                        传 0 表示保留全部；传 1 表示丢掉最外层（通常是文档标题）
         */
        public List<String> headingPath(int skipLevelsAbove) {
            List<String> path = new ArrayList<String>();
            Node current = this;
            while (current != null && current.level >= skipLevelsAbove) {
                path.add(0, current.title);
                current = current.parent;
            }
            return path;
        }
    }

    private final Node root;

    /** 页对象 → 1-based 页码。建一次表，避免每个书签都线性扫一遍页树。 */
    private final Map<PDPage, Integer> pageNumbers = new HashMap<PDPage, Integer>();

    /**
     * @throws IllegalStateException PDF 没有 outline。调用方应当据此降级到别的策略，
     *                               而不是当作空文档继续 —— 那会静默丢掉整篇内容
     */
    public PdfOutlineTree(PDDocument document) throws IOException {
        PDDocumentOutline outline = document.getDocumentCatalog().getDocumentOutline();
        if (outline == null) {
            throw new IllegalStateException("PDF 没有 outline（书签/目录）");
        }

        PDPageTree pages = document.getDocumentCatalog().getPages();
        for (int i = 0; i < pages.getCount(); i++) {
            pageNumbers.put(pages.get(i), i + 1);
        }

        this.root = new Node(null, "(root)", -1, 1, 0);
        build(document, root, outline, 0);
    }

    /**
     * 递归遍历兄弟书签，每个都建成 {@link Node} 并挂到 {@code parent} 下。
     *
     * <p>与原版结构一致，区别是这里不再记录 {@code endPage}：
     * 一节到哪结束由「展平后的下一个书签在哪开始」决定，
     * 存两份容易不一致，用的时候现算更可靠。
     */
    private void build(PDDocument document, Node parent, PDOutlineNode bookmark, int level)
            throws IOException {
        PDOutlineItem current = bookmark.getFirstChild();
        while (current != null) {
            Node node = new Node(parent, safeTitle(current), level,
                    pageNumberOf(document, current), topOf(current));
            parent.children().add(node);

            // 递归进这个书签的子书签，深一层
            build(document, node, current, level + 1);

            current = current.getNextSibling();
        }
    }

    /** 书签标题可能为空或带首尾空白，兜一下。 */
    private static String safeTitle(PDOutlineItem item) throws IOException {
        String title = item.getTitle();
        return title == null ? "" : title.trim();
    }

    /**
     * 书签指向的页内垂直位置。
     *
     * <p>只有 {@link PDPageXYZDestination} 带得上坐标。其它 destination 类型
     * （FitH / Fit / FitR 等）拿不到，返回 0 —— 也就是退化成「这一节从页顶开始」，
     * 同页多节会粘在一起。这是数据本身的限制，不是 bug。
     */
    private static int topOf(PDOutlineItem item) throws IOException {
        PDPageDestination destination = asPageDestination(item);
        if (destination instanceof PDPageXYZDestination) {
            return ((PDPageXYZDestination) destination).getTop();
        }
        return 0;
    }

    /** 1-based 页码，取不到返回 -1。 */
    private int pageNumberOf(PDDocument document, PDOutlineItem item) throws IOException {
        PDPage page = item.findDestinationPage(document);
        if (page == null) {
            return -1;
        }
        Integer number = pageNumbers.get(page);
        return number == null ? -1 : number;
    }

    private static PDPageDestination asPageDestination(PDOutlineItem item) throws IOException {
        return item.getDestination() instanceof PDPageDestination
                ? (PDPageDestination) item.getDestination() : null;
    }

    /**
     * 深度优先展平。
     *
     * <p>顺序很关键：展平后<b>相邻两个节点之间的内容</b>就是前一个节点的正文。
     * 深度优先保证「章标题」的下一个是「它的第一个节标题」，
     * 于是章标题拿到的正文正好是章引言 —— 与 markdown 那边
     * 「正文归属最深标题」的语义一致。
     */
    public List<Node> flatten() {
        List<Node> flat = new ArrayList<Node>();
        for (Node child : root.children()) {
            flatten(child, flat);
        }
        return flat;
    }

    private void flatten(Node node, List<Node> out) {
        out.add(node);
        for (Node child : node.children()) {
            flatten(child, out);
        }
    }

    /** 顶层书签。用来判断第一层是不是「文档标题」那一层。 */
    public List<Node> topLevel() {
        return root.children();
    }
}
