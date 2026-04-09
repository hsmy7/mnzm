@file:Suppress("DEPRECATION")

package com.xianxia.sect.data.incremental

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.xianxia.sect.core.model.*
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.model.SaveSlot
import com.xianxia.sect.data.local.GameDatabase
import com.xianxia.sect.data.cache.CacheKey
import com.xianxia.sect.data.cache.GameDataCacheManager
import com.xianxia.sect.data.engine.UnifiedStorageEngine
import com.xianxia.sect.data.result.StorageResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

data class StorageConfig(
    val enableIncrementalSave: Boolean = true,
    val autoSaveIntervalMs: Long = 60_000L,
    val maxDeltaChainLength: Int = 50,
    val compactionThreshold: Int = 10,
    val forceFullSaveInterval: Int = 20
)

data class StorageSaveResult(
    val success: Boolean,
    val savedBytes: Long = 0,
    val elapsedMs: Long = 0,
    val isIncremental: Boolean = false,
    val deltaCount: Int = 0,
    val error: String? = null
)

data class StorageLoadResult(
    val data: SaveData?,
    val elapsedMs: Long = 0,
    val appliedDeltas: Int = 0,
    val wasFromSnapshot: Boolean = false,
    val error: String? = null
)

data class StorageStats(
    val totalSaves: Long = 0,
    val totalLoads: Long = 0,
    val incrementalSaves: Long = 0,
    val fullSaves: Long = 0,
    val totalBytesSaved: Long = 0,
    val totalBytesLoaded: Long = 0,
    val avgSaveTimeMs: Long = 0,
    val avgLoadTimeMs: Long = 0,
    val currentDeltaChainLength: Int = 0,
    val pendingChanges: Int = 0
)

