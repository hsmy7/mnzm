package com.xianxia.sect.data.recovery

import android.content.Context
import android.util.Log
import com.xianxia.sect.data.local.GameDatabase
import com.xianxia.sect.data.unified.SaveError
import com.xianxia.sect.data.unified.SaveResult
import com.xianxia.sect.data.wal.EnhancedTransactionalWAL
import com.xianxia.sect.data.wal.RecoveryResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class WALRecoveryReport(
    val success: Boolean,
    val recoveredSlots: Set<Int>,
    val failedSlots: Set<Int>,
    val pendingTransactions: Int,
    val errors: List<String>,
    val recoveryTimeMs: Long
)

data class StartupRecoveryResult(
    val walRecovery: WALRecoveryReport,
    val databaseIntegrity: Map<Int, Boolean>,
    val cacheIntegrity: Boolean,
    val overallSuccess: Boolean
)

data class RecoveryStats(
    val activeTransactions: Int,
    val walFileSize: Long,
    val lastCheckpointTime: Long
)

@Singleton
class WALRecoveryManager @Inject constructor(
    private val context: Context,
    private val wal: EnhancedTransactionalWAL,
    private val database: GameDatabase
) {
    companion object {
        private const val TAG = "WALRecoveryManager"
        private const val RECOVERY_MARKER_FILE = ".recovery_marker"
    }

    suspend fun performStartupRecovery(): SaveResult<StartupRecoveryResult> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        
        try {
            Log.i(TAG, "Starting startup recovery process")
            
            val markerFile = File(context.filesDir, RECOVERY_MARKER_FILE)
            val wasUncleanShutdown = markerFile.exists()
            
            if (wasUncleanShutdown) {
                Log.w(TAG, "Detected unclean shutdown from previous session")
            }
            
            markerFile.createNewFile()
            
            val walRecovery = performWALRecovery()
            
            val databaseIntegrity = mutableMapOf<Int, Boolean>()
            for (slot in 1..5) {
                if (GameDatabase.exists(context, slot)) {
                    databaseIntegrity[slot] = verifyDatabaseIntegrity(slot)
                }
            }
            
            val cacheIntegrity = verifyCacheIntegrity()
            
            if (walRecovery.success) {
                markerFile.delete()
            }
            
            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "Startup recovery completed in ${elapsed}ms")
            
            SaveResult.success(StartupRecoveryResult(
                walRecovery = walRecovery,
                databaseIntegrity = databaseIntegrity,
                cacheIntegrity = cacheIntegrity,
                overallSuccess = walRecovery.success && 
                    databaseIntegrity.values.all { it } && 
                    cacheIntegrity
            ))
            
        } catch (e: Exception) {
            Log.e(TAG, "Startup recovery failed", e)
            SaveResult.failure(SaveError.WAL_ERROR, "Recovery failed: ${e.message}", e)
        }
    }

    private suspend fun performWALRecovery(): WALRecoveryReport {
        val startTime = System.currentTimeMillis()
        
        return try {
            val recovery = wal.recover()
            
            val recoveredSlots = mutableSetOf<Int>()
            val failedSlots = mutableSetOf<Int>()
            val errors = mutableListOf<String>()
            
            for (slot in recovery.recoveredSlots) {
                val restoreResult = wal.restoreFromSnapshot(slot) { data ->
                    try {
                        val dbFile = GameDatabase.getDatabaseFile(context, slot)
                        dbFile.writeBytes(data)
                        Log.i(TAG, "Restored slot $slot from WAL snapshot")
                        true
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to restore slot $slot from snapshot", e)
                        false
                    }
                }
                
                if (restoreResult.isSuccess) {
                    recoveredSlots.add(slot)
                } else {
                    failedSlots.add(slot)
                    errors.add("Failed to restore slot $slot")
                }
            }
            
            failedSlots.addAll(recovery.failedSlots)
            errors.addAll(recovery.errors)
            
            val elapsed = System.currentTimeMillis() - startTime
            
            WALRecoveryReport(
                success = failedSlots.isEmpty() && errors.isEmpty(),
                recoveredSlots = recoveredSlots,
                failedSlots = failedSlots,
                pendingTransactions = recovery.recoveredSlots.size,
                errors = errors,
                recoveryTimeMs = elapsed
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "WAL recovery failed", e)
            WALRecoveryReport(
                success = false,
                recoveredSlots = emptySet(),
                failedSlots = emptySet(),
                pendingTransactions = 0,
                errors = listOf(e.message ?: "Unknown error"),
                recoveryTimeMs = System.currentTimeMillis() - startTime
            )
        }
    }

    private fun verifyDatabaseIntegrity(slot: Int): Boolean {
        return try {
            val dbFile = GameDatabase.getDatabaseFile(context, slot)
            
            if (!dbFile.exists()) {
                return true
            }
            
            if (dbFile.length() == 0L) {
                Log.w(TAG, "Database file for slot $slot is empty")
                return false
            }
            
            val header = dbFile.inputStream().use { it.readNBytes(16) }
            if (header.size < 16) {
                Log.w(TAG, "Database file for slot $slot has invalid header")
                return false
            }
            
            val sqliteHeader = String(header, 0, 15, Charsets.US_ASCII)
            if (!sqliteHeader.startsWith("SQLite format 3")) {
                Log.w(TAG, "Database file for slot $slot is not a valid SQLite database")
                return false
            }
            
            Log.d(TAG, "Database integrity verified for slot $slot")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Database integrity check failed for slot $slot", e)
            false
        }
    }

    private fun verifyCacheIntegrity(): Boolean {
        return try {
            val cacheDir = File(context.cacheDir, "unified_cache")
            if (!cacheDir.exists()) {
                return true
            }
            
            val indexFile = File(cacheDir, "cache_index")
            if (indexFile.exists()) {
                val lines = indexFile.readLines()
                for (line in lines) {
                    val parts = line.split("|")
                    if (parts.size >= 2) {
                        val cacheFile = File(cacheDir, parts[1])
                        if (!cacheFile.exists()) {
                            Log.w(TAG, "Cache file missing: ${parts[1]}")
                        }
                    }
                }
            }
            
            Log.d(TAG, "Cache integrity verified")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Cache integrity check failed", e)
            false
        }
    }

    suspend fun recoverSlot(slot: Int): SaveResult<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Attempting to recover slot $slot")
            
            val restoreResult = wal.restoreFromSnapshot(slot) { data ->
                try {
                    val dbFile = GameDatabase.getDatabaseFile(context, slot)
                    dbFile.writeBytes(data)
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to write recovered data for slot $slot", e)
                    false
                }
            }
            
            if (restoreResult.isSuccess) {
                Log.i(TAG, "Successfully recovered slot $slot")
                SaveResult.success(Unit)
            } else {
                Log.e(TAG, "Failed to recover slot $slot")
                SaveResult.failure(SaveError.RESTORE_FAILED, "Failed to recover slot $slot")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error recovering slot $slot", e)
            SaveResult.failure(SaveError.RESTORE_FAILED, "Recovery error: ${e.message}", e)
        }
    }

    fun hasPendingRecovery(): Boolean {
        return wal.hasActiveTransactions()
    }

    fun getPendingTransactionCount(): Int {
        return wal.getActiveTransactionCount()
    }

    fun getRecoveryStats(): RecoveryStats {
        val walStats = wal.getStats()
        return RecoveryStats(
            activeTransactions = walStats.activeTransactions,
            walFileSize = walStats.walFileSize,
            lastCheckpointTime = walStats.lastCheckpointTime
        )
    }

    fun markCleanShutdown() {
        val markerFile = File(context.filesDir, RECOVERY_MARKER_FILE)
        if (markerFile.exists()) {
            markerFile.delete()
        }
    }
}
