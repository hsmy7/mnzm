// GameDatabaseMigrationsV65.kt — v64→v65 迁移（见 GameDatabase.kt ALL_MIGRATIONS）
package com.xianxia.sect.data.local

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Migration 文件共用的日志 TAG（各文件独立声明，避免 top-level 冲突） */
private const val TAG_V65 = "GameDatabase"

/**
 * v64→v65: AI 洞府探索队伍链退役（零消费休眠列删列）。
 *
 * 背景：`GameData.aiCaveTeams` 的唯一写入方（AI 洞府队伍生成逻辑）已随
 * W4-D/D5 死代码清零批删除，此后该字段零构造、零读写、C++ 零镜像——
 * 纯死存储。本迁移删除 game_data 与 world_map_state 双表的 `aiCaveTeams`
 * 列，模型链（AICaveTeam/AICaveDisciple/AIRandomEquipment/AIRandomManual/
 * AITeamStatus 及旧档 Serializable 镜像）同批删除；字段号 28 双侧标记
 * reserved 禁止复用。
 *
 * 实现：`rebuildTableDroppingColumns` PRAGMA 重建（create-copy-drop-rename，
 * 删列迁移唯一实现；两表各调一次，幂等——列不存在时直接返回）。
 * 数据无损：仅删死列，其余列原样保留。
 */
internal val MIGRATION_64_65 = object : Migration(64, 65) {
    override fun migrate(db: SupportSQLiteDatabase) {
        rebuildTableDroppingColumns(
            db = db,
            table = "game_data",
            columnsToDrop = listOf("aiCaveTeams"),
            pkColumns = listOf("id", "slot_id"),
            indices = listOf(
                Triple("index_game_data_slot_id", "game_data(`slot_id`)", true),
                Triple("index_game_data_lastSaveTime", "game_data(`lastSaveTime`)", false),
                Triple("index_game_data_gameYear_gameMonth", "game_data(`gameYear`, `gameMonth`)", false),
                Triple("index_game_data_sectName", "game_data(`sectName`)", false),
                Triple("index_game_data_spiritStones", "game_data(`spiritStones`)", false)
            )
        )
        rebuildTableDroppingColumns(
            db = db,
            table = "world_map_state",
            columnsToDrop = listOf("aiCaveTeams"),
            pkColumns = listOf("slot_id"),
            indices = listOf(
                Triple("index_world_map_state_slot_id", "world_map_state(`slot_id`)", true)
            )
        )
        Log.i(TAG_V65, "Migration 64→65: dropped aiCaveTeams from game_data and world_map_state")
    }
}
