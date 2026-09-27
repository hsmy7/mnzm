// GameDatabaseMigrationsV60.kt — v59→v60 迁移（见 GameDatabase.kt ALL_MIGRATIONS）
package com.xianxia.sect.data.local

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Migration 文件共用的日志 TAG（各文件独立声明，避免 top-level 冲突） */
private const val TAG_V60 = "GameDatabase"

/**
 * v59→v60: 双轨时间权威轴（结算改造 2026-09-27 B3，方案 §3.3/§4.1）——
 * 纯加法 5 列（旧档 DEFAULT 0，读档归一化按旧字段换算回填；只增不删）：
 *
 * | 表 | 列 | 语义 | 回填口径 |
 * |---|---|---|---|
 * | game_data | elapsedGameMs | 权威游戏时间轴（INV-1：日历为其派生投影） | C++ ensureBaselineTimeAxis / Kotlin TimeAxisRule：calendarToGameMs(year,month,phase) |
 * | game_data | lastSettleGameMs | 连续积分差分基准 | 与 elapsedGameMs 同刻初始化 |
 * | game_data | spiritMineLastSettledGameMs | 灵矿月度产出的毫秒孪生 | 旧绝对月字段换算（B6 切换判据前不消费） |
 * | production_slots | startedAtGameMs | 开工绝对游戏毫秒 | startYear/startMonth 月初换算 |
 * | production_slots | completeAtGameMs | 预期完工绝对游戏毫秒 | startedAt + duration × 月长 |
 *
 * 旧字段（gameYear/gameMonth/gamePhase、startYear/startMonth/duration/
 * completionMonth/completionPhase、spiritMineLastSettledMonth）**全部保留**——
 * 判据切换分批进行（B5/B6），切换前旧字段仍是唯一判据；本批后任何时点
 * 旧行为可回退且旧档双向可读（方案 §4.4 回滚路径）。
 *
 * ## 实现
 * 纯 `ALTER TABLE ADD COLUMN ... DEFAULT 0`（规则允许的加列形态，幂等安全）；
 * 60.json 由 KSP 自动导出。
 */
internal val MIGRATION_59_60 = object : Migration(59, 60) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE game_data ADD COLUMN elapsedGameMs INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE game_data ADD COLUMN lastSettleGameMs INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE game_data ADD COLUMN spiritMineLastSettledGameMs INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE production_slots ADD COLUMN startedAtGameMs INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE production_slots ADD COLUMN completeAtGameMs INTEGER NOT NULL DEFAULT 0")
        Log.i(TAG_V60, "Migration 59→60: added 3 time-axis columns to game_data, 2 to production_slots")
    }
}
