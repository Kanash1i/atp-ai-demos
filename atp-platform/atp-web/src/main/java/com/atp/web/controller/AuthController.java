package com.atp.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.atp.platform.service.ApiClientService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 机器主体换 token。
 *
 * <p>CLI 手上只有 {@code client_id} + {@code secret}，用它换一个有时限的 token，
 * 之后每次请求带 {@code Authorization: Bearer <token>}。
 *
 * <p>⚠️ 这条路径本身不鉴权（否则就成了先有鸡还是先有蛋），
 * 所以它是唯一一个能被暴力试探的入口 —— {@code authenticate} 里
 * 「不存在 / 已禁用 / secret 错」返回同一个结果，就是为了不给试探者任何缩小范围的信息。
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private ApiClientService apiClientService;

    @PostMapping("/token")
    public ResponseEntity<?> token(@RequestBody TokenRequest body) {
        Optional<List<String>> scopes = apiClientService.authenticate(body.clientId(), body.clientSecret());

        if (scopes.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "client_id 或 secret 不正确"));
        }

        StpUtil.login(body.clientId());
        log.info("[AUTH] {} 换取 token 成功，权限 {}", body.clientId(), scopes.get());

        return ResponseEntity.ok(Map.of(
                "token", StpUtil.getTokenValue(),
                "tokenName", StpUtil.getTokenName(),
                "expiresIn", StpUtil.getTokenTimeout(),
                "scopes", scopes.get()));
    }

    public record TokenRequest(String clientId, String clientSecret) {
    }
}
