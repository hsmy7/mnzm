package com.xianxia.sect.data.unified

import android.util.Log
import com.xianxia.sect.data.concurrent.SlotLockManager
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupManager @Inject constructor(
    private val fileHandler: SaveFileHandler,
    private val lockManager: SlotLockManager
) {
    
    companion object {
        private const val TAG = "BackupManager"
        private const val MAX_BACKUP_VERSIONS = 5
    }
    
    fun rotateBackupVersions(slot: Int) {
        for (version in MAX_BACKUP_VERSIONS downTo 2) {
            val current = fileHandler.getBackupFile(slot, version - 1)
            val next = fileHandler.getBackupFile(slot, version)
            if (current.exists()) {
                if (next.exists()) next.delete()
                current.renameTo(next)
            }
        }
    }
    
    suspend fun createBackup(slot: Int): SaveResult<String> {
        if (!lockManager.isValidSlot(slot)) {
            return SaveResult.failure(SaveError.INVALID_SLOT, "Invalid slot: $slot")
        }
        
        return lockManager.withWriteLockSuspend(slot) {
            try {
                val saveFile = fileHandler.getSaveFile(slot)
                if (!saveFile.exists()) {
                    return@withWriteLockSuspend SaveResult.failure(
                        SaveError.SLOT_EMPTY, 
                        "No save data for slot $slot"
                    )
                }
                
                rotateBackupVersions(slot)
                
                val backupFile = fileHandler.getBackupFile(slot, 1)
                val tempBackup = File(backupFile.parent, "${backupFile.name}.tmp")
                
                saveFile.copyTo(tempBackup, overwrite = true)
                
                if (!tempBackup.renameTo(backupFile)) {
                    tempBackup.delete()
                    return@withWriteLockSuspend SaveResult.failure(
                        SaveError.BACKUP_FAILED, 
                        "Failed to create backup"
                    )
                }
                
                val backupId = "${slot}_${System.currentTimeMillis()}"
                Log.i(TAG, "Created backup for slot $slot: $backupId")
                
                SaveResult.success(backupId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create backup for slot $slot", e)
                SaveResult.failure(SaveError.BACKUP_FAILED, e.message ?: "Unknown error", e)
            }
        }
    }
    
    suspend fun restoreFromBackup(
        slot: Int,
        backupId: String,
        loadFromFile: suspend (Int) -> SaveResult<com.xianxia.sect.data.model.SaveData>
    ): SaveResult<com.xianxia.sect.data.model.SaveData> {
        if (!lockManager.isValidSlot(slot)) {
            return SaveResult.failure(SaveError.INVALID_SLOT, "Invalid slot: $slot")
        }
        
        return lockManager.withWriteLockSuspend(slot) {
            try {
                val version = extractBackupVersion(backupId) ?: 1
                val backupFile = fileHandler.getBackupFile(slot, version)
                
                if (!backupFile.exists()) {
                    return@withWriteLockSuspend SaveResult.failure(
                        SaveError.SLOT_EMPTY, 
                        "Backup not found: $backupId"
                    )
                }
                
                val saveFile = fileHandler.getSaveFile(slot)
                val tempFile = File(saveFile.parent, "${saveFile.name}.restore_tmp")
                
                backupFile.copyTo(tempFile, overwrite = true)
                
                if (saveFile.exists()) {
                    createBackup(slot)
                    saveFile.delete()
                }
                
                if (!tempFile.renameTo(saveFile)) {
                    tempFile.delete()
                    return@withWriteLockSuspend SaveResult.failure(
                        SaveError.RESTORE_FAILED, 
                        "Failed to restore backup"
                    )
                }
                
                loadFromFile(slot)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore backup for slot $slot", e)
                SaveResult.failure(SaveError.RESTORE_FAILED, e.message ?: "Unknown error", e)
            }
        }
    }
    
    fun getBackupVersions(slot: Int): List<BackupInfo> {
        if (!lockManager.isValidSlot(slot)) return emptyList()
        
        return (1..MAX_BACKUP_VERSIONS).mapNotNull { version ->
            val backupFile = fileHandler.getBackupFile(slot, version)
            if (backupFile.exists()) {
                BackupInfo(
                    id = "${slot}_${version}_${backupFile.lastModified()}",
                    slot = slot,
                    timestamp = backupFile.lastModified(),
                    size = backupFile.length(),
                    checksum = computeFileChecksum(backupFile)
                )
            } else null
        }.sortedByDescending { it.timestamp }
    }
    
    fun deleteBackupVersions(slot: Int) {
        (1..MAX_BACKUP_VERSIONS).forEach { version ->
            fileHandler.getBackupFile(slot, version).delete()
        }
    }
    
    fun extractBackupVersion(backupId: String): Int? {
        val parts = backupId.split("_")
        return parts.getOrNull(1)?.toIntOrNull()
    }
    
    private fun computeFileChecksum(file: File): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            java.io.FileInputStream(file).use { fis ->
                val buffer = ByteArray(8192)
                var read: Int
                while (fis.read(buffer).also { read = it } > 0) {
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().take(8).joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }
}
