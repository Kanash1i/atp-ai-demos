package com.atp.platform.service;

import java.util.List;

/**
 * 提交时案例的表头字段不全。
 *
 * <h3>为什么要单独拦这一下，而不是让数据库去撞</h3>
 *
 * {@code tc_case} 上有 {@code ck_case_complete} 约束：一离开编辑期，六个字段必须齐。
 * 缺字段时数据库确实会拦住，但抛出来的是
 * {@code DataIntegrityViolationException: violates check constraint "ck_case_complete"}，
 * Spring 兜底成 **500**。
 *
 * <p>500 的意思是「服务端出错了」——而实际上是调用方少填了字段。
 * 前端接契约时就撞过这个：堆栈长得像后端崩了，只能去翻源码才知道要补什么。
 *
 * <p>⚠️ 注意 **agent 走的那条路径撞不到这个**：CLI 的 JSON Schema 把六个字段
 * 全标了 required，缺了在本地就被拦下。所以这个洞只在「前端直连 REST」这条路上 ——
 * **两条路径的防护不对等，正是这类问题的温床**。
 */
public class CaseHeaderIncompleteException extends RuntimeException {

    private final List<String> missing;

    public CaseHeaderIncompleteException(List<String> missing) {
        super("案例的必填字段缺失：" + String.join("、", missing)
                + "。它们是 draftJson 顶层的键（snake_case），提交时会被投影进案例表");
        this.missing = missing;
    }

    public List<String> missing() {
        return missing;
    }
}
