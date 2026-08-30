package com.atp.agent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * 集成测试用的最小上下文：只装配 agent 需要的东西
 * （模型、RAG、写侧服务、持久层），不起 web 层。
 */
@SpringBootApplication
@ComponentScan(basePackages = {"com.atp.agent", "com.atp.rag", "com.atp.platform"})
@MapperScan("com.atp.platform.mapper")
public class AgentTestApp {
}
