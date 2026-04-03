package com.xianxia.sect.data.v2.migration

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.xianxia.sect.data.v2.StorageArchitecture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class MigrationPlan(
    val fromVersion: Int,
    val toVersion: Int,
    val steps: List<MigrationStep>,
    val estimatedDurationMs: Long,
    val requiresBackup: Boolean = true,
    val isReversible: Boolean = false
)

data class MigrationStep(
    val id: String,
    val description: String,
    val sqlStatements: List<String>,
    val estimatedDurationMs: Long,
    val isCritical: Boolean = false
)

data class MigrationProgress(
    val currentStep: Int,
    val totalSteps: Int,
    val currentStepName: String,
    val percentComplete: Float,
    val elapsedTimeMs: Long,
    val estimatedRemainingMs: Long
)

data class MigrationResult(
    val success: Boolean,
    val fromVersion: Int,
    val toVersion: Int,
    val durationMs: Long,
    val backupCreated: Boolean,
    val error: Throwable? = null
)

sealed class MigrationState {
    object Idle : MigrationState()
    object Checking : MigrationState()
    data class Ready(val plan: MigrationPlan) : MigrationState()
    data class InProgress(val progress: MigrationProgress) : MigrationState()
    data class Completed(val result: MigrationResult) : MigrationState()
    data class Failed(val error: Throwable) : MigrationState()
}

class DatabaseVersionManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "DatabaseVersionManager"
        private const val PREFS_NAME = "db_version_prefs"
        private const val KEY_DB_VERSION = "db_version_"
        private const val KEY_MIGRATION_HISTORY = "migration_history"
        private const val BACKUP_DIR = "db_backups"
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    private val migrations = ConcurrentHashMap<Int, Migration>()
    private val migrationHistory = mutableListOf<MigrationRecord>()
    
    private val _state = MutableStateFlow<MigrationState>(MigrationState.Idle)
    val state: StateFlow<MigrationState> = _state.asStateFlow()
    
    private val _progress = MutableStateFlow<MigrationProgress?>(null)
    val progress: StateFlow<MigrationProgress?> = _progress.asStateFlow()
    
    private val isMigrating = AtomicBoolean(false)
    private val migrationStartTime = AtomicLong(0)
    
    init {
        loadMigrationHistory()
        registerMigrations()
    }
    
    private fun loadMigrationHistory() {
        val historyJson = prefs.getString(KEY_MIGRATION_HISTORY, null)
        if (historyJson != null) {
            try {
                val gson = com.google.gson.Gson()
                val records = gson.fromJson(historyJson, Array<MigrationRecord>::class.java)
                migrationHistory.addAll(records)
                Log.i(TAG, "Loaded ${migrationHistory.size} migration records")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load migration history", e)
            }
        }
    }
    
    private fun saveMigrationHistory() {
        try {
            val gson = com.google.gson.Gson()
            val historyJson = gson.toJson(migrationHistory.toList())
            prefs.edit().putString(KEY_MIGRATION_HISTORY, historyJson).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save migration history", e)
        }
    }
    
    private fun registerMigrations() {
        registerMigration(58, 59) { database ->
            Log.i(TAG, "Migrating from version 58 to 59")
            
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS storage_metadata (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    key TEXT NOT NULL UNIQUE,
                    value TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
            """)
            
            database.execSQL("CREATE INDEX IF NOT EXISTS idx_storage_metadata_key ON storage_metadata (key)")
            
            database.execSQL("""
                INSERT INTO storage_metadata (key, value, created_at, updated_at)
                VALUES ('version', '59', ${System.currentTimeMillis()}, ${System.currentTimeMillis()})
            """)
            
            Log.i(TAG, "Migration 58 to 59 completed")
        }
        
        registerMigration(59, 60) { database ->
            Log.i(TAG, "Migrating from version 59 to 60")
            
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS data_integrity_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    table_name TEXT NOT NULL,
                    record_id TEXT NOT NULL,
                    checksum TEXT NOT NULL,
                    verified_at INTEGER NOT NULL,
                    is_valid INTEGER NOT NULL DEFAULT 1
                )
            """)
            
            database.execSQL("CREATE INDEX IF NOT EXISTS idx_integrity_log_table ON data_integrity_log (table_name)")
            database.execSQL("CREATE INDEX IF NOT EXISTS idx_integrity_log_time ON data_integrity_log (verified_at)")
            
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS async_operation_queue (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    operation_type TEXT NOT NULL,
                    data BLOB,
                    priority INTEGER NOT NULL DEFAULT 0,
                    status TEXT NOT NULL DEFAULT 'pending',
                    created_at INTEGER NOT NULL,
                    processed_at INTEGER,
                    retry_count INTEGER NOT NULL DEFAULT 0
                )
            """)
            
            database.execSQL("CREATE INDEX IF NOT EXISTS idx_async_queue_status ON async_operation_queue (status)")
            database.execSQL("CREATE INDEX IF NOT EXISTS idx_async_queue_priority ON async_operation_queue (priority)")
            
            database.execSQL("""
                UPDATE storage_metadata 
                SET value = '60', updated_at = ${System.currentTimeMillis()}
                WHERE key = 'version'
            """)
            
            Log.i(TAG, "Migration 59 to 60 completed")
        }
        
        /**
         * 迁移 60 → 61: 整合迁移
         *
         * 此迁移整合了之前分散在多次迁移中的表结构优化工作：
         * - 检查并修复 storage_metadata 表的索引完整性
         * - 清理 async_operation_queue 中的过期记录（超过30天）
         * - 为 data_integrity_log 添加复合索引以优化查询性能
         * - 更新 version 元数据记录
         * - 创建迁移计划文档注释，记录当前数据库架构状态
         */
        registerMigration(60, 61) { database ->
            Log.i(TAG, "Migrating from version 60 to 61 (Consolidation Migration)")
            
            // 1. 检查并修复 storage_metadata 表的索引
            try {
                // 验证关键索引是否存在，不存在则创建
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_storage_metadata_key ON storage_metadata (key)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_storage_metadata_updated ON storage_metadata (updated_at)")
                Log.d(TAG, "Verified storage_metadata indexes")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to verify storage_metadata indexes", e)
            }
            
            // 2. 清理 async_operation_queue 中的过期记录
            try {
                val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
                val deletedCount = database.delete(
                    "async_operation_queue",
                    "status IN ('completed', 'failed', 'cancelled') AND created_at < ?",
                    arrayOf(thirtyDaysAgo.toString())
                )
                Log.i(TAG, "Cleaned up $deletedCount expired async operation records")
                
                // 清理重试次数过多的失败记录
                database.execSQL("""
                    DELETE FROM async_operation_queue 
                    WHERE status = 'pending' 
                    AND retry_count >= 5 
                    AND created_at < ${System.currentTimeMillis() - (7L * 24 * 60 * 60 * 1000)}
                """)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cleanup async_operation_queue", e)
            }
            
            // 3. 为 data_integrity_log 添加复合索引优化查询性能
            try {
                // 复合索引：按表名和验证时间查询（常见查询模式）
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS idx_integrity_log_table_time 
                    ON data_integrity_log (table_name, verified_at)
                """)
                
                // 复合索引：按验证状态和时间查询
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS idx_integrity_log_valid_time 
                    ON data_integrity_log (is_valid, verified_at)
                """)
                Log.d(TAG, "Created composite indexes for data_integrity_log")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create composite indexes for data_integrity_log", e)
            }
            
            // 4. 更新 version 元数据记录
            try {
                val currentTime = System.currentTimeMillis()
                database.execSQL("""
                    INSERT OR REPLACE INTO storage_metadata (key, value, created_at, updated_at)
                    VALUES ('version', '61', $currentTime, $currentTime)
                """)
                
                // 记录迁移历史信息
                database.execSQL("""
                    INSERT OR REPLACE INTO storage_metadata (key, value, created_at, updated_at)
                    VALUES ('last_consolidation_migration', '60->61', $currentTime, $currentTime)
                """)
                
                Log.d(TAG, "Updated version metadata to 61")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update version metadata", e)
            }
            
            // 5. 数据库架构状态文档（通过元数据表存储）
            try {
                val architectureDoc = """
                    Database Architecture Status (Version 61):
                    ==========================================
                    Tables:
                    - storage_metadata: Key-value metadata store with indexes on (key), (updated_at)
                    - data_integrity_log: Integrity verification log with composite indexes on (table_name, verified_at), (is_valid, verified_at)
                    - async_operation_queue: Async operation queue with indexes on (status), (priority)
                    
                    Migration History:
                    - Version 58→59: Created storage_metadata table
                    - Version 59→60: Created data_integrity_log and async_operation_queue tables
                    - Version 60→61: Consolidation migration (index optimization, cleanup)
                    
                    Notes:
                    - All critical tables have appropriate indexes for common query patterns
                    - Expired async operation records are automatically cleaned up
                    - Data integrity tracking supports per-table and time-range queries
                """.trimIndent()
                
                database.execSQL("""
                    INSERT OR REPLACE INTO storage_metadata (key, value, created_at, updated_at)
                    VALUES ('architecture_doc', '${architectureDoc.replace("'", "''")}', 
                    ${System.currentTimeMillis()}, ${System.currentTimeMillis()})
                """)
                Log.d(TAG, "Stored architecture documentation in metadata")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to store architecture documentation", e)
            }
            
            Log.i(TAG, "Migration 60 to 61 (Consolidation) completed successfully")
        }
    }
    
    // ==================== 具名 Migration 类（避免匿名内部类触发 KSP getSimpleName NPE）====================
    private class LambdaMigration(fromVersion: Int, toVersion: Int, private val migration: (SupportSQLiteDatabase) -> Unit) : Migration(fromVersion, toVersion) {
        override fun migrate(database: SupportSQLiteDatabase) {
            migration(database)
        }
    }

    private fun registerMigration(fromVersion: Int, toVersion: Int, migration: (SupportSQLiteDatabase) -> Unit) {
        migrations[fromVersion] = LambdaMigration(fromVersion, toVersion, migration)
    }
    
    fun getMigration(startVersion: Int, endVersion: Int): Migration? {
        return migrations[startVersion]
    }
    
    fun getAllMigrations(): List<Migration> = migrations.values.toList()
    
    suspend fun checkAndMigrate(db: SupportSQLiteDatabase, currentVersion: Int): MigrationResult {
        if (currentVersion == StorageArchitecture.Database.CURRENT_VERSION) {
            return MigrationResult(true, currentVersion, currentVersion, 0, false)
        }
        
        if (currentVersion < StorageArchitecture.Database.MIN_SUPPORTED_VERSION) {
            return MigrationResult(
                false, 
                currentVersion, 
                StorageArchitecture.Database.CURRENT_VERSION,
                0,
                false,
                UnsupportedOperationException("Database version $currentVersion is too old, minimum supported is ${StorageArchitecture.Database.MIN_SUPPORTED_VERSION}")
            )
        }
        
        if (!isMigrating.compareAndSet(false, true)) {
            return MigrationResult(
                false,
                currentVersion,
                StorageArchitecture.Database.CURRENT_VERSION,
                0,
                false,
                IllegalStateException("Migration already in progress")
            )
        }
        
        migrationStartTime.set(System.currentTimeMillis())
        _state.value = MigrationState.Checking
        
        return try {
            val plan = createMigrationPlan(currentVersion, StorageArchitecture.Database.CURRENT_VERSION)
            _state.value = MigrationState.Ready(plan)
            
            if (plan.requiresBackup) {
                createBackup(db)
            }
            
            executeMigration(db, plan)
        } catch (e: Exception) {
            _state.value = MigrationState.Failed(e)
            MigrationResult(false, currentVersion, StorageArchitecture.Database.CURRENT_VERSION, 
                System.currentTimeMillis() - migrationStartTime.get(), false, e)
        } finally {
            isMigrating.set(false)
        }
    }
    
    private fun createMigrationPlan(fromVersion: Int, toVersion: Int): MigrationPlan {
        val steps = mutableListOf<MigrationStep>()
        var current = fromVersion
        
        while (current < toVersion) {
            val migration = migrations[current]
            if (migration != null) {
                steps.add(MigrationStep(
                    id = "migration_${current}_${current + 1}",
                    description = "Migrate from version $current to ${current + 1}",
                    sqlStatements = emptyList(),
                    estimatedDurationMs = 1000L,
                    isCritical = true
                ))
            }
            current++
        }
        
        return MigrationPlan(
            fromVersion = fromVersion,
            toVersion = toVersion,
            steps = steps,
            estimatedDurationMs = steps.sumOf { it.estimatedDurationMs },
            requiresBackup = true,
            isReversible = false
        )
    }
    
    private suspend fun executeMigration(db: SupportSQLiteDatabase, plan: MigrationPlan): MigrationResult {
        _state.value = MigrationState.InProgress(MigrationProgress(
            currentStep = 0,
            totalSteps = plan.steps.size,
            currentStepName = "Starting migration",
            percentComplete = 0f,
            elapsedTimeMs = 0,
            estimatedRemainingMs = plan.estimatedDurationMs
        ))
        
        val startTime = System.currentTimeMillis()
        var currentVersion = plan.fromVersion
        
        try {
            plan.steps.forEachIndexed { index, step ->
                updateProgress(index, plan.steps.size, step.description, startTime)
                
                val migration = migrations[currentVersion]
                if (migration != null) {
                    migration.migrate(db)
                }
                
                currentVersion++
                
                delay(10)
            }
            
            val duration = System.currentTimeMillis() - startTime
            
            val record = MigrationRecord(
                fromVersion = plan.fromVersion,
                toVersion = plan.toVersion,
                timestamp = System.currentTimeMillis(),
                durationMs = duration,
                success = true
            )
            
            migrationHistory.add(record)
            saveMigrationHistory()
            
            val result = MigrationResult(true, plan.fromVersion, plan.toVersion, duration, plan.requiresBackup)
            _state.value = MigrationState.Completed(result)
            
            Log.i(TAG, "Migration completed: ${plan.fromVersion} -> ${plan.toVersion} in ${duration}ms")
            
            return result
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            
            val record = MigrationRecord(
                fromVersion = plan.fromVersion,
                toVersion = currentVersion,
                timestamp = System.currentTimeMillis(),
                durationMs = duration,
                success = false,
                errorMessage = e.message
            )
            
            migrationHistory.add(record)
            saveMigrationHistory()
            
            throw e
        }
    }
    
    private fun updateProgress(currentStep: Int, totalSteps: Int, stepName: String, startTime: Long) {
        val elapsed = System.currentTimeMillis() - startTime
        val percent = if (totalSteps > 0) (currentStep + 1).toFloat() / totalSteps else 0f
        val estimatedRemaining = if (percent > 0) (elapsed / percent * (1 - percent)).toLong() else 0L
        
        val progress = MigrationProgress(
            currentStep = currentStep + 1,
            totalSteps = totalSteps,
            currentStepName = stepName,
            percentComplete = percent * 100,
            elapsedTimeMs = elapsed,
            estimatedRemainingMs = estimatedRemaining
        )
        
        _progress.value = progress
        _state.value = MigrationState.InProgress(progress)
    }
    
    private fun createBackup(db: SupportSQLiteDatabase): Boolean {
        return try {
            val dbPath = db.path ?: return false
            val dbFile = File(dbPath)
            
            if (!dbFile.exists()) return false
            
            val backupDir = File(context.filesDir, BACKUP_DIR)
            if (!backupDir.exists()) backupDir.mkdirs()
            
            val timestamp = System.currentTimeMillis()
            val backupFile = File(backupDir, "backup_${timestamp}.db")
            
            dbFile.copyTo(backupFile, overwrite = true)
            
            val walFile = File(dbPath + "-wal")
            if (walFile.exists()) {
                walFile.copyTo(File(backupDir, "backup_${timestamp}.db-wal"), overwrite = true)
            }
            
            cleanupOldBackups()
            
            Log.i(TAG, "Database backup created: ${backupFile.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create database backup", e)
            false
        }
    }
    
    private fun cleanupOldBackups() {
        val backupDir = File(context.filesDir, BACKUP_DIR)
        if (!backupDir.exists()) return
        
        val maxBackups = 5
        val backups = backupDir.listFiles()
            ?.filter { it.name.startsWith("backup_") && it.name.endsWith(".db") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        
        backups.drop(maxBackups).forEach { it.delete() }
    }
    
    fun restoreFromBackup(backupFile: File): Boolean {
        return try {
            val dbName = backupFile.name.removePrefix("backup_").removeSuffix(".db")
            val dbFile = context.getDatabasePath(dbName)
            
            backupFile.copyTo(dbFile, overwrite = true)
            
            val walBackup = File(backupFile.parent, "${backupFile.name}-wal")
            if (walBackup.exists()) {
                walBackup.copyTo(File(dbFile.path + "-wal"), overwrite = true)
            }
            
            Log.i(TAG, "Database restored from backup: ${backupFile.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore from backup", e)
            false
        }
    }
    
    fun listBackups(): List<BackupInfo> {
        val backupDir = File(context.filesDir, BACKUP_DIR)
        if (!backupDir.exists()) return emptyList()
        
        return backupDir.listFiles()
            ?.filter { it.name.startsWith("backup_") && it.name.endsWith(".db") }
            ?.map { file ->
                BackupInfo(
                    file = file,
                    timestamp = file.lastModified(),
                    size = file.length()
                )
            }
            ?.sortedByDescending { it.timestamp }
            ?: emptyList()
    }
    
    fun getMigrationHistory(): List<MigrationRecord> = migrationHistory.toList()
    
    fun getCurrentVersion(): Int {
        return prefs.getInt(KEY_DB_VERSION, StorageArchitecture.Database.CURRENT_VERSION)
    }
    
    fun setCurrentVersion(version: Int) {
        prefs.edit().putInt(KEY_DB_VERSION, version).apply()
    }
    
    /**
     * 获取迁移系统健康状态
     *
     * 返回当前迁移系统的全面健康信息，包括：
     * - 当前版本和目标版本
     * - 待执行迁移数量
     * - 最后一次迁移时间
     * - 迁移成功率统计
     * - 健康建议和推荐操作
     *
     * @return MigrationHealth 对象，包含完整的健康状态信息
     */
    fun getMigrationHealth(): MigrationHealth {
        val currentVersion = getCurrentVersion()
        val targetVersion = StorageArchitecture.Database.CURRENT_VERSION
        
        // 计算待执行迁移数
        val pendingMigrations = if (currentVersion < targetVersion) {
            var count = 0
            var version = currentVersion
            while (version < targetVersion) {
                if (migrations.containsKey(version)) {
                    count++
                }
                version++
            }
            count
        } else {
            0
        }
        
        // 统计历史记录
        val totalMigrations = migrationHistory.size
        val failedMigrations = migrationHistory.count { !it.success }
        val successRate = if (totalMigrations > 0) {
            (totalMigrations - failedMigrations).toFloat() / totalMigrations.toFloat()
        } else {
            1.0f  // 无记录时视为完美（或可根据业务需求调整）
        }
        
        // 获取最后迁移时间
        val lastMigrationTime = if (migrationHistory.isNotEmpty()) {
            migrationHistory.maxOfOrNull { it.timestamp }
        } else {
            null
        }
        
        // 生成健康建议
        val recommendations = mutableListOf<String>()
        
        if (pendingMigrations > 0) {
            recommendations.add("有 $pendingMigrations 个待执行的迁移，建议尽快执行以保持数据库最新")
        }
        
        if (failedMigrations > 0) {
            val recentFailures = migrationHistory.filter { 
                !it.success && it.timestamp > System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000 
            }.size
            if (recentFailures > 0) {
                recommendations.add("最近7天有 $recentFailures 次迁移失败，请检查错误日志")
            } else {
                recommendations.add("历史上有 $failedMigrations 次失败记录，但近期运行正常")
            }
        }
        
        if (totalMigrations > 10 && failedMigrations == 0 && pendingMigrations == 0) {
            recommendations.add("迁移系统运行良好，可考虑执行 consolidateMigrations() 归档旧记录")
        }
        
        if (lastMigrationTime != null) {
            val daysSinceLastMigration = (System.currentTimeMillis() - lastMigrationTime) / (24 * 60 * 60 * 1000)
            if (daysSinceLastMigration > 30 && currentVersion < targetVersion) {
                recommendations.add("已超过 ${daysSinceLastMigration} 天未执行迁移，请检查是否有阻塞")
            }
        }
        
        if (recommendations.isEmpty()) {
            recommendations.add("迁移系统状态正常")
        }
        
        return MigrationHealth(
            currentVersion = currentVersion,
            targetVersion = targetVersion,
            pendingMigrations = pendingMigrations,
            lastMigrationTime = lastMigrationTime,
            successRate = successRate,
            totalMigrations = totalMigrations,
            failedMigrations = failedMigrations,
            recommendations = recommendations.toList()
        )
    }
    
    /**
     * 整合归档旧迁移记录
     *
     * 将已成功应用的旧迁移记录归档，减少活动迁移链长度。
     * 此操作不会影响实际的数据库结构，仅优化迁移历史记录的管理。
     *
     * 执行条件：
     * - 必须有成功的历史迁移记录
     * - 当前不在迁移过程中
     *
     * @return ConsolidationResult 包含整合结果详情
     */
    suspend fun consolidateMigrations(): ConsolidationResult {
        // 检查是否正在迁移
        if (isMigrating.get()) {
            return ConsolidationResult(
                consolidated = false,
                archivedCount = 0,
                newBaseVersion = getCurrentVersion(),
                message = "Cannot consolidate: migration is in progress"
            )
        }
        
        // 筛选可归档的成功记录（排除最近3次的记录以保留足够的历史）
        val recentThreshold = System.currentTimeMillis() - (3L * 24 * 60 * 60 * 1000)  // 3天
        val successfulRecords = migrationHistory.filter { 
            it.success && it.timestamp < recentThreshold 
        }
        
        if (successfulRecords.isEmpty()) {
            return ConsolidationResult(
                consolidated = false,
                archivedCount = 0,
                newBaseVersion = getCurrentVersion(),
                message = "No eligible records to archive (need successful records older than 3 days)"
            )
        }
        
        return try {
            // 计算新的基础版本（使用最大成功迁移的 toVersion）
            val newBaseVersion = successfulRecords.maxOfOrNull { it.toVersion } ?: getCurrentVersion()
            
            // 创建归档摘要
            val archivedRecords = successfulRecords.map { record ->
                mapOf(
                    "fromVersion" to record.fromVersion,
                    "toVersion" to record.toVersion,
                    "timestamp" to record.timestamp,
                    "durationMs" to record.durationMs,
                    "success" to record.success
                )
            }
            
            // 从活动列表中移除已归档的记录
            val archivedCount = successfulRecords.size
            migrationHistory.removeAll { it in successfulRecords }
            
            // 保存更新后的历史记录
            saveMigrationHistory()
            
            // 记录归档操作到偏好设置
            val consolidationRecord = mapOf(
                "archivedAt" to System.currentTimeMillis(),
                "archivedCount" to archivedCount,
                "newBaseVersion" to newBaseVersion,
                "archivedRecords" to archivedRecords
            )
            
            try {
                val gson = com.google.gson.Gson()
                prefs.edit()
                    .putString("last_consolidation", gson.toJson(consolidationRecord))
                    .apply()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save consolidation metadata", e)
            }
            
            Log.i(TAG, "Consolidated $archivedCount migration records, new base version: $newBaseVersion")
            
            ConsolidationResult(
                consolidated = true,
                archivedCount = archivedCount,
                newBaseVersion = newBaseVersion,
                message = "Successfully archived $archivedCount migration records. New base version: $newBaseVersion"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to consolidate migrations", e)
            ConsolidationResult(
                consolidated = false,
                archivedCount = 0,
                newBaseVersion = getCurrentVersion(),
                message = "Consolidation failed: ${e.message}"
            )
        }
    }
    
    fun shutdown() {
        scope.cancel()
        Log.i(TAG, "DatabaseVersionManager shutdown completed")
    }
}

data class MigrationRecord(
    val fromVersion: Int,
    val toVersion: Int,
    val timestamp: Long,
    val durationMs: Long,
    val success: Boolean,
    val errorMessage: String? = null
)

data class BackupInfo(
    val file: File,
    val timestamp: Long,
    val size: Long
)

/**
 * 数据库迁移健康状态
 * 提供迁移系统的整体健康状况信息
 */
data class MigrationHealth(
    val currentVersion: Int,
    val targetVersion: Int,
    val pendingMigrations: Int,
    val lastMigrationTime: Long?,
    val successRate: Float,
    val totalMigrations: Int,
    val failedMigrations: Int,
    val recommendations: List<String>
) {
    val isHealthy: Boolean get() = failedMigrations == 0 && pendingMigrations == 0
    val healthScore: Float get() = when {
        failedMigrations > 0 -> 0.3f
        pendingMigrations > 0 -> 0.6f
        else -> 1.0f
    }
}

/**
 * 迁移整合结果
 * 记录迁移归档操作的结果
 */
data class ConsolidationResult(
    val consolidated: Boolean,
    val archivedCount: Int,
    val newBaseVersion: Int,
    val message: String? = null
)
