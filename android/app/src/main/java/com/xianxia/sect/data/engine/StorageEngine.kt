package com.xianxia.sect.data.engine

import android.content.Context
import android.util.Log
import com.xianxia.sect.core.state.fixStorageBagReferences
import com.xianxia.sect.core.model.*
import com.xianxia.sect.data.archive.DataArchiver
import com.xianxia.sect.data.cache.CacheLayer
import com.xianxia.sect.data.cache.CacheKey
import com.xianxia.sect.data.config.SaveLimitsConfig
import com.xianxia.sect.data.concurrent.SlotLockManager
import com.xianxia.sect.data.crypto.CryptoModule
import com.xianxia.sect.data.crypto.IntegrityReport
import com.xianxia.sect.data.crypto.IntegrityValidator
import com.xianxia.sect.data.incremental.ChangeLogOperation
import com.xianxia.sect.data.incremental.ChangeLogPersistence
import com.xianxia.sect.data.local.GameDatabase
import com.xianxia.sect.data.local.SaveSlotMetadata
import com.xianxia.sect.data.memory.DynamicMemoryManager
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.model.SaveSlot
import com.xianxia.sect.data.result.StorageError
import com.xianxia.sect.data.result.StorageResult
import com.xianxia.sect.data.StorageConstants
import com.xianxia.sect.data.unified.BackupInfo
import com.xianxia.sect.data.unified.BackupManager
import com.xianxia.sect.data.unified.MetadataManager
import com.xianxia.sect.data.serialization.unified.SerializationModule
import com.xianxia.sect.data.unified.SlotMetadata
import com.xianxia.sect.data.validation.StorageValidator
import com.xianxia.sect.data.wal.WALProvider
import com.xianxia.sect.di.ApplicationScopeProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.room.withTransaction
import java.io.File
import java.io.FileOutputStream
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
class StorageEngine @Inject internal constructor(
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
    private val memoryManager: DynamicMemoryManager,
    private val metadataManager: MetadataManager,
    private val applicationScopeProvider: ApplicationScopeProvider,
    private val circuitBreaker: StorageCircuitBreaker,
    private val pruningScheduler: DataPruningScheduler,
    private val archiveScheduler: DataArchiveScheduler,
    private val memoryGuard: ProactiveMemoryGuard
) {
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

    private val scope get() = applicationScopeProvider.ioScope

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
                    database.saveSlotMetadataDao().deleteBySlotId(slot)
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
                val saveData = database.withTransaction {
                    val gameData = database.gameDataDao().getGameDataSync(slot)
                        ?: return@withTransaction null

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
                    val productionSlots = database.productionSlotDao().getBySlotSync(slot)

                    SaveData(
                        gameData = gameData,
                        disciples = disciples,
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
                    )
                } ?: return@withReadLockLight StorageResult.failure(StorageError.SLOT_EMPTY, "No data in slot $slot")

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
            val saveData = loadFromDatabase(slot)
                ?: return StorageResult.failure(StorageError.SLOT_EMPTY, "No data found for slot $slot")

            val key = crypto.getOrCreateKey(context)
            val currentDataHash = crypto.computeFullDataSignature(saveData, key)
            val currentMerkleRoot = crypto.computeMerkleRoot(saveData)

            val storedMetadata = metadataManager.loadSlotMetadata(slot)
            val errors = mutableListOf<String>()

            val signatureValid = if (storedMetadata != null && storedMetadata.checksum.isNotEmpty()) {
                val result = IntegrityValidator.verifyFullDataSignature(saveData, storedMetadata.checksum, key)
                if (!result) errors.add("Signature verification failed")
                result
            } else {
                null
            }

            val hashValid = if (storedMetadata != null && storedMetadata.dataHash.isNotEmpty()) {
                val result = constantTimeEquals(currentDataHash, storedMetadata.dataHash)
                if (!result) errors.add("Data hash mismatch")
                result
            } else {
                null
            }

            val merkleValid = if (storedMetadata != null && storedMetadata.merkleRoot.isNotEmpty()) {
                val result = constantTimeEquals(currentMerkleRoot, storedMetadata.merkleRoot)
                if (!result) errors.add("Merkle root mismatch")
                result
            } else {
                null
            }

            val isValid = signatureValid != false && hashValid != false && merkleValid != false

            StorageResult.success(IntegrityReport(
                isValid = isValid,
                dataHash = currentDataHash,
                merkleRoot = currentMerkleRoot,
                signatureValid = signatureValid ?: true,
                hashValid = hashValid ?: true,
                merkleValid = merkleValid ?: true,
                errors = errors
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

        try {
            database.performPostSaveCheckpoint()
        } catch (e: Exception) {
            Log.w(TAG, "Post-save checkpoint failed for slot $slot (non-fatal)", e)
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
            database.withTransaction {
                loadFromDatabaseInternal(slot)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load from database for slot $slot", e)
            null
        }
    }

    private suspend fun loadFromDatabaseInternal(slot: Int): SaveData? {
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

        return SaveData(
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

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
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
