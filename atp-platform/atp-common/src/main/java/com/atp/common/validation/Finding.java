package com.atp.common.validation;

import com.atp.common.enums.StdCode;

/**
 * 一条校验发现。
 *
 * @param std     违反的规范
 * @param seq     出问题的步骤序号；案例级问题（如 STD-007 编号不合规）为 {@code null}
 * @param message 给人看的说明。**要具体到能直接改** —— 「XPath 用了绝对路径」不如
 *                「第 2 步的 XPath 以 /html/body 开头，元素位置一变就失效」
 */
public record Finding(StdCode std, Integer seq, String message) {

    public StdCode.Severity severity() {
        return std.severity();
    }
}
