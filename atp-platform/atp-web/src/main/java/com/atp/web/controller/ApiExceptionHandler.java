package com.atp.web.controller;

import com.atp.platform.service.ApprovalAlreadyDecidedException;
import com.atp.platform.service.ApprovalNotFoundException;
import com.atp.platform.service.CaseNotFoundException;
import com.atp.platform.service.TaskNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常出口。
 *
 * <p>用 Spring 6 的 {@link ProblemDetail}（RFC 7807）而不是自定义信封：
 * 前端和 agent 都能按标准字段解析，不用为这个项目单独写一套约定。
 */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler({CaseNotFoundException.class, ApprovalNotFoundException.class, TaskNotFoundException.class})
    public ProblemDetail notFound(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /**
     * 审批被并发抢先处理。
     *
     * <p>⚠️ 409 而不是 400 —— 请求本身没错，是状态变了。
     * 前端据此提示「刷新看看别人已经处理成什么了」，而不是让用户去改参数重试。
     */
    @ExceptionHandler(ApprovalAlreadyDecidedException.class)
    public ProblemDetail conflict(ApprovalAlreadyDecidedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /**
     * 兜底。
     *
     * <p>⚠️ 日志里打完整堆栈，返回给客户端的只有一句话 ——
     * 但**不是**「服务器开小差了」那种没信息量的提示：把异常类型带上，
     * 演示时看一眼响应就知道该去查哪一层。
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail unexpected(Exception e) {
        log.error("未处理的异常", e);
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                e.getClass().getSimpleName() + ": " + e.getMessage());
    }
}
