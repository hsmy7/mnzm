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
import com.xianxia.sect.core.model.EquipmentInstance
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



/** 文件级日志 TAG（启动前快照恢复顶层辅助函数共用） */
private const val TAG = "GameDatabase"

/** 恢复尝试 marker 文件名——恢复后数据库重建崩溃时防止"恢复→崩溃"死循环 */
private const val RESTORE_ATTEMPT_MARKER = ".restore_attempted"
private const val RESTORE_ATTEMPT_MARKER_CONTENT = "1"

/** 启动前快照文件大小上限（200MB，防恶意/损坏超大快照占满磁盘） */
private const val MAX_BACKUP_FILE_SIZE_BYTES = 200L * 1024 * 1024

/** 启动前快照 game_data 行数上限（game_data 恒单行；上限 64 防恶意行数膨胀） */
private const val MAX_BACKUP_GAME_DATA_ROWS = 64


object GameDatabaseConfig {
    /**
     * 数据库 schema 版本号——@Database(version) 与启动前快照判据统一引用此常量，
     * 禁止任何位置硬编码版本号。
     * 升级数据库版本时必须同步递增此常量、注册 `MIGRATION_(N-1)_N` 并更新
     * `MigrationRequiredGuardTest` 的实体清单基线（缺迁移 = 老库被 destructive 重建）。
     */
    const val DATABASE_VERSION = 70

    /**
     * 判定是否应从启动前快照恢复（纯逻辑，无 I/O——独立测试覆盖）。
     *
     * @param currentRowCount 当前数据库 game_data 行数（-1 = 读取失败/库打不开）
     * @param currentVersion 当前数据库 user_version（-1 = 读取失败）
     * @param backupVersion 启动前快照的 user_version
     * @return true = 应恢复；false = 跳过
     */
    @Suppress("ReturnCount") // 恢复判定多分支守卫，多 return 为守卫风格
    fun shouldRestoreFromBackup(
        currentRowCount: Int,
        currentVersion: Int,
        backupVersion: Int
    ): Boolean {
        // 当前库打不开/表缺失（-1）或空库（destructive 重建后）→ 恢复
        if (currentRowCount <= 0) return true
        // 降级场景：当前库版本高于 App 支持的版本（高版本 App 数据回退到低版本
        // App）→ Room 无法降级打开必然崩溃——用启动前快照（旧版本）恢复
        if (currentVersion > DATABASE_VERSION &&
            backupVersion in 2..DATABASE_VERSION && backupVersion < currentVersion
        ) {
            return true
        }
        // 有数据但版本升级待完成（快照创建后重建从未完成）→ 恢复
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
        OverflowMailDraftEntity::class,
        DirectMailDraftEntity::class
    ],
    // v70: 多档元数据表退役（SS4）——`save_slot_metadata` 整表删除，单档摘要
    // 直接读 `game_data` 行。无迁移路径（SS0 删档重置后不存在需要保护的旧库），
    // v69 及更早的库打开时由 fallbackToDestructiveMigration 全量毁灭重建；
    // 新增 @Entity / 列变更必须同批注册 MIGRATION_(N-1)_N，否则老库被静默重建——
    // `MigrationRequiredGuardTest` 守卫
    version = GameDatabaseConfig.DATABASE_VERSION
)

@TypeConverters(ProtobufConverters::class, EnumConverters::class, CollectionConverters::class, JsonConverters::class)
@Suppress("TooManyFunctions") // Room 数据库契约面：25 个 abstract DAO 访问器 = Room 强制协议 + 数据库回调，
// 函数数=注册 DAO 数，拆分即破坏 RoomDatabase 单元
abstract class GameDatabase : RoomDatabase() {

