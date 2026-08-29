package com.atp.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

/** 审批类型。与前端审批中心的三类卡片一一对应。 */
public enum ApprovalType implements CodedEnum {

    /** 规范例外：申请对某条 STD 破例（如暂留 SLEEP），带到期日 */
    RULE_EXCEPTION((short) 1),
    /** 案例变更：payload 里存整包 before/after，审批页要算 diff */
    CASE_CHANGE((short) 2),
    /** 数据集发布：语料集推到生产索引 */
    DATASET_RELEASE((short) 3);

    @EnumValue
    private final short code;

    ApprovalType(short code) {
        this.code = code;
    }

    @Override
    public short code() {
        return code;
    }
}
