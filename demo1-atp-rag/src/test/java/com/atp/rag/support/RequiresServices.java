package com.atp.rag.support;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记需要服务机（TEI + Qdrant）才能跑的测试，服务不可用时<b>跳过而不是失败</b>。
 *
 * <p>为什么需要它：改成 Spring Boot 之后，{@code QdrantClient} bean 在构造时就会去连
 * Qdrant 校验版本（这是刻意的，见 D-002）。所以没连服务机时，
 * 整个 {@code @SpringBootTest} 上下文都起不来 —— 用 {@code Assumptions} 已经来不及，
 * 那是在上下文加载之后才执行的。
 *
 * <p>必须在<b>上下文加载之前</b>判断，所以用 JUnit 5 的 {@code ExecutionCondition}。
 *
 * <p>设计取舍：没连服务机时 {@code mvn test} 仍然要能过（纯逻辑测试有 40 多项），
 * 否则开发时每次都得连着服务机。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(ServicesAvailableCondition.class)
public @interface RequiresServices {
}
