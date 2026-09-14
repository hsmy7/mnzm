package com.xianxia.sect.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase

import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.BuildingSlot
import com.xianxia.sect.core.model.DiplomacyState
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAttributes
import com.xianxia.sect.core.model.DiscipleCombatStats
import com.xianxia.sect.core.model.DiscipleCompact
import com.xianxia.sect.core.model.DiscipleCore
import com.xianxia.sect.core.model.DiscipleEquipment
import com.xianxia.sect.core.model.DiscipleExtended
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GameHeavyData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.MailEntity
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.PatrolStateEntity
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.ProductionState
import com.xianxia.sect.core.model.Recipe
import com.xianxia.sect.core.model.SectPolicyState
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.StorageBag
import com.xianxia.sect.core.model.WorldMapStateEntity
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.data.incremental.ChangeLogEntity
import com.xianxia.sect.data.incremental.ChangeLogDao
import com.xianxia.sect.data.archive.ArchivedBattleLog
import com.xianxia.sect.data.archive.ArchivedDisciple
import com.xianxia.sect.data.archive.ArchivedBattleLogDao
import com.xianxia.sect.data.archive.ArchivedDiscipleDao
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong



/** 文件级日志 TAG（迁移前备份恢复顶层辅助函数共用） */
private const val TAG = "GameDatabase"

/** 恢复尝试 marker 文件名——恢复后迁移崩溃时防止"恢复→崩溃"死循环 */
private const val RESTORE_ATTEMPT_MARKER = ".restore_attempted"
private const val RESTORE_ATTEMPT_MARKER_CONTENT = "1"

/** 迁移前备份文件大小上限（200MB，防恶意/损坏超大备份占满磁盘） */
private const val MAX_BACKUP_FILE_SIZE_BYTES = 200L * 1024 * 1024

/** 迁移前备份 game_data 行数上限（每槽一行，正常 ≤ 7；上限 64 防恶意行数膨胀） */
private const val MAX_BACKUP_GAME_DATA_ROWS = 64


object GameDatabaseConfig {
    /**
     * 数据库 schema 版本号——@Database(version) 与迁移前备份判据统一引用此常量，
     * 禁止任何位置硬编码版本号。
     * 升级数据库版本时必须同步递增此常量并注册 MIGRATION_(N-1)_N。
     */
    const val DATABASE_VERSION = 51

