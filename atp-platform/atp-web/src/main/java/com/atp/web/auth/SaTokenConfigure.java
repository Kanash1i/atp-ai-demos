package com.atp.web.auth;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import com.atp.common.enums.ApiScope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 哪些接口需要窄 token。
 *
 * <h3>⚠️ 只保护 CLI 走的那几个，读接口一律放行</h3>
 *
 * 前端目前没有登录态，把读接口也保护上，M1 那套面板会立刻全白 ——
 * 而人的登录链路是另一件事（{@code sys_user} + 密码），不该塞进这次改动里。
 *
 * <h3>过渡开关</h3>
 *
 * {@code atp.auth.enabled} 默认关。因为 CLI 还没开始带 token，
 * 一上来就强制会让 agent 的探查与自验立刻全挂。
 * 顺序是：平台先具备能力 → CLI 改造 → 打开开关 → 然后 PG 才能上云。
 *
 * <p>⚠️ 这个开关是**过渡状态**，不是长期设计。CLI 改完就该删掉它 ——
 * 留着一个「一行配置就能关掉全部鉴权」的开关，本身就是个洞。
 */
@Slf4j
@Configuration
public class SaTokenConfigure implements WebMvcConfigurer {

    @Value("${atp.auth.enabled:false}")
    private boolean authEnabled;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (!authEnabled) {
            log.warn("[AUTH] 鉴权未启用（atp.auth.enabled=false）—— 过渡状态，CLI 改造完成后必须打开");
            return;
        }
        log.info("[AUTH] 鉴权已启用：写案例 / 自验 / 探查 需要窄 token");

        registry.addInterceptor(new SaInterceptor(handle -> {
            // 写案例：draft / update / commit
            SaRouter.match("/api/cases/draft").check(() -> StpUtil.checkPermission(ApiScope.CASE_WRITE.code()));
            SaRouter.match("/api/cases/*/draft").check(() -> StpUtil.checkPermission(ApiScope.CASE_WRITE.code()));
            SaRouter.match("/api/cases/*/commit").check(() -> StpUtil.checkPermission(ApiScope.CASE_WRITE.code()));

            // 跑单条自验。⚠️ 与派发批次是两个权限 —— 后者是平台调度权，默认不发给客户端
            SaRouter.match("/api/executions/run-once").check(() -> StpUtil.checkPermission(ApiScope.EXEC_RUN_ONCE.code()));

            // 页面探查
            SaRouter.match("/api/inspect/**").check(() -> StpUtil.checkPermission(ApiScope.INSPECT.code()));
        })).addPathPatterns("/**");
    }
}
