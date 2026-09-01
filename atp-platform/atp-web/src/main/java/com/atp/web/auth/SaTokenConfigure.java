package com.atp.web.auth;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import com.atp.common.enums.ApiScope;
import lombok.extern.slf4j.Slf4j;
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
 * <h3>没有开关，这是有意的</h3>
 *
 * 迁移期间这里有过一个 {@code atp.auth.enabled}，因为那时 CLI 还直连数据库、
 * 不会带 token，强制鉴权会让 agent 的探查与自验立刻全挂。
 *
 * <p>CLI 完成迁移之后（2026-09-01）它被**删掉**了，不是设成默认 true ——
 * 留着一行能关掉全部鉴权的配置本身就是那个洞：
 * **它不会被审计发现（配置看起来完全正常），只会在某次排查问题时被人临时关掉，然后忘了打开。**
 *
 * <p>要临时绕过鉴权只能改代码，而改代码会留在 diff 里。
 */
@Slf4j
@Configuration
public class SaTokenConfigure implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
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

            // ⭐ 审批决策 —— 只有人拿得到这个 scope。
            //    agent 能写案例、能自验，但「这条变更该不该放行」是人的判断；
            //    发给机器等于让 agent 自己批准自己提交的东西
            SaRouter.match("/api/approvals/*/decision")
                    .check(() -> StpUtil.checkPermission(ApiScope.APPROVAL_DECIDE.code()));
        })).addPathPatterns("/**");
    }
}
