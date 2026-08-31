package com.atp.web.auth;

import cn.dev33.satoken.stp.StpInterface;
import com.atp.platform.service.ApiClientService;
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

    @Autowired
    private ApiClientService apiClientService;

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return apiClientService.permissionsOf(String.valueOf(loginId));
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        // 机器主体不分角色 —— 它能做什么完全由 scope 决定，没有「管理员机器」这种东西
        return List.of();
    }
}
