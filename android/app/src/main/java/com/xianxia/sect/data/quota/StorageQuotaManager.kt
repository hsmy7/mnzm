package com.xianxia.sect.data.quota

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.xianxia.sect.data.config.SaveLimitsConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

sealed class QuotaResult {
    object OK : QuotaResult()
    data class Exceeded(val reason: String) : QuotaResult()
    data class DiskFull(val availableBytes: Long, val requiredBytes: Long) : QuotaResult()
    data class Evicted(val evictedFiles: List<String>, val freedBytes: Long) : QuotaResult()
}

data class StorageQuotaStats(
    val totalUsedBytes: Long,
    val totalLimitBytes: Long,
    val effectiveLimitBytes: Long = totalLimitBytes,
    val freeDiskBytes: Long,
    val saveFileCount: Int,
    val backupFileCount: Int,
    val walSnapshotCount: Int,
    val shardFileCount: Int = 0,
    val archiveFileCount: Int = 0,
    val usagePercentage: Float,
    val gamePhase: String = "unknown",
    val dynamicMultiplier: Float = 1.0f
)

/**
 * 存储使用明细，用于统计和诊断。
 */
data class StorageUsageBreakdown(
    val savesDirBytes: Long,
    val walDirBytes: Long,
    val backupDirBytes: Long,
    val databaseBytes: Long,
    val shardFilesBytes: Long,
    val archiveDirBytes: Long,
    val otherBytes: Long
)

object StorageQuotaConfig {
    /** 总存储上限：1 GB（原 500 MB） */
    const val MAX_TOTAL_STORAGE_MB = 1024L

    /** 单个存档上限：100 MB（原 50 MB） */
    const val MAX_SINGLE_SAVE_MB = 100L

    /** 最小预留空闲空间：100 MB */
    const val MIN_FREE_SPACE_MB = 100L

    /** 警告阈值：80% */
    const val WARNING_THRESHOLD_PERCENTAGE = 80f

    /** 严重阈值：95% */
    const val CRITICAL_THRESHOLD_PERCENTAGE = 95f

    // ==================== 动态配额 - 游戏阶段 ====================

    /** 短期游戏（< 6 游戏月）：不额外增加 */
    const val PHASE_SHORT_MONTHS = 6

    /** 中期游戏（6-24 游戏月）：配额倍率 1.2x */
    const val PHASE_MEDIUM_MULTIPLIER = 1.2f

    /** 长期游戏（> 24 游戏月）：配额倍率 1.5x */
    const val PHASE_LONG_MULTIPLIER = 1.5f

    const val PHASE_LONG_MONTHS = 24

    // ==================== 自动清理策略 ====================

    /** 重要数据保护时间：30 天内数据不被自动清理 */
    const val IMPORTANT_DATA_PROTECTION_MS = 30L * 24 * 60 * 60 * 1000

    /** 备份文件默认保留天数 */
    const val BACKUP_RETENTION_DAYS = 90

    /** WAL 快照默认保留小时数 */
    const val WAL_SNAPSHOT_RETENTION_HOURS = 24
}

