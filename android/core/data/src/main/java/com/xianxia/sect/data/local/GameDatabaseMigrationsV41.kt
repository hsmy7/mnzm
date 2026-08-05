// GameDatabaseMigrationsV41.kt — 由 GameDatabase.kt 拆分生成（见 GameDatabase.kt addMigrations 列表）
package com.xianxia.sect.data.local

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Migration 文件共用的日志 TAG（各文件独立声明，避免 top-level 冲突） */
private const val TAG = "GameDatabase"

/**
 * v40→v41: game_data 新增 last_ai_sect_recruit_year 列（AI 宗门弟子三年一度招募差值判据）
 *
 * 背景：AI 宗门弟子招募改为每 3 年一次（差值判据，与 refreshRecruitList 同款语义），
 * 需持久化上次触发年份以便老存档相位漂移自愈与失败次年重试。
 *
 * 仅 ADD COLUMN（不删列，无需 create-copy-drop-rename）：
 * - last_ai_sect_recruit_year INTEGER NOT NULL DEFAULT 0
 *
 * 列定义与 GameData 实体注解一致，由 RoomMigrationTest 的真实 Room 打开
 * 触发 onValidateSchema 校验（对齐 v39→v40 测试模式）。
 */
internal val MIGRATION_40_41 = object : Migration(40, 41) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE game_data ADD COLUMN last_ai_sect_recruit_year INTEGER NOT NULL DEFAULT 0"
        )
        Log.i(TAG, "Migration 40→41: added last_ai_sect_recruit_year to game_data")
    }
}
