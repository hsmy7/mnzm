// GameDatabaseMigrationsV40.kt — 由 GameDatabase.kt 拆分生成（见 GameDatabase.kt addMigrations 列表）
package com.xianxia.sect.data.local

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Migration 文件共用的日志 TAG（各文件独立声明，避免 top-level 冲突） */
private const val TAG = "GameDatabase"

/**
 * v39→v40: game_data 新增战斗队伍持久化三列（A3，2026-08-05）
 *
 * 背景：battleTeams/usedTeamNumbers 此前为 @Ignore+@Transient 不落盘——
 * 读档后玩家出战队伍全清、DiscipleStatusService"在队中"状态判定失效。
 * 现持久化（Room 列 + proto 字段），并新增 battleTeamsInitialized
 * 区分"旧档空列表"（读档走默认队伍初始化）与"玩家明确清空队伍"。
 *
 * 仅 ADD COLUMN（不删列，无需 create-copy-drop-rename）：
 * - battle_teams TEXT NOT NULL DEFAULT ''（List<BattleTeam> protobuf base64）
 * - used_team_numbers TEXT NOT NULL DEFAULT ''（List<Int> protobuf base64）
 * - battle_teams_initialized INTEGER NOT NULL DEFAULT 0
 *
 * 列定义与 GameData 实体注解一致，由 RoomMigrationTest 的真实 Room 打开
 * 触发 onValidateSchema 校验（对齐 v38→v39 测试模式）。
 */
internal val MIGRATION_39_40 = object : Migration(39, 40) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE game_data ADD COLUMN battle_teams TEXT NOT NULL DEFAULT ''"
        )
        db.execSQL(
            "ALTER TABLE game_data ADD COLUMN used_team_numbers TEXT NOT NULL DEFAULT ''"
        )
        db.execSQL(
            "ALTER TABLE game_data ADD COLUMN battle_teams_initialized INTEGER NOT NULL DEFAULT 0"
        )
        Log.i(TAG, "Migration 39→40: added battle_teams/used_team_numbers/battle_teams_initialized to game_data")
    }
}
