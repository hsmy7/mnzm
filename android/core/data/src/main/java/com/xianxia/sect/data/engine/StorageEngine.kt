package com.xianxia.sect.data.engine

import android.util.Log
import com.xianxia.sect.core.state.GameStateSnapshotProvider
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.util.fixStorageBagReferences
import com.xianxia.sect.data.GameStateRepository
import com.xianxia.sect.data.archive.DataArchiver
import com.xianxia.sect.data.cache.CacheKey
import com.xianxia.sect.data.config.SaveLimitsConfig
import com.xianxia.sect.data.incremental.ChangeLogOperation
import com.xianxia.sect.data.local.ProtobufConverters
import com.xianxia.sect.data.local.SaveSlotMetadata
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.model.SaveSlot
import com.xianxia.sect.data.result.StorageError
import com.xianxia.sect.data.result.StorageResult
import com.xianxia.sect.data.StorageConstants
import com.xianxia.sect.data.unified.SlotMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.Immutable
import androidx.room.withTransaction
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

@Immutable
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
    private val core: StorageCoreFacade,
    private val saveLimitsConfig: SaveLimitsConfig,
    private val dataArchiver: DataArchiver,
    private val infra: StorageInfraFacade,
    private val maintenanceFacade: StorageMaintenanceFacade,
    private val stateStore: GameStateSnapshotProvider,
    private val repository: GameStateRepository
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
            size += data.teams.size * es.TEAM
            size += data.alliances.size * es.ALLIANCE

            val serializationOverhead = (size * StorageConstants.ESTIMATE_SERIALIZATION_OVERHEAD_RATIO).toLong()
            size += serializationOverhead

            return size
        }
    }

    private val scope get() = infra.scopeProvider.ioScope

    private val _progress = MutableStateFlow(EngineProgress(EngineProgress.Stage.IDLE, 0f))
    val progress: StateFlow<EngineProgress> = _progress.asStateFlow()

    private val _currentSlot = MutableStateFlow(1)
    val currentSlot: StateFlow<Int> = _currentSlot.asStateFlow()

    suspend fun save(slot: Int, data: SaveData, priority: SavePriority = SavePriority.NORMAL): StorageResult<SaveOperationStats> {
        if (!core.lockManager.isValidSlot(slot)) {
            return StorageResult.failure(StorageError.INVALID_SLOT, "Invalid slot: $slot")
        }

        return core.lockManager.withWriteLockLight(slot) {
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

                    infra.storageMetrics.recordSave()
                    _progress.value = EngineProgress(EngineProgress.Stage.COMPLETED, 1.0f, "Save completed")
                }

                result.map { stats ->
                    val elapsed = System.currentTimeMillis() - startTime
                    stats.copy(timeMs = elapsed)
                }
            } catch (e: CancellationException) {
                throw e
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
        if (!core.lockManager.isValidSlot(slot)) {
            return StorageResult.failure(StorageError.INVALID_SLOT, "Invalid slot: $slot")
        }

        return core.lockManager.withReadLockLight(slot) {
            try {
                _progress.value = EngineProgress(EngineProgress.Stage.SAVING_CORE, 0.1f, "Loading from cache")

                val cachedData = loadFromCache(slot)
                if (cachedData != null) {
                    infra.storageMetrics.recordCacheHit()
                    infra.storageMetrics.recordLoad()
                    Log.d(TAG, "Cache hit for slot $slot")
                    _progress.value = EngineProgress(EngineProgress.Stage.COMPLETED, 1.0f, "Load completed (cache)")
                    return@withReadLockLight StorageResult.success(cachedData)
                }

                infra.storageMetrics.recordCacheMiss()
                _progress.value = EngineProgress(EngineProgress.Stage.SAVING_CORE, 0.2f, "Loading from database")
                val dbData = loadFromDatabase(slot)

                if (dbData != null) {
                    infra.storageMetrics.recordLoad()
                    clearCacheForSlot(slot)
                    updateCacheAfterSave(slot, dbData)
                    _progress.value = EngineProgress(EngineProgress.Stage.COMPLETED, 1.0f, "Load completed (database)")
                    StorageResult.success(dbData)
                } else {
                    _progress.value = EngineProgress(EngineProgress.Stage.FAILED, 0f, "No data found")
                    StorageResult.failure(StorageError.SLOT_EMPTY, "No data in slot $slot")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Load failed for slot $slot", e)
                _progress.value = EngineProgress(EngineProgress.Stage.FAILED, 0f, e.message ?: "Unknown error")
                StorageResult.failure(StorageError.LOAD_FAILED, e.message ?: "Load failed", e)
            }
        }
    }

    suspend fun delete(slot: Int): StorageResult<Unit> {
        if (!core.lockManager.isValidSlot(slot)) {
            return StorageResult.failure(StorageError.INVALID_SLOT, "Invalid slot: $slot")
        }

        val isAutoSave = StorageConstants.isAutoSaveSlot(slot)
        Log.i(TAG, "Deleting slot $slot (isAutoSave=$isAutoSave)")

        return core.lockManager.withWriteLockLight(slot) {
            try {
                clearCacheForSlot(slot)

                core.database.withTransaction {
                    core.database.gameDataDao().deleteAll(slot)
                    core.database.discipleDao().deleteAll(slot)
                    core.database.discipleCoreDao().deleteAll(slot)
                    core.database.discipleCombatStatsDao().deleteAll(slot)
                    core.database.discipleEquipmentDao().deleteAll(slot)
                    core.database.discipleExtendedDao().deleteAll(slot)
                    core.database.discipleAttributesDao().deleteAll(slot)
                    core.database.equipmentStackDao().deleteAll(slot)
                    core.database.equipmentInstanceDao().deleteAll(slot)
                    core.database.manualStackDao().deleteAll(slot)
                    core.database.manualInstanceDao().deleteAll(slot)
                    core.database.pillDao().deleteAll(slot)
                    core.database.materialDao().deleteAll(slot)
                    core.database.seedDao().deleteAll(slot)
                    core.database.herbDao().deleteAll(slot)
                    core.database.explorationTeamDao().deleteAll(slot)
                    core.database.buildingSlotDao().deleteAll(slot)
                    core.database.recipeDao().deleteAll(slot)
                    core.database.forgeSlotDao().deleteAll(slot)
                    core.database.alchemySlotDao().deleteAll(slot)
                    core.database.productionSlotDao().deleteBySlot(slot)
                    core.database.battleLogDao().deleteAll(slot)
                    core.database.mailDao().deleteAllForSlot(slot)
                    core.database.saveSlotMetadataDao().deleteBySlotId(slot)
                    core.database.storageBagDao().deleteAll(slot)
                    core.database.gameHeavyDataDao().deleteAllForSlot(slot)
                    core.database.diplomacyStateDao().deleteBySlot(slot)
                    core.database.productionStateDao().deleteBySlot(slot)
                    core.database.patrolStateDao().deleteBySlot(slot)
                    core.database.worldMapStateDao().deleteBySlot(slot)
                    core.database.sectPolicyStateDao().deleteBySlot(slot)
                    core.database.discipleCompactDao().deleteAll(slot)
                }

                clearCacheForSlot(slot)

                Log.i(TAG, "Deleted all data for slot $slot (isAutoSave=$isAutoSave)")
                StorageResult.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Delete failed for slot $slot", e)
                StorageResult.failure(StorageError.DELETE_FAILED, e.message ?: "Delete failed", e)
            }
        }
    }

    suspend fun hasData(slot: Int): Boolean {
        if (!core.lockManager.isValidSlot(slot)) return false

        return try {
            core.database.gameDataDao().existsBySlot(slot) != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "hasData check failed for slot $slot", e)
            false
        }
    }

    suspend fun getSlotMetadata(slot: Int): SlotMetadata? {
        if (!core.lockManager.isValidSlot(slot)) return null

        return try {
            val meta = core.database.gameDataDao().getMetadataBySlot(slot) ?: return null
            SlotMetadata(
                slot = slot,
                timestamp = meta.lastSaveTime,
                gameYear = meta.gameYear,
                gameMonth = meta.gameMonth,
                sectName = meta.sectName,
                discipleCount = core.database.discipleDao().getAllAliveSync(slot).size,
                spiritStones = meta.spiritStones,
                fileSize = 0,
                customName = meta.sectName
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "getSlotMetadata failed for slot $slot", e)
            null
        }
    }

    suspend fun listSlots(): StorageResult<List<SlotMetadata>> {
        return try {
            val slots = (1..core.lockManager.getMaxSlots()).mapNotNull { slot ->
                getSlotMetadata(slot)
            }
            StorageResult.success(slots)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "listSlots failed", e)
            StorageResult.failure(StorageError.LOAD_FAILED, e.message ?: "Failed to list slots")
        }
    }

    suspend fun getSaveSlots(): List<SaveSlot> {
        val slots = mutableListOf<SaveSlot>()

        try {
            slots.add(querySingleSlot(StorageConstants.AUTO_SAVE_SLOT))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query auto-save slot, using empty placeholder", e)
            slots.add(SaveSlot(StorageConstants.AUTO_SAVE_SLOT, "", 0, 1, 1, "", 0, 0, true, isAutoSave = true))
        }

        for (slot in 1..core.lockManager.getMaxSlots()) {
            try {
                slots.add(querySingleSlot(slot))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to query slot $slot, using empty placeholder", e)
                slots.add(SaveSlot(slot, "", 0, 1, 1, "", 0, 0, true))
            }
        }

        return slots
    }

    fun setCurrentSlot(slot: Int) {
        if (core.lockManager.isValidSlot(slot)) {
            _currentSlot.value = slot
        }
    }

    fun getCurrentSlot(): Int = _currentSlot.value

    suspend fun incrementalSave(slot: Int): StorageResult<SaveOperationStats> {
        if (!core.lockManager.isValidSlot(slot)) {
            return StorageResult.failure(StorageError.INVALID_SLOT, "Invalid slot: $slot")
        }

        return core.lockManager.withWriteLockLight(slot) {
            try {
                val startTime = System.currentTimeMillis()
                _progress.value = EngineProgress(EngineProgress.Stage.SAVING_CORE, 0.1f, "Incremental save")

                // 读取独立快照属性，避免触发 17-way combine
                val gameData = stateStore.gameDataSnapshot
                val disciples = stateStore.disciplesSnapshot
                val equipmentStacks = stateStore.equipmentStacksSnapshot
                val equipmentInstances = stateStore.equipmentInstancesSnapshot
                val manualStacks = stateStore.manualStacksSnapshot
                val manualInstances = stateStore.manualInstancesSnapshot
                val pills = stateStore.pillsSnapshot
                val materials = stateStore.materialsSnapshot
                val herbs = stateStore.herbsSnapshot
                val seeds = stateStore.seedsSnapshot
                val storageBags = stateStore.storageBagsSnapshot
                val teams = stateStore.teamsSnapshot
                val battleLogs = stateStore.battleLogsSnapshot

                repository.setActiveSlot(slot)

                val gameDataWithTimestamp = gameData.copy(lastSaveTime = System.currentTimeMillis())

                core.database.withTransaction {
                    repository.flushDirtyState(
                        gameData = gameDataWithTimestamp,
                        disciples = disciples,
                        equipmentStacks = equipmentStacks,
                        equipmentInstances = equipmentInstances,
                        manualStacks = manualStacks,
                        manualInstances = manualInstances,
                        pills = pills,
                        materials = materials,
                        herbs = herbs,
                        seeds = seeds,
                        storageBags = storageBags,
                        teams = teams,
                        battleLogs = battleLogs
                    )
                }

                // 增量存档后清理缓存，确保下次 load() 从 DB 读取最新数据
                // 而非返回初始完整存档时的陈旧缓存
                clearCacheForSlot(slot)

                val elapsed = System.currentTimeMillis() - startTime
                _progress.value = EngineProgress(EngineProgress.Stage.COMPLETED, 1.0f, "Incremental save completed")
                StorageResult.success(SaveOperationStats(
                    bytesWritten = 0,
                    timeMs = elapsed,
                    wasIncremental = true
                ))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Incremental save failed for slot $slot", e)
                _progress.value = EngineProgress(EngineProgress.Stage.FAILED, 0f, e.message ?: "Unknown error")
                StorageResult.failure(StorageError.SAVE_FAILED, e.message ?: "Incremental save failed", e)
            }
        }
    }

    fun startMaintenance() {
        maintenanceFacade.startMaintenance()
        Log.i(TAG, "Storage maintenance started")
    }

    fun stopMaintenance() {
        maintenanceFacade.stopMaintenance()
        Log.i(TAG, "Storage maintenance stopped")
    }

    fun shutdown() {
        maintenanceFacade.shutdown()
        core.cache.shutdown()
        core.wal.shutdown()
        core.lockManager.shutdown()
        Log.i(TAG, "StorageEngine shutdown completed")
    }

    private suspend fun cleanSaveDataWithArchive(data: SaveData): SaveData {
        val maxBattleLogs = saveLimitsConfig.maxBattleLogs

        val cleanedBattleLogs = if (data.battleLogs.size > maxBattleLogs) {
            val archiveResult = dataArchiver.archiveBattleLogsIfNeeded(data.battleLogs, maxBattleLogs)
            if (archiveResult.success && archiveResult.archivedCount > 0) {
                Log.i(TAG, "Archived ${archiveResult.archivedCount} battle logs")
            }
            dataArchiver.getRetainedBattleLogs(data.battleLogs, maxBattleLogs)
        } else {
            data.battleLogs
        }

        return data.copy(battleLogs = cleanedBattleLogs)
    }

    private suspend fun performFullTransactionSave(slot: Int, data: SaveData): StorageResult<SaveOperationStats> {
        core.database.withTransaction {
            writeAllDataToDatabase(slot, data)
        }

        try {
            core.database.performPostSaveCheckpoint()
        } catch (e: CancellationException) {
            throw e
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
        Log.d(TAG, "writeAllDataToDatabase: slot=$slot, " +
            "${data.disciples.size} disciples, " +
            "recruitList=${data.gameData.recruitList.size} unrecruited")

        // ── 内存守卫 ──
        val runtime = Runtime.getRuntime()
        val maxMem = runtime.maxMemory()
        val usedMem = runtime.totalMemory() - runtime.freeMemory()
        val availableMem = maxMem - usedMem
        if (availableMem < 100L * 1024 * 1024) {
            Log.w(TAG, "Low memory (${availableMem / 1024 / 1024}MB available), " +
                "skipping auto-save for slot $slot")
            return
        }

        // ── 存档前数据完整性校验 ──
        if (data.gameData.worldMapSects.isEmpty()) {
            Log.e(TAG, "存档前检测到 worldMapSects 为空 slot=$slot — 数据管线异常，" +
                "世界地图宗门数据已丢失。请检查 ensureHeavyDataLoaded 日志。")
        }
        if (data.gameData.sectName.isBlank()) {
            Log.w(TAG, "存档前检测到 sectName 为空 slot=$slot")
        }

        val now = System.currentTimeMillis()
        val heavyDao = core.database.gameHeavyDataDao()

        // ── 清除旧重型数据（按前缀批量删除）──
        val allPrefixes = listOf(
            GameHeavyData.KEY_AI_SECT_DISCIPLES,
            GameHeavyData.KEY_SECT_DETAILS,
            GameHeavyData.KEY_EXPLORED_SECTS,
            GameHeavyData.KEY_SCOUT_INFO,
            GameHeavyData.KEY_MANUAL_PROFICIENCIES,
            GameHeavyData.KEY_RECRUIT_LIST,
            GameHeavyData.KEY_WORLD_MAP_SECTS
        )
        for (prefix in allPrefixes) {
            heavyDao.deleteByKeyPrefix(slot, prefix)
        }

        // ── 增量编码写入（每项编码完立即写入，立即释放 ByteArray）──
        ProtobufConverters.encodeDiscipleListMapIncremental(
            data.gameData.aiSectDisciples, slot, GameHeavyData.KEY_AI_SECT_DISCIPLES
        ) { chunks -> heavyDao.upsertAll(chunks) }

        ProtobufConverters.encodeSectDetailMapIncremental(
            data.gameData.sectDetails, slot, GameHeavyData.KEY_SECT_DETAILS
        ) { chunks -> heavyDao.upsertAll(chunks) }

        ProtobufConverters.encodeExploredSectInfoMapIncremental(
            data.gameData.exploredSects, slot, GameHeavyData.KEY_EXPLORED_SECTS
        ) { chunks -> heavyDao.upsertAll(chunks) }

        ProtobufConverters.encodeSectScoutInfoMapIncremental(
            data.gameData.scoutInfo, slot, GameHeavyData.KEY_SCOUT_INFO
        ) { chunks -> heavyDao.upsertAll(chunks) }

        ProtobufConverters.encodeManualProficiencyMapIncremental(
            data.gameData.manualProficiencies, slot, GameHeavyData.KEY_MANUAL_PROFICIENCIES
        ) { chunks -> heavyDao.upsertAll(chunks) }

        ProtobufConverters.encodeDiscipleListIncremental(
            data.gameData.recruitList, slot, GameHeavyData.KEY_RECRUIT_LIST
        ) { chunks -> heavyDao.upsertAll(chunks) }

        ProtobufConverters.encodeWorldSectListIncremental(
            data.gameData.worldMapSects, slot, GameHeavyData.KEY_WORLD_MAP_SECTS
        ) { chunks -> heavyDao.upsertAll(chunks) }

        // ── 轻型 GameData（所有大型字段已清空，TypeConverter 编码近乎零开销）──
        val lightGameData = data.gameData.copy(
            slotId = slot,
            id = "game_data_$slot",
            lastSaveTime = data.timestamp,
            aiSectDisciples = emptyMap(),
            sectDetails = emptyMap(),
            exploredSects = emptyMap(),
            scoutInfo = emptyMap(),
            manualProficiencies = emptyMap(),
            recruitList = emptyList(),
            worldMapSects = emptyList()
        )
        core.database.withTransaction {
            // ── 先清空所有旧数据，再写入新数据 ──
            // 防止重新开始游戏时，旧存档的高 ID 行残留在数据库中
            core.database.discipleDao().deleteAll(slot)
            core.database.discipleCoreDao().deleteAll(slot)
            core.database.discipleCombatStatsDao().deleteAll(slot)
            core.database.discipleEquipmentDao().deleteAll(slot)
            core.database.discipleExtendedDao().deleteAll(slot)
            core.database.discipleAttributesDao().deleteAll(slot)
            core.database.equipmentStackDao().deleteAll(slot)
            core.database.equipmentInstanceDao().deleteAll(slot)
            core.database.manualStackDao().deleteAll(slot)
            core.database.manualInstanceDao().deleteAll(slot)
            core.database.pillDao().deleteAll(slot)
            core.database.materialDao().deleteAll(slot)
            core.database.herbDao().deleteAll(slot)
            core.database.seedDao().deleteAll(slot)
            core.database.storageBagDao().deleteAll(slot)
            core.database.explorationTeamDao().deleteAll(slot)
            core.database.battleLogDao().deleteAll(slot)
            core.database.recipeDao().deleteAll(slot)
            core.database.productionSlotDao().deleteBySlot(slot)

            // ── 轻型 GameData ──
            core.database.gameDataDao().insert(lightGameData)

            data.disciples.chunked(MAX_BATCH_SIZE).forEach { batch ->
                val withSlot = batch.map { d -> d.copy(slotId = slot) }
                core.database.discipleDao().upsertAll(withSlot)
                core.database.discipleCoreDao().upsertAll(batch.map { d -> DiscipleCore.fromDisciple(d).copy(slotId = slot) })
                core.database.discipleCombatStatsDao().upsertAll(batch.map { d -> DiscipleCombatStats.fromDisciple(d).copy(slotId = slot) })
                core.database.discipleEquipmentDao().upsertAll(batch.map { d -> DiscipleEquipment.fromDisciple(d).copy(slotId = slot) })
                core.database.discipleExtendedDao().upsertAll(batch.map { d -> DiscipleExtended.fromDisciple(d).copy(slotId = slot) })
                core.database.discipleAttributesDao().upsertAll(batch.map { d -> DiscipleAttributes.fromDisciple(d).copy(slotId = slot) })
            }

            data.equipmentStacks.chunked(MAX_BATCH_SIZE).forEach { core.database.equipmentStackDao().upsertAll(it.map { e -> e.copy(slotId = slot) }) }
            data.equipmentInstances.chunked(MAX_BATCH_SIZE).forEach { core.database.equipmentInstanceDao().upsertAll(it.map { e -> e.copy(slotId = slot) }) }
            data.manualStacks.chunked(MAX_BATCH_SIZE).forEach { core.database.manualStackDao().upsertAll(it.map { m -> m.copy(slotId = slot) }) }
            data.manualInstances.chunked(MAX_BATCH_SIZE).forEach { core.database.manualInstanceDao().upsertAll(it.map { m -> m.copy(slotId = slot) }) }
            data.pills.chunked(MAX_BATCH_SIZE).forEach { core.database.pillDao().upsertAll(it.map { p -> p.copy(slotId = slot) }) }
            data.materials.chunked(MAX_BATCH_SIZE).forEach { core.database.materialDao().upsertAll(it.map { m -> m.copy(slotId = slot) }) }
            data.herbs.chunked(MAX_BATCH_SIZE).forEach { core.database.herbDao().upsertAll(it.map { h -> h.copy(slotId = slot) }) }
            data.seeds.chunked(MAX_BATCH_SIZE).forEach { core.database.seedDao().upsertAll(it.map { s -> s.copy(slotId = slot) }) }

            data.storageBags.chunked(MAX_BATCH_SIZE).forEach { core.database.storageBagDao().upsertAll(it) }

            data.teams.chunked(MAX_BATCH_SIZE).forEach { core.database.explorationTeamDao().upsertAll(it.map { t -> t.copy(slotId = slot) }) }

            data.battleLogs.chunked(MAX_BATCH_SIZE).forEach { core.database.battleLogDao().upsertAll(it.map { b -> b.copy(slotId = slot) }) }

            val productionSlotsToSave = data.productionSlots
            if (productionSlotsToSave.isEmpty()) {
                Log.w(TAG, "writeAllDataToDatabase: productionSlotsToSave is EMPTY for slot $slot — " +
                    "data.productionSlots.size=${data.productionSlots.size}")
            }
            productionSlotsToSave.chunked(MAX_BATCH_SIZE).forEach { batch ->
                core.database.productionSlotDao().upsertAll(batch.map { it.copy(slotId = slot) })
            }

            data.gameData.unlockedRecipes?.map { Recipe(it, slotId = slot) }?.let { recipes ->
                core.database.recipeDao().upsertAll(recipes)
            }

            syncSlotMetadata(slot, data)

            // ── 领域实体表写入（Phase B：细粒度读取路径）──
            val gd = data.gameData
            core.database.diplomacyStateDao().upsert(DiplomacyState(
                slotId = slot,
                sectRelations = gd.sectRelations,
                alliances = gd.alliances,
                playerAllianceSlots = gd.playerAllianceSlots,
                playerProtectionEnabled = gd.playerProtectionEnabled,
                playerProtectionStartYear = gd.playerProtectionStartYear,
                playerHasAttackedAI = gd.playerHasAttackedAI,
                sectDetails = gd.sectDetails,
                exploredSects = gd.exploredSects,
                scoutInfo = gd.scoutInfo
            ))
            core.database.productionStateDao().upsert(ProductionState(
                slotId = slot,
                spiritFieldPlants = gd.spiritFieldPlants,
                unlockedRecipes = gd.unlockedRecipes ?: emptyList(),
                unlockedManuals = gd.unlockedManuals ?: emptyList(),
                manualProficiencies = gd.manualProficiencies
            ))
            core.database.patrolStateDao().upsert(PatrolStateEntity(
                slotId = slot,
                patrolSlots = gd.patrolSlots,
                patrolConfig = gd.patrolConfig,
                patrolConfigs = gd.patrolConfigs,
                patrolBattleResultPopup = gd.patrolBattleResultPopup
            ))
            core.database.worldMapStateDao().upsert(WorldMapStateEntity(
                slotId = slot,
                worldMapSects = gd.worldMapSects,
                aiSectDisciples = gd.aiSectDisciples,
                cultivatorCaves = gd.cultivatorCaves,
                caveExplorationTeams = gd.caveExplorationTeams,
                aiCaveTeams = gd.aiCaveTeams,
                worldLevels = gd.worldLevels
            ))
            core.database.sectPolicyStateDao().upsert(SectPolicyState(
                slotId = slot,
                sectPolicies = gd.sectPolicies,
                autoRecruitSpiritRootFilter = gd.autoRecruitSpiritRootFilter,
                daoCompanionBannedRootCounts = gd.daoCompanionBannedRootCounts,
                daoCompanionConsentRequired = gd.daoCompanionConsentRequired,
                breakthroughAutoPillFocused = gd.breakthroughAutoPillFocused,
                breakthroughAutoPillRootCounts = gd.breakthroughAutoPillRootCounts,
                autoEquipFromWarehouseFocused = gd.autoEquipFromWarehouseFocused,
                autoEquipFromWarehouseRootCounts = gd.autoEquipFromWarehouseRootCounts,
                autoLearnFromWarehouseFocused = gd.autoLearnFromWarehouseFocused,
                autoLearnFromWarehouseRootCounts = gd.autoLearnFromWarehouseRootCounts,
                yearlySalary = gd.yearlySalary,
                yearlySalaryEnabled = gd.yearlySalaryEnabled,
                autoSaveIntervalMonths = gd.autoSaveIntervalMonths
            ))
        }
    }

    private suspend fun syncSlotMetadata(slot: Int, data: SaveData) {
        val gd = data.gameData
        val metadata = SaveSlotMetadata(
            slotId = slot,
            sectName = gd.sectName,
            gameYear = gd.gameYear,
            gameMonth = gd.gameMonth,
            gamePhase = gd.gamePhase,
            spiritStones = gd.spiritStones,
            spiritHerbs = gd.spiritHerbs,
            sectCultivation = gd.sectCultivation,
            isGameStarted = gd.isGameStarted,
            lastSaveTime = data.timestamp,
            discipleCount = data.disciples.count { it.isAlive }
        )
        core.database.saveSlotMetadataDao().upsert(metadata)
    }

    private suspend fun logSaveChanges(slot: Int, data: SaveData) {
        try {
            infra.changeLogPersistence.logChange(
                tableName = "game_data",
                recordId = "game_data_$slot",
                operation = ChangeLogOperation.UPDATE
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to log save change for slot $slot", e)
        }
    }

    private suspend fun loadFromCache(slot: Int): SaveData? = withContext(Dispatchers.IO) {
        try {
            val gameDataKey = CacheKey.forGameData(slot)
            core.cache.getOrNull<SaveData>(gameDataKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load from cache for slot $slot", e)
            null
        }
    }

    private suspend fun loadFromDatabase(slot: Int): SaveData? {
        return try {
            core.database.withTransaction {
                loadFromDatabaseInternal(slot, loadHeavyData = true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load from database for slot $slot", e)
            null
        }
    }

    private suspend fun loadFromDatabaseInternal(slot: Int, loadHeavyData: Boolean = false): SaveData? {
        val gameData = core.database.gameDataDao().getGameDataSync(slot) ?: return null

        if (loadHeavyData) {
            val merged = mergeHeavyData(gameData, slot)
            val saveData = buildSaveDataFromDatabase(slot, merged)
            if (saveData != null) {
                val migrated = migrateSaveDataIfNeeded(saveData)
                if (!validateSaveData(migrated)) {
                    Log.w(TAG, "Save data validation failed for slot $slot after heavy data merge")
                }
                return migrated
            }
            return saveData
        }

        val saveData = buildSaveDataFromDatabase(slot, gameData)
        if (saveData != null) {
            val migrated = migrateSaveDataIfNeeded(saveData)
            if (!validateSaveData(migrated)) {
                Log.w(TAG, "Save data validation failed for slot $slot: gameYear=${gameData.gameYear}, gameMonth=${gameData.gameMonth}, sectName='${gameData.sectName}'")
            }
            return migrated
        }
        return saveData
    }

    /**
     * 迁移旧版存档数据（saveVersion < 1）。
     *
     * v4.0.13 将修炼基础值(REALM_SPEED_PER_PHASE / cultivationBase)
     * 等比缩小为 1/10，旧存档中的 cultivation 值需同步缩放。
     */
    private fun migrateSaveDataIfNeeded(saveData: SaveData): SaveData {
        val gd = saveData.gameData
        if (gd.saveVersion >= 1) return saveData

        val scaleFactor = 10.0
        Log.i(TAG, "Migrating save data: scaling cultivation by 1/$scaleFactor (slot ${gd.slotId})")

        // 1. 宗门总修为
        val migratedGd = gd.copy(
            sectCultivation = gd.sectCultivation / scaleFactor,
            saveVersion = 1,
            // 2. 招募列表中的弟子
            recruitList = gd.recruitList.map { d -> d.scaleCultivation(scaleFactor) },
            // 3. AI宗门弟子
            aiSectDisciples = gd.aiSectDisciples.mapValues { (_, list) ->
                list.map { d -> d.scaleCultivation(scaleFactor) }
            }
        )

        // 4. 主弟子列表
        val migratedDisciples = saveData.disciples.map { it.scaleCultivation(scaleFactor) }

        Log.i(TAG, "Save migration complete: ${migratedDisciples.size} disciples scaled")
        return saveData.copy(
            gameData = migratedGd,
            disciples = migratedDisciples
        )
    }

    /**
     * 将弟子的修炼值和战力值同步缩放（向上取整，宁可多不可少）。
     */
    private fun Disciple.scaleCultivation(factor: Double): Disciple {
        return this.copy(
            cultivation = this.cultivation / factor,
            combat = this.combat.copy(
                totalCultivation = kotlin.math.ceil(
                    this.combat.totalCultivation / factor
                ).toLong()
            )
        )
    }

    /**
     * 存档数据完整性校验。
     * 检查关键字段是否在合法范围内，防止损坏数据导致游戏逻辑异常。
     */
    private fun validateSaveData(data: SaveData): Boolean {
        if (data.gameData.gameYear < 1) return false
        if (data.gameData.gameMonth < 1 || data.gameData.gameMonth > 12) return false
        if (data.gameData.sectName.isBlank()) return false
        return true
    }

    private suspend fun mergeHeavyData(gameData: GameData, slot: Int): GameData {
        val allRows = loadHeavyDataSafe(slot)
        if (allRows.isEmpty()) return gameData

        return gameData.copy(
            aiSectDisciples = if (gameData.aiSectDisciples.isEmpty())
                ProtobufConverters.decodeDiscipleListMapFromRows(allRows, GameHeavyData.KEY_AI_SECT_DISCIPLES)
            else gameData.aiSectDisciples,

            sectDetails = if (gameData.sectDetails.isEmpty())
                ProtobufConverters.decodeSectDetailMapFromRows(allRows, GameHeavyData.KEY_SECT_DETAILS)
            else gameData.sectDetails,

            exploredSects = if (gameData.exploredSects.isEmpty())
                ProtobufConverters.decodeExploredSectInfoMapFromRows(allRows, GameHeavyData.KEY_EXPLORED_SECTS)
            else gameData.exploredSects,

            scoutInfo = if (gameData.scoutInfo.isEmpty())
                ProtobufConverters.decodeSectScoutInfoMapFromRows(allRows, GameHeavyData.KEY_SCOUT_INFO)
            else gameData.scoutInfo,

            manualProficiencies = if (gameData.manualProficiencies.isEmpty())
                ProtobufConverters.decodeManualProficiencyMapFromRows(allRows, GameHeavyData.KEY_MANUAL_PROFICIENCIES)
            else gameData.manualProficiencies,

            recruitList = if (gameData.recruitList.isEmpty())
                ProtobufConverters.decodeDiscipleListFromRows(allRows, GameHeavyData.KEY_RECRUIT_LIST)
            else gameData.recruitList,

            worldMapSects = if (gameData.worldMapSects.isEmpty())
                ProtobufConverters.decodeWorldSectListFromRows(allRows, GameHeavyData.KEY_WORLD_MAP_SECTS)
            else gameData.worldMapSects
        )
    }

    suspend fun loadHeavyDataForSlot(slot: Int): Map<String, ByteArray> {
        val heavyDataList = loadHeavyDataSafe(slot)
        return GameHeavyData.reassemble(heavyDataList)
    }

    /**
     * 安全加载重型数据：逐 key 读取，跳过超过 CursorWindow 限制的单行。
     * 跳过的数据会在下次保存时由游戏逻辑重新生成并分块存储。
     */
    private suspend fun loadHeavyDataSafe(slot: Int): List<GameHeavyData> {
        val keys = try {
            core.database.gameHeavyDataDao().getLoadedKeys(slot)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load heavy data keys for slot $slot, skipping", e)
            return emptyList()
        }

        val result = mutableListOf<GameHeavyData>()
        for (key in keys) {
            try {
                val row = core.database.gameHeavyDataDao().getByKey(slot, key)
                if (row != null) result.add(row)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Heavy data key '$key' exceeds CursorWindow limit, will be regenerated on next save", e)
                // 删除超大行，下次保存时会分块重写
                try { core.database.gameHeavyDataDao().deleteByKey(slot, key) } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to delete oversized heavy data key '$key'", e)
                }
            }
        }
        return result
    }

    private suspend fun buildSaveDataFromDatabase(slot: Int, gameData: GameData): SaveData = withContext(Dispatchers.IO) {
        val deferredDisciples = async { core.database.discipleDao().getAllSync(slot) }
        val deferredEquipmentStacks = async { core.database.equipmentStackDao().getAllSync(slot) }
        val deferredEquipmentInstances = async { core.database.equipmentInstanceDao().getAllSync(slot) }
        val deferredManualStacks = async { core.database.manualStackDao().getAllSync(slot) }
        val deferredManualInstances = async { core.database.manualInstanceDao().getAllSync(slot) }
        val deferredPills = async { core.database.pillDao().getAllSync(slot) }
        val deferredMaterials = async { core.database.materialDao().getAllSync(slot) }
        val deferredHerbs = async { core.database.herbDao().getAllSync(slot) }
        val deferredSeeds = async { core.database.seedDao().getAllSync(slot) }
        val deferredStorageBags = async { core.database.storageBagDao().getAll(slot) }
        val deferredTeams = async { core.database.explorationTeamDao().getAllSync(slot) }
        val deferredBattleLogs = async { core.database.battleLogDao().getAllSync(slot) }
        var deferredProductionSlots = async { core.database.productionSlotDao().getBySlotSync(slot) }

        val disciples = deferredDisciples.await()
        val equipmentStacks = deferredEquipmentStacks.await()
        val equipmentInstances = deferredEquipmentInstances.await()
        val manualStacks = deferredManualStacks.await()
        val manualInstances = deferredManualInstances.await()
        val pills = deferredPills.await()
        val materials = deferredMaterials.await()
        val herbs = deferredHerbs.await()
        val seeds = deferredSeeds.await()
        val storageBags = deferredStorageBags.await()
        val teams = deferredTeams.await()
        val battleLogs = deferredBattleLogs.await()
        var productionSlots = deferredProductionSlots.await()

        val alliances = gameData.alliances ?: emptyList()

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
            storageBags = storageBags,
            teams = teams,
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
            core.cache.putWithoutTracking(cacheKey, data)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update cache for slot $slot", e)
        }
    }

    private fun clearCacheForSlot(slot: Int) {
        try {
            val cacheKey = CacheKey.forGameData(slot)
            core.cache.remove(cacheKey)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear cache for slot $slot", e)
        }
    }

    private suspend fun querySingleSlot(slot: Int): SaveSlot {
        val isAutoSave = slot == StorageConstants.AUTO_SAVE_SLOT
        return try {
            val meta = core.database.gameDataDao().getMetadataBySlot(slot)
            if (meta != null) {
                SaveSlot(
                    slot = slot,
                    name = "Save $slot",
                    timestamp = meta.lastSaveTime,
                    gameYear = meta.gameYear,
                    gameMonth = meta.gameMonth,
                    sectName = meta.sectName,
                    discipleCount = core.database.discipleDao().getAllAliveSync(slot).size,
                    spiritStones = meta.spiritStones,
                    isEmpty = false,
                    customName = meta.sectName,
                    isAutoSave = isAutoSave
                )
            } else {
                SaveSlot(slot, "", 0, 1, 1, "", 0, 0, true, isAutoSave = isAutoSave)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "querySingleSlot FAILED for slot $slot -- database may be unreachable or schema is mismatched", e)
            throw RuntimeException("Failed to query save slot $slot: ${e.message}", e)
        }
    }

}