    /**
     * 判定是否应从迁移前备份恢复（纯逻辑，无 I/O——独立测试覆盖）。
     *
     * @param currentRowCount 当前数据库 game_data 行数（-1 = 读取失败/库打不开）
     * @param currentVersion 当前数据库 user_version（-1 = 读取失败）
     * @param backupVersion 迁移前备份的 user_version
     * @return true = 应恢复；false = 跳过
     */
    @Suppress("ReturnCount") // 恢复判定多分支守卫，多 return 为守卫风格
    fun shouldRestoreFromBackup(
        currentRowCount: Int,
        currentVersion: Int,
        backupVersion: Int
    ): Boolean {
        // 当前库打不开/表缺失（-1）或空库（destructive fallback 后）→ 恢复
        if (currentRowCount <= 0) return true
        // 降级场景：当前库版本高于 App 支持的版本（高版本 App 数据回退到低版本
        // App）→ Room 无法降级打开必然崩溃——用迁移前备份（旧版本、迁移链可达）恢复
        if (currentVersion > DATABASE_VERSION &&
            backupVersion in 2..DATABASE_VERSION && backupVersion < currentVersion
        ) {
            return true
        }
        // 有数据但迁移待完成（备份创建后迁移从未完成）→ 恢复
        return currentVersion < DATABASE_VERSION && backupVersion == currentVersion
    }

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
        DiscipleCompact::class,
        OverflowMailDraftEntity::class,
        DirectMailDraftEntity::class
    ],
    // v40: MIGRATION_39_40 game_data 新增战斗队伍持久化三列
    //（battle_teams/used_team_numbers/battle_teams_initialized）
    // v41: MIGRATION_40_41 game_data 新增 last_ai_sect_recruit_year 列
    //（AI 宗门弟子三年一度招募差值判据）
    // v42: MIGRATION_41_42 game_data 新增玉符（氪金货币）四列
    //（jade_symbols/jade_symbols_today/jade_day_anchor_ms/jade_accum_ms）
    // v43: MIGRATION_42_43 新增溢出/直发邮件草稿持久化两表
    //（overflow_mail_drafts/direct_mail_drafts，邮件事务化落盘）
    // v44: MIGRATION_43_44 弟子炼丹师/锻造师职业 4 列
    //（alchemyLevel/alchemyPromotionCount/forgeLevel/forgePromotionCount，
    // disciples 与 disciples_attributes 两表各 4 列）
    // v45: MIGRATION_44_45 世界地图探索队功能下线——删除 exploration_teams 表
    // v46: MIGRATION_45_46 弟子新增基础属性"资质"——disciples 与 disciples_attributes
    // 两表各加 aptitude 列（DEFAULT 50 为旧档自愈哨兵值）
    // v47: MIGRATION_46_47 game_data 新增"新增天赋/体质/词条"待确认产物列
    //（pending_trait_adds，玉符消耗玩法刷新结果持久化）
    // v48: MIGRATION_47_48 overflow_mail_drafts 新增 item_id 列
    //（溢出邮件附件携带物品模板 id，领取时精确还原物品而非随机生成）
    // v49: MIGRATION_48_49 game_data 新增"石板道路"列（roads，自动拼接道路数据）
    // v50: MIGRATION_49_50 自动存档残留清理——删除 game_data 与 sect_policy_state
    //（纯手动存档设计：autoSaveIntervalMonths 列已删除，实体字段 @Ignore 不映射）
    // v51: MIGRATION_50_51 地图冻结（WS-5b）——game_data 新增地形段两列
    //（map_gen_version 版本戳 + terrain_tiles 行主序 flat 瓦片段；
    // "存的地形恒优先"，仅无段才按 mapSeed 生成回填）
    version = GameDatabaseConfig.DATABASE_VERSION
)

