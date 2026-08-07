// GameDatabaseMigrationsV42.kt — 由 GameDatabase.kt 拆分生成（见 GameDatabase.kt addMigrations 列表）
package com.xianxia.sect.data.local

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Migration 文件共用的日志 TAG（各文件独立声明，避免 top-level 冲突） */
private const val TAG = "GameDatabase"

/**
 * v41→v42: game_data 新增玉符（氪金货币）4 列
 *
 * 背景：玉符按真实前台游玩时长发放（每 20 分钟 1 枚、单日上限 30、次日凌晨 12 点重置），
 * 为墙钟货币（与游戏时间解耦），不占仓库、无品阶。发放/跨天重置/循环停止/存档快照时低频写入。
 *
 * 仅 ADD COLUMN（不删列，无需 create-copy-drop-rename）：
 * - jade_symbols INTEGER NOT NULL DEFAULT 0        （持有数量）
 * - jade_symbols_today INTEGER NOT NULL DEFAULT 0  （今日已获得）
 * - jade_day_anchor_ms INTEGER NOT NULL DEFAULT 0  （今日午夜锚点 epoch ms）
 * - jade_accum_ms INTEGER NOT NULL DEFAULT 0       （当前周期已累计毫秒）
 *
 * 列定义与 GameData 实体注解一致，由 RoomMigrationTest 的真实 Room 打开
 * 触发 onValidateSchema 校验（对齐 v40→v41 测试模式）。
 */
internal val MIGRATION_41_42 = object : Migration(41, 42) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE game_data ADD COLUMN jade_symbols INTEGER NOT NULL DEFAULT 0"
        )
        db.execSQL(
            "ALTER TABLE game_data ADD COLUMN jade_symbols_today INTEGER NOT NULL DEFAULT 0"
        )
        db.execSQL(
            "ALTER TABLE game_data ADD COLUMN jade_day_anchor_ms INTEGER NOT NULL DEFAULT 0"
        )
        db.execSQL(
            "ALTER TABLE game_data ADD COLUMN jade_accum_ms INTEGER NOT NULL DEFAULT 0"
        )
        Log.i(TAG, "Migration 41→42: added 4 jade columns to game_data")
    }
}