    abstract fun gameDataDao(): GameDataDao
    abstract fun discipleDao(): DiscipleDao
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
     * 裁剪启动前快照：按版本号降序保留最近 [keep] 份 `.pre_migrate_backup.v{N}`，
     * 删除更旧。
     * 幂等可重入；接入两处——verifyAndRecoverDatabase 版本达标分支（每次
     * 启动 DB 打开）+ DataPruningScheduler 周期任务（双保险）。
     */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    fun pruneDatabaseSnapshots(keep: Int = STARTUP_SNAPSHOT_KEEP_COUNT) {
        pruneDatabaseSnapshots(openHelper.writableDatabase.path, keep)
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

        /** 启动前快照保留份数（不是时长；WAL 快照时长见 `SaveLimitsConfig.WAL_SNAPSHOT_RETENTION_HOURS`）：最近 2 个版本供降级恢复 */
        const val STARTUP_SNAPSHOT_KEEP_COUNT = 2
    /** 静态实现（companion 可达——verifyAndRecoverDatabase 为 companion 域） */
    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    fun pruneDatabaseSnapshots(dbPath: String?, keep: Int = STARTUP_SNAPSHOT_KEEP_COUNT) {
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
                    if (f.delete()) Log.i(TAG, "Pruned old startup snapshot: ${f.name}")
                }
        } catch (e: Exception) {
            Log.w(TAG, "pruneDatabaseSnapshots failed (non-fatal)", e)
        }
    }


        private const val RESTORE_ATTEMPT_MARKER_CONTENT = "1"

        private val threadCounter = AtomicInteger(0)

        /**
         * 在数据库打开前落启动前快照。
         * 将库文件复制到 `{db}.pre_migrate_backup.v{当前版本}`（库文件所在目录 =
         * 账号数据空间，SS2 分库）。
         * 仅当数据库版本落后于 [GameDatabaseConfig.DATABASE_VERSION] 时执行——
         * 版本落后即意味着本次启动将发生 destructive 重建，快照是重建前唯一的
         * 抢救副本（供 [restoreFromBackupIfNeeded] 的损坏恢复与降级恢复消费）。
         *
         * 注意：WAL 模式下直接文件复制可能包含未检查点的 wal 数据。
         * 此处使用 PRAGMA wal_checkpoint(TRUNCATE) 先行落盘再复制。
         */
        @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
        fun snapshotDatabaseBeforeUpgrade(dbFile: File) {
            if (!dbFile.exists()) {
                Log.d(TAG, "数据库文件不存在，跳过启动前快照（首次安装）")
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
                Log.w(TAG, "无法读取当前数据库版本，跳过启动前快照", e)
                return
            }

            val targetVersion = GameDatabaseConfig.DATABASE_VERSION
            if (currentVersion >= targetVersion) {
                Log.d(TAG, "数据库已是最新版本 (v$currentVersion)，无需快照")
                return
            }
            if (currentVersion < 2) {
                // v1 数据库允许 destructive 重建，无保留价值，跳过快照
                Log.d(TAG, "数据库版本 v$currentVersion 低于 v2，允许毁灭回退，跳过快照")
                return
            }

            // 快照版本化命名 `{db}.pre_migrate_backup.v{currentVersion}`——重建成功后
            // 保留快照：高版本 App 数据降回旧版 App 时仍需旧版本快照恢复，
            // 多版本保留供降级恢复与维护清理
            val backupFile = File(dbFile.absolutePath + ".pre_migrate_backup.v$currentVersion")
            try {
                checkpointForSnapshot(dbFile)
                // 文件级复制快照
                dbFile.inputStream().use { input ->
                    backupFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.i(TAG, "启动前快照完成: ${backupFile.absolutePath} (v$currentVersion → v$targetVersion)")
            } catch (e: Exception) {
                Log.e(TAG, "启动前快照失败（非阻断，继续执行）", e)
                backupFile.delete()  // 清理不完整快照
            }
        }

        /**
         * 快照前 WAL 落盘：部分 SQLite 实现拒绝 execSQL 执行 PRAGMA 查询
         * （真机 Bugly 同源现象，见 performCheckpointSync），拒绝时降级 rawQuery 重试。
         */
        @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
        private fun checkpointForSnapshot(dbFile: File) {
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                try {
                    db.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
                } catch (e: android.database.sqlite.SQLiteException) {
                    if (e.message?.contains("query or rawQuery") == true) {
                        db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).close()
                    } else {
                        throw e
                    }
                }
            }
        }

        /**
         * 创建账号空间内的统一单实例数据库。
         *
         * @param context applicationContext（Room builder / 设备内存探测）
         * @param dbFile 库文件绝对路径——由账号数据空间派生
         *   （`filesDir/accounts/<accountKey>/xianxia_sect.db`，路径经
         *   [com.xianxia.sect.data.account.AccountSpaceManager.requireDatabaseFile]）。
         *   绝对路径直接被 SQLiteOpenHelper 采用，不再落入设备级 databases/ 目录。
         */
        fun create(context: Context, dbFile: File): GameDatabase {
            Log.i(TAG, "Creating single-instance database at: $dbFile")

            // 数据库打开前落启动前快照（destructive 重建前的抢救副本）
            snapshotDatabaseBeforeUpgrade(dbFile)

            return Room.databaseBuilder(
                context.applicationContext,
                GameDatabase::class.java,
                dbFile.absolutePath
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
                        Log.i(TAG, "Database created")
                        configureDatabase(db, context)
                    }
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        Log.i(TAG, "Database opened")
                        optimizeDatabase(db)
                        // 启动时检查数据库完整性，并在异常时自动尝试从快照恢复
                        verifyAndRecoverDatabase(db, dbFile)
                    }
                })
                // 版本落后且无迁移路径时毁灭重建（dropAllTables 含 Room schema 外
                // 的历史残留表）；忘写迁移 = 静默清档，由 MigrationRequiredGuardTest
                // 在 CI 面兜住
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                .also { db -> applySafetyPragmas(db) }
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
         * 如果 integrity_check 失败或 game_data 为空（可能由 destructive 重建导致），
         * 记录严重警告以便后续处理。
         * 实际的数据恢复通过以下机制完成：
         * 1. StorageEngine.load() → SaveFileManager.readWithFallback() 自动从 .sav/.bak 恢复
         * 2. 如果已调用 restoreFromBackupIfNeeded() 且启动前快照存在，则文件级恢复优先
         */
        @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
        private fun verifyAndRecoverDatabase(db: SupportSQLiteDatabase, dbFile: File) {
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

            // Step 4: 版本达标后清理恢复 marker + 裁剪启动前快照
            //（注释承诺的维护任务接线——版本达标即旧版快照
            // 价值衰减，按保留窗口留最近 STARTUP_SNAPSHOT_KEEP_COUNT 份）
            try {
                if (db.version >= GameDatabaseConfig.DATABASE_VERSION) {
                    File(dbFile.absolutePath + RESTORE_ATTEMPT_MARKER).delete()
                    pruneDatabaseSnapshots(dbFile.absolutePath)
                }
            } catch (e: Exception) {
                Log.w(TAG, "清理恢复 marker/启动前快照失败", e)
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
         * 在 Room databaseBuilder 执行前检查并恢复快照。
         * 如果启动前快照文件存在且当前数据库为空/损坏，
         * 用快照文件覆盖当前数据库。
         *
         * 此方法必须在 [create] 之前调用。当前由 AppModule.provideGameDatabase 调用。
         *
         * @param dbFile 账号空间内的库文件（快照/恢复 marker 均按其路径派生）
         * @return true 表示已执行恢复，false 表示无需恢复或恢复失败
         */
        @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
        fun restoreFromBackupIfNeeded(dbFile: File): Boolean {
            val backupFile = findVersionedBackup(dbFile) ?: return false
            val markerFile = File(dbFile.absolutePath + RESTORE_ATTEMPT_MARKER)
            if (!dbFile.exists()) return false

            // 验证快照文件可用（含大小/行数上限检查）
            val backup = readBackupInfo(backupFile)
            if (!backup.ok) {
                Log.w(TAG, "快照文件 integrity_check 失败，不可用于恢复")
                return false
            }

            // 读取当前数据库版本号与 game_data 行数
            val current = readCurrentDbInfo(dbFile)

            if (!shouldAttemptRestore(current, backup, markerFile)) {
                return false
            }
            logUpgradePendingIfAny(current, backup)

            // 执行恢复：用快照文件覆盖当前数据库
            return performFileRestoreWithMarker(dbFile, backupFile, backup, markerFile)
        }

        /**
         * 恢复前置判定：当前库无数据、判定不通过或存在恢复 marker 时跳过并记录原因。
         *
         * - 版本升级待完成（重建崩溃后 DB 行数仍 > 0）场景的判定在
         *   GameDatabaseConfig.shouldRestoreFromBackup 纯函数中，便于单元测试
         * - 恢复-重建崩溃死循环防护：上次恢复后重建仍未完成（marker 存在）→
         *   跳过重复恢复，让 Room 直接重建并崩溃报错（行为可预期）
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
                Log.d(TAG, "当前数据库有数据 (${current.rowCount} 行)，跳过快照恢复")
                return false
            }
            if (markerFile.exists()) {
                Log.w(TAG, "检测到上次恢复后重建仍未完成，跳过重复恢复（防止死循环）")
                return false
            }
            return true
        }

        /** 版本升级未完成场景告警：快照与当前库同版本且低于目标版本 */
        private fun logUpgradePendingIfAny(current: CurrentDbInfo, backup: BackupValidation) {
            if (current.version < GameDatabaseConfig.DATABASE_VERSION &&
                backup.version == current.version
            ) {
                Log.w(TAG, "检测到版本升级未完成 (v${current.version} → v" +
                    "${GameDatabaseConfig.DATABASE_VERSION})，从启动前快照恢复")
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
                // 创建恢复 marker：重建成功后由 verifyAndRecoverDatabase 清理
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
         * 扫描可用的启动前快照文件。
         *
         * 快照版本化命名 `{db}.pre_migrate_backup.v{N}`，选择最高可用版本
         * （N ∈ 2..DATABASE_VERSION-1）——降级场景（高版本 App 回退）需要比
         * 当前库版本低的快照。
         */
        private fun findVersionedBackup(dbFile: File): File? {
            return (2 until GameDatabaseConfig.DATABASE_VERSION).mapNotNull { v ->
                File(dbFile.absolutePath + ".pre_migrate_backup.v$v").takeIf { it.exists() }
            }.maxByOrNull { it.name.substringAfterLast(".v").toIntOrNull() ?: -1 }
        }

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




// ==================== 启动前快照恢复辅助（文件顶层私有） ====================

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

/** 验证启动前快照文件可用性（integrity_check + user_version + game_data 行数 + 大小/行数上限） */
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
        Log.e(TAG, "快照文件验证失败: ${backupFile.absolutePath}", e)
    }
    if (!ok) return BackupValidation(false, -1, -1)
    val rowCount = try {
        SQLiteDatabase.openDatabase(backupFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { bdb ->
            readGameDataRowCount(bdb)
        }
    } catch (e: Exception) {
        Log.e(TAG, "快照文件行数读取失败: ${backupFile.absolutePath}", e)
        -1
    }
    // game_data 行数上限（game_data 恒单行）
    if (rowCount > MAX_BACKUP_GAME_DATA_ROWS) {
        Log.w(TAG, "快照 game_data 行数异常 ($rowCount)，视为无效")
        return BackupValidation(false, -1, -1)
    }
    val version = try {
        SQLiteDatabase.openDatabase(backupFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { bdb ->
            readUserVersion(bdb)
        }
    } catch (e: Exception) {
        Log.e(TAG, "快照文件版本读取失败: ${backupFile.absolutePath}", e)
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
        Log.w(TAG, "当前数据库无法打开，将直接使用快照覆盖", e)
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
