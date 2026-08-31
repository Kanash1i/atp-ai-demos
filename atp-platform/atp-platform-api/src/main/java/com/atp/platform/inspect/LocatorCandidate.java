package com.atp.platform.inspect;

/**
 * 页面上一个可用的定位器候选 —— 探查的产物。
 *
 * <p>⚠️ 这里给出的 {@code locatorValue} 必须是**规范允许**的写法，
 * 否则 agent 照抄之后会被 STD 校验挡下，工具反而添乱：
 * 优先 {@code data-testid}（STD-003 的首选），其次 name / id，
 * 绝不产出绝对路径 XPath（STD-001 是 ERROR 档）。
 *
 * @param kind          元素类别：button / link / input / select / heading / testid
 * @param locatorType   XPATH / CSS / ID / NAME / LINK_TEXT
 * @param locatorValue  可直接填进案例步骤的定位器
 * @param text          可见文本，给模型判断"这是不是我要的那个元素"
 * @param note          补充说明，如 input 的 type、是否 disabled
 */
public record LocatorCandidate(
        String kind,
        String locatorType,
        String locatorValue,
        String text,
        String note) {
}
