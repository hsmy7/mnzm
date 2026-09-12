// GameDatabaseMigrationsV48.kt — 由 GameDatabase.kt 拆分生成（见 GameDatabase.kt addMigrations 列表）
package com.xianxia.sect.data.local

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Migration 文件共用的日志 TAG（各文件独立声明，避免 top-level 冲突） */
private const val TAG = "GameDatabase"

/**
 * v47→v48: overflow_mail_drafts 新增 item_id 列
 *
 * 背景（溢出邮件领取精确还原）：溢出邮件附件持久化物品模板 id（item_id），
 * drain 构建附件时透传，领取方据此精确还原原物品；不带 item_id 的草稿
 * 走按稀有度随机生成的回退逻辑。
 *
 * 仅 ALTER TABLE ADD COLUMN（新增列不删列，DEFAULT '' 兼容旧行——旧草稿
 * 无模板 id 时领取方仍按既有回退逻辑处理）。
 */
internal val MIGRATION_47_48 = object : Migration(47, 48) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `overflow_mail_drafts` ADD COLUMN `itemId` TEXT NOT NULL DEFAULT ''"
        )
        Log.i(TAG, "Migration 47→48: added itemId column to overflow_mail_drafts")
    }
}
