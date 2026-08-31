package com.atp.platform;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * 并发测试的最小上下文：只要写侧服务与持久层。
 *
 * <p>不装 agent、RAG、web —— 那些要 LLM key、要 TEI、要端口，
 * 而这里验的是数据库层面的并发仲裁，跟它们一点关系都没有。
 * 上下文越小，测试失败时的怀疑面就越小。
 */
@SpringBootApplication(exclude = RedisAutoConfiguration.class)
@ComponentScan(basePackages = "com.atp.platform",
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                // 探查队列与执行派发都要 Redis，与写侧的并发仲裁无关，排掉省得起不来。
                // ⚠️ 排除的是**依赖 Redis 的那几个**，不是"凡是没用到的都排" ——
                //    上下文越小怀疑面越小，但排过头会让测试环境和真实环境产生看不见的差异
                pattern = "com\\.atp\\.platform\\.(inspect|exec)\\..*"
                        + "|com\\.atp\\.platform\\.service\\.(PageInspect|RunOnce)Service"))
@MapperScan("com.atp.platform.mapper")
public class PlatformTestApp {
}
