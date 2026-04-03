package com.xianxia.sect.data.facade

import android.content.Context
import android.util.Log
import com.xianxia.sect.data.concurrent.SlotLockManager
import com.xianxia.sect.data.unified.UnifiedSaveRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class StorageHealthReport(
    val slotReports: Map<Int, SlotHealthReport>,
    val autoSaveHealthy: Boolean,
    val emergencySaveHealthy: Boolean,
    val totalIssues: Int,
    val checkDurationMs: Long
)

data class SlotHealthReport(
    val slot: Int,
    val exists: Boolean,
    val hasIssues: Boolean,
    val issues: List<String>,
    val canRecover: Boolean,
    val fileSize: Long,
    val dataIntegrityScore: Double = 0.0,
    val lastVerified: Long = 0
)

@Singleton
class StorageHealthChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val saveRepository: UnifiedSaveRepository,
    private val lockManager: SlotLockManager
) {
    companion object {
        private const val TAG = "StorageHealthChecker"
        private const val MAX_SLOTS = 5
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    suspend fun performHealthCheck(): StorageHealthReport {
        val startTime = System.currentTimeMillis()
        val slotReports = mutableMapOf<Int, SlotHealthReport>()
        var totalIssues = 0
        
        for (slot in 0..MAX_SLOTS) {
            val report = checkSlotHealth(slot)
            slotReports[slot] = report
            if (report.hasIssues) totalIssues++
        }
        
        val elapsed = System.currentTimeMillis() - startTime
        
        return StorageHealthReport(
            slotReports = slotReports,
            totalIssues = totalIssues,
            checkDurationMs = elapsed,
            autoSaveHealthy = hasAutoSave(),
            emergencySaveHealthy = !hasEmergencySave()
        )
    }
    
    suspend fun checkSlotHealth(slot: Int): SlotHealthReport {
        val issues = mutableListOf<String>()
        var canRecover = false
        var fileSize = 0L
        
        val hasSave = saveRepository.hasSave(slot)
        
        if (!hasSave) {
            return SlotHealthReport(
                slot = slot,
                exists = false,
                hasIssues = false,
                issues = emptyList(),
                canRecover = false,
                fileSize = 0
            )
        }
        
        try {
            val saveFile = File(context.filesDir, "saves/slot_$slot.sav")
            if (saveFile.exists()) {
                fileSize = saveFile.length()
                
                if (fileSize < 100) {
                    issues.add("File size suspiciously small")
                }
            }
            
            val backups = saveRepository.getBackupVersions(slot)
            if (backups.isNotEmpty()) {
                canRecover = true
            } else {
                issues.add("No backup available")
            }
            
            val integrity = saveRepository.verifyIntegrity(slot)
            if (integrity is com.xianxia.sect.data.unified.IntegrityResult.Invalid) {
                issues.add("Integrity check failed: ${integrity.errors.joinToString()}")
            }
            
        } catch (e: Exception) {
            issues.add("Health check error: ${e.message}")
            Log.e(TAG, "Error checking slot $slot health", e)
        }
        
        return SlotHealthReport(
            slot = slot,
            exists = true,
            hasIssues = issues.isNotEmpty(),
            issues = issues,
            canRecover = canRecover,
            fileSize = fileSize,
            lastVerified = System.currentTimeMillis()
        )
    }
    
    suspend fun hasAutoSave(): Boolean = saveRepository.hasSave(0)
    
    fun hasEmergencySave(): Boolean = saveRepository.hasEmergencySave()
    
    suspend fun isSaveCorrupted(slot: Int): Boolean {
        val result = saveRepository.verifyIntegrity(slot)
        return result is com.xianxia.sect.data.unified.IntegrityResult.Invalid
    }
    
    fun shutdown() {
        scope.cancel()
    }
}
