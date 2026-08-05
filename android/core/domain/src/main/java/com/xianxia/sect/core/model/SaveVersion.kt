package com.xianxia.sect.core.model

/**
 * 存档数据版本号权威定义（domain 层，engine/data 共用）。
 *
 * 迁移链定义于 `core/data` 的 [com.xianxia.sect.data.migration.SaveDataVersionMigrator]：
 * - v0→1：修炼基础值等比缩小为 1/10（v4.0.13）
 * - v1→2：所有 sectRelations 的 acquainted 置 true
 *
 * engine 模块（创建新档/重启）与 data 模块（保存/迁移）都必须引用本常量
 * 盖章或比较，禁止硬编码版本号——防止"新档未盖章被误迁移"类缺陷复发。
 */
object SaveVersion {
    /** 当前存档数据版本号（迁移链终点），须与 SaveDataVersionMigrator 同步 */
    const val CURRENT = 2
}
