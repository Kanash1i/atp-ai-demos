package com.atp.common.enums;

/** 步骤失败后的处置。同样住在 step_json 里，存字符串。 */
public enum OnFailure {

    /** 中止整条案例 */
    ABORT,
    /** 记下失败，继续跑后面的步骤 */
    CONTINUE
}