@Singleton
class StorageQuotaManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val saveLimitsConfig: SaveLimitsConfig
) {
    companion object {
        private const val TAG = "StorageQuotaManager"
    }

    /** 基础总存储上限（字节），来自 SaveLimitsConfig */
    private val baseMaxTotalBytes: Long get() = saveLimitsConfig.totalStorageQuotaBytes

    /** 单个存档大小上限（字节），来自 SaveLimitsConfig */
    private val maxSingleSaveBytes: Long get() = saveLimitsConfig.maxSaveSizeBytes

    private val minFreeSpaceBytes = StorageQuotaConfig.MIN_FREE_SPACE_MB * 1024 * 1024

    // ==================== 动态配额计算 ====================

    /**
     * 根据游戏时长获取当前有效的总存储上限（字节）。
     *
     * 优先使用 [SaveLimitsConfig] 的动态配额逻辑，
     * 若未启用动态配额则返回基础值。
     *
     * @param gameMonth 当前游戏月份
     * @return 当前生效的总存储上限
     */
    fun getEffectiveQuotaLimit(gameMonth: Int = 0): Long {
        return saveLimitsConfig.getEffectiveTotalStorageQuota(gameMonth)
    }

    /**
     * 获取当前游戏阶段描述。
     */
    fun getGamePhaseDescription(gameMonth: Int): String {
        return saveLimitsConfig.getGamePhaseDescription(gameMonth)
    }

    /**
     * 获取当前游戏阶段的动态倍率。
     */
    fun getDynamicMultiplier(gameMonth: Int): Float {
        if (!saveLimitsConfig.isDynamicQuotaEnabled) return 1.0f
        return when {
            gameMonth < StorageQuotaConfig.PHASE_SHORT_MONTHS -> 1.0f
            gameMonth < StorageQuotaConfig.PHASE_LONG_MONTHS -> StorageQuotaConfig.PHASE_MEDIUM_MULTIPLIER
            else -> StorageQuotaConfig.PHASE_LONG_MULTIPLIER
        }
    }

    fun checkBeforeWrite(additionalBytes: Long, gameMonth: Int = 0): QuotaResult {
        val totalUsed = calculateTotalUsage()
        val freeSpace = getFreeDiskSpace()
        val effectiveLimit = getEffectiveQuotaLimit(gameMonth)

        return when {
            additionalBytes > maxSingleSaveBytes -> {
                Log.w(TAG, "Single save size ${additionalBytes} bytes exceeds limit $maxSingleSaveBytes")
                QuotaResult.Exceeded("Single save too large: ${(additionalBytes / 1024 / 1024)}MB exceeds ${(maxSingleSaveBytes / 1024 / 1024)}MB limit")
            }

            freeSpace < minFreeSpaceBytes && additionalBytes > freeSpace -> {
                Log.e(TAG, "Insufficient disk space: free=${freeSpace / 1024 / 1024}MB, required=${additionalBytes / 1024 / 1024}MB")
                QuotaResult.DiskFull(freeSpace, additionalBytes)
            }

            totalUsed + additionalBytes > effectiveLimit -> {
                Log.w(TAG, "Total storage would exceed effective limit ${effectiveLimit / 1024 / 1024}MB (phase: ${getGamePhaseDescription(gameMonth)}), attempting auto-eviction")
                autoEvictOldest(additionalBytes, totalUsed, effectiveLimit)
            }

            else -> {
                val usagePct = ((totalUsed + additionalBytes).toFloat() / effectiveLimit.toFloat() * 100f)
                if (usagePct > StorageQuotaConfig.CRITICAL_THRESHOLD_PERCENTAGE) {
                    Log.w(TAG, "Storage usage critical: %.1f%% (effective limit: ${effectiveLimit / 1024 / 1024}MB)".format(usagePct))
                }
                QuotaResult.OK
            }
        }
    }

    fun calculateTotalUsage(): Long {
        val breakdown = getStorageBreakdown()
        return breakdown.savesDirBytes + breakdown.walDirBytes + breakdown.backupDirBytes +
               breakdown.databaseBytes + breakdown.shardFilesBytes + breakdown.archiveDirBytes +
               breakdown.otherBytes
    }

    /**
     * 获取存储使用明细，按目录/类型分类。
     */
    fun getStorageBreakdown(): StorageUsageBreakdown {
        var savesDirBytes = 0L
        var walDirBytes = 0L
        var backupDirBytes = 0L
        var databaseBytes = 0L
        var shardFilesBytes = 0L
        var archiveDirBytes = 0L

        // saves 目录（主存档）
        val savesDir = File(context.filesDir, "saves")
        if (savesDir.exists()) {
            savesDir.listFiles()?.forEach { file ->
                if (file.name.contains(".shard_")) {
                    shardFilesBytes += file.length()
                } else {
                    savesDirBytes += file.length()
                }
            }
        }

        // WAL 目录
        val walDir = File(context.filesDir, "wal_v4")
        if (walDir.exists()) {
            walDir.listFiles()?.forEach { walDirBytes += it.length() }
        }

        // 备份目录
        val backupDir = File(context.filesDir, "transaction_backups")
        if (backupDir.exists()) {
            backupDir.listFiles()?.forEach { backupDirBytes += it.length() }
        }

        // 数据库文件
        for (slot in 0..10) {
            val dbFile = context.getDatabasePath("xianxia_sect_db_slot_${slot}.db")
            if (dbFile.exists()) databaseBytes += dbFile.length()
            val walFile = File(dbFile.path + "-wal")
            if (walFile.exists()) databaseBytes += walFile.length()
            val shmFile = File(dbFile.path + "-shm")
            if (shmFile.exists()) databaseBytes += shmFile.length()
        }

        // 归档目录
        val archiveDir = File(context.filesDir, "archives")
        if (archiveDir.exists()) {
            archiveDir.walkTopDown().filter { it.isFile }.forEach { archiveDirBytes += it.length() }
        }

        return StorageUsageBreakdown(
            savesDirBytes = savesDirBytes,
            walDirBytes = walDirBytes,
            backupDirBytes = backupDirBytes,
            databaseBytes = databaseBytes,
            shardFilesBytes = shardFilesBytes,
            archiveDirBytes = archiveDirBytes,
            otherBytes = 0L  // 预留扩展
        )
    }

    fun getQuotaStats(gameMonth: Int = 0): StorageQuotaStats {
        val totalUsed = calculateTotalUsage()
        val freeSpace = getFreeDiskSpace()
        val effectiveLimit = getEffectiveQuotaLimit(gameMonth)
        val breakdown = getStorageBreakdown()

        var saveCount = 0
        var backupCount = 0
        var snapshotCount = 0
        var shardCount = 0
        var archiveCount = 0

        val savesDir = File(context.filesDir, "saves")
        if (savesDir.exists()) {
            savesDir.listFiles()?.forEach {
                when {
                    it.name.endsWith(".sav") -> saveCount++
                    it.name.endsWith(".bak") -> backupCount++
                    it.name.contains(".shard_") -> shardCount++
                }
            }
        }

        val snapshotDir = File(context.filesDir, "wal_v4/snapshots")
        if (snapshotDir.exists()) {
            snapshotDir.listFiles()?.filter { it.name.endsWith(".snap") }?.let { snapshotCount = it.size }
        }

        // 统计归档文件数量
        val archiveDir = File(context.filesDir, "archives")
        if (archiveDir.exists()) {
            archiveCount = archiveDir.walkTopDown().filter { it.isFile && it.extension == "arc" }.count()
        }

        return StorageQuotaStats(
            totalUsedBytes = totalUsed,
            totalLimitBytes = baseMaxTotalBytes,
            effectiveLimitBytes = effectiveLimit,
            freeDiskBytes = freeSpace,
            saveFileCount = saveCount,
            backupFileCount = backupCount,
            walSnapshotCount = snapshotCount,
            shardFileCount = shardCount,
            archiveFileCount = archiveCount,
            usagePercentage = (totalUsed.toFloat() / effectiveLimit.toFloat() * 100f).coerceIn(0f, 100f),
            gamePhase = getGamePhaseDescription(gameMonth),
            dynamicMultiplier = getDynamicMultiplier(gameMonth)
        )
    }

    fun isNearQuotaLimit(gameMonth: Int = 0): Boolean {
        val usage = calculateTotalUsage()
        val effectiveLimit = getEffectiveQuotaLimit(gameMonth)
        return usage.toFloat() / effectiveLimit.toFloat() > StorageQuotaConfig.WARNING_THRESHOLD_PERCENTAGE / 100f
    }

    /**
     * 清理过期备份文件。
     *
     * 改进策略：
     * - 默认保留 90 天内的备份
     * - 30 天内的重要备份受保护不被删除
     * - 清理时优先删除最旧的非关键备份
     *
     * @param maxAgeMs 最大保留时间，默认 90 天
     * @param protectImportantMs 重要数据保护时间，默认 30 天
     * @return 释放的字节数
     */
    fun cleanupOldBackups(
        maxAgeMs: Long = StorageQuotaConfig.BACKUP_RETENTION_DAYS.toLong() * 24 * 60 * 60 * 1000L,
        protectImportantMs: Long = StorageQuotaConfig.IMPORTANT_DATA_PROTECTION_MS
    ): Long {
        var freed = 0L
        val now = System.currentTimeMillis()
        var protectedSkipped = 0

        // Phase 1: 清理 transaction_backups 目录中的过期备份
        val backupDir = File(context.filesDir, "transaction_backups")
        if (backupDir.exists()) {
            val candidates = backupDir.listFiles()
                ?.filter { now - it.lastModified() > maxAgeMs }
                ?.sortedBy { it.lastModified() }
                ?: emptyList()

            for (file in candidates) {
                val age = now - file.lastModified()
                // 保护期内的文件跳过
                if (age < protectImportantMs) {
                    protectedSkipped++
                    continue
                }
                freed += file.length()
                if (file.delete()) {
                    Log.d(TAG, "Cleaned old backup: ${file.name} (${file.length() / 1024}KB)")
                }
            }
        }

        // Phase 2: 清理 saves 目录中的 .bak 文件
        val savesDir = File(context.filesDir, "saves")
        if (savesDir.exists()) {
            val candidates = savesDir.listFiles()
                ?.filter { it.name.endsWith(".bak") && now - it.lastModified() > maxAgeMs }
                ?.sortedBy { it.lastModified() }
                ?: emptyList()

            for (file in candidates) {
                val age = now - file.lastModified()
                if (age < protectImportantMs) {
                    protectedSkipped++
                    continue
                }
                freed += file.length()
                if (file.delete()) {
                    Log.d(TAG, "Cleaned old save backup: ${file.name} (${file.length() / 1024}KB)")
                }
            }
        }

        if (freed > 0 || protectedSkipped > 0) {
            Log.i(TAG, "Backup cleanup: freed ${freed / 1024}KB, protected $protectedSkipped important files")
        }
        return freed
    }

    /**
     * 清理过期的 WAL 快照。
     *
     * 改进策略：
     * - 默认保留 24 小时内的快照
     * - 始终保留最新的 3 个快照（防止恢复失败）
     *
     * @param maxAgeMs 最大保留时间，默认 24 小时
     * @return 释放的字节数
     */
    fun cleanupWALSnapshots(maxAgeMs: Long = StorageQuotaConfig.WAL_SNAPSHOT_RETENTION_HOURS.toLong() * 60 * 60 * 1000L): Long {
        var freed = 0L
        val now = System.currentTimeMillis()

        val snapshotDir = File(context.filesDir, "wal_v4/snapshots")
        if (!snapshotDir.exists()) return 0L

        val snapshots = snapshotDir.listFiles()
            ?.filter { it.name.endsWith(".snap") }
            ?.sortedBy { it.lastModified() }
            ?: return 0L

        // 始终保留最后 3 个最新快照
        val deletable = snapshots.dropLast(3).filter { now - it.lastModified() > maxAgeMs }

        for (file in deletable) {
            freed += file.length()
            if (file.delete()) {
                Log.d(TAG, "Cleaned old WAL snapshot: ${file.name} (${file.length() / 1024}KB)")
            }
        }

        return freed
    }

    /**
     * 执行一次全面的存储清理，按优先级依次处理各类数据。
     *
     * 清理优先级（从低到高）：
     * 1. 过期 WAL 快照
     * 2. 过期备份文件（非保护期内）
     * 3. 过期归档文件
     *
     * @return 总共释放的字节数
     */
    fun performFullCleanup(): Long {
        Log.i(TAG, "Starting full storage cleanup...")
        var totalFreed = 0L

        // Step 1: WAL 快照（最低优先级数据）
        totalFreed += cleanupWALSnapshots()

        // Step 2: 备份文件
        totalFreed += cleanupOldBackups()

        // Step 3: 归档文件（超过保留期限的）
        totalFreed += cleanupExpiredArchives()

        if (totalFreed > 0) {
            Log.i(TAG, "Full cleanup completed: freed ${totalFreed / 1024}KB total")
        } else {
            Log.d(TAG, "Full cleanup: nothing to clean")
        }

        return totalFreed
    }

    private fun autoEvictOldest(requiredBytes: Long, currentUsed: Long, effectiveLimit: Long): QuotaResult {
        val targetFreed = (requiredBytes + currentUsed - effectiveLimit).coerceAtLeast(requiredBytes / 2)
        var freed = 0L
        val evictedFiles = mutableListOf<String>()

        val candidates = mutableListOf<Triple<File, Long, Int>>()  // (file, size, priority)

        // Priority 1 (最低): WAL 快照（可重建）
        val snapshotDir = File(context.filesDir, "wal_v4/snapshots")
        if (snapshotDir.exists()) {
            snapshotDir.listFiles()
                ?.filter { it.name.endsWith(".snap") }
                ?.sortedBy { it.lastModified() }
                ?.dropLast(2)  // 保留最新2个
                ?.forEach { candidates.add(Triple(it, it.length(), 1)) }
        }

        // Priority 2: 备份文件
        val backupDir = File(context.filesDir, "transaction_backups")
        if (backupDir.exists()) {
            val now = System.currentTimeMillis()
            backupDir.listFiles()
                ?.filter { now - it.lastModified() > StorageQuotaConfig.IMPORTANT_DATA_PROTECTION_MS }
                ?.sortedBy { it.lastModified() }
                ?.forEach { candidates.add(Triple(it, it.length(), 2)) }
        }

        // Priority 3: saves 目录中的 .bak 文件
        val savesDir = File(context.filesDir, "saves")
        if (savesDir.exists()) {
            val now = System.currentTimeMillis()
            savesDir.listFiles()
                ?.filter {
                    it.name.endsWith(".bak") &&
                    now - it.lastModified() > StorageQuotaConfig.IMPORTANT_DATA_PROTECTION_MS
                }
                ?.sortedBy { it.lastModified() }
                ?.forEach { candidates.add(Triple(it, it.length(), 3)) }
        }

        // 按优先级排序（同优先级按时间排序）
        candidates.sortWith(compareBy({ it.third }, { it.first.lastModified() }))

        for ((file, size, _) in candidates) {
            if (freed >= targetFreed) break
            if (file.delete()) {
                freed += size
                evictedFiles.add(file.name)
                Log.d(TAG, "Evicted [priority]: ${file.name} (${size / 1024}KB)")
            }
        }

        return if (freed >= targetFreed || evictedFiles.isNotEmpty()) {
            Log.i(TAG, "Auto-eviction complete: freed ${freed / 1024}KB from ${evictedFiles.size} files")
            QuotaResult.Evicted(evictedFiles, freed)
        } else {
            Log.w(TAG, "Auto-eviction: nothing to evict, quota still exceeded")
            QuotaResult.Exceeded("Storage quota exceeded and no evictable files found")
        }
    }

    /**
     * 清理过期的归档文件。
     * 归档文件的保留期限由 DataArchiver 的 DEFAULT_RETENTION_MONTHS 决定，
     * 这里默认清理超过 12 个月的归档。
     *
     * @return 释放的字节数
     */
    private fun cleanupExpiredArchives(): Long {
        var freed = 0L
        val retentionMs = 365L * 24 * 60 * 60 * 1000L  // 12 个月
        val now = System.currentTimeMillis()

        val archiveDir = File(context.filesDir, "archives")
        if (!archiveDir.exists()) return 0L

        archiveDir.walkTopDown()
            .filter { it.isFile && it.extension == "arc" && now - it.lastModified() > retentionMs }
            .sortedBy { it.lastModified() }
            .forEach { file ->
                freed += file.length()
                if (file.delete()) {
                    Log.d(TAG, "Cleaned expired archive: ${file.name} (${file.length() / 1024}KB)")
                }
            }

        return freed
    }

    private fun getFreeDiskSpace(): Long {
        return try {
            val stat = StatFs(context.filesDir.path)
            val blockSize = stat.blockSizeLong
            val availableBlocks = stat.availableBlocksLong
            blockSize * availableBlocks
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get free disk space", e)
            Long.MAX_VALUE
        }
    }
}
