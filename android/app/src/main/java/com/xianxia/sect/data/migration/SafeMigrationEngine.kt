package com.xianxia.sect.data.migration

import android.content.Context
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object MigrationConfig {
    const val BACKUP_DIR = "migration_backups"
    const val MAX_BACKUPS = 5
    const val BATCH_SIZE = 100
    const val PROGRESS_UPDATE_INTERVAL = 100
    const val VALIDATION_SAMPLE_SIZE = 1000
}

enum class MigrationStatus {
    IDLE,
    PREPARING,
    BACKING_UP,
    MIGRATING,
    VALIDATING,
    COMPLETED,
    FAILED,
    ROLLED_BACK
}

data class MigrationStep(
    val id: String,
    val name: String,
    val description: String,
    val version: Int,
    var status: StepStatus = StepStatus.PENDING,
    var progress: Float = 0f,
    var error: String? = null
)

enum class StepStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    SKIPPED
}

data class SafeMigrationProgress(
    val currentStep: String = "",
    val currentStepIndex: Int = 0,
    val totalSteps: Int = 0,
    val overallProgress: Float = 0f,
    val itemsProcessed: Long = 0,
    val totalItems: Long = 0,
    val status: MigrationStatus = MigrationStatus.IDLE,
    val error: String? = null,
    val startTime: Long = 0,
    val elapsedTime: Long = 0
)

data class MigrationResult(
    val success: Boolean,
    val fromVersion: Int,
    val toVersion: Int,
    val stepsCompleted: Int,
    val stepsFailed: Int,
    val durationMs: Long,
    val rolledBack: Boolean,
    val errors: List<String>
)

data class ValidationResult(
    val isValid: Boolean,
    val tablesChecked: Int,
    val recordsChecked: Long,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
)

data class BackupInfo(
    val id: String,
    val version: Int,
    val timestamp: Long,
    val size: Long,
    val checksum: String,
    val path: String
)

interface MigrationHandler {
    val version: Int
    val name: String
    suspend fun migrate(db: SupportSQLiteDatabase, context: MigrationContext): Boolean
    suspend fun validate(db: SupportSQLiteDatabase): ValidationResult
}

data class MigrationContext(
    val context: Context,
    val progressCallback: (Float, String) -> Unit = { _, _ -> },
    val isCancelled: () -> Boolean = { false }
)

