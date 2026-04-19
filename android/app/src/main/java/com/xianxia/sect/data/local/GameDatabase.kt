package com.xianxia.sect.data.local

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.data.incremental.ChangeLogEntity
import com.xianxia.sect.data.incremental.ChangeLogDao
import com.xianxia.sect.data.archive.ArchivedBattleLog
import com.xianxia.sect.data.archive.ArchivedGameEvent
import com.xianxia.sect.data.archive.ArchivedDisciple
import com.xianxia.sect.data.archive.ArchivedBattleLogDao
import com.xianxia.sect.data.archive.ArchivedGameEventDao
import com.xianxia.sect.data.archive.ArchivedDiscipleDao
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
    const val QUERY_THREAD_COUNT = 2
}

val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i("GameDatabase", "Migrating database from version 4 to 5: recruitList serialization fix (no schema change)")
    }
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
        ChangeLogEntity::class,
        SaveSlotMetadata::class,
        ArchivedBattleLog::class,
        ArchivedGameEvent::class,
        ArchivedDisciple::class
    ],
    version = 5,
    exportSchema = true
)

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
    abstract fun saveSlotMetadataDao(): SaveSlotMetadataDao

    abstract fun archivedBattleLogDao(): ArchivedBattleLogDao
    abstract fun archivedGameEventDao(): ArchivedGameEventDao
    abstract fun archivedDiscipleDao(): ArchivedDiscipleDao

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

    fun shutdown() {
        Log.i(TAG, "Shutting down unified database instance")
        isShuttingDown = true

        checkpointJob?.cancel(false)
        checkpointJob = null

        checkpointExecutor.shutdown()
        try {
            if (!checkpointExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                Log.w(TAG, "Checkpoint executor did not terminate in time, forcing shutdown")
                checkpointExecutor.shutdownNow()
                if (!checkpointExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    Log.e(TAG, "Checkpoint executor did not terminate after forced shutdown")
                }
            }
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while waiting for checkpoint executor termination")
            checkpointExecutor.shutdownNow()
            Thread.currentThread().interrupt()
        }

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
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        Log.i(TAG, "Unified database created")
                        configureDatabase(db, context)
                    }
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        Log.i(TAG, "Unified database opened")
                        optimizeDatabase(db)
                    }
                })
                .addMigrations(MIGRATION_4_5)
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
                    } catch (_: Exception) {}
                } else {
                    Log.w(TAG, "Failed to apply PRAGMA synchronous: ${e.message}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to apply PRAGMA synchronous: ${e.message}")
            }
        }

        private fun configureDatabase(db: SupportSQLiteDatabase, context: Context? = null) {
            Log.d(TAG, "Configuring database parameters")

            val dynamicMmapSize = resolveDynamicMmapSize(context)
            val dynamicCacheSize = resolveDynamicCacheSize(context)

            executeSafely(db, "PRAGMA journal_mode = WAL")
            executeSafely(db, "PRAGMA synchronous = NORMAL")
            executeSafely(db, "PRAGMA cache_size = $dynamicCacheSize")
            executeSafely(db, "PRAGMA temp_store = MEMORY")
            executeSafely(db, "PRAGMA mmap_size = $dynamicMmapSize")
            executeSafely(db, "PRAGMA foreign_keys = ON")
            executeSafely(db, "PRAGMA wal_autocheckpoint = 1000")
            executeSafely(db, "PRAGMA busy_timeout = 5000")
            executeSafely(db, "PRAGMA journal_size_limit = 5242880")

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
            executeSafely(db, "PRAGMA analysis_limit = 2000")
            executeSafely(db, "PRAGMA optimize")
            Log.d(TAG, "Database optimization completed (analysis_limit=2000)")
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
