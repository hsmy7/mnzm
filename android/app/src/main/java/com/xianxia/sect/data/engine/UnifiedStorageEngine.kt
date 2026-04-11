@file:Suppress("UNCHECKED_CAST")

package com.xianxia.sect.data.engine

import android.content.Context
import android.util.Log
import com.xianxia.sect.core.model.*
import com.xianxia.sect.data.cache.CacheKey
import com.xianxia.sect.data.cache.GameDataCacheManager
import com.xianxia.sect.data.config.SaveLimitsConfig
import com.xianxia.sect.data.concurrent.SlotLockManager
import com.xianxia.sect.data.incremental.ChangeLogPersistence
import com.xianxia.sect.data.incremental.ChangeLogOperation
import com.xianxia.sect.data.local.GameDatabase
import com.xianxia.sect.data.local.SaveSlotMetadata
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.result.StorageError
import com.xianxia.sect.data.result.StorageResult
import com.xianxia.sect.data.unified.SerializationHelper
import com.xianxia.sect.data.wal.WALProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import androidx.room.withTransaction
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

data class SaveOperationStats(
    val bytesWritten: Long = 0,
    val timeMs: Long = 0,
    val wasIncremental: Boolean = false
)

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

@Singleton
class UnifiedStorageEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: GameDatabase,
    private val cacheManager: GameDataCacheManager,
    private val lockManager: SlotLockManager,
    private val wal: WALProvider,
    private val saveLimitsConfig: SaveLimitsConfig,
    private val serializationHelper: SerializationHelper,
    private val changeLogPersistence: ChangeLogPersistence,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "UnifiedStorageEngine"
        private const val MAX_BATCH_SIZE = 200

        fun estimateSaveSize(data: SaveData): Long {
            val discipleBytesPerEntity = 800L
            val equipmentBytesPerEntity = 350L
            val manualBytesPerEntity = 280L
            val pillBytesPerEntity = 180L
            val materialBytesPerEntity = 150L
            val herbBytesPerEntity = 160L
            val seedBytesPerEntity = 140L
            val battleLogBytesPerEntity = 600L
            val eventBytesPerEntity = 250L
            val teamBytesPerEntity = 450L

            var size = 2000L

            size += data.disciples.size * discipleBytesPerEntity
            size += data.equipment.size * equipmentBytesPerEntity
            size += data.manuals.size * manualBytesPerEntity
            size += data.pills.size * pillBytesPerEntity
            size += data.materials.size * materialBytesPerEntity
            size += data.herbs.size * herbBytesPerEntity
            size += data.seeds.size * seedBytesPerEntity
            size += data.battleLogs.size * battleLogBytesPerEntity
            size += data.events.size * eventBytesPerEntity
            size += data.teams.size * teamBytesPerEntity
            size += data.alliances.size * 300L

            val serializationOverhead = (size * 0.15).toLong()
            size += serializationOverhead

            return size
        }
    }

    private val _progress = MutableStateFlow(EngineProgress(EngineProgress.Stage.IDLE, 0f))
    val progress: StateFlow<EngineProgress> = _progress.asStateFlow()

    private val isEmergencySaving = AtomicBoolean(false)

    suspend fun save(slot: Int, data: SaveData, priority: SavePriority = SavePriority.NORMAL): StorageResult<SaveOperationStats> {
        if (!lockManager.isValidSlot(slot)) {
            return StorageResult.failure(StorageError.INVALID_SLOT, "Invalid slot: $slot")
        }

        return lockManager.withWriteLockLight(slot) {
            try {
                val startTime = System.currentTimeMillis()

                _progress.value = EngineProgress(EngineProgress.Stage.SAVING_CORE, 0.1f, "Saving core data")

                val result = performFullTransactionSave(slot, data)

                if (result.isSuccess) {
                    updateCacheAfterSave(slot, data)
                    logSaveChanges(slot, data)

                    if (priority == SavePriority.CRITICAL) {
                        createCriticalSnapshot(slot, data)
                    }

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
                    Log.d(TAG, "Cache hit for slot $slot")
                    _progress.value = EngineProgress(EngineProgress.Stage.COMPLETED, 1.0f, "Load completed (cache)")
                    return@withReadLockLight StorageResult.success(cachedData)
                }

                _progress.value = EngineProgress(EngineProgress.Stage.SAVING_CORE, 0.2f, "Loading from database")
                val dbData = loadFromDatabase(slot)

                if (dbData != null) {
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
                    database.equipmentDao().deleteAll(slot)
                    database.manualDao().deleteAll(slot)
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

                Log.i(TAG, "Deleted all data for slot $slot")
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
                val saveDataBytes = serializationHelper.serializeAndCompressSaveData(saveData)

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
        val emergencySlot = SlotLockManager.EMERGENCY_SLOT

        if (!isEmergencySaving.compareAndSet(false, true)) {
            Log.w(TAG, "Emergency save already in progress, skipping duplicate request")
            return StorageResult.failure(StorageError.SAVE_FAILED, "Emergency save already in progress")
        }

        return try {
            lockManager.withWriteLockLight(emergencySlot) {
                try {
                    val startTime = System.currentTimeMillis()

                    database.withTransaction {
                        writeAllDataToDatabase(emergencySlot, data)
                    }

                    val elapsed = System.currentTimeMillis() - startTime
                    Log.i(TAG, "Emergency save completed in ${elapsed}ms")

                    StorageResult.success(SaveOperationStats(
                        bytesWritten = estimateSaveSize(data),
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

    fun shutdown() {
        cacheManager.shutdown()
        wal.shutdown()
        lockManager.shutdown()
        (scope.coroutineContext[Job] as? Job)?.cancel()
        Log.i(TAG, "UnifiedStorageEngine shutdown completed")
    }

    private suspend fun writeAllDataToDatabase(slot: Int, data: SaveData) {
        val gameDataWithSlot = data.gameData.copy(slotId = slot, id = "game_data_$slot")
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

        database.equipmentDao().deleteAll(slot)
        database.manualDao().deleteAll(slot)
        database.pillDao().deleteAll(slot)
        database.materialDao().deleteAll(slot)
        database.herbDao().deleteAll(slot)
        database.seedDao().deleteAll(slot)

        data.equipment.chunked(MAX_BATCH_SIZE).forEach { database.equipmentDao().insertAll(it.map { e -> e.copy(slotId = slot) }) }
        data.manuals.chunked(MAX_BATCH_SIZE).forEach { database.manualDao().insertAll(it.map { m -> m.copy(slotId = slot) }) }
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
            lastSaveTime = gd.lastSaveTime,
            discipleCount = data.disciples.count { it.isAlive }
        )
        database.saveSlotMetadataDao().upsert(metadata)
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
            cacheManager.getOrNull(gameDataKey) as? SaveData
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load from cache for slot $slot", e)
            null
        }
    }

    private suspend fun loadFromDatabase(slot: Int): SaveData? {
        return try {
            val gameData = database.gameDataDao().getGameDataSync(slot) ?: return null

            val disciples = database.discipleDao().getAllAliveSync(slot)
            val equipment = database.equipmentDao().getAllSync(slot)
            val manuals = database.manualDao().getAllSync(slot)
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
                equipment = equipment,
                manuals = manuals,
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load from database for slot $slot", e)
            null
        }
    }

    private fun updateCacheAfterSave(slot: Int, data: SaveData) {
        try {
            val cacheKey = CacheKey.forGameData(slot)
            cacheManager.putWithoutTracking(cacheKey, data)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update cache for slot $slot", e)
        }
    }

    private fun clearCacheForSlot(slot: Int) {
        try {
            val cacheKey = CacheKey.forGameData(slot)
            cacheManager.remove(cacheKey)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear cache for slot $slot", e)
        }
    }

    private suspend fun createCriticalSnapshot(slot: Int, data: SaveData) {
        try {
            val snapshotData = serializationHelper.serializeAndCompressSaveData(data)
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

    internal suspend fun writeDataToDatabaseDirectly(slot: Int, data: SaveData): Boolean {
        return try {
            database.withTransaction {
                writeAllDataToDatabase(slot, data)
            }
            Log.d(TAG, "Direct DB write succeeded for slot $slot")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Direct DB write failed for slot $slot", e)
            false
        }
    }
}

enum class SavePriority {
    NORMAL,
    HIGH,
    CRITICAL
}
