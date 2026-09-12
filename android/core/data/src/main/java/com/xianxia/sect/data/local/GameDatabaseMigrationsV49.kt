// GameDatabaseMigrationsV49.kt — 由 GameDatabase.kt 拆分生成（见 GameDatabase.kt addMigrations 列表）
package com.xianxia.sect.data.local

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Migration 文件共用的日志 TAG（各文件独立声明，避免 top-level 冲突） */
private const val TAG = "GameDatabase"

/**
 * v48→v49: game_data 新增"石板道路"列
 *
 * 背景：道路系统——玩家在宗门地图网格放置石板道路，程序按邻接位掩码自动拼接。
 * 道路数据（gridX/gridY/bitMask/roadType）经 CollectionConverters 以 Protobuf
 * Base64 序列化存入 TEXT 列（空列表编码后为空字符串，与 placedBuildings /
 * pending_trait_adds 等 List 列先例一致）。
 *
 * 仅 ALTER TABLE ADD COLUMN（新增列不删列，DEFAULT '' 兼容旧行）。
 */
internal val MIGRATION_48_49 = object : Migration(48, 49) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `game_data` ADD COLUMN `roads` TEXT NOT NULL DEFAULT ''")
        Log.i(TAG, "Migration 48→49: added roads column to game_data")
    }
}
