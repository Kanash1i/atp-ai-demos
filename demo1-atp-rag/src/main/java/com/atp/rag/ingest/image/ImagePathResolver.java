package com.atp.rag.ingest.image;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 把 markdown 里的图片引用解析成实际文件路径。
 *
 * <h3>为什么需要它</h3>
 *
 * markdown 的图片路径没有统一约定，取决于写作者以什么为根。本项目语料里是这样的：
 *
 * <pre>
 * corpus/docs/manual/05-等待策略.md   引用   img/manual/case-edit-wait-strategy.png
 * corpus/docs/img/manual/case-edit-wait-strategy.png                    ← 实际位置
 * </pre>
 *
 * 也就是路径相对的是 <b>docs 根</b>，不是 md 文件自己所在的目录。
 * 按「相对 md 文件」去 resolve 会得到 {@code docs/manual/img/manual/…}，找不到文件。
 *
 * <p>这个错误<b>不会报错</b>，只会让图片被静默跳过 —— 生成 PDF 时表现为
 * 一行「图片缺失」，入库时表现为那张图的内容在库里不存在。
 * 是比对 md 与 PDF 文本层的字符差异才发现的（DECISIONS.md D-023）。
 *
 * <p>所以这里不猜规则，而是<b>按候选根目录依次试</b>，第一个存在的就用。
 * 规则变了也不用改代码，加一个候选即可。
 */
public final class ImagePathResolver {

    private ImagePathResolver() {
    }

    /**
     * 依次在候选根目录下查找图片。
     *
     * @param reference markdown 里写的路径，如 {@code img/manual/x.png}
     * @param roots     候选根目录，按优先级排列。通常传
     *                  {@code [md 文件所在目录, docs 根]}
     * @return 第一个真实存在的路径；全都找不到返回 null
     */
    public static Path resolve(String reference, Path... roots) {
        if (reference == null || reference.isEmpty()) {
            return null;
        }

        Path direct = toPath(reference);
        if (direct == null) {
            return null;        // 路径字符串本身非法，当作找不到
        }
        // 绝对路径直接用，不参与候选查找
        if (direct.isAbsolute()) {
            return Files.exists(direct) ? direct : null;
        }

        for (Path root : roots) {
            if (root == null) {
                continue;
            }
            Path candidate = root.resolve(direct).normalize();
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 安全地把字符串转成 {@link Path}。非法字符（Windows 上路径里的 {@code :} 之类）
     * 会抛 {@code InvalidPathException} —— 一个路径写错不该让整篇文档的处理失败，
     * 所以这里吞掉返回 null，由调用方按「图片找不到」处理。
     */
    private static Path toPath(String value) {
        try {
            return Paths.get(value);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
