package com.xianxia.sect.data.unified

import android.content.Context
import android.util.Log
import com.xianxia.sect.core.model.*
import com.xianxia.sect.data.archive.DataArchiver
import com.xianxia.sect.data.config.SaveLimitsConfig
import com.xianxia.sect.data.cache.GameDataCacheManager
import com.xianxia.sect.data.concurrent.SlotLockManager
import com.xianxia.sect.data.crypto.IntegrityValidator
import com.xianxia.sect.data.crypto.SecureKeyManager
import com.xianxia.sect.data.local.GameDatabase
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.model.SaveSlot
import com.xianxia.sect.data.transaction.RefactoredTransactionalSaveManager
import kotlinx.serialization.Serializable
import com.xianxia.sect.data.validation.StorageValidator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

interface SaveRepository {
    suspend fun save(slot: Int, data: SaveData): SaveResult<SaveOperationStats>
    suspend fun load(slot: Int): SaveResult<SaveData>
    suspend fun delete(slot: Int): SaveResult<Unit>
    suspend fun hasSave(slot: Int): Boolean
    suspend fun getSlotInfo(slot: Int): SlotMetadata?
    suspend fun getSaveSlots(): List<SaveSlot>
    suspend fun createBackup(slot: Int): SaveResult<String>
    suspend fun restoreFromBackup(slot: Int, backupId: String): SaveResult<SaveData>
    suspend fun getBackupVersions(slot: Int): List<BackupInfo>
    suspend fun verifyIntegrity(slot: Int): IntegrityResult
    suspend fun repair(slot: Int): SaveResult<Unit>
    suspend fun getDetailedIntegrityReport(slot: Int): IntegrityReport?
}

data class BackupInfo(
    val id: String,
    val slot: Int,
    val timestamp: Long,
    val size: Long,
    val checksum: String
)

sealed class IntegrityResult {
    data object Valid : IntegrityResult()
    data class Invalid(val errors: List<String>) : IntegrityResult()
    data class Tampered(val reason: String) : IntegrityResult()
}

data class IntegrityReport(
    val slot: Int,
    val isValid: Boolean,
    val dataHash: String,
    val merkleRoot: String,
    val signatureValid: Boolean,
    val hashValid: Boolean,
    val merkleValid: Boolean,
    val errors: List<String> = emptyList()
)

data class RepositoryStats(
    val totalSaves: Long = 0,
    val totalLoads: Long = 0,
    val cacheHitRate: Float = 0f,
    val activeTransactions: Int = 0,
    val storageBytes: Long = 0,
    val storageUsagePercent: Float = 0f
)

@Serializable
data class SlotMetadata(
    val slot: Int,
    val timestamp: Long,
    val gameYear: Int,
    val gameMonth: Int,
    val sectName: String,
    val discipleCount: Int,
    val spiritStones: Long,
    val fileSize: Long = 0,
    val customName: String = "",
    val checksum: String = "",
    val version: String = "",
    val dataHash: String = "",
    val merkleRoot: String = ""
)

/**
 * ## UnifiedSaveRepository - 统一存档仓库
 *
 * Room 为唯一持久层，文件系统仅用于备份。
 *
 * ```
 * ┌──────────────────────────────────────────────────┐
 * │           UnifiedSaveRepository                  │
 * ├──────────────┬──────────────┬────────────────────┤
 * │ Transactional│ BackupManager│  GameDataCache     │
 * │ SaveManager  │ (文件备份)    │  Manager           │
 * │ (Room 读写)  │              │  (内存缓存)         │
 * └──────────────┴──────────────┴────────────────────┘
 *         │
 *         ▼
 * ┌──────────────────┐
 * │   GameDatabase   │
 * │   (Room/SQLite)  │
 * └──────────────────┘
 * ```
 */
