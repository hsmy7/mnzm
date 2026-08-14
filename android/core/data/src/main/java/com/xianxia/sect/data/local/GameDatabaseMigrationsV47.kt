// GameDatabaseMigrationsV47.kt — 由 GameDatabase.kt 拆分生成（见 GameDatabase.kt addMigrations 列表）
package com.xianxia.sect.data.local

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Migration 文件共用的日志 TAG（各文件独立声明，避免 top-level 冲突） */
private const val TAG = "GameDatabase"

/**
 * v46→v47: game_data 新增"新增天赋/体质/词条"待确认产物列
 *
 * 背景（2026-08-15 玉符消耗玩法扩展）：新增天赋/体质/词条时，刷新（消耗 1 玉符）
 * 的产物必须**持久化**——玩家不确认直接关闭界面，下次打开仍显示该产物并可直接确认新增。
 * 产物按（discipleId + 类型 → traitId）存为 List[PendingTraitAdd]，经
 * ProtobufConverters 序列化为 Base64 存入 TEXT 列（空列表编码后为空字符串，
 * 与 watchedItemIds/mailRecords 等 List 列先例一致，见 MIGRATION_36_37 注释）。
 *
 * 仅 ALTER TABLE ADD COLUMN（新增列不删列，DEFAULT '' 兼容旧行）。
 */
internal val MIGRATION_46_47 = object : Migration(46, 47) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `game_data` ADD COLUMN `pending_trait_adds` TEXT NOT NULL DEFAULT ''")
        Log.i(TAG, "Migration 46→47: added pending_trait_adds column to game_data")
    }
}
