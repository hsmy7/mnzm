@file:Suppress("DEPRECATION", "UNCHECKED_CAST")

package com.xianxia.sect.data.engine

import android.content.Context
import android.util.Log
import com.xianxia.sect.core.model.*
import com.xianxia.sect.data.cache.CacheKey
import com.xianxia.sect.data.cache.GameDataCacheManager
import com.xianxia.sect.data.config.SaveLimitsConfig
import com.xianxia.sect.data.concurrent.SlotLockManager
import com.xianxia.sect.data.local.GameDatabase
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.result.StorageError
import com.xianxia.sect.data.result.StorageResult
import com.xianxia.sect.data.unified.SerializationHelper
import com.xianxia.sect.data.wal.WALProvider
import com.xianxia.sect.data.pipeline.AtomicSavePipeline
import com.xianxia.sect.data.incremental.IncrementalStorageManager
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
    private val eventStore: Any? = null,
    private val incrementalManager: IncrementalStorageManager? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "UnifiedStorageEngine"
        private const val MAX_BATCH_SIZE = 200
        private const val INCREMENTAL_THRESHOLD_BYTES = 50 * 1024

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

    @Volatile
    var useAtomicPipeline: Boolean = false

    // 紧急写入并发保护：防止多个emergencySave同时执行
    private val isEmergencySaving = AtomicBoolean(false)

    private val atomicSavePipeline: AtomicSavePipeline? by lazy {
        if (incrementalManager != null) {
            AtomicSavePipeline(
                context = context,
                wal = wal,
                incrementalManager = incrementalManager,
                cacheManager = cacheManager,
                engine = this,
                scope = scope
            )
        } else {
            Log.w(TAG, "AtomicSavePipeline not available: incrementalManager=$incrementalManager")
            null
        }
    }

    suspend fun save(slot: Int, data: SaveData, priority: SavePriority = SavePriority.NORMAL): StorageResult<SaveOperationStats> {
        if (!lockManager.isValidSlot(slot)) {
            return StorageResult.failure(StorageError.INVALID_SLOT, "Invalid slot: $slot")
        }

        val estimatedSize = estimateSaveSize(data)

        return lockManager.withWriteLockSuspend(slot) {
            try {
                val startTime = System.currentTimeMillis()

                if (useAtomicPipeline && atomicSavePipeline != null) {
                    Log.d(TAG, "Using atomic pipeline path for slot $slot")
                    executeAtomicSavePath(slot, data, priority)
                } else {
                    val shouldUseIncremental = estimatedSize < INCREMENTAL_THRESHOLD_BYTES && hasBaselineSnapshot(slot)

                    if (shouldUseIncremental) {
                        Log.d(TAG, "Using incremental path for slot $slot (estimated ${estimatedSize} bytes)")
                        performIncrementalSave(slot, data).also { result ->
                            if (result.isSuccess) {
                                updateCacheAfterSave(slot, data)
                                if (priority == SavePriority.CRITICAL) {
                                    createCriticalSnapshot(slot, data)
                                }
                            }
                        }
                    } else {
                        Log.d(TAG, "Using full transaction path for slot $slot (estimated ${estimatedSize} bytes)")
                        performFullTransactionSave(slot, data).also { result ->
                            if (result.isSuccess) {
                                updateCacheAfterSave(slot, data)
                                if (priority == SavePriority.CRITICAL) {
                                    createCriticalSnapshot(slot, data)
                                }
                            }
                        }
                    }.map { stats ->
                        val elapsed = System.currentTimeMillis() - startTime
                        stats.copy(timeMs = elapsed)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Save failed for slot $slot", e)
                val error = when (e) {
                    is OutOfMemoryError -> StorageError.OUT_OF_MEMORY
                    is java.io.IOException -> StorageError.IO_ERROR
                    else -> StorageError.SAVE_FAILED
                }
                StorageResult.failure(error, e.message ?: "Save failed", e)
            }
        }
    }

    private suspend fun executeAtomicSavePath(slot: Int, data: SaveData, priority: SavePriority): StorageResult<SaveOperationStats> {
        return try {
            val startTime = System.currentTimeMillis()

            _progress.value = EngineProgress(EngineProgress.Stage.SAVING_CORE, 0.1f, "Serializing data for atomic pipeline")

            val saveDataBytes = serializationHelper.serializeAndCompressSaveData(data)

            _progress.value = EngineProgress(EngineProgress.Stage.SAVING_CORE, 0.3f, "Executing atomic pipeline Phase 1")

            val pipeline = atomicSavePipeline ?: run {
                Log.e(TAG, "Atomic pipeline is null, falling back to legacy path")
                return performFullTransactionSave(slot, data)
            }

            val atomicResult = pipeline.executeAtomicSave(
                slot = slot,
                saveData = saveDataBytes,
                gameEvent = if (priority == SavePriority.CRITICAL) "CRITICAL_SAVE" else null
            )

            if (atomicResult.success) {
                _progress.value = EngineProgress(EngineProgress.Stage.UPDATING_CACHE, 0.8f, "Updating cache after atomic save")
                updateCacheAfterSave(slot, data)

                if (priority == SavePriority.CRITICAL) {
                    createCriticalSnapshot(slot, data)
                }

                _progress.value = EngineProgress(EngineProgress.Stage.COMPLETED, 1.0f, "Atomic save completed")
                StorageResult.success(SaveOperationStats(
                    bytesWritten = saveDataBytes.size.toLong(),
                    timeMs = System.currentTimeMillis() - startTime,
                    wasIncremental = false
                ))
            } else {
                Log.e(TAG, "[slot=$slot] Atomic pipeline failed at phase=${atomicResult.phase}, error=${atomicResult.error}")
                _progress.value = EngineProgress(EngineProgress.Stage.FAILED, 0f, atomicResult.error ?: "Atomic save failed")
                StorageResult.failure(StorageError.SAVE_FAILED, atomicResult.error ?: "Atomic save failed")
            }

        } catch (e: Exception) {
            Log.e(TAG, "[slot=$slot] Atomic save path exception", e)
            _progress.value = EngineProgress(EngineProgress.Stage.FAILED, 0f, e.message ?: "Unknown error in atomic path")
            StorageResult.failure(StorageError.SAVE_FAILED, e.message ?: "Atomic path failed", e)
        }
    }

    suspend fun load(slot: Int): StorageResult<SaveData> {
        if (!lockManager.isValidSlot(slot)) {
            return StorageResult.failure(StorageError.INVALID_SLOT, "Invalid slot: $slot")
        }

        return lockManager.withReadLockSuspend(slot) {
            try {
                _progress.value = EngineProgress(EngineProgress.Stage.SAVING_CORE, 0.1f, "Loading from cache")

                val cachedData = loadFromCache(slot)
                if (cachedData != null) {
                    Log.d(TAG, "Cache hit for slot $slot")
                    _progress.value = EngineProgress(EngineProgress.Stage.COMPLETED, 1.0f, "Load completed (cache)")
                    return@withReadLockSuspend StorageResult.success(cachedData)
                }

                _progress.value = EngineProgress(EngineProgress.Stage.SAVING_CORE, 0.2f, "Loading from database")
                val dbData = loadFromDatabase(slot)

                if (dbData != null) {
                    // 先使旧缓存失效，再更新为新数据，确保一致性
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

        return lockManager.withWriteLockSuspend(slot) {
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

                // 事务成功后再次确保缓存完全失效（防御性编程）
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

        return lockManager.withReadLockSuspend(slot) {
            try {
                val loadResult = load(slot)
                if (loadResult.isFailure) {
                    return@withReadLockSuspend StorageResult.failure(
                        StorageError.LOAD_FAILED,
                        "Failed to load data for export: ${loadResult.getOrNull()}"
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

        // 并发保护：如果已有紧急写入在进行中，直接返回失败
        if (!isEmergencySaving.compareAndSet(false, true)) {
            Log.w(TAG, "Emergency save already in progress, skipping duplicate request")
            return StorageResult.failure(StorageError.SAVE_FAILED, "Emergency save already in progress")
        }

        return try {
            lockManager.withWriteLockSuspend(emergencySlot) {
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
            // 确保并发保护标志被清除（无论成功或失败）
            isEmergencySaving.set(false)
        }
    }

    fun shutdown() {
        atomicSavePipeline?.shutdown()
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

        data.gameData.forgeSlots?.map { it.copy(slotId = slot) }?.let { slots ->
            database.buildingSlotDao().insertAll(slots)
        }
        data.gameData.alchemySlots?.map { it.copy(slotId = slot) }?.let { slots ->
            database.alchemySlotDao().insertAll(slots)
        }
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

    private suspend fun performIncrementalSave(slot: Int, data: SaveData): StorageResult<SaveOperationStats> {
        val baseData = loadFromDatabase(slot)
        if (baseData == null) {
            Log.w(TAG, "No baseline for incremental save, falling back to full save")
            return performFullTransactionSave(slot, data)
        }

        val changes = computeDelta(baseData, data)
        if (changes.isEmpty()) {
            Log.d(TAG, "No changes detected for incremental save")
            return StorageResult.success(SaveOperationStats(
                bytesWritten = 0,
                timeMs = 0,
                wasIncremental = true
            ))
        }

        database.withTransaction {
            applyDeltaToDatabase(slot, changes)
        }

        val bytesWritten = estimateSaveSize(data)

        return StorageResult.success(SaveOperationStats(
            bytesWritten = bytesWritten,
            timeMs = 0,
            wasIncremental = true
        ))
    }

    private fun computeDelta(baseData: SaveData, newData: SaveData): Map<String, Any> {
        val changes = mutableMapOf<String, Any>()

        if (baseData.gameData != newData.gameData) {
            changes["gameData"] = newData.gameData
        }

        if (baseData.disciples != newData.disciples) {
            changes["disciples"] = newData.disciples
        }

        if (baseData.equipment != newData.equipment) {
            changes["equipment"] = newData.equipment
        }

        if (baseData.manuals != newData.manuals) {
            changes["manuals"] = newData.manuals
        }

        if (baseData.pills != newData.pills) {
            changes["pills"] = newData.pills
        }

        if (baseData.materials != newData.materials) {
            changes["materials"] = newData.materials
        }

        if (baseData.herbs != newData.herbs) {
            changes["herbs"] = newData.herbs
        }

        if (baseData.seeds != newData.seeds) {
            changes["seeds"] = newData.seeds
        }

        if (baseData.teams != newData.teams) {
            changes["teams"] = newData.teams
        }

        if (baseData.events != newData.events) {
            changes["events"] = newData.events
        }

        if (baseData.battleLogs != newData.battleLogs) {
            changes["battleLogs"] = newData.battleLogs
        }

        return changes
    }

    private suspend fun applyDeltaToDatabase(slot: Int, changes: Map<String, Any>) {
        changes["gameData"]?.let {
            val gameDataWithSlot = (it as GameData).copy(slotId = slot, id = "game_data_$slot")
            database.gameDataDao().insert(gameDataWithSlot)
        }

        @Suppress("UNCHECKED_CAST")
        val disciples = changes["disciples"] as? List<*>
        if (disciples != null) {
            database.discipleDao().deleteAll(slot)
            (disciples as List<Disciple>).chunked(MAX_BATCH_SIZE).forEach { batch ->
                database.discipleDao().insertAll(batch.map { d -> d.copy(slotId = slot) })
            }
        }

        @Suppress("UNCHECKED_CAST")
        val equipment = changes["equipment"] as? List<*>
        if (equipment != null) {
            database.equipmentDao().deleteAll(slot)
            (equipment as List<Equipment>).chunked(MAX_BATCH_SIZE).forEach { batch ->
                database.equipmentDao().insertAll(batch.map { e -> e.copy(slotId = slot) })
            }
        }

        @Suppress("UNCHECKED_CAST")
        val manuals = changes["manuals"] as? List<*>
        if (manuals != null) {
            database.manualDao().deleteAll(slot)
            (manuals as List<Manual>).chunked(MAX_BATCH_SIZE).forEach { batch ->
                database.manualDao().insertAll(batch.map { m -> m.copy(slotId = slot) })
            }
        }

        @Suppress("UNCHECKED_CAST")
        val pills = changes["pills"] as? List<*>
        if (pills != null) {
            database.pillDao().deleteAll(slot)
            (pills as List<Pill>).chunked(MAX_BATCH_SIZE).forEach { batch ->
                database.pillDao().insertAll(batch.map { p -> p.copy(slotId = slot) })
            }
        }

        @Suppress("UNCHECKED_CAST")
        val materials = changes["materials"] as? List<*>
        if (materials != null) {
            database.materialDao().deleteAll(slot)
            (materials as List<Material>).chunked(MAX_BATCH_SIZE).forEach { batch ->
                database.materialDao().insertAll(batch.map { m -> m.copy(slotId = slot) })
            }
        }

        @Suppress("UNCHECKED_CAST")
        val herbs = changes["herbs"] as? List<*>
        if (herbs != null) {
            database.herbDao().deleteAll(slot)
            (herbs as List<Herb>).chunked(MAX_BATCH_SIZE).forEach { batch ->
                database.herbDao().insertAll(batch.map { h -> h.copy(slotId = slot) })
            }
        }

        @Suppress("UNCHECKED_CAST")
        val seeds = changes["seeds"] as? List<*>
        if (seeds != null) {
            database.seedDao().deleteAll(slot)
            (seeds as List<Seed>).chunked(MAX_BATCH_SIZE).forEach { batch ->
                database.seedDao().insertAll(batch.map { s -> s.copy(slotId = slot) })
            }
        }

        @Suppress("UNCHECKED_CAST")
        val teams = changes["teams"] as? List<*>
        if (teams != null) {
            database.explorationTeamDao().deleteAll(slot)
            (teams as List<ExplorationTeam>).chunked(MAX_BATCH_SIZE).forEach { batch ->
                database.explorationTeamDao().insertAll(batch.map { t -> t.copy(slotId = slot) })
            }
        }

        @Suppress("UNCHECKED_CAST")
        val events = changes["events"] as? List<*>
        if (events != null) {
            database.gameEventDao().deleteAll(slot)
            (events as List<GameEvent>).chunked(MAX_BATCH_SIZE).forEach { batch ->
                database.gameEventDao().insertAll(batch.map { e -> e.copy(slotId = slot) })
            }
        }

        @Suppress("UNCHECKED_CAST")
        val battleLogs = changes["battleLogs"] as? List<*>
        if (battleLogs != null) {
            database.battleLogDao().deleteAll(slot)
            (battleLogs as List<BattleLog>).chunked(MAX_BATCH_SIZE).forEach { batch ->
                database.battleLogDao().insertAll(batch.map { b -> b.copy(slotId = slot) })
            }
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

    private suspend fun hasBaselineSnapshot(slot: Int): Boolean {
        return try {
            database.gameDataDao().getGameDataSync(slot) != null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check baseline for slot $slot", e)
            false
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

    /**
     * 直接写入数据到 Room Database（供 AtomicSavePipeline 调用）
     *
     * 【P0#2 修复】此方法专门为 AtomicSavePipeline.phase2Commit() 提供，
     * 允许 pipeline 在 commit 阶段将数据写入 Room Database，
     * 解决 atomic save 不写 DB 导致加载失败的问题。
     *
     * 与 save() 不同，此方法：
     * - 不触发缓存更新（由 pipeline 统一管理）
     * - 不创建快照（由 WAL 子系统管理）
     * - 不更新进度（由 pipeline 管理）
     *
     * @param slot 目标槽位
     * @param data 要写入的存档数据
     * @return true 表示写入成功
     */
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
