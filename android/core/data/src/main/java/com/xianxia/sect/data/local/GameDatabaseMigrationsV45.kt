// GameDatabaseMigrationsV45.kt — 由 GameDatabase.kt 拆分生成（见 GameDatabase.kt addMigrations 列表）
package com.xianxia.sect.data.local

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Migration 文件共用的日志 TAG（各文件独立声明，避免 top-level 冲突） */
private const val TAG = "GameDatabase"

/**
 * v44→v45: 世界地图探索队功能下线——删除 exploration_teams 表
 *
 * 背景（功能清理）：世界地图探索队（ExplorationTeam/ExplorationStatus）已随
 * 世界地图玩法下线，全链路代码删除（domain/data/engine/app 四层）。数据库侧
 * 同步删除持久化表 exploration_teams。洞府探索（cave_exploration）不受影响。
 *
 * 表内历史数据无迁移价值（功能已下线，数据无消费方），直接 DROP TABLE。
 * 后续 schema 校验（Room onValidateSchema）会确认该表已不存在。
 */
internal val MIGRATION_44_45 = object : Migration(44, 45) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `exploration_teams`")
        Log.i(TAG, "Migration 44→45: dropped exploration_teams table (world exploration team feature removed)")
    }
}
