package com.atp.web.controller;

import com.atp.platform.service.ApprovalAlreadyDecidedException;
import com.atp.platform.service.ApprovalNotFoundException;
import com.atp.platform.service.CaseConflictException;
import com.atp.platform.service.CaseHeaderIncompleteException;
import com.atp.platform.service.CaseNotFoundException;
import com.atp.platform.service.CaseValidationException;
import com.atp.platform.service.TaskNotFoundException;
import lombok.extern.slf4j.Slf4j;
import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
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

    /**
     * RFC 7807 {@code type} 的前缀。
     *
     * <p>它是一个**标识符**，不是要去访问的地址 —— 标准明确允许 type URI 不可解引用。
     * 用它是为了让「问题分类」有个稳定的机器可读的名字，而不是让调用方去解析 detail 文案。
     */
    private static final String PROBLEM_BASE = "https://atp.example/problems/";


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

    /**
     * 编辑期的版本冲突。
     *
     * <p>与审批的并发冲突同样是 409 —— 请求没错，是状态变了。
     */
    /**
     * 编辑期并发冲突 → 409，**用 `type` 区分两种**。
     *
     * <p>两种冲突的处置完全相反：版本不对要「重来一遍」，状态不对要「别再试了」。
     * 都发 409 而只让 detail 文案不同的话，调用方要区分就只能匹配中文字符串 ——
     * 那是把机器契约挂在人类文案上，改个措辞对方就静默错判，测试还全绿。
     *
     * <p>RFC 7807 的 {@code type} 字段本来就是干这个的（机器可读的问题分类），
     * 所以不需要自造约定。调用方按 type 分派，我改 detail 文案不影响它。
     */
    @ExceptionHandler(CaseConflictException.class)
    public ProblemDetail caseConflict(CaseConflictException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setType(URI.create(PROBLEM_BASE + e.kind().slug()));
        return pd;
    }

    /**
     * 规范校验未通过。
     *
     * <p>⚠️ 用 **422 Unprocessable Entity** 而不是 400：请求格式完全正确，
     * 是内容不符合业务规则。而且要把**每一条违反的明细**带回去 ——
     * 前端要高亮到具体步骤行，agent 要据此自我修正，只给一句「校验失败」的话它只能瞎改。
     */
    @ExceptionHandler(CaseValidationException.class)
    public ProblemDetail caseInvalid(CaseValidationException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        pd.setProperty("violatedCodes", e.getResult().violatedCodes());
        pd.setProperty("findings", e.getResult().findings().stream()
                .map(f -> java.util.Map.of(
                        "std", f.std().display(),
                        "severity", f.severity().name(),
                        "seq", f.seq() == null ? -1 : f.seq(),
                        "message", f.message()))
                .toList());
        pd.setType(URI.create(PROBLEM_BASE + "validation-failed"));
        return pd;
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
    /**
     * 没带 token / token 无效 → 401。
     *
     * <p>⚠️ 必须写在 {@code Exception.class} 那个兜底之前 —— 否则 Sa-Token 的异常
     * 会被当成服务器内部错误返回 500，调用方（尤其是 CLI）就分不清
     * 「我没登录」和「平台挂了」，前者该去换 token，后者该重试或报警。
     */
    /**
     * 提交时表头字段不全 → 422。
     *
     * <p>不拦的话数据库的 {@code ck_case_complete} 会抛
     * {@code DataIntegrityViolationException}，兜底成 500 ——
     * 而 500 让调用方以为是后端崩了，实际上是自己少填了字段。
     */
    @ExceptionHandler(CaseHeaderIncompleteException.class)
    public ProblemDetail headerIncomplete(CaseHeaderIncompleteException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        pd.setProperty("missingFields", e.missing());
        pd.setType(URI.create(PROBLEM_BASE + "header-incomplete"));
        return pd;
    }

    @ExceptionHandler(NotLoginException.class)
    public ProblemDetail notLogin(NotLoginException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                "缺少或无效的 Authorization: Bearer <token>，先调 POST /api/auth/token 换取");
    }

    /** token 有效但权限不够 → 403。与 401 分开：一个该去换 token，一个换了也没用 */
    @ExceptionHandler(NotPermissionException.class)
    public ProblemDetail notPermission(NotPermissionException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                "当前 token 没有 " + e.getPermission() + " 权限");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail unexpected(Exception e) {
        log.error("未处理的异常", e);
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                e.getClass().getSimpleName() + ": " + e.getMessage());
    }
}
