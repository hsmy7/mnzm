package com.xianxia.sect.data.lifecycle

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

object LifecycleConfig {
    const val DEFAULT_BATTLE_LOG_RETENTION_DAYS = 30
    const val DEFAULT_EVENT_RETENTION_DAYS = 60
    const val DEFAULT_ARCHIVE_AFTER_DAYS = 90
    const val DEFAULT_DELETE_AFTER_DAYS = 180
    
    const val CLEANUP_INTERVAL_MS = 3600_000L
    const val ARCHIVE_INTERVAL_MS = 6 * 3600_000L
    
    const val ARCHIVE_DIR = "archives"
    const val TEMP_DIR = "temp"
    
    val RETENTION_POLICIES = mapOf(
        "battle_log" to RetentionPolicy(
            dataType = "battle_log",
            retentionDays = DEFAULT_BATTLE_LOG_RETENTION_DAYS,
            archiveAfterDays = 30,
            deleteAfterDays = 90
        ),
        "event" to RetentionPolicy(
            dataType = "event",
            retentionDays = DEFAULT_EVENT_RETENTION_DAYS,
            archiveAfterDays = 45,
            deleteAfterDays = 120
        ),
        "disciple_dead" to RetentionPolicy(
            dataType = "disciple_dead",
            retentionDays = 180,
            archiveAfterDays = 60,
            deleteAfterDays = 365
        )
    )
}

data class RetentionPolicy(
    val dataType: String,
    val retentionDays: Int,
    val archiveAfterDays: Int,
    val deleteAfterDays: Int,
    val compressOnArchive: Boolean = true,
    val keepMetadata: Boolean = true
)

data class LifecycleStats(
    val totalCleanups: Long = 0,
    val totalArchived: Long = 0,
    val totalDeleted: Long = 0,
    val spaceReclaimed: Long = 0,
    val lastCleanupTime: Long = 0,
    val lastArchiveTime: Long = 0
)

data class ArchiveInfo(
    val id: String,
    val dataType: String,
    val slot: Int,
    val createdAt: Long,
    val originalSize: Long,
    val compressedSize: Long,
    val recordCount: Int,
    val dateRange: Pair<Long, Long>
)

sealed class LifecycleEvent {
    data class CleanupStarted(val dataType: String) : LifecycleEvent()
    data class CleanupCompleted(val dataType: String, val count: Int, val bytesReclaimed: Long) : LifecycleEvent()
    data class ArchiveStarted(val dataType: String) : LifecycleEvent()
    data class ArchiveCompleted(val dataType: String, val archiveId: String) : LifecycleEvent()
    data class DataExpired(val dataType: String, val recordId: String) : LifecycleEvent()
    data class Error(val message: String) : LifecycleEvent()
}

class DataLifecycleManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "DataLifecycleManager"
    }

    private val policies = ConcurrentHashMap<String, RetentionPolicy>()
    private val archives = ConcurrentHashMap<String, ArchiveInfo>()
    
    private val _stats = MutableStateFlow(LifecycleStats())
    val stats: StateFlow<LifecycleStats> = _stats.asStateFlow()
    
    private val _events = MutableStateFlow<LifecycleEvent?>(null)
    val events: StateFlow<LifecycleEvent?> = _events.asStateFlow()
    
    private var cleanupJob: Job? = null
    private var archiveJob: Job? = null
    private var isShuttingDown = false

    init {
        initializePolicies()
        loadArchiveIndex()
        startBackgroundTasks()
    }

    private fun initializePolicies() {
        LifecycleConfig.RETENTION_POLICIES.forEach { (key, policy) ->
            policies[key] = policy
        }
    }

    private fun loadArchiveIndex() {
        val archiveDir = File(context.filesDir, LifecycleConfig.ARCHIVE_DIR)
        if (!archiveDir.exists()) {
            archiveDir.mkdirs()
            return
        }
        
        val indexFile = File(archiveDir, "index.json")
        if (indexFile.exists()) {
            try {
                indexFile.readLines().filter { it.isNotBlank() }.forEach { line ->
                    val parts = line.split(",")
                    if (parts.size >= 8) {
                        val archiveId = parts[0]
                        archives[archiveId] = ArchiveInfo(
                            id = archiveId,
                            dataType = parts[1],
                            slot = parts[2].toInt(),
                            createdAt = parts[3].toLong(),
                            originalSize = parts[4].toLong(),
                            compressedSize = parts[5].toLong(),
                            recordCount = parts[6].toInt(),
                            dateRange = Pair(parts[7].toLong(), parts[8].toLong())
                        )
                    }
                }
                Log.i(TAG, "Loaded ${archives.size} archive entries")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load archive index", e)
            }
        }
    }

    private fun saveArchiveIndex() {
        val archiveDir = File(context.filesDir, LifecycleConfig.ARCHIVE_DIR)
        if (!archiveDir.exists()) archiveDir.mkdirs()
        
        val indexFile = File(archiveDir, "index.json")
        val content = archives.values.joinToString("\n") { archive ->
            "${archive.id},${archive.dataType},${archive.slot},${archive.createdAt},${archive.originalSize},${archive.compressedSize},${archive.recordCount},${archive.dateRange.first},${archive.dateRange.second}"
        }
        indexFile.writeText(content)
    }

    private fun startBackgroundTasks() {
        cleanupJob = scope.launch {
            while (isActive && !isShuttingDown) {
                delay(LifecycleConfig.CLEANUP_INTERVAL_MS)
                performScheduledCleanup()
            }
        }
        
        archiveJob = scope.launch {
            while (isActive && !isShuttingDown) {
                delay(LifecycleConfig.ARCHIVE_INTERVAL_MS)
                performScheduledArchive()
            }
        }
    }

    suspend fun cleanupExpiredData(
        slot: Int,
        battleLogRetentionDays: Int = LifecycleConfig.DEFAULT_BATTLE_LOG_RETENTION_DAYS,
        eventRetentionDays: Int = LifecycleConfig.DEFAULT_EVENT_RETENTION_DAYS
    ): Int {
        var totalCleaned = 0
        
        _events.value = LifecycleEvent.CleanupStarted("all")
        
        totalCleaned += cleanupBattleLogs(slot, battleLogRetentionDays)
        totalCleaned += cleanupEvents(slot, eventRetentionDays)
        totalCleaned += cleanupTempFiles()
        
        updateStats { it.copy(
            totalCleanups = it.totalCleanups + 1,
            lastCleanupTime = System.currentTimeMillis()
        )}
        
        _events.value = LifecycleEvent.CleanupCompleted("all", totalCleaned, 0)
        
        return totalCleaned
    }

    private suspend fun cleanupBattleLogs(slot: Int, retentionDays: Int): Int {
        val cutoffTime = System.currentTimeMillis() - (retentionDays * 24 * 60 * 60 * 1000L)
        
        val battleLogDir = File(context.filesDir, "battle_logs/$slot")
        if (!battleLogDir.exists()) return 0
        
        var cleanedCount = 0
        var bytesReclaimed = 0L
        
        battleLogDir.listFiles()?.forEach { partitionDir ->
            if (partitionDir.isDirectory) {
                val partitionFiles = partitionDir.listFiles() ?: return@forEach
                partitionFiles.forEach { file ->
                    if (file.name.endsWith(".dat") || file.name.endsWith(".idx")) {
                        val fileTime = file.lastModified()
                        if (fileTime < cutoffTime) {
                            bytesReclaimed += file.length()
                            file.delete()
                            cleanedCount++
                        }
                    }
                }
                
                if (partitionDir.listFiles()?.isEmpty() == true) {
                    partitionDir.delete()
                }
            }
        }
        
        Log.i(TAG, "Cleaned up $cleanedCount battle log files, reclaimed $bytesReclaimed bytes")
        return cleanedCount
    }

    private suspend fun cleanupEvents(slot: Int, retentionDays: Int): Int {
        val cutoffTime = System.currentTimeMillis() - (retentionDays * 24 * 60 * 60 * 1000L)
        
        val eventDir = File(context.filesDir, "events/$slot")
        if (!eventDir.exists()) return 0
        
        var cleanedCount = 0
        var bytesReclaimed = 0L
        
        eventDir.listFiles()?.forEach { partitionDir ->
            if (partitionDir.isDirectory) {
                val partitionFiles = partitionDir.listFiles() ?: return@forEach
                partitionFiles.forEach { file ->
                    if (file.name.endsWith(".dat") || file.name.endsWith(".idx")) {
                        val fileTime = file.lastModified()
                        if (fileTime < cutoffTime) {
                            bytesReclaimed += file.length()
                            file.delete()
                            cleanedCount++
                        }
                    }
                }
                
                if (partitionDir.listFiles()?.isEmpty() == true) {
                    partitionDir.delete()
                }
            }
        }
        
        Log.i(TAG, "Cleaned up $cleanedCount event files, reclaimed $bytesReclaimed bytes")
        return cleanedCount
    }

    private suspend fun cleanupTempFiles(): Int {
        val tempDir = File(context.filesDir, LifecycleConfig.TEMP_DIR)
        if (!tempDir.exists()) return 0
        
        var cleanedCount = 0
        val cutoffTime = System.currentTimeMillis() - (24 * 60 * 60 * 1000L)
        
        tempDir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoffTime) {
                if (file.isDirectory) {
                    file.deleteRecursively()
                } else {
                    file.delete()
                }
                cleanedCount++
            }
        }
        
        return cleanedCount
    }

    suspend fun archiveOldData(
        slot: Int,
        archiveAfterDays: Int = LifecycleConfig.DEFAULT_ARCHIVE_AFTER_DAYS
    ): List<String> {
        val archivedIds = mutableListOf<String>()
        
        _events.value = LifecycleEvent.ArchiveStarted("all")
        
        val battleLogArchiveId = archiveBattleLogs(slot, archiveAfterDays)
        if (battleLogArchiveId != null) {
            archivedIds.add(battleLogArchiveId)
        }
        
        val eventArchiveId = archiveEvents(slot, archiveAfterDays)
        if (eventArchiveId != null) {
            archivedIds.add(eventArchiveId)
        }
        
        updateStats { it.copy(
            totalArchived = it.totalArchived + archivedIds.size,
            lastArchiveTime = System.currentTimeMillis()
        )}
        
        _events.value = LifecycleEvent.ArchiveCompleted("all", archivedIds.firstOrNull() ?: "")
        
        return archivedIds
    }

    private suspend fun archiveBattleLogs(slot: Int, archiveAfterDays: Int): String? {
        val cutoffTime = System.currentTimeMillis() - (archiveAfterDays * 24 * 60 * 60 * 1000L)
        val battleLogDir = File(context.filesDir, "battle_logs/$slot")
        
        if (!battleLogDir.exists()) return null
        
        val archiveId = generateArchiveId("battle_log", slot)
        val archiveFile = File(context.filesDir, "${LifecycleConfig.ARCHIVE_DIR}/$archiveId.zip")
        
        var originalSize = 0L
        var recordCount = 0
        var minTime = Long.MAX_VALUE
        var maxTime = Long.MIN_VALUE
        
        val filesToArchive = battleLogDir.listFiles()
            ?.filter { it.lastModified() < cutoffTime }
            ?: return null
        
        if (filesToArchive.isEmpty()) return null
        
        filesToArchive.forEach { file ->
            originalSize += file.length()
            if (file.name.contains("_")) {
                try {
                    val timestamp = file.name.split("_")[1].split(".")[0].toLongOrNull() ?: 0
                    minTime = minOf(minTime, timestamp)
                    maxTime = maxOf(maxTime, timestamp)
                } catch (e: Exception) {
                }
            }
            recordCount++
        }
        
        try {
            createArchive(filesToArchive, archiveFile)
            
            filesToArchive.forEach { it.delete() }
            
            val archiveInfo = ArchiveInfo(
                id = archiveId,
                dataType = "battle_log",
                slot = slot,
                createdAt = System.currentTimeMillis(),
                originalSize = originalSize,
                compressedSize = archiveFile.length(),
                recordCount = recordCount,
                dateRange = Pair(
                    if (minTime == Long.MAX_VALUE) 0 else minTime,
                    if (maxTime == Long.MIN_VALUE) 0 else maxTime
                )
            )
            
            archives[archiveId] = archiveInfo
            saveArchiveIndex()
            
            Log.i(TAG, "Archived battle logs: $archiveId, original=${originalSize}B, compressed=${archiveFile.length()}B")
            return archiveId
        } catch (e: Exception) {
            Log.e(TAG, "Failed to archive battle logs", e)
            return null
        }
    }

    private suspend fun archiveEvents(slot: Int, archiveAfterDays: Int): String? {
        val cutoffTime = System.currentTimeMillis() - (archiveAfterDays * 24 * 60 * 60 * 1000L)
        val eventDir = File(context.filesDir, "events/$slot")
        
        if (!eventDir.exists()) return null
        
        val archiveId = generateArchiveId("event", slot)
        val archiveFile = File(context.filesDir, "${LifecycleConfig.ARCHIVE_DIR}/$archiveId.zip")
        
        var originalSize = 0L
        var recordCount = 0
        var minTime = Long.MAX_VALUE
        var maxTime = Long.MIN_VALUE
        
        val filesToArchive = eventDir.listFiles()
            ?.filter { it.lastModified() < cutoffTime }
            ?: return null
        
        if (filesToArchive.isEmpty()) return null
        
        filesToArchive.forEach { file ->
            originalSize += file.length()
            recordCount++
        }
        
        try {
            createArchive(filesToArchive, archiveFile)
            
            filesToArchive.forEach { it.delete() }
            
            val archiveInfo = ArchiveInfo(
                id = archiveId,
                dataType = "event",
                slot = slot,
                createdAt = System.currentTimeMillis(),
                originalSize = originalSize,
                compressedSize = archiveFile.length(),
                recordCount = recordCount,
                dateRange = Pair(0, 0)
            )
            
            archives[archiveId] = archiveInfo
            saveArchiveIndex()
            
            Log.i(TAG, "Archived events: $archiveId")
            return archiveId
        } catch (e: Exception) {
            Log.e(TAG, "Failed to archive events", e)
            return null
        }
    }

    private fun createArchive(files: List<File>, archiveFile: File) {
        java.util.zip.ZipOutputStream(java.io.FileOutputStream(archiveFile)).use { zos ->
            files.forEach { file ->
                if (file.isFile) {
                    val entry = java.util.zip.ZipEntry(file.name)
                    zos.putNextEntry(entry)
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
    }

    private fun generateArchiveId(dataType: String, slot: Int): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "archive_${dataType}_${slot}_$timestamp"
    }

    suspend fun restoreFromArchive(archiveId: String, targetSlot: Int? = null): Boolean {
        val archiveInfo = archives[archiveId] ?: return false
        val archiveFile = File(context.filesDir, "${LifecycleConfig.ARCHIVE_DIR}/$archiveId.zip")
        
        if (!archiveFile.exists()) {
            Log.e(TAG, "Archive file not found: $archiveId")
            return false
        }
        
        val slot = targetSlot ?: archiveInfo.slot
        val targetDir = when (archiveInfo.dataType) {
            "battle_log" -> File(context.filesDir, "battle_logs/$slot")
            "event" -> File(context.filesDir, "events/$slot")
            else -> return false
        }
        
        return try {
            targetDir.mkdirs()
            
            java.util.zip.ZipInputStream(java.io.FileInputStream(archiveFile)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val file = File(targetDir, entry.name)
                    file.parentFile?.mkdirs()
                    file.outputStream().use { zis.copyTo(it) }
                    entry = zis.nextEntry
                }
            }
            
            Log.i(TAG, "Restored archive $archiveId to slot $slot")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore archive $archiveId", e)
            false
        }
    }

    fun listArchives(dataType: String? = null, slot: Int? = null): List<ArchiveInfo> {
        return archives.values
            .filter { dataType == null || it.dataType == dataType }
            .filter { slot == null || it.slot == slot }
            .sortedByDescending { it.createdAt }
    }

    fun getArchiveInfo(archiveId: String): ArchiveInfo? {
        return archives[archiveId]
    }

    fun deleteArchive(archiveId: String): Boolean {
        val archiveInfo = archives[archiveId] ?: return false
        val archiveFile = File(context.filesDir, "${LifecycleConfig.ARCHIVE_DIR}/$archiveId.zip")
        
        if (archiveFile.exists()) {
            archiveFile.delete()
        }
        
        archives.remove(archiveId)
        saveArchiveIndex()
        
        return true
    }

    fun setRetentionPolicy(dataType: String, policy: RetentionPolicy) {
        policies[dataType] = policy
    }

    fun getRetentionPolicy(dataType: String): RetentionPolicy? {
        return policies[dataType]
    }

    private suspend fun performScheduledCleanup() {
        (1..5).forEach { slot ->
            try {
                cleanupExpiredData(slot)
            } catch (e: Exception) {
                Log.e(TAG, "Scheduled cleanup failed for slot $slot", e)
            }
        }
    }

    private suspend fun performScheduledArchive() {
        (1..5).forEach { slot ->
            try {
                archiveOldData(slot)
            } catch (e: Exception) {
                Log.e(TAG, "Scheduled archive failed for slot $slot", e)
            }
        }
    }

    private fun updateStats(update: (LifecycleStats) -> LifecycleStats) {
        _stats.value = update(_stats.value)
    }

    fun getStorageUsage(): StorageUsage {
        val battleLogDir = File(context.filesDir, "battle_logs")
        val eventDir = File(context.filesDir, "events")
        val archiveDir = File(context.filesDir, LifecycleConfig.ARCHIVE_DIR)
        
        return StorageUsage(
            battleLogSize = calculateDirectorySize(battleLogDir),
            eventSize = calculateDirectorySize(eventDir),
            archiveSize = calculateDirectorySize(archiveDir),
            archiveCount = archives.size
        )
    }

    private fun calculateDirectorySize(dir: File): Long {
        if (!dir.exists()) return 0
        return dir.walkTopDown()
            .filter { it.isFile }
            .map { it.length() }
            .sum()
    }

    data class StorageUsage(
        val battleLogSize: Long,
        val eventSize: Long,
        val archiveSize: Long,
        val archiveCount: Int
    ) {
        val totalSize: Long get() = battleLogSize + eventSize + archiveSize
    }

    fun shutdown() {
        isShuttingDown = true
        cleanupJob?.cancel()
        archiveJob?.cancel()
        saveArchiveIndex()
        Log.i(TAG, "DataLifecycleManager shutdown completed")
    }
}