@Singleton
class IncrementalStorageCoordinator @Inject constructor(
    private val context: Context,
    private val database: GameDatabase,
    private val cacheManager: GameDataCacheManager,
    private val changeTracker: ChangeTracker,
    private val deltaCompressor: DeltaCompressor,
    private val engine: UnifiedStorageEngine
) {
    companion object {
        private const val TAG = "IncStorageCoordinator"
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val saveMutex = Mutex()
    
    private val config = StorageConfig()
    
    private var saveCount = 0L
    private var loadCount = 0L
    private var incrementalSaveCount = 0L
    private var fullSaveCount = 0L
    private var totalBytesSaved = 0L
    private var totalBytesLoaded = 0L
    private var totalSaveTime = 0L
    private var totalLoadTime = 0L
    
    private val _stats = MutableStateFlow(StorageStats())
    val stats: StateFlow<StorageStats> = _stats.asStateFlow()
    
    private val _saveProgress = MutableStateFlow(0f)
    val saveProgress: StateFlow<Float> = _saveProgress.asStateFlow()
    
    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()
    
    private var lastFullSaveCount = 0L
    
    suspend fun save(slot: Int, data: SaveData): StorageSaveResult {
        if (!isValidSlot(slot)) {
            return StorageSaveResult(false, error = "Invalid slot")
        }

        return saveMutex.withLock {
            _isSaving.value = true
            val startTime = System.currentTimeMillis()

            try {
                val result = engine.save(slot, data)
                val coordinatorResult = result.toCoordinatorResult(startTime)

                if (coordinatorResult.success) {
                    saveCount++
                    totalBytesSaved += coordinatorResult.savedBytes
                    totalSaveTime += coordinatorResult.elapsedMs

                    if (coordinatorResult.isIncremental) {
                        incrementalSaveCount++
                    } else {
                        fullSaveCount++
                        lastFullSaveCount = saveCount
                    }

                    updateStats()
                }

                coordinatorResult
            } finally {
                _isSaving.value = false
            }
        }
    }
    
    private suspend fun performFullSave(slot: Int, data: SaveData): StorageSaveResult {
        Log.i(TAG, "Performing full save for slot $slot")

        val result = engine.save(slot, data, com.xianxia.sect.data.engine.SavePriority.HIGH)

        if (result.isSuccess) {
            changeTracker.clearChanges()
        }

        val stats = result.getOrNull() ?: com.xianxia.sect.data.engine.SaveOperationStats()
        return StorageSaveResult(
            success = result.isSuccess,
            savedBytes = stats.bytesWritten,
            elapsedMs = stats.timeMs,
            isIncremental = false,
            deltaCount = 0,
            error = if (result.isFailure) (result as com.xianxia.sect.data.result.StorageResult.Failure).message else null
        )
    }

    private suspend fun performIncrementalSave(slot: Int, data: SaveData): StorageSaveResult {
        Log.i(TAG, "Performing incremental save for slot $slot")

        val result = engine.save(slot, data)

        if (result.isSuccess) {
            changeTracker.clearChanges()
        }

        val stats = result.getOrNull() ?: com.xianxia.sect.data.engine.SaveOperationStats()
        return StorageSaveResult(
            success = result.isSuccess,
            savedBytes = stats.bytesWritten,
            elapsedMs = stats.timeMs,
            isIncremental = stats.wasIncremental,
            deltaCount = if (stats.wasIncremental) 1 else 0,
            error = if (result.isFailure) (result as com.xianxia.sect.data.result.StorageResult.Failure).message else null
        )
    }
    
    private suspend fun syncToDatabase(data: SaveData) {
        try {
            val slot = data.gameData.currentSlot.takeIf { it > 0 } ?: data.gameData.slotId.takeIf { it > 0 } ?: 1
            val gameDataWithSlot = data.gameData.copy(slotId = slot, currentSlot = slot, id = "game_data_$slot")
            database.gameDataDao().insert(gameDataWithSlot)
            
            val batchSize = 50
            data.disciples.chunked(batchSize).forEach { batch ->
                database.discipleDao().insertAll(batch)
            }
            
            data.equipment.chunked(100).forEach { batch ->
                database.equipmentDao().insertAll(batch)
            }
            data.manuals.chunked(100).forEach { batch ->
                database.manualDao().insertAll(batch)
            }
            data.pills.chunked(100).forEach { batch ->
                database.pillDao().insertAll(batch)
            }
            data.materials.chunked(100).forEach { batch ->
                database.materialDao().insertAll(batch)
            }
            data.herbs.chunked(100).forEach { batch ->
                database.herbDao().insertAll(batch)
            }
            data.seeds.chunked(100).forEach { batch ->
                database.seedDao().insertAll(batch)
            }
            
            Log.d(TAG, "Synced data to database")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync data to database", e)
        }
    }
    
    private suspend fun syncToDatabaseWithTransaction(data: SaveData): Boolean {
        return try {
            database.withTransaction {
                val slot = data.gameData?.currentSlot?.takeIf { it > 0 } ?: data.gameData?.slotId?.takeIf { it > 0 } ?: 1
                data.gameData?.let {
                    val gameDataWithSlot = it.copy(slotId = slot, currentSlot = slot, id = "game_data_$slot")
                    database.gameDataDao().insert(gameDataWithSlot)
                }
                
                val batchSize = 50
                data.disciples.chunked(batchSize).forEach { batch ->
                    database.discipleDao().insertAll(batch)
                }
                
                data.equipment.chunked(100).forEach { batch ->
                    database.equipmentDao().insertAll(batch)
                }
                data.manuals.chunked(100).forEach { batch ->
                    database.manualDao().insertAll(batch)
                }
                data.pills.chunked(100).forEach { batch ->
                    database.pillDao().insertAll(batch)
                }
                data.materials.chunked(100).forEach { batch ->
                    database.materialDao().insertAll(batch)
                }
                data.herbs.chunked(100).forEach { batch ->
                    database.herbDao().insertAll(batch)
                }
                data.seeds.chunked(100).forEach { batch ->
                    database.seedDao().insertAll(batch)
                }
            }
            
            Log.d(TAG, "Synced data to database with transaction")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync data to database with transaction", e)
            false
        }
    }
    
    private suspend fun syncChangesToDatabase() {
        try {
            val changes = changeTracker.getChanges()
            
            changes.changes.forEach { change ->
                when (change.changeType) {
                    ChangeType.CREATED -> {
                        when (change.dataType) {
                            DataType.DISCIPLE -> if (change.newValue is Disciple) database.discipleDao().insert(change.newValue)
                            DataType.EQUIPMENT -> if (change.newValue is Equipment) database.equipmentDao().insert(change.newValue)
                            DataType.MANUAL -> if (change.newValue is Manual) database.manualDao().insert(change.newValue)
                            DataType.PILL -> if (change.newValue is Pill) database.pillDao().insert(change.newValue)
                            DataType.MATERIAL -> if (change.newValue is Material) database.materialDao().insert(change.newValue)
                            DataType.HERB -> if (change.newValue is Herb) database.herbDao().insert(change.newValue)
                            DataType.SEED -> if (change.newValue is Seed) database.seedDao().insert(change.newValue)
                            DataType.GAME_DATA -> if (change.newValue is GameData) {
                                val slot = change.newValue.currentSlot.takeIf { it > 0 } ?: change.newValue.slotId.takeIf { it > 0 } ?: 1
                                database.gameDataDao().insert(change.newValue.copy(slotId = slot, currentSlot = slot, id = "game_data_$slot"))
                            }
                            else -> {}
                        }
                    }
                    ChangeType.UPDATED -> {
                        when (change.dataType) {
                            DataType.DISCIPLE -> if (change.newValue is Disciple) database.discipleDao().insert(change.newValue)
                            DataType.EQUIPMENT -> if (change.newValue is Equipment) database.equipmentDao().insert(change.newValue)
                            DataType.MANUAL -> if (change.newValue is Manual) database.manualDao().insert(change.newValue)
                            DataType.PILL -> if (change.newValue is Pill) database.pillDao().insert(change.newValue)
                            DataType.MATERIAL -> if (change.newValue is Material) database.materialDao().insert(change.newValue)
                            DataType.HERB -> if (change.newValue is Herb) database.herbDao().insert(change.newValue)
                            DataType.SEED -> if (change.newValue is Seed) database.seedDao().insert(change.newValue)
                            DataType.GAME_DATA -> if (change.newValue is GameData) {
                                val slot = change.newValue.currentSlot.takeIf { it > 0 } ?: change.newValue.slotId.takeIf { it > 0 } ?: 1
                                database.gameDataDao().insert(change.newValue.copy(slotId = slot, currentSlot = slot, id = "game_data_$slot"))
                            }
                            else -> {}
                        }
                    }
                    ChangeType.DELETED -> {
                        when (change.dataType) {
                            DataType.DISCIPLE -> database.discipleDao().deleteById(0, change.entityId)
                            DataType.EQUIPMENT -> database.equipmentDao().deleteById(0, change.entityId)
                            DataType.MANUAL -> if (change.oldValue is Manual) database.manualDao().delete(change.oldValue)
                            DataType.PILL -> if (change.oldValue is Pill) database.pillDao().delete(change.oldValue)
                            DataType.MATERIAL -> if (change.oldValue is Material) database.materialDao().delete(change.oldValue)
                            DataType.HERB -> if (change.oldValue is Herb) database.herbDao().delete(change.oldValue)
                            DataType.SEED -> if (change.oldValue is Seed) database.seedDao().delete(change.oldValue)
                            else -> {}
                        }
                    }
                }
            }
            
            Log.d(TAG, "Synced ${changes.changeCount} changes to database")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync changes to database", e)
        }
    }
    
    suspend fun load(slot: Int): StorageLoadResult {
        if (!isValidSlot(slot)) {
            return StorageLoadResult(null, error = "Invalid slot")
        }

        val startTime = System.currentTimeMillis()

        try {
            val result = engine.load(slot)

            loadCount++
            totalLoadTime += System.currentTimeMillis() - startTime

            val data = result.getOrNull()
            if (data != null) {
                totalBytesSaved += UnifiedStorageEngine.estimateSaveSize(data)
                warmupCache(slot, data)
            }

            updateStats()

            return StorageLoadResult(
                data = data,
                elapsedMs = System.currentTimeMillis() - startTime,
                appliedDeltas = 0,
                wasFromSnapshot = false,
                error = if (result.isFailure) (result as com.xianxia.sect.data.result.StorageResult.Failure).message else null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Load failed for slot $slot", e)
            return StorageLoadResult(null, error = e.message)
        }
    }
    
    private fun warmupCache(slot: Int, data: SaveData) {
        try {
            cacheManager.putAsync(CacheKey.forGameData(slot), data.gameData)
            Log.d(TAG, "Warmed up cache with loaded data for slot $slot")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to warmup cache: ${e.message}")
        }
    }
    
    fun trackChange(dataType: DataType, entityId: String, oldValue: Any?, newValue: Any?) {
        when {
            oldValue == null -> newValue?.let { changeTracker.trackCreate(dataType, entityId, it) }
                ?: Log.w(TAG, "trackCreate called with null newValue for $entityId")
            newValue == null -> changeTracker.trackDelete(dataType, entityId, oldValue)
            else -> changeTracker.trackUpdate(dataType, entityId, oldValue, newValue)
        }
    }
    
    fun trackDiscipleCreate(disciple: Disciple) {
        trackChange(DataType.DISCIPLE, disciple.id.toString(), null, disciple)
    }
    
    fun trackDiscipleUpdate(oldDisciple: Disciple, newDisciple: Disciple) {
        trackChange(DataType.DISCIPLE, newDisciple.id.toString(), oldDisciple, newDisciple)
    }
    
    fun trackDiscipleDelete(disciple: Disciple) {
        trackChange(DataType.DISCIPLE, disciple.id.toString(), disciple, null)
    }
    
    fun trackEquipmentCreate(equipment: Equipment) {
        trackChange(DataType.EQUIPMENT, equipment.id.toString(), null, equipment)
    }
    
    fun trackEquipmentUpdate(oldEquipment: Equipment, newEquipment: Equipment) {
        trackChange(DataType.EQUIPMENT, newEquipment.id.toString(), oldEquipment, newEquipment)
    }
    
    fun trackEquipmentDelete(equipment: Equipment) {
        trackChange(DataType.EQUIPMENT, equipment.id.toString(), equipment, null)
    }
    
    fun trackManualCreate(manual: Manual) {
        trackChange(DataType.MANUAL, manual.id.toString(), null, manual)
    }
    
    fun trackManualUpdate(oldManual: Manual, newManual: Manual) {
        trackChange(DataType.MANUAL, newManual.id.toString(), oldManual, newManual)
    }
    
    fun trackManualDelete(manual: Manual) {
        trackChange(DataType.MANUAL, manual.id.toString(), manual, null)
    }
    
    fun trackPillCreate(pill: Pill) {
        trackChange(DataType.PILL, pill.id.toString(), null, pill)
    }
    
    fun trackPillUpdate(oldPill: Pill, newPill: Pill) {
        trackChange(DataType.PILL, newPill.id.toString(), oldPill, newPill)
    }
    
    fun trackPillDelete(pill: Pill) {
        trackChange(DataType.PILL, pill.id.toString(), pill, null)
    }
    
    fun trackMaterialCreate(material: Material) {
        trackChange(DataType.MATERIAL, material.id.toString(), null, material)
    }
    
    fun trackMaterialUpdate(oldMaterial: Material, newMaterial: Material) {
        trackChange(DataType.MATERIAL, newMaterial.id.toString(), oldMaterial, newMaterial)
    }
    
    fun trackMaterialDelete(material: Material) {
        trackChange(DataType.MATERIAL, material.id.toString(), material, null)
    }
    
    fun trackHerbCreate(herb: Herb) {
        trackChange(DataType.HERB, herb.id.toString(), null, herb)
    }
    
    fun trackHerbUpdate(oldHerb: Herb, newHerb: Herb) {
        trackChange(DataType.HERB, newHerb.id.toString(), oldHerb, newHerb)
    }
    
    fun trackHerbDelete(herb: Herb) {
        trackChange(DataType.HERB, herb.id.toString(), herb, null)
    }
    
    fun trackSeedCreate(seed: Seed) {
        trackChange(DataType.SEED, seed.id.toString(), null, seed)
    }
    
    fun trackSeedUpdate(oldSeed: Seed, newSeed: Seed) {
        trackChange(DataType.SEED, newSeed.id.toString(), oldSeed, newSeed)
    }
    
    fun trackSeedDelete(seed: Seed) {
        trackChange(DataType.SEED, seed.id.toString(), seed, null)
    }
    
    fun trackTeamCreate(team: ExplorationTeam) {
        trackChange(DataType.TEAM, team.id.toString(), null, team)
    }
    
    fun trackTeamUpdate(oldTeam: ExplorationTeam, newTeam: ExplorationTeam) {
        trackChange(DataType.TEAM, newTeam.id.toString(), oldTeam, newTeam)
    }
    
    fun trackTeamDelete(team: ExplorationTeam) {
        trackChange(DataType.TEAM, team.id.toString(), team, null)
    }
    
    fun trackGameDataUpdate(oldGameData: GameData, newGameData: GameData) {
        trackChange(DataType.GAME_DATA, "current", oldGameData, newGameData)
    }
    
    fun hasPendingChanges(): Boolean = changeTracker.hasChanges()

    fun getPendingChangeCount(): Int = changeTracker.changeCount()
    
    fun getSaveSlots(): List<SaveSlot> = emptyList()

    suspend fun hasSave(slot: Int): Boolean = engine.hasData(slot)

    suspend fun delete(slot: Int): Boolean {
        return engine.delete(slot).isSuccess
    }

    suspend fun compact(slot: Int): Boolean {
        return engine.save(slot, engine.load(slot).getOrNull() ?: return false, com.xianxia.sect.data.engine.SavePriority.HIGH).isSuccess
    }

    fun getDeltaChain(slot: Int): DeltaChain? = null

    fun getIncrementalStats(): IncrementalStorageStats = IncrementalStorageStats()
    
    private fun updateStats() {
        _stats.value = StorageStats(
            totalSaves = saveCount,
            totalLoads = loadCount,
            incrementalSaves = incrementalSaveCount,
            fullSaves = fullSaveCount,
            totalBytesSaved = totalBytesSaved,
            totalBytesLoaded = totalBytesLoaded,
            avgSaveTimeMs = if (saveCount > 0) totalSaveTime / saveCount else 0,
            avgLoadTimeMs = if (loadCount > 0) totalLoadTime / loadCount else 0,
            currentDeltaChainLength = 0,
            pendingChanges = changeTracker.changeCount()
        )
    }

    private fun estimateDataSize(data: SaveData): Long = UnifiedStorageEngine.estimateSaveSize(data)
    
    private fun isValidSlot(slot: Int): Boolean = slot in 1..IncrementalStorageConfig.MAX_SLOTS
    
    fun shutdown() {
        scope.cancel()
        Log.i(TAG, "IncrementalStorageCoordinator shutdown completed")
    }
}

private fun StorageResult<com.xianxia.sect.data.engine.SaveOperationStats>.toCoordinatorResult(startTime: Long): StorageSaveResult {
    return if (isSuccess) {
        val stats = getOrNull()
        if (stats != null) {
            StorageSaveResult(
                success = true,
                savedBytes = stats.bytesWritten,
                elapsedMs = if (stats.timeMs > 0) stats.timeMs else System.currentTimeMillis() - startTime,
                isIncremental = stats.wasIncremental
            )
        } else {
            StorageSaveResult(
                success = false,
                error = "Stats data is null for successful save result"
            )
        }
    } else {
        StorageSaveResult(
            success = false,
            error = (this as StorageResult.Failure).message.let { if (it.isEmpty()) "Save failed" else it }
        )
    }
}
