package com.atp.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 开发期的跨域放行。
 *
 * <p>前端是独立的 Vite 应用（默认 5173），与后端 8080 不同源 ——
 * 不放行的话浏览器会直接拦下预检请求，而且报错在控制台里，
 * 看起来像"接口挂了"，其实后端连日志都不会有一行。
 *
 * <p>⚠️ 允许的来源从配置读，默认只放开发端口。
 * 部署时前端由 nginx 反代到同源，这条配置就不该再放开 ——
 * 到时候把 {@code atp.cors.allowed-origins} 设成空即可。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Value("${atp.cors.allowed-origins:http://localhost:5173,http://localhost:4173,http://localhost:3000,http://127.0.0.1:5173}")
    private String[] allowedOrigins;

    @Override
    public void addCorsMappings(@NonNull CorsRegistry registry) {
        if (allowedOrigins.length == 0 || (allowedOrigins.length == 1 && allowedOrigins[0].isBlank())) {
            return;
        }
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                // Sa-Token 的 token 走 Authorization 头，登录接上后前端要能读到响应头
                .exposedHeaders("Authorization")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