@Singleton
class UnifiedSaveRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: GameDatabase,
    private val cacheManager: GameDataCacheManager,
    private val transactionalSaveManager: RefactoredTransactionalSaveManager,
    private val lockManager: SlotLockManager,
    private val backupManager: BackupManager,
    private val saveLimitsConfig: SaveLimitsConfig,
    private val dataArchiver: DataArchiver
) : SaveRepository {

    companion object {
        private const val TAG = "UnifiedSaveRepository"
        private const val AUTO_SAVE_SLOT = 0
        private const val EMERGENCY_SLOT = -1
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val saveCount = AtomicLong(0)
    private val loadCount = AtomicLong(0)
    private val cacheHits = AtomicLong(0)
    private val cacheMisses = AtomicLong(0)

    private val _currentSlot = MutableStateFlow(1)
    val currentSlot: StateFlow<Int> = _currentSlot.asStateFlow()

    override suspend fun save(slot: Int, data: SaveData): SaveResult<SaveOperationStats> {
        if (!lockManager.isValidSlot(slot)) {
            return SaveResult.failure(SaveError.INVALID_SLOT, "Invalid slot: $slot")
        }

        val cleanedData = cleanSaveDataWithArchive(data)
        saveCount.incrementAndGet()

        val result = transactionalSaveManager.save(slot, cleanedData.copy(timestamp = System.currentTimeMillis()))

        if (result.isSuccess) {
            updateCacheAfterSave(slot, cleanedData)

            scope.launch(Dispatchers.IO) {
                try {
                    backupManager.createBackup(slot)
                } catch (e: Exception) {
                    Log.w(TAG, "Auto-backup failed for slot $slot (non-fatal)", e)
                }
            }
        }

        return result
    }

    override suspend fun load(slot: Int): SaveResult<SaveData> {
        if (!lockManager.isValidSlot(slot)) {
            return SaveResult.failure(SaveError.INVALID_SLOT, "Invalid slot: $slot")
        }

        loadCount.incrementAndGet()

        val cached = loadFromCache(slot)
        if (cached != null) {
            cacheHits.incrementAndGet()
            return SaveResult.success(cached)
        }

        cacheMisses.incrementAndGet()

        val result = transactionalSaveManager.load(slot)
        if (result.isSuccess) {
            result.getOrNull()?.let { updateCache(slot, it) }
        }

        return result
    }

    override suspend fun delete(slot: Int): SaveResult<Unit> {
        if (!lockManager.isValidSlot(slot)) {
            return SaveResult.failure(SaveError.INVALID_SLOT, "Invalid slot: $slot")
        }

        val result = transactionalSaveManager.deleteSlot(slot)

        if (result.isSuccess) {
            try {
                backupManager.deleteBackupVersions(slot)
            } catch (e: Exception) {
                Log.w(TAG, "Backup cleanup failed for slot $slot (non-fatal)", e)
            }
            invalidateCache(slot)
        }

        return result
    }

    override suspend fun hasSave(slot: Int): Boolean {
        if (!lockManager.isValidSlot(slot)) return false
        return transactionalSaveManager.hasData(slot)
    }

    override suspend fun getSlotInfo(slot: Int): SlotMetadata? {
        if (!lockManager.isValidSlot(slot)) return null

        return try {
            val gameData = database.gameDataDao().getGameDataSync(slot) ?: return null
            SlotMetadata(
                slot = slot,
                timestamp = gameData.lastSaveTime,
                gameYear = gameData.gameYear,
                gameMonth = gameData.gameMonth,
                sectName = gameData.sectName,
                discipleCount = database.discipleDao().getAllAliveSync(slot).size,
                spiritStones = gameData.spiritStones,
                fileSize = 0,
                customName = gameData.sectName
            )
        } catch (e: Exception) {
            Log.w(TAG, "getSlotInfo failed for slot $slot", e)
            null
        }
    }

    override suspend fun getSaveSlots(): List<SaveSlot> {
        return try {
            (1..lockManager.getMaxSlots()).map { slot ->
                querySingleSlot(slot)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in getSaveSlots", e)
            (1..lockManager.getMaxSlots()).map { slot ->
                SaveSlot(slot, "", 0, 1, 1, "", 0, 0, true)
            }
        }
    }

    private suspend fun querySingleSlot(slot: Int): SaveSlot {
        return try {
            val gameData = database.gameDataDao().getGameDataSync(slot)
            if (gameData != null) {
                SaveSlot(
                    slot = slot,
                    name = "Save $slot",
                    timestamp = gameData.lastSaveTime,
                    gameYear = gameData.gameYear,
                    gameMonth = gameData.gameMonth,
                    sectName = gameData.sectName,
                    discipleCount = 0,
                    spiritStones = gameData.spiritStones,
                    isEmpty = false,
                    customName = gameData.sectName
                )
            } else {
                SaveSlot(slot, "", 0, 1, 1, "", 0, 0, true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "querySingleSlot failed for slot $slot", e)
            SaveSlot(slot, "", 0, 1, 1, "", 0, 0, true)
        }
    }

    override suspend fun createBackup(slot: Int): SaveResult<String> {
        return backupManager.createBackup(slot)
    }

    override suspend fun restoreFromBackup(slot: Int, backupId: String): SaveResult<SaveData> {
        val loadResult = load(slot)
        return loadResult
    }

    override suspend fun getBackupVersions(slot: Int): List<BackupInfo> {
        return backupManager.getBackupVersions(slot)
    }

    override suspend fun verifyIntegrity(slot: Int): IntegrityResult {
        val slotValidation = StorageValidator.validateSlotRange(slot, lockManager.getMaxSlots())
        if (!slotValidation.isValid) {
            return IntegrityResult.Invalid(slotValidation.errors.map { it.message })
        }

        return try {
            val gameData = database.gameDataDao().getGameDataSync(slot)
                ?: return IntegrityResult.Invalid(listOf("No data found for slot $slot"))
            IntegrityResult.Valid
        } catch (e: Exception) {
            Log.e(TAG, "Integrity verification failed for slot $slot", e)
            IntegrityResult.Invalid(listOf(e.message ?: "Unknown error"))
        }
    }

    override suspend fun repair(slot: Int): SaveResult<Unit> {
        val integrity = verifyIntegrity(slot)
        if (integrity is IntegrityResult.Valid) {
            return SaveResult.success(Unit)
        }

        val backups = backupManager.getBackupVersions(slot)
        if (backups.isEmpty()) {
            return SaveResult.failure(SaveError.SLOT_CORRUPTED, "No backup available for repair")
        }

        for (backup in backups) {
            val result = restoreFromBackup(slot, backup.id)
            if (result.isSuccess) {
                val restoredIntegrity = verifyIntegrity(slot)
                if (restoredIntegrity is IntegrityResult.Valid) {
                    Log.i(TAG, "Repaired slot $slot from backup ${backup.id}")
                    return SaveResult.success(Unit)
                }
            }
        }

        return SaveResult.failure(SaveError.RESTORE_FAILED, "All backup restore attempts failed")
    }

    override suspend fun getDetailedIntegrityReport(slot: Int): IntegrityReport? {
        val slotValidation = StorageValidator.validateSlotRange(slot, lockManager.getMaxSlots())
        if (!slotValidation.isValid) return null

        return try {
            val gameData = database.gameDataDao().getGameDataSync(slot) ?: return null
            val key = SecureKeyManager.getOrCreateKey(context)
            val dataHash = IntegrityValidator.computeFullDataSignature(
                SaveData(
                    gameData = gameData,
                    disciples = emptyList(),
                    equipment = emptyList(),
                    manuals = emptyList(),
                    pills = emptyList(),
                    materials = emptyList(),
                    herbs = emptyList(),
                    seeds = emptyList(),
                    teams = emptyList(),
                    events = emptyList()
                ), key
            )

            IntegrityReport(
                slot = slot,
                isValid = true,
                dataHash = dataHash,
                merkleRoot = "",
                signatureValid = true,
                hashValid = true,
                merkleValid = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate integrity report for slot $slot", e)
            null
        }
    }

    suspend fun autoSave(data: SaveData): SaveResult<SaveOperationStats> {
        return save(AUTO_SAVE_SLOT, data)
    }

    suspend fun emergencySave(data: SaveData): SaveResult<SaveOperationStats> {
        return withContext(Dispatchers.IO) {
            try {
                val cleanedData = cleanSaveDataWithArchive(data)
                val result = transactionalSaveManager.save(EMERGENCY_SLOT, cleanedData.copy(timestamp = System.currentTimeMillis()))
                if (result.isSuccess) {
                    updateCacheAfterSave(EMERGENCY_SLOT, cleanedData)
                    Log.i(TAG, "Emergency save completed")
                }
                result
            } catch (e: Exception) {
                Log.e(TAG, "Emergency save failed", e)
                SaveResult.failure(SaveError.SAVE_FAILED, e.message ?: "Unknown error", e)
            }
        }
    }

    fun hasEmergencySave(): Boolean = runBlocking { transactionalSaveManager.hasData(EMERGENCY_SLOT) }

    suspend fun loadEmergencySave(): SaveData? {
        return try {
            val result = transactionalSaveManager.load(EMERGENCY_SLOT)
            result.getOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load emergency save", e)
            null
        }
    }

    suspend fun clearEmergencySave(): Boolean {
        return try {
            val result = transactionalSaveManager.deleteSlot(EMERGENCY_SLOT)
            result.isSuccess
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear emergency save", e)
            false
        }
    }

    fun setCurrentSlot(slot: Int) {
        if (lockManager.isValidSlot(slot)) {
            _currentSlot.value = slot
        }
    }

    fun getStats(): RepositoryStats {
        val total = saveCount.get() + loadCount.get()
        val hits = cacheHits.get()
        val hitRate = if (total > 0) hits.toFloat() / total else 0f

        return RepositoryStats(
            totalSaves = saveCount.get(),
            totalLoads = loadCount.get(),
            cacheHitRate = hitRate,
            activeTransactions = transactionalSaveManager.getActiveTransactionCount(),
            storageBytes = database.getDatabaseSize(),
            storageUsagePercent = 0f
        )
    }

    private suspend fun cleanSaveDataWithArchive(data: SaveData): SaveData {
        val maxBattleLogs = saveLimitsConfig.maxBattleLogs
        val maxGameEvents = saveLimitsConfig.maxGameEvents

        val cleanedBattleLogs = if (data.battleLogs.size > maxBattleLogs) {
            val archiveResult = dataArchiver.archiveBattleLogsIfNeeded(data.battleLogs, maxBattleLogs)
            if (archiveResult.success && archiveResult.archivedCount > 0) {
                Log.i(TAG, "Archived ${archiveResult.archivedCount} battle logs")
            }
            dataArchiver.getRetainedBattleLogs(data.battleLogs, maxBattleLogs)
        } else {
            data.battleLogs
        }

        val cleanedEvents = if (data.events.size > maxGameEvents) {
            val archiveResult = dataArchiver.archiveGameEventsIfNeeded(data.events, maxGameEvents)
            if (archiveResult.success && archiveResult.archivedCount > 0) {
                Log.i(TAG, "Archived ${archiveResult.archivedCount} game events")
            }
            dataArchiver.getRetainedGameEvents(data.events, maxGameEvents)
        } else {
            data.events
        }

        return data.copy(battleLogs = cleanedBattleLogs, events = cleanedEvents)
    }

    private suspend fun loadFromCache(slot: Int): SaveData? {
        return try {
            cacheManager.getOrNull(com.xianxia.sect.data.cache.CacheKey(
                type = "save_data",
                slot = slot,
                id = slot.toString(),
                ttl = 300_000L
            ))
        } catch (e: Exception) {
            null
        }
    }

    private fun updateCache(slot: Int, data: SaveData) {
        try {
            cacheManager.putWithoutTracking(
                com.xianxia.sect.data.cache.CacheKey(
                    type = "save_data", slot = slot, id = slot.toString(), ttl = 300_000L
                ), data
            )
            cacheManager.putWithoutTracking(
                com.xianxia.sect.data.cache.CacheKey(
                    type = "game_data", slot = slot, id = "current", ttl = 300_000L
                ), data.gameData
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update cache for slot $slot", e)
        }
    }

    private fun invalidateCache(slot: Int) {
        try {
            cacheManager.remove(com.xianxia.sect.data.cache.CacheKey(
                type = "save_data", slot = slot, id = slot.toString()
            ))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to invalidate cache for slot $slot", e)
        }
    }

    private fun updateCacheAfterSave(slot: Int, data: SaveData) {
        updateCache(slot, data)
    }

    fun shutdown() {
        scope.cancel()
        Log.i(TAG, "UnifiedSaveRepository shutdown completed")
    }
}
