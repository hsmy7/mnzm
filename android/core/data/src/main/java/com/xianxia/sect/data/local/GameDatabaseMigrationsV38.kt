// GameDatabaseMigrationsV38.kt — 由 GameDatabase.kt 拆分生成（见 GameDatabase.kt addMigrations 列表）
package com.xianxia.sect.data.local

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Migration 文件共用的日志 TAG（各文件独立声明，避免 top-level 冲突） */
private const val TAG = "GameDatabase"

        /**
         * v37→v38: 新增远古秘境 4 列
         *
         * - secret_realm_state：秘境地图实例（Base64 proto，空实例编码为空串，默认 ''）
         * - secret_realm_cooldown_year：冷却年份（Int）
         * - secret_realm_session：探索会话（Base64 proto，默认 ''）
         * - secret_realm_ai_teams：AI 宗门队伍列表（Base64 proto，默认 ''）
         *
         * 与 MIGRATION_36_37 watchedItemIds 先例一致（List/复合类型经 ProtobufConverters
         * 序列化为 Base64，空值编码后为空字符串，默认值用 ''）。
         */
        internal val MIGRATION_37_38 = object : Migration(37, 38) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!columnExists(db, "game_data", "secret_realm_state")) {
                    db.execSQL(
                        "ALTER TABLE game_data ADD COLUMN secret_realm_state " +
                        "TEXT NOT NULL DEFAULT ''"
                    )
                }
                if (!columnExists(db, "game_data", "secret_realm_cooldown_year")) {
                    db.execSQL(
                        "ALTER TABLE game_data ADD COLUMN secret_realm_cooldown_year " +
                        "INTEGER NOT NULL DEFAULT 0"
                    )
                }
                if (!columnExists(db, "game_data", "secret_realm_session")) {
                    db.execSQL(
                        "ALTER TABLE game_data ADD COLUMN secret_realm_session " +
                        "TEXT NOT NULL DEFAULT ''"
                    )
                }
                if (!columnExists(db, "game_data", "secret_realm_ai_teams")) {
                    db.execSQL(
                        "ALTER TABLE game_data ADD COLUMN secret_realm_ai_teams " +
                        "TEXT NOT NULL DEFAULT ''"
                    )
                }
                Log.i(TAG, "Migration 37→38: added secret_realm 4 columns")
            }
        }