@TypeConverters(ProtobufConverters::class, EnumConverters::class, CollectionConverters::class, JsonConverters::class)
@Suppress("TooManyFunctions") // Room 数据库契约面：33 个 abstract DAO 访问器 = Room 强制协议 + 迁移回调，
// 函数数=注册 DAO 数，拆分即破坏 RoomDatabase 单元
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

    abstract fun mailDraftDao(): MailDraftDao

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
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    fun performPostSaveCheckpoint() {
        try {
            if (isShuttingDown) return
            performCheckpointSync(CheckpointMode.PASSIVE)
        } catch (e: Exception) {
            Log.w(TAG, "Post-save checkpoint failed", e)
        }
    }

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
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

    /**
     * 裁剪迁移前备份（审计 P1-5 / 方案 D4 改动 1）：按版本号降序保留最近
     * [keep] 份 `.pre_migrate_backup.v{N}`，删除更旧。
     * 幂等可重入；接入两处——verifyAndRecoverDatabase 版本达标分支（每次
     * 启动 DB 打开）+ DataPruningScheduler 周期任务（双保险）。
     */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    fun pruneMigrationBackups(keep: Int = MIGRATION_BACKUP_RETENTION) {
        pruneMigrationBackups(openHelper.writableDatabase.path, keep)
    }


    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
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

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源不可枚举, 失败降级继续, 非静默吞噬
    fun getWalFileSize(): Long {
        return try {
            val dbPath = openHelper.writableDatabase.path ?: return 0
            val walFile = File(dbPath + "-wal")
            if (walFile.exists()) walFile.length() else 0
        } catch (ignored: Exception) {
            0
        }
    }

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源不可枚举, 失败降级继续, 非静默吞噬
    fun getShmFileSize(): Long {
        return try {
            val dbPath = openHelper.writableDatabase.path ?: return 0
            val shmFile = File(dbPath + "-shm")
            if (shmFile.exists()) shmFile.length() else 0
        } catch (ignored: Exception) {
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

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    fun shutdown() {
        Log.i(TAG, "Shutting down unified database instance")
        isShuttingDown = true

        // WAL checkpoint 由 post-save 处理

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

    // 伴生函数数量在 TooManyFunctions 阈值内
    @Suppress("TooManyFunctions") // 数据库工厂/备份/恢复/维护聚合，内聚单一职责
    companion object {
        private const val TAG = "GameDatabase"
        private const val UNIFIED_DB_NAME = "xianxia_sect.db"

        /** 迁移备份保留份数（审计 P1-5）：最近 2 个版本供降级恢复 */
        const val MIGRATION_BACKUP_RETENTION = 2
    /** 静态实现（companion 可达——verifyAndRecoverDatabase 为 companion 域） */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    fun pruneMigrationBackups(dbPath: String?, keep: Int = MIGRATION_BACKUP_RETENTION) {
        try {
            if (dbPath.isNullOrEmpty()) return
            val dir = File(dbPath).parentFile ?: return
            val dbFile = File(dbPath).name
            val prefix = "$dbFile.pre_migrate_backup.v"
            val backups = dir.listFiles { f -> f.name.startsWith(prefix) }
                ?.mapNotNull { f ->
                    f.name.removePrefix(prefix).toIntOrNull()?.let { v -> v to f }
                }
                ?: return
            backups.sortedByDescending { it.first }
                .drop(keep.coerceAtLeast(0))
                .forEach { (_, f) ->
                    if (f.delete()) Log.i(TAG, "Pruned old migration backup: ${f.name}")
                }
        } catch (e: Exception) {
            Log.w(TAG, "pruneMigrationBackups failed (non-fatal)", e)
        }
    }


        private const val RESTORE_ATTEMPT_MARKER_CONTENT = "1"

        private val threadCounter = AtomicInteger(0)

        /**
         * 在 Room migration 前备份 SQLite 数据库文件。
         * 将 xianxia_sect.db 复制到 xianxia_sect.db.pre_migrate_backup。
         * 仅当检测到需要 migration 时才执行，避免无意义的 I/O。
         *
         * 注意：WAL 模式下直接文件复制可能包含未检查点的 wal 数据。
         * 此处使用 PRAGMA wal_checkpoint(TRUNCATE) 先行落盘再复制。
         */
        @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
        fun backupDatabaseForMigration(context: Context) {
            val dbFile = context.getDatabasePath(UNIFIED_DB_NAME)
            if (!dbFile.exists()) {
                Log.d(TAG, "数据库文件不存在，跳过迁移前备份（首次安装）")
                return
            }

            // 读取当前数据库版本（PRAGMA user_version）
            var currentVersion = 0
            try {
                SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    val cursor = db.rawQuery("PRAGMA user_version", null)
                    if (cursor.moveToFirst()) currentVersion = cursor.getInt(0)
                    cursor.close()
                }
            } catch (e: Exception) {
                Log.w(TAG, "无法读取当前数据库版本，跳过迁移前备份", e)
                return
            }

            val targetVersion = GameDatabaseConfig.DATABASE_VERSION
            if (currentVersion >= targetVersion) {
                Log.d(TAG, "数据库已是最新版本 (v$currentVersion)，无需备份")
                return
            }
            if (currentVersion < 2) {
                // v1 数据库允许 fallbackToDestructiveMigrationFrom(1) 毁灭重建，无需备份
                Log.d(TAG, "数据库版本 v$currentVersion 低于 v2，允许毁灭回退，跳过备份")
                return
            }

            // 备份版本化命名 `{db}.pre_migrate_backup.v{currentVersion}`——迁移成功后
            // 保留备份：高版本 App 数据降回旧版 App 时仍需旧版本备份恢复，
            // 多版本保留供降级恢复与维护清理
            val backupFile = File(dbFile.absolutePath + ".pre_migrate_backup.v$currentVersion")
            try {
                // WAL 模式下先 checkpoint 确保数据一致性
                SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                    db.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
                }
                // 文件级复制备份
                dbFile.inputStream().use { input ->
                    backupFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.i(TAG, "迁移前备份完成: ${backupFile.absolutePath} (v$currentVersion → v$targetVersion)")
            } catch (e: Exception) {
                Log.e(TAG, "迁移前备份失败（非阻断，继续执行）", e)
                backupFile.delete()  // 清理不完整备份
            }
        }

        fun create(context: Context): GameDatabase {
            Log.i(TAG, "Creating unified single-instance database: $UNIFIED_DB_NAME")

            // 在 Room 迁移前备份 SQLite 文件，防止 migration 失败导致数据丢失
            backupDatabaseForMigration(context)

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
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
                    MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14,
                        MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19,
                            MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24,
                                MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29,
                                    MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33, MIGRATION_33_34,
                                        MIGRATION_34_35, MIGRATION_35_36, MIGRATION_36_37, MIGRATION_37_38,
                                            MIGRATION_38_39, MIGRATION_39_40, MIGRATION_40_41, MIGRATION_41_42,
                                                MIGRATION_42_43, MIGRATION_43_44, MIGRATION_44_45, MIGRATION_45_46,
                                                    MIGRATION_46_47, MIGRATION_47_48, MIGRATION_48_49,
                                                    MIGRATION_49_50, MIGRATION_50_51)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        Log.i(TAG, "Unified database created")
                        configureDatabase(db, context)
                    }
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        Log.i(TAG, "Unified database opened")
                        optimizeDatabase(db)
                        // 启动时检查数据库完整性，并在异常时自动尝试从备份恢复
                        verifyAndRecoverDatabase(db, context)
                    }
                })
                .fallbackToDestructiveMigrationFrom(1)
                .build()
                .also { db -> applySafetyPragmas(db) }
        }

        fun getUnifiedDatabaseFile(context: Context): File {
            return context.getDatabasePath(UNIFIED_DB_NAME)
        }

        @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
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

            Log.d(TAG, "Database configuration completed (mmap=0, cache=${-dynamicCacheSize / 1024}MB, " +
                "temp_store=${if (totalMemMB >= 4096) "MEMORY" else "FILE"}, journal_limit=5MB)")
        }

        @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
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

        @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源不可枚举, 失败降级继续, 非静默吞噬
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
            } catch (ignored: Exception) {
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
         * 检查数据库完整性并验证数据非空。
         * 如果 integrity_check 失败或 game_data 为空（可能由 destructive migration 导致），
         * 记录严重警告以便后续处理。
         * 实际的数据恢复通过以下机制完成：
         * 1. StorageEngine.load() → SaveFileManager.readWithFallback() 自动从 .sav/.bak 恢复
         * 2. 如果已调用 restoreFromBackupIfNeeded() 且 .pre_migrate_backup 存在，则文件级恢复优先
         */
        @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
        private fun verifyAndRecoverDatabase(db: SupportSQLiteDatabase, context: Context) {
            // Step 1: integrity_check
            val integrityOk = checkDatabaseIntegrity(db)

            // Step 2: 验证 game_data 表有数据
            val hasData = hasGameDataRows(db)

            // Step 3: 如果 integrity 失败或数据为空，记录严重警告
            // 实际恢复由 StorageEngine.load() → SaveFileManager 的 .sav/.bak 备份完成
            if (!integrityOk || !hasData) {
                Log.wtf(TAG, "数据库异常: integrity_ok=$integrityOk, has_data=$hasData, " +
                    "数据将由 StorageEngine 从 SaveFileManager 备份恢复")
            }

            // Step 4: 迁移成功（版本达到最新）后清理恢复 marker + 裁剪迁移
            // 备份（审计 P1-5：注释承诺的维护任务接线——版本达标即旧版备份
            // 价值衰减，按保留窗口留最近 MIGRATION_BACKUP_RETENTION 份）
            try {
                if (db.version >= GameDatabaseConfig.DATABASE_VERSION) {
                    val dbFile = context.getDatabasePath(UNIFIED_DB_NAME)
                    File(dbFile.absolutePath + RESTORE_ATTEMPT_MARKER).delete()
                    pruneMigrationBackups(dbFile.absolutePath)
                }
            } catch (e: Exception) {
                Log.w(TAG, "清理恢复 marker/迁移备份失败", e)
            }
        }

        /** Step 1: integrity_check：查询异常视为未通过 */
        @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
        private fun checkDatabaseIntegrity(db: SupportSQLiteDatabase): Boolean {
            return try {
                querySingleString(db, "PRAGMA integrity_check")?.let { result ->
                    val ok = result == "ok"
                    if (!ok) {
                        Log.wtf(TAG, "DB INTEGRITY FAILED: $result")
                    }
                    ok
                } ?: false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check database integrity", e)
                false
            }
        }

        /** Step 2: game_data 行数检查：表不存在等异常视为无数据 */
        @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
        private fun hasGameDataRows(db: SupportSQLiteDatabase): Boolean {
            return try {
                querySingleInt(db, "SELECT COUNT(*) FROM game_data") > 0
            } catch (e: Exception) {
                Log.w(TAG, "game_data 表不存在或查询异常", e)
                false
            }
        }

        /** 执行只读查询并返回首行首列字符串（无行返回 null） */
        private fun querySingleString(db: SupportSQLiteDatabase, sql: String): String? {
            val cursor = db.query(sql, emptyArray())
            return cursor.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }

        /** 执行只读查询并返回首行首列 Int（无行返回 0） */
        private fun querySingleInt(db: SupportSQLiteDatabase, sql: String): Int {
            val cursor = db.query(sql, emptyArray())
            return cursor.use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
        }

        /**
         * 在 Room databaseBuilder 执行前检查并恢复备份。
         * 如果 pre_migrate_backup 文件存在且当前数据库为空/损坏，
         * 用备份文件覆盖当前数据库。
         *
         * 此方法必须在 [create] 之前调用。当前由 AppModule.provideGameDatabase 调用。
         *
         * @return true 表示已执行恢复，false 表示无需恢复或恢复失败
         */
        @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
        fun restoreFromBackupIfNeeded(context: Context): Boolean {
            val dbFile = context.getDatabasePath(UNIFIED_DB_NAME)
            val backupFile = findVersionedBackup(dbFile) ?: return false
            val markerFile = File(dbFile.absolutePath + RESTORE_ATTEMPT_MARKER)
            if (!dbFile.exists()) return false

            // 验证备份文件可用（含大小/行数上限检查）
            val backup = readBackupInfo(backupFile)
            if (!backup.ok) {
                Log.w(TAG, "备份文件 integrity_check 失败，不可用于恢复")
                return false
            }

            // 读取当前数据库版本号与 game_data 行数
            val current = readCurrentDbInfo(dbFile)

            if (!shouldAttemptRestore(current, backup, markerFile)) {
                return false
            }
            logMigrationPendingIfAny(current, backup)

            // 执行恢复：用备份文件覆盖当前数据库
            return performFileRestoreWithMarker(dbFile, backupFile, backup, markerFile)
        }

        /**
         * 恢复前置判定：当前库无数据、判定不通过或存在恢复 marker 时跳过并记录原因。
         *
         * - 迁移待完成（迁移崩溃后 DB 行数仍 > 0）场景的判定在
         *   GameDatabaseConfig.shouldRestoreFromBackup 纯函数中，便于单元测试
         * - 恢复-迁移崩溃死循环防护：上次恢复后迁移仍未完成（marker 存在）→
         *   跳过重复恢复，让 Room 直接尝试迁移并崩溃报错（行为可预期）
         */
        private fun shouldAttemptRestore(
            current: CurrentDbInfo,
            backup: BackupValidation,
            markerFile: File
        ): Boolean {
            if (!GameDatabaseConfig.shouldRestoreFromBackup(
                    current.rowCount, current.version, backup.version
                )
            ) {
                Log.d(TAG, "当前数据库有数据 (${current.rowCount} 行)，跳过备份恢复")
                return false
            }
            if (markerFile.exists()) {
                Log.w(TAG, "检测到上次恢复后迁移仍未完成，跳过重复恢复（防止死循环）")
                return false
            }
            return true
        }

        /** 迁移未完成场景告警：备份与当前库同版本且低于目标版本 */
        private fun logMigrationPendingIfAny(current: CurrentDbInfo, backup: BackupValidation) {
            if (current.version < GameDatabaseConfig.DATABASE_VERSION &&
                backup.version == current.version
            ) {
                Log.w(TAG, "检测到迁移未完成 (v${current.version} → v" +
                    "${GameDatabaseConfig.DATABASE_VERSION})，从迁移前备份恢复")
            }
        }

        /**
         * 文件覆盖恢复 + marker 写入。
         * renameTo 失败时 performFileRestore 内部回退 copyTo 覆盖；仍失败返回
         * false——恢复失败时不得报"恢复成功"且不得写 marker（marker 会阻断
         * 下次启动的恢复路径）。
         */
        @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
        private fun performFileRestoreWithMarker(
            dbFile: File,
            backupFile: File,
            backup: BackupValidation,
            markerFile: File
        ): Boolean {
            return try {
                // -wal/-shm 必须在"确定要恢复"之后才清理：恢复覆盖后若残留旧 -wal，
                // SQLite 会将其重放到恢复后的文件上污染结果；提前清理则会误删
                // 正常崩溃会话未 checkpoint 的进度
                File(dbFile.absolutePath + "-wal").delete()
                File(dbFile.absolutePath + "-shm").delete()
                if (!performFileRestore(dbFile, backupFile)) {
                    Log.e(TAG, "文件覆盖失败，未执行恢复 (backup=${backupFile.absolutePath})")
                    return false
                }
                // 创建恢复 marker：迁移成功后由 verifyAndRecoverDatabase 清理
                try {
                    markerFile.writeText(RESTORE_ATTEMPT_MARKER_CONTENT)
                } catch (e: Exception) {
                    Log.w(TAG, "创建恢复 marker 失败", e)
                }
                Log.w(TAG, "数据库已从备份恢复 (backup_rows=${backup.rowCount}, " +
                    "backup=${backupFile.absolutePath})")
                true
            } catch (e: Exception) {
                Log.e(TAG, "从备份恢复数据库失败", e)
                false
            }
        }

        /**
         * 扫描可用的迁移前备份文件。
         *
         * 备份版本化命名 `{db}.pre_migrate_backup.v{N}`，选择最高可用版本
         * （N ∈ 2..DATABASE_VERSION-1）——降级场景（高版本 App 回退）需要比
         * 当前库版本低的备份；同时兼容旧的无版本后缀备份（`.pre_migrate_backup`）。
         */
        private fun findVersionedBackup(dbFile: File): File? {
            val legacy = File(dbFile.absolutePath + ".pre_migrate_backup")
            val versioned = (2 until GameDatabaseConfig.DATABASE_VERSION).mapNotNull { v ->
                File(dbFile.absolutePath + ".pre_migrate_backup.v$v").takeIf { it.exists() }
            }.maxByOrNull { it.name.substringAfterLast(".v").toIntOrNull() ?: -1 }
            return versioned ?: legacy.takeIf { it.exists() }
        }

        /** 备份文件验证结果 */

        @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
        private fun executeSafely(db: SupportSQLiteDatabase, pragma: String) {
            try {
                db.execSQL(pragma)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to execute pragma: $pragma", e)
            }
        }
    }
}




