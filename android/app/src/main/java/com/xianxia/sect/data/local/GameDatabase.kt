@file:Suppress("DEPRECATION")

package com.xianxia.sect.data.local

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.xianxia.sect.core.model.*
import com.xianxia.sect.data.local.ProtobufConverters
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.data.incremental.ChangeLogEntity
import com.xianxia.sect.data.incremental.ChangeLogDao
import com.xianxia.sect.BuildConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

object GameDatabaseConfig {
    const val MEMORY_CACHE_SIZE = 64 * 1024 * 1024
    const val DISK_CACHE_SIZE = 100 * 1024 * 1024
    const val WRITE_BATCH_SIZE = 100
    const val WRITE_DELAY_MS = 1000L
    const val WAL_CHECK_INTERVAL_SECONDS = 30L
    const val WAL_SIZE_THRESHOLD_MB = 10L
    const val WAL_CRITICAL_SIZE_MB = 50L
    const val CHECKPOINT_COOLDOWN_MS = 10000L
    // 统一单实例后，查询线程数从4降至2，减少资源占用
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
        Equipment::class,
        Manual::class,
        Pill::class,
        Material::class,
        Seed::class,
        Herb::class,
        ExplorationTeam::class,
        BuildingSlot::class,
        GameEvent::class,
        Dungeon::class,
        Recipe::class,
        BattleLog::class,
        ForgeSlot::class,
        AlchemySlot::class,
        ProductionSlot::class,
        ChangeLogEntity::class
    ],
    version = 73,
    exportSchema = true
)
/**
 * ## TypeConverter 配置说明
 *
 * 使用 [ProtobufConverters] 作为唯一转换器，基于 kotlinx.serialization.protobuf.ProtoBuf：
 * - **所有类型** 均使用 Protobuf + Base64 编码存储
 * - 二进制体积比 JSON 小 30-50%，序列化速度提升 2-5x
 * - 类型安全：编译时检查 schema 一致性
 */
@TypeConverters(ProtobufConverters::class)
abstract class GameDatabase : RoomDatabase() {
    
    abstract fun gameDataDao(): GameDataDao
    abstract fun discipleDao(): DiscipleDao
    abstract fun discipleCoreDao(): DiscipleCoreDao
    abstract fun discipleCombatStatsDao(): DiscipleCombatStatsDao
    abstract fun discipleEquipmentDao(): DiscipleEquipmentDao
    abstract fun discipleExtendedDao(): DiscipleExtendedDao
    abstract fun discipleAttributesDao(): DiscipleAttributesDao
    abstract fun equipmentDao(): EquipmentDao
    abstract fun manualDao(): ManualDao
    abstract fun pillDao(): PillDao
    abstract fun materialDao(): MaterialDao
    abstract fun seedDao(): SeedDao
    abstract fun herbDao(): HerbDao
    abstract fun explorationTeamDao(): ExplorationTeamDao
    abstract fun buildingSlotDao(): BuildingSlotDao
    abstract fun gameEventDao(): GameEventDao
    abstract fun dungeonDao(): DungeonDao
    abstract fun recipeDao(): RecipeDao
    abstract fun battleLogDao(): BattleLogDao
    abstract fun forgeSlotDao(): ForgeSlotDao
    abstract fun alchemySlotDao(): AlchemySlotDao
    abstract fun productionSlotDao(): ProductionSlotDao
    abstract fun changeLogDao(): ChangeLogDao
    abstract fun batchUpdateDao(): BatchUpdateDao
    
