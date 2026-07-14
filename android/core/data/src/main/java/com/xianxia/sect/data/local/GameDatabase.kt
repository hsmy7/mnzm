package com.xianxia.sect.data.local

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.data.incremental.ChangeLogEntity
import com.xianxia.sect.data.incremental.ChangeLogDao
import com.xianxia.sect.data.archive.ArchivedBattleLog
import com.xianxia.sect.data.archive.ArchivedDisciple
import com.xianxia.sect.data.archive.ArchivedBattleLogDao
import com.xianxia.sect.data.archive.ArchivedDiscipleDao
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object GameDatabaseConfig {
    const val MEMORY_CACHE_SIZE = 64 * 1024 * 1024
    const val DISK_CACHE_SIZE = 100 * 1024 * 1024
    const val WRITE_BATCH_SIZE = 100
    const val WRITE_DELAY_MS = 1000L
    const val WAL_CHECK_INTERVAL_SECONDS = 30L
    const val WAL_SIZE_THRESHOLD_MB = 10L
    const val WAL_CRITICAL_SIZE_MB = 50L
    const val CHECKPOINT_COOLDOWN_MS = 10000L
    const val QUERY_THREAD_COUNT = 2
}


@Database(
    entities = [
        GameData::class,
        Disciple::class,
        DiscipleCore::class,
        DiscipleCombatStats::class,
        DiscipleEquipment::class,
        DiscipleExtended::class,
        DiscipleAttributes::class,
        EquipmentStack::class,
        EquipmentInstance::class,
        ManualStack::class,
        ManualInstance::class,
        Pill::class,
        Material::class,
        Seed::class,
        Herb::class,
        ExplorationTeam::class,
        BuildingSlot::class,
        Recipe::class,
        BattleLog::class,
        ProductionSlot::class,
        ChangeLogEntity::class,
        SaveSlotMetadata::class,
        ArchivedBattleLog::class,
        ArchivedDisciple::class,
        GameHeavyData::class,
        StorageBag::class,
        MailEntity::class,
        DiplomacyState::class,
        ProductionState::class,
        PatrolStateEntity::class,
        WorldMapStateEntity::class,
        SectPolicyState::class,
        DiscipleCompact::class
    ],
    version = 18  // v18: MIGRATION_17_18 删除 forge_slots/alchemy_slots 两张僵尸表
)

@TypeConverters(ProtobufConverters::class, EnumConverters::class, CollectionConverters::class, JsonConverters::class)
abstract class GameDatabase : RoomDatabase() {

    abstract fun gameDataDao(): GameDataDao
    abstract fun discipleDao(): DiscipleDao
    abstract fun discipleCoreDao(): DiscipleCoreDao
    abstract fun discipleCombatStatsDao(): DiscipleCombatStatsDao
    abstract fun discipleEquipmentDao(): DiscipleEquipmentDao
    abstract fun discipleExtendedDao(): DiscipleExtendedDao
    abstract fun discipleAttributesDao(): DiscipleAttributesDao
    abstract fun equipmentStackDao(): EquipmentStackDao
    abstract fun equipmentInstanceDao(): EquipmentInstanceDao
    abstract fun manualStackDao(): ManualStackDao
    abstract fun manualInstanceDao(): ManualInstanceDao
    abstract fun pillDao(): PillDao
    abstract fun materialDao(): MaterialDao
    abstract fun seedDao(): SeedDao
    abstract fun herbDao(): HerbDao
    abstract fun storageBagDao(): StorageBagDao
    abstract fun explorationTeamDao(): ExplorationTeamDao
    abstract fun buildingSlotDao(): BuildingSlotDao
    abstract fun recipeDao(): RecipeDao
    abstract fun battleLogDao(): BattleLogDao
    abstract fun productionSlotDao(): ProductionSlotDao
    abstract fun changeLogDao(): ChangeLogDao
    abstract fun saveSlotMetadataDao(): SaveSlotMetadataDao

    abstract fun archivedBattleLogDao(): ArchivedBattleLogDao
    abstract fun archivedDiscipleDao(): ArchivedDiscipleDao

    abstract fun gameHeavyDataDao(): GameHeavyDataDao

    abstract fun mailDao(): MailDao

    abstract fun diplomacyStateDao(): DiplomacyStateDao
    abstract fun productionStateDao(): ProductionStateDao
    abstract fun patrolStateDao(): PatrolStateDao
    abstract fun worldMapStateDao(): WorldMapStateDao
    abstract fun sectPolicyStateDao(): SectPolicyStateDao

    abstract fun discipleCompactDao(): DiscipleCompactDao

