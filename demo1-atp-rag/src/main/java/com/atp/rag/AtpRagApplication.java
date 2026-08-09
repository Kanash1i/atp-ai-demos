package com.atp.rag;

import com.atp.rag.config.AtpProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * ATP 知识助手。
 *
 * <p>一个 Spring Boot 2.7 + Java 8 的应用 —— 版本不是随便选的：
 * ATP 平台本身是 Java 8 + Spring 4 + MySQL 5.7 的遗留系统，
 * 给它做的模块只能停在各生态<b>最后一个支持 Java 8</b> 的版本上
 * （Spring Boot 2.7.18 / langchain4j 0.35.0）。详见 DECISIONS.md D-014。
 *
 * <p>没有 web starter —— 这是 CLI / 批处理应用，不对外提供 HTTP 端点。
 * 用 {@code --atp.task=xxx} 选择跑哪个任务：
 *
 * <pre>
 * mvn spring-boot:run -Dspring-boot.run.arguments=--atp.task=spike    # 环境自检
 * mvn spring-boot:run -Dspring-boot.run.arguments=--atp.task=ingest   # 语料入库
 * mvn spring-boot:run -Dspring-boot.run.arguments=--atp.task=demo     # 非交互跑批
 * mvn spring-boot:run -Dspring-boot.run.arguments=--atp.task=cli      # 交互式问答
 * mvn spring-boot:run -Dspring-boot.run.arguments=--atp.task=probe    # 检索对比探针
 * </pre>
 */
@SpringBootApplication
@EnableConfigurationProperties(AtpProperties.class)
public class AtpRagApplication {

    public static void main(String[] args) {
        SpringApplication.run(AtpRagApplication.class, args);
    }
}
