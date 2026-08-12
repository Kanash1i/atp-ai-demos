package com.atp.rag.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 把原图写到本地目录的 {@link ObjectStorage} 实现 —— demo 的默认档。
 *
 * <p>返回的地址是 {@code base-url + key}。base-url 可配，所以即使文件在本地，
 * 也能配成一个真实可访问的 URL（比如前面挂个 nginx，或者开发机上起个
 * {@code python -m http.server}）。payload 里存的始终是 URL 而不是文件路径 ——
 * 这样将来换成 OSS，<b>已入库的数据结构不用变</b>。
 *
 * <p>不适合生产：入库进程和查询进程通常不在一台机器上，本地路径在另一端打不开。
 * 生产该换成 OSS/S3 实现，而换实现不需要碰任何解析或入库代码。
 */
public class LocalFileStorage implements ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalFileStorage.class);

    private final Path root;
    private final String baseUrl;

    /**
     * @param root    落盘根目录
     * @param baseUrl 返回地址的前缀，如 {@code http://localhost:8000/} 或 {@code file:///…}。
     *                末尾有没有斜杠都行，这里会规范化
     */
    public LocalFileStorage(String root, String baseUrl) {
        this.root = Paths.get(root);
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
    }

    @Override
    public String put(String key, byte[] content, String contentType) {
        Path target = root.resolve(key).normalize();

        // 防目录穿越：key 由文件名拼出来，理论上受控，但万一语料里有 ../
        // 就会写到语料目录外面去
        if (!target.startsWith(root.normalize())) {
            throw new IllegalArgumentException("非法的存储 key（越出根目录）：" + key);
        }

        try {
            Files.createDirectories(target.getParent());

            // 先写临时文件再原子移动 —— 入库中途被 Ctrl-C 时，
            // 不会留下一个半截的图片文件让下次重跑读到坏数据
            Path temp = Files.createTempFile(target.getParent(), ".put-", ".tmp");
            try {
                Files.write(temp, content);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("写入图片失败：" + target, e);
        }

        // key 里的路径分隔符在 URL 里始终是 /，不能用平台分隔符
        return baseUrl + key.replace('\\', '/');
    }

    @Override
    public String describe() {
        return "本地文件（" + root.toAbsolutePath() + " → " + baseUrl + "）";
    }

    /** 启动时打一次，免得不知道图片存哪去了。 */
    public void logConfiguration() {
        log.info("原图存储：{}", describe());
    }
}
