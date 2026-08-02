// GameDatabaseMigrationsV31ToV37.kt — 由 GameDatabase.kt 拆分生成（见 GameDatabase.kt addMigrations 列表）
package com.xianxia.sect.data.local

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Migration 文件共用的日志 TAG（各文件独立声明，避免 top-level 冲突） */
private const val TAG = "GameDatabase"

        /**
         * v31→v32: 新增 game_data.theft_judgements_this_month 列 — 每月偷盗判定计数
         */
        internal val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!columnExists(db, "game_data", "theft_judgements_this_month")) {
                    db.execSQL(
                        "ALTER TABLE game_data ADD COLUMN theft_judgements_this_month " +
                        "INTEGER NOT NULL DEFAULT 0"
                    )
                }
                Log.i(TAG, "Migration 31→32: added theft_judgements_this_month")
            }
        }

        /**
         * v32→v33: 新增 game_data.soundEnabled + musicEnabled 列 — 音乐/音效开关从 SessionManager 迁移到 GameData
         */
        internal val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!columnExists(db, "game_data", "soundEnabled")) {
                    db.execSQL(
                        "ALTER TABLE game_data ADD COLUMN soundEnabled " +
                        "INTEGER NOT NULL DEFAULT 1"
                    )
                }
                if (!columnExists(db, "game_data", "musicEnabled")) {
                    db.execSQL(
                        "ALTER TABLE game_data ADD COLUMN musicEnabled " +
                        "INTEGER NOT NULL DEFAULT 1"
                    )
                }
                Log.i(TAG, "Migration 32→33: added soundEnabled + musicEnabled")
            }
        }

        internal val MIGRATION_33_34 = object : Migration(33, 34) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!columnExists(db, "game_data", "prisonerSpiritRootFilter")) {
                    db.execSQL(
                        "ALTER TABLE game_data ADD COLUMN prisonerSpiritRootFilter " +
                        "TEXT NOT NULL DEFAULT '{}'"
                    )
                }
                if (!columnExists(db, "game_data", "recruitCountThisMonth")) {
                    db.execSQL(
                        "ALTER TABLE game_data ADD COLUMN recruitCountThisMonth " +
                        "INTEGER NOT NULL DEFAULT 0"
                    )
                }
                Log.i(TAG, "Migration 33→34: added prisonerSpiritRootFilter + recruitCountThisMonth")
            }
        }

        internal val MIGRATION_34_35 = object : Migration(34, 35) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!columnExists(db, "game_data", "autoRejectSpiritRootFilter")) {
                    db.execSQL(
                        "ALTER TABLE game_data ADD COLUMN autoRejectSpiritRootFilter " +
                        "TEXT NOT NULL DEFAULT '{}'"
                    )
                }
                Log.i(TAG, "Migration 34→35: added autoRejectSpiritRootFilter")
            }
        }

        /**
         * v35→v36: 新增 disciples.physiqueIds/affixIds 与 disciples_extended.physiqueIds/affixIds 列
         *
         * 用于弟子天赋三分类重构：将原 Talent 拆分为 Talent（天赋）/Physique（体质）/Affix（词条）。
         * 旧存档弟子新列默认为空字符串（反序列化为 emptyList），符合"旧存档弟子视为无体质无词条"约定。
         * List<String> 经 ProtobufConverters 序列化为 Base64，空列表编码后为空字符串。
         */
        internal val MIGRATION_35_36 = object : Migration(35, 36) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!columnExists(db, "disciples", "physiqueIds")) {
                    db.execSQL(
                        "ALTER TABLE disciples ADD COLUMN physiqueIds TEXT NOT NULL DEFAULT ''"
                    )
                }
                if (!columnExists(db, "disciples", "affixIds")) {
                    db.execSQL(
                        "ALTER TABLE disciples ADD COLUMN affixIds TEXT NOT NULL DEFAULT ''"
                    )
                }
                if (!columnExists(db, "disciples_extended", "physiqueIds")) {
                    db.execSQL(
                        "ALTER TABLE disciples_extended ADD COLUMN physiqueIds TEXT NOT NULL DEFAULT ''"
                    )
                }
                if (!columnExists(db, "disciples_extended", "affixIds")) {
                    db.execSQL(
                        "ALTER TABLE disciples_extended ADD COLUMN affixIds TEXT NOT NULL DEFAULT ''"
                    )
                }
                Log.i(TAG, "Migration 35→36: added disciples + disciples_extended physiqueIds/affixIds")
            }
        }

        /**
         * v36→v37: 新增 game_data.watchedItemIds 列（物品关注列表）
         *
         * List<String> 经 ProtobufConverters 序列化为 Base64，空列表编码后为空字符串，
         * 默认值用 ''（与 MIGRATION_35_36 physiqueIds/affixIds 先例一致）。
         * 旧存档反序列化取默认空列表。
         */
        internal val MIGRATION_36_37 = object : Migration(36, 37) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!columnExists(db, "game_data", "watchedItemIds")) {
                    db.execSQL(
                        "ALTER TABLE game_data ADD COLUMN watchedItemIds " +
                        "TEXT NOT NULL DEFAULT ''"
                    )
                }
                Log.i(TAG, "Migration 36→37: added game_data.watchedItemIds")
            }
        }
