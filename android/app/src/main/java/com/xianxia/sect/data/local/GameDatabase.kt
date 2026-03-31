package com.xianxia.sect.data.local

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.ModelConverters
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.data.incremental.ChangeLogEntity
import com.xianxia.sect.data.incremental.ChangeLogDao
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
    const val QUERY_THREAD_COUNT = 4
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
    version = 60,
    exportSchema = false
)
@TypeConverters(ModelConverters::class)
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
        Log.i(TAG, "Shutting down database")
        isShuttingDown = true
        
        checkpointJob?.cancel(false)
        checkpointExecutor.shutdown()
        
        try {
            if (!checkpointExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                checkpointExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            checkpointExecutor.shutdownNow()
            Thread.currentThread().interrupt()
        }
        
        try {
            performCheckpointSync(CheckpointMode.TRUNCATE)
            Log.i(TAG, "Final checkpoint completed")
        } catch (e: Exception) {
            Log.e(TAG, "Final checkpoint failed", e)
        }
        
        try {
            close()
            Log.i(TAG, "Database closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing database", e)
        }
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
        private const val DB_PREFIX = "xianxia_sect_db_slot_"
        private const val DB_SUFFIX = ".db"
        private val threadCounter = AtomicInteger(0)
        
        fun getDatabaseName(slot: Int): String = "${DB_PREFIX}${slot}${DB_SUFFIX}"
        
        fun getDatabaseFile(context: Context, slot: Int): File {
            return context.getDatabasePath(getDatabaseName(slot))
        }
        
        fun getWalFile(context: Context, slot: Int): File {
            return File(getDatabaseFile(context, slot).path + "-wal")
        }
        
        fun getShmFile(context: Context, slot: Int): File {
            return File(getDatabaseFile(context, slot).path + "-shm")
        }
        
        fun exists(context: Context, slot: Int): Boolean {
            return getDatabaseFile(context, slot).exists()
        }
        
        fun delete(context: Context, slot: Int): Boolean {
            val dbFile = getDatabaseFile(context, slot)
            val walFile = getWalFile(context, slot)
            val shmFile = getShmFile(context, slot)
            
            var success = true
            
            if (dbFile.exists() && !dbFile.delete()) {
                Log.w(TAG, "Failed to delete database file for slot $slot")
                success = false
            }
            
            if (walFile.exists() && !walFile.delete()) {
                Log.w(TAG, "Failed to delete WAL file for slot $slot")
            }
            
            if (shmFile.exists() && !shmFile.delete()) {
                Log.w(TAG, "Failed to delete SHM file for slot $slot")
            }
            
            return success
        }
        
        fun create(context: Context, slot: Int): GameDatabase {
            Log.i(TAG, "Creating database for slot $slot")
            
            return Room.databaseBuilder(
                context.applicationContext,
                GameDatabase::class.java,
                getDatabaseName(slot)
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
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        Log.i(TAG, "Database created for slot $slot")
                        configureDatabase(db)
                    }
                    
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        Log.i(TAG, "Database opened for slot $slot")
                        optimizeDatabase(db)
                    }
                })
                .addMigrations(*DatabaseMigrations.ALL_MIGRATIONS)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
        }
        
        private fun configureDatabase(db: SupportSQLiteDatabase) {
            Log.d(TAG, "Configuring database parameters")
            
            db.execSQL("PRAGMA journal_mode = WAL")
            db.execSQL("PRAGMA synchronous = NORMAL")
            db.execSQL("PRAGMA cache_size = -64000")
            db.execSQL("PRAGMA temp_store = MEMORY")
            db.execSQL("PRAGMA mmap_size = 268435456")
            db.execSQL("PRAGMA foreign_keys = ON")
            
            Log.d(TAG, "Database configuration completed")
        }
        
        private fun optimizeDatabase(db: SupportSQLiteDatabase) {
            Log.d(TAG, "Running database optimization")
            db.execSQL("PRAGMA optimize")
            Log.d(TAG, "Database optimization completed")
        }
    }
}
