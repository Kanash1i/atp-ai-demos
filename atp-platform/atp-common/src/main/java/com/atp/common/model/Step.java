package com.atp.common.model;

import com.atp.common.enums.ActionType;
import com.atp.common.enums.LocatorType;
import com.atp.common.enums.OnFailure;
import com.atp.common.enums.WaitStrategy;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 一个步骤 —— {@code tc_step.step_json} 数组里的一个元素。
 *
 * <p>⚠️ 顺序由 {@link #seq} 这个 key 承载，**不抽成列**。
 * 老平台的执行器读整份步骤跑，不会按 seq 逐条查库，抽出来只有「一步一行」时才有意义。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Step(
        String stepId,
        String caseId,
        int seq,
        ActionType action,
        LocatorType locatorType,
        String locatorValue,
        String inputData,
        String expected,
        WaitStrategy waitStrategy,
        Integer waitTimeoutSec,
        OnFailure onFailure,
        String description
) {

    public boolean hasLocator() {
        return locatorValue != null && !locatorValue.isBlank();
    }

    public boolean hasInput() {
        return inputData != null && !inputData.isBlank();
    }

    public boolean hasExpected() {
        return expected != null && !expected.isBlank();
    }
}
