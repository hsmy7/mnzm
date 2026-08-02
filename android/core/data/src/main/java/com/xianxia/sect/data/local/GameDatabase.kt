package com.xianxia.sect.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
import java.io.FileOutputStream
import java.util.Locale
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
    version = 38  // v38: MIGRATION_37_38 新增远古秘境 4 列（secret_realm_*）
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

        private val threadCounter = AtomicInteger(0)

        /**
         * 在 Room migration 前备份 SQLite 数据库文件。
         * 将 xianxia_sect.db 复制到 xianxia_sect.db.pre_migrate_backup。
         * 仅当检测到需要 migration 时才执行，避免无意义的 I/O。
         *
         * 注意：WAL 模式下直接文件复制可能包含未检查点的 wal 数据。
         * 此处使用 PRAGMA wal_checkpoint(TRUNCATE) 先行落盘再复制。
         */
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

            val targetVersion = 38  // @Database(version = 38)
            if (currentVersion >= targetVersion) {
                Log.d(TAG, "数据库已是最新版本 (v$currentVersion)，无需备份")
                return
            }
            if (currentVersion < 2) {
                // v1 数据库允许 fallbackToDestructiveMigrationFrom(1) 毁灭重建，无需备份
                Log.d(TAG, "数据库版本 v$currentVersion 低于 v2，允许毁灭回退，跳过备份")
                return
            }

            val backupFile = File(dbFile.absolutePath + ".pre_migrate_backup")
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
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33, MIGRATION_33_34, MIGRATION_34_35, MIGRATION_35_36, MIGRATION_36_37, MIGRATION_37_38)
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
         * 检查数据库完整性并验证数据非空。
         * 如果 integrity_check 失败或 game_data 为空（可能由 destructive migration 导致），
         * 记录严重警告以便后续处理。
         * 实际的数据恢复通过以下机制完成：
         * 1. StorageEngine.load() → SaveFileManager.readWithFallback() 自动从 .sav/.bak 恢复
         * 2. 如果已调用 restoreFromBackupIfNeeded() 且 .pre_migrate_backup 存在，则文件级恢复优先
         */
        private fun verifyAndRecoverDatabase(db: SupportSQLiteDatabase, context: Context) {
            // Step 1: integrity_check
            var integrityOk = false
            try {
                val cursor = db.query("PRAGMA integrity_check", emptyArray())
                try {
                    if (cursor.moveToFirst()) {
                        val result = cursor.getString(0)
                        integrityOk = (result == "ok")
                        if (!integrityOk) {
                            Log.wtf(TAG, "DB INTEGRITY FAILED: $result")
                        }
                    }
                } finally {
                    cursor.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check database integrity", e)
            }

            // Step 2: 验证 game_data 表有数据
            var hasData = false
            try {
                val cursor = db.query("SELECT COUNT(*) FROM game_data", emptyArray())
                try {
                    if (cursor.moveToFirst()) {
                        hasData = cursor.getInt(0) > 0
                    }
                } finally {
                    cursor.close()
                }
            } catch (e: Exception) {
                Log.w(TAG, "game_data 表不存在或查询异常", e)
            }

            // Step 3: 如果 integrity 失败或数据为空，记录严重警告
            // 实际恢复由 StorageEngine.load() → SaveFileManager 的 .sav/.bak 备份完成
            if (!integrityOk || !hasData) {
                Log.wtf(TAG, "数据库异常: integrity_ok=$integrityOk, has_data=$hasData, " +
                    "数据将由 StorageEngine 从 SaveFileManager 备份恢复")
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
        fun restoreFromBackupIfNeeded(context: Context): Boolean {
            val dbFile = context.getDatabasePath(UNIFIED_DB_NAME)
            val backupFile = File(dbFile.absolutePath + ".pre_migrate_backup")
            if (!dbFile.exists() || !backupFile.exists()) return false

            // 验证备份文件可用
            var backupOk = false
            var backupRowCount = -1
            try {
                SQLiteDatabase.openDatabase(backupFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { bdb ->
                    val cursor = bdb.rawQuery("PRAGMA integrity_check", null)
                    backupOk = cursor.moveToFirst() && cursor.getString(0) == "ok"
                    cursor.close()
                    if (backupOk) {
                        try {
                            val rc = bdb.rawQuery("SELECT COUNT(*) FROM game_data", null)
                            if (rc.moveToFirst()) backupRowCount = rc.getInt(0)
                            rc.close()
                        } catch (_: Exception) { }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "备份文件验证失败: ${backupFile.absolutePath}", e)
            }

            if (!backupOk) {
                Log.w(TAG, "备份文件 integrity_check 失败，不可用于恢复")
                return false
            }

            // 读取当前数据库 game_data 行数
            var currentRowCount = -1
            try {
                SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { cdb ->
                    try {
                        val rc = cdb.rawQuery("SELECT COUNT(*) FROM game_data", null)
                        if (rc.moveToFirst()) currentRowCount = rc.getInt(0)
                        rc.close()
                    } catch (_: Exception) { }
                }
            } catch (e: Exception) {
                Log.w(TAG, "当前数据库无法打开，将直接使用备份覆盖", e)
            }

            // 当前数据库有数据且 integrity 正常时跳过恢复
            if (currentRowCount > 0) {
                Log.d(TAG, "当前数据库有数据 ($currentRowCount 行)，跳过备份恢复")
                return false
            }

            // 执行恢复：用备份文件覆盖当前数据库
            try {
                backupFile.inputStream().use { input ->
                    // 先写入 .tmp 防止覆盖过程中崩溃损坏原文件
                    val tmpFile = File(dbFile.absolutePath + ".restore_tmp")
                    tmpFile.outputStream().use { output -> input.copyTo(output) }
                    // fsync 确保写完
                    FileOutputStream(tmpFile, true).use { fos -> fos.fd.sync() }
                    // 覆盖原文件
                    tmpFile.renameTo(dbFile)
                }
                Log.w(TAG, "数据库已从备份恢复 (backup_rows=$backupRowCount, " +
                    "backup=${backupFile.absolutePath})")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "从备份恢复数据库失败", e)
                return false
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

