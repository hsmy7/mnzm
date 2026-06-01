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
        ForgeSlot::class,
        AlchemySlot::class,
        ProductionSlot::class,
        ChangeLogEntity::class,
        SaveSlotMetadata::class,
        ArchivedBattleLog::class,
        ArchivedDisciple::class,
        GameHeavyData::class,
        StorageBag::class,
        MailEntity::class
    ],
    version = 24
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
    abstract fun forgeSlotDao(): ForgeSlotDao
    abstract fun alchemySlotDao(): AlchemySlotDao
    abstract fun productionSlotDao(): ProductionSlotDao
    abstract fun changeLogDao(): ChangeLogDao
    abstract fun saveSlotMetadataDao(): SaveSlotMetadataDao

    abstract fun archivedBattleLogDao(): ArchivedBattleLogDao
    abstract fun archivedDiscipleDao(): ArchivedDiscipleDao

    abstract fun gameHeavyDataDao(): GameHeavyDataDao

    abstract fun mailDao(): MailDao

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

    fun performPostSaveCheckpoint() {
        try {
            performCheckpointSync(CheckpointMode.PASSIVE)
        } catch (e: Exception) {
            Log.w(TAG, "Post-save checkpoint failed", e)
        }
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

        val MIGRATION_1_3 = object : Migration(1, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE production_slots ADD COLUMN autoRestartEnabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE alchemy_slots ADD COLUMN autoRestartEnabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE alchemy_slots ADD COLUMN assignedDiscipleId TEXT")
                db.execSQL("ALTER TABLE alchemy_slots ADD COLUMN assignedDiscipleName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE forge_slots ADD COLUMN autoRestartEnabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE forge_slots ADD COLUMN assignedDiscipleId TEXT")
                db.execSQL("ALTER TABLE forge_slots ADD COLUMN assignedDiscipleName TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE game_data ADD COLUMN aiSectDisciples TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE game_data ADD COLUMN residenceSlots TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * Safely drop columns from a table, compatible with all Android API levels.
         * Always rebuilds the table via PRAGMA — ALTER TABLE DROP COLUMN requires
         * SQLite 3.35.0+ which is not guaranteed even on API 31+ devices.
         */
        private fun SupportSQLiteDatabase.safeDropColumns(table: String, vararg dropCols: String) {
            val dropped = dropCols.toSet()

            // 1. 保存索引定义 (PRAGMA index_list + index_info)
            data class IndexInfo(val name: String, val unique: Boolean, val cols: List<String>)
            val indices = mutableListOf<IndexInfo>()
            query("PRAGMA index_list($table)").use { c ->
                while (c.moveToNext()) {
                    val idxName = c.getString(c.getColumnIndexOrThrow("name"))
                    val unique = c.getInt(c.getColumnIndexOrThrow("unique")) == 1
                    val idxCols = mutableListOf<String>()
                    query("PRAGMA index_info($idxName)").use { ic ->
                        while (ic.moveToNext()) {
                            idxCols.add(ic.getString(ic.getColumnIndexOrThrow("name")))
                        }
                    }
                    // 只保留不涉及被删除列的索引
                    if (idxCols.none { it in dropped }) {
                        indices.add(IndexInfo(idxName, unique, idxCols))
                    }
                }
            }

            // 2. 读取列定义（含NOT NULL和DEFAULT）
            data class ColDef(val name: String, val type: String, val notNull: Boolean, val dflt: String?, val pk: Int)
            val cols = mutableListOf<ColDef>()
            query("PRAGMA table_info($table)").use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(c.getColumnIndexOrThrow("name"))
                    if (name in dropped) continue
                    cols.add(ColDef(
                        name = name,
                        type = c.getString(c.getColumnIndexOrThrow("type")),
                        notNull = c.getInt(c.getColumnIndexOrThrow("notnull")) == 1,
                        dflt = c.getString(c.getColumnIndexOrThrow("dflt_value")),
                        pk = c.getInt(c.getColumnIndexOrThrow("pk"))
                    ))
                }
            }

            val colDefs = cols.joinToString(", ") {
                val nn = if (it.notNull) " NOT NULL" else ""
                val dflt = if (it.dflt != null) " DEFAULT ${it.dflt}" else ""
                "${it.name} ${it.type}$nn$dflt"
            }
            val pkCols = cols.filter { it.pk > 0 }.sortedBy { it.pk }
            val pk = if (pkCols.isNotEmpty()) ", PRIMARY KEY(${pkCols.joinToString { it.name }})" else ""
            val names = cols.joinToString(", ") { it.name }

            execSQL("CREATE TABLE ${table}_new ($colDefs$pk)")
            execSQL("INSERT INTO ${table}_new ($names) SELECT $names FROM $table")
            execSQL("DROP TABLE $table")
            execSQL("ALTER TABLE ${table}_new RENAME TO $table")

            // 3. 重建索引（跳过sqlite_autoindex内部保留名，PRIMARY KEY已自动重建）
            indices.forEach { idx ->
                if (idx.name.startsWith("sqlite_autoindex_")) return@forEach
                val unique = if (idx.unique) "UNIQUE " else ""
                execSQL("CREATE ${unique}INDEX IF NOT EXISTS ${idx.name} ON $table (${idx.cols.joinToString()})")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.safeDropColumns("game_data", "pendingCompetitionResults", "lastCompetitionYear")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE game_data ADD COLUMN activeSectId TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE game_data ADD COLUMN spiritFieldPlants TEXT NOT NULL DEFAULT '[]'")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE game_data ADD COLUMN warehouseGarrisons TEXT NOT NULL DEFAULT '[]'")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE game_data ADD COLUMN patrolSlots TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE game_data ADD COLUMN patrolConfig TEXT NOT NULL DEFAULT '{}'")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE game_data ADD COLUMN patrolConfigs TEXT NOT NULL DEFAULT '[]'")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE game_data ADD COLUMN daoCompanionBannedRootCounts TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE game_data ADD COLUMN daoCompanionConsentRequired INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE disciples ADD COLUMN social_childBirthMonth INTEGER DEFAULT NULL")
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE game_data ADD COLUMN patrolBattleResultPopup INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE game_data ADD COLUMN breakthroughAutoPillFocused INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE game_data ADD COLUMN breakthroughAutoPillRootCounts TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE game_data ADD COLUMN autoEquipFromWarehouseFocused INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE game_data ADD COLUMN autoEquipFromWarehouseRootCounts TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE game_data ADD COLUMN autoLearnFromWarehouseFocused INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE game_data ADD COLUMN autoLearnFromWarehouseRootCounts TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 天制→旬制: 幂等迁移，覆盖所有可能状态
                // Room列名规则: Kotlin属性名即列名，不转snake_case。gamePhase→gamePhase(非game_phase)

                fun columnExists(table: String, col: String): Boolean {
                    db.query("PRAGMA table_info($table)").use { c ->
                        while (c.moveToNext()) {
                            if (c.getString(c.getColumnIndexOrThrow("name")) == col) return true
                        }
                    }
                    return false
                }

                val hasGamePhaseCamel = columnExists("game_data", "gamePhase")
                val hasGamePhaseSnake = columnExists("game_data", "game_phase")
                val hasGameDayCamel = columnExists("game_data", "gameDay")
                val hasGameDaySnake = columnExists("game_data", "game_day")

                // 清理之前错误迁移可能留下的 snake_case game_phase 列
                if (hasGamePhaseSnake && !hasGamePhaseCamel) {
                    // 从 snake_case 错误列复制数据
                    db.execSQL("ALTER TABLE game_data ADD COLUMN gamePhase INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("UPDATE game_data SET gamePhase = game_phase")
                    db.safeDropColumns("game_data", "game_phase")
                } else if (!hasGamePhaseCamel) {
                    db.execSQL("ALTER TABLE game_data ADD COLUMN gamePhase INTEGER NOT NULL DEFAULT 0")
                }

                // 从旧 gameDay 列复制修炼数据
                val oldDayCol = if (hasGameDayCamel) "gameDay" else if (hasGameDaySnake) "game_day" else null
                if (oldDayCol != null) {
                    // gameDay 1-10→0(上旬), 11-20→1(中旬), 21-30→2(下旬)
                    db.execSQL("UPDATE game_data SET gamePhase = ($oldDayCol - 1) / 10")
                }

                // 删除旧 gameDay 列
                if (hasGameDayCamel) db.safeDropColumns("game_data", "gameDay")
                if (hasGameDaySnake) db.safeDropColumns("game_data", "game_day")

                // save_slot_metadata 表: game_day → game_phase (snake_case, 有 @ColumnInfo)
                val metaHasPhase = columnExists("save_slot_metadata", "game_phase")
                val metaHasDay = columnExists("save_slot_metadata", "game_day")
                if (!metaHasPhase && metaHasDay) {
                    db.execSQL("ALTER TABLE save_slot_metadata ADD COLUMN game_phase INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("UPDATE save_slot_metadata SET game_phase = (game_day - 1) / 10")
                    db.safeDropColumns("save_slot_metadata", "game_day")
                } else if (!metaHasPhase) {
                    db.execSQL("ALTER TABLE save_slot_metadata ADD COLUMN game_phase INTEGER NOT NULL DEFAULT 0")
                }
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 清理旧版迁移可能残留的 snake_case game_phase 和 game_day/gameDay 列
                fun colExists(col: String): Boolean {
                    db.query("PRAGMA table_info(game_data)").use { c ->
                        while (c.moveToNext()) {
                            if (c.getString(c.getColumnIndexOrThrow("name")) == col) return true
                        }
                    }
                    return false
                }
                // 确保有正确的 gamePhase 列
                if (!colExists("gamePhase") && colExists("game_phase")) {
                    db.execSQL("ALTER TABLE game_data ADD COLUMN gamePhase INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("UPDATE game_data SET gamePhase = game_phase")
                }
                if (colExists("game_phase")) db.safeDropColumns("game_data", "game_phase")
                if (colExists("gameDay")) db.safeDropColumns("game_data", "gameDay")
                if (colExists("game_day")) db.safeDropColumns("game_data", "game_day")

                // save_slot_metadata 表: 清理残留的 game_day 列
                var metaHasDay = false
                db.query("PRAGMA table_info(save_slot_metadata)").use { c ->
                    while (c.moveToNext()) {
                        if (c.getString(c.getColumnIndexOrThrow("name")) == "game_day") { metaHasDay = true; break }
                    }
                }
                if (metaHasDay) {
                    var metaHasPhase = false
                    db.query("PRAGMA table_info(save_slot_metadata)").use { c2 ->
                        while (c2.moveToNext()) {
                            if (c2.getString(c2.getColumnIndexOrThrow("name")) == "game_phase") { metaHasPhase = true; break }
                        }
                    }
                    if (!metaHasPhase) {
                        db.execSQL("ALTER TABLE save_slot_metadata ADD COLUMN game_phase INTEGER NOT NULL DEFAULT 0")
                        db.execSQL("UPDATE save_slot_metadata SET game_phase = (game_day - 1) / 10")
                    }
                    db.safeDropColumns("save_slot_metadata", "game_day")
                }
            }
        }

        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS storage_bags (
                        id TEXT NOT NULL,
                        slot_id INTEGER NOT NULL DEFAULT 0,
                        name TEXT NOT NULL DEFAULT '',
                        rarity INTEGER NOT NULL DEFAULT 1,
                        description TEXT NOT NULL DEFAULT '',
                        quantity INTEGER NOT NULL DEFAULT 1,
                        isLocked INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(id)
                    )
                """)
            }
        }

        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS mails (
                        id TEXT NOT NULL,
                        slotId INTEGER NOT NULL DEFAULT 0,
                        source TEXT NOT NULL DEFAULT 'builtin',
                        mailType TEXT NOT NULL DEFAULT 'reward',
                        title TEXT NOT NULL DEFAULT '',
                        content TEXT NOT NULL DEFAULT '',
                        senderName TEXT NOT NULL DEFAULT '天道意志',
                        sendTime INTEGER NOT NULL DEFAULT 0,
                        expireTime INTEGER NOT NULL DEFAULT 0,
                        isRead INTEGER NOT NULL DEFAULT 0,
                        attachmentClaimed INTEGER NOT NULL DEFAULT 0,
                        hasAttachment INTEGER NOT NULL DEFAULT 0,
                        attachments TEXT NOT NULL DEFAULT '[]',
                        remoteMailId TEXT DEFAULT NULL,
                        PRIMARY KEY(id)
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_mails_slotId ON mails(slotId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_mails_remoteMailId ON mails(remoteMailId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_mails_slotId_expireTime ON mails(slotId, expireTime)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS claimed_mail_records (
                        mailGlobalId TEXT NOT NULL,
                        slotId INTEGER NOT NULL DEFAULT 0,
                        claimedTime INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(mailGlobalId, slotId)
                    )
                """)
            }
        }

        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // v20→v21 迁移中索引名和 DEFAULT 值与 Room Entity 不匹配，
                // 重建两张新表修复（新表无业务数据，直接 DROP 安全）
                db.execSQL("DROP TABLE IF EXISTS mails")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS mails (
                        id TEXT NOT NULL,
                        slotId INTEGER NOT NULL DEFAULT 0,
                        source TEXT NOT NULL DEFAULT 'builtin',
                        mailType TEXT NOT NULL DEFAULT 'reward',
                        title TEXT NOT NULL DEFAULT '',
                        content TEXT NOT NULL DEFAULT '',
                        senderName TEXT NOT NULL DEFAULT '天道意志',
                        sendTime INTEGER NOT NULL DEFAULT 0,
                        expireTime INTEGER NOT NULL DEFAULT 0,
                        isRead INTEGER NOT NULL DEFAULT 0,
                        attachmentClaimed INTEGER NOT NULL DEFAULT 0,
                        hasAttachment INTEGER NOT NULL DEFAULT 0,
                        attachments TEXT NOT NULL DEFAULT '[]',
                        remoteMailId TEXT DEFAULT NULL,
                        PRIMARY KEY(id)
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_mails_slotId ON mails(slotId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_mails_remoteMailId ON mails(remoteMailId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_mails_slotId_expireTime ON mails(slotId, expireTime)")

                db.execSQL("DROP TABLE IF EXISTS claimed_mail_records")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS claimed_mail_records (
                        mailGlobalId TEXT NOT NULL,
                        slotId INTEGER NOT NULL DEFAULT 0,
                        claimedTime INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(mailGlobalId, slotId)
                    )
                """)
            }
        }

        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // v20→v21 和 v21→v22 迁移中 claimed_mail_records 表 DEFAULT 值与 Room Entity 不匹配，
                // 重建修复（新表无业务数据，直接 DROP 安全）
                db.execSQL("DROP TABLE IF EXISTS claimed_mail_records")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS claimed_mail_records (
                        mailGlobalId TEXT NOT NULL,
                        slotId INTEGER NOT NULL DEFAULT 0,
                        claimedTime INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(mailGlobalId, slotId)
                    )
                """)
            }
        }

        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS claimed_mail_records")
            }
        }

        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS game_heavy_data (
                        slot_id INTEGER NOT NULL,
                        data_key TEXT NOT NULL,
                        data_value TEXT NOT NULL,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY(slot_id, data_key)
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_game_heavy_data_slot_id ON game_heavy_data(slot_id)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_game_heavy_data_slot_id_data_key ON game_heavy_data(slot_id, data_key)")
                val heavyColumns = mapOf(
                    "aiSectDisciples" to "aiSectDisciples",
                    "sectDetails" to "sectDetails",
                    "exploredSects" to "exploredSects",
                    "scoutInfo" to "scoutInfo",
                    "manualProficiencies" to "manualProficiencies"
                )
                for ((key, col) in heavyColumns) {
                    db.execSQL("INSERT INTO game_heavy_data (slot_id, data_key, data_value, updated_at) SELECT slot_id, '$key', $col, strftime('%s','now') FROM game_data WHERE $col IS NOT NULL AND length($col) > 2")
                }
                db.execSQL("UPDATE game_data SET aiSectDisciples = '' WHERE length(aiSectDisciples) > 2")
                db.execSQL("UPDATE game_data SET sectDetails = '' WHERE length(sectDetails) > 2")
                db.execSQL("UPDATE game_data SET exploredSects = '' WHERE length(exploredSects) > 2")
                db.execSQL("UPDATE game_data SET scoutInfo = '' WHERE length(scoutInfo) > 2")
                db.execSQL("UPDATE game_data SET manualProficiencies = '' WHERE length(manualProficiencies) > 2")
            }
        }

        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE materials SET name = REPLACE(name, '蛇皮', '蛇鳞') WHERE name LIKE '%蛇皮'")
                db.execSQL("UPDATE materials SET name = REPLACE(name, '蛇骨', '蛇血'), category = 'BEAST_BLOOD' WHERE name LIKE '%蛇骨'")
                db.execSQL("UPDATE materials SET name = REPLACE(name, '毒牙', '蛇牙') WHERE name LIKE '%毒牙'")
                db.execSQL("UPDATE materials SET name = REPLACE(name, '龙骨', '龙爪'), category = 'BEAST_CLAW' WHERE name LIKE '%龙骨'")
                db.execSQL("UPDATE materials SET name = REPLACE(name, '龟甲', '龟血'), category = 'BEAST_BLOOD' WHERE name LIKE '%龟甲'")
            }
        }

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
                .addMigrations(MIGRATION_1_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24)
                .fallbackToDestructiveMigration()
                .fallbackToDestructiveMigrationOnDowngrade()
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
