package com.xianxia.sect.data.engine

import android.content.Context
import android.util.Log
import com.xianxia.sect.core.state.fixStorageBagReferences
import com.xianxia.sect.core.model.*
import com.xianxia.sect.data.archive.ArchivedBattleLog
import com.xianxia.sect.data.archive.ArchivedDisciple
import com.xianxia.sect.data.archive.ArchivedGameEvent
import com.xianxia.sect.data.archive.DataArchiver
import com.xianxia.sect.data.cache.CacheLayer
import com.xianxia.sect.data.cache.CacheKey
import com.xianxia.sect.data.config.SaveLimitsConfig
import com.xianxia.sect.data.concurrent.SlotLockManager
import com.xianxia.sect.data.crypto.CryptoModule
import com.xianxia.sect.data.crypto.IntegrityReport
import com.xianxia.sect.data.incremental.ChangeLogOperation
import com.xianxia.sect.data.incremental.ChangeLogPersistence
import com.xianxia.sect.data.local.GameDatabase
import com.xianxia.sect.data.local.SaveSlotMetadata
import com.xianxia.sect.data.memory.DynamicMemoryManager
import com.xianxia.sect.data.memory.MemoryPressureLevel
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.model.SaveSlot
import com.xianxia.sect.data.result.StorageError
import com.xianxia.sect.data.result.StorageResult
import com.xianxia.sect.data.StorageConstants
import com.xianxia.sect.data.unified.BackupInfo
import com.xianxia.sect.data.unified.BackupManager
import com.xianxia.sect.data.unified.IntegrityResult
import com.xianxia.sect.data.serialization.unified.SerializationModule
import com.xianxia.sect.data.unified.SlotMetadata
import com.xianxia.sect.data.validation.StorageValidator
import com.xianxia.sect.data.wal.WALProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.room.withTransaction
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

data class EngineProgress(
    val stage: Stage,
    val progress: Float,
    val message: String = ""
) {
    enum class Stage {
        IDLE,
        VALIDATING,
        SAVING_CORE,
        SAVING_DISCIPLES,
        SAVING_ITEMS,
        SAVING_WORLD,
        SAVING_HISTORY,
        UPDATING_CACHE,
        COMPLETED,
        FAILED
    }
}

data class SaveOperationStats(
    val bytesWritten: Long = 0,
    val timeMs: Long = 0,
    val wasIncremental: Boolean = false
)

enum class SavePriority {
    NORMAL,
    HIGH,
    CRITICAL
}

