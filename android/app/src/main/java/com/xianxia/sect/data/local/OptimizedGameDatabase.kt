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
import com.xianxia.sect.core.model.ModelConverters
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

object OptimizedGameDatabaseConfig {
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
        ChangeLogEntity::class
    ],
    version = 58,
    exportSchema = true
)
@TypeConverters(ModelConverters::class)
abstract class OptimizedGameDatabase : RoomDatabase() {
    
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
        }, OptimizedGameDatabaseConfig.WAL_CHECK_INTERVAL_SECONDS,
           OptimizedGameDatabaseConfig.WAL_CHECK_INTERVAL_SECONDS,
           TimeUnit.SECONDS)
        Log.d(TAG, "Auto-checkpoint started")
    }
    
    private fun checkAndCheckpoint() {
        try {
            val dbPath = openHelper.writableDatabase.path ?: return
            val walFile = File(dbPath + "-wal")
            if (!walFile.exists()) return
            
            val walSizeMB = walFile.length() / (1024 * 1024)
            
            if (walSizeMB >= OptimizedGameDatabaseConfig.WAL_SIZE_THRESHOLD_MB &&
                System.currentTimeMillis() - lastCheckpointTime.get() >= OptimizedGameDatabaseConfig.CHECKPOINT_COOLDOWN_MS &&
                isCheckpointRunning.compareAndSet(false, true)) {
                
                try {
                    val beforeSize = walFile.length()
                    
                    val mode = if (walSizeMB >= OptimizedGameDatabaseConfig.WAL_CRITICAL_SIZE_MB) {
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
        val databaseSize: Long,
        val walSize: Long,
        val shmSize: Long,
        val totalSize: Long,
        val totalCheckpoints: Long,
        val totalWalFreed: Long,
        val lastCheckpointTime: Long
    )
    
    companion object {
        private const val TAG = "OptimizedGameDatabase"
        private const val DB_PREFIX = "game_slot_"
        private const val DB_SUFFIX = ".db"
        private val threadCounter = AtomicInteger(0)
        
        val MIGRATION_53_54 = object : Migration(53, 54) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Starting migration from version 53 to 54")
                
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS change_log (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        table_name TEXT NOT NULL,
                        record_id TEXT NOT NULL,
                        operation TEXT NOT NULL,
                        old_value BLOB,
                        new_value BLOB,
                        timestamp INTEGER NOT NULL,
                        synced INTEGER NOT NULL DEFAULT 0,
                        sync_version INTEGER NOT NULL DEFAULT 0
                    )
                """)
                
                db.execSQL("CREATE INDEX IF NOT EXISTS index_change_log_table_record ON change_log (table_name, record_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_change_log_timestamp ON change_log (timestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_change_log_synced ON change_log (synced)")
                
                Log.i(TAG, "Migration 53 to 54 completed successfully")
            }
        }
        
        val MIGRATION_54_55 = object : Migration(54, 55) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migrating from version 54 to 55")
                
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS alchemy_slots (
                        id TEXT NOT NULL PRIMARY KEY,
                        slotIndex INTEGER NOT NULL,
                        recipeId TEXT,
                        recipeName TEXT NOT NULL,
                        pillName TEXT NOT NULL,
                        pillRarity INTEGER NOT NULL,
                        startYear INTEGER NOT NULL,
                        startMonth INTEGER NOT NULL,
                        duration INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        successRate REAL NOT NULL,
                        requiredMaterials TEXT NOT NULL
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_alchemy_slots_slotIndex ON alchemy_slots (slotIndex)")
                
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_disciple_slot_alive ON disciple (slot, isAlive)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_disciple_slot_level ON disciple (slot, level)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_equipment_slot_rarity ON equipment (slot, rarity)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_battle_log_slot_time ON battle_log (slot, timestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_event_slot_time ON game_event (slot, timestamp)")
                
                Log.i(TAG, "Migration 54 to 55 completed successfully")
            }
        }
        
        val MIGRATION_55_56 = object : Migration(55, 56) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migrating from version 55 to 56")
                
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS data_version (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        table_name TEXT NOT NULL,
                        record_id TEXT NOT NULL,
                        version INTEGER NOT NULL DEFAULT 1,
                        updated_at INTEGER NOT NULL,
                        UNIQUE(table_name, record_id)
                    )
                """)
                
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_data_version_table ON data_version (table_name)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_data_version_record ON data_version (record_id)")
                
                Log.i(TAG, "Migration 55 to 56 completed successfully")
            }
        }
        
        val MIGRATION_56_57 = object : Migration(56, 57) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migrating from version 56 to 57")
                
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS storage_stats (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        stat_type TEXT NOT NULL,
                        stat_key TEXT NOT NULL,
                        stat_value REAL NOT NULL,
                        recorded_at INTEGER NOT NULL
                    )
                """)
                
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_storage_stats_type ON storage_stats (stat_type)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_storage_stats_time ON storage_stats (recorded_at)")
                
                Log.i(TAG, "Migration 56 to 57 completed successfully")
            }
        }
        
        val MIGRATION_57_58 = object : Migration(57, 58) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Migrating from version 57 to 58 - Disciple entity split with foreign keys")
                
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS disciples_core (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        realm INTEGER NOT NULL,
                        realmLayer INTEGER NOT NULL,
                        cultivation REAL NOT NULL,
                        isAlive INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        discipleType TEXT NOT NULL,
                        age INTEGER NOT NULL,
                        lifespan INTEGER NOT NULL,
                        gender TEXT NOT NULL,
                        spiritRootType TEXT NOT NULL,
                        recruitedMonth INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_disciples_core_name ON disciples_core (name)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_disciples_core_realm ON disciples_core (realm, realmLayer)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_disciples_core_alive_realm ON disciples_core (isAlive, realm)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_disciples_core_alive_status ON disciples_core (isAlive, status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_disciples_core_type ON disciples_core (discipleType)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_disciples_core_age ON disciples_core (age)")
                
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS disciples_combat (
                        discipleId TEXT NOT NULL PRIMARY KEY,
                        baseHp INTEGER NOT NULL,
                        baseMp INTEGER NOT NULL,
                        basePhysicalAttack INTEGER NOT NULL,
                        baseMagicAttack INTEGER NOT NULL,
                        basePhysicalDefense INTEGER NOT NULL,
                        baseMagicDefense INTEGER NOT NULL,
                        baseSpeed INTEGER NOT NULL,
                        hpVariance INTEGER NOT NULL,
                        mpVariance INTEGER NOT NULL,
                        physicalAttackVariance INTEGER NOT NULL,
                        magicAttackVariance INTEGER NOT NULL,
                        physicalDefenseVariance INTEGER NOT NULL,
                        magicDefenseVariance INTEGER NOT NULL,
                        speedVariance INTEGER NOT NULL,
                        pillPhysicalAttackBonus REAL NOT NULL,
                        pillMagicAttackBonus REAL NOT NULL,
                        pillPhysicalDefenseBonus REAL NOT NULL,
                        pillMagicDefenseBonus REAL NOT NULL,
                        pillHpBonus REAL NOT NULL,
                        pillMpBonus REAL NOT NULL,
                        pillSpeedBonus REAL NOT NULL,
                        pillEffectDuration INTEGER NOT NULL,
                        battlesWon INTEGER NOT NULL,
                        totalCultivation INTEGER NOT NULL,
                        breakthroughCount INTEGER NOT NULL,
                        breakthroughFailCount INTEGER NOT NULL,
                        FOREIGN KEY (discipleId) REFERENCES disciples_core(id) ON DELETE CASCADE ON UPDATE CASCADE
                    )
                """)
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_disciples_combat_disciple ON disciples_combat (discipleId)")
                
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS disciples_equipment (
                        discipleId TEXT NOT NULL PRIMARY KEY,
                        weaponId TEXT,
                        armorId TEXT,
                        bootsId TEXT,
                        accessoryId TEXT,
                        weaponNurture TEXT,
                        armorNurture TEXT,
                        bootsNurture TEXT,
                        accessoryNurture TEXT,
                        storageBagItems TEXT,
                        storageBagSpiritStones INTEGER NOT NULL,
                        spiritStones INTEGER NOT NULL,
                        soulPower INTEGER NOT NULL,
                        FOREIGN KEY (discipleId) REFERENCES disciples_core(id) ON DELETE CASCADE ON UPDATE CASCADE
                    )
                """)
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_disciples_equipment_disciple ON disciples_equipment (discipleId)")
                
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS disciples_extended (
                        discipleId TEXT NOT NULL PRIMARY KEY,
                        manualIds TEXT,
                        talentIds TEXT,
                        manualMasteries TEXT,
                        statusData TEXT,
                        cultivationSpeedBonus REAL NOT NULL,
                        cultivationSpeedDuration INTEGER NOT NULL,
                        partnerId TEXT,
                        partnerSectId TEXT,
                        parentId1 TEXT,
                        parentId2 TEXT,
                        lastChildYear INTEGER NOT NULL,
                        griefEndYear INTEGER,
                        monthlyUsedPillIds TEXT,
                        usedExtendLifePillIds TEXT,
                        hasReviveEffect INTEGER NOT NULL,
                        hasClearAllEffect INTEGER NOT NULL,
                        FOREIGN KEY (discipleId) REFERENCES disciples_core(id) ON DELETE CASCADE ON UPDATE CASCADE
                    )
                """)
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_disciples_extended_disciple ON disciples_extended (discipleId)")
                
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS disciples_attributes (
                        discipleId TEXT NOT NULL PRIMARY KEY,
                        intelligence INTEGER NOT NULL,
                        charm INTEGER NOT NULL,
                        loyalty INTEGER NOT NULL,
                        comprehension INTEGER NOT NULL,
                        artifactRefining INTEGER NOT NULL,
                        pillRefining INTEGER NOT NULL,
                        spiritPlanting INTEGER NOT NULL,
                        teaching INTEGER NOT NULL,
                        morality INTEGER NOT NULL,
                        salaryPaidCount INTEGER NOT NULL,
                        salaryMissedCount INTEGER NOT NULL,
                        FOREIGN KEY (discipleId) REFERENCES disciples_core(id) ON DELETE CASCADE ON UPDATE CASCADE
                    )
                """)
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_disciples_attributes_disciple ON disciples_attributes (discipleId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_disciples_attributes_loyalty ON disciples_attributes (loyalty)")
                
                db.execSQL("PRAGMA foreign_keys = ON")
                
                Log.i(TAG, "Migration 57 to 58 completed successfully - Created split disciple tables with foreign keys")
            }
        }
        
        fun create(context: Context, slot: Int): OptimizedGameDatabase {
            Log.i(TAG, "Creating database for slot $slot")
            
            return Room.databaseBuilder(
                context.applicationContext,
                OptimizedGameDatabase::class.java,
                getDatabaseName(slot)
            )
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .setQueryExecutor(
                    Executors.newFixedThreadPool(OptimizedGameDatabaseConfig.QUERY_THREAD_COUNT) { r ->
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
                .addMigrations(MIGRATION_53_54, MIGRATION_54_55, MIGRATION_55_56, MIGRATION_56_57, MIGRATION_57_58)
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
    }
}
