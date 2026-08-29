package com.atp.web.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 执行产物：录像与失败截图。
 *
 * <p>执行节点跑在家里的台式机上，主应用在云服务器 —— 面试官打开的是公网地址，
 * 他的浏览器不在 Tailscale 网络里，读不到台式机的任何东西。
 * 所以节点执行完要把产物 PUT 上来，这里落盘，前端再从这里读。
 */
@Slf4j
@RestController
@RequestMapping("/api/artifacts")
public class ArtifactController {

    @Value("${atp.artifact-dir:/tmp/atp-artifacts}")
    private String artifactDir;

    /**
     * 接收执行节点上传的产物。
     *
     * <p>用 PUT + 路径即资源名，不用 multipart —— 只传一个文件、没有额外字段，
     * multipart 的 boundary 和编码处理是白付的成本。
     *
     * <p>⚠️ 路径穿越防护与读取一致：{@code normalize()} 之后必须仍在根目录内。
     * 这个端点接受的是**写入**，防护比读取更要紧 —— 越界的读只是泄漏，越界的写是覆盖。
     */
    @PutMapping("/**")
    public ResponseEntity<String> upload(HttpServletRequest request, @RequestBody byte[] body) {
        Path file = resolve(request);
        if (file == null) {
            return ResponseEntity.badRequest().body("越界的产物路径");
        }
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, body);
            log.info("收到产物 {}（{} KB）", file.getFileName(), body.length / 1024);
            return ResponseEntity.ok("ok");
        } catch (Exception e) {
            log.error("产物落盘失败：{}", file, e);
            return ResponseEntity.internalServerError().body("落盘失败");
        }
    }

    /** 解析并校验路径。越界返回 null */
    private Path resolve(HttpServletRequest request) {
        String full = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        String relative = full.replaceFirst("^/api/artifacts/?", "");
        Path root = Path.of(artifactDir).toAbsolutePath().normalize();
        Path file = root.resolve(relative).normalize();
        if (!file.startsWith(root)) {
            log.warn("拒绝越界的产物路径：{}", relative);
            return null;
        }
        return file;
    }

    @GetMapping("/**")
    public ResponseEntity<Resource> get(HttpServletRequest request) {
        String full = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        String relative = full.replaceFirst("^/api/artifacts/?", "");

        Path root = Path.of(artifactDir).toAbsolutePath().normalize();
        Path file = root.resolve(relative).normalize();

        // ⚠️ 路径穿越防护：relative 来自 URL，可能是 ../../etc/passwd。
        //    normalize() 之后必须仍在根目录内，否则一律拒绝。
        if (!file.startsWith(root)) {
            log.warn("拒绝越界的产物请求：{}", relative);
            return ResponseEntity.badRequest().build();
        }
        if (!Files.isRegularFile(file)) {
            return ResponseEntity.notFound().build();
        }

        MediaType type = relative.endsWith(".webm") ? MediaType.parseMediaType("video/webm")
                : relative.endsWith(".png") ? MediaType.IMAGE_PNG
                : MediaType.APPLICATION_OCTET_STREAM;

        return ResponseEntity.ok()
                .contentType(type)
                // 录像要能拖进度条，浏览器靠这个头判断能不能分段请求
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .body(new FileSystemResource(file));
    }
}