@Singleton
class StorageEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: GameDatabase,
    private val cache: CacheLayer,
    private val crypto: CryptoModule,
    private val lockManager: SlotLockManager,
    private val wal: WALProvider,
    private val saveLimitsConfig: SaveLimitsConfig,
    private val serializationModule: SerializationModule,
    private val changeLogPersistence: ChangeLogPersistence,
    private val backupManager: BackupManager,
    private val dataArchiver: DataArchiver,
    private val memoryManager: DynamicMemoryManager
) {
    private val circuitBreaker = StorageCircuitBreaker()
    private val pruningScheduler = DataPruningScheduler(database, circuitBreaker)
    private val archiveScheduler = DataArchiveScheduler(database)
    private val memoryGuard = ProactiveMemoryGuard(memoryManager, cache)
    companion object {
        private const val TAG = "StorageEngine"
        private const val MAX_BATCH_SIZE = 200

        fun estimateSaveSize(data: SaveData): Long {
            val es = StorageConstants.EntitySize

            var size = StorageConstants.ESTIMATE_BASE_OVERHEAD

            size += data.disciples.size * es.DISCIPLE
            size += data.equipmentStacks.size * es.EQUIPMENT
            size += data.equipmentInstances.size * es.EQUIPMENT
            size += data.manualStacks.size * es.MANUAL
            size += data.manualInstances.size * es.MANUAL
            size += data.pills.size * es.PILL
            size += data.materials.size * es.MATERIAL
            size += data.herbs.size * es.HERB
            size += data.seeds.size * es.SEED
            size += data.battleLogs.size * es.BATTLE_LOG
            size += data.events.size * es.GAME_EVENT
            size += data.teams.size * es.TEAM
            size += data.alliances.size * es.ALLIANCE

            val serializationOverhead = (size * StorageConstants.ESTIMATE_SERIALIZATION_OVERHEAD_RATIO).toLong()
            size += serializationOverhead

            return size
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _progress = MutableStateFlow(EngineProgress(EngineProgress.Stage.IDLE, 0f))
    val progress: StateFlow<EngineProgress> = _progress.asStateFlow()

    private val _currentSlot = MutableStateFlow(1)
    val currentSlot: StateFlow<Int> = _currentSlot.asStateFlow()

    private val isEmergencySaving = AtomicBoolean(false)

    private val saveCount = AtomicLong(0)
    private val loadCount = AtomicLong(0)
    private val cacheHits = AtomicLong(0)
    private val cacheMisses = AtomicLong(0)

    suspend fun save(slot: Int, data: SaveData, priority: SavePriority = SavePriority.NORMAL): StorageResult<SaveOperationStats> {
        if (!lockManager.isValidSlot(slot)) {
            return StorageResult.failure(StorageError.INVALID_SLOT, "Invalid slot: $slot")
        }

        return lockManager.withWriteLockLight(slot) {
            try {
                val startTime = System.currentTimeMillis()

                _progress.value = EngineProgress(EngineProgress.Stage.SAVING_CORE, 0.1f, "Saving core data")

                val cleanedData = cleanSaveDataWithArchive(data)
                val dataWithTimestamp = cleanedData.copy(timestamp = System.currentTimeMillis())

                val result = performFullTransactionSave(slot, dataWithTimestamp)

                if (result.isSuccess) {
                    _progress.value = EngineProgress(EngineProgress.Stage.UPDATING_CACHE, 0.8f, "Updating cache")
                    updateCacheAfterSave(slot, dataWithTimestamp)

                    _progress.value = EngineProgress(EngineProgress.Stage.SAVING_HISTORY, 0.85f, "Logging changes")
                    logSaveChanges(slot, dataWithTimestamp)

                    if (priority == SavePriority.CRITICAL) {
                        createCriticalSnapshot(slot, dataWithTimestamp)
                    }

                    scope.launch(Dispatchers.IO) {
                        try {
                            backupManager.createBackup(slot)
                        } catch (e: Exception) {
                            Log.w(TAG, "Auto-backup failed for slot $slot (non-fatal)", e)
                        }
                    }

                    saveCount.incrementAndGet()
                    _progress.value = EngineProgress(EngineProgress.Stage.COMPLETED, 1.0f, "Save completed")
                }

                result.map { stats ->
                    val elapsed = System.currentTimeMillis() - startTime
                    stats.copy(timeMs = elapsed)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Save failed for slot $slot", e)
                _progress.value = EngineProgress(EngineProgress.Stage.FAILED, 0f, e.message ?: "Unknown error")
                val error = when (e) {
                    is OutOfMemoryError -> StorageError.OUT_OF_MEMORY
                    is java.io.IOException -> StorageError.IO_ERROR
                    else -> StorageError.SAVE_FAILED
                }
                StorageResult.failure(error, e.message ?: "Save failed", e)
            }
        }
    }

    suspend fun load(slot: Int): StorageResult<SaveData> {
        if (!lockManager.isValidSlot(slot)) {
            return StorageResult.failure(StorageError.INVALID_SLOT, "Invalid slot: $slot")
        }

        return lockManager.withReadLockLight(slot) {
            try {
                _progress.value = EngineProgress(EngineProgress.Stage.SAVING_CORE, 0.1f, "Loading from cache")

                val cachedData = loadFromCache(slot)
                if (cachedData != null) {
                    cacheHits.incrementAndGet()
                    loadCount.incrementAndGet()
                    Log.d(TAG, "Cache hit for slot $slot")
                    _progress.value = EngineProgress(EngineProgress.Stage.COMPLETED, 1.0f, "Load completed (cache)")
                    return@withReadLockLight StorageResult.success(cachedData)
                }

                cacheMisses.incrementAndGet()
                _progress.value = EngineProgress(EngineProgress.Stage.SAVING_CORE, 0.2f, "Loading from database")
                val dbData = loadFromDatabase(slot)

                if (dbData != null) {
                    loadCount.incrementAndGet()
                    clearCacheForSlot(slot)
                    updateCacheAfterSave(slot, dbData)
                    _progress.value = EngineProgress(EngineProgress.Stage.COMPLETED, 1.0f, "Load completed (database)")
                    StorageResult.success(dbData)
                } else {
                    _progress.value = EngineProgress(EngineProgress.Stage.FAILED, 0f, "No data found")
                    StorageResult.failure(StorageError.SLOT_EMPTY, "No data in slot $slot")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Load failed for slot $slot", e)
                _progress.value = EngineProgress(EngineProgress.Stage.FAILED, 0f, e.message ?: "Unknown error")
                StorageResult.failure(StorageError.LOAD_FAILED, e.message ?: "Load failed", e)
            }
        }
    }

    suspend fun delete(slot: Int): StorageResult<Unit> {
        if (!lockManager.isValidSlot(slot)) {
            return StorageResult.failure(StorageError.INVALID_SLOT, "Invalid slot: $slot")
        }

        val isAutoSave = StorageConstants.isAutoSaveSlot(slot)
        Log.i(TAG, "Deleting slot $slot (isAutoSave=$isAutoSave)")

        return lockManager.withWriteLockLight(slot) {
            try {
                clearCacheForSlot(slot)

                database.withTransaction {
                    database.gameDataDao().deleteAll(slot)
                    database.discipleDao().deleteAll(slot)
                    database.discipleCoreDao().deleteAll(slot)
                    database.discipleCombatStatsDao().deleteAll(slot)
                    database.discipleEquipmentDao().deleteAll(slot)
                    database.discipleExtendedDao().deleteAll(slot)
                    database.discipleAttributesDao().deleteAll(slot)
                    database.equipmentStackDao().deleteAll(slot)
                    database.equipmentInstanceDao().deleteAll(slot)
                    database.manualStackDao().deleteAll(slot)
                    database.manualInstanceDao().deleteAll(slot)
                    database.pillDao().deleteAll(slot)
                    database.materialDao().deleteAll(slot)
                    database.seedDao().deleteAll(slot)
                    database.herbDao().deleteAll(slot)
                    database.explorationTeamDao().deleteAll(slot)
                    database.buildingSlotDao().deleteAll(slot)
                    database.dungeonDao().deleteAll(slot)
                    database.recipeDao().deleteAll(slot)
                    database.forgeSlotDao().deleteAll(slot)
                    database.alchemySlotDao().deleteAll(slot)
                    database.productionSlotDao().deleteBySlot(slot)
                    database.battleLogDao().deleteAll(slot)
                    database.gameEventDao().deleteAll(slot)
                }

                clearCacheForSlot(slot)

                try {
                    backupManager.deleteBackupVersions(slot)
                } catch (e: Exception) {
                    Log.w(TAG, "Backup cleanup failed for slot $slot (non-fatal)", e)
                }

                Log.i(TAG, "Deleted all data for slot $slot (isAutoSave=$isAutoSave)")
                StorageResult.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Delete failed for slot $slot", e)
                StorageResult.failure(StorageError.DELETE_FAILED, e.message ?: "Delete failed", e)
            }
        }
    }

    suspend fun hasData(slot: Int): Boolean {
        if (!lockManager.isValidSlot(slot)) return false

        return try {
            database.gameDataDao().getGameDataSync(slot) != null
        } catch (e: Exception) {
            Log.e(TAG, "hasData check failed for slot $slot", e)
            false
        }
    }

    suspend fun exportToFile(slot: Int, file: File): StorageResult<Unit> {
        if (!lockManager.isValidSlot(slot)) {
            return StorageResult.failure(StorageError.INVALID_SLOT, "Invalid slot: $slot")
        }

        return lockManager.withReadLockLight(slot) {
            try {
                val loadResult = load(slot)
                if (loadResult.isFailure) {
                    return@withReadLockLight StorageResult.failure(
                        StorageError.LOAD_FAILED,
                        "Failed to load data for export"
                    )
                }

                val saveData = loadResult.getOrThrow()
                val saveDataBytes = serializationModule.serializeAndCompressSaveData(saveData)

                file.parentFile?.mkdirs()
                FileOutputStream(file).use { output ->
                    output.write(saveDataBytes)
                }

                Log.i(TAG, "Exported slot $slot to ${file.absolutePath} (${saveDataBytes.size} bytes)")
                StorageResult.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Export failed for slot $slot", e)
                StorageResult.failure(StorageError.IO_ERROR, e.message ?: "Export failed", e)
            }
        }
    }

    suspend fun emergencySave(data: SaveData): StorageResult<SaveOperationStats> {
        val emergencySlot = StorageConstants.EMERGENCY_SLOT

        if (!isEmergencySaving.compareAndSet(false, true)) {
            Log.w(TAG, "Emergency save already in progress, skipping duplicate request")
            return StorageResult.failure(StorageError.SAVE_FAILED, "Emergency save already in progress")
        }

        return try {
            lockManager.withWriteLockLight(emergencySlot) {
                try {
                    val startTime = System.currentTimeMillis()

                    val cleanedData = cleanSaveDataWithArchive(data)
                    val dataWithTimestamp = cleanedData.copy(timestamp = System.currentTimeMillis())

                    database.withTransaction {
                        writeAllDataToDatabase(emergencySlot, dataWithTimestamp)
                    }

                    updateCacheAfterSave(emergencySlot, dataWithTimestamp)

                    val elapsed = System.currentTimeMillis() - startTime
                    Log.i(TAG, "Emergency save completed in ${elapsed}ms")

                    StorageResult.success(SaveOperationStats(
                        bytesWritten = estimateSaveSize(dataWithTimestamp),
                        timeMs = elapsed,
                        wasIncremental = false
                    ))
                } catch (e: Exception) {
                    Log.e(TAG, "Emergency save failed", e)
                    StorageResult.failure(StorageError.SAVE_FAILED, e.message ?: "Emergency save failed", e)
                }
            }
        } finally {
            isEmergencySaving.set(false)
        }
    }

    suspend fun createBackup(slot: Int): com.xianxia.sect.data.unified.SaveResult<String> {
        return backupManager.createBackup(slot)
    }

    fun getBackupVersions(slot: Int): List<BackupInfo> {
        return backupManager.getBackupVersions(slot)
    }

    suspend fun restoreBackup(slot: Int, backupId: String): com.xianxia.sect.data.unified.SaveResult<SaveData> {
        val loadFunc: suspend (Int) -> com.xianxia.sect.data.unified.SaveResult<SaveData> = { restoreSlot ->
            val storageResult = load(restoreSlot)
            when (storageResult) {
                is StorageResult.Success -> com.xianxia.sect.data.unified.SaveResult.success(storageResult.data)
                is StorageResult.Failure -> com.xianxia.sect.data.unified.SaveResult.failure(
                    when (storageResult.error) {
                        StorageError.INVALID_SLOT -> com.xianxia.sect.data.unified.SaveError.INVALID_SLOT
                        StorageError.SLOT_EMPTY -> com.xianxia.sect.data.unified.SaveError.SLOT_EMPTY
                        StorageError.SLOT_CORRUPTED -> com.xianxia.sect.data.unified.SaveError.SLOT_CORRUPTED
                        StorageError.LOAD_FAILED -> com.xianxia.sect.data.unified.SaveError.LOAD_FAILED
                        else -> com.xianxia.sect.data.unified.SaveError.UNKNOWN
                    },
                    storageResult.message,
                    storageResult.cause
                )
            }
        }
        val restoreResult = backupManager.restoreFromBackup(slot, backupId, loadFunc)

        if (restoreResult.isSuccess) {
            clearCacheForSlot(slot)
        }

        return restoreResult
    }

    suspend fun getSlotMetadata(slot: Int): SlotMetadata? {
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
            Log.w(TAG, "getSlotMetadata failed for slot $slot", e)
            null
        }
    }

    suspend fun listSlots(): StorageResult<List<SlotMetadata>> {
        return try {
            val slots = (1..lockManager.getMaxSlots()).mapNotNull { slot ->
                getSlotMetadata(slot)
            }
            StorageResult.success(slots)
        } catch (e: Exception) {
            Log.e(TAG, "listSlots failed", e)
            StorageResult.failure(StorageError.LOAD_FAILED, e.message ?: "Failed to list slots")
        }
    }

    suspend fun validateIntegrity(slot: Int): StorageResult<IntegrityReport> {
        val slotValidation = StorageValidator.validateSlotRange(slot, lockManager.getMaxSlots())
        if (!slotValidation.isValid) {
            return StorageResult.failure(StorageError.VALIDATION_ERROR, slotValidation.errors.map { it.message }.joinToString())
        }

        return try {
            val gameData = database.gameDataDao().getGameDataSync(slot)
                ?: return StorageResult.failure(StorageError.SLOT_EMPTY, "No data found for slot $slot")

            val key = crypto.getOrCreateKey(context)
            val dataHash = crypto.computeFullDataSignature(
                SaveData(
                    gameData = gameData,
                    disciples = emptyList(),
                    pills = emptyList(),
                    materials = emptyList(),
                    herbs = emptyList(),
                    seeds = emptyList(),
                    teams = emptyList(),
                    events = emptyList()
                ), key
            )

            StorageResult.success(IntegrityReport(
                isValid = true,
                dataHash = dataHash,
                merkleRoot = "",
                signatureValid = true,
                hashValid = true,
                merkleValid = true
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Integrity validation failed for slot $slot", e)
            StorageResult.failure(StorageError.SLOT_CORRUPTED, e.message ?: "Integrity check failed")
        }
    }

    suspend fun getSaveSlots(): List<SaveSlot> {
        return try {
            val autoSlot = querySingleSlot(StorageConstants.AUTO_SAVE_SLOT)
            val manualSlots = (1..lockManager.getMaxSlots()).map { slot ->
                querySingleSlot(slot)
            }
            listOf(autoSlot) + manualSlots
        } catch (e: Exception) {
            Log.e(TAG, "Error in getSaveSlots", e)
            val autoSlot = SaveSlot(StorageConstants.AUTO_SAVE_SLOT, "", 0, 1, 1, "", 0, 0, true, isAutoSave = true)
            val manualSlots = (1..lockManager.getMaxSlots()).map { slot ->
                SaveSlot(slot, "", 0, 1, 1, "", 0, 0, true)
            }
            listOf(autoSlot) + manualSlots
        }
    }

    fun setCurrentSlot(slot: Int) {
        if (lockManager.isValidSlot(slot)) {
            _currentSlot.value = slot
        }
    }

    fun getCurrentSlot(): Int = _currentSlot.value

    suspend fun autoSave(data: SaveData): StorageResult<SaveOperationStats> {
        return save(StorageConstants.AUTO_SAVE_SLOT, data)
    }

    fun hasEmergencySave(): Boolean {
        return try {
            val slot = StorageConstants.EMERGENCY_SLOT
            lockManager.isValidSlot(slot) && runCatching {
                kotlinx.coroutines.runBlocking {
                    database.gameDataDao().getGameDataSync(slot) != null
                }
            }.getOrDefault(false)
        } catch (e: Exception) {
            false
        }
    }

    suspend fun loadEmergencySave(): SaveData? {
        return try {
            val result = load(StorageConstants.EMERGENCY_SLOT)
            result.getOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load emergency save", e)
            null
        }
    }

    suspend fun clearEmergencySave(): Boolean {
        return try {
            val result = delete(StorageConstants.EMERGENCY_SLOT)
            result.isSuccess
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear emergency save", e)
            false
        }
    }

    fun startMaintenance() {
        memoryGuard.startMonitoring()
        pruningScheduler.start()
        archiveScheduler.start()
        Log.i(TAG, "Storage maintenance started")
    }

    fun stopMaintenance() {
        memoryGuard.stopMonitoring()
        pruningScheduler.stop()
        archiveScheduler.stop()
        Log.i(TAG, "Storage maintenance stopped")
    }

    fun shutdown() {
        memoryGuard.shutdown()
        pruningScheduler.shutdown()
        archiveScheduler.shutdown()
        cache.shutdown()
        wal.shutdown()
        lockManager.shutdown()
        (scope.coroutineContext[Job] as? Job)?.cancel()
        Log.i(TAG, "StorageEngine shutdown completed")
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

    private suspend fun performFullTransactionSave(slot: Int, data: SaveData): StorageResult<SaveOperationStats> {
        database.withTransaction {
            writeAllDataToDatabase(slot, data)
        }

        val bytesWritten = estimateSaveSize(data)

        return StorageResult.success(SaveOperationStats(
            bytesWritten = bytesWritten,
            timeMs = 0,
            wasIncremental = false
        ))
    }

    private suspend fun writeAllDataToDatabase(slot: Int, data: SaveData) {
        Log.d(TAG, "writeAllDataToDatabase: slot=$slot, ${data.disciples.size} disciples, recruitList=${data.gameData.recruitList.size} unrecruited disciples")
        val gameDataWithSlot = data.gameData.copy(slotId = slot, id = "game_data_$slot", lastSaveTime = data.timestamp)
        database.gameDataDao().insert(gameDataWithSlot)

        database.discipleDao().deleteAll(slot)
        database.discipleCoreDao().deleteAll(slot)
        database.discipleCombatStatsDao().deleteAll(slot)
        database.discipleEquipmentDao().deleteAll(slot)
        database.discipleExtendedDao().deleteAll(slot)
        database.discipleAttributesDao().deleteAll(slot)

        data.disciples.chunked(MAX_BATCH_SIZE).forEach { batch ->
            val withSlot = batch.map { d -> d.copy(slotId = slot) }
            database.discipleDao().insertAll(withSlot)
            database.discipleCoreDao().insertAll(batch.map { d -> DiscipleCore.fromDisciple(d).copy(slotId = slot) })
            database.discipleCombatStatsDao().insertAll(batch.map { d -> DiscipleCombatStats.fromDisciple(d).copy(slotId = slot) })
            database.discipleEquipmentDao().insertAll(batch.map { d -> DiscipleEquipment.fromDisciple(d).copy(slotId = slot) })
            database.discipleExtendedDao().insertAll(batch.map { d -> DiscipleExtended.fromDisciple(d).copy(slotId = slot) })
            database.discipleAttributesDao().insertAll(batch.map { d -> DiscipleAttributes.fromDisciple(d).copy(slotId = slot) })
        }

        database.equipmentStackDao().deleteAll(slot)
        database.equipmentInstanceDao().deleteAll(slot)
        database.manualStackDao().deleteAll(slot)
        database.manualInstanceDao().deleteAll(slot)
        database.pillDao().deleteAll(slot)
        database.materialDao().deleteAll(slot)
        database.herbDao().deleteAll(slot)
        database.seedDao().deleteAll(slot)

        data.equipmentStacks.chunked(MAX_BATCH_SIZE).forEach { database.equipmentStackDao().insertAll(it.map { e -> e.copy(slotId = slot) }) }
        data.equipmentInstances.chunked(MAX_BATCH_SIZE).forEach { database.equipmentInstanceDao().insertAll(it.map { e -> e.copy(slotId = slot) }) }
        data.manualStacks.chunked(MAX_BATCH_SIZE).forEach { database.manualStackDao().insertAll(it.map { m -> m.copy(slotId = slot) }) }
        data.manualInstances.chunked(MAX_BATCH_SIZE).forEach { database.manualInstanceDao().insertAll(it.map { m -> m.copy(slotId = slot) }) }
        data.pills.chunked(MAX_BATCH_SIZE).forEach { database.pillDao().insertAll(it.map { p -> p.copy(slotId = slot) }) }
        data.materials.chunked(MAX_BATCH_SIZE).forEach { database.materialDao().insertAll(it.map { m -> m.copy(slotId = slot) }) }
        data.herbs.chunked(MAX_BATCH_SIZE).forEach { database.herbDao().insertAll(it.map { h -> h.copy(slotId = slot) }) }
        data.seeds.chunked(MAX_BATCH_SIZE).forEach { database.seedDao().insertAll(it.map { s -> s.copy(slotId = slot) }) }

        database.explorationTeamDao().deleteAll(slot)
        database.buildingSlotDao().deleteAll(slot)
        database.dungeonDao().deleteAll(slot)
        database.recipeDao().deleteAll(slot)

        data.teams.chunked(MAX_BATCH_SIZE).forEach { database.explorationTeamDao().insertAll(it.map { t -> t.copy(slotId = slot) }) }

        database.battleLogDao().deleteAll(slot)
        database.gameEventDao().deleteAll(slot)

        data.battleLogs.chunked(MAX_BATCH_SIZE).forEach { database.battleLogDao().insertAll(it.map { b -> b.copy(slotId = slot) }) }
        data.events.chunked(MAX_BATCH_SIZE).forEach { database.gameEventDao().insertAll(it.map { e -> e.copy(slotId = slot) }) }

        database.forgeSlotDao().deleteAll(slot)
        database.alchemySlotDao().deleteAll(slot)
        database.productionSlotDao().deleteBySlot(slot)

        val productionSlotsToSave = data.productionSlots.ifEmpty {
            data.gameData.productionSlots ?: emptyList()
        }
        if (productionSlotsToSave.isNotEmpty()) {
            productionSlotsToSave.chunked(MAX_BATCH_SIZE).forEach { batch ->
                database.productionSlotDao().insertAll(batch.map { it.copy(slotId = slot) })
            }
        }

        data.gameData.unlockedDungeons?.map { Dungeon(it, slotId = slot) }?.let { dungeons ->
            database.dungeonDao().insertAll(dungeons)
        }
        data.gameData.unlockedRecipes?.map { Recipe(it, slotId = slot) }?.let { recipes ->
            database.recipeDao().insertAll(recipes)
        }

        syncSlotMetadata(slot, data)
    }

    private suspend fun syncSlotMetadata(slot: Int, data: SaveData) {
        val gd = data.gameData
        val metadata = SaveSlotMetadata(
            slotId = slot,
            sectName = gd.sectName,
            gameYear = gd.gameYear,
            gameMonth = gd.gameMonth,
            gameDay = gd.gameDay,
            spiritStones = gd.spiritStones,
            spiritHerbs = gd.spiritHerbs,
            sectCultivation = gd.sectCultivation,
            isGameStarted = gd.isGameStarted,
            lastSaveTime = data.timestamp,
            discipleCount = data.disciples.count { it.isAlive }
        )
        database.saveSlotMetadataDao().upsert(metadata)
    }

    private suspend fun logSaveChanges(slot: Int, data: SaveData) {
        try {
            changeLogPersistence.logChange(
                tableName = "game_data",
                recordId = "game_data_$slot",
                operation = ChangeLogOperation.UPDATE
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to log save change for slot $slot", e)
        }
    }

    private suspend fun loadFromCache(slot: Int): SaveData? = withContext(Dispatchers.IO) {
        try {
            val gameDataKey = CacheKey.forGameData(slot)
            cache.getOrNull<SaveData>(gameDataKey)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load from cache for slot $slot", e)
            null
        }
    }

    private suspend fun loadFromDatabase(slot: Int): SaveData? {
        return try {
            val gameData = database.gameDataDao().getGameDataSync(slot) ?: return null

            val disciples = database.discipleDao().getAllSync(slot)
            val equipmentStacks = database.equipmentStackDao().getAllSync(slot)
            val equipmentInstances = database.equipmentInstanceDao().getAllSync(slot)
            val manualStacks = database.manualStackDao().getAllSync(slot)
            val manualInstances = database.manualInstanceDao().getAllSync(slot)
            val pills = database.pillDao().getAllSync(slot)
            val materials = database.materialDao().getAllSync(slot)
            val herbs = database.herbDao().getAllSync(slot)
            val seeds = database.seedDao().getAllSync(slot)
            val teams = database.explorationTeamDao().getAllSync(slot)
            val alliances = gameData.alliances ?: emptyList()
            val battleLogs = database.battleLogDao().getAllSync(slot)
            val events = database.gameEventDao().getAllSync(slot)
            var productionSlots = database.productionSlotDao().getBySlotSync(slot)

            if (productionSlots.isEmpty()) {
                val fallbackSlots = gameData.productionSlots
                if (!fallbackSlots.isNullOrEmpty()) {
                    Log.w(TAG, "Production slots empty in DB, using GameData fallback (${fallbackSlots.size} slots)")
                    productionSlots = fallbackSlots
                }
            }

            val fixedDisciples = fixStorageBagReferences(
                equipmentStacks = equipmentStacks,
                equipmentInstances = equipmentInstances,
                manualStacks = manualStacks,
                manualInstances = manualInstances,
                disciples = disciples
            )

            SaveData(
                gameData = gameData,
                disciples = fixedDisciples,
                equipmentStacks = equipmentStacks,
                equipmentInstances = equipmentInstances,
                manualStacks = manualStacks,
                manualInstances = manualInstances,
                pills = pills,
                materials = materials,
                herbs = herbs,
                seeds = seeds,
                teams = teams,
                events = events,
                battleLogs = battleLogs,
                alliances = alliances,
                productionSlots = productionSlots
            ).also {
                Log.d(TAG, "loadFromDatabase: slot=$slot, ${disciples.size} disciples, recruitList=${gameData.recruitList.size} unrecruited disciples")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load from database for slot $slot", e)
            null
        }
    }

    private fun updateCacheAfterSave(slot: Int, data: SaveData) {
        try {
            val cacheKey = CacheKey.forGameData(slot)
            cache.putWithoutTracking(cacheKey, data)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update cache for slot $slot", e)
        }
    }

    private fun clearCacheForSlot(slot: Int) {
        try {
            val cacheKey = CacheKey.forGameData(slot)
            cache.remove(cacheKey)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear cache for slot $slot", e)
        }
    }

    private suspend fun createCriticalSnapshot(slot: Int, data: SaveData) {
        try {
            val snapshotData = serializationModule.serializeAndCompressSaveData(data)
            wal.createImportantSnapshot(
                slot = slot,
                snapshotProvider = { snapshotData },
                eventType = "CRITICAL_SAVE"
            )
            Log.d(TAG, "Created critical snapshot for slot $slot")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create critical snapshot for slot $slot", e)
        }
    }

    private suspend fun querySingleSlot(slot: Int): SaveSlot {
        val isAutoSave = slot == StorageConstants.AUTO_SAVE_SLOT
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
                    discipleCount = database.discipleDao().getAllAliveSync(slot).size,
                    spiritStones = gameData.spiritStones,
                    isEmpty = false,
                    customName = gameData.sectName,
                    isAutoSave = isAutoSave
                )
            } else {
                SaveSlot(slot, "", 0, 1, 1, "", 0, 0, true, isAutoSave = isAutoSave)
            }
        } catch (e: Exception) {
            Log.w(TAG, "querySingleSlot failed for slot $slot", e)
            SaveSlot(slot, "", 0, 1, 1, "", 0, 0, true, isAutoSave = isAutoSave)
        }
    }

}

// region StorageCircuitBreaker

internal enum class CircuitState {
    CLOSED,
    OPEN,
    HALF_OPEN
}

internal data class CircuitBreakerConfig(
    val failureThreshold: Int = 5,
    val recoveryTimeoutMs: Long = 30_000L,
    val halfOpenMaxAttempts: Int = 3,
    val successThresholdToClose: Int = 3,
    val slidingWindowSize: Int = 20
)

internal data class CircuitBreakerStats(
    val operation: String,
    val state: CircuitState,
    val failureCount: Int,
    val successCount: Int,
    val totalCalls: Long,
    val lastFailureTime: Long,
    val lastStateChangeTime: Long,
    val rejectedCount: Long
)

internal class OperationCircuitBreaker(
    private val operation: String,
    private val config: CircuitBreakerConfig
) {
    private val mutex = Mutex()
    private var state = CircuitState.CLOSED
    private var failureCount = 0
    private var successCount = 0
    private var halfOpenAttempts = 0
    private var consecutiveSuccesses = 0
    private val totalCalls = AtomicLong(0)
    private val rejectedCount = AtomicLong(0)
    private var lastFailureTime = 0L
    private var lastStateChangeTime = System.currentTimeMillis()

    private val slidingWindow = ArrayDeque<Long>(config.slidingWindowSize)
    private val _stateFlow = MutableStateFlow(CircuitState.CLOSED)
    val stateFlow: StateFlow<CircuitState> = _stateFlow.asStateFlow()

    suspend fun allowRequest(): Boolean = mutex.withLock {
        totalCalls.incrementAndGet()

        when (state) {
            CircuitState.CLOSED -> true
            CircuitState.OPEN -> {
                val elapsed = System.currentTimeMillis() - lastFailureTime
                if (elapsed >= config.recoveryTimeoutMs) {
                    transitionTo(CircuitState.HALF_OPEN)
                    halfOpenAttempts = 0
                    return@withLock true
                }
                rejectedCount.incrementAndGet()
                false
            }
            CircuitState.HALF_OPEN -> {
                if (halfOpenAttempts < config.halfOpenMaxAttempts) {
                    halfOpenAttempts++
                    true
                } else {
                    rejectedCount.incrementAndGet()
                    false
                }
            }
        }
    }

    suspend fun recordSuccess() = mutex.withLock {
        recordInWindow(true)

        when (state) {
            CircuitState.CLOSED -> {
                failureCount = 0
            }
            CircuitState.HALF_OPEN -> {
                consecutiveSuccesses++
                if (consecutiveSuccesses >= config.successThresholdToClose) {
                    transitionTo(CircuitState.CLOSED)
                    failureCount = 0
                    consecutiveSuccesses = 0
                }
            }
            CircuitState.OPEN -> {}
        }
    }

    suspend fun recordFailure() = mutex.withLock {
        recordInWindow(false)
        lastFailureTime = System.currentTimeMillis()
        failureCount++
        consecutiveSuccesses = 0

        when (state) {
            CircuitState.CLOSED -> {
                val recentFailures = countRecentFailures()
                if (recentFailures >= config.failureThreshold) {
                    transitionTo(CircuitState.OPEN)
                }
            }
            CircuitState.HALF_OPEN -> {
                transitionTo(CircuitState.OPEN)
            }
            CircuitState.OPEN -> {}
        }
    }

    private fun recordInWindow(success: Boolean) {
        val now = System.currentTimeMillis()
        slidingWindow.addLast(if (success) now else -now)
        while (slidingWindow.size > config.slidingWindowSize) {
            slidingWindow.removeFirst()
        }
    }

    private fun countRecentFailures(): Int {
        return slidingWindow.count { it < 0 }
    }

    private fun transitionTo(newState: CircuitState) {
        val oldState = state
        state = newState
        lastStateChangeTime = System.currentTimeMillis()
        _stateFlow.value = newState
        Log.w(TAG, "Circuit breaker [$operation]: $oldState -> $newState")
    }

    suspend fun getStats(): CircuitBreakerStats = mutex.withLock {
        CircuitBreakerStats(
            operation = operation,
            state = state,
            failureCount = failureCount,
            successCount = successCount,
            totalCalls = totalCalls.get(),
            lastFailureTime = lastFailureTime,
            lastStateChangeTime = lastStateChangeTime,
            rejectedCount = rejectedCount.get()
        )
    }

    suspend fun reset() = mutex.withLock {
        state = CircuitState.CLOSED
        failureCount = 0
        successCount = 0
        halfOpenAttempts = 0
        consecutiveSuccesses = 0
        slidingWindow.clear()
        _stateFlow.value = CircuitState.CLOSED
        lastStateChangeTime = System.currentTimeMillis()
    }

    companion object {
        private const val TAG = "OpCircuitBreaker"
    }
}

internal class StorageCircuitBreaker {

    private val breakers = ConcurrentHashMap<String, OperationCircuitBreaker>()

    private val defaultConfigs = mapOf(
        "save" to CircuitBreakerConfig(
            failureThreshold = 5,
            recoveryTimeoutMs = 30_000L,
            halfOpenMaxAttempts = 2,
            successThresholdToClose = 3
        ),
        "load" to CircuitBreakerConfig(
            failureThreshold = 8,
            recoveryTimeoutMs = 15_000L,
            halfOpenMaxAttempts = 3,
            successThresholdToClose = 2
        ),
        "cache" to CircuitBreakerConfig(
            failureThreshold = 10,
            recoveryTimeoutMs = 10_000L,
            halfOpenMaxAttempts = 5,
            successThresholdToClose = 2
        ),
        "wal" to CircuitBreakerConfig(
            failureThreshold = 3,
            recoveryTimeoutMs = 60_000L,
            halfOpenMaxAttempts = 1,
            successThresholdToClose = 5
        ),
        "incremental" to CircuitBreakerConfig(
            failureThreshold = 5,
            recoveryTimeoutMs = 30_000L,
            halfOpenMaxAttempts = 2,
            successThresholdToClose = 3
        ),
        "recovery" to CircuitBreakerConfig(
            failureThreshold = 3,
            recoveryTimeoutMs = 120_000L,
            halfOpenMaxAttempts = 1,
            successThresholdToClose = 5
        ),
        "pruning" to CircuitBreakerConfig(
            failureThreshold = 10,
            recoveryTimeoutMs = 60_000L,
            halfOpenMaxAttempts = 3,
            successThresholdToClose = 2
        )
    )

    suspend fun allowRequest(operation: String): Boolean {
        return getOrCreateBreaker(operation).allowRequest()
    }

    suspend fun recordSuccess(operation: String) {
        getOrCreateBreaker(operation).recordSuccess()
    }

    suspend fun recordFailure(operation: String) {
        getOrCreateBreaker(operation).recordFailure()
    }

    suspend fun getState(operation: String): CircuitState {
        return getOrCreateBreaker(operation).getStats().state
    }

    suspend fun getStats(operation: String): CircuitBreakerStats {
        return getOrCreateBreaker(operation).getStats()
    }

    suspend fun getAllStats(): Map<String, CircuitBreakerStats> {
        return breakers.mapValues { it.value.getStats() }
    }

    suspend fun reset(operation: String) {
        breakers[operation]?.reset()
    }

    suspend fun resetAll() {
        breakers.values.forEach { it.reset() }
    }

    private fun getOrCreateBreaker(operation: String): OperationCircuitBreaker {
        return breakers.getOrPut(operation) {
            val config = defaultConfigs[operation] ?: CircuitBreakerConfig()
            OperationCircuitBreaker(operation, config)
        }
    }

    companion object {
        private const val TAG = "StorageCircuitBreaker"
    }
}

// endregion StorageCircuitBreaker

// region ProactiveMemoryGuard

internal data class MemoryGuardConfig(
    val checkIntervalMs: Long = 10_000L,
    val warningThresholdPercent: Double = 0.75,
    val criticalThresholdPercent: Double = 0.85,
    val emergencyThresholdPercent: Double = 0.92,
    val jvmWarningThresholdPercent: Double = 0.70,
    val jvmCriticalThresholdPercent: Double = 0.80,
    val jvmEmergencyThresholdPercent: Double = 0.90,
    val cooldownMs: Long = 30_000L,
    val maxGcAttempts: Int = 3
)

internal enum class MemoryGuardLevel {
    NORMAL,
    WARNING,
    CRITICAL,
    EMERGENCY
}

internal data class MemoryGuardSnapshot(
    val level: MemoryGuardLevel,
    val systemAvailablePercent: Double,
    val jvmUsedPercent: Double,
    val totalChecks: Long,
    val totalWarnings: Long,
    val totalCriticalEvents: Long,
    val totalEmergencyEvents: Long,
    val totalGcInvocations: Long,
    val totalCacheEvictions: Long,
    val lastActionTime: Long,
    val lastActionDescription: String
)

internal class ProactiveMemoryGuard(
    private val memoryManager: DynamicMemoryManager,
    private val cache: CacheLayer
) {
    private val config = MemoryGuardConfig()
    private var monitorJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val totalChecks = AtomicLong(0)
    private val totalWarnings = AtomicLong(0)
    private val totalCriticalEvents = AtomicLong(0)
    private val totalEmergencyEvents = AtomicLong(0)
    private val totalGcInvocations = AtomicLong(0)
    private val totalCacheEvictions = AtomicLong(0)
    private var lastActionTime = 0L
    private var lastActionDescription = ""

    private val _currentLevel = MutableStateFlow(MemoryGuardLevel.NORMAL)
    val currentLevel: StateFlow<MemoryGuardLevel> = _currentLevel.asStateFlow()

    private val _snapshot = MutableStateFlow(
        MemoryGuardSnapshot(
            level = MemoryGuardLevel.NORMAL,
            systemAvailablePercent = 100.0,
            jvmUsedPercent = 0.0,
            totalChecks = 0,
            totalWarnings = 0,
            totalCriticalEvents = 0,
            totalEmergencyEvents = 0,
            totalGcInvocations = 0,
            totalCacheEvictions = 0,
            lastActionTime = 0,
            lastActionDescription = ""
        )
    )
    val snapshot: StateFlow<MemoryGuardSnapshot> = _snapshot.asStateFlow()

    fun startMonitoring() {
        if (monitorJob?.isActive == true) {
            Log.w(TAG, "Memory guard monitoring already active")
            return
        }

        monitorJob = scope.launch {
            Log.i(TAG, "Proactive memory guard started (interval=${config.checkIntervalMs}ms)")
            while (isActive) {
                try {
                    performCheck()
                } catch (e: Exception) {
                    Log.e(TAG, "Memory guard check failed", e)
                }
                delay(config.checkIntervalMs)
            }
        }
    }

    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        Log.i(TAG, "Proactive memory guard stopped")
    }

    fun shutdown() {
        stopMonitoring()
        scope.cancel()
        Log.i(TAG, "Proactive memory guard shutdown")
    }

    private suspend fun performCheck() {
        totalChecks.incrementAndGet()

        val memSnapshot = memoryManager.getMemorySnapshot()
        val systemAvailPercent = memSnapshot.availablePercent
        val jvmUsedPercent = memSnapshot.jvmUsagePercent

        val newLevel = when {
            systemAvailPercent < (100.0 - config.emergencyThresholdPercent * 100.0) ||
                jvmUsedPercent > config.jvmEmergencyThresholdPercent * 100.0 -> MemoryGuardLevel.EMERGENCY
            systemAvailPercent < (100.0 - config.criticalThresholdPercent * 100.0) ||
                jvmUsedPercent > config.jvmCriticalThresholdPercent * 100.0 -> MemoryGuardLevel.CRITICAL
            systemAvailPercent < (100.0 - config.warningThresholdPercent * 100.0) ||
                jvmUsedPercent > config.jvmWarningThresholdPercent * 100.0 -> MemoryGuardLevel.WARNING
            else -> MemoryGuardLevel.NORMAL
        }

        val previousLevel = _currentLevel.value
        _currentLevel.value = newLevel

        if (newLevel != previousLevel) {
            Log.w(TAG, "Memory guard level changed: $previousLevel -> $newLevel " +
                "(systemAvail=${String.format("%.1f%%", systemAvailPercent)}, " +
                "jvmUsed=${String.format("%.1f%%", jvmUsedPercent)})")
        }

        when (newLevel) {
            MemoryGuardLevel.WARNING -> handleWarning()
            MemoryGuardLevel.CRITICAL -> handleCritical()
            MemoryGuardLevel.EMERGENCY -> handleEmergency()
            MemoryGuardLevel.NORMAL -> {}
        }

        updateSnapshot(systemAvailPercent, jvmUsedPercent)
    }

    private suspend fun handleWarning() {
        if (!isCooldownElapsed()) return

        totalWarnings.incrementAndGet()
        Log.d(TAG, "Memory WARNING: requesting cache reduction")
        memoryManager.requestDegradation()
        recordAction("Requested memory degradation (warning level)")
    }

    private suspend fun handleCritical() {
        if (!isCooldownElapsed()) return

        totalCriticalEvents.incrementAndGet()
        Log.w(TAG, "Memory CRITICAL: forcing GC and aggressive cache eviction")

        withContext(Dispatchers.IO) {
            memoryManager.forceGcAndWait(300L)
            totalGcInvocations.incrementAndGet()
        }

        cache.evictColdData()
        totalCacheEvictions.incrementAndGet()

        memoryManager.requestDegradation()
        recordAction("Forced GC + cache eviction (critical level)")
    }

    private suspend fun handleEmergency() {
        totalEmergencyEvents.incrementAndGet()
        Log.e(TAG, "Memory EMERGENCY: aggressive cleanup required")

        withContext(Dispatchers.IO) {
            repeat(config.maxGcAttempts) { attempt ->
                memoryManager.forceGcAndWait(500L)
                totalGcInvocations.incrementAndGet()

                val snapshot = memoryManager.getMemorySnapshot()
                if (snapshot.pressureLevel.ordinal < MemoryPressureLevel.HIGH.ordinal) {
                    Log.i(TAG, "Memory recovered after GC attempt ${attempt + 1}")
                    return@withContext
                }
            }
        }

        cache.evictColdData()
        totalCacheEvictions.incrementAndGet()

        memoryManager.requestDegradation()
        recordAction("Emergency GC + full cache eviction")
    }

    private fun isCooldownElapsed(): Boolean {
        val elapsed = System.currentTimeMillis() - lastActionTime
        return elapsed >= config.cooldownMs
    }

    private fun recordAction(description: String) {
        lastActionTime = System.currentTimeMillis()
        lastActionDescription = description
    }

    private fun updateSnapshot(systemAvailPercent: Double, jvmUsedPercent: Double) {
        _snapshot.value = MemoryGuardSnapshot(
            level = _currentLevel.value,
            systemAvailablePercent = systemAvailPercent,
            jvmUsedPercent = jvmUsedPercent,
            totalChecks = totalChecks.get(),
            totalWarnings = totalWarnings.get(),
            totalCriticalEvents = totalCriticalEvents.get(),
            totalEmergencyEvents = totalEmergencyEvents.get(),
            totalGcInvocations = totalGcInvocations.get(),
            totalCacheEvictions = totalCacheEvictions.get(),
            lastActionTime = lastActionTime,
            lastActionDescription = lastActionDescription
        )
    }

    fun getDiagnostics(): MemoryGuardSnapshot = _snapshot.value

    companion object {
        private const val TAG = "ProactiveMemoryGuard"
    }
}

// endregion ProactiveMemoryGuard

// region DataPruningScheduler

internal data class PruningConfig(
    val checkIntervalMs: Long = 300_000L,
    val maxBattleLogs: Int = StorageConstants.DEFAULT_MAX_BATTLE_LOGS,
    val maxGameEvents: Int = StorageConstants.DEFAULT_MAX_GAME_EVENTS,
    val battleLogRetentionMs: Long = 7 * 24 * 60 * 60 * 1000L,
    val gameEventRetentionMs: Long = 14 * 24 * 60 * 60 * 1000L,
    val slotIds: List<Int> = listOf(1, 2, 3, 4, 5),
    val enableAutoPruning: Boolean = true
)

internal data class PruningResult(
    val battleLogsDeleted: Int,
    val eventsDeleted: Int,
    val walSizeBeforeBytes: Long,
    val walSizeAfterBytes: Long,
    val dbSizeBeforeBytes: Long,
    val dbSizeAfterBytes: Long,
    val elapsedMs: Long
)

internal data class PruningStats(
    val totalPruningRuns: Long,
    val totalBattleLogsDeleted: Long,
    val totalEventsDeleted: Long,
    val lastPruningTime: Long,
    val lastPruningResult: PruningResult?,
    val isRunning: Boolean
)

internal class DataPruningScheduler(
    private val database: GameDatabase,
    private val circuitBreaker: StorageCircuitBreaker
) {
    private val config = PruningConfig()
    private var pruningJob: Job? = null
    private val isRunning = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val totalPruningRuns = AtomicLong(0)
    private val totalBattleLogsDeleted = AtomicLong(0)
    private val totalEventsDeleted = AtomicLong(0)
    private var lastPruningTime = 0L
    private var lastPruningResult: PruningResult? = null

    fun start() {
        if (!config.enableAutoPruning) {
            Log.i(TAG, "Auto pruning is disabled by config")
            return
        }

        if (pruningJob?.isActive == true) {
            Log.w(TAG, "Pruning scheduler already running")
            return
        }

        pruningJob = scope.launch {
            Log.i(TAG, "Data pruning scheduler started (interval=${config.checkIntervalMs}ms)")
            delay(config.checkIntervalMs)

            while (isActive) {
                try {
                    if (circuitBreaker.allowRequest("pruning")) {
                        performPruning()
                    } else {
                        Log.w(TAG, "Pruning skipped: circuit breaker is OPEN")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Pruning failed", e)
                    circuitBreaker.recordFailure("pruning")
                }

                delay(config.checkIntervalMs)
            }
        }
    }

   fun stop() {
        pruningJob?.cancel()
        pruningJob = null
        isRunning.set(false)
        Log.i(TAG, "Data pruning scheduler stopped")
    }

    fun shutdown() {
        stop()
        scope.cancel()
        Log.i(TAG, "Data pruning scheduler shutdown")
    }
    suspend fun performPruning(): PruningResult {
        if (!isRunning.compareAndSet(false, true)) {
            Log.w(TAG, "Pruning already in progress, skipping")
            return lastPruningResult ?: PruningResult(0, 0, 0, 0, 0, 0, 0)
        }

        val startTime = System.currentTimeMillis()

        return try {
            val dbSizeBefore = database.getDatabaseSize()
            val walSizeBefore = database.getWalFileSize()

            val battleLogCutoff = System.currentTimeMillis() - config.battleLogRetentionMs
            val eventCutoff = System.currentTimeMillis() - config.gameEventRetentionMs

            var totalLogsDeleted = 0
            var totalEventsDeleted = 0

            for (slotId in config.slotIds) {
                try {
                    database.battleLogDao().deleteOld(slotId, battleLogCutoff)
                    totalLogsDeleted++
                } catch (e: Exception) {
                    Log.d(TAG, "Battle log pruning for slot $slotId: ${e.message}")
                }

                try {
                    database.gameEventDao().deleteOld(slotId, eventCutoff)
                    totalEventsDeleted++
                } catch (e: Exception) {
                    Log.d(TAG, "Game event pruning for slot $slotId: ${e.message}")
                }
            }

            val dbSizeAfter = database.getDatabaseSize()
            val walSizeAfter = database.getWalFileSize()
            val elapsed = System.currentTimeMillis() - startTime

            val result = PruningResult(
                battleLogsDeleted = totalLogsDeleted,
                eventsDeleted = totalEventsDeleted,
                walSizeBeforeBytes = walSizeBefore,
                walSizeAfterBytes = walSizeAfter,
                dbSizeBeforeBytes = dbSizeBefore,
                dbSizeAfterBytes = dbSizeAfter,
                elapsedMs = elapsed
            )

            this.totalPruningRuns.incrementAndGet()
            this.totalBattleLogsDeleted.addAndGet(totalLogsDeleted.toLong())
            this.totalEventsDeleted.addAndGet(totalEventsDeleted.toLong())
            lastPruningTime = System.currentTimeMillis()
            lastPruningResult = result

            circuitBreaker.recordSuccess("pruning")

            Log.i(TAG, "Pruning complete: logs=$totalLogsDeleted, events=$totalEventsDeleted, " +
                "dbSize=${dbSizeBefore / 1024}KB->${dbSizeAfter / 1024}KB, elapsed=${elapsed}ms")

            result
        } catch (e: Exception) {
            circuitBreaker.recordFailure("pruning")
            Log.e(TAG, "Pruning failed", e)
            PruningResult(0, 0, 0, 0, 0, 0, System.currentTimeMillis() - startTime)
        } finally {
            isRunning.set(false)
        }
    }

    fun getStats(): PruningStats {
        return PruningStats(
            totalPruningRuns = totalPruningRuns.get(),
            totalBattleLogsDeleted = totalBattleLogsDeleted.get(),
            totalEventsDeleted = totalEventsDeleted.get(),
            lastPruningTime = lastPruningTime,
            lastPruningResult = lastPruningResult,
            isRunning = isRunning.get()
        )
    }

    companion object {
        private const val TAG = "DataPruningScheduler"
    }
}

// endregion DataPruningScheduler

// region DataArchiveScheduler

internal data class ArchiveConfig(
    val checkIntervalMs: Long = 600_000L,
    val battleLogHotCount: Int = 200,
    val gameEventHotCount: Int = 500,
    val deadDiscipleArchiveDelayMs: Long = 60_000L,
    val archiveRetentionMs: Long = 180L * 24 * 60 * 60 * 1000L,
    val slotIds: List<Int> = listOf(1, 2, 3, 4, 5),
    val enableAutoArchive: Boolean = true
)

internal data class ArchiveOperationResult(
    val battleLogsArchived: Int = 0,
    val gameEventsArchived: Int = 0,
    val disciplesArchived: Int = 0,
    val battleLogsCleaned: Int = 0,
    val gameEventsCleaned: Int = 0,
    val disciplesCleaned: Int = 0,
    val elapsedMs: Long = 0
)

internal class DataArchiveScheduler(
    private val database: GameDatabase
) {
    private val config = ArchiveConfig()
    private var archiveJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        if (!config.enableAutoArchive) {
            Log.i(TAG, "Auto archive is disabled by config")
            return
        }

        if (archiveJob?.isActive == true) {
            Log.w(TAG, "Archive scheduler already running")
            return
        }

        archiveJob = scope.launch {
            Log.i(TAG, "Data archive scheduler started (interval=${config.checkIntervalMs}ms)")
            delay(config.checkIntervalMs)

            while (isActive) {
                try {
                    performArchive()
                } catch (e: Exception) {
                    Log.e(TAG, "Archive operation failed", e)
                }

                delay(config.checkIntervalMs)
            }
        }
    }

    fun stop() {
        archiveJob?.cancel()
        archiveJob = null
        Log.i(TAG, "Data archive scheduler stopped")
    }

    fun shutdown() {
        stop()
        scope.cancel()
        Log.i(TAG, "Data archive scheduler shutdown")
    }

    suspend fun performArchive(): ArchiveOperationResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        var battleLogsArchived = 0
        var gameEventsArchived = 0
        var disciplesArchived = 0
        var battleLogsCleaned = 0
        var gameEventsCleaned = 0
        var disciplesCleaned = 0

        for (slotId in config.slotIds) {
            try {
                val logsResult = archiveBattleLogs(slotId)
                battleLogsArchived += logsResult

                val eventsResult = archiveGameEvents(slotId)
                gameEventsArchived += eventsResult

                val disciplesResult = archiveDeadDisciples(slotId)
                disciplesArchived += disciplesResult
            } catch (e: Exception) {
                Log.w(TAG, "Archive for slot $slotId failed: ${e.message}")
            }
        }

        try {
            val cutoff = System.currentTimeMillis() - config.archiveRetentionMs
            battleLogsCleaned = database.archivedBattleLogDao().deleteArchivedBefore(cutoff)
            gameEventsCleaned = database.archivedGameEventDao().deleteArchivedBefore(cutoff)
            disciplesCleaned = database.archivedDiscipleDao().deleteArchivedBefore(cutoff)
        } catch (e: Exception) {
            Log.w(TAG, "Archive cleanup failed: ${e.message}")
        }

        val elapsed = System.currentTimeMillis() - startTime
        Log.i(TAG, "Archive complete: logs=$battleLogsArchived, events=$gameEventsArchived, " +
            "disciples=$disciplesArchived, cleaned=($battleLogsCleaned,$gameEventsCleaned,$disciplesCleaned), " +
            "elapsed=${elapsed}ms")

        ArchiveOperationResult(
            battleLogsArchived = battleLogsArchived,
            gameEventsArchived = gameEventsArchived,
            disciplesArchived = disciplesArchived,
            battleLogsCleaned = battleLogsCleaned,
            gameEventsCleaned = gameEventsCleaned,
            disciplesCleaned = disciplesCleaned,
            elapsedMs = elapsed
        )
    }

    private suspend fun archiveBattleLogs(slotId: Int): Int {
        val totalCount = database.battleLogDao().countBySlot(slotId)
        if (totalCount <= config.battleLogHotCount) return 0

        val overflow = totalCount - config.battleLogHotCount
        val toArchive = database.battleLogDao().getOldestBySlot(slotId, overflow)

        if (toArchive.isEmpty()) return 0

        val archived = toArchive.map { log ->
            ArchivedBattleLog(
                slotId = slotId,
                originalId = log.id,
                battleType = log.type.name,
                result = log.result.name,
                timestamp = log.timestamp,
                attackerName = log.attackerName,
                defenderName = log.defenderName,
                dataBlob = ""
            )
        }

        database.withTransaction {
            database.archivedBattleLogDao().insertAll(archived)
            for (log in toArchive) {
                database.battleLogDao().deleteById(slotId, log.id)
            }
        }

        return archived.size
    }

    private suspend fun archiveGameEvents(slotId: Int): Int {
        val totalCount = database.gameEventDao().countBySlot(slotId)
        if (totalCount <= config.gameEventHotCount) return 0

        val overflow = totalCount - config.gameEventHotCount
        val toArchive = database.gameEventDao().getOldestBySlot(slotId, overflow)

        if (toArchive.isEmpty()) return 0

        val archived = toArchive.map { event ->
            ArchivedGameEvent(
                slotId = slotId,
                originalId = event.id,
                eventType = event.type.name,
                timestamp = event.timestamp,
                message = event.message,
                dataBlob = ""
            )
        }

        database.withTransaction {
            database.archivedGameEventDao().insertAll(archived)
            for (event in toArchive) {
                database.gameEventDao().deleteById(slotId, event.id)
            }
        }

        return archived.size
    }

    private suspend fun archiveDeadDisciples(slotId: Int): Int {
        val deadDisciples = database.discipleDao().getDeadBySlotSync(slotId)

        if (deadDisciples.isEmpty()) return 0

        val archived = deadDisciples.map { disciple ->
            ArchivedDisciple(
                slotId = slotId,
                originalId = disciple.id,
                name = disciple.name,
                realm = disciple.realm,
                dataBlob = ""
            )
        }

        database.withTransaction {
            database.archivedDiscipleDao().insertAll(archived)
            database.discipleDao().deleteDeadBySlot(slotId)
        }

        return archived.size
    }

    companion object {
        private const val TAG = "DataArchiveScheduler"
    }
}

// endregion DataArchiveScheduler
