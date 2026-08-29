package com.atp.platform.service;

import com.atp.common.validation.ValidationResult;
import lombok.Getter;

/**
 * 规范校验没过，不允许提交。
 *
 * <p>⚠️ 带上完整的 {@link ValidationResult} 而不是一句话 ——
 * 调用方（前端要高亮到步骤行、agent 要据此自我修正）需要的是**每一条违反的明细**，
 * 只给「校验失败」的话，agent 只能瞎猜着改。
 */
@Getter
public class CaseValidationException extends RuntimeException {

    private final transient ValidationResult result;

    public CaseValidationException(ValidationResult result) {
        super("规范校验未通过：" + String.join("、", result.violatedCodes()));
        this.result = result;
    }
}
