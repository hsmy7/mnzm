// GameDatabaseMigrationsV61.kt — v60→v61 迁移（见 GameDatabase.kt ALL_MIGRATIONS）
package com.xianxia.sect.data.local

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Migration 文件共用的日志 TAG（各文件独立声明，避免 top-level 冲突） */
private const val TAG_V61 = "GameDatabase"

/** v60→v61 删除的 disciples 列（死值退役，结算改造方案 §9.1 缺陷 #10） */
internal val V61_DISCIPLES_DROPPED_COLUMNS = listOf("cultivationCompletionPhase")

/**
 * v60→v61: `cultivationCompletionPhase` 死值退役（结算改造 2026-09-27 B9，
 * 方案 §9.1 缺陷 #10）。
 *
 * 该列自 v39 起由 C++ 硬编码恒写 1（`phase_settlement.h` 完成时间预估段），
 * 无任何读取方——ProtoNumber(95) + Room 列 + C++ 镜像三重承载纯协议成本。
 * 本批全链除名：C++ Disciple 字段/列存储/脏列导出/GameView 协议行、Kotlin
 * Entity 字段/Proto surrogate/DiscipleTables 列/GameView 行编解码同批删除。
 *
 * ## 实现与安全
 * [rebuildTableDroppingColumns] create-copy-drop-rename 重建 disciples 表
 * （列定义/索引按 v60 现状重建，仅去目标列）；升级前由
 * `GameDatabase.backupDatabaseForMigration` 落 `.pre_migrate_backup.v60` 文件级
 * 备份，降级依赖该备份恢复。旧档中恒 1 的值删除零信息损失（无存续读取方）。
 * 61.json 由 KSP 自动导出。
 */
internal val MIGRATION_60_61 = object : Migration(60, 61) {
    override fun migrate(db: SupportSQLiteDatabase) {
        rebuildTableDroppingColumns(
            db = db,
            table = "disciples",
            columnsToDrop = V61_DISCIPLES_DROPPED_COLUMNS,
            pkColumns = listOf("id", "slot_id"),
            indices = listOf(
                Triple("index_disciples_name", "disciples(`name`)", false),
                Triple("index_disciples_realm_realmLayer", "disciples(`realm`, `realmLayer`)", false),
                Triple("index_disciples_isAlive_realm", "disciples(`isAlive`, `realm`)", false),
                Triple("index_disciples_isAlive_status", "disciples(`isAlive`, `status`)", false),
                Triple("index_disciples_discipleType", "disciples(`discipleType`)", false)
            )
        )
        Log.i(TAG_V61, "Migration 60→61: dropped ${V61_DISCIPLES_DROPPED_COLUMNS.size} column from disciples")
    }
}
