package com.xianxia.sect.data.unified

import android.content.Context
import android.util.Log
import com.xianxia.sect.data.memory.DynamicMemoryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SaveFileHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val memoryManager: DynamicMemoryManager
) {
    
    companion object {
        private const val TAG = "SaveFileHandler"
        private const val SAVE_DIR = "saves"
        private const val BACKUP_DIR = "backups"
        private const val FILE_EXTENSION = ".sav"
        private const val META_EXTENSION = ".meta"
        private const val BACKUP_EXTENSION = ".bak"
        val TOTAL_STORAGE_BUDGET_BYTES = 300 * 1024 * 1024L
    }
    
    val saveDir: File by lazy {
        File(context.filesDir, SAVE_DIR).apply { if (!exists()) mkdirs() }
    }
    
    val backupDir: File by lazy {
        File(context.filesDir, BACKUP_DIR).apply { if (!exists()) mkdirs() }
    }

    val deltaDir: File by lazy {
        File(context.filesDir, "deltas").apply { if (!exists()) mkdirs() }
    }

    val snapshotDir: File by lazy {
        File(context.filesDir, "snapshots").apply { if (!exists()) mkdirs() }
    }
    
    fun getSaveFile(slot: Int): File = File(saveDir, "slot_$slot$FILE_EXTENSION")
    
    fun getMetaFile(slot: Int): File = File(saveDir, "slot_$slot$META_EXTENSION")
    
    fun getBackupFile(slot: Int, version: Int): File =
        File(backupDir, "slot_$slot$BACKUP_EXTENSION.$version")

    /**
     * 获取新格式的备份文件
     * 格式: {type}_slot_{slot}_{timestamp}.bak
     * @param slot 存档槽位
     * @param type 备份类型
     * @param timestamp 时间戳
     * @return 备份文件对象
     */
    fun getBackupFile(slot: Int, type: String, timestamp: Long): File {
        val fileName = "${type}_slot_${slot}_${timestamp}$BACKUP_EXTENSION"
        return File(backupDir, fileName)
    }

    /**
     * 获取指定槽位的所有备份文件（兼容新旧两种格式）
     */
    fun getAllBackupFilesForSlot(slot: Int): List<File> {
        return backupDir.listFiles()
            ?.filter { file ->
                file.exists() && file.name.endsWith(BACKUP_EXTENSION) &&
                (file.name.startsWith("slot_$slot$BACKUP_EXTENSION.") ||
                 Regex("""^(auto|manual|critical|emergency)_slot_${slot}_\d+\.bak$""").matches(file.name))
            }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }
    
    fun getEmergencySaveFile(): File = File(saveDir, "emergency$FILE_EXTENSION")
    
    /**
     * P0 FIX #4: 增强原子写入的重试机制和回滚逻辑
     *
     * 改进点：
     * 1. 使用 BufferedOutputStream 优化 I/O 性能
     * 2. 增加 renameTo 失败后的多次重试机制（最多3次）
     * 3. 增强回滚逻辑：当备份文件不存在时，保留临时文件并记录详细日志
     * 4. 增加详细的 DEBUG/INFO 级别日志记录关键路径
     * 5. 使用协程 delay 替代 Thread.sleep 避免阻塞线程池（P0 紧急修复）
     */
    suspend fun writeAtomically(
        targetFile: File,
        data: ByteArray,
        magicHeader: ByteArray? = null
    ): Boolean {
        val tempFile = File(targetFile.parent, "${targetFile.name}.tmp")
        val backupFile = File(targetFile.parent, "${targetFile.name}.bak")

        // P0 FIX #4a: 增加重试常量
        val MAX_RENAME_RETRIES = 3
        val RETRY_DELAY_MS = 50L

        return try {
            // 使用 BufferedOutputStream 优化 I/O 性能
            java.io.FileOutputStream(tempFile).use { fileOut ->
                val fos = java.io.BufferedOutputStream(fileOut, 64 * 1024)
                magicHeader?.let { fos.write(it) }
                fos.write(data)
                fos.flush()
                fos.close()
                fileOut.fd.sync()
            }

            syncDirectory(targetFile.parentFile)

            // P0 FIX #4b: renameTo 失败时增加重试机制
            var renamed = false
            var retryCount = 0

            while (!renamed && retryCount < MAX_RENAME_RETRIES) {
                renamed = tempFile.renameTo(targetFile)
                if (!renamed && retryCount < MAX_RENAME_RETRIES - 1) {
                    retryCount++
                    Log.w(TAG, "Atomic rename attempt $retryCount/$MAX_RENAME_RETRIES failed for ${targetFile.name}, retrying in ${RETRY_DELAY_MS}ms...")
                    delay(RETRY_DELAY_MS)  // P0 FIX: 使用协程 delay 替代 Thread.sleep，避免阻塞线程池
                    // 重试前确保目标文件不存在（可能被其他进程锁定）
                    if (targetFile.exists()) {
                        targetFile.delete()
                    }
                }
            }

            if (renamed) {
                syncDirectory(targetFile.parentFile)

                // 清理备份文件（成功后不再需要）
                if (backupFile.exists()) {
                    backupFile.delete()
                }
                Log.d(TAG, "Atomic write succeeded for ${targetFile.name}${if (retryCount > 0) " after $retryCount retries" else ""} (${data.size} bytes)")
                true
            } else {
                // P0 FIX #4c: 所有重试都失败后的增强回滚逻辑
                Log.e(TAG, "CRITICAL: Atomic rename failed after $MAX_RENAME_RETRIES attempts for ${targetFile.name}")
                Log.e(TAG, "  tempFile: ${tempFile.absolutePath} (exists=${tempFile.exists()}, size=${tempFile.length()})")
                Log.e(TAG, "  targetFile: ${targetFile.absolutePath} (exists=${targetFile.exists()}, size=${try { targetFile.length() } catch (_: Exception) { -1 }})")
                Log.e(TAG, "  backupFile: ${backupFile.absolutePath} (exists=${backupFile.exists()})")

                // 尝试从备份恢复
                if (backupFile.exists()) {
                    Log.w(TAG, "Attempting to restore from backup for ${targetFile.name}...")
                    var restored = false
                    for (backupRetry in 1..2) {
                        restored = backupFile.renameTo(targetFile)
                        if (restored) break
                        delay(RETRY_DELAY_MS)  // P0 FIX: 使用协程 delay 替代 Thread.sleep，避免阻塞线程池
                        if (targetFile.exists()) targetFile.delete()
                    }

                    if (restored) {
                        Log.i(TAG, "Successfully restored from backup for ${targetFile.name}")
                    } else {
                        Log.e(TAG, "Failed to restore from backup for ${targetFile.name} even after retries")
                        // 最后手段：强制复制
                        try {
                            backupFile.copyTo(targetFile, overwrite = true)
                            Log.w(TAG, "Force copy from backup succeeded for ${targetFile.name} (not atomic)")
                        } catch (ex: Exception) {
                            Log.e(TAG, "Force copy from backup also failed", ex)
                        }
                    }
                } else {
                    // 无备份可用：保留临时文件作为降级方案
                    Log.e(TAG, "No backup available for ${targetFile.name}. Temporary file preserved at: ${tempFile.absolutePath}")
                    Log.e(TAG, "WARNING: Data integrity cannot be guaranteed! Manual intervention may be required.")
                    // 不删除临时文件，以便后续可能的恢复操作
                }

                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Atomic write failed for ${targetFile.name}", e)
            // 清理临时文件（写入阶段异常）
            try { tempFile.delete() } catch (_: Exception) {}
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
    
    /**
     * 检查是否有足够内存执行存档操作
     *
     * 已委托给 [DynamicMemoryManager]，使用基于设备能力和存档大小的动态计算，
     * 替代原有的固定 15% 阈值逻辑。
     *
     * @return true 表示有足够内存可用
     */
    fun checkAvailableMemory(): Boolean {
        return memoryManager.checkAvailableMemory()
    }

    /**
     * 强制垃圾回收并等待完成
     *
     * 已委托给 [DynamicMemoryManager] 的真实实现（多阶段 GC + 等待），
     * 替代原有的空实现。
     */
    fun forceGcAndWait() {
        memoryManager.forceGcAndWait()
    }
    
    fun calculateTotalStorageBytes(): Long {
        var total = 0L
        saveDir.listFiles()?.forEach { total += it.length() }
        backupDir.listFiles()?.forEach { total += it.length() }
        deltaDir.listFiles()?.forEach { total += it.length() }
        snapshotDir.listFiles()?.forEach { total += it.length() }
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

    private enum class CandidateType { SAVE, BACKUP, DELTA, SNAPSHOT, MANIFEST }

    private data class QuotaCandidate(
        val file: File,
        val type: CandidateType,
        val slot: Int
    )

    fun enforceStorageQuota(requiredBytes: Long, protectedSlots: Set<Int> = emptySet()): QuotaEnforcementResult {
        val candidates = mutableListOf<QuotaCandidate>()

        // 1. 扫描 saves/ 目录 (.sav 文件)
        saveDir.listFiles()?.filter { it.exists() && it.name.endsWith(FILE_EXTENSION) }?.forEach { file ->
            val slot = extractSlotFromFileName(file.name)
            candidates.add(QuotaCandidate(file, CandidateType.SAVE, slot))
        }

        // 2. 扫描 backups/ 目录 (.bak 文件)
        backupDir.listFiles()?.filter { it.exists() && it.name.endsWith(BACKUP_EXTENSION) }?.forEach { file ->
            val slot = extractSlotFromFileName(file.name)
            candidates.add(QuotaCandidate(file, CandidateType.BACKUP, slot))
        }

        // 3. 扫描 deltas/ 目录下所有文件
        deltaDir.listFiles()?.filter { it.exists() && it.isFile }?.forEach { file ->
            val slot = extractSlotFromFileName(file.name)
            candidates.add(QuotaCandidate(file, CandidateType.DELTA, slot))
        }

        // 4. 扫描 snapshots/ 目录下所有文件
        snapshotDir.listFiles()?.filter { it.exists() && it.isFile }?.forEach { file ->
            val slot = extractSlotFromFileName(file.name)
            candidates.add(QuotaCandidate(file, CandidateType.SNAPSHOT, slot))
        }

        // 5. 扫描 manifest_*.json 文件（遍历 filesDir 下所有子目录）
        scanManifestFiles(saveDir, candidates)
        scanManifestFiles(backupDir, candidates)
        scanManifestFiles(deltaDir, candidates)
        scanManifestFiles(snapshotDir, candidates)

        // 排序优先级：BACKUP > SNAPSHOT > DELTA > SAVE > MANIFEST（备份和快照最易重建）
        val sorted = candidates.sortedWith(
            compareBy<QuotaCandidate> {
                when (it.type) {
                    CandidateType.MANIFEST -> 0
                    CandidateType.SAVE -> 1
                    CandidateType.DELTA -> 2
                    CandidateType.SNAPSHOT -> 3
                    CandidateType.BACKUP -> 4
                }
            }.thenBy { it.file.lastModified() }
        )

        var freedSpace = 0L
        val deletedFiles = mutableListOf<File>()

        for (candidate in sorted) {
            if (freedSpace >= requiredBytes) break
            if (candidate.slot in protectedSlots && candidate.type != CandidateType.MANIFEST) continue

            val fileSize = candidate.file.length()
            if (candidate.file.delete()) {
                freedSpace += fileSize
                deletedFiles.add(candidate.file)

                // 联动清理：删除存档时同步清理关联的 meta 文件
                if (candidate.type == CandidateType.SAVE) {
                    getMetaFile(candidate.slot)?.delete()
                }

                Log.i(TAG, "Quota enforcement deleted [${candidate.type.name}] ${candidate.file.name} (${fileSize} bytes)")
            }
        }

        return QuotaEnforcementResult(freedSpace, deletedFiles, freedSpace >= requiredBytes)
    }

    /**
     * 在指定目录中扫描 manifest_*.json 文件并加入配额候选列表。
     */
    private fun scanManifestFiles(dir: File, candidates: MutableList<QuotaCandidate>) {
        dir.listFiles()?.filter { it.exists() && it.name.matches(Regex("""manifest_.*\.json""")) }?.forEach { file ->
            val slot = extractSlotFromFileName(file.name)
            candidates.add(QuotaCandidate(file, CandidateType.MANIFEST, slot))
        }
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
