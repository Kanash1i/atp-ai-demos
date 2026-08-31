package com.atp.web.controller;

import com.atp.platform.inspect.InspectResponse;
import com.atp.platform.service.PageInspectService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 页面探查接口 —— 给 {@code atp inspect} 命令用。
 *
 * <h3>⭐ 为什么平台自己的 agent 也要绕一圈走 CLI 调这里</h3>
 *
 * CLI 是**两个 agent 唯一的工具层**：opencode 与平台内的 agent 用完全相同的工具集。
 * 平台 agent 若直接注入 {@link PageInspectService}，两边的工具就又分叉了 ——
 * 而这正是写侧统一走 CLI 之后已经消灭掉的那类问题。
 *
 * <p>加一个工具只改 CLI 一处，两个 agent 同时获得。
 *
 * <h3>状态码就是给 CLI 用的分派信号</h3>
 *
 * <ul>
 *   <li>{@code 200} → 探到了</li>
 *   <li>{@code 404} → 路径不存在。**你查错了**，换一个或问用户（CLI 退出码 12）</li>
 *   <li>{@code 503} → 没有执行机应答 / 浏览器起不来。**环境坏了**，重试或如实报告（CLI 退出码 20）</li>
 * </ul>
 *
 * <p>两者绝不能合并成一个"探查失败"：agent 分不清是自己错了还是环境错了，
 * 大概率退回编造 —— 而编造正是这个工具要消灭的东西。
 */
@RestController
@RequestMapping("/api/inspect")
public class InspectController {

    @Autowired
    private PageInspectService service;

    @PostMapping("/page")
    public ResponseEntity<InspectResponse> page(@RequestBody InspectRequestBody body) {
        InspectResponse resp = service.inspect(body.path());
        HttpStatus status = switch (resp.code()) {
            case "OK" -> HttpStatus.OK;
            case "NOT_FOUND" -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return ResponseEntity.status(status).body(resp);
    }

    /** @param path 路径、完整 URL、或含 {@code ${base_url}} 的案例原文写法都接受 */
    public record InspectRequestBody(String path) {
    }
}
