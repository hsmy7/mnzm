package com.xianxia.sect.data.strategy

import android.util.Log
import com.xianxia.sect.data.incremental.IncrementalStorageManager
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.model.SaveSlot
import com.xianxia.sect.data.unified.SaveError
import com.xianxia.sect.data.unified.SaveResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

import kotlin.math.max
import kotlin.math.min

data class CoordinatorStats(
    val totalSaves: Long = 0,
    val totalLoads: Long = 0,
    val incrementalSaves: Long = 0,
    val fullSaves: Long = 0,
    val totalBytesWritten: Long = 0,
    val avgSaveTimeMs: Long = 0,
    val avgLoadTimeMs: Long = 0,
    val currentDeltaChainLength: Int = 0,
    val pendingChanges: Int = 0,
    val lastSaveType: StorageType = StorageType.FULL
)

data class CoordinatorConfig(
    val enableIncremental: Boolean = true,
    val maxDeltaChainLength: Int = 50,
    val compactionThreshold: Int = 10,
    val forceFullSaveInterval: Int = 20
)

@Singleton
class CoordinatedStorageManager @Inject constructor(
    private val fullStrategy: FullStorageStrategy,
    private val incrementalStrategy: IncrementalStorageStrategy,
    private val incrementalManager: IncrementalStorageManager,
    private val config: CoordinatorConfig = CoordinatorConfig()
) {
    companion object {
        private const val TAG = "CoordinatedStorage"
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val saveMutex = Mutex()
    
    private val strategies: List<StorageStrategy> = listOf(
        incrementalStrategy,
        fullStrategy
    ).sortedByDescending { it.priority }
    
    private var saveCount = 0L
    private var loadCount = 0L
    private var incrementalSaveCount = 0L
    private var fullSaveCount = 0L
    private var totalBytesWritten = 0L
    private var totalSaveTime = 0L
    private var totalLoadTime = 0L
    private var savesSinceLastFull = 0
    private var lastSaveType = StorageType.FULL
    
    
    private val _stats = MutableStateFlow(CoordinatorStats())
    val stats: StateFlow<CoordinatorStats> = _stats.asStateFlow()
    
    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()
    
    suspend fun save(slot: Int, data: SaveData, type: StorageType = StorageType.AUTO): SaveResult<StorageStats> {
        return saveMutex.withLock {
            _isSaving.value = true
            val startTime = System.currentTimeMillis()
            
            try {
                val context = buildStorageContext(slot)
                val strategy = selectStrategy(slot, type, context)
                
                Log.i(TAG, "Selected strategy '${strategy.name}' for slot $slot save")
                
                val result = strategy.save(slot, data)
                
                if (result.isSuccess) {
                    val stats = result.getOrThrow()
                    saveCount++
                    totalBytesWritten += stats.bytesWritten
                    totalSaveTime += System.currentTimeMillis() - startTime
                    
                    if (stats.isIncremental) {
                        incrementalSaveCount++
                        savesSinceLastFull++
                        lastSaveType = StorageType.INCREMENTAL
                        
                        checkAndCompact(slot)
                    } else {
                        fullSaveCount++
                        savesSinceLastFull = 0
                        lastSaveType = StorageType.FULL
                    }
                    
                    updateStats()
                }
                
                result
            } catch (e: Exception) {
                Log.e(TAG, "Save failed for slot $slot", e)
                SaveResult.failure(SaveError.SAVE_FAILED, e.message ?: "Unknown error", e)
            } finally {
                _isSaving.value = false
            }
        }
    }
    
    suspend fun load(slot: Int): SaveResult<SaveData> {
        val startTime = System.currentTimeMillis()
        
        val incrementalResult = incrementalStrategy.load(slot)
        
        val result = when {
            incrementalResult.isSuccess -> {
                incrementalResult
            }
            incrementalResult.isFailure -> {
                val error = when (incrementalResult) {
                    is SaveResult.Failure -> incrementalResult.error
                    else -> SaveError.LOAD_FAILED
                }
                
                when (error) {
                    SaveError.SLOT_EMPTY, SaveError.NOT_FOUND -> {
                        Log.d(TAG, "No incremental data for slot $slot, trying full storage")
                        fullStrategy.load(slot)
                    }
                    else -> {
                        Log.w(TAG, "Incremental data corrupted for slot $slot (error: $error), falling back to full storage")
                        val fullResult = fullStrategy.load(slot)
                        if (fullResult.isSuccess) {
                            Log.i(TAG, "Recovered slot $slot from full storage after incremental corruption")
                        }
                        fullResult
                    }
                }
            }
            else -> fullStrategy.load(slot)
        }
        
        loadCount++
        totalLoadTime += System.currentTimeMillis() - startTime
        updateStats()
        
        return result
    }
    
    suspend fun delete(slot: Int): SaveResult<Unit> {
        return saveMutex.withLock {
            try {
                val incResult = incrementalStrategy.delete(slot)
                val fullResult = fullStrategy.delete(slot)
                
                if (incResult.isSuccess || fullResult.isSuccess) {
                    SaveResult.success(Unit)
                } else {
                    SaveResult.failure(SaveError.DELETE_FAILED, "Failed to delete save data")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Delete failed for slot $slot", e)
                SaveResult.failure(SaveError.DELETE_FAILED, e.message ?: "Unknown error", e)
            }
        }
    }
    
    suspend fun hasSave(slot: Int): Boolean {
        return incrementalStrategy.hasSave(slot) || fullStrategy.hasSave(slot)
    }
    
    suspend fun getSlotInfo(slot: Int): SaveSlot? {
        return incrementalStrategy.getSlotInfo(slot) ?: fullStrategy.getSlotInfo(slot)
    }
    
    suspend fun getSaveSlots(): List<SaveSlot> {
        return coroutineScope {
            (1..5).mapNotNull { slot ->
                getSlotInfo(slot)
            }
        }
    }
    
    suspend fun forceFullSave(slot: Int, data: SaveData): SaveResult<StorageStats> {
        return save(slot, data, StorageType.FULL)
    }
    
    suspend fun compact(slot: Int): Boolean {
        return incrementalManager.compact(slot)
    }
    
    fun hasPendingChanges(): Boolean = incrementalManager.hasPendingChanges()
    
    fun getPendingChangeCount(): Int = incrementalManager.getPendingChangeCount()
    
    private suspend fun buildStorageContext(slot: Int): StorageContext {
        val chain = incrementalManager.getDeltaChain(slot)
        
        return StorageContext(
            hasExistingSave = hasSave(slot),
            hasPendingChanges = incrementalManager.hasPendingChanges(),
            deltaChainLength = chain?.chainLength ?: 0,
            lastSaveWasIncremental = lastSaveType == StorageType.INCREMENTAL,
            saveCountSinceLastFull = savesSinceLastFull
        )
    }
    
    private fun selectStrategy(slot: Int, type: StorageType, context: StorageContext): StorageStrategy {
        if (!config.enableIncremental) {
            return fullStrategy
        }
        
        when (type) {
            StorageType.FULL -> return fullStrategy
            StorageType.INCREMENTAL -> {
                if (incrementalStrategy.isApplicable(slot, context)) {
                    return incrementalStrategy
                }
                return fullStrategy
            }
            StorageType.AUTO -> {
                if (savesSinceLastFull >= config.forceFullSaveInterval) {
                    Log.d(TAG, "Forcing full save after ${savesSinceLastFull} incremental saves")
                    return fullStrategy
                }
                
                for (strategy in strategies) {
                    if (strategy.isApplicable(slot, context)) {
                        return strategy
                    }
                }
                
                return fullStrategy
            }
        }
    }
    
    private suspend fun checkAndCompact(slot: Int) {
        val chain = incrementalManager.getDeltaChain(slot)
        if (chain != null && chain.chainLength >= config.compactionThreshold) {
            Log.i(TAG, "Delta chain length ${chain.chainLength} exceeds threshold, compacting")
            incrementalManager.compact(slot)
        }
    }
    
    private fun updateStats() {
        _stats.value = CoordinatorStats(
            totalSaves = saveCount,
            totalLoads = loadCount,
            incrementalSaves = incrementalSaveCount,
            fullSaves = fullSaveCount,
            totalBytesWritten = totalBytesWritten,
            avgSaveTimeMs = if (saveCount > 0) totalSaveTime / saveCount else 0,
            avgLoadTimeMs = if (loadCount > 0) totalLoadTime / loadCount else 0,
            currentDeltaChainLength = incrementalManager.stats.value.deltaChainLength,
            pendingChanges = incrementalManager.stats.value.pendingChanges,
            lastSaveType = lastSaveType
        )
    }
    
    fun shutdown() {
        incrementalManager.shutdown()
        scope.cancel()
        Log.i(TAG, "CoordinatedStorageManager shutdown completed")
    }
}