    // ── WAL Checkpoint 管理（简化版） ──
    // 移除独立 ScheduledExecutorService 线程，避免与 Room 事务线程发生 WAL 文件竞争。
    // 运行时仅使用 PASSIVE 模式（在 post-save 中调用），TRUNCATE 仅在 shutdown 时使用。
    // 参考: SQLite WAL checkpoint 分析 — TRUNCATE 在并发时自动降级为 PASSIVE 不报错
    //       Room KMP ConnectionPool — WAL 模式使用 1 writer + N readers
    private val totalCheckpoints = AtomicLong(0)
    private val totalWalSizeFreed = AtomicLong(0)
    private val lastCheckpointTimeMs = AtomicLong(0)

    @Volatile
    private var isShuttingDown = false

    /** 在保存完成后执行 PASSIVE checkpoint（在 Room 事务线程上运行） */
    fun performPostSaveCheckpoint() {
        try {
            if (isShuttingDown) return
            performCheckpointSync(CheckpointMode.PASSIVE)
        } catch (e: Exception) {
            Log.w(TAG, "Post-save checkpoint failed", e)
        }
    }

    private fun performCheckpointSync(mode: CheckpointMode) {
        try {
            openHelper.writableDatabase.execSQL(mode.query)
            totalCheckpoints.incrementAndGet()
            lastCheckpointTimeMs.set(System.currentTimeMillis())
            Log.d(TAG, "Checkpoint performed: ${mode.name}")
        } catch (e: android.database.sqlite.SQLiteException) {
            if (e.message?.contains("query or rawQuery") == true) {
                Log.w(TAG, "execSQL rejected for checkpoint, using rawQuery fallback")
                try {
                    openHelper.writableDatabase.query(mode.query, emptyArray()).close()
                    Log.d(TAG, "Checkpoint performed via query: ${mode.name}")
                } catch (q: Exception) {
                    Log.e(TAG, "Failed to perform checkpoint (${mode.name}) via query", q)
                }
            } else {
                Log.e(TAG, "Failed to perform checkpoint (${mode.name})", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to perform checkpoint (${mode.name})", e)
        }
    }

    fun getDatabaseSize(): Long {
        return try {
            val path = openHelper.writableDatabase.path ?: return 0
            val file = File(path)
            if (file.exists()) file.length() else 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get database size", e)
            0
        }
    }

    fun getWalFileSize(): Long {
        return try {
            val dbPath = openHelper.writableDatabase.path ?: return 0
            val walFile = File(dbPath + "-wal")
            if (walFile.exists()) walFile.length() else 0
        } catch (e: Exception) {
            0
        }
    }

    fun getShmFileSize(): Long {
        return try {
            val dbPath = openHelper.writableDatabase.path ?: return 0
            val shmFile = File(dbPath + "-shm")
            if (shmFile.exists()) shmFile.length() else 0
        } catch (e: Exception) {
            0
        }
    }

    fun getDatabaseStats(): DatabaseStats {
        val dbSize = getDatabaseSize()
        val walSize = getWalFileSize()
        val shmSize = getShmFileSize()

        return DatabaseStats(
            databaseSize = dbSize,
            walSize = walSize,
            shmSize = shmSize,
            totalSize = dbSize + walSize + shmSize,
            totalCheckpoints = totalCheckpoints.get(),
            totalWalFreed = totalWalSizeFreed.get(),
            lastCheckpointTime = lastCheckpointTimeMs.get()
        )
    }

    fun shutdown() {
        Log.i(TAG, "Shutting down unified database instance")
        isShuttingDown = true

        // checkpoint executor 已移除，WAL checkpoint 由 post-save 处理

        try {
            val db = openHelper.writableDatabase
            if (!db.isOpen) {
                Log.w(TAG, "Database already closed, skipping final checkpoint")
            } else {
                // shutdown 时无并发事务，可使用 TRUNCATE 确保 WAL 完全落盘
                performCheckpointSync(CheckpointMode.TRUNCATE)
                Log.i(TAG, "Final TRUNCATE checkpoint completed - WAL data flushed to main DB")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Final checkpoint failed - attempting to continue with close", e)
        }

        try {
            if (openHelper.writableDatabase.isOpen) {
                close()
                Log.i(TAG, "Unified database closed successfully")
            } else {
                Log.w(TAG, "Database was already closed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error closing database", e)
        }

        Log.i(TAG, "Unified database instance shutdown completed")
    }

    enum class CheckpointMode(val query: String) {
        PASSIVE("PRAGMA wal_checkpoint(PASSIVE)"),
        FULL("PRAGMA wal_checkpoint(FULL)"),
        TRUNCATE("PRAGMA wal_checkpoint(TRUNCATE)")
    }

    data class DatabaseStats(
        val databaseSize: Long = 0L,
        val walSize: Long = 0L,
        val shmSize: Long = 0L,
        val totalSize: Long = 0L,
        val totalCheckpoints: Long = 0L,
        val totalWalFreed: Long = 0L,
        val lastCheckpointTime: Long = 0L
    )

    companion object {
        private const val TAG = "GameDatabase"
        private const val UNIFIED_DB_NAME = "xianxia_sect.db"

        /** 4.0.13: 新增 sectLevelClaimRecords 列 — 宗门等级每周奖励领取记录 */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE game_data ADD COLUMN sectLevelClaimRecords TEXT " +
                    "NOT NULL DEFAULT '[]'"
                )
                Log.i(TAG, "Migration 2→3: added sectLevelClaimRecords column to game_data")
            }
        }

        /** 4.0.13: 新增 saveVersion 列 + 修炼基础值缩放 1/10 */
        val MIGRATION_3_4 = object : Migration(3, 4) {
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
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE game_data ADD COLUMN autoBuyList TEXT " +
                    "NOT NULL DEFAULT '[]'"
                )
                Log.i(TAG, "Migration 4→5: added autoBuyList column")
            }
        }

