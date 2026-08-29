package com.atp.platform.vo;

import java.util.List;

/**
 * 模块节点 —— 树里可展开的一层。
 *
 * @param caseCount 该模块下的案例数。⚠️ 是数据库里的真实条数，不是 {@code cases.size()} ——
 *                  将来列表分页了，展开只加载前 N 条，计数仍要显示全量
 */
public record ModuleNodeVO(
        String moduleId,
        String moduleCode,
        String moduleName,
        int caseCount,
        List<CaseSummaryVO> cases
) {
}
