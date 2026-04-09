package com.xianxia.sect.data.coordinator

import android.content.Context
import android.util.Log
import com.xianxia.sect.data.cache.GameDataCacheManager
import com.xianxia.sect.data.concurrent.SlotLockManager
import com.xianxia.sect.data.config.StorageConfig
import com.xianxia.sect.data.engine.SavePriority as EngineSavePriority
import com.xianxia.sect.data.engine.UnifiedStorageEngine
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.result.StorageResult
import com.xianxia.sect.data.unified.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

enum class SaveType {
    FULL,
    INCREMENTAL,
    AUTO
}

enum class SavePriority {
    LOW,
    NORMAL,
    HIGH,
    CRITICAL
}

data class SaveRequest(
    val slot: Int,
    val data: SaveData,
    val type: SaveType = SaveType.AUTO,
    val priority: SavePriority = SavePriority.NORMAL,
    val timestamp: Long = System.currentTimeMillis()
)

data class SaveCoordinatorStats(
    val totalSaves: Long = 0,
    val fullSaves: Long = 0,
    val incrementalSaves: Long = 0,
    val failedSaves: Long = 0,
    val averageSaveTimeMs: Long = 0,
    val lastSaveTime: Long = 0,
    val pendingOperations: Int = 0
)

data class CoordinatorSaveProgress(
    val stage: Stage,
    val progress: Float,
    val message: String = ""
) {
    enum class Stage {
        IDLE,
        VALIDATING,
        PREPARING_SNAPSHOT,
        SAVING_PRIMARY,
        SAVING_BACKUP,
        UPDATING_CACHE,
        VERIFYING,
        COMPLETED,
        FAILED
    }
}

