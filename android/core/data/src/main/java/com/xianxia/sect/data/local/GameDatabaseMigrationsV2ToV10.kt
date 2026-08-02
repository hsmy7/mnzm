// GameDatabaseMigrationsV2ToV10.kt — 由 GameDatabase.kt 拆分生成（见 GameDatabase.kt addMigrations 列表）
package com.xianxia.sect.data.local

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Migration 文件共用的日志 TAG（各文件独立声明，避免 top-level 冲突） */
private const val TAG = "GameDatabase"

        /** 4.0.13: 新增 sectLevelClaimRecords 列 — 宗门等级每周奖励领取记录 */
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE game_data ADD COLUMN sectLevelClaimRecords TEXT " +
                    "NOT NULL DEFAULT '[]'"
                )
                Log.i(TAG, "Migration 2→3: added sectLevelClaimRecords column to game_data")
            }
        }

        /** 4.0.13: 新增 saveVersion 列 + 修炼基础值缩放 1/10 */
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE game_data ADD COLUMN save_version INTEGER " +
                    "NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "UPDATE disciple_compact SET cultivation = cultivation / 10.0"
                )
                Log.i(TAG, "Migration 3→4: added saveVersion + scaled cultivation values")
            }
        }

        /** v4→v5: 新增 autoBuyList 列 */
        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE game_data ADD COLUMN autoBuyList TEXT " +
                    "NOT NULL DEFAULT '[]'"
                )
                Log.i(TAG, "Migration 4→5: added autoBuyList column")
            }
        }

        /** v5→v6: 新增 buildingInstanceId + bloodRefinementBonusTotals + usage_lastTheftMonth 列 */
        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // production_slots.buildingInstanceId — 任何旧版都没有
                db.execSQL(
                    "ALTER TABLE production_slots ADD COLUMN buildingInstanceId TEXT " +
                    "NOT NULL DEFAULT ''"
                )
                // bloodRefinementBonusTotals — 可能已由错误的 MIGRATION_4_5 回填过
                if (!columnExists(db, "game_data", "bloodRefinementBonusTotals")) {
                    db.execSQL(
                        "ALTER TABLE game_data ADD COLUMN bloodRefinementBonusTotals TEXT " +
                        "NOT NULL DEFAULT '{}'"
                    )
                }
                // usage_lastTheftMonth — UsageTracking 新增字段，@Embedded 展开
                if (!columnExists(db, "disciples", "usage_lastTheftMonth")) {
                    db.execSQL(
                        "ALTER TABLE disciples ADD COLUMN usage_lastTheftMonth INTEGER " +
                        "NOT NULL DEFAULT 0"
                    )
                }
                Log.i(TAG, "Migration 5→6: added production_slots.buildingInstanceId, " +
                    "game_data.bloodRefinementBonusTotals, " +
                    "disciples.usage_lastTheftMonth columns")
            }
        }

        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // AI宗门攻击个性映射
                if (!columnExists(db, "game_data", "aiSectPersonalities")) {
                    db.execSQL(
                        "ALTER TABLE game_data ADD COLUMN aiSectPersonalities TEXT " +
                        "NOT NULL DEFAULT ''"
                    )
                }
                // 附庸关系（"" = 独立）
                if (!columnExists(db, "game_data", "suzerainSectId")) {
                    db.execSQL(
                        "ALTER TABLE game_data ADD COLUMN suzerainSectId TEXT " +
                        "NOT NULL DEFAULT ''"
                    )
                }
                // 上年灵石收入
                if (!columnExists(db, "game_data", "lastYearSpiritStoneIncome")) {
                    db.execSQL(
                        "ALTER TABLE game_data ADD COLUMN lastYearSpiritStoneIncome INTEGER " +
                        "NOT NULL DEFAULT 0"
                    )
                }
                // 活跃攻击预警
                if (!columnExists(db, "game_data", "activeAttackWarnings")) {
                    db.execSQL(
                        "ALTER TABLE game_data ADD COLUMN activeAttackWarnings TEXT " +
                        "NOT NULL DEFAULT ''"
                    )
                }
                // 已展示预警阶段
                if (!columnExists(db, "game_data", "shownWarningStageIds")) {
                    db.execSQL(
                        "ALTER TABLE game_data ADD COLUMN shownWarningStageIds TEXT " +
                        "NOT NULL DEFAULT ''"
                    )
                }
                // AI宗门攻击冷却
                if (!columnExists(db, "game_data", "sectAttackCooldowns")) {
                    db.execSQL(
                        "ALTER TABLE game_data ADD COLUMN sectAttackCooldowns TEXT " +
                        "NOT NULL DEFAULT ''"
                    )
                }
                Log.i(TAG, "Migration 6→7: added AI attack system columns to game_data")
        }
        }

        /** v7→v8: 新增中品/上品灵石列 */
        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!columnExists(db, "game_data", "midGradeSpiritStones")) {
                    db.execSQL(
                        "ALTER TABLE game_data ADD COLUMN midGradeSpiritStones INTEGER " +
                        "NOT NULL DEFAULT 0"
                    )
                }
                if (!columnExists(db, "game_data", "highGradeSpiritStones")) {
                    db.execSQL(
                        "ALTER TABLE game_data ADD COLUMN highGradeSpiritStones INTEGER " +
                        "NOT NULL DEFAULT 0"
                    )
                }
                Log.i(TAG, "Migration 7→8: added midGradeSpiritStones and highGradeSpiritStones columns")
            }
        }

        /** v8→v9: 新增灵石自动补差价开关字段 */
        internal val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!columnExists(db, "game_data", "autoSellMidGradeForPurchase")) {
                    db.execSQL(
                        "ALTER TABLE game_data ADD COLUMN autoSellMidGradeForPurchase INTEGER " +
                        "NOT NULL DEFAULT 0"
                    )
                }
                if (!columnExists(db, "game_data", "autoSellHighGradeForPurchase")) {
                    db.execSQL(
                        "ALTER TABLE game_data ADD COLUMN autoSellHighGradeForPurchase INTEGER " +
                        "NOT NULL DEFAULT 0"
                    )
                }
                Log.i(TAG, "Migration 8→9: added autoSellMidGradeForPurchase and autoSellHighGradeForPurchase columns")
            }
        }

        /** v9→v10: 师徒系统新增 masterId 字段（两处同时补充） */
        internal val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. disciples 表：SocialData 通过 @Embedded(prefix="social_") 嵌入，
                //    新增 masterId 映射为 social_masterId 列
                if (!columnExists(db, "disciples", "social_masterId")) {
                    db.execSQL(
                        "ALTER TABLE disciples ADD COLUMN social_masterId TEXT"
                    )
                }
                // 2. disciples_extended 表：直接字段 masterId
                if (!columnExists(db, "disciples_extended", "masterId")) {
                    db.execSQL(
                        "ALTER TABLE disciples_extended ADD COLUMN masterId TEXT"
                    )
                }
                Log.i(TAG, "Migration 9→10: added social_masterId (disciples) " +
                    "and masterId (disciples_extended)")
            }
        }

        /** v10→v11: 新增附属宗门 vassalContracts + 宗门战记录 */
        internal val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!columnExists(db, "game_data", "vassalContracts")) {
                    db.execSQL(
                        "ALTER TABLE game_data ADD COLUMN vassalContracts TEXT " +
                        "NOT NULL DEFAULT '[]'"
                    )
                }
                if (!columnExists(db, "game_data", "sectBattleRecords")) {
                    db.execSQL(
                        "ALTER TABLE game_data ADD COLUMN sectBattleRecords TEXT " +
                        "NOT NULL DEFAULT '[]'"
                    )
                }
                // 2026-08-01 修复：merchantAcquisition* 列缺失导致 v2-v11 老存档在
                // MIGRATION_12_13 的 INSERT SELECT 处崩溃（no such column）。
                // 实体在 v11 引入但迁移缺失——在此补齐，使 v10→v11 输出与 schema 11.json 一致。
                if (!columnExists(db, "game_data", "merchantAcquisitionItems")) {
                    db.execSQL(
                        "ALTER TABLE game_data ADD COLUMN merchantAcquisitionItems TEXT " +
                        "NOT NULL DEFAULT '[]'"
                    )
                }
                if (!columnExists(db, "game_data", "merchantAcquisitionLastRefreshYear")) {
                    db.execSQL(
                        "ALTER TABLE game_data ADD COLUMN merchantAcquisitionLastRefreshYear " +
                        "INTEGER NOT NULL DEFAULT 0"
                    )
                }
                Log.i(TAG, "Migration 10→11: added vassalContracts, sectBattleRecords, merchantAcquisition*")
            }
        }
