package com.atp.platform.service;

import com.atp.platform.entity.SysApiClient;
import com.atp.platform.mapper.SysApiClientMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 机器主体的签发与校验。
 *
 * <h3>secret 只在创建时返回一次</h3>
 *
 * 库里存的是 {@code SHA-256(salt + secret)}，平台自己也读不出明文。
 * 读得出来的密钥迟早会被打进日志、备份、或者某次 {@code SELECT *} 的截图里 ——
 * 而那时它已经发到客户机器上，换一轮的成本远高于一开始就不存。
 */
@Slf4j
@Service
public class ApiClientService {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private SysApiClientMapper mapper;

    /**
     * 校验 client_id + secret。
     *
     * @return 通过时返回该主体的权限列表；不通过返回 empty
     */
    public Optional<List<String>> authenticate(String clientId, String secret) {
        if (clientId == null || secret == null) {
            return Optional.empty();
        }
        SysApiClient c = mapper.selectById(clientId);

        // ⚠️ 「不存在」「已禁用」「secret 不对」对外必须是同一个结果 ——
        //    分开返回等于告诉试探者「这个 client_id 是存在的」，帮他缩小了范围
        if (c == null || !Boolean.TRUE.equals(c.getEnabled()) || c.getRevokedAt() != null) {
            return Optional.empty();
        }
        if (!constantTimeEquals(hash(c.getSecretSalt(), secret), c.getSecretHash())) {
            log.warn("[AUTH] {} secret 不匹配", clientId);
            return Optional.empty();
        }

        c.setLastUsedAt(OffsetDateTime.now());
        mapper.updateById(c);
        return Optional.of(scopesOf(c));
    }

    /** 取权限列表。给 Sa-Token 的 StpInterface 用 */
    public List<String> permissionsOf(String clientId) {
        SysApiClient c = mapper.selectById(clientId);
        if (c == null || !Boolean.TRUE.equals(c.getEnabled()) || c.getRevokedAt() != null) {
            // 主体在 token 有效期内被吊销 —— 权限立刻为空，已签发的 token 自然失效
            return List.of();
        }
        return scopesOf(c);
    }

    /**
     * 创建一个机器主体。
     *
     * @return 明文 secret —— **这是唯一一次能拿到它**，调用方必须立刻交给使用者
     */
    public String create(String clientId, String clientName, List<String> scopes, String createdBy) {
        String salt = randomHex(16);
        String secret = randomHex(32);

        SysApiClient c = new SysApiClient();
        c.setClientId(clientId);
        c.setClientName(clientName);
        c.setSecretSalt(salt);
        c.setSecretHash(hash(salt, secret));
        c.setScopes(String.join(",", scopes));
        c.setEnabled(true);
        c.setCreatedBy(createdBy);
        mapper.insert(c);

        log.info("[AUTH] 创建机器主体 {}（{}），权限 {}", clientId, clientName, c.getScopes());
        return secret;
    }

    private List<String> scopesOf(SysApiClient c) {
        if (c.getScopes() == null || c.getScopes().isBlank()) {
            return List.of();
        }
        return Arrays.stream(c.getScopes().split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).toList();
    }

    private String hash(String salt, String secret) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest((salt + secret).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 定长比较。
     *
     * <p>用 {@code equals} 的话，比较会在第一个不同的字符处提前返回，
     * 耗时随「猜对了几位」变化 —— 这是可测量的信息泄露。
     * 这里的字符串很短、调用不频繁，实际难以利用，但没有理由不做对。
     */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    private String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }
}
