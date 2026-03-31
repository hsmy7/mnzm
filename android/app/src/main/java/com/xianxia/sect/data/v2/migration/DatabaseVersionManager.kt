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
    }
    
    private fun registerMigration(fromVersion: Int, toVersion: Int, migration: (SupportSQLiteDatabase) -> Unit) {
        val migrationObj = object : Migration(fromVersion, toVersion) {
            override fun migrate(database: SupportSQLiteDatabase) {
                migration(database)
            }
        }
        migrations[fromVersion] = migrationObj
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
