package com.atp.platform.vo;

import java.util.List;

/**
 * 规范校验结果 —— 前端案例详情页的「规范校验」区块。
 *
 * @param passed       能不能保存。只有 ERROR 拦人
 * @param violatedCodes 违反的规范编号（STD chips）
 * @param findings     逐条明细，带步骤序号，前端可以高亮到具体那一行
 */
public record ValidationVO(
        boolean passed,
        long errorCount,
        long warnCount,
        long infoCount,
        List<String> violatedCodes,
        List<FindingVO> findings
) {

    /** @param seq 出问题的步骤序号；案例级问题（如编号不合规）为 null */
    public record FindingVO(String std, String severity, Integer seq, String message) {
    }
}
