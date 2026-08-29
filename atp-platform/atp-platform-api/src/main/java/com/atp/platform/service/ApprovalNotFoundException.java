package com.atp.platform.service;

public class ApprovalNotFoundException extends RuntimeException {

    public ApprovalNotFoundException(String requestId) {
        super("审批请求不存在：" + requestId);
    }
}
