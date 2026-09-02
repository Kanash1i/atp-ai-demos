package com.atp.platform.service;

import com.atp.platform.entity.SysApiClient;
import com.atp.common.util.DisplayTime;
import com.atp.common.util.Secrets;
import com.atp.platform.mapper.SysApiClientMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Arrays;
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
        if (!Secrets.constantTimeEquals(Secrets.hash(c.getSecretSalt(), secret), c.getSecretHash())) {
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
        String salt = Secrets.randomHex(16);
        String secret = Secrets.randomHex(32);

        SysApiClient c = new SysApiClient();
        c.setClientId(clientId);
        c.setClientName(clientName);
        c.setSecretSalt(salt);
        c.setSecretHash(Secrets.hash(salt, secret));
        c.setScopes(String.join(",", scopes));
        c.setEnabled(true);
        c.setCreatedBy(createdBy);
        mapper.insert(c);

        log.info("[AUTH] 创建机器主体 {}（{}），权限 {}", clientId, clientName, c.getScopes());
        return secret;
    }

    /** 是否已存在同名主体 —— 创建前查一次，避免撞号后覆盖掉别人的凭证 */
    public boolean exists(String clientId) {
        return mapper.selectById(clientId) != null;
    }

    /**
     * 列出全部机器主体。
     *
     * <p>⚠️ 返回里**没有 secret**，也没有 hash 与 salt —— 它们连"能被看到"这件事都不该发生。
     * 项目经理要看的是「发过哪些、谁在用、还有效吗」，那些字段一个都不需要。
     */
    public List<ClientView> list() {
        return mapper.selectList(null).stream()
                .map(c -> new ClientView(c.getClientId(), c.getClientName(), c.getScopes(),
                        Boolean.TRUE.equals(c.getEnabled()),
                        c.getCreatedBy(),
                        c.getCreatedAt() == null ? null : DisplayTime.toMinute(c.getCreatedAt()),
                        c.getLastUsedAt() == null ? null : DisplayTime.toMinute(c.getLastUsedAt())))
                .toList();
    }

    /**
     * 吊销一个机器主体。
     *
     * <p>⚠️ 是禁用不是删除。删掉的话，「这个 client 曾经存在过、谁签的、什么时候停的」
     * 这条线索就没了 —— 而凭证泄露之后要查的恰恰是这个。
     *
     * <p>吊销立刻生效：权限是每次鉴权现查库的（见 StpInterfaceImpl），
     * 不缓存在 token 里，所以不存在"已签发的 token 还能再用一阵"。
     */
    public boolean revoke(String clientId) {
        SysApiClient c = mapper.selectById(clientId);
        if (c == null || !Boolean.TRUE.equals(c.getEnabled())) {
            return false;
        }
        SysApiClient patch = new SysApiClient();
        patch.setClientId(clientId);
        patch.setEnabled(false);
        patch.setRevokedAt(OffsetDateTime.now());
        mapper.updateById(patch);
        log.info("[AUTH] 吊销机器主体 {}", clientId);
        return true;
    }

    /** 给人看的机器主体信息。⚠️ 不含 secret / hash / salt */
    public record ClientView(String clientId, String clientName, String scopes,
                             boolean enabled, String createdBy,
                             String createdAt, String lastUsedAt) {
    }

    private List<String> scopesOf(SysApiClient c) {
        if (c.getScopes() == null || c.getScopes().isBlank()) {
            return List.of();
        }
        return Arrays.stream(c.getScopes().split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).toList();
    }



}
