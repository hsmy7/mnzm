// GameDatabaseMigrationSupport.kt — Migration 辅助成员（从 GameDatabase.kt 拆分）
package com.xianxia.sect.data.local

import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Migration 文件共用的日志 TAG（各文件独立声明，避免 top-level 冲突） */
private const val TAG = "GameDatabase"

        /**
         * 检查表中是否存在指定列。
         * 用于处理错误的 Migration 回填（已存在列重复 ALTER 会崩溃）。
         */
        internal fun columnExists(
            db: SupportSQLiteDatabase,
            table: String,
            column: String
        ): Boolean {
            val cursor = db.query("PRAGMA table_info($table)")
            return cursor.use {
                while (it.moveToNext()) {
                    if (it.getString(it.getColumnIndexOrThrow("name")) == column)
                        return@use true
                }
                false
            }
        }
        /**
         * Room v29 全量 game_data 表 CREATE TABLE SQL。
         * 用于 MIGRATION_22_23 和 MIGRATION_24_25 重建 game_data 表。
         *
         * 必须与 GameData 实体完全一致（NOT NULL、DEFAULT、PRIMARY KEY）。
         * 包含 v26（引导）、v27/v28（年报）、v29（广纳门徒冷却）等全部字段。
         */
        internal val GAME_DATA_CREATE_SQL = """
            CREATE TABLE IF NOT EXISTS `game_data` (
                `id` TEXT NOT NULL, `slot_id` INTEGER NOT NULL, `sectName` TEXT NOT NULL,
                `currentSlot` INTEGER NOT NULL, `gameYear` INTEGER NOT NULL, `gameMonth` INTEGER NOT NULL,
                `gamePhase` INTEGER NOT NULL, `spiritStones` INTEGER NOT NULL,
                `midGradeSpiritStones` INTEGER NOT NULL, `highGradeSpiritStones` INTEGER NOT NULL,
                `spiritHerbs` INTEGER NOT NULL, `sectCultivation` REAL NOT NULL,
                `autoSaveIntervalMonths` INTEGER NOT NULL, `monthlySalary` TEXT NOT NULL,
                `monthlySalaryEnabled` TEXT NOT NULL, `worldMapSects` TEXT NOT NULL,
                `sectDetails` TEXT NOT NULL, `aiSectDisciples` TEXT NOT NULL,
                `exploredSects` TEXT NOT NULL, `scoutInfo` TEXT NOT NULL,
                `manualProficiencies` TEXT NOT NULL, `travelingMerchantItems` TEXT NOT NULL,
                `merchantLastRefreshYear` INTEGER NOT NULL, `merchantRefreshCount` INTEGER NOT NULL,
                `merchantRefreshChances` INTEGER NOT NULL, `merchantLastRefreshChanceGrantYear` INTEGER NOT NULL,
                `playerListedItems` TEXT NOT NULL, `merchantAcquisitionItems` TEXT NOT NULL,
                `merchantAcquisitionLastRefreshYear` INTEGER NOT NULL, `autoBuyList` TEXT NOT NULL,
                `recruitList` TEXT NOT NULL, `lastRecruitYear` INTEGER NOT NULL,
                `worldLevels` TEXT NOT NULL, `worldLevelLastRefreshMonth` INTEGER NOT NULL,
                `rngStates` TEXT NOT NULL, `cultivatorCaves` TEXT NOT NULL,
                `caveExplorationTeams` TEXT NOT NULL, `aiCaveTeams` TEXT NOT NULL,
                `unlockedRecipes` TEXT NOT NULL, `unlockedManuals` TEXT NOT NULL,
                `lastSaveTime` INTEGER NOT NULL, `elderSlots` TEXT NOT NULL,
                `spiritMineSlots` TEXT NOT NULL, `spiritMineExpansions` INTEGER NOT NULL,
                `spiritMineLastSettledMonth` INTEGER NOT NULL, `librarySlots` TEXT NOT NULL,
                `productionSlots` TEXT NOT NULL, `placedBuildings` TEXT NOT NULL,
                `spiritFieldPlants` TEXT NOT NULL, `activeSectId` TEXT NOT NULL,
                `residenceSlots` TEXT NOT NULL, `warehouseGarrisons` TEXT NOT NULL,
                `patrolSlots` TEXT NOT NULL, `patrolConfig` TEXT NOT NULL,
                `patrolConfigs` TEXT NOT NULL, `pendingPatrolBattleResults` TEXT NOT NULL,
                `alliances` TEXT NOT NULL, `vassalContracts` TEXT NOT NULL,
                `sectRelations` TEXT NOT NULL, `playerAllianceSlots` INTEGER NOT NULL,
                `sectPolicies` TEXT NOT NULL, `open_recruitment_last_paid_month` INTEGER NOT NULL,
                `battleTeam` TEXT,
                `aiBattleTeams` TEXT NOT NULL, `usedRedeemCodes` TEXT NOT NULL,
                `mailRecords` TEXT NOT NULL, `sectLevelClaimRecords` TEXT NOT NULL,
                `save_version` INTEGER NOT NULL DEFAULT 0,
                `playerProtectionEnabled` INTEGER NOT NULL, `playerProtectionStartYear` INTEGER NOT NULL,
                `playerHasAttackedAI` INTEGER NOT NULL, `activeMissions` TEXT NOT NULL,
                `availableMissions` TEXT NOT NULL, `autoRecruitSpiritRootFilter` TEXT NOT NULL,
                `daoCompanionBannedRootCounts` TEXT NOT NULL,
                `daoCompanionConsentRequired` INTEGER NOT NULL, `patrolBattleResultPopup` INTEGER NOT NULL,
                `autoSellMidGradeForPurchase` INTEGER NOT NULL,
                `autoSellHighGradeForPurchase` INTEGER NOT NULL,
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
                `lastYearSpiritStoneIncome` INTEGER NOT NULL, `activeAttackWarnings` TEXT NOT NULL,
                `shownWarningStageIds` TEXT NOT NULL, `sectAttackCooldowns` TEXT NOT NULL,
                `sectBattleRecords` TEXT NOT NULL, `gameEventRecords` TEXT NOT NULL,
                `guideClaimedRewardIds` TEXT NOT NULL, `guideCounters` TEXT NOT NULL,
                `map_seed` INTEGER NOT NULL DEFAULT 0,
                `annual_income_by_source` TEXT NOT NULL, `annual_expenditure_by_reason` TEXT NOT NULL,
                `annual_total_income` INTEGER NOT NULL, `annual_total_expenditure` INTEGER NOT NULL,
                `annual_alchemy_count` INTEGER NOT NULL, `annual_forge_count` INTEGER NOT NULL,
                `annual_herb_count` INTEGER NOT NULL, `annual_new_disciples` INTEGER NOT NULL,
                `annual_deceased_disciples` INTEGER NOT NULL, `annual_deserted_disciples` INTEGER NOT NULL,
                `annual_theft_count` INTEGER NOT NULL DEFAULT 0,
                `annual_equipment_by_source` TEXT NOT NULL, `annual_pill_by_source` TEXT NOT NULL,
                `annual_herb_by_source` TEXT NOT NULL, `yearly_reports` TEXT NOT NULL,
                PRIMARY KEY(`id`, `slot_id`)
            )
        """.trimIndent()

        /**
         * 重建 game_data 表（create-copy-drop-rename 模式）。
         *
         * 使用显式 CREATE TABLE 保留完整约束（NOT NULL、DEFAULT、PRIMARY KEY），
         * 并对 NOT NULL 列使用 IFNULL 兜底，防止旧版 CTAS 引入的 NULL 值导致
         * NOT NULL constraint failed。
         *
         * @param oldSuffix 旧表重命名后缀（如 "_old"）
         * @param sourceColumns 旧表的列名列表（带引号），仅复制这些列
         */
        internal fun rebuildGameData(
            db: SupportSQLiteDatabase,
            oldSuffix: String,
            sourceColumns: List<String>
        ) {
            // 校验后缀：仅允许字母数字下划线，防止 SQL 注入
            require(oldSuffix.matches(Regex("^[a-zA-Z0-9_]+$"))) {
                "rebuildGameData: invalid suffix '$oldSuffix' — only alphanumeric and underscore allowed"
            }
            val oldTable = "game_data$oldSuffix"

            db.execSQL("ALTER TABLE game_data RENAME TO $oldTable")
            db.execSQL(GAME_DATA_CREATE_SQL)

            // 先读取旧表实际存在的列名集合
            val oldCursor = db.query("PRAGMA table_info($oldTable)")
            val oldColumnNames = mutableSetOf<String>()
            oldCursor.use {
                while (it.moveToNext()) {
                    oldColumnNames.add(it.getString(it.getColumnIndexOrThrow("name")))
                }
            }

            // 从新表读取所有列定义，逐列决定 SELECT 源：
            // - 旧表已有的列 → IFNULL(旧表列, 默认值)（兜底旧数据中的 NULL）
            // - 仅新表有的列 → 直接使用 DEFAULT 值或按类型兜底
            // 避免 GAME_DATA_CREATE_SQL 包含后续新增列时，
            // SELECT 引用旧表不存在的列导致 SQLITE_ERROR。
            val newCursor = db.query("PRAGMA table_info(game_data)")
            val selectParts = mutableListOf<String>()
            newCursor.use {
                while (it.moveToNext()) {
                    val name = it.getString(it.getColumnIndexOrThrow("name"))
                    val notNull = it.getInt(it.getColumnIndexOrThrow("notnull")) == 1
                    val defaultVal = it.getString(it.getColumnIndexOrThrow("dflt_value"))
                    val type = it.getString(it.getColumnIndexOrThrow("type"))
                    val quotedName = "\"$name\""

                    // 列存在于旧表中 → 从旧表 SELECT（含 IFNULL 兜底）
                    // 列不存在于旧表中 → 使用 SQLite DEFAULT 值
                    if (name in oldColumnNames) {
                        if (notNull) {
                            val fallback = if (defaultVal != null) {
                                defaultVal
                            } else {
                                // 无 SQLite 默认值，按类型提供安全兜底
                                when (type.uppercase(Locale.ROOT)) {
                                    "INTEGER" -> "0"
                                    "REAL" -> "0.0"
                                    "TEXT" -> "''"
                                    else -> "0"
                                }
                            }
                            selectParts.add("IFNULL($oldTable.$quotedName, $fallback) AS $quotedName")
                        } else {
                            selectParts.add("$oldTable.$quotedName AS $quotedName")
                        }
                    } else {
                        // 仅新表有的列：用 DEFAULT 值
                        val literal = if (defaultVal != null) {
                            defaultVal
                        } else {
                            when (type.uppercase(Locale.ROOT)) {
                                "INTEGER" -> "0"
                                "REAL" -> "0.0"
                                "TEXT" -> "''"
                                else -> "0"
                            }
                        }
                        selectParts.add("$literal AS $quotedName")
                    }
                }
            }

            val selectSql = selectParts.joinToString(", ")
            // selectParts 为空意味着新表列全部在旧表中不存在——这不可能发生
            // （至少有 id/slot_id 等基础列是始终存在的）。
            // 若触发此断言，说明 GAME_DATA_CREATE_SQL 或迁移逻辑有严重问题。
            check(selectParts.isNotEmpty()) {
                "rebuildGameData: no columns matched between new and old table for suffix '$oldSuffix'"
            }
            db.execSQL("INSERT INTO `game_data` SELECT $selectSql FROM `$oldTable`")
            db.execSQL("DROP TABLE IF EXISTS `$oldTable`")

            // 重建 5 个索引
            rebuildGameDataIndices(db)
        }

        /** 重建 game_data 表的 5 个索引（create-copy-drop-rename 后必须重建） */
        internal fun rebuildGameDataIndices(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_game_data_slot_id` ON `game_data` (`slot_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_game_data_lastSaveTime` ON `game_data` (`lastSaveTime`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_game_data_gameYear_gameMonth` ON `game_data` (`gameYear`, `gameMonth`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_game_data_sectName` ON `game_data` (`sectName`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_game_data_spiritStones` ON `game_data` (`spiritStones`)")
        }

        /**
         * Room v25 生成的 storage_bags 表 CREATE TABLE SQL（单行，来自 25.json createSql）。
         * 必须与 StorageBag 实体完全一致：无 DEFAULT 子句。
         */
        internal val STORAGE_BAGS_CREATE_SQL =
            "CREATE TABLE IF NOT EXISTS `storage_bags` (`id` TEXT NOT NULL, `slot_id` INTEGER NOT NULL, `name` TEXT NOT NULL, `rarity` INTEGER NOT NULL, `description` TEXT NOT NULL, `quantity` INTEGER NOT NULL, `isLocked` INTEGER NOT NULL, PRIMARY KEY(`id`, `slot_id`))"

        /** 重建 storage_bags 表，使用 Room 生成的正确 schema（无 DEFAULT）。
         *  注意：避免与 rebuildGameData 共用 _old 后缀以防事务内冲突。 */
        internal fun rebuildStorageBags(db: SupportSQLiteDatabase) {
            // 一次性读取列名（单个 use 块内，防 StaleDataException）
            val oldColumns = mutableListOf<String>()
            val infoCursor = db.query("PRAGMA table_info(storage_bags)")
            var tableExists = false
            infoCursor.use {
                while (it.moveToNext()) {
                    tableExists = true
                    oldColumns.add("\"${it.getString(it.getColumnIndexOrThrow("name"))}\"")
                }
            }
            if (!tableExists) {
                Log.w(TAG, "storage_bags table does not exist, skipping rebuild")
                return
            }
            val colList = oldColumns.joinToString(", ")

            // 创建新表（临时名称 _v2），复制数据，替换旧表
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `storage_bags_v2` (`id` TEXT NOT NULL, `slot_id` INTEGER NOT NULL, " +
                "`name` TEXT NOT NULL, `rarity` INTEGER NOT NULL, `description` TEXT NOT NULL, " +
                "`quantity` INTEGER NOT NULL, `isLocked` INTEGER NOT NULL, PRIMARY KEY(`id`, `slot_id`))"
            )
            db.execSQL("INSERT INTO `storage_bags_v2` SELECT $colList FROM `storage_bags`")
            db.execSQL("DROP TABLE IF EXISTS `storage_bags`")
            db.execSQL("ALTER TABLE `storage_bags_v2` RENAME TO `storage_bags`")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_storage_bags_slot_id ON storage_bags(`slot_id`)")
            Log.i(TAG, "rebuilt storage_bags (removed DEFAULT clauses to match entity schema)")
        }
