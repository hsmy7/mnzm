// GameDatabaseMigrationsV50.kt — v49→v50 迁移（见 GameDatabase.kt addMigrations 列表）
package com.xianxia.sect.data.local

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Migration 文件共用的日志 TAG（各文件独立声明，避免 top-level 冲突） */
private const val TAG = "GameDatabase"

/**
 * v49→v50: 自动存档残留清理——删除 game_data / sect_policy_state 的 autoSaveIntervalMonths 列
 *
 * 背景：纯手动存档为既定产品设计，本条为残留彻底清理。自动存档机制
 * 已从产品移除，但 autoSaveIntervalMonths 列仍残留
 * 于两张表：game_data 与 sect_policy_state。本次删除该列，杜绝后续被误判为功能缺失。
 *
 * SQLite < 3.35.0（API24 内置 3.9）不支持 DROP COLUMN（规则 7.2），故采用
 * create-copy-drop-rename 重建表。
 *
 * ⚠️ 关键：不能复用 [rebuildGameData] / GAME_DATA_CREATE_SQL——那是 v29 历史基线，
 * 用它重建会丢弃 v29 之后新增的 21 列（roads、battle_teams、jade_*、secret_realm_*、
 * soundEnabled、musicEnabled、pending_trait_adds 等）。本迁移：
 *  1. 读旧表 PRAGMA table_info 的全部列（name/type/notnull/dflt_value/pk）
 *  2. 剔除 autoSaveIntervalMonths，用逐列重建 CREATE TABLE（保留约束与默认值）
 *  3. 逐列名 INSERT SELECT 复制数据 → 删旧表 → 重命名 → 重建索引
 *
 * 旧档兼容：GameData / SectPolicyState 实体上的 autoSaveIntervalMonths 为 @Ignore +
 * @Transient——新档不再写入；旧档经 lenient 解码（ignoreUnknownKeys=true）跳过该字段仍可读。
 * 数据无损：被删列无消费方（纯手动存档），不迁移业务数据。
 */
internal val MIGRATION_49_50 = object : Migration(49, 50) {
    override fun migrate(db: SupportSQLiteDatabase) {
        rebuildDroppingColumn(
            db = db,
            table = "game_data",
            columnToDrop = "autoSaveIntervalMonths",
            pkColumns = listOf("id", "slot_id"),
            indices = listOf(
                Triple("index_game_data_slot_id", "game_data(`slot_id`)", true),
                Triple("index_game_data_lastSaveTime", "game_data(`lastSaveTime`)", false),
                Triple("index_game_data_gameYear_gameMonth", "game_data(`gameYear`, `gameMonth`)", false),
                Triple("index_game_data_sectName", "game_data(`sectName`)", false),
                Triple("index_game_data_spiritStones", "game_data(`spiritStones`)", false)
            )
        )
        Log.i(TAG, "Migration 49→50: dropped autoSaveIntervalMonths from game_data")

        rebuildDroppingColumn(
            db = db,
            table = "sect_policy_state",
            columnToDrop = "autoSaveIntervalMonths",
            pkColumns = listOf("slot_id"),
            indices = listOf(
                Triple("index_sect_policy_state_slot_id", "sect_policy_state(`slot_id`)", true)
            )
        )
        Log.i(TAG, "Migration 49→50: dropped autoSaveIntervalMonths from sect_policy_state")
    }

    /**
     * 用 create-copy-drop-rename 重建指定表并删除一列。
     *
     * 实现已收敛到 [rebuildTableDroppingColumns]（B19 删列批抽取的多列通用实现，
     * 语义与 v50 原私有实现逐字等价：待删列不存在即返回、PRAGMA 逐列重建、
     * INSERT SELECT 复制、重建索引）；本函数保留为单列调用点的语义化入口。
     */
    private fun rebuildDroppingColumn(
        db: SupportSQLiteDatabase,
        table: String,
        columnToDrop: String,
        pkColumns: List<String>,
        indices: List<Triple<String, String, Boolean>>
    ) = rebuildTableDroppingColumns(
        db = db,
        table = table,
        columnsToDrop = listOf(columnToDrop),
        pkColumns = pkColumns,
        indices = indices
    )
}
