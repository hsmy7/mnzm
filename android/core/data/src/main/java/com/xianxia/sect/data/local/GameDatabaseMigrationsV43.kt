// GameDatabaseMigrationsV43.kt — 由 GameDatabase.kt 拆分生成（见 GameDatabase.kt addMigrations 列表）
package com.xianxia.sect.data.local

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Migration 文件共用的日志 TAG（各文件独立声明，避免 top-level 冲突） */
private const val TAG = "GameDatabase"

/**
 * v42→v43: 新增溢出邮件/直发邮件草稿持久化 2 张表
 *
 * 背景（D-01 溢出邮件事务化根治）：旧实现草稿仅存内存队列（300ms 防抖后才写
 * Room）——崩溃丢草稿（物品丢失）、事务回滚后邮件照发（物品复制）。新机制
 * 「草稿入队即持久化 + 事务世代号」：GameStateStore 提交钩子在事务锁外同步落盘
 * 草稿到本表，回滚钩子丢弃；drain 读本表转邮件后删行。
 *
 * 表结构（与 MailDraftDao 的 Entity 注解一致，由 RoomMigrationTest 的真实
 * Room 打开触发 onValidateSchema 校验，对齐 v41→v42 测试模式）：
 * - overflow_mail_drafts: 溢出草稿（id PK/slotId/source/itemType/itemName/
 *   rarity/quantity/createdAt）
 * - direct_mail_drafts: 直发草稿（id PK=邮件 id，payload=MailEntity JSON，
 *   createdAt）——id 天然幂等，重放不产生重复邮件
 *
 * 仅 CREATE TABLE（新建表，无数据搬迁，不删列）。
 */
internal val MIGRATION_42_43 = object : Migration(42, 43) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `overflow_mail_drafts` (" +
                "`id` TEXT NOT NULL, " +
                "`slotId` INTEGER NOT NULL, " +
                "`source` TEXT NOT NULL, " +
                "`itemType` TEXT NOT NULL, " +
                "`itemName` TEXT NOT NULL, " +
                "`rarity` INTEGER NOT NULL, " +
                "`quantity` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `direct_mail_drafts` (" +
                "`id` TEXT NOT NULL, " +
                "`slotId` INTEGER NOT NULL, " +
                "`payload` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
        Log.i(TAG, "Migration 42→43: created overflow_mail_drafts & direct_mail_drafts tables")
    }
}