    private val checkpointExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "GameDB-Checkpoint")
    }
    
    private val isCheckpointRunning = AtomicBoolean(false)
    private val lastCheckpointTime = AtomicLong(0)
    private val totalCheckpoints = AtomicLong(0)
    private val totalWalSizeFreed = AtomicLong(0)
    
    @Volatile
    private var isShuttingDown = false
    
    private var checkpointJob: ScheduledFuture<*>? = null
    
    fun startAutoCheckpoint() {
        checkpointJob = checkpointExecutor.scheduleWithFixedDelay({
            if (!isShuttingDown) {
                checkAndCheckpoint()
            }
        }, GameDatabaseConfig.WAL_CHECK_INTERVAL_SECONDS,
           GameDatabaseConfig.WAL_CHECK_INTERVAL_SECONDS,
           TimeUnit.SECONDS)
        Log.d(TAG, "Auto-checkpoint started")
    }
    
    private fun checkAndCheckpoint() {
        try {
            val dbPath = openHelper.writableDatabase.path ?: return
            val walFile = File(dbPath + "-wal")
            if (!walFile.exists()) return
            
            val walSizeMB = walFile.length() / (1024 * 1024)
            
            if (walSizeMB >= GameDatabaseConfig.WAL_SIZE_THRESHOLD_MB &&
                System.currentTimeMillis() - lastCheckpointTime.get() >= GameDatabaseConfig.CHECKPOINT_COOLDOWN_MS &&
                isCheckpointRunning.compareAndSet(false, true)) {
                
                try {
                    val beforeSize = walFile.length()
                    
                    val mode = if (walSizeMB >= GameDatabaseConfig.WAL_CRITICAL_SIZE_MB) {
                        Log.w(TAG, "CRITICAL: WAL size ${walSizeMB}MB, forcing TRUNCATE checkpoint")
                        CheckpointMode.TRUNCATE
                    } else {
                        CheckpointMode.PASSIVE
                    }
                    
                    performCheckpointSync(mode)
                    
                    val afterSize = walFile.length()
                    val freed = beforeSize - afterSize
                    
                    if (freed > 0) {
                        totalWalSizeFreed.addAndGet(freed)
                    }
                    
                    lastCheckpointTime.set(System.currentTimeMillis())
                    totalCheckpoints.incrementAndGet()
                    
                    Log.d(TAG, "Checkpoint completed: WAL ${walSizeMB}MB -> ${afterSize / (1024 * 1024)}MB")
                } catch (e: Exception) {
                    Log.e(TAG, "Error during checkpoint", e)
                } finally {
                    isCheckpointRunning.set(false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during auto-checkpoint check", e)
        }
    }
    
    private fun performCheckpointSync(mode: CheckpointMode) {
        try {
            openHelper.writableDatabase.execSQL(mode.query)
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
            lastCheckpointTime = lastCheckpointTime.get()
        )
    }
    
    /**
     * 关闭统一数据库实例。
     *
     * 执行顺序：
     * 1. 设置关闭标志，阻止新的 checkpoint 任务
     * 2. 停止自动 checkpoint 定时任务
     * 3. 关闭 checkpoint 线程池（等待进行中的任务完成）
     * 4. 执行最终 TRUNCATE checkpoint（将 WAL 刷入主DB）- 必须在 close 之前
     * 5. 关闭数据库连接
     *
     * 注意：此方法只关闭单一实例。旧版 per-slot 文件如需清理请调用 [cleanupLegacySlotDatabases]。
     */
    fun shutdown() {
        Log.i(TAG, "Shutting down unified database instance")
        isShuttingDown = true

        // 1. 停止自动 checkpoint 定时任务
        checkpointJob?.cancel(false)
        checkpointJob = null

        // 2. 优雅关闭 checkpoint 线程池，等待进行中的任务完成
        checkpointExecutor.shutdown()
        try {
            if (!checkpointExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                Log.w(TAG, "Checkpoint executor did not terminate in time, forcing shutdown")
                checkpointExecutor.shutdownNow()
                // 再给2秒让强制关闭生效
                if (!checkpointExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    Log.e(TAG, "Checkpoint executor did not terminate after forced shutdown")
                }
            }
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while waiting for checkpoint executor termination")
            checkpointExecutor.shutdownNow()
            Thread.currentThread().interrupt()
        }

        // 3. 最终 TRUNCATE checkpoint：将 WAL 中所有未提交数据刷入主数据库文件
        // 这是关键步骤：必须在 close() 之前完成，否则可能丢失 WAL 中的数据
        try {
            val db = openHelper.writableDatabase
            if (!db.isOpen) {
                Log.w(TAG, "Database already closed, skipping final checkpoint")
            } else {
                performCheckpointSync(CheckpointMode.TRUNCATE)
                Log.i(TAG, "Final TRUNCATE checkpoint completed - WAL data flushed to main DB")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Final checkpoint failed - attempting to continue with close", e)
        }

        // 4. 关闭单一数据库连接
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

        // ========== 统一单实例配置 (v61+) ==========
        /** 统一数据库名称，所有槽位共享此单一实例 */
        private const val UNIFIED_DB_NAME = "xianxia_sect.db"

        // ========== 旧版 per-slot 配置 (已废弃，保留用于过渡期清理) ==========
        private const val DB_PREFIX = "xianxia_sect_db_slot_"
        private const val DB_SUFFIX = ".db"
        private val threadCounter = AtomicInteger(0)

        // ==================== 统一实例 API (v61+) ====================

        /**
         * 创建统一单一数据库实例。
         *
         * 设计决策：
         * - 移除 slot 参数：不再按槽位创建独立DB文件
         * - 所有存档槽位的数据通过 [slot_id] 列在表内区分
         * - 单一实例意味着：1个DB文件 + 1个WAL + 1个SHM + 2个查询线程 + 1个事务线程
         *   （旧方案5个槽位 = 5个DB + 5个WAL + 5个SHM + 20个查询线程 + 5个事务线程）
         */
        fun create(context: Context): GameDatabase {
            Log.i(TAG, "Creating unified single-instance database: $UNIFIED_DB_NAME")

            val report = DatabaseMigrations.validateMigrationIntegrity(70)
            if (!report.isValid) {
                Log.e(TAG, "Migration integrity check FAILED - this likely means an @Entity was modified without bumping the DB version")
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "Debug mode: proceeding anyway (schema mismatch will be caught by Room at query time)")
                }
            }

            return try {
                val db = buildDatabase(context)
                db.openHelper.writableDatabase
                db
            } catch (e: IllegalStateException) {
                val msg = e.message ?: ""
                val isSchemaError = msg.contains("identity hash") ||
                        msg.contains("Migration didn't properly handle") ||
                        msg.contains("Expected:") && msg.contains("Found:")

                if (isSchemaError) {
                    val dbFile = context.getDatabasePath(UNIFIED_DB_NAME)
                    Log.e(TAG, "Schema mismatch detected: $msg")
                    Log.e(TAG, "DB file: ${dbFile.absolutePath} (${if (dbFile.exists()) dbFile.length() else 0} bytes)")

                    if (BuildConfig.DEBUG) {
                        Log.w(TAG, "Debug mode: using MigrationFallbackHandler for safe recovery")
                        val handled = MigrationFallbackHandler.handleMigrationFailure(
                            context, dbFile,
                            "Schema mismatch recovery: $msg"
                        )
                        if (handled) {
                            Log.w(TAG, "MigrationFallbackHandler completed. Rebuilding database...")
                            buildDatabase(context)
                        } else {
                            Log.e(TAG, "MigrationFallbackHandler FAILED - cannot safely recover")
                            throw IllegalStateException(
                                "Schema migration failed AND fallback handler failed. " +
                                "Manual intervention required. DB file: ${dbFile.absolutePath}", e
                            )
                        }
                    } else {
                        Log.e(TAG, "Release mode: attempting safe recovery via MigrationFallbackHandler")
                        val handled = MigrationFallbackHandler.handleMigrationFailure(
                            context, dbFile,
                            "Schema mismatch recovery (release): $msg"
                        )
                        if (handled) {
                            Log.w(TAG, "MigrationFallbackHandler completed. Rebuilding database...")
                            buildDatabase(context)
                        } else {
                            Log.e(TAG, "Release mode: MigrationFallbackHandler failed, throwing exception")
                            throw IllegalStateException(
                                "Schema migration failed in release build. " +
                                "A proper Migration must be added for this schema change. " +
                                "DB file: ${dbFile.absolutePath}", e
                            )
                        }
                    }
                } else {
                    throw e
                }
            }
        }

        private fun buildDatabase(context: Context): GameDatabase {
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
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        Log.i(TAG, "Unified database created")
                        configureDatabase(db, context)

                        val dbFile = context.getDatabasePath(UNIFIED_DB_NAME)
                        val legacySlots = (0..4).filter { slot ->
                            "${DB_PREFIX}${slot}${DB_SUFFIX}".let { name ->
                                context.getDatabasePath(name).exists()
                            }
                        }
                        if (legacySlots.isNotEmpty()) {
                            Log.w(TAG, "!!! DESTRUCTIVE MIGRATION DETECTED !!!")
                            Log.w(TAG, "Unified DB recreated while legacy per-slot DBs still exist in slots: $legacySlots")
                            Log.w(TAG, "DB file: ${dbFile.absolutePath}")
                            Log.w(TAG, "Root cause likely: Entity schema changed without version bump, or Migration missing")
                        }
                    }
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        Log.i(TAG, "Unified database opened")
                        optimizeDatabase(db)
                    }
                })
                .addMigrations(*DatabaseMigrations.ALL_MIGRATIONS)
                // 已移除 fallbackToDestructiveMigration / fallbackToDestructiveMigrationOnDowngrade
                // 原因：破坏性迁移会静默删除用户存档数据，违反数据安全原则
                // 替代方案：MigrationFallbackHandler 提供安全的备份-清理流程
                // 如果遇到迁移失败，请添加对应的 Migration 对象到 DatabaseMigrations.ALL_MIGRATIONS
                .build()
                .also { db -> applySafetyPragmas(db) }
        }

        private fun deleteDatabaseFiles(context: Context) {
            val dbFile = context.getDatabasePath(UNIFIED_DB_NAME)
            val walFile = File(dbFile.path + "-wal")
            val shmFile = File(dbFile.path + "-shm")
            for (file in listOf(dbFile, walFile, shmFile)) {
                if (file.exists()) {
                    val deleted = file.delete()
                    Log.d(TAG, "Deleted ${file.name}: $deleted (${file.length()} bytes)")
                }
            }
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
                    } catch (_: Exception) {}
                } else {
                    Log.w(TAG, "Failed to apply PRAGMA synchronous: ${e.message}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to apply PRAGMA synchronous: ${e.message}")
            }
        }

        /**
         * 获取统一数据库文件的绝对路径。
         *
         * @return 统一DB的 File 对象，如 `/data/data/com.xianxia.sect/databases/xianxia_sect.db`
         */
        fun getUnifiedDatabaseFile(context: Context): File {
            return context.getDatabasePath(UNIFIED_DB_NAME)
        }

        fun cleanupLegacySlotDatabases(context: Context): Int {
            var deletedCount = 0
            for (slot in 0..4) {
                val dbName = "${DB_PREFIX}${slot}${DB_SUFFIX}"
                val dbFile = context.getDatabasePath(dbName)
                val walFile = File(dbFile.path + "-wal")
                val shmFile = File(dbFile.path + "-shm")

                for (file in listOf(dbFile, walFile, shmFile)) {
                    if (file.exists() && file.delete()) {
                        deletedCount++
                        Log.d(TAG, "Deleted legacy file: ${file.name}")
                    }
                }
            }
            if (deletedCount > 0) {
                Log.i(TAG, "Cleanup complete: $deletedCount legacy files removed")
            }
            return deletedCount
        }
        
        private fun configureDatabase(db: SupportSQLiteDatabase, context: Context? = null) {
            Log.d(TAG, "Configuring database parameters")

            val dynamicMmapSize = resolveDynamicMmapSize(context)
            val dynamicCacheSize = resolveDynamicCacheSize(context)

            DbPragmas.executeSafely(db, "PRAGMA journal_mode = WAL")
            DbPragmas.executeSafely(db, "PRAGMA synchronous = NORMAL")
            DbPragmas.executeSafely(db, "PRAGMA cache_size = $dynamicCacheSize")
            DbPragmas.executeSafely(db, "PRAGMA temp_store = MEMORY")
            DbPragmas.executeSafely(db, "PRAGMA mmap_size = $dynamicMmapSize")
            DbPragmas.executeSafely(db, "PRAGMA foreign_keys = ON")

            DbPragmas.executeSafely(db, "PRAGMA wal_autocheckpoint = 1000")
            DbPragmas.executeSafely(db, "PRAGMA busy_timeout = 5000")
            DbPragmas.executeSafely(db, "PRAGMA journal_size_limit = 5242880")

            Log.d(TAG, "Database configuration completed (mmap=${dynamicMmapSize / 1024 / 1024}MB, cache=${-dynamicCacheSize / 1024}MB, journal_limit=5MB)")
        }

        private fun resolveDynamicMmapSize(context: Context?): Long {
            val defaultMmap = 268435456L
            if (context == null) return defaultMmap
            return try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                    ?: return defaultMmap
                val memInfo = android.app.ActivityManager.MemoryInfo()
                am.getMemoryInfo(memInfo)
                val totalMemMB = memInfo.totalMem / (1024 * 1024)
                when {
                    totalMemMB < 2048 -> 67108864L
                    totalMemMB < 4096 -> 134217728L
                    else -> defaultMmap
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to detect memory for mmap sizing", e)
                defaultMmap
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
            DbPragmas.executeSafely(db, "PRAGMA analysis_limit = 2000")
            DbPragmas.executeSafely(db, "PRAGMA optimize")
            Log.d(TAG, "Database optimization completed (analysis_limit=2000)")
        }
    }
}
