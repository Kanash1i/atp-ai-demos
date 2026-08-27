package com.atp.rag.ingest.pdf;

import com.atp.rag.ingest.image.ExtractedImage;
import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.state.Concatenate;
import org.apache.pdfbox.contentstream.operator.state.Restore;
import org.apache.pdfbox.contentstream.operator.state.Save;
import org.apache.pdfbox.contentstream.operator.state.SetGraphicsStateParameters;
import org.apache.pdfbox.contentstream.operator.state.SetMatrix;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.util.Matrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 从 PDF 里抽出内嵌图片，<b>连位置一起</b>。
 *
 * <h3>为什么不能只遍历 PDResources</h3>
 *
 * 直接扫 {@code page.getResources().getXObjectNames()} 能拿到图片对象，但拿不到
 * <b>它画在页面哪个位置</b> —— resources 只是「这一页用到了哪些资源」的字典，
 * 位置信息在内容流的绘制指令里。
 *
 * <p>而位置是必需的：{@link PdfDocument} 按书签 Y 坐标把一页切成几个小节，
 * 图片必须落到正确那一节。没有位置就只能按页归属，一页多节时必然归错。
 *
 * <p>所以走 {@link PDFStreamEngine} 解释内容流，在 {@code Do} 操作符处
 * 从当前变换矩阵（CTM）读出图片的位置和显示尺寸。
 *
 * <h3>为什么要处理 Form XObject</h3>
 *
 * PDF 允许把一组绘制指令打包成 form 再引用（Word 导出的 PDF 常这么干）。
 * 图片可能嵌在 form 里，不递归进去就会漏掉 —— 而且是<b>静默</b>漏掉。
 */
public final class PdfImageExtractor extends PDFStreamEngine {

    private static final Logger log = LoggerFactory.getLogger(PdfImageExtractor.class);

    private final List<ExtractedImage> images = new ArrayList<ExtractedImage>();
    private final String documentName;

    /** 当前处理到第几页（1-based），供 {@link ExtractedImage} 记录位置。 */
    private int currentPage;

    /** 已抽出的图片计数，用来生成稳定的名字。 */
    private int counter;

    private PdfImageExtractor(String documentName) {
        this.documentName = documentName;

        // ⚠️⚠️ 这几行不是样板代码，缺了就<b>静默算错位置</b>。
        //
        // 裸的 PDFStreamEngine **不注册任何操作符处理器** —— PDFTextStripper 之类
        // 是在自己构造函数里注册的。不注册的话 `cm`（矩阵变换）没有处理器，
        // super.processOperator 直接忽略它，于是 CTM 永远停在单位矩阵上：
        //
        //   实测症状：一张实际在第 2 页 top=781.89 的图，读出来 top = 1.0
        //             （translateY=0 + scalingFactorY=1，正是单位矩阵）
        //   注册之后才拿到 781.89，图片也才归进正确的小节
        //
        // 后果是所有图片的位置都算成页面底部，全都归到最后一个小节去 ——
        // 而且**不报任何错**，只是图文对不上。
        //
        // 只注册维护图形状态所必需的这几个：
        addOperator(new Concatenate());                  // cm —— 矩阵变换，位置全靠它
        addOperator(new Save());                         // q  —— 压栈
        addOperator(new Restore());                      // Q  —— 弹栈
        addOperator(new SetMatrix());                    // Tm —— 文本矩阵
        addOperator(new SetGraphicsStateParameters());   // gs —— 引用 ExtGState
        // 注意不注册 DrawObject —— `Do` 由本类的 processOperator 自己接管
    }

    /**
     * 抽出整篇文档里的所有图片。
     *
     * @param documentName 用于生成图片名字，通常是文档标题或文件名
     */
    public static List<ExtractedImage> extract(PDDocument document, String documentName) {
        PdfImageExtractor extractor = new PdfImageExtractor(documentName);
        for (int i = 0; i < document.getNumberOfPages(); i++) {
            extractor.currentPage = i + 1;
            PDPage page = document.getPage(i);
            try {
                extractor.processPage(page);
            } catch (IOException e) {
                // 一页解析失败不该让整篇文档报废 —— 其余页的图还是有价值的。
                // 但必须留下日志，否则「少了几张图」这件事无人察觉
                log.warn("PDF 第 {} 页图片抽取失败，跳过该页：{}", i + 1, e.getMessage());
            }
        }
        return extractor.images;
    }

    /**
     * 拦截 {@code Do} 操作符 —— 它是「画一个 XObject」的指令。
     *
     * <p>其余操作符交回父类处理，否则 CTM 不会被正确维护，位置就全错了。
     */
    @Override
    protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
        if (!"Do".equals(operator.getName()) || operands.isEmpty()) {
            super.processOperator(operator, operands);
            return;
        }

        COSBase operand = operands.get(0);
        if (!(operand instanceof COSName)) {
            super.processOperator(operator, operands);
            return;
        }

        PDXObject xobject = getResources().getXObject((COSName) operand);

        if (xobject instanceof PDImageXObject) {
            record((PDImageXObject) xobject);
        } else if (xobject instanceof PDFormXObject) {
            // 递归进 form —— 图片可能藏在里面。showForm 会正确地叠加 CTM，
            // 所以 form 内部图片的位置换算依然是对的
            showForm((PDFormXObject) xobject);
        }
    }

    private void record(PDImageXObject image) {
        // CTM 描述了「单位正方形」到「图片实际占据的矩形」的变换。
        // translateY 是图片**底边**的 Y，scalingFactorY 是显示高度，
        // 两者相加得到顶边 —— 与小节划分用的基准一致（见 ExtractedImage.top）
        Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
        float bottom = ctm.getTranslateY();
        float height = ctm.getScalingFactorY();
        float top = bottom + height;

        byte[] bytes = toPng(image);
        if (bytes == null) {
            return;
        }

        counter++;
        String name = documentName + "-img-" + counter + ".png";
        images.add(new ExtractedImage(bytes, "image/png", name, "", currentPage, top));
    }

    /**
     * 统一转成 PNG 字节。
     *
     * <p>为什么不直接取原始流（{@code image.getStream()}）：PDF 里的图片可能是
     * CCITT、JBIG2、CMYK JPEG 这些格式，原始字节拿出来很多 VLM 和浏览器都不认。
     * 走 {@link PDImageXObject#getImage()} 让 PDFBox 解码成 {@link BufferedImage}
     * 再统一编码成 PNG，下游（VLM、对象存储、浏览器展示）只需要认一种格式。
     *
     * <p>代价是每张图多一次解码编码。图片数量在语料规模下是几十张，可以忽略。
     */
    private byte[] toPng(PDImageXObject image) {
        try {
            BufferedImage buffered = image.getImage();
            if (buffered == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(buffered, "png", out)) {
                log.warn("图片编码成 PNG 失败（无可用 writer），跳过");
                return null;
            }
            return out.toByteArray();
        } catch (IOException e) {
            log.warn("图片解码失败，跳过：{}", e.getMessage());
            return null;
        } catch (RuntimeException e) {
            // PDFBox 遇到损坏的图片流会抛各种 unchecked 异常
            log.warn("图片解码异常，跳过：{}", e.getMessage());
            return null;
        }
    }
}
