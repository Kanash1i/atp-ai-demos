package com.atp.platform.service;

/** 案例不存在。由 web 层翻成 404 —— 不让 null 顺着调用链漏下去。 */
public class CaseNotFoundException extends RuntimeException {

    public CaseNotFoundException(String caseId) {
        super("案例不存在：" + caseId);
    }
}
