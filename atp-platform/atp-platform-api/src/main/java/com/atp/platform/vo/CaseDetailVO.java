package com.atp.platform.vo;

import com.atp.common.model.Step;

import java.util.List;

/**
 * 案例详情 —— 前端右侧那一屏。
 *
 * <p>⚠️ 这里**没有 browser 与 timeout_sec**：browser 是执行参数（在 exec_run / exec_task 上），
 * timeout_sec 没有消费方。设计稿详情页的这两格要相应调整。
 *
 * <h3>⚠️ 命名风格是混的，这是刻意的</h3>
 *
 * 本 VO 的字段是 camelCase（{@code caseCode}），但 {@code steps} 里是 snake_case
 * （{@code locator_type} / {@code wait_strategy}）—— 因为 {@link Step} 直接就是
 * {@code tc_step.step_json} 里的形状，而那个形状是**跨四个组件的共享契约**：
 * 平台、agent、demo2 的 Go CLI、Playwright 执行器读写的是同一份 JSON。
 *
 * <p>在表示层把它转成 camelCase，等于让 API 返回的步骤和库里存的步骤长得不一样 ——
 * 调试时要多做一次心算，agent 生成的 JSON 也要多一次映射。
 * 宁可命名风格不统一，也不要让同一份数据有两种形状。**前端按 snake_case 读 steps。**
 */
public record CaseDetailVO(
        String caseId,
        String caseCode,
        String title,
        String moduleId,
        String moduleCode,
        String moduleName,
        String projectId,
        String priority,
        String status,
        String caseType,
        String author,
        String precondition,
        String updatedAt,
        /**
         * {@code tc_case.version} —— 平台版本，编辑期全程不动。
         * ⚠️ **不要拿它去调 PUT/POST**，那两个要的是 {@link #editVersion}。
         */
        int version,
        /**
         * {@code tc_step.version} —— 编辑期的乐观锁版本，写侧三个接口用的就是它。
         *
         * <p>⚠️ 两个 version 不是一回事：草稿在编辑期反复改，{@code tc_case.version} 一直是 0。
         * 早先这里只返回前者，前端拿它去 PUT 必然 409 ——
         * 而 409 的文案说「内容被别人改过」，把人指向了完全错误的方向。
         */
        int editVersion,
        List<Step> steps,
        ValidationVO validation
) {
}