        /** v5→v6: 新增 buildingInstanceId + bloodRefinementBonusTotals + usage_lastTheftMonth 列 */
        val MIGRATION_5_6 = object : Migration(5, 6) {
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

        val MIGRATION_6_7 = object : Migration(6, 7) {
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
        val MIGRATION_7_8 = object : Migration(7, 8) {
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
        val MIGRATION_8_9 = object : Migration(8, 9) {
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
        val MIGRATION_9_10 = object : Migration(9, 10) {
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
        val MIGRATION_10_11 = object : Migration(10, 11) {
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
                Log.i(TAG, "Migration 10→11: added vassalContracts, sectBattleRecords")
            }
        }

        /** v11→v12: 新增 map_seed 列 — 宗门地图随机种子 */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!columnExists(db, "game_data", "map_seed")) {
                    db.execSQL(
                        "ALTER TABLE game_data ADD COLUMN map_seed INTEGER NOT NULL DEFAULT 0"
                    )
                }
                Log.i(TAG, "Migration 11→12: added map_seed")
            }
        }

        /** v12→v13: 移除 isGameStarted 列 — 迁移到 GameLifecycle 纯运行时状态 */
        val MIGRATION_12_13 = object : Migration(12, 13) {
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
        val MIGRATION_13_14 = object : Migration(13, 14) {
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

        val MIGRATION_14_15 = object : Migration(14, 15) {
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
        val MIGRATION_15_16 = object : Migration(15, 16) {
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
        val MIGRATION_16_17 = object : Migration(16, 17) {
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
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS forge_slots")
                db.execSQL("DROP TABLE IF EXISTS alchemy_slots")
                Log.i(TAG, "Migration 17→18: dropped forge_slots and alchemy_slots tables")
            }
        }

        /**
         * 检查表中是否存在指定列。
         * 用于处理错误的 Migration 回填（已存在列重复 ALTER 会崩溃）。
         */
        private fun columnExists(
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
        private val threadCounter = AtomicInteger(0)

        fun create(context: Context): GameDatabase {
            Log.i(TAG, "Creating unified single-instance database: $UNIFIED_DB_NAME")

            return Room.databaseBuilder(
                context.applicationContext,
                GameDatabase::class.java,
                UNIFIED_DB_NAME
            )
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .setQueryExecutor(
                    Executors.newFixedThreadPool(GameDatabaseConfig.QUERY_THREAD_COUNT) { r ->
                        Thread(r, "GameDB-Query-${threadCounter.incrementAndGet()}")
                    }
                )
                .setTransactionExecutor(
                    Executors.newSingleThreadExecutor { r ->
                        Thread(r, "GameDB-Txn")
                    }
                )
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        Log.i(TAG, "Unified database created")
                        configureDatabase(db, context)
                    }
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        Log.i(TAG, "Unified database opened")
                        optimizeDatabase(db)
                        // 启动时检查数据库完整性（PRAGMA integrity_check）
                        // 在 MIGRATION_12_13/13_14 的 create-copy-drop-rename 后确保 schema 正确
                        checkDatabaseIntegrity(db)
                    }
                })
                .fallbackToDestructiveMigration()
                .build()
                .also { db -> applySafetyPragmas(db) }
        }

        fun getUnifiedDatabaseFile(context: Context): File {
            return context.getDatabasePath(UNIFIED_DB_NAME)
        }

        private fun applySafetyPragmas(db: GameDatabase) {
            try {
                db.openHelper.writableDatabase.execSQL("PRAGMA synchronous = NORMAL")
                Log.d(TAG, "PRAGMA synchronous = NORMAL applied")
            } catch (e: android.database.sqlite.SQLiteException) {
                if (e.message?.contains("query or rawQuery") == true) {
                    Log.w(TAG, "execSQL rejected for synchronous pragma, using rawQuery fallback")
                    try {
                        db.openHelper.writableDatabase.query("PRAGMA synchronous = NORMAL", emptyArray()).close()
                        Log.d(TAG, "PRAGMA synchronous = NORMAL applied via query")
                    } catch (e: Exception) { Log.w(TAG, "PRAGMA synchronous query fallback also failed: ${e.message}") }
                } else {
                    Log.w(TAG, "Failed to apply PRAGMA synchronous: ${e.message}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to apply PRAGMA synchronous: ${e.message}")
            }
        }

        private fun configureDatabase(db: SupportSQLiteDatabase, context: Context? = null) {
            Log.d(TAG, "Configuring database parameters")

            val dynamicCacheSize = resolveDynamicCacheSize(context)

            executeSafely(db, "PRAGMA journal_mode = WAL")
            executeSafely(db, "PRAGMA synchronous = NORMAL")
            executeSafely(db, "PRAGMA cache_size = $dynamicCacheSize")
            // temp_store: 仅在 >= 4GB RAM 设备上使用 MEMORY，避免低端设备内存压力
            val totalMemMB = resolveTotalMem(context)
            if (totalMemMB >= 4096) {
                executeSafely(db, "PRAGMA temp_store = MEMORY")
            } else {
                executeSafely(db, "PRAGMA temp_store = FILE")
            }
            // mmap_size = 0: 禁用内存映射，避免 onTrimMemory 时内核解除 mmap 页面导致 SIGSEGV
            // 参考: SQLite 官方文档及 Bugly #5037 多个设备 libsqlite.so native 崩溃
            executeSafely(db, "PRAGMA mmap_size = 0")
            executeSafely(db, "PRAGMA foreign_keys = ON")
            executeSafely(db, "PRAGMA wal_autocheckpoint = 1000")
            executeSafely(db, "PRAGMA busy_timeout = 5000")
            executeSafely(db, "PRAGMA journal_size_limit = 5242880")

            Log.d(TAG, "Database configuration completed (mmap=0, cache=${-dynamicCacheSize / 1024}MB, temp_store=${if (totalMemMB >= 4096) "MEMORY" else "FILE"}, journal_limit=5MB)")
        }

        private fun resolveTotalMem(context: Context?): Long {
            if (context == null) return 4096L
            return try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                    ?: return 4096L
                val memInfo = android.app.ActivityManager.MemoryInfo()
                am.getMemoryInfo(memInfo)
                (memInfo.totalMem) / (1024 * 1024)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to detect memory", e)
                4096L
            }
        }

        private fun resolveDynamicCacheSize(context: Context?): Int {
            val defaultCachePages = -64000
            if (context == null) return defaultCachePages
            return try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                    ?: return defaultCachePages
                val memInfo = android.app.ActivityManager.MemoryInfo()
                am.getMemoryInfo(memInfo)
                val totalMemMB = memInfo.totalMem / (1024 * 1024)
                when {
                    totalMemMB < 2048 -> -16000
                    totalMemMB < 4096 -> -32000
                    else -> defaultCachePages
                }
            } catch (e: Exception) {
                defaultCachePages
            }
        }

        private fun optimizeDatabase(db: SupportSQLiteDatabase) {
            Log.d(TAG, "Running database optimization")
            executeSafely(db, "PRAGMA analysis_limit = 2000")
            executeSafely(db, "PRAGMA optimize")
            Log.d(TAG, "Database optimization completed (analysis_limit=2000)")
        }

        /**
         * 检查数据库完整性（PRAGMA integrity_check）。
         * 用于发现 MIGRATION 后的 schema 损坏或 mmap 破损。
         * 记录到 Bugly 以便排查 #5037 等 native 崩溃。
         */
        private fun checkDatabaseIntegrity(db: SupportSQLiteDatabase) {
            try {
                val cursor = db.query("PRAGMA integrity_check", emptyArray())
                try {
                    if (cursor.moveToFirst()) {
                        val result = cursor.getString(0)
                        if (result != "ok") {
                            Log.wtf(TAG, "DB INTEGRITY FAILED: $result")
                        }
                    }
                } finally {
                    cursor.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check database integrity", e)
            }
        }

        private fun executeSafely(db: SupportSQLiteDatabase, pragma: String) {
            try {
                db.execSQL(pragma)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to execute pragma: $pragma", e)
            }
        }
    }
}
