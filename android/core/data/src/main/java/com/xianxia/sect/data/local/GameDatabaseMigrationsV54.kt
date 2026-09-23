// GameDatabaseMigrationsV54.kt — v53→v54 迁移（见 GameDatabase.kt ALL_MIGRATIONS）
package com.xianxia.sect.data.local

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Migration 文件共用的日志 TAG（各文件独立声明，避免 top-level 冲突） */
private const val TAG = "GameDatabase"

/**
 * v53→v54: 角色卡池 G01 协议列（纯加法 ADD COLUMN，兼容旧行；schema JSON 由 KSP 导出）。
 *
 * - `disciples.templateId`：角色模板 id（Q32 只读）；存量旧弟子默认 `''`
 * - `game_data.gacha_fragment_counts` / `gacha_star_map` / `gacha_pity_counters` / `gacha_history`
 *   碎片/星级/保底/历史；旧档默认空 map/list
 */
internal val MIGRATION_53_54 = object : Migration(53, 54) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE disciples ADD COLUMN templateId TEXT NOT NULL DEFAULT ''"
        )
        db.execSQL(
            "ALTER TABLE game_data ADD COLUMN gacha_fragment_counts TEXT NOT NULL DEFAULT '{}'"
        )
        db.execSQL(
            "ALTER TABLE game_data ADD COLUMN gacha_star_map TEXT NOT NULL DEFAULT '{}'"
        )
        db.execSQL(
            "ALTER TABLE game_data ADD COLUMN gacha_pity_counters TEXT NOT NULL DEFAULT '{}'"
        )
        db.execSQL(
            "ALTER TABLE game_data ADD COLUMN gacha_history TEXT NOT NULL DEFAULT '[]'"
        )
        Log.i(TAG, "v53→v54 完成：gacha 协议列 + disciples.templateId")
    }
}