@Singleton
class SaveCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: UnifiedStorageEngine,
    private val cacheManager: GameDataCacheManager,
    private val lockManager: SlotLockManager,
    private val config: StorageConfig,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "SaveCoordinator"
        private const val SAVE_TIMEOUT_MS = 60_000L
    }

    private val saveMutex = Mutex()
    private val isSaving = AtomicBoolean(false)
    
    private val totalSaves = AtomicLong(0)
    private val fullSaves = AtomicLong(0)
    private val incrementalSaves = AtomicLong(0)
    private val failedSaves = AtomicLong(0)
    private val totalSaveTime = AtomicLong(0)
    
    private val _progress = MutableStateFlow(CoordinatorSaveProgress(
        CoordinatorSaveProgress.Stage.IDLE, 0f
    ))
    val progress: StateFlow<CoordinatorSaveProgress> = _progress.asStateFlow()
    
    private val _stats = MutableStateFlow(SaveCoordinatorStats())
    val stats: StateFlow<SaveCoordinatorStats> = _stats.asStateFlow()
    
    private var lastSaveType = SaveType.FULL
    private var savesSinceLastFull = 0

    suspend fun save(
        slot: Int,
        data: SaveData,
        type: SaveType = SaveType.AUTO,
        priority: SavePriority = SavePriority.NORMAL
    ): SaveResult<SaveOperationStats> {
        val request = SaveRequest(slot, data, type, priority)
        return executeSave(request)
    }

    suspend fun saveFull(slot: Int, data: SaveData): SaveResult<SaveOperationStats> {
        return save(slot, data, SaveType.FULL, SavePriority.HIGH)
    }

    suspend fun saveIncremental(slot: Int, data: SaveData): SaveResult<SaveOperationStats> {
        return save(slot, data, SaveType.INCREMENTAL, SavePriority.NORMAL)
    }

    suspend fun emergencySave(data: SaveData): SaveResult<SaveOperationStats> {
        return engine.emergencySave(data).toCoordinatorResult()
    }

    private suspend fun executeSave(request: SaveRequest): SaveResult<SaveOperationStats> {
        if (!lockManager.isValidSlot(request.slot)) {
            return SaveResult.failure(SaveError.INVALID_SLOT, "Invalid slot: ${request.slot}")
        }

        return saveMutex.withLock {
            val startTime = System.currentTimeMillis()
            isSaving.set(true)
            
            try {
                _progress.value = CoordinatorSaveProgress(
                    CoordinatorSaveProgress.Stage.VALIDATING, 0.1f, "Validating save data"
                )
                
                val validationResult = validateSaveData(request.data)
                if (!validationResult.isValid) {
                    _progress.value = CoordinatorSaveProgress(
                        CoordinatorSaveProgress.Stage.FAILED, 0f, validationResult.error
                    )
                    return@withLock SaveResult.failure(SaveError.SAVE_FAILED, validationResult.error)
                }

                _progress.value = CoordinatorSaveProgress(
                    CoordinatorSaveProgress.Stage.PREPARING_SNAPSHOT, 0.2f, "Preparing snapshot"
                )
                
                val determinedType = determineSaveType(request)
                
                val result = when (determinedType) {
                    SaveType.FULL -> executeFullSave(request)
                    SaveType.INCREMENTAL -> executeIncrementalSave(request)
                    else -> executeFullSave(request)
                }

                if (result.isSuccess) {
                    _progress.value = CoordinatorSaveProgress(
                        CoordinatorSaveProgress.Stage.UPDATING_CACHE, 0.9f, "Updating cache"
                    )
                    updateCacheAfterSave(request.slot, request.data)
                    
                    _progress.value = CoordinatorSaveProgress(
                        CoordinatorSaveProgress.Stage.VERIFYING, 0.95f, "Verifying"
                    )
                    
                    val elapsed = System.currentTimeMillis() - startTime
                    recordSuccess(determinedType, elapsed)
                    
                    _progress.value = CoordinatorSaveProgress(
                        CoordinatorSaveProgress.Stage.COMPLETED, 1.0f, 
                        "Save completed in ${elapsed}ms"
                    )
                } else {
                    recordFailure()
                    _progress.value = CoordinatorSaveProgress(
                        CoordinatorSaveProgress.Stage.FAILED, 0f, 
                        (result as? SaveResult.Failure)?.message ?: "Unknown error"
                    )
                }

                result
            } catch (e: CancellationException) {
                recordFailure()
                _progress.value = CoordinatorSaveProgress(
                    CoordinatorSaveProgress.Stage.FAILED, 0f, "Save cancelled"
                )
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Save failed for slot ${request.slot}", e)
                recordFailure()
                _progress.value = CoordinatorSaveProgress(
                    CoordinatorSaveProgress.Stage.FAILED, 0f, e.message ?: "Unknown error"
                )
                SaveResult.failure(SaveError.SAVE_FAILED, e.message ?: "Unknown error", e)
            } finally {
                isSaving.set(false)
                updateStats()
            }
        }
    }

    private suspend fun executeFullSave(request: SaveRequest): SaveResult<SaveOperationStats> {
        _progress.value = CoordinatorSaveProgress(
            CoordinatorSaveProgress.Stage.SAVING_PRIMARY, 0.3f, "Performing full save"
        )

        val result = engine.save(request.slot, request.data,
            when (request.priority) {
                SavePriority.CRITICAL -> EngineSavePriority.CRITICAL
                SavePriority.HIGH -> EngineSavePriority.HIGH
                else -> EngineSavePriority.NORMAL
            })

        if (result.isSuccess) {
            savesSinceLastFull = 0
            lastSaveType = SaveType.FULL
        }

        return result.toCoordinatorResult()
    }

    private suspend fun executeIncrementalSave(request: SaveRequest): SaveResult<SaveOperationStats> {
        _progress.value = CoordinatorSaveProgress(
            CoordinatorSaveProgress.Stage.SAVING_PRIMARY, 0.3f, "Performing incremental save"
        )

        val result = engine.save(request.slot, request.data)

        if (result.isFailure) {
            return executeFullSave(request)
        }

        savesSinceLastFull++
        lastSaveType = SaveType.INCREMENTAL
        return result.toCoordinatorResult()
    }

    private suspend fun determineSaveType(request: SaveRequest): SaveType {
        return when (request.type) {
            SaveType.FULL -> SaveType.FULL
            SaveType.INCREMENTAL -> SaveType.INCREMENTAL
            SaveType.AUTO -> {
                when {
                    request.priority == SavePriority.CRITICAL -> SaveType.FULL
                    savesSinceLastFull >= config.incrementalSaveThreshold -> SaveType.FULL
                    !engine.hasData(request.slot) -> SaveType.FULL
                    else -> SaveType.INCREMENTAL
                }
            }
        }
    }

    private suspend fun hasIncrementalBase(slot: Int): Boolean {
        return try {
            engine.hasData(slot)
        } catch (e: Exception) {
            false
        }
    }

    private fun validateSaveData(data: SaveData): CoordinatorValidationResult {
        // 硬性校验：sectName 为空则拒绝存档
        if (data.gameData.sectName.isBlank()) {
            return CoordinatorValidationResult(false, "Sect name is empty")
        }

        // 软性校验（仅 warn，不阻止存档流程）
        if (data.disciples.isEmpty()) {
            Log.w(TAG, "Saving with empty disciple list for slot")
        }
        if (data.gameData.spiritStones < 0) {
            Log.w(TAG, "Negative spiritStones detected: ${data.gameData.spiritStones}")
        }
        if (data.disciples.size > config.maxDisciples) {
            Log.w(TAG, "Disciple count ${data.disciples.size} exceeds max ${config.maxDisciples}")
        }

        return CoordinatorValidationResult(true)
    }

    private suspend fun updateCacheAfterSave(slot: Int, data: SaveData) {
        try {
            cacheManager.put(
                com.xianxia.sect.data.cache.CacheKey(
                    type = com.xianxia.sect.data.cache.CacheKey.TYPE_GAME_DATA,
                    slot = slot,
                    id = "current",
                    ttl = 300_000L
                ),
                data.gameData
            )
            Log.d(TAG, "Cache updated for slot $slot")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update cache for slot $slot", e)
        }
    }

    private fun recordSuccess(type: SaveType, elapsedMs: Long) {
        totalSaves.incrementAndGet()
        totalSaveTime.addAndGet(elapsedMs)
        
        when (type) {
            SaveType.FULL -> fullSaves.incrementAndGet()
            SaveType.INCREMENTAL -> incrementalSaves.incrementAndGet()
            SaveType.AUTO -> {}
        }
    }

    private fun recordFailure() {
        failedSaves.incrementAndGet()
    }

    private fun updateStats() {
        val total = totalSaves.get()
        _stats.value = SaveCoordinatorStats(
            totalSaves = total,
            fullSaves = fullSaves.get(),
            incrementalSaves = incrementalSaves.get(),
            failedSaves = failedSaves.get(),
            averageSaveTimeMs = if (total > 0) totalSaveTime.get() / total else 0,
            lastSaveTime = System.currentTimeMillis(),
            pendingOperations = 0
        )
    }

    fun isSaveInProgress(): Boolean = isSaving.get()

    fun getLastSaveType(): SaveType = lastSaveType

    fun getSavesSinceLastFull(): Int = savesSinceLastFull

    fun resetIncrementalCounter() {
        savesSinceLastFull = 0
    }

    suspend fun forceFullSaveNext(): Boolean {
        savesSinceLastFull = config.incrementalSaveThreshold
        return true
    }
}

data class CoordinatorValidationResult(
    val isValid: Boolean,
    val error: String = ""
)

private fun <T> StorageResult<T>.toCoordinatorResult(): SaveResult<SaveOperationStats> {
    return when (this) {
        is StorageResult.Success -> {
            val stats = this.data as? SaveOperationStats ?: SaveOperationStats()
            SaveResult.success(stats)
        }
        is StorageResult.Failure -> {
            SaveResult.failure(SaveError.SAVE_FAILED, this.message, this.cause)
        }
    }
}
