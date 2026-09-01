package com.atp.web.auth;

import cn.dev33.satoken.stp.StpInterface;
import com.atp.platform.service.ApiClientService;
import com.atp.platform.service.UserAuthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 告诉 Sa-Token「这个主体有哪些权限」。
 *
 * <p>⚠️ 每次鉴权都现查库，不缓存在 token 里。
 * 把 scope 塞进 token 的话，**吊销就失效了** —— 已签发的 token 会一直带着旧权限，
 * 直到它自己过期。现查库的代价是一次主键查询，换来的是「吊销立刻生效」。
 */
@Component
public class StpInterfaceImpl implements StpInterface {

    /** 机器主体的 loginId 前缀 —— 与人的 id 空间分开，避免撞号 */
    public static final String CLIENT_PREFIX = "client:";
    /** 人的 loginId 前缀 */
    public static final String USER_PREFIX = "user:";

    @Autowired
    private ApiClientService apiClientService;

    @Autowired
    private UserAuthService userAuthService;

    /**
     * ⭐ 两种主体共用一套 token 体系，靠 loginId 前缀区分。
     *
     * <p>不分前缀的话 {@code client_id} 和 {@code user_id} 在同一个空间里，
     * 一旦有人建了个叫 {@code U001} 的机器主体，权限就串了 ——
     * 而这种串不会报错，只会让一个机器拿到人的审批权限。
     */
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        String id = String.valueOf(loginId);
        if (id.startsWith(CLIENT_PREFIX)) {
            return apiClientService.permissionsOf(id.substring(CLIENT_PREFIX.length()));
        }
        if (id.startsWith(USER_PREFIX)) {
            return userAuthService.permissionsOf(id.substring(USER_PREFIX.length()));
        }
        // 不认识的前缀一律无权限 —— 宁可拒绝，也不要猜它是哪一类
        return List.of();
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        // 两种主体都不用角色 —— 能做什么完全由 scope 决定。
        // 角色是个额外的间接层，而这里的权限模型简单到不需要它
        return List.of();
    }
}
