package com.atp.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.atp.platform.entity.SysUser;
import com.atp.platform.service.ApiClientService;
import com.atp.platform.service.UserAuthService;
import com.atp.web.auth.StpInterfaceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.net.URI;
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

    @Autowired
    private UserAuthService userAuthService;

    @PostMapping("/token")
    public ResponseEntity<?> token(@RequestBody TokenRequest body) {
        Optional<List<String>> scopes = apiClientService.authenticate(body.clientId(), body.clientSecret());

        if (scopes.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "client_id 或 secret 不正确"));
        }

        StpUtil.login(StpInterfaceImpl.CLIENT_PREFIX + body.clientId());
        log.info("[AUTH] {} 换取 token 成功，权限 {}", body.clientId(), scopes.get());

        return ResponseEntity.ok(Map.of(
                "token", StpUtil.getTokenValue(),
                "tokenName", StpUtil.getTokenName(),
                "expiresIn", StpUtil.getTokenTimeout(),
                "scopes", scopes.get()));
    }

    /**
     * 人登录。与 {@code /token}（机器主体）是两条路径，**刻意不合并**：
     *
     * <ul>
     *   <li>凭据形态不同：人是用户名+密码，机器是 client_id+secret（64 位 hex）</li>
     *   <li>返回内容不同：人需要 displayName / canApprove 这些界面要用的东西</li>
     *   <li>权限不同：只有人拿得到 {@code approval:decide}</li>
     * </ul>
     *
     * <p>合并成一个「通用登录」的话，这三处差异就得靠请求体里的一个 type 字段去分支 ——
     * 而分支越深，「这个 token 到底是谁的」越难回答。
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest body) {
        var user = userAuthService.authenticate(body.username(), body.password());

        if (user.isEmpty()) {
            ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                    HttpStatus.UNAUTHORIZED, "用户名或密码不正确");
            pd.setType(URI.create("https://atp.example/problems/bad-credentials"));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(pd);
        }

        SysUser u = user.get();
        StpUtil.login(StpInterfaceImpl.USER_PREFIX + u.getUserId());
        log.info("[AUTH] {}（{}）登录成功", u.getUsername(), u.getDisplayName());

        return ResponseEntity.ok(Map.of(
                "token", StpUtil.getTokenValue(),
                "tokenName", StpUtil.getTokenName(),
                "expiresIn", StpUtil.getTokenTimeout(),
                "user", Map.of(
                        "userId", u.getUserId(),
                        "username", u.getUsername(),
                        "displayName", u.getDisplayName(),
                        "avatarText", u.getAvatarText() == null ? "" : u.getAvatarText(),
                        "role", u.getRole() == null ? "" : u.getRole().name(),
                        "canApprove", userAuthService.canApprove(u))));
    }

    public record TokenRequest(String clientId, String clientSecret) {
    }

    public record LoginRequest(String username, String password) {
    }
}
