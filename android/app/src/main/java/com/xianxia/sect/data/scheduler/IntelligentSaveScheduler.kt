package com.xianxia.sect.data.scheduler

import android.util.Log
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.data.cache.SlotAwareCacheKey
import com.xianxia.sect.data.cache.SlotIsolatedCacheManager
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.unified.UnifiedSaveRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

enum class MemoryPressure {
    NONE,
    LOW,
    MODERATE,
    HIGH,
    CRITICAL
}

enum class GameState {
    IDLE,
    IN_MENU,
    IN_GAME,
    IN_BATTLE,
    IN_DIALOG,
    LOADING
}

data class SaveSchedulerConfig(
    val baseAutoSaveIntervalMs: Long = 60_000L,
    val minAutoSaveIntervalMs: Long = 15_000L,
    val maxAutoSaveIntervalMs: Long = 300_000L,
    val changeThreshold: Int = 50,
    val memoryCriticalThreshold: Float = 0.9f,
    val debounceMs: Long = 5_000L
)

data class SchedulerStats(
    val totalSaves: Long = 0,
    val autoSaves: Long = 0,
    val manualSaves: Long = 0,
    val emergencySaves: Long = 0,
    val skippedSaves: Long = 0,
    val averageSaveTimeMs: Long = 0,
    val pendingChanges: Int = 0,
    val currentIntervalMs: Long = 0
)

