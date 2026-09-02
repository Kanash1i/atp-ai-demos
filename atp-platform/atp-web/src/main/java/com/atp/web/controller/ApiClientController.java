package com.atp.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.atp.common.enums.ApiScope;
import com.atp.platform.service.ApiClientService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 机器凭证的签发与吊销 —— 给项目经理用。
 *
 * <h3>它解决的是「派发」这一步</h3>
 *
 * atp CLI 装到测试人员机器上之后，还需要一对 {@code client_id} / {@code secret}
 * 才能写案例。在这个接口之前，那对凭证只能由人手写 SQL 插进库里 ——
 * 于是「多来一个测试人员」这件事需要一个能连生产库的人参与。
 *
 * <p>现在的流程：测试人员找项目经理要 → 项目经理调这个接口 → 把返回的
 * 两行发给他。签发这件事本身不再需要数据库权限。
 *
 * <h3>⚠️ secret 只返回这一次</h3>
 *
 * 库里存的是 {@code SHA-256(salt + secret)}，平台自己也读不出明文。
 * 丢了只能吊销重发 —— 这是刻意的：读得出来的密钥迟早会出现在日志、
 * 备份或者某次截图里，而那时它已经在客户机器上了。
 */
@Slf4j
@RestController
// ⚠️ 鉴权不在这里 —— 全站规则集中在 SaTokenConfigure，
// 分散成注解的话「谁能调什么」就得翻遍每个 controller 才数得清
@RequestMapping("/api/auth/clients")
public class ApiClientController {

    /**
     * 允许签发的 scope 白名单。
     *
     * <p>⚠️ 这里不是「所有 scope」。两个刻意排除的：
     * <ul>
     *   <li>{@code approval:decide} —— 「这条变更该不该放行」是人的判断。
     *       发给机器等于让 agent 批准自己提交的东西。</li>
     *   <li>{@code client:manage} —— 能签发凭证的凭证。一旦发出去，
     *       持有者就能给自己再签一个带任意权限的，整套权限控制当场失效。</li>
     * </ul>
     *
     * <p>白名单而不是黑名单：以后新增 scope 时，默认是「不能发给机器」，
     * 要发必须显式加进来 —— 忘了加只会导致签不出，而忘了排除会导致越权。
     */
    private static final Set<String> GRANTABLE = Set.of(
            ApiScope.CASE_WRITE.code(),
            ApiScope.EXEC_RUN_ONCE.code(),
            ApiScope.INSPECT.code());

    /** client_id 的形状：小写字母、数字、连字符，3~64 位 */
    private static final String ID_PATTERN = "^[a-z0-9][a-z0-9-]{2,63}$";

    @Autowired
    private ApiClientService clients;

    /**
     * 签发一对新凭证。
     *
     * <p>返回体里的 {@code clientSecret} 是**唯一一次**能拿到明文的机会。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestBody CreateRequest body) {
        String clientId = body.clientId() == null ? "" : body.clientId().trim().toLowerCase(Locale.ROOT);
        if (!clientId.matches(ID_PATTERN)) {
            throw new IllegalArgumentException(
                    "clientId 只能是小写字母、数字与连字符，3~64 位。收到：" + body.clientId());
        }
        // ⚠️ 先查存在再插入。不查的话第二次用同一个 id 会覆盖掉前一个的 secret_hash，
        //    结果是**上一个测试人员手上的凭证突然失效**，而没有任何人知道发生了什么。
        if (clients.exists(clientId)) {
            throw new IllegalStateException("clientId 已存在：" + clientId + "。换一个，或先吊销旧的");
        }

        List<String> scopes = body.scopes() == null || body.scopes().isEmpty()
                ? List.copyOf(GRANTABLE)
                : body.scopes();
        List<String> rejected = scopes.stream().filter(s -> !GRANTABLE.contains(s)).toList();
        if (!rejected.isEmpty()) {
            throw new IllegalArgumentException(
                    "这些权限不能发给机器主体：" + String.join("、", rejected)
                            + "。可发的是：" + String.join("、", GRANTABLE));
        }

        String issuer = StpUtil.getLoginIdAsString();
        String secret = clients.create(clientId, body.clientName(), scopes, issuer);

        log.info("[AUTH] {} 为 {} 签发凭证，权限 {}", issuer, clientId, scopes);
        return Map.of(
                "clientId", clientId,
                "clientSecret", secret,
                "scopes", scopes,
                "notice", "clientSecret 只在这次返回，平台不保存明文。丢了只能吊销重发。");
    }

    /** 列出全部机器主体。⚠️ 不含 secret */
    @GetMapping
    public List<ApiClientService.ClientView> list() {
        return clients.list();
    }

    /** 吊销 —— 禁用而不是删除，留下线索 */
    @DeleteMapping("/{clientId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable String clientId) {
        if (!clients.revoke(clientId)) {
            throw new IllegalArgumentException("clientId 不存在或已吊销：" + clientId);
        }
    }

    public record CreateRequest(String clientId, String clientName, List<String> scopes) {
    }
}
