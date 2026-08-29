package com.atp.platform.service;

/**
 * 编辑期的并发冲突。
 *
 * <p>⚠️ 这是 **409**，不是 400：请求本身没错，是内容在你确认之后被别人改过了。
 * 报错必须说清「库里是哪个版本、你手上是哪个」—— 只说「操作失败」的话，
 * 用户只会再点一次，而再点一次仍然会失败。
 */
public class CaseConflictException extends RuntimeException {

    public CaseConflictException(String message) {
        super(message);
    }
}