// ==================== 迁移前备份恢复辅助（文件顶层私有） ====================

/** 备份文件验证结果 */
private data class BackupValidation(val ok: Boolean, val rowCount: Int, val version: Int)

/** 当前数据库信息 */
private data class CurrentDbInfo(val rowCount: Int, val version: Int)

/** 读取数据库的 user_version（读取失败返回 -1） */
@Suppress("TooGenericExceptionCaught")
private fun readUserVersion(db: SQLiteDatabase): Int {
    return try {
        val vc = db.rawQuery("PRAGMA user_version", null)
        val v = if (vc.moveToFirst()) vc.getInt(0) else -1
        vc.close()
        v
    } catch (_: Exception) {
        -1
    }
}

/** 读取数据库 game_data 表行数（读取失败返回 -1） */
@Suppress("TooGenericExceptionCaught")
private fun readGameDataRowCount(db: SQLiteDatabase): Int {
    return try {
        val rc = db.rawQuery("SELECT COUNT(*) FROM game_data", null)
        val n = if (rc.moveToFirst()) rc.getInt(0) else -1
        rc.close()
        n
    } catch (_: Exception) {
        -1
    }
}

/** 验证迁移前备份文件可用性（integrity_check + user_version + game_data 行数 + 大小/行数上限） */
@Suppress("ReturnCount", "TooGenericExceptionCaught") // 备份多失败守卫，多 return 为守卫风格
private fun readBackupInfo(backupFile: File): BackupValidation {
    // 文件大小上限——恶意/损坏超大备份会占满磁盘
    if (backupFile.length() > MAX_BACKUP_FILE_SIZE_BYTES) {
        Log.w(TAG, "备份文件过大 (${backupFile.length() / 1024 / 1024}MB)，视为无效")
        return BackupValidation(false, -1, -1)
    }
    var ok = false
    try {
        SQLiteDatabase.openDatabase(backupFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { bdb ->
            val cursor = bdb.rawQuery("PRAGMA integrity_check", null)
            ok = cursor.moveToFirst() && cursor.getString(0) == "ok"
            cursor.close()
        }
    } catch (e: Exception) {
        Log.e(TAG, "备份文件验证失败: ${backupFile.absolutePath}", e)
    }
    if (!ok) return BackupValidation(false, -1, -1)
    val rowCount = try {
        SQLiteDatabase.openDatabase(backupFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { bdb ->
            readGameDataRowCount(bdb)
        }
    } catch (e: Exception) {
        Log.e(TAG, "备份文件行数读取失败: ${backupFile.absolutePath}", e)
        -1
    }
    // game_data 行数上限（正常每槽一行 ≤ 7）
    if (rowCount > MAX_BACKUP_GAME_DATA_ROWS) {
        Log.w(TAG, "备份 game_data 行数异常 ($rowCount)，视为无效")
        return BackupValidation(false, -1, -1)
    }
    val version = try {
        SQLiteDatabase.openDatabase(backupFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { bdb ->
            readUserVersion(bdb)
        }
    } catch (e: Exception) {
        Log.e(TAG, "备份文件版本读取失败: ${backupFile.absolutePath}", e)
        -1
    }
    return BackupValidation(ok, rowCount, version)
}

/** 读取当前数据库版本号与 game_data 行数（读取失败返回 -1） */
@Suppress("TooGenericExceptionCaught")
private fun readCurrentDbInfo(dbFile: File): CurrentDbInfo {
    val rowCount = try {
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { cdb ->
            readGameDataRowCount(cdb)
        }
    } catch (e: Exception) {
        Log.w(TAG, "当前数据库无法打开，将直接使用备份覆盖", e)
        -1
    }
    val version = try {
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { cdb ->
            readUserVersion(cdb)
        }
    } catch (e: Exception) {
        Log.w(TAG, "当前数据库版本读取失败", e)
        -1
    }
    return CurrentDbInfo(rowCount, version)
}

/** 用备份文件覆盖当前数据库（tmp 写入 + fsync + rename 原子替换） */
/**
 * 用备份文件覆盖当前数据库文件。
 *
 * renameTo 失败 → 回退 copyTo(overwrite=true) + 删除原文件（兼容不支持
 * 原子覆盖的文件系统）；仍失败 → 清理 .restore_tmp 并返回 false，
 * 调用方不写恢复 marker（避免恢复失败被误报为成功）。
 *
 * @return true 覆盖成功；false 覆盖失败（调用方不得标记恢复完成）
 */
@Suppress("ReturnCount", "TooGenericExceptionCaught") // 覆盖结果守卫风格；文件 IO 异常面广
private fun performFileRestore(dbFile: File, backupFile: File): Boolean {
    val tmpFile = File(dbFile.absolutePath + ".restore_tmp")
    try {
        backupFile.inputStream().use { input ->
            // 先写入 .tmp 防止覆盖过程中崩溃损坏原文件
            tmpFile.outputStream().use { output -> input.copyTo(output) }
            // fsync 确保写完
            FileOutputStream(tmpFile, true).use { fos -> fos.fd.sync() }
        }
        // 覆盖原文件：优先原子 rename，失败回退流拷贝
        if (tmpFile.renameTo(dbFile)) return true
        tmpFile.copyTo(dbFile, overwrite = true)
        if (!tmpFile.delete()) Log.w("GameDatabase", "覆盖回退后清理 .restore_tmp 失败")
        return true
    } catch (e: Exception) {
        Log.e("GameDatabase", "performFileRestore 失败", e)
        tmpFile.delete()
        return false
    }
}
