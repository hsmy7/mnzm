package com.xianxia.sect.data.unified

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.ReferenceQueue
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SaveFileHandler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    companion object {
        private const val TAG = "SaveFileHandler"
        private const val SAVE_DIR = "saves"
        private const val BACKUP_DIR = "backups"
        private const val FILE_EXTENSION = ".sav"
        private const val META_EXTENSION = ".meta"
        private const val BACKUP_EXTENSION = ".bak"
        private const val MIN_MEMORY_RATIO = 0.15
        val TOTAL_STORAGE_BUDGET_BYTES = 300 * 1024 * 1024L
    }
    
    val saveDir: File by lazy {
        File(context.filesDir, SAVE_DIR).apply { if (!exists()) mkdirs() }
    }
    
    val backupDir: File by lazy {
        File(context.filesDir, BACKUP_DIR).apply { if (!exists()) mkdirs() }
    }
    
    fun getSaveFile(slot: Int): File = File(saveDir, "slot_$slot$FILE_EXTENSION")
    
    fun getMetaFile(slot: Int): File = File(saveDir, "slot_$slot$META_EXTENSION")
    
    fun getBackupFile(slot: Int, version: Int): File = 
        File(backupDir, "slot_$slot$BACKUP_EXTENSION.$version")
    
    fun getEmergencySaveFile(): File = File(saveDir, "emergency$FILE_EXTENSION")
    
    fun writeAtomically(
        targetFile: File,
        data: ByteArray,
        magicHeader: ByteArray? = null
    ): Boolean {
        val tempFile = File(targetFile.parent, "${targetFile.name}.tmp")
        val backupFile = File(targetFile.parent, "${targetFile.name}.bak")

        return try {
            FileOutputStream(tempFile).use { fos ->
                magicHeader?.let { fos.write(it) }
                fos.write(data)
                fos.fd.sync()
            }

            syncDirectory(targetFile.parentFile)

            if (tempFile.renameTo(targetFile)) {
                syncDirectory(targetFile.parentFile)

                if (backupFile.exists()) {
                    backupFile.delete()
                }
                true
            } else {
                Log.w(TAG, "Atomic rename failed for ${targetFile.name}, attempting rollback")

                tempFile.delete()

                if (backupFile.exists()) {
                    if (backupFile.renameTo(targetFile)) {
                        Log.i(TAG, "Restored from backup for ${targetFile.name}")
                    } else {
                        Log.e(TAG, "Failed to restore from backup for ${targetFile.name}")
                    }
                }
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Atomic write failed for ${targetFile.name}", e)
            tempFile.delete()
            false
        }
    }

    private fun syncDirectory(dir: File?) {
        if (dir == null || !dir.exists()) return

        try {
            val fileOutputStream = java.io.FileOutputStream(java.io.File(dir, ".sync_marker"))
            try {
                fileOutputStream.fd.sync()
            } finally {
                fileOutputStream.close()
                java.io.File(dir, ".sync_marker").delete()
            }
        } catch (e: Exception) {
            try {
                Runtime.getRuntime().exec(arrayOf("sync"))
            } catch (ex: Exception) {
                Log.w(TAG, "Failed to sync directory", ex)
            }
        }
    }
    
    fun readBytes(file: File): ByteArray? {
        return try {
            if (!file.exists()) null else file.readBytes()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read file ${file.name}", e)
            null
        }
    }
    
    fun deleteFiles(vararg files: File): Boolean {
        var success = true
        for (file in files) {
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "Failed to delete file ${file.name}")
                success = false
            }
        }
        return success
    }
    
    fun checkAvailableMemory(): Boolean {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val availableMemory = maxMemory - usedMemory
        val ratio = availableMemory.toDouble() / maxMemory.toDouble()
        return ratio >= MIN_MEMORY_RATIO
    }
    
    fun forceGcAndWait() {
        // Android VM 会自动管理内存，不需要强制 GC
        // 保留此方法以兼容现有调用
    }
    
    fun calculateTotalStorageBytes(): Long {
        var total = 0L
        saveDir.listFiles()?.forEach { total += it.length() }
        backupDir.listFiles()?.forEach { total += it.length() }
        return total
    }
    
    fun hasEnoughDiskSpace(file: File, requiredBytes: Long): Boolean {
        return file.parentFile?.let { parent ->
            parent.freeSpace >= requiredBytes * 2L
        } ?: false
    }

    fun checkTotalStorageBudget(additionalBytes: Long): Boolean {
        val currentUsage = calculateTotalStorageBytes()
        return (currentUsage + additionalBytes) <= TOTAL_STORAGE_BUDGET_BYTES
    }

    data class QuotaEnforcementResult(
        val freedBytes: Long,
        val deletedFiles: List<File>,
        val success: Boolean
    )

    private enum class CandidateType { SAVE, BACKUP }

    private data class QuotaCandidate(
        val file: File,
        val type: CandidateType,
        val slot: Int
    )

    fun enforceStorageQuota(requiredBytes: Long, protectedSlots: Set<Int> = emptySet()): QuotaEnforcementResult {
        val candidates = mutableListOf<QuotaCandidate>()

        saveDir.listFiles()?.filter { it.exists() && it.name.endsWith(FILE_EXTENSION) }?.forEach { file ->
            val slot = extractSlotFromFileName(file.name)
            candidates.add(QuotaCandidate(file, CandidateType.SAVE, slot))
        }

        backupDir.listFiles()?.filter { it.exists() && it.name.endsWith(BACKUP_EXTENSION) }?.forEach { file ->
            val slot = extractSlotFromFileName(file.name)
            candidates.add(QuotaCandidate(file, CandidateType.BACKUP, slot))
        }

        val sorted = candidates.sortedWith(
            compareBy<QuotaCandidate> { it.type != CandidateType.BACKUP }
                .thenBy { it.file.lastModified() }
        )

        var freedSpace = 0L
        val deletedFiles = mutableListOf<File>()

        for (candidate in sorted) {
            if (freedSpace >= requiredBytes) break
            if (candidate.slot in protectedSlots) continue

            val fileSize = candidate.file.length()
            if (candidate.file.delete()) {
                freedSpace += fileSize
                deletedFiles.add(candidate.file)

                if (candidate.type == CandidateType.SAVE) {
                    getMetaFile(candidate.slot)?.delete()
                }

                Log.i(TAG, "Quota enforcement deleted ${candidate.file.name} (${fileSize} bytes)")
            }
        }

        return QuotaEnforcementResult(freedSpace, deletedFiles, freedSpace >= requiredBytes)
    }

    private fun extractSlotFromFileName(fileName: String): Int {
        return Regex("""slot_(-?\d+)""").find(fileName)?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    fun getStorageUsagePercent(): Float {
        val currentUsage = calculateTotalStorageBytes()
        return if (TOTAL_STORAGE_BUDGET_BYTES > 0) {
            (currentUsage.toFloat() / TOTAL_STORAGE_BUDGET_BYTES.toFloat()) * 100f
        } else {
            0f
        }
    }
}