class SafeMigrationEngine(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "SafeMigrationEngine"
    }
    
    private val backupDir = File(context.filesDir, MigrationConfig.BACKUP_DIR)
    
    private val _migrationProgress = MutableStateFlow(SafeMigrationProgress())
    val migrationProgress: StateFlow<SafeMigrationProgress> = _migrationProgress.asStateFlow()
    
    private val migrationHandlers = mutableListOf<MigrationHandler>()
    private val backups = mutableListOf<BackupInfo>()
    
    private val isMigrating = AtomicBoolean(false)
    private val isCancelled = AtomicBoolean(false)
    private val itemsProcessed = AtomicLong(0)
    private val startTime = AtomicLong(0)
    
    init {
        if (!backupDir.exists()) backupDir.mkdirs()
        loadBackups()
    }
    
    fun registerHandler(handler: MigrationHandler) {
        migrationHandlers.add(handler)
        migrationHandlers.sortBy { it.version }
    }
    
    suspend fun migrate(
        db: SupportSQLiteDatabase,
        fromVersion: Int,
        toVersion: Int
    ): MigrationResult {
        if (!isMigrating.compareAndSet(false, true)) {
            return MigrationResult(
                success = false,
                fromVersion = fromVersion,
                toVersion = toVersion,
                stepsCompleted = 0,
                stepsFailed = 0,
                durationMs = 0,
                rolledBack = false,
                errors = listOf("Migration already in progress")
            )
        }
        
        isCancelled.set(false)
        startTime.set(System.currentTimeMillis())
        itemsProcessed.set(0)
        
        val errors = mutableListOf<String>()
        var stepsCompleted = 0
        var stepsFailed = 0
        var rolledBack = false
        
        try {
            updateProgress(MigrationStatus.PREPARING, "准备迁移")
            
            val steps = buildMigrationSteps(fromVersion, toVersion)
            if (steps.isEmpty()) {
                return MigrationResult(
                    success = true,
                    fromVersion = fromVersion,
                    toVersion = toVersion,
                    stepsCompleted = 0,
                    stepsFailed = 0,
                    durationMs = System.currentTimeMillis() - startTime.get(),
                    rolledBack = false,
                    errors = emptyList()
                )
            }
            
            updateProgress(MigrationStatus.BACKING_UP, "创建备份")
            val backup = createBackup(db, fromVersion)
            
            updateProgress(MigrationStatus.MIGRATING, "执行迁移")
            
            for ((index, step) in steps.withIndex()) {
                if (isCancelled.get()) {
                    throw MigrationCancelledException("Migration cancelled by user")
                }
                
                step.status = StepStatus.RUNNING
                updateStepProgress(step, index, steps.size)
                
                val handler = migrationHandlers.find { it.version == step.version }
                
                if (handler != null) {
                    val migrationContext = MigrationContext(
                        context = context,
                        progressCallback = { progress, message ->
                            step.progress = progress
                            updateStepProgress(step, index, steps.size, message)
                        },
                        isCancelled = { isCancelled.get() }
                    )
                    
                    val success = try {
                        handler.migrate(db, migrationContext)
                    } catch (e: Exception) {
                        Log.e(TAG, "Migration step ${step.name} failed", e)
                        step.error = e.message
                        false
                    }
                    
                    if (success) {
                        step.status = StepStatus.COMPLETED
                        step.progress = 1f
                        stepsCompleted++
                    } else {
                        step.status = StepStatus.FAILED
                        stepsFailed++
                        errors.add("Step ${step.name} failed: ${step.error}")
                        
                        updateProgress(MigrationStatus.VALIDATING, "验证数据完整性")
                        val validationResult = handler.validate(db)
                        
                        if (!validationResult.isValid) {
                            errors.addAll(validationResult.errors)
                            throw MigrationException("Validation failed after migration step")
                        }
                    }
                } else {
                    step.status = StepStatus.SKIPPED
                }
            }
            
            updateProgress(MigrationStatus.VALIDATING, "验证迁移结果")
            val finalValidation = validateMigration(db, toVersion)
            
            if (!finalValidation.isValid) {
                errors.addAll(finalValidation.errors)
                throw MigrationException("Final validation failed")
            }
            
            cleanupOldBackups()
            
            updateProgress(MigrationStatus.COMPLETED, "迁移完成")
            
            return MigrationResult(
                success = stepsFailed == 0,
                fromVersion = fromVersion,
                toVersion = toVersion,
                stepsCompleted = stepsCompleted,
                stepsFailed = stepsFailed,
                durationMs = System.currentTimeMillis() - startTime.get(),
                rolledBack = false,
                errors = errors
            )
            
        } catch (e: MigrationCancelledException) {
            Log.w(TAG, "Migration cancelled", e)
            errors.add(e.message ?: "Migration cancelled")
            
            rolledBack = performRollback(db, fromVersion)
            
            updateProgress(MigrationStatus.ROLLED_BACK, "已回滚")
            
            return MigrationResult(
                success = false,
                fromVersion = fromVersion,
                toVersion = toVersion,
                stepsCompleted = stepsCompleted,
                stepsFailed = stepsFailed,
                durationMs = System.currentTimeMillis() - startTime.get(),
                rolledBack = rolledBack,
                errors = errors
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Migration failed", e)
            errors.add(e.message ?: "Unknown error")
            
            rolledBack = performRollback(db, fromVersion)
            
            updateProgress(if (rolledBack) MigrationStatus.ROLLED_BACK else MigrationStatus.FAILED, 
                          if (rolledBack) "已回滚" else "迁移失败")
            
            return MigrationResult(
                success = false,
                fromVersion = fromVersion,
                toVersion = toVersion,
                stepsCompleted = stepsCompleted,
                stepsFailed = stepsFailed,
                durationMs = System.currentTimeMillis() - startTime.get(),
                rolledBack = rolledBack,
                errors = errors
            )
            
        } finally {
            isMigrating.set(false)
        }
    }
    
    private fun buildMigrationSteps(fromVersion: Int, toVersion: Int): List<MigrationStep> {
        return migrationHandlers
            .filter { it.version > fromVersion && it.version <= toVersion }
            .map { handler ->
                MigrationStep(
                    id = "step_${handler.version}",
                    name = handler.name,
                    description = "Migrate to version ${handler.version}",
                    version = handler.version
                )
            }
    }
    
    private suspend fun createBackup(db: SupportSQLiteDatabase, version: Int): BackupInfo {
        val backupId = "backup_${version}_${System.currentTimeMillis()}"
        val backupFile = File(backupDir, "$backupId.db")
        
        db.beginTransaction()
        try {
            db.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        
        val dbFile = File(db.path ?: "")
        if (dbFile.exists()) {
            dbFile.copyTo(backupFile, overwrite = true)
        }
        
        val checksum = calculateFileChecksum(backupFile)
        val backup = BackupInfo(
            id = backupId,
            version = version,
            timestamp = System.currentTimeMillis(),
            size = backupFile.length(),
            checksum = checksum,
            path = backupFile.absolutePath
        )
        
        synchronized(backups) {
            backups.add(backup)
        }
        
        Log.i(TAG, "Created backup: $backupId")
        return backup
    }
    
    private fun performRollback(db: SupportSQLiteDatabase, targetVersion: Int): Boolean {
        val backup = synchronized(backups) {
            backups.lastOrNull { it.version == targetVersion }
        }
        
        if (backup == null) {
            Log.e(TAG, "No backup found for version $targetVersion")
            return false
        }
        
        val backupFile = File(backup.path)
        if (!backupFile.exists()) {
            Log.e(TAG, "Backup file not found: ${backup.path}")
            return false
        }
        
        val currentChecksum = calculateFileChecksum(backupFile)
        if (currentChecksum != backup.checksum) {
            Log.e(TAG, "Backup checksum mismatch, backup may be corrupted")
            return false
        }
        
        return try {
            val dbFile = File(db.path ?: "")
            backupFile.copyTo(dbFile, overwrite = true)
            
            Log.i(TAG, "Rolled back to version $targetVersion from backup ${backup.id}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rollback", e)
            false
        }
    }
    
    private suspend fun validateMigration(db: SupportSQLiteDatabase, version: Int): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        var tablesChecked = 0
        var recordsChecked = 0L
        
        try {
            val cursor = db.query("SELECT name FROM sqlite_master WHERE type='table'")
            val tables = mutableListOf<String>()
            cursor.use {
                while (it.moveToNext()) {
                    tables.add(it.getString(0))
                }
            }
            
            for (table in tables) {
                if (isCancelled.get()) break
                
                try {
                    val countCursor = db.query("SELECT COUNT(*) FROM $table")
                    countCursor.use {
                        if (it.moveToFirst()) {
                            val count = it.getLong(0)
                            recordsChecked += count
                        }
                    }
                    
                    val integrityCursor = db.query("PRAGMA integrity_check($table)")
                    integrityCursor.use {
                        if (it.moveToFirst()) {
                            val result = it.getString(0)
                            if (result != "ok") {
                                errors.add("Integrity check failed for table $table: $result")
                            }
                        }
                    }
                    
                    tablesChecked++
                    
                } catch (e: Exception) {
                    warnings.add("Could not validate table $table: ${e.message}")
                }
            }
            
            val foreignKeyCursor = db.query("PRAGMA foreign_key_check")
            foreignKeyCursor.use {
                if (it.count > 0) {
                    warnings.add("Foreign key violations detected")
                }
            }
            
        } catch (e: Exception) {
            errors.add("Validation error: ${e.message}")
        }
        
        return ValidationResult(
            isValid = errors.isEmpty(),
            tablesChecked = tablesChecked,
            recordsChecked = recordsChecked,
            errors = errors,
            warnings = warnings
        )
    }
    
    private fun calculateFileChecksum(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { fis ->
            val buffer = ByteArray(8192)
            var read: Int
            while (fis.read(buffer).also { read = it } > 0) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    
    private fun loadBackups() {
        if (!backupDir.exists()) return
        
        backupDir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".db")) {
                val parts = file.nameWithoutExtension.split("_")
                if (parts.size >= 3) {
                    val version = parts[1].toIntOrNull() ?: 0
                    val timestamp = parts[2].toLongOrNull() ?: 0L
                    
                    backups.add(BackupInfo(
                        id = file.nameWithoutExtension,
                        version = version,
                        timestamp = timestamp,
                        size = file.length(),
                        checksum = calculateFileChecksum(file),
                        path = file.absolutePath
                    ))
                }
            }
        }
        
        backups.sortByDescending { it.timestamp }
    }
    
    private fun cleanupOldBackups() {
        synchronized(backups) {
            while (backups.size > MigrationConfig.MAX_BACKUPS) {
                val oldest = backups.removeLast()
                File(oldest.path).delete()
                Log.d(TAG, "Deleted old backup: ${oldest.id}")
            }
        }
    }
    
    private fun updateProgress(status: MigrationStatus, message: String) {
        _migrationProgress.value = _migrationProgress.value.copy(
            currentStep = message,
            status = status,
            startTime = startTime.get(),
            elapsedTime = System.currentTimeMillis() - startTime.get()
        )
    }
    
    private fun updateStepProgress(step: MigrationStep, index: Int, total: Int, message: String = "") {
        val stepProgress = (index + step.progress) / total
        _migrationProgress.value = _migrationProgress.value.copy(
            currentStep = message.ifEmpty { step.name },
            currentStepIndex = index,
            totalSteps = total,
            overallProgress = stepProgress,
            itemsProcessed = itemsProcessed.get()
        )
    }
    
    fun cancelMigration() {
        isCancelled.set(true)
    }
    
    fun getBackups(): List<BackupInfo> = synchronized(backups) { backups.toList() }
    
    fun deleteBackup(backupId: String): Boolean {
        return synchronized(backups) {
            val backup = backups.find { it.id == backupId } ?: return false
            val file = File(backup.path)
            val deleted = file.delete()
            if (deleted) {
                backups.remove(backup)
            }
            deleted
        }
    }
    
    fun shutdown() {
        cancelMigration()
        scope.cancel()
        Log.i(TAG, "SafeMigrationEngine shutdown completed")
    }
}

