// GameDatabaseMigrationsV11ToV20.kt — 由 GameDatabase.kt 拆分生成（见 GameDatabase.kt addMigrations 列表）
package com.xianxia.sect.data.local

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Migration 文件共用的日志 TAG（各文件独立声明，避免 top-level 冲突） */
private const val TAG = "GameDatabase"

        /** v11→v12: 新增 map_seed 列 — 宗门地图随机种子 */
        internal val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!columnExists(db, "game_data", "map_seed")) {
                    db.execSQL(
                        "ALTER TABLE game_data ADD COLUMN map_seed INTEGER NOT NULL DEFAULT 0"
                    )
                }
                // 防御纵深（2026-08-01）：若旧版本升级链在 MIGRATION_10_11 之前已存在
                //（如 v2→v11 的中间版本），此处兜底补齐 merchantAcquisition* 列
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
                // 2026-08-01 修复（全链迁移测试暴露）：v12 schema 已含 mailRecords /
                // heavenly_trial_state / sign_in_state_json，但 v2-v11 段无任何迁移添加
                // 这三列——MIGRATION_12_13 的 INSERT SELECT 引用它们导致升级崩溃
                if (!columnExists(db, "game_data", "mailRecords")) {
                    db.execSQL(
                        "ALTER TABLE game_data ADD COLUMN mailRecords TEXT " +
                        "NOT NULL DEFAULT '[]'"
                    )
                }
                if (!columnExists(db, "game_data", "heavenly_trial_state")) {
                    db.execSQL(
                        "ALTER TABLE game_data ADD COLUMN heavenly_trial_state TEXT " +
                        "NOT NULL DEFAULT '{\"highestClearedLevel\":-1,\"levelClearCounts\":[0,0,0,0,0,0,0,0]}'"
                    )
                }
                if (!columnExists(db, "game_data", "sign_in_state_json")) {
                    db.execSQL(
                        "ALTER TABLE game_data ADD COLUMN sign_in_state_json TEXT " +
                        "NOT NULL DEFAULT '{\"claimedDays\":[],\"currentMonth\":0,\"currentYear\":0}'"
                    )
                }
                Log.i(TAG, "Migration 11→12: added map_seed, merchantAcquisition*, mailRecords, heavenly_trial_state, sign_in_state_json (defense)")
            }
        }

        /** v12→v13: 移除 isGameStarted 列 — 迁移到 GameLifecycle 纯运行时状态 */
        internal val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (columnExists(db, "game_data", "isGameStarted")) {
                    // 使用 create-copy-drop-rename 模式移除 isGameStarted 列
                    // CREATE TABLE 使用 Room 自动生成的 v13 schema SQL，确保列定义完全一致
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `game_data_new` (
                            `id` TEXT NOT NULL, `slot_id` INTEGER NOT NULL, `sectName` TEXT NOT NULL,
                            `currentSlot` INTEGER NOT NULL, `gameYear` INTEGER NOT NULL, `gameMonth` INTEGER NOT NULL,
                            `gamePhase` INTEGER NOT NULL, `gameSpeed` INTEGER NOT NULL, `spiritStones` INTEGER NOT NULL,
                            `midGradeSpiritStones` INTEGER NOT NULL, `highGradeSpiritStones` INTEGER NOT NULL,
                            `spiritHerbs` INTEGER NOT NULL, `sectCultivation` REAL NOT NULL,
                            `autoSaveIntervalMonths` INTEGER NOT NULL, `monthlySalary` TEXT NOT NULL,
                            `monthlySalaryEnabled` TEXT NOT NULL, `worldMapSects` TEXT NOT NULL,
                            `sectDetails` TEXT NOT NULL, `aiSectDisciples` TEXT NOT NULL,
                            `exploredSects` TEXT NOT NULL, `scoutInfo` TEXT NOT NULL,
                            `manualProficiencies` TEXT NOT NULL, `travelingMerchantItems` TEXT NOT NULL,
                            `merchantLastRefreshYear` INTEGER NOT NULL, `merchantRefreshCount` INTEGER NOT NULL,
                            `playerListedItems` TEXT NOT NULL, `merchantAcquisitionItems` TEXT NOT NULL,
                            `merchantAcquisitionLastRefreshYear` INTEGER NOT NULL, `autoBuyList` TEXT NOT NULL,
                            `recruitList` TEXT NOT NULL, `lastRecruitYear` INTEGER NOT NULL,
                            `worldLevels` TEXT NOT NULL, `cultivatorCaves` TEXT NOT NULL,
                            `caveExplorationTeams` TEXT NOT NULL, `aiCaveTeams` TEXT NOT NULL,
                            `unlockedRecipes` TEXT NOT NULL, `unlockedManuals` TEXT NOT NULL,
                            `lastSaveTime` INTEGER NOT NULL, `elderSlots` TEXT NOT NULL,
                            `spiritMineSlots` TEXT NOT NULL, `spiritMineExpansions` INTEGER NOT NULL,
                            `librarySlots` TEXT NOT NULL, `productionSlots` TEXT NOT NULL,
                            `placedBuildings` TEXT NOT NULL, `spiritFieldPlants` TEXT NOT NULL,
                            `activeSectId` TEXT NOT NULL, `residenceSlots` TEXT NOT NULL,
                            `warehouseGarrisons` TEXT NOT NULL, `patrolSlots` TEXT NOT NULL,
                            `patrolConfig` TEXT NOT NULL, `patrolConfigs` TEXT NOT NULL,
                            `alliances` TEXT NOT NULL, `vassalContracts` TEXT NOT NULL,
                            `sectRelations` TEXT NOT NULL, `playerAllianceSlots` INTEGER NOT NULL,
                            `sectPolicies` TEXT NOT NULL, `battleTeam` TEXT,
                            `aiBattleTeams` TEXT NOT NULL, `usedRedeemCodes` TEXT NOT NULL,
                            `mailRecords` TEXT NOT NULL, `sectLevelClaimRecords` TEXT NOT NULL,
                            `save_version` INTEGER NOT NULL DEFAULT 0,
                            `playerProtectionEnabled` INTEGER NOT NULL,
                            `playerProtectionStartYear` INTEGER NOT NULL,
                            `playerHasAttackedAI` INTEGER NOT NULL, `activeMissions` TEXT NOT NULL,
                            `availableMissions` TEXT NOT NULL,
                            `autoRecruitSpiritRootFilter` TEXT NOT NULL,
                            `daoCompanionBannedRootCounts` TEXT NOT NULL,
                            `daoCompanionConsentRequired` INTEGER NOT NULL,
                            `patrolBattleResultPopup` INTEGER NOT NULL,
                            `autoSellMidGradeForPurchase` INTEGER NOT NULL,
                            `autoSellHighGradeForPurchase` INTEGER NOT NULL,
                            `breakthroughAutoPillFocused` INTEGER NOT NULL,
                            `breakthroughAutoPillRootCounts` TEXT NOT NULL,
                            `autoEquipFromWarehouseFocused` INTEGER NOT NULL,
                            `autoEquipFromWarehouseRootCounts` TEXT NOT NULL,
                            `autoLearnFromWarehouseFocused` INTEGER NOT NULL,
                            `autoLearnFromWarehouseRootCounts` TEXT NOT NULL,
                            `isGameOver` INTEGER NOT NULL,
                            `bloodRefinements` TEXT NOT NULL DEFAULT '{}',
                            `activeBloodRefinements` TEXT NOT NULL DEFAULT '{}',
                            `bloodRefinementBonusTotals` TEXT NOT NULL DEFAULT '{}',
                            `heavenly_trial_state` TEXT NOT NULL DEFAULT '{"highestClearedLevel":-1,"levelClearCounts":[0,0,0,0,0,0,0,0]}',
                            `sign_in_state_json` TEXT NOT NULL DEFAULT '{"claimedDays":[],"currentMonth":0,"currentYear":0}',
                            `aiSectPersonalities` TEXT NOT NULL, `suzerainSectId` TEXT NOT NULL,
                            `lastYearSpiritStoneIncome` INTEGER NOT NULL,
                            `activeAttackWarnings` TEXT NOT NULL, `shownWarningStageIds` TEXT NOT NULL,
                            `sectAttackCooldowns` TEXT NOT NULL, `sectBattleRecords` TEXT NOT NULL,
                            `map_seed` INTEGER NOT NULL DEFAULT 0,
                            PRIMARY KEY(`id`, `slot_id`)
                        )
                    """)
                    // 复制数据（排除 isGameStarted 列）
                    // 使用紧凑单行 SQL 以避免 sqlite4java (Robolectric) 解析长多行 SQL 的问题
                    val insertCols = listOf(
                        "id", "slot_id", "sectName", "currentSlot",
                        "gameYear", "gameMonth", "gamePhase", "gameSpeed",
                        "spiritStones", "midGradeSpiritStones", "highGradeSpiritStones",
                        "spiritHerbs", "sectCultivation", "autoSaveIntervalMonths",
                        "monthlySalary", "monthlySalaryEnabled",
                        "worldMapSects", "sectDetails", "aiSectDisciples",
                        "exploredSects", "scoutInfo", "manualProficiencies",
                        "travelingMerchantItems", "merchantLastRefreshYear",
                        "merchantRefreshCount", "playerListedItems",
                        "merchantAcquisitionItems", "merchantAcquisitionLastRefreshYear",
                        "autoBuyList", "recruitList", "lastRecruitYear",
                        "worldLevels", "cultivatorCaves", "caveExplorationTeams",
                        "aiCaveTeams", "unlockedRecipes", "unlockedManuals",
                        "lastSaveTime", "elderSlots",
                        "spiritMineSlots", "spiritMineExpansions", "librarySlots",
                        "productionSlots", "placedBuildings", "spiritFieldPlants",
                        "activeSectId", "residenceSlots", "warehouseGarrisons",
                        "patrolSlots", "patrolConfig", "patrolConfigs",
                        "alliances", "vassalContracts",
                        "sectRelations", "playerAllianceSlots",
                        "sectPolicies", "battleTeam", "aiBattleTeams",
                        "usedRedeemCodes", "mailRecords", "sectLevelClaimRecords",
                        "save_version",
                        "playerProtectionEnabled", "playerProtectionStartYear", "playerHasAttackedAI",
                        "activeMissions", "availableMissions", "autoRecruitSpiritRootFilter",
                        "daoCompanionBannedRootCounts", "daoCompanionConsentRequired", "patrolBattleResultPopup",
                        "autoSellMidGradeForPurchase", "autoSellHighGradeForPurchase",
                        "breakthroughAutoPillFocused", "breakthroughAutoPillRootCounts",
                        "autoEquipFromWarehouseFocused", "autoEquipFromWarehouseRootCounts",
                        "autoLearnFromWarehouseFocused", "autoLearnFromWarehouseRootCounts",
                        "isGameOver",
                        "bloodRefinements", "activeBloodRefinements", "bloodRefinementBonusTotals",
                        "heavenly_trial_state", "sign_in_state_json",
                        "aiSectPersonalities", "suzerainSectId", "lastYearSpiritStoneIncome",
                        "activeAttackWarnings", "shownWarningStageIds", "sectAttackCooldowns", "sectBattleRecords",
                        "map_seed"
                    )
                    val quotedCols = insertCols.joinToString(", ") { "`$it`" }
                    db.execSQL("INSERT INTO `game_data_new` SELECT $quotedCols FROM `game_data`")
                    db.execSQL("DROP TABLE IF EXISTS `game_data`")
                    db.execSQL("ALTER TABLE `game_data_new` RENAME TO `game_data`")
                    // ⚠️ 索引必须在 RENAME 之后重建，否则会随旧表一起被 DROP
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_game_data_slot_id` ON `game_data` (`slot_id`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_game_data_lastSaveTime` ON `game_data` (`lastSaveTime`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_game_data_gameYear_gameMonth` ON `game_data` (`gameYear`, `gameMonth`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_game_data_sectName` ON `game_data` (`sectName`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_game_data_spiritStones` ON `game_data` (`spiritStones`)")
                    Log.i(TAG, "Migration 12→13: removed isGameStarted column from game_data")
                } else {
                    Log.i(TAG, "Migration 12→13: isGameStarted column not found, skipping")
                }
            }
        }

        /** v13→v14: 新增 cultivationCheckpoint/cultivationCheckpointGameMonth 到 disciples + spiritMineLastSettledMonth 到 game_data + baseDuration 到 production_slots */
        internal val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!columnExists(db, "disciples", "cultivationCheckpoint")) {
                    db.execSQL(
                        "ALTER TABLE disciples ADD COLUMN cultivationCheckpoint REAL NOT NULL DEFAULT 0.0"
                    )
                }
                if (!columnExists(db, "disciples", "cultivationCheckpointGameMonth")) {
                    db.execSQL(
                        "ALTER TABLE disciples ADD COLUMN cultivationCheckpointGameMonth INTEGER NOT NULL DEFAULT 0"
                    )
                }
                Log.i(TAG, "Migration 13→14: added cultivationCheckpoint, cultivationCheckpointGameMonth columns to disciples")
                // production_slots.baseDuration（原有commit遗漏的migration）
                if (!columnExists(db, "production_slots", "baseDuration")) {
                    db.execSQL(
                        "ALTER TABLE production_slots ADD COLUMN baseDuration INTEGER NOT NULL DEFAULT 0"
                    )
                    Log.i(TAG, "Migration 13→14: added baseDuration to production_slots")
                }
                // 新增 game_data.spiritMineLastSettledMonth 列（时间戳差分惰性结算所需）
                // ⚠️ 必须用 create-copy-drop-rename：Room v14 schema 中该列无 DEFAULT，
                //    但 ALTER TABLE ADD COLUMN 要求 NOT NULL 列必须带 DEFAULT，导致校验不匹配。
                if (!columnExists(db, "game_data", "spiritMineLastSettledMonth")) {
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `game_data_new` (
                            `id` TEXT NOT NULL, `slot_id` INTEGER NOT NULL, `sectName` TEXT NOT NULL,
                            `currentSlot` INTEGER NOT NULL, `gameYear` INTEGER NOT NULL, `gameMonth` INTEGER NOT NULL,
                            `gamePhase` INTEGER NOT NULL, `gameSpeed` INTEGER NOT NULL, `spiritStones` INTEGER NOT NULL,
                            `midGradeSpiritStones` INTEGER NOT NULL, `highGradeSpiritStones` INTEGER NOT NULL,
                            `spiritHerbs` INTEGER NOT NULL, `sectCultivation` REAL NOT NULL,
                            `autoSaveIntervalMonths` INTEGER NOT NULL, `monthlySalary` TEXT NOT NULL,
                            `monthlySalaryEnabled` TEXT NOT NULL, `worldMapSects` TEXT NOT NULL,
                            `sectDetails` TEXT NOT NULL, `aiSectDisciples` TEXT NOT NULL,
                            `exploredSects` TEXT NOT NULL, `scoutInfo` TEXT NOT NULL,
                            `manualProficiencies` TEXT NOT NULL, `travelingMerchantItems` TEXT NOT NULL,
                            `merchantLastRefreshYear` INTEGER NOT NULL, `merchantRefreshCount` INTEGER NOT NULL,
                            `playerListedItems` TEXT NOT NULL, `merchantAcquisitionItems` TEXT NOT NULL,
                            `merchantAcquisitionLastRefreshYear` INTEGER NOT NULL, `autoBuyList` TEXT NOT NULL,
                            `recruitList` TEXT NOT NULL, `lastRecruitYear` INTEGER NOT NULL,
                            `worldLevels` TEXT NOT NULL, `cultivatorCaves` TEXT NOT NULL,
                            `caveExplorationTeams` TEXT NOT NULL, `aiCaveTeams` TEXT NOT NULL,
                            `unlockedRecipes` TEXT NOT NULL, `unlockedManuals` TEXT NOT NULL,
                            `lastSaveTime` INTEGER NOT NULL, `elderSlots` TEXT NOT NULL,
                            `spiritMineSlots` TEXT NOT NULL, `spiritMineExpansions` INTEGER NOT NULL,
                            `spiritMineLastSettledMonth` INTEGER NOT NULL,
                            `librarySlots` TEXT NOT NULL, `productionSlots` TEXT NOT NULL,
                            `placedBuildings` TEXT NOT NULL, `spiritFieldPlants` TEXT NOT NULL,
                            `activeSectId` TEXT NOT NULL, `residenceSlots` TEXT NOT NULL,
                            `warehouseGarrisons` TEXT NOT NULL, `patrolSlots` TEXT NOT NULL,
                            `patrolConfig` TEXT NOT NULL, `patrolConfigs` TEXT NOT NULL,
                            `alliances` TEXT NOT NULL, `vassalContracts` TEXT NOT NULL,
                            `sectRelations` TEXT NOT NULL, `playerAllianceSlots` INTEGER NOT NULL,
                            `sectPolicies` TEXT NOT NULL, `battleTeam` TEXT,
                            `aiBattleTeams` TEXT NOT NULL, `usedRedeemCodes` TEXT NOT NULL,
                            `mailRecords` TEXT NOT NULL, `sectLevelClaimRecords` TEXT NOT NULL,
                            `save_version` INTEGER NOT NULL DEFAULT 0,
                            `playerProtectionEnabled` INTEGER NOT NULL,
                            `playerProtectionStartYear` INTEGER NOT NULL,
                            `playerHasAttackedAI` INTEGER NOT NULL, `activeMissions` TEXT NOT NULL,
                            `availableMissions` TEXT NOT NULL,
                            `autoRecruitSpiritRootFilter` TEXT NOT NULL,
                            `daoCompanionBannedRootCounts` TEXT NOT NULL,
                            `daoCompanionConsentRequired` INTEGER NOT NULL,
                            `patrolBattleResultPopup` INTEGER NOT NULL,
                            `autoSellMidGradeForPurchase` INTEGER NOT NULL,
                            `autoSellHighGradeForPurchase` INTEGER NOT NULL,
                            `breakthroughAutoPillFocused` INTEGER NOT NULL,
                            `breakthroughAutoPillRootCounts` TEXT NOT NULL,
                            `autoEquipFromWarehouseFocused` INTEGER NOT NULL,
                            `autoEquipFromWarehouseRootCounts` TEXT NOT NULL,
                            `autoLearnFromWarehouseFocused` INTEGER NOT NULL,
                            `autoLearnFromWarehouseRootCounts` TEXT NOT NULL,
                            `isGameOver` INTEGER NOT NULL,
                            `bloodRefinements` TEXT NOT NULL DEFAULT '{}',
                            `activeBloodRefinements` TEXT NOT NULL DEFAULT '{}',
                            `bloodRefinementBonusTotals` TEXT NOT NULL DEFAULT '{}',
                            `heavenly_trial_state` TEXT NOT NULL DEFAULT '{"highestClearedLevel":-1,"levelClearCounts":[0,0,0,0,0,0,0,0]}',
                            `sign_in_state_json` TEXT NOT NULL DEFAULT '{"claimedDays":[],"currentMonth":0,"currentYear":0}',
                            `aiSectPersonalities` TEXT NOT NULL, `suzerainSectId` TEXT NOT NULL,
                            `lastYearSpiritStoneIncome` INTEGER NOT NULL,
                            `activeAttackWarnings` TEXT NOT NULL, `shownWarningStageIds` TEXT NOT NULL,
                            `sectAttackCooldowns` TEXT NOT NULL, `sectBattleRecords` TEXT NOT NULL,
                            `map_seed` INTEGER NOT NULL DEFAULT 0,
                            PRIMARY KEY(`id`, `slot_id`)
                        )
                    """)
                    val insertCols = listOf(
                        "id", "slot_id", "sectName", "currentSlot",
                        "gameYear", "gameMonth", "gamePhase", "gameSpeed",
                        "spiritStones", "midGradeSpiritStones", "highGradeSpiritStones",
                        "spiritHerbs", "sectCultivation", "autoSaveIntervalMonths",
                        "monthlySalary", "monthlySalaryEnabled",
                        "worldMapSects", "sectDetails", "aiSectDisciples",
                        "exploredSects", "scoutInfo", "manualProficiencies",
                        "travelingMerchantItems", "merchantLastRefreshYear",
                        "merchantRefreshCount", "playerListedItems",
                        "merchantAcquisitionItems", "merchantAcquisitionLastRefreshYear",
                        "autoBuyList", "recruitList", "lastRecruitYear",
                        "worldLevels", "cultivatorCaves", "caveExplorationTeams",
                        "aiCaveTeams", "unlockedRecipes", "unlockedManuals",
                        "lastSaveTime", "elderSlots",
                        "spiritMineSlots", "spiritMineExpansions", "librarySlots",
                        "productionSlots", "placedBuildings", "spiritFieldPlants",
                        "activeSectId", "residenceSlots", "warehouseGarrisons",
                        "patrolSlots", "patrolConfig", "patrolConfigs",
                        "alliances", "vassalContracts",
                        "sectRelations", "playerAllianceSlots",
                        "sectPolicies", "battleTeam", "aiBattleTeams",
                        "usedRedeemCodes", "mailRecords", "sectLevelClaimRecords",
                        "save_version",
                        "playerProtectionEnabled", "playerProtectionStartYear", "playerHasAttackedAI",
                        "activeMissions", "availableMissions", "autoRecruitSpiritRootFilter",
                        "daoCompanionBannedRootCounts", "daoCompanionConsentRequired", "patrolBattleResultPopup",
                        "autoSellMidGradeForPurchase", "autoSellHighGradeForPurchase",
                        "breakthroughAutoPillFocused", "breakthroughAutoPillRootCounts",
                        "autoEquipFromWarehouseFocused", "autoEquipFromWarehouseRootCounts",
                        "autoLearnFromWarehouseFocused", "autoLearnFromWarehouseRootCounts",
                        "isGameOver",
                        "bloodRefinements", "activeBloodRefinements", "bloodRefinementBonusTotals",
                        "heavenly_trial_state", "sign_in_state_json",
                        "aiSectPersonalities", "suzerainSectId", "lastYearSpiritStoneIncome",
                        "activeAttackWarnings", "shownWarningStageIds", "sectAttackCooldowns", "sectBattleRecords",
                        "map_seed"
                    )
                    val quotedCols = insertCols.joinToString(", ") { "`$it`" }
                    // 保护：清理前次失败 migration 可能留下的 NULL 值
                    // （TEXT NOT NULL 列被污染为 NULL 时，用 '{}' 兜底）
                    db.execSQL("UPDATE `game_data` SET `sectPolicies` = '{}' WHERE `sectPolicies` IS NULL")
                    db.execSQL("UPDATE `game_data` SET `mailRecords` = '[]' WHERE `mailRecords` IS NULL")
                    db.execSQL("UPDATE `game_data` SET `sectLevelClaimRecords` = '[]' WHERE `sectLevelClaimRecords` IS NULL")
                    // ⚠️ SELECT 列顺序必须与 CREATE TABLE 完全一致！
                    // spiritMineLastSettledMonth 位于 spiritMineExpansions 和 librarySlots 之间（第42列），
                    // 不能加在末尾，否则后面所有列错位。（第一性原理：SQLite INSERT SELECT 按位置映射，非按列名）
                    val selectParts = mutableListOf<String>()
                    for (col in insertCols) {
                        if (col == "librarySlots") {
                            selectParts.add("0 AS `spiritMineLastSettledMonth`")
                        }
                        selectParts.add("`$col`")
                    }
                    val selectSql = selectParts.joinToString(", ")
                    db.execSQL("INSERT INTO `game_data_new` SELECT $selectSql FROM `game_data`")
                    db.execSQL("DROP TABLE IF EXISTS `game_data`")
                    db.execSQL("ALTER TABLE `game_data_new` RENAME TO `game_data`")
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_game_data_slot_id` ON `game_data` (`slot_id`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_game_data_lastSaveTime` ON `game_data` (`lastSaveTime`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_game_data_gameYear_gameMonth` ON `game_data` (`gameYear`, `gameMonth`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_game_data_sectName` ON `game_data` (`sectName`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_game_data_spiritStones` ON `game_data` (`spiritStones`)")
                    Log.i(TAG, "Migration 13→14: rebuilt game_data with spiritMineLastSettledMonth column")
                } else {
                    Log.i(TAG, "Migration 13→14: spiritMineLastSettledMonth already exists in game_data, skipping")
                }
            }
        }

        internal val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!columnExists(db, "game_data", "discipleDesertionPopup")) {
                    db.execSQL(
                        "ALTER TABLE game_data ADD COLUMN discipleDesertionPopup INTEGER NOT NULL DEFAULT 1"
                    )
                }
                Log.i(TAG, "Migration 14→15: added discipleDesertionPopup to game_data")
            }
        }

        /** v15→v16: 新增 showAllAvailableDisciples + worldLevelLastRefreshMonth + rngStates + pendingPatrolBattleResults 列 */
        internal val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!columnExists(db, "game_data", "showAllAvailableDisciples")) {
                    db.execSQL(
                        "ALTER TABLE game_data ADD COLUMN showAllAvailableDisciples INTEGER NOT NULL DEFAULT 0"
                    )
                }
                if (!columnExists(db, "game_data", "worldLevelLastRefreshMonth")) {
                    db.execSQL(
                        "ALTER TABLE game_data ADD COLUMN worldLevelLastRefreshMonth INTEGER NOT NULL DEFAULT 0"
                    )
                }
                if (!columnExists(db, "game_data", "rngStates")) {
                    db.execSQL(
                        "ALTER TABLE game_data ADD COLUMN rngStates TEXT NOT NULL DEFAULT '{}'"
                    )
                }
                if (!columnExists(db, "game_data", "pendingPatrolBattleResults")) {
                    db.execSQL(
                        "ALTER TABLE game_data ADD COLUMN pendingPatrolBattleResults TEXT NOT NULL DEFAULT '[]'"
                    )
                }
                Log.i(TAG, "Migration 15→16: added showAllAvailableDisciples, worldLevelLastRefreshMonth, rngStates, pendingPatrolBattleResults to game_data")
            }
        }

        /** v16→v17: 补漏 rngStates/pendingPatrolBattleResults/worldLevelLastRefreshMonth — 旧 MIGRATION_15_16 遗漏此 3 列 */
        internal val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!columnExists(db, "game_data", "worldLevelLastRefreshMonth")) {
                    db.execSQL(
                        "ALTER TABLE game_data ADD COLUMN worldLevelLastRefreshMonth INTEGER NOT NULL DEFAULT 0"
                    )
                }
                if (!columnExists(db, "game_data", "rngStates")) {
                    db.execSQL(
                        "ALTER TABLE game_data ADD COLUMN rngStates TEXT NOT NULL DEFAULT '{}'"
                    )
                }
                if (!columnExists(db, "game_data", "pendingPatrolBattleResults")) {
                    db.execSQL(
                        "ALTER TABLE game_data ADD COLUMN pendingPatrolBattleResults TEXT NOT NULL DEFAULT '[]'"
                    )
                }
                Log.i(TAG, "Migration 16→17: added missing worldLevelLastRefreshMonth, rngStates, pendingPatrolBattleResults to game_data")
            }
        }

        /** v17→v18: 删除 forge_slots/alchemy_slots 两张僵尸表 — 已全部迁移到 production_slots */
        internal val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS forge_slots")
                db.execSQL("DROP TABLE IF EXISTS alchemy_slots")
                Log.i(TAG, "Migration 17→18: dropped forge_slots and alchemy_slots tables")
            }
        }

        internal val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!columnExists(db, "disciples_extended", "pillCultivationSpeedBonus")) {
                    db.execSQL("ALTER TABLE disciples_extended ADD COLUMN pillCultivationSpeedBonus REAL NOT NULL DEFAULT 0.0")
                }
                if (!columnExists(db, "disciples_extended", "pillEffectDuration")) {
                    db.execSQL("ALTER TABLE disciples_extended ADD COLUMN pillEffectDuration INTEGER NOT NULL DEFAULT 0")
                }
                Log.i(TAG, "Migration 18→19: disciples_extended added pillCultivationSpeedBonus/pillEffectDuration")
            }
        }

        /** v19→v20: game_data 新增 bloodRefinementPctTotals 列 + 删除 gameSpeed 列 */
        internal val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (columnExists(db, "game_data", "gameSpeed")) {
                    // 需要同时：删除 gameSpeed + 新增 bloodRefinementPctTotals
                    // SQLite 不支持 ALTER TABLE DROP COLUMN（< 3.35.0），使用 create-copy-drop-rename
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `game_data_new` (
                            `id` TEXT NOT NULL, `slot_id` INTEGER NOT NULL, `sectName` TEXT NOT NULL,
                            `currentSlot` INTEGER NOT NULL, `gameYear` INTEGER NOT NULL,
                            `gameMonth` INTEGER NOT NULL, `gamePhase` INTEGER NOT NULL,
                            `spiritStones` INTEGER NOT NULL,
                            `midGradeSpiritStones` INTEGER NOT NULL,
                            `highGradeSpiritStones` INTEGER NOT NULL,
                            `spiritHerbs` INTEGER NOT NULL, `sectCultivation` REAL NOT NULL,
                            `autoSaveIntervalMonths` INTEGER NOT NULL, `monthlySalary` TEXT NOT NULL,
                            `monthlySalaryEnabled` TEXT NOT NULL, `worldMapSects` TEXT NOT NULL,
                            `sectDetails` TEXT NOT NULL, `aiSectDisciples` TEXT NOT NULL,
                            `exploredSects` TEXT NOT NULL, `scoutInfo` TEXT NOT NULL,
                            `manualProficiencies` TEXT NOT NULL,
                            `travelingMerchantItems` TEXT NOT NULL,
                            `merchantLastRefreshYear` INTEGER NOT NULL,
                            `merchantRefreshCount` INTEGER NOT NULL,
                            `playerListedItems` TEXT NOT NULL,
                            `merchantAcquisitionItems` TEXT NOT NULL,
                            `merchantAcquisitionLastRefreshYear` INTEGER NOT NULL,
                            `autoBuyList` TEXT NOT NULL, `recruitList` TEXT NOT NULL,
                            `lastRecruitYear` INTEGER NOT NULL, `worldLevels` TEXT NOT NULL,
                            `worldLevelLastRefreshMonth` INTEGER NOT NULL,
                            `rngStates` TEXT NOT NULL,
                            `cultivatorCaves` TEXT NOT NULL,
                            `caveExplorationTeams` TEXT NOT NULL, `aiCaveTeams` TEXT NOT NULL,
                            `unlockedRecipes` TEXT NOT NULL, `unlockedManuals` TEXT NOT NULL,
                            `lastSaveTime` INTEGER NOT NULL, `elderSlots` TEXT NOT NULL,
                            `spiritMineSlots` TEXT NOT NULL, `spiritMineExpansions` INTEGER NOT NULL,
                            `spiritMineLastSettledMonth` INTEGER NOT NULL,
                            `librarySlots` TEXT NOT NULL, `productionSlots` TEXT NOT NULL,
                            `placedBuildings` TEXT NOT NULL, `spiritFieldPlants` TEXT NOT NULL,
                            `activeSectId` TEXT NOT NULL, `residenceSlots` TEXT NOT NULL,
                            `warehouseGarrisons` TEXT NOT NULL, `patrolSlots` TEXT NOT NULL,
                            `patrolConfig` TEXT NOT NULL, `patrolConfigs` TEXT NOT NULL,
                            `pendingPatrolBattleResults` TEXT NOT NULL,
                            `alliances` TEXT NOT NULL, `vassalContracts` TEXT NOT NULL,
                            `sectRelations` TEXT NOT NULL, `playerAllianceSlots` INTEGER NOT NULL,
                            `sectPolicies` TEXT NOT NULL, `battleTeam` TEXT,
                            `aiBattleTeams` TEXT NOT NULL, `usedRedeemCodes` TEXT NOT NULL,
                            `mailRecords` TEXT NOT NULL,
                            `sectLevelClaimRecords` TEXT NOT NULL,
                            `save_version` INTEGER NOT NULL DEFAULT 0,
                            `playerProtectionEnabled` INTEGER NOT NULL,
                            `playerProtectionStartYear` INTEGER NOT NULL,
                            `playerHasAttackedAI` INTEGER NOT NULL,
                            `activeMissions` TEXT NOT NULL, `availableMissions` TEXT NOT NULL,
                            `autoRecruitSpiritRootFilter` TEXT NOT NULL,
                            `daoCompanionBannedRootCounts` TEXT NOT NULL,
                            `daoCompanionConsentRequired` INTEGER NOT NULL,
                            `patrolBattleResultPopup` INTEGER NOT NULL,
                            `autoSellMidGradeForPurchase` INTEGER NOT NULL,
                            `autoSellHighGradeForPurchase` INTEGER NOT NULL,
                            `discipleDesertionPopup` INTEGER NOT NULL,
                            `showAllAvailableDisciples` INTEGER NOT NULL,
                            `breakthroughAutoPillFocused` INTEGER NOT NULL,
                            `breakthroughAutoPillRootCounts` TEXT NOT NULL,
                            `autoEquipFromWarehouseFocused` INTEGER NOT NULL,
                            `autoEquipFromWarehouseRootCounts` TEXT NOT NULL,
                            `autoLearnFromWarehouseFocused` INTEGER NOT NULL,
                            `autoLearnFromWarehouseRootCounts` TEXT NOT NULL,
                            `isGameOver` INTEGER NOT NULL,
                            `bloodRefinements` TEXT NOT NULL DEFAULT '{}',
                            `activeBloodRefinements` TEXT NOT NULL DEFAULT '{}',
                            `bloodRefinementBonusTotals` TEXT NOT NULL DEFAULT '{}',
                            `bloodRefinementPctTotals` TEXT NOT NULL DEFAULT '{}',
                            `heavenly_trial_state` TEXT NOT NULL DEFAULT '{"highestClearedLevel":-1,"levelClearCounts":[0,0,0,0,0,0,0,0]}',
                            `sign_in_state_json` TEXT NOT NULL DEFAULT '{"claimedDays":[],"currentMonth":0,"currentYear":0}',
                            `aiSectPersonalities` TEXT NOT NULL, `suzerainSectId` TEXT NOT NULL,
                            `lastYearSpiritStoneIncome` INTEGER NOT NULL,
                            `activeAttackWarnings` TEXT NOT NULL,
                            `shownWarningStageIds` TEXT NOT NULL,
                            `sectAttackCooldowns` TEXT NOT NULL,
                            `sectBattleRecords` TEXT NOT NULL,
                            `map_seed` INTEGER NOT NULL DEFAULT 0,
                            PRIMARY KEY(`id`, `slot_id`)
                        )
                    """)
                    // 从旧表复制数据（排除 gameSpeed 列）
                    val insertCols = listOf(
                        "id", "slot_id", "sectName", "currentSlot",
                        "gameYear", "gameMonth", "gamePhase",
                        "spiritStones", "midGradeSpiritStones", "highGradeSpiritStones",
                        "spiritHerbs", "sectCultivation", "autoSaveIntervalMonths",
                        "monthlySalary", "monthlySalaryEnabled",
                        "worldMapSects", "sectDetails", "aiSectDisciples",
                        "exploredSects", "scoutInfo", "manualProficiencies",
                        "travelingMerchantItems", "merchantLastRefreshYear",
                        "merchantRefreshCount", "playerListedItems",
                        "merchantAcquisitionItems", "merchantAcquisitionLastRefreshYear",
                        "autoBuyList", "recruitList", "lastRecruitYear",
                        "worldLevels", "worldLevelLastRefreshMonth", "rngStates",
                        "cultivatorCaves", "caveExplorationTeams", "aiCaveTeams",
                        "unlockedRecipes", "unlockedManuals", "lastSaveTime", "elderSlots",
                        "spiritMineSlots", "spiritMineExpansions", "spiritMineLastSettledMonth",
                        "librarySlots", "productionSlots", "placedBuildings", "spiritFieldPlants",
                        "activeSectId", "residenceSlots", "warehouseGarrisons",
                        "patrolSlots", "patrolConfig", "patrolConfigs",
                        "pendingPatrolBattleResults",
                        "alliances", "vassalContracts",
                        "sectRelations", "playerAllianceSlots",
                        "sectPolicies", "battleTeam", "aiBattleTeams",
                        "usedRedeemCodes", "mailRecords", "sectLevelClaimRecords",
                        "save_version",
                        "playerProtectionEnabled", "playerProtectionStartYear", "playerHasAttackedAI",
                        "activeMissions", "availableMissions", "autoRecruitSpiritRootFilter",
                        "daoCompanionBannedRootCounts", "daoCompanionConsentRequired", "patrolBattleResultPopup",
                        "autoSellMidGradeForPurchase", "autoSellHighGradeForPurchase",
                        "discipleDesertionPopup", "showAllAvailableDisciples",
                        "breakthroughAutoPillFocused", "breakthroughAutoPillRootCounts",
                        "autoEquipFromWarehouseFocused", "autoEquipFromWarehouseRootCounts",
                        "autoLearnFromWarehouseFocused", "autoLearnFromWarehouseRootCounts",
                        "isGameOver",
                        "bloodRefinements", "activeBloodRefinements", "bloodRefinementBonusTotals",
                        "heavenly_trial_state", "sign_in_state_json",
                        "aiSectPersonalities", "suzerainSectId", "lastYearSpiritStoneIncome",
                        "activeAttackWarnings", "shownWarningStageIds", "sectAttackCooldowns",
                        "sectBattleRecords", "map_seed"
                    )
                    // 构建 SELECT 列列表：在 bloodRefinementBonusTotals 和
                    // heavenly_trial_state 之间插入 bloodRefinementPctTotals 字面量
                    // 因为 v19 源表没有此列，必须用 DEFAULT 值填充
                    val selectParts = mutableListOf<String>()
                    for (col in insertCols) {
                        if (col == "heavenly_trial_state") {
                            selectParts.add("'{}' AS `bloodRefinementPctTotals`")
                        }
                        selectParts.add("`$col`")
                    }
                    val selectSql = selectParts.joinToString(", ")
                    // 保护：清理前次失败可能留下的 NULL 值
                    db.execSQL("UPDATE `game_data` SET `sectPolicies` = '{}' WHERE `sectPolicies` IS NULL")
                    db.execSQL("UPDATE `game_data` SET `mailRecords` = '[]' WHERE `mailRecords` IS NULL")
                    db.execSQL("UPDATE `game_data` SET `sectLevelClaimRecords` = '[]' WHERE `sectLevelClaimRecords` IS NULL")
                    db.execSQL("INSERT INTO `game_data_new` SELECT $selectSql FROM `game_data`")
                    db.execSQL("DROP TABLE IF EXISTS `game_data`")
                    db.execSQL("ALTER TABLE `game_data_new` RENAME TO `game_data`")
                    // 索引必须在 RENAME 之后重建
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_game_data_slot_id` ON `game_data` (`slot_id`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_game_data_lastSaveTime` ON `game_data` (`lastSaveTime`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_game_data_gameYear_gameMonth` ON `game_data` (`gameYear`, `gameMonth`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_game_data_sectName` ON `game_data` (`sectName`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_game_data_spiritStones` ON `game_data` (`spiritStones`)")
                    Log.i(TAG, "Migration 19→20: rebuilt game_data (dropped gameSpeed, added bloodRefinementPctTotals)")
                } else {
                    // gameSpeed 已不存，只需加列
                    if (!columnExists(db, "game_data", "bloodRefinementPctTotals")) {
                        db.execSQL(
                            "ALTER TABLE game_data ADD COLUMN bloodRefinementPctTotals TEXT " +
                            "NOT NULL DEFAULT '{}'"
                        )
                    }
                    Log.i(TAG, "Migration 19→20: added bloodRefinementPctTotals (gameSpeed already dropped)")
                }
            }
        }

        /** v20->v21: game_data 新增 gameEventRecords 列 */
        internal val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!columnExists(db, "game_data", "gameEventRecords")) {
                    db.execSQL("ALTER TABLE game_data ADD COLUMN gameEventRecords TEXT NOT NULL DEFAULT '[]'")
                }
                Log.i(TAG, "Migration 20->21: game_data added gameEventRecords column")
            }
        }
