@file:Suppress("DEPRECATION")

package com.xianxia.sect.data.unified

import android.util.Log
import com.xianxia.sect.data.backup.BackupImportance
import com.xianxia.sect.data.backup.BackupStrategy
import com.xianxia.sect.data.backup.BackupType
import com.xianxia.sect.data.backup.EnhancedBackupInfo
import com.xianxia.sect.data.concurrent.SlotLockManager
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupManager @Inject constructor(
    private val fileHandler: SaveFileHandler,
    private val lockManager: SlotLockManager,
    private val strategy: BackupStrategy = BackupStrategy()
) {

    companion object {
        private const val TAG = "BackupManager"

        // 各类备份的最大保留版本数
        const val MAX_AUTO_BACKUPS = 5
        const val MAX_MANUAL_BACKUPS = 10
        const val MAX_CRITICAL_BACKUPS = 20

        // 全局最大备份数（所有槽位合计）
        const val GLOBAL_MAX_BACKUPS = 50
    }

    /**
     * 创建备份
     *
     * @param slot 存档槽位
     * @param type 备份类型（AUTO/MANUAL/CRITICAL/EMERGENCY）
     * @param importance 重要性级别（可选，会根据类型自动推断）
     * @param description 备份描述（可选）
     * @return 备份结果，包含备份ID
     */
    suspend fun createBackup(
        slot: Int,
        type: BackupType = BackupType.AUTO,
        importance: BackupImportance? = null,
        description: String? = null
    ): SaveResult<String> {
        if (!lockManager.isValidSlot(slot)) {
            return SaveResult.failure(SaveError.INVALID_SLOT, "Invalid slot: $slot")
        }

        return lockManager.withWriteLockLight(slot) {
            try {
                val saveFile = fileHandler.getSaveFile(slot)
                if (!saveFile.exists()) {
                    return@withWriteLockLight SaveResult.failure(
                        SaveError.SLOT_EMPTY,
                        "No save data for slot $slot"
                    )
                }

                // 确定重要性级别
                val finalImportance = importance ?: strategy.suggestImportance(type)

                // 执行智能清理（在创建新备份前）
                performSmartCleanup(slot, type)

                // 生成备份文件名
                val timestamp = System.currentTimeMillis()
                val backupFileName = strategy.generateBackupFileName(slot, type, timestamp)
                val backupFile = File(fileHandler.backupDir, backupFileName)
                val tempBackup = File(fileHandler.backupDir, "${backupFileName}.tmp")

                // 原子性写入备份文件
                saveFile.copyTo(tempBackup, overwrite = true)

                if (!tempBackup.renameTo(backupFile)) {
                    tempBackup.delete()
                    return@withWriteLockLight SaveResult.failure(
                        SaveError.BACKUP_FAILED,
                        "Failed to create backup"
                    )
                }

                val backupId = "${type.name}_${slot}_${timestamp}"
                Log.i(TAG, "Created ${type} backup for slot $slot: $backupId (importance: $finalImportance)")

                SaveResult.success(backupId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create backup for slot $slot", e)
                SaveResult.failure(SaveError.BACKUP_FAILED, e.message ?: "Unknown error", e)
            }
        }
    }

    /**
     * 创建自动备份（便捷方法）
     */
    suspend fun createAutoBackup(slot: Int): SaveResult<String> =
        createBackup(slot, BackupType.AUTO)

    /**
     * 创建手动备份（便捷方法）
     */
    suspend fun createManualBackup(
        slot: Int,
        description: String? = null,
        importance: BackupImportance? = null
    ): SaveResult<String> =
        createBackup(slot, BackupType.MANUAL, importance, description)

    /**
     * 创建关键节点备份（便捷方法）
     * 用于境界突破等重要事件
     */
    suspend fun createCriticalBackup(
        slot: Int,
        eventDescription: String? = null
    ): SaveResult<String> =
        createBackup(slot, BackupType.CRITICAL, BackupImportance.PERMANENT, eventDescription)

    /**
     * 智能清理：根据策略删除过期备份
     */
    private fun performSmartCleanup(slot: Int, newBackupType: BackupType) {
        try {
            // 获取该槽位的所有备份
            val backups = getEnhancedBackupVersions(slot)

            if (backups.isEmpty()) return

            // 获取需要清理的备份列表
            val toClean = strategy.getBackupsToClean(backups)

            // 删除标记为清理的备份
            toClean.forEach { backup ->
                val fileName = strategy.generateBackupFileName(backup.slot, backup.type, backup.timestamp)
                val file = File(fileHandler.backupDir, fileName)
                if (file.exists() && file.delete()) {
                    Log.i(TAG, "Cleaned up old backup: ${backup.id} (${backup.type}, ${backup.importance})")
                }
            }

            // 检查全局备份数量限制
            enforceGlobalBackupLimit()
        } catch (e: Exception) {
            Log.w(TAG, "Smart cleanup failed for slot $slot", e)
        }
    }

    /**
     * 强制执行全局备份总数限制
     */
    private fun enforceGlobalBackupLimit() {
        try {
            val allBackups = getAllBackups()
            if (allBackups.size <= GLOBAL_MAX_BACKUPS) return

            // 获取所有可清理的备份并排序
            val cleanableBackups = allBackups.filter { it.importance != BackupImportance.PERMANENT }
            val toDelete = strategy.getBackupsToClean(cleanableBackups)

            // 删除超出限制的备份
            val exceedCount = allBackups.size - GLOBAL_MAX_BACKUPS
            toDelete.take(exceedCount).forEach { backup ->
                val fileName = strategy.generateBackupFileName(backup.slot, backup.type, backup.timestamp)
                val file = File(fileHandler.backupDir, fileName)
                if (file.exists() && file.delete()) {
                    Log.i(TAG, "Global limit cleanup: deleted ${backup.id}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Global backup limit enforcement failed", e)
        }
    }

    /**
     * 从备份恢复
     *
     * @param slot 存档槽位
     * @param backupId 备份ID
     * @param loadFromFile 加载存档数据的函数
     * @return 恢复结果
     */
    suspend fun restoreFromBackup(
        slot: Int,
        backupId: String,
        loadFromFile: suspend (Int) -> SaveResult<com.xianxia.sect.data.model.SaveData>
    ): SaveResult<com.xianxia.sect.data.model.SaveData> {
        if (!lockManager.isValidSlot(slot)) {
            return SaveResult.failure(SaveError.INVALID_SLOT, "Invalid slot: $slot")
        }

        return lockManager.withWriteLockLight(slot) {
            try {
                val parsed = parseBackupId(backupId)
                    ?: return@withWriteLockLight SaveResult.failure(
                        SaveError.SLOT_EMPTY,
                        "Invalid backup ID format: $backupId"
                    )

                val backupFileName = strategy.generateBackupFileName(slot, parsed.type, parsed.timestamp)
                val backupFile = File(fileHandler.backupDir, backupFileName)

                if (!backupFile.exists()) {
                    return@withWriteLockLight SaveResult.failure(
                        SaveError.SLOT_EMPTY,
                        "Backup not found: $backupFileName"
                    )
                }

                createAutoBackup(slot)

                val saveFile = fileHandler.getSaveFile(slot)
                val tempFile = File(saveFile.parent, "${saveFile.name}.restore_tmp")

                backupFile.copyTo(tempFile, overwrite = true)

                if (saveFile.exists()) {
                    saveFile.delete()
                }

                if (!tempFile.renameTo(saveFile)) {
                    tempFile.delete()
                    return@withWriteLockLight SaveResult.failure(
                        SaveError.RESTORE_FAILED,
                        "Failed to restore backup"
                    )
                }

                Log.i(TAG, "Restored backup for slot $slot from: $backupId")
                loadFromFile(slot)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore backup for slot $slot", e)
                SaveResult.failure(SaveError.RESTORE_FAILED, e.message ?: "Unknown error", e)
            }
        }
    }

    /**
     * 使用最佳备份自动恢复
     *
     * @param slot 存档槽位
     * @param loadFromFile 加载存档数据函数
     * @return 恢复结果
     */
    suspend fun restoreFromBestBackup(
        slot: Int,
        loadFromFile: suspend (Int) -> SaveResult<com.xianxia.sect.data.model.SaveData>
    ): SaveResult<com.xianxia.sect.data.model.SaveData> {
        val backups = getEnhancedBackupVersions(slot)
        val selection = strategy.selectBestBackup(backups)

        val selected = selection.selected
            ?: return SaveResult.failure(SaveError.SLOT_EMPTY, "No backups available for slot $slot")

        Log.i(TAG, "Selected best backup for slot $slot: ${selected.id} (reason: ${selection.reason})")
        return restoreFromBackup(slot, selected.id, loadFromFile)
    }

    /**
     * 获取增强版备份信息列表（包含类型和重要性）
     */
    fun getEnhancedBackupVersions(slot: Int): List<EnhancedBackupInfo> {
        if (!lockManager.isValidSlot(slot)) return emptyList()

        return fileHandler.backupDir.listFiles()
            ?.filter { it.exists() && it.name.endsWith(".bak") }
            ?.mapNotNull { file ->
                parseBackupFile(file, slot)
            }
            ?.sortedByDescending { it.timestamp }
            ?: emptyList()
    }

    /**
     * 获取传统格式的备份信息列表（向后兼容）
     */
    fun getBackupVersions(slot: Int): List<BackupInfo> {
        return getEnhancedBackupVersions(slot).map { enhanced ->
            BackupInfo(
                id = enhanced.id,
                slot = enhanced.slot,
                timestamp = enhanced.timestamp,
                size = enhanced.size,
                checksum = enhanced.checksum
            )
        }
    }

    /**
     * 获取所有槽位的所有备份
     */
    fun getAllBackups(): List<EnhancedBackupInfo> {
        return fileHandler.backupDir.listFiles()
            ?.filter { it.exists() && it.name.endsWith(".bak") }
           ?.mapNotNull { file ->
                val parsed = strategy.parseBackupFileName(file.name)
                if (parsed != null) {
                    parseBackupFile(file, parsed.slot)
                } else null
            }
            ?: emptyList()
    }

    /**
     * 解析备份文件为 EnhancedBackupInfo
     */
    private fun parseBackupFile(file: File, slot: Int): EnhancedBackupInfo? {
        return try {
            val parsed = strategy.parseBackupFileName(file.name) ?: return null
            val importance = inferImportanceFromFile(parsed.type, file)

            EnhancedBackupInfo(
                id = "${parsed.type.name}_${parsed.slot}_${parsed.timestamp}",
                slot = parsed.slot,
                type = parsed.type,
                importance = importance,
                timestamp = parsed.timestamp,
                size = file.length(),
                checksum = computeFileChecksum(file)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse backup file: ${file.name}", e)
            null
        }
    }

    /**
     * 根据文件名和类型推断重要性级别
     */
    private fun inferImportanceFromFile(type: BackupType, file: File): BackupImportance {
        // CRITICAL 和 EMERGENCY 类型默认为 PERMANENT
        if (type == BackupType.CRITICAL || type == BackupType.EMERGENCY) {
            return BackupImportance.PERMANENT
        }

        // 手动备份默认为 IMPORTANT
        if (type == BackupType.MANUAL) {
            return BackupImportance.IMPORTANT
        }

        // 自动备份默认为 NORMAL
        return BackupImportance.NORMAL
    }

    /**
     * 删除指定备份
     */
    fun deleteBackup(backupId: String): Boolean {
        return try {
            val parsed = parseBackupId(backupId) ?: return false
            val fileName = strategy.generateBackupFileName(parsed.slot, parsed.type, parsed.timestamp)
            val file = File(fileHandler.backupDir, fileName)

            if (file.exists() && file.delete()) {
                Log.i(TAG, "Deleted backup: $backupId")
                true
            } else false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete backup: $backupId", e)
            false
        }
    }

    /**
     * 删除指定槽位的所有备份
     */
    fun deleteBackupVersions(slot: Int) {
        val backups = getEnhancedBackupVersions(slot)
        backups.forEach { backup ->
            deleteBackup(backup.id)
        }
    }

    /**
     * 解析备份ID
     * 格式: {TYPE}_{slot}_{timestamp}
     */
    private fun parseBackupId(backupId: String): ParsedBackupId? {
        val parts = backupId.split("_")
        if (parts.size != 3) return null

        val type = BackupType.fromString(parts[0]) ?: return null
        val slot = parts[1].toIntOrNull() ?: return null
        val timestamp = parts[2].toLongOrNull() ?: return null

        return ParsedBackupId(type, slot, timestamp)
    }

    /**
     * 计算文件校验和
     */
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

    /**
     * 解析后的备份ID组件
     */
    private data class ParsedBackupId(
        val type: BackupType,
        val slot: Int,
        val timestamp: Long
    )

    /**
     * 从备份ID提取版本号
     */
}
