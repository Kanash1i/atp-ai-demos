package com.atp.common.enums;

/**
 * 存 SMALLINT 的枚举的共同约定。
 *
 * <p>枚举一律存码不存名（见 demo2 的 DECISIONS D-112）：加一个新状态不需要任何 DDL，
 * 迁移脚本因此可以是单个原子事务。代价是 {@code SELECT *} 出来是数字 ——
 * 靠 {@code COMMENT ON COLUMN} 把映射写在列上补偿。
 *
 * <p>⚠️ 码值必须与 {@code demo2-atp-cli/internal/model/enums.go} 完全一致。
 * 两条路线写的是同一张表，码值对不上就是静默写坏数据。
 */
public interface CodedEnum {

    short code();
}
