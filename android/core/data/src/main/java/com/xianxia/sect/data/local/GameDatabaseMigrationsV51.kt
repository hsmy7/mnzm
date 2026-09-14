// GameDatabaseMigrationsV51.kt — v50→v51 迁移（见 GameDatabase.kt addMigrations 列表）
package com.xianxia.sect.data.local

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Migration 文件共用的日志 TAG（各文件独立声明，避免 top-level 冲突） */
private const val TAG = "GameDatabase"

/**
 * v50→v51: 地图冻结（WS-5b）——game_data 新增地形段两列
 *
 * 背景：地形从"种子确定性重生"改为"生成即数据、存的地形恒优先"。
 * 生成器版本演进时老档地图冻结不变（老档老地图、新档新地图），
 * 无需发版协调。两列：
 *  - map_gen_version：地形生成器版本戳（0 = 无段；生成回填时戳
 *    GameConfig.SectMap.MAP_GEN_VERSION）
 *  - terrain_tiles：行主序 flat 瓦片段（CollectionConverters.intList 编码，
 *    TEXT；'' = 无段）
 *
 * 判定口径："存的地形恒优先"——terrainTiles 非空即直接采用（跨版本冻结，
 * 不重算）；仅无段才按 mapSeed + 当前生成器版本生成（boot 回填 /
 * C++ importStateInternal 归一化族 ensureTerrainGenerated，两端同源确定性）。
 *
 * 旧档兼容：新列带 DEFAULT 兜底，旧行读出 mapGenVersion=0 + terrainTiles 空
 * ⇒ 走"无段生成 + 回填"路径（零回归）。仅 ALTER TABLE ADD COLUMN
 *（规则：禁止 ALTER TABLE DROP COLUMN）。
 */
internal val MIGRATION_50_51 = object : Migration(50, 51) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `game_data` ADD COLUMN `map_gen_version` INTEGER NOT NULL DEFAULT 0"
        )
        db.execSQL(
            "ALTER TABLE `game_data` ADD COLUMN `terrain_tiles` TEXT NOT NULL DEFAULT ''"
        )
        Log.i(TAG, "Migration 50→51: added map_gen_version / terrain_tiles columns to game_data")
    }
}
