// GameDatabaseMigrationsV44.kt — 由 GameDatabase.kt 拆分生成（见 GameDatabase.kt addMigrations 列表）
package com.xianxia.sect.data.local

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Migration 文件共用的日志 TAG（各文件独立声明，避免 top-level 冲突） */
private const val TAG = "GameDatabase"

/**
 * v43→v44: 弟子炼丹师/锻造师职业 4 列（disciples 与 disciples_attributes 两表）
 *
 * 背景（职业系统）：弟子在炼丹/锻造槽位成功炼制出丹药/装备后晋升为职业弟子，
 * 职业等级决定可炼制品阶上限。SkillStats 新增 4 字段：
 * - alchemyLevel: 炼丹师职业等级（0=无职业, 1~5=炼丹师~丹圣）
 * - alchemyPromotionCount: 当前解锁最高阶的成功炼制次数（晋升用，低阶不计数）
 * - forgeLevel / forgePromotionCount: 炼器师同构
 *
 * 列命名与 Room 默认一致（字段名，无前缀——已核对 43.json schema：
 * disciples 与 disciples_attributes 两表技能列均为字段名直用）。
 * 仅 ALTER TABLE ADD COLUMN（新增列不删列，DEFAULT 0 兼容旧行）。
 */
internal val MIGRATION_43_44 = object : Migration(43, 44) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val addColumns = listOf(
            "alchemyLevel" to "INTEGER NOT NULL DEFAULT 0",
            "alchemyPromotionCount" to "INTEGER NOT NULL DEFAULT 0",
            "forgeLevel" to "INTEGER NOT NULL DEFAULT 0",
            "forgePromotionCount" to "INTEGER NOT NULL DEFAULT 0"
        )
        for (table in listOf("disciples", "disciples_attributes")) {
            for ((name, type) in addColumns) {
                db.execSQL("ALTER TABLE `$table` ADD COLUMN `$name` $type")
            }
        }
        Log.i(TAG, "Migration 43→44: added 8 profession columns to disciples & disciples_attributes")
    }
}