class MigrationException(message: String) : Exception(message)
class MigrationCancelledException(message: String) : Exception(message)

abstract class BaseMigrationHandler(
    override val version: Int,
    override val name: String
) : MigrationHandler {
    
    override suspend fun migrate(db: SupportSQLiteDatabase, context: MigrationContext): Boolean {
        return try {
            performMigration(db, context)
        } catch (e: Exception) {
            Log.e("MigrationHandler", "Migration $name failed", e)
            false
        }
    }
    
    protected abstract suspend fun performMigration(db: SupportSQLiteDatabase, context: MigrationContext): Boolean
    
    override suspend fun validate(db: SupportSQLiteDatabase): ValidationResult {
        return try {
            performValidation(db)
        } catch (e: Exception) {
            ValidationResult(
                isValid = false,
                tablesChecked = 0,
                recordsChecked = 0,
                errors = listOf(e.message ?: "Validation error")
            )
        }
    }
    
    protected open suspend fun performValidation(db: SupportSQLiteDatabase): ValidationResult {
        return ValidationResult(isValid = true, tablesChecked = 0, recordsChecked = 0)
    }
    
    protected fun reportProgress(context: MigrationContext, progress: Float, message: String) {
        context.progressCallback(progress, message)
    }
    
    protected fun checkCancelled(context: MigrationContext): Boolean {
        return context.isCancelled()
    }
}