@Singleton
class IntelligentSaveScheduler @Inject constructor(
    private val saveRepository: UnifiedSaveRepository,
    private val cacheManager: SlotIsolatedCacheManager,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "IntelligentSaveScheduler"
    }
    
    private val config = SaveSchedulerConfig()
    
    private val pendingChanges = AtomicInteger(0)
    private val lastSaveTime = AtomicLong(0)
    private val lastChangeTime = AtomicLong(0)
    private val currentInterval = MutableStateFlow(config.baseAutoSaveIntervalMs)
    
    private val totalSaves = AtomicLong(0)
    private val autoSaves = AtomicLong(0)
    private val manualSaves = AtomicLong(0)
    private val emergencySaves = AtomicLong(0)
    private val skippedSaves = AtomicLong(0)
    private val totalSaveTime = AtomicLong(0)
    
    private val _memoryPressure = MutableStateFlow(MemoryPressure.NONE)
    val memoryPressure: StateFlow<MemoryPressure> = _memoryPressure.asStateFlow()
    
    private val _gameState = MutableStateFlow(GameState.IDLE)
    val gameState: StateFlow<GameState> = _gameState.asStateFlow()
    
    private val _stats = MutableStateFlow(SchedulerStats())
    val stats: StateFlow<SchedulerStats> = _stats.asStateFlow()
    
    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()
    
    private var autoSaveJob: Job? = null
    private var isShuttingDown = false
    
    private var currentSlot = 1
    private var pendingSaveData: SaveData? = null
    
    fun setCurrentSlot(slot: Int) {
        currentSlot = slot
    }
    
    fun setPendingSaveData(data: SaveData) {
        pendingSaveData = data
    }
    
    init {
        startAutoSave()
    }
    
    private fun startAutoSave() {
        autoSaveJob = scope.launch {
            while (isActive && !isShuttingDown) {
                val interval = currentInterval.value
                delay(interval)
                
                if (shouldAutoSave()) {
                    performAutoSave()
                }
            }
        }
    }
    
    fun recordChange() {
        pendingChanges.incrementAndGet()
        lastChangeTime.set(System.currentTimeMillis())
    }
    
    fun recordChanges(count: Int) {
        pendingChanges.addAndGet(count)
        lastChangeTime.set(System.currentTimeMillis())
    }
    
    fun setGameState(state: GameState) {
        _gameState.value = state
        adjustInterval(state)
    }
    
    fun setMemoryPressure(pressure: MemoryPressure) {
        _memoryPressure.value = pressure
        
        if (pressure == MemoryPressure.CRITICAL) {
            scope.launch {
                triggerEmergencySave()
            }
        }
    }
    
    private fun adjustInterval(state: GameState) {
        currentInterval.value = when (state) {
            GameState.IN_BATTLE -> config.minAutoSaveIntervalMs
            GameState.IN_DIALOG -> config.baseAutoSaveIntervalMs / 2
            GameState.IN_MENU -> config.baseAutoSaveIntervalMs
            GameState.IN_GAME -> config.baseAutoSaveIntervalMs
            GameState.IDLE -> config.maxAutoSaveIntervalMs
            GameState.LOADING -> config.maxAutoSaveIntervalMs
        }
        
        Log.d(TAG, "Adjusted save interval to ${currentInterval.value}ms for state $state")
    }
    
    private fun shouldAutoSave(): Boolean {
        if (_isSaving.value) return false
        
        val now = System.currentTimeMillis()
        val timeSinceLastSave = now - lastSaveTime.get()
        val changes = pendingChanges.get()
        
        return when {
            _memoryPressure.value == MemoryPressure.CRITICAL -> true
            changes >= config.changeThreshold -> true
            timeSinceLastSave >= currentInterval.value -> true
            else -> false
        }
    }
    
    private suspend fun performAutoSave() {
        if (_isSaving.value) return
        
        _isSaving.value = true
        val startTime = System.currentTimeMillis()
        
        try {
            val changes = pendingChanges.get()
            if (changes == 0) {
                Log.d(TAG, "No changes to save")
                return
            }
            
            val dataToSave = pendingSaveData
            if (dataToSave == null) {
                Log.w(TAG, "No pending save data available, skipping auto-save")
                return
            }
            
            Log.d(TAG, "Performing auto-save with $changes pending changes to slot $currentSlot")
            
            val result = saveRepository.save(currentSlot, dataToSave)
            
            if (result.isSuccess) {
                pendingChanges.set(0)
                lastSaveTime.set(System.currentTimeMillis())
                
                autoSaves.incrementAndGet()
                totalSaves.incrementAndGet()
                
                val elapsed = System.currentTimeMillis() - startTime
                totalSaveTime.addAndGet(elapsed)
                
                Log.i(TAG, "Auto-save completed in ${elapsed}ms")
            } else {
                Log.e(TAG, "Auto-save failed")
                skippedSaves.incrementAndGet()
            }
            
            updateStats()
            
        } catch (e: Exception) {
            Log.e(TAG, "Auto-save failed", e)
            skippedSaves.incrementAndGet()
        } finally {
            _isSaving.value = false
        }
    }
    
    suspend fun manualSave(slot: Int, data: SaveData): Boolean {
        if (_isSaving.value) {
            Log.w(TAG, "Save already in progress, skipping manual save")
            return false
        }
        
        _isSaving.value = true
        val startTime = System.currentTimeMillis()
        
        return try {
            val result = saveRepository.save(slot, data)
            
            if (result.isSuccess) {
                pendingChanges.set(0)
                lastSaveTime.set(System.currentTimeMillis())
                
                manualSaves.incrementAndGet()
                totalSaves.incrementAndGet()
                
                val elapsed = System.currentTimeMillis() - startTime
                totalSaveTime.addAndGet(elapsed)
                
                Log.i(TAG, "Manual save completed in ${elapsed}ms")
                true
            } else {
                Log.e(TAG, "Manual save failed: ${result.getOrNull()}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Manual save failed", e)
            false
        } finally {
            _isSaving.value = false
            updateStats()
        }
    }
    
    suspend fun triggerEmergencySave(): Boolean {
        Log.w(TAG, "Triggering emergency save due to critical memory pressure")
        
        if (_isSaving.value) {
            Log.w(TAG, "Save already in progress, cannot perform emergency save")
            return false
        }
        
        _isSaving.value = true
        
        return try {
            val dataToSave = pendingSaveData
            if (dataToSave == null) {
                Log.w(TAG, "No pending save data for emergency save")
                return false
            }
            
            val result = saveRepository.save(currentSlot, dataToSave)
            
            if (result.isSuccess) {
                pendingChanges.set(0)
                lastSaveTime.set(System.currentTimeMillis())
                
                emergencySaves.incrementAndGet()
                totalSaves.incrementAndGet()
                
                Log.i(TAG, "Emergency save completed successfully")
                true
            } else {
                Log.e(TAG, "Emergency save failed")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Emergency save failed", e)
            false
        } finally {
            _isSaving.value = false
        }
    }
    
    suspend fun shutdownAsync() {
        isShuttingDown = true
        
        if (pendingChanges.get() > 0 && pendingSaveData != null) {
            try {
                performAutoSave()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to perform final save during shutdown", e)
            }
        }
        
        autoSaveJob?.cancel()
        scope.cancel()
        
        Log.i(TAG, "IntelligentSaveScheduler shutdown completed")
    }
    
    fun forceSave() {
        scope.launch {
            performAutoSave()
        }
    }
    
    fun getPendingChangeCount(): Int = pendingChanges.get()
    
    fun getCurrentInterval(): Long = currentInterval.value
    
    private fun updateStats() {
        val saves = totalSaves.get()
        _stats.value = SchedulerStats(
            totalSaves = saves,
            autoSaves = autoSaves.get(),
            manualSaves = manualSaves.get(),
            emergencySaves = emergencySaves.get(),
            skippedSaves = skippedSaves.get(),
            averageSaveTimeMs = if (saves > 0) totalSaveTime.get() / saves else 0,
            pendingChanges = pendingChanges.get(),
            currentIntervalMs = currentInterval.value
        )
    }
    
    fun getStats(): SchedulerStats {
        updateStats()
        return _stats.value
    }
    
    fun resetPendingChanges() {
        pendingChanges.set(0)
    }
    
    fun shutdown() {
        isShuttingDown = true
        
        scope.launch {
            if (pendingChanges.get() > 0 && pendingSaveData != null) {
                try {
                    performAutoSave()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to perform final save during shutdown", e)
                }
            }
            
            autoSaveJob?.cancel()
            scope.cancel()
            
            Log.i(TAG, "IntelligentSaveScheduler shutdown completed")
        }
    }
}
