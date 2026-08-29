package com.atp.runner.node;

import com.atp.runner.RunnerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * 把录像与截图传回主应用。
 *
 * <h3>为什么必须上传，而不是让前端直接读节点磁盘</h3>
 *
 * 部署形态是：主应用在**云服务器**（公网），执行节点在**家里的台式机**，两边靠 Tailscale 组网。
 * 面试官打开的是公网地址，<b>他的浏览器不在 Tailscale 网络里</b> —— 读不到台式机的任何东西。
 * 所以产物必须主动送到云服务器上。
 *
 * <h3>为什么用 PUT 裸字节而不是 multipart</h3>
 *
 * multipart 要拼 boundary、要处理编码，而这里只传一个文件、没有额外字段。
 * 用 PUT + 路径即资源名，客户端一行 {@code BodyPublishers.ofFile}，服务端一个 {@code byte[]} 参数，
 * 两边都不需要引第三方库 —— JDK 自带的 HttpClient 就够。
 *
 * <h3>⚠️ 上传失败不能让任务失败</h3>
 *
 * 录像只是**佐证**，执行结果本身已经产生了。因为传不上去就把一条本来跑通的用例判成失败，
 * 是拿证据的缺失去否定事实。上传失败时记日志、URL 置空，任务结果照常回写。
 */
@Slf4j
@Component
public class ArtifactUploader {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Autowired
    private RunnerProperties props;

    /**
     * @param localPath 节点本地的产物文件
     * @param relative  相对路径，形如 {@code RUN-20260830-0001/ATP-LOGIN-0002-abc12345/xxx.webm}
     * @return 可供前端访问的 URL；上传失败或未启用时返回 null
     */
    public String upload(String localPath, String relative) {
        if (localPath == null) {
            return null;
        }
        if (!props.isUploadEnabled()) {
            // 同机部署时不必绕一圈网络，直接给相对 URL —— 主应用从同一个目录 serve
            return "/api/artifacts/" + relative;
        }

        Path file = Path.of(localPath);
        if (!Files.isRegularFile(file)) {
            log.warn("产物文件不存在，跳过上传：{}", localPath);
            return null;
        }

        String url = props.getPlatformUrl() + "/api/artifacts/" + relative;
        try {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(30))
                            .header("Content-Type", contentType(relative))
                            .PUT(HttpRequest.BodyPublishers.ofFile(file))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() / 100 != 2) {
                log.warn("产物上传失败 HTTP {}：{}", resp.statusCode(), url);
                return null;
            }
            log.debug("产物已上传 {}（{} KB）", relative, Files.size(file) / 1024);
            return "/api/artifacts/" + relative;
        } catch (Exception e) {
            // ⚠️ 不抛：录像传不上去，不该把一条跑通的用例判成失败
            log.warn("产物上传异常，URL 置空：{}", url, e);
            return null;
        }
    }

    private String contentType(String name) {
        if (name.endsWith(".webm")) {
            return "video/webm";
        }
        if (name.endsWith(".png")) {
            return "image/png";
        }
        return "application/octet-stream";
    }
}
