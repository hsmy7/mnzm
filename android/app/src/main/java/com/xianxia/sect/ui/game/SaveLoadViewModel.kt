package com.xianxia.sect.ui.game

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.coordinator.SavePipeline
import com.xianxia.sect.core.state.UnifiedGameStateManager
import com.xianxia.sect.data.facade.StorageFacade
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.model.SaveSlot
import com.xianxia.sect.data.unified.SaveError
import com.xianxia.sect.data.unified.SaveResult
import com.xianxia.sect.di.ApplicationScopeProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

@HiltViewModel
class SaveLoadViewModel @Inject constructor(
    private val gameEngine: GameEngine,
    private val gameEngineCore: GameEngineCore,
    private val storageFacade: StorageFacade,
    private val stateManager: UnifiedGameStateManager,
    private val savePipeline: SavePipeline,
    private val applicationScopeProvider: ApplicationScopeProvider
) : BaseViewModel() {

    companion object {
        private const val TAG = "SaveLoadViewModel"
        private const val MB = 1024 * 1024L
        private const val MAX_CONSECUTIVE_SAVE_FAILURES = 3
        private const val SAVE_LOCK_TIMEOUT_MS = 60_000L

        private const val PROGRESS_START = 0f
        private const val PROGRESS_ENGINE_INIT = 0.2f
        private const val PROGRESS_DATA_LOAD = 0.3f
        private const val PROGRESS_SAVE_COMPLETE = 0.5f
        private const val PROGRESS_RESTART_DATA_LOAD = 0.6f
        private const val PROGRESS_GAME_LOOP_START = 0.8f
        private const val PROGRESS_COMPLETE = 1f
    }

    private val saveLock = AtomicBoolean(false)
    private val pendingAutoSave = AtomicReference<SavePipeline.SaveSource?>(null)
    private val saveLockAcquireTime = AtomicLong(0L)
    private val consecutiveSaveFailures = AtomicInteger(0)

    @Volatile
    private var _isGameLoaded = false
    val isGameLoaded: Boolean get() = _isGameLoaded

    private val _isRestarting = MutableStateFlow(false)
    val isRestarting: StateFlow<Boolean> = _isRestarting.asStateFlow()

    private val _loadingProgress = MutableStateFlow(0f)
    val loadingProgress: StateFlow<Float> = _loadingProgress.asStateFlow()

    private val _saveSlots = MutableStateFlow<List<SaveSlot>>(emptyList())
    val saveSlots: StateFlow<List<SaveSlot>> = _saveSlots.asStateFlow()

    private val _autoSaveInterval = MutableStateFlow(5)
    val autoSaveInterval: StateFlow<Int> = _autoSaveInterval.asStateFlow()

    private val _pendingSlot = MutableStateFlow<Int?>(null)
    val pendingSlot: StateFlow<Int?> = _pendingSlot.asStateFlow()

    private val _pendingAction = MutableStateFlow<String?>(null)
    val pendingAction: StateFlow<String?> = _pendingAction.asStateFlow()

    data class SaveLoadState(
        val isSaving: Boolean = false,
        val isLoading: Boolean = false,
        val pendingSlot: Int? = null,
        val pendingAction: String? = null
    ) {
        val isBusy: Boolean get() = isSaving || isLoading
    }

    val saveLoadState: StateFlow<SaveLoadState> = combine(
        stateManager.state,
        _pendingSlot,
        _pendingAction
    ) { engineState, slot, action ->
        SaveLoadState(
            isSaving = engineState.isSaving,
            isLoading = engineState.isLoading,
            pendingSlot = slot,
            pendingAction = action
        )
    }.stateIn(viewModelScope, sharingStarted, SaveLoadState())

    val isLoading: StateFlow<Boolean> = saveLoadState.map { it.isLoading }
        .stateIn(viewModelScope, sharingStarted, false)

    val isSaving: StateFlow<Boolean> = saveLoadState.map { it.isSaving }
        .stateIn(viewModelScope, sharingStarted, false)

    private val _isTimeRunning = MutableStateFlow(false)
    val isTimeRunning: StateFlow<Boolean> = _isTimeRunning.asStateFlow()

    init {
        viewModelScope.launch { _saveSlots.value = storageFacade.getSaveSlotsSuspend() }

        viewModelScope.launch {
            while (isActive) {
                gameEngineCore.autoSaveTrigger.collect {
                    try {
                        if (gameEngine.gameData.value?.autoSaveIntervalMonths ?: 0 <= 0) {
                            Log.d(TAG, "Auto save trigger received but auto-save is disabled, skipping")
                            return@collect
                        }
                        withTimeoutOrNull(30_000L) {
                            performAutoSave()
                        } ?: Log.w(TAG, "Auto save cancelled due to timeout")
                    } catch (e: CancellationException) {
                        Log.w(TAG, "Auto save cancelled", e)
                    } catch (e: Exception) {
                        Log.e(TAG, "Auto save error", e)
                    }
                }
            }
        }

        viewModelScope.launch {
            savePipeline.saveResults.collect { result ->
                if (result.success) {
                    _saveSlots.value = storageFacade.getSaveSlotsSuspend()
                    Log.d(TAG, "Save slots refreshed after save completed: slot=${result.slot}, source=${result.source}")
                }
            }
        }
    }

    private suspend fun setSaveLoadState(
        isSaving: Boolean? = null,
        isLoading: Boolean? = null,
        pendingSlot: Int? = _pendingSlot.value,
        pendingAction: String? = _pendingAction.value
    ) {
        val current = stateManager.state.value
        val finalIsSaving = isSaving ?: current.isSaving
        val finalIsLoading = isLoading ?: current.isLoading

        try { stateManager.setLoading(finalIsLoading) } catch (e: Exception) { Log.w(TAG, "Failed to sync isLoading to stateManager: ${e.message}") }
        try { stateManager.setSaving(finalIsSaving) } catch (e: Exception) { Log.w(TAG, "Failed to sync isSaving to stateManager: ${e.message}") }

        _pendingSlot.value = pendingSlot
        _pendingAction.value = pendingAction
    }

    fun cancelSaveLoad() {
        viewModelScope.launch { setSaveLoadState(isSaving = false, isLoading = false, pendingSlot = null, pendingAction = null) }
        saveLock.set(false)
    }

    fun resetSaveLoadState() {
        viewModelScope.launch {
            try { stateManager.setLoading(false) } catch (e: Exception) { Log.w(TAG, "resetSaveLoadState: setLoading failed: ${e.message}") }
            try { stateManager.setSaving(false) } catch (e: Exception) { Log.w(TAG, "resetSaveLoadState: setSaving failed: ${e.message}") }
        }
        _pendingSlot.value = null
        _pendingAction.value = null
        saveLock.set(false)
    }

    fun setPendingSave(slot: Int) {
        _pendingSlot.value = slot
        _pendingAction.value = "save"
    }

    fun setPendingLoad(slot: Int) {
        _pendingSlot.value = slot
        _pendingAction.value = "load"
    }

    fun clearPendingAction() {
        _pendingSlot.value = null
        _pendingAction.value = null
    }

    private fun canPerformSaveOperation(): Boolean {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()

        val usedMemory = totalMemory - freeMemory
        val availableMemory = maxMemory - usedMemory
        val memoryRatio = availableMemory.toDouble() / maxMemory.toDouble()
        val memoryUsagePercent = (usedMemory * 100 / maxMemory)

        if (memoryRatio < 0.4) {
            Log.w(TAG, "Low memory before save: ${memoryUsagePercent}% used, triggering GC")
            System.gc()

            val newFree = runtime.freeMemory()
            val newAvailable = maxMemory - (runtime.totalMemory() - newFree)
            if (newAvailable.toDouble() / maxMemory < 0.3) {
                Log.e(TAG, "Insufficient memory after GC: only ${newAvailable/1024/1024}MB available")
                return false
            }
        }

        Log.d(TAG, "Memory status: max=${maxMemory / MB}MB, " +
                "used=${usedMemory / MB}MB (${memoryUsagePercent}%), available=${availableMemory / MB}MB")

        return true
    }

    private fun performGarbageCollection() {
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val memoryUsagePercent = (usedMemory * 100 / runtime.maxMemory())

        if (memoryUsagePercent > 70) {
            Log.d(TAG, "High memory usage before save: ${memoryUsagePercent}%, triggering GC")
            System.gc()
        } else {
            Log.d(TAG, "Memory usage acceptable: ${memoryUsagePercent}%")
        }
    }

    private fun trimSaveData(snapshot: com.xianxia.sect.core.engine.GameStateSnapshot): SaveData {
        val maxBattleLogs = 1000
        val maxEvents = 2000
        val trimmedBattleLogs = if (snapshot.battleLogs.size > maxBattleLogs) {
            Log.w(TAG, "Trimming battleLogs: ${snapshot.battleLogs.size} -> $maxBattleLogs")
            snapshot.battleLogs.takeLast(maxBattleLogs)
        } else {
            snapshot.battleLogs
        }
        val trimmedEvents = if (snapshot.events.size > maxEvents) {
            Log.w(TAG, "Trimming events: ${snapshot.events.size} -> $maxEvents")
            snapshot.events.takeLast(maxEvents)
        } else {
            snapshot.events
        }
        return SaveData(
            gameData = snapshot.gameData,
            disciples = snapshot.disciples,
            equipmentStacks = snapshot.equipmentStacks,
            equipmentInstances = snapshot.equipmentInstances,
            manualStacks = snapshot.manualStacks,
            manualInstances = snapshot.manualInstances,
            pills = snapshot.pills,
            materials = snapshot.materials,
            herbs = snapshot.herbs,
            seeds = snapshot.seeds,
            teams = snapshot.teams,
            events = trimmedEvents,
            battleLogs = trimmedBattleLogs,
            alliances = snapshot.alliances,
            productionSlots = snapshot.productionSlots
        )
    }

    private fun startGameLoop() {
        gameEngineCore.startListening()
        gameEngineCore.startGameLoop()
        _isTimeRunning.value = true
        Log.d(TAG, "Game loop started via GameEngineCore")
    }

    private fun stopGameLoop() {
        try {
            gameEngineCore.stopGameLoop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping game loop", e)
        } finally {
            _isTimeRunning.value = false
        }
    }

    private fun enqueueAutoSave(source: SavePipeline.SaveSource) {
        val lockAge = System.currentTimeMillis() - saveLockAcquireTime.get()
        if (saveLock.get() && saveLockAcquireTime.get() > 0 && lockAge > SAVE_LOCK_TIMEOUT_MS) {
            Log.e(TAG, "Save lock held for ${lockAge}ms, force releasing")
            saveLock.set(false)
            saveLockAcquireTime.set(0)
        }

        if (!saveLock.compareAndSet(false, true)) {
            pendingAutoSave.set(source)
            Log.w(TAG, "Already saving, marking pending auto save (source=$source)")
            return
        }

        saveLockAcquireTime.set(System.currentTimeMillis())

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val snapshot = gameEngine.getStateSnapshot()

                val autoSaveSlot = com.xianxia.sect.data.StorageConstants.AUTO_SAVE_SLOT
                val autoRequest = SavePipeline.SaveRequest(
                    slot = autoSaveSlot,
                    snapshot = snapshot,
                    source = source
                )
                val autoEnqueued = savePipeline.enqueue(autoRequest)

                if (!autoEnqueued) {
                    Log.w(TAG, "Auto save queue full, retrying after delay")
                    delay(2000)
                    savePipeline.enqueue(autoRequest)
                }

                consecutiveSaveFailures.set(0)
                Log.d(TAG, "Auto save enqueued for slot: $autoSaveSlot, source=$source")
            } catch (e: Exception) {
                val failures = consecutiveSaveFailures.incrementAndGet()
                Log.e(TAG, "Auto save failed (source=$source): ${e.message}", e)
                if (failures >= MAX_CONSECUTIVE_SAVE_FAILURES) {
                    showError("自动保存连续失败，请手动保存或重启游戏")
                    consecutiveSaveFailures.set(0)
                }
            } finally {
                saveLock.set(false)
                saveLockAcquireTime.set(0)
                val pendingSource = pendingAutoSave.getAndSet(null)
                if (pendingSource != null) {
                    if (pendingSource == SavePipeline.SaveSource.AUTO && (gameEngine.gameData.value?.autoSaveIntervalMonths ?: 0) <= 0) {
                        Log.d(TAG, "Discarding pending auto save because auto-save is disabled")
                    } else {
                        Log.d(TAG, "Processing pending auto save (source=$pendingSource)")
                        enqueueAutoSave(pendingSource)
                    }
                }
            }
        }
    }

    fun performAutoSave() {
        enqueueAutoSave(SavePipeline.SaveSource.AUTO)
    }

    fun performEmergencySave() {
        enqueueAutoSave(SavePipeline.SaveSource.EMERGENCY)
    }

    suspend fun createSaveData(): SaveData {
        val snapshot = gameEngine.getStateSnapshot()
        return trimSaveData(snapshot)
    }

    fun createSaveDataSync(): SaveData {
        val snapshot = gameEngine.getStateSnapshotSync()
        return trimSaveData(snapshot)
    }

    fun isGameAlreadyLoaded(): Boolean {
        return _isGameLoaded && gameEngine.gameData.value?.sectName?.isNotEmpty() == true
    }

    fun startNewGame(sectName: String, slot: Int = 1) {
        if (stateManager.state.value.isLoading && _loadingProgress.value < PROGRESS_COMPLETE) {
            Log.w(TAG, "Already loading with progress ${_loadingProgress.value}, ignoring startNewGame request")
            return
        }

        if (_isGameLoaded) {
            Log.w(TAG, "Game already loaded, ignoring startNewGame request")
            return
        }

        Log.i(TAG, "=== startNewGame BEGIN === sectName=$sectName, slot=$slot")
        val startTime = System.currentTimeMillis()

        viewModelScope.launch(Dispatchers.IO) {
            var needSlotRefresh = false
            try {
                setSaveLoadState(isLoading = true, pendingSlot = slot, pendingAction = "newgame")

                _loadingProgress.value = PROGRESS_START

                _loadingProgress.value = PROGRESS_ENGINE_INIT
                Log.d(TAG, "startNewGame: Calling gameEngine.createNewGame(sectName=$sectName, slot=$slot)")
                gameEngine.createNewGame(sectName, slot)
                Log.d(TAG, "startNewGame: Game engine created new game successfully, elapsed=${System.currentTimeMillis() - startTime}ms")

                storageFacade.setCurrentSlot(slot)
                Log.d(TAG, "Active slot set to $slot")

                _loadingProgress.value = PROGRESS_SAVE_COMPLETE
                val saveSuccess = performSynchronousSave(slot)
                needSlotRefresh = true
                if (!saveSuccess) {
                    Log.e(TAG, "=== startNewGame SAVE FAILED === but continuing with game for slot $slot")
                    showError("保存失败，游戏已启动，请稍后手动保存")
                } else {
                    Log.d(TAG, "Game saved to slot $slot, elapsed=${System.currentTimeMillis() - startTime}ms")
                }

                _loadingProgress.value = PROGRESS_GAME_LOOP_START

                setSaveLoadState(isLoading = false, pendingSlot = slot, pendingAction = null)

                startGameLoop()
                Log.d(TAG, "Game loop started, isPaused=${gameEngineCore.state.value.isPaused}")

                _isGameLoaded = true
                _loadingProgress.value = PROGRESS_COMPLETE

                val gd = gameEngine.gameData.value
                Log.i(TAG, "=== startNewGame SUCCESS === " +
                    "sectName=${gd.sectName}, year=${gd.gameYear}, month=${gd.gameMonth}, day=${gd.gameDay}, " +
                    "spiritStones=${gd.spiritStones}, disciples=${gameEngine.disciples.value.size}, " +
                    "totalElapsed=${System.currentTimeMillis() - startTime}ms")
            } catch (e: Exception) {
                Log.e(TAG, "=== startNewGame FAILED === error=${e.message}", e)

                val partialGameData = gameEngine.gameData.value
                if (partialGameData.sectName.isNotEmpty() && gameEngine.disciples.value.isNotEmpty()) {
                    Log.w(TAG, "startNewGame: Game data partially initialized (sectName=${partialGameData.sectName}, disciples=${gameEngine.disciples.value.size}), attempting to start game loop anyway")
                    try {
                        setSaveLoadState(isLoading = false, pendingSlot = slot, pendingAction = null)
                        startGameLoop()
                        _isGameLoaded = true
                        Log.w(TAG, "startNewGame: Game loop started with partial data")
                    } catch (loopEx: Exception) {
                        Log.e(TAG, "startNewGame: Failed to start game loop with partial data: ${loopEx.message}", loopEx)
                    }
                } else {
                    Log.e(TAG, "startNewGame: Game data not initialized, game loop will not start")
                }

                showError(e.message ?: "开始新游戏失败")
            } finally {
                try {
                    setSaveLoadState(isLoading = false, pendingSlot = null, pendingAction = null)
                } catch (resetEx: Exception) {
                    Log.e(TAG, "startNewGame: Failed to reset loading state in finally block, forcing direct reset", resetEx)
                    try { stateManager.setLoading(false) } catch (e: Exception) { Log.w(TAG, "stateManager.setLoading(false) also failed: ${e.message}") }
                    try { stateManager.setSaving(false) } catch (e: Exception) { Log.w(TAG, "stateManager.setSaving(false) also failed: ${e.message}") }
                    _pendingSlot.value = null
                    _pendingAction.value = null
                }
                _loadingProgress.value = PROGRESS_START
                if (needSlotRefresh) {
                    try {
                        _saveSlots.value = storageFacade.getSaveSlotsSuspend()
                    } catch (e: Exception) {
                        Log.w(TAG, "startNewGame: Failed to refresh save slots after completion: ${e.message}")
                    }
                }
            }
        }
    }

    private suspend fun performSynchronousSave(slot: Int): Boolean {
        return try {
            val snapshot = gameEngine.getStateSnapshot()
            if (snapshot.gameData.sectName.isBlank()) {
                Log.e(TAG, "performSynchronousSave: gameData not initialized")
                return false
            }
            val updatedGameData = snapshot.gameData.copy(currentSlot = slot)
            val saveData = trimSaveData(snapshot).copy(gameData = updatedGameData)

            val result = withTimeoutOrNull(30_000L) {
                storageFacade.save(slot, saveData)
            }

            when {
                result == null -> {
                    Log.e(TAG, "performSynchronousSave TIMEOUT for slot $slot")
                    showError("保存超时，请稍后手动保存")
                    false
                }
                result.isSuccess -> {
                    gameEngine.updateGameData { updatedGameData }
                    _saveSlots.value = storageFacade.getSaveSlotsSuspend()
                    Log.i(TAG, "performSynchronousSave SUCCESS for slot $slot")
                    true
                }
                result is SaveResult.Failure && result.error == SaveError.KEY_DERIVATION_ERROR -> {
                    Log.e(TAG, "performSynchronousSave KEY_DERIVATION_ERROR for slot $slot")
                    showError("密钥错误：${result.message}\n请尝试清除应用数据或联系支持")
                    false
                }
                result is SaveResult.Failure && result.error == SaveError.IO_ERROR -> {
                    Log.e(TAG, "performSynchronousSave IO_ERROR for slot $slot")
                    showError("存储错误：${result.message}\n请检查存储空间或重启应用")
                    false
                }
                result is SaveResult.Failure -> {
                    Log.e(TAG, "performSynchronousSave FAILED for slot $slot: ${result.error} - ${result.message}")
                    showError("保存失败，请稍后手动保存")
                    false
                }
                else -> {
                    Log.e(TAG, "performSynchronousSave FAILED for slot $slot: unknown error")
                    showError("保存失败，请稍后手动保存")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "performSynchronousSave ERROR: ${e.message}", e)
            showError("保存错误：${e.message}")
            false
        }
    }

    fun loadGame(saveSlot: SaveSlot) {
        if (stateManager.state.value.isLoading) {
            Log.w(TAG, "Already loading, ignoring loadGame request")
            return
        }

        if (!canPerformSaveOperation()) {
            Log.e(TAG, "=== loadGame FAILED === insufficient memory")
            showError("内存不足，无法读档。请关闭其他应用后重试。")
            return
        }

        Log.i(TAG, "=== loadGame BEGIN === slot=${saveSlot.slot}, sectName=${saveSlot.sectName}, " +
            "year=${saveSlot.gameYear}, month=${saveSlot.gameMonth}")
        val startTime = System.currentTimeMillis()

        viewModelScope.launch(Dispatchers.IO) {
            try {
                setSaveLoadState(isLoading = true, pendingSlot = saveSlot.slot, pendingAction = "load")

                if (_isGameLoaded) {
                    Log.i(TAG, "Game already loaded, will reload from slot ${saveSlot.slot}")
                    stopGameLoop()
                    _isGameLoaded = false
                }

                performGarbageCollection()

                savePipeline.waitForCurrentSave(timeoutMs = 5_000L)

                Log.d(TAG, "Starting to load save data for slot ${saveSlot.slot}")
                val loadStartTime = System.currentTimeMillis()

                val saveData = withTimeoutOrNull(60_000L) {
                    try {
                        val data = storageFacade.load(saveSlot.slot).getOrNull()
                        Log.d(TAG, "Save data loaded in ${System.currentTimeMillis() - loadStartTime}ms")
                        data
                    } catch (e: Exception) {
                        Log.e(TAG, "Error loading save data: ${e.message}", e)
                        null
                    }
                }
                if (saveData == null) {
                    val elapsed = System.currentTimeMillis() - loadStartTime
                    Log.e(TAG, "=== loadGame FAILED === timeout or null for slot ${saveSlot.slot}, elapsed=${elapsed}ms")
                    showError(if (elapsed >= 60_000L) "读档超时，请重试" else "存档为空或已损坏，请重试")
                    return@launch
                }

                val effectiveSlot = com.xianxia.sect.data.StorageConstants.resolveEffectiveSlot(saveSlot.slot)
                storageFacade.setCurrentSlot(effectiveSlot)
                gameEngine.loadData(
                    gameData = saveData.gameData.copy(currentSlot = effectiveSlot),
                    disciples = saveData.disciples,
                    equipmentStacks = saveData.equipmentStacks,
                    equipmentInstances = saveData.equipmentInstances,
                    manualStacks = saveData.manualStacks,
                    manualInstances = saveData.manualInstances,
                    pills = saveData.pills,
                    materials = saveData.materials,
                    herbs = saveData.herbs,
                    seeds = saveData.seeds,
                    teams = saveData.teams,
                    events = saveData.events,
                    battleLogs = saveData.battleLogs,
                    alliances = saveData.alliances,
                    productionSlots = saveData.productionSlots
                )

                setSaveLoadState(isLoading = false, pendingSlot = saveSlot.slot, pendingAction = null)
                startGameLoop()
                _isGameLoaded = true
                showSuccess("读档成功")

                val gd = gameEngine.gameData.value
                Log.i(TAG, "=== loadGame SUCCESS === " +
                    "sectName=${gd.sectName}, year=${gd.gameYear}, month=${gd.gameMonth}, day=${gd.gameDay}, " +
                    "spiritStones=${gd.spiritStones}, disciples=${gameEngine.disciples.value.size}, " +
                    "equipment=${gameEngine.equipmentInstances.value.size}, manuals=${gameEngine.manualInstances.value.size}, " +
                    "elapsed=${System.currentTimeMillis() - startTime}ms")
            } catch (e: Exception) {
                Log.e(TAG, "=== loadGame FAILED === error=${e.message}", e)
                val partialGameData = gameEngine.gameData.value
                if (partialGameData.sectName.isNotEmpty() && gameEngine.disciples.value.isNotEmpty()) {
                    Log.w(TAG, "loadGame: Game data partially loaded, attempting to start game loop anyway")
                    try {
                        setSaveLoadState(isLoading = false, pendingSlot = saveSlot.slot, pendingAction = null)
                        startGameLoop()
                        _isGameLoaded = true
                    } catch (loopEx: Exception) {
                        Log.e(TAG, "loadGame: Failed to start game loop with partial data: ${loopEx.message}", loopEx)
                    }
                }
                showError("加载游戏失败: ${e.message}")
            } finally {
                try {
                    setSaveLoadState(isLoading = false, pendingSlot = null, pendingAction = null)
                } catch (resetEx: Exception) {
                    Log.e(TAG, "loadGame: Failed to reset loading state in finally block, forcing direct reset", resetEx)
                    try { stateManager.setLoading(false) } catch (e: Exception) { Log.w(TAG, "stateManager.setLoading(false) also failed: ${e.message}") }
                    _pendingSlot.value = null
                    _pendingAction.value = null
                }
            }
        }
    }

    fun loadGameFromSlot(slot: Int) {
        if (stateManager.state.value.isLoading) {
            Log.w(TAG, "Already loading, ignoring loadGameFromSlot request")
            return
        }

        if (!canPerformSaveOperation()) {
            Log.e(TAG, "=== loadGameFromSlot FAILED === insufficient memory")
            showError("内存不足，无法读档。请关闭其他应用后重试。")
            return
        }

        Log.i(TAG, "=== loadGameFromSlot BEGIN === slot=$slot")
        val startTime = System.currentTimeMillis()

        viewModelScope.launch(Dispatchers.IO) {
            try {
                setSaveLoadState(isLoading = true, pendingSlot = slot, pendingAction = "load")

                _loadingProgress.value = PROGRESS_START

                if (_isGameLoaded) {
                    Log.i(TAG, "Game already loaded, will reload from slot $slot")
                    stopGameLoop()
                    _isGameLoaded = false
                }

                performGarbageCollection()

                savePipeline.waitForCurrentSave(timeoutMs = 5_000L)

                Log.d(TAG, "Starting to load save data for slot $slot")
                val loadStartTime = System.currentTimeMillis()

                val saveData = withTimeoutOrNull(60_000L) {
                    try {
                        val data = storageFacade.load(slot).getOrNull()
                        Log.d(TAG, "Save data loaded in ${System.currentTimeMillis() - loadStartTime}ms")
                        data
                    } catch (e: Exception) {
                        Log.e(TAG, "Error loading save data: ${e.message}", e)
                        null
                    }
                }
                if (saveData != null) {
                    _loadingProgress.value = PROGRESS_DATA_LOAD
                    val effectiveSlot = com.xianxia.sect.data.StorageConstants.resolveEffectiveSlot(slot)
                    storageFacade.setCurrentSlot(effectiveSlot)
                    gameEngine.loadData(
                        gameData = saveData.gameData.copy(currentSlot = effectiveSlot),
                        disciples = saveData.disciples,
                        equipmentStacks = saveData.equipmentStacks,
                    equipmentInstances = saveData.equipmentInstances,
                        manualStacks = saveData.manualStacks,
                    manualInstances = saveData.manualInstances,
                        pills = saveData.pills,
                        materials = saveData.materials,
                        herbs = saveData.herbs,
                        seeds = saveData.seeds,
                        teams = saveData.teams,
                        events = saveData.events,
                        battleLogs = saveData.battleLogs,
                        alliances = saveData.alliances,
                        productionSlots = saveData.productionSlots
                    )
                    _loadingProgress.value = PROGRESS_GAME_LOOP_START

                    setSaveLoadState(isLoading = false, pendingSlot = slot, pendingAction = null)

                    startGameLoop()
                    _isGameLoaded = true
                    _loadingProgress.value = PROGRESS_COMPLETE

                    val gd = gameEngine.gameData.value
                    Log.i(TAG, "=== loadGameFromSlot SUCCESS === " +
                        "sectName=${gd.sectName}, year=${gd.gameYear}, month=${gd.gameMonth}, day=${gd.gameDay}, " +
                        "spiritStones=${gd.spiritStones}, disciples=${gameEngine.disciples.value.size}, " +
                        "equipment=${gameEngine.equipmentInstances.value.size}, manuals=${gameEngine.manualInstances.value.size}, " +
                        "elapsed=${System.currentTimeMillis() - startTime}ms")
                } else {
                    val elapsed = System.currentTimeMillis() - loadStartTime
                    Log.e(TAG, "=== loadGameFromSlot FAILED === timeout or null for slot $slot, elapsed=${elapsed}ms")
                    showError(if (elapsed >= 60_000L) "读档超时，请重试" else "存档为空或已损坏，请重试")
                }
            } catch (e: Exception) {
                Log.e(TAG, "=== loadGameFromSlot FAILED === error=${e.message}", e)

                val partialGameData = gameEngine.gameData.value
                if (partialGameData.sectName.isNotEmpty() && gameEngine.disciples.value.isNotEmpty()) {
                    Log.w(TAG, "loadGameFromSlot: Game data partially loaded, attempting to start game loop anyway")
                    try {
                        setSaveLoadState(isLoading = false, pendingSlot = slot, pendingAction = null)
                        startGameLoop()
                        _isGameLoaded = true
                        Log.w(TAG, "loadGameFromSlot: Game loop started with partial data")
                    } catch (loopEx: Exception) {
                        Log.e(TAG, "loadGameFromSlot: Failed to start game loop with partial data: ${loopEx.message}", loopEx)
                    }
                }

                showError("加载游戏失败: ${e.message}")
            } finally {
                try {
                    setSaveLoadState(isLoading = false, pendingSlot = null, pendingAction = null)
                } catch (resetEx: Exception) {
                    Log.e(TAG, "loadGameFromSlot: Failed to reset loading state in finally block, forcing direct reset", resetEx)
                    try { stateManager.setLoading(false) } catch (e: Exception) { Log.w(TAG, "stateManager.setLoading(false) also failed: ${e.message}") }
                    _pendingSlot.value = null
                    _pendingAction.value = null
                }
                _loadingProgress.value = PROGRESS_START
            }
        }
    }

    fun saveGame(slotId: String? = null) {
        if (!_isGameLoaded) {
            Log.w(TAG, "Game not loaded, ignoring saveGame request")
            return
        }

        if (!canPerformSaveOperation()) {
            Log.e(TAG, "=== saveGame FAILED === insufficient memory")
            showError("内存不足，无法保存。请关闭其他应用后重试。")
            return
        }

        val slot = slotId?.toIntOrNull() ?: gameEngine.gameData.value?.currentSlot ?: 1
        Log.i(TAG, "=== saveGame BEGIN === slot=$slot, slotId=$slotId")
        val startTime = System.currentTimeMillis()

        viewModelScope.launch(Dispatchers.IO) {
            setSaveLoadState(isSaving = true, pendingSlot = slot, pendingAction = "save")

            try {
                if (!waitForSaveLock(timeoutMs = 5000)) {
                    Log.e(TAG, "=== saveGame FAILED === saveLock busy after timeout")
                    showError("保存操作繁忙，请稍后重试")
                    return@launch
                }

                val pipelineWaitResult = savePipeline.waitForCurrentSave(timeoutMs = 10_000L)
                if (!pipelineWaitResult) {
                    Log.w(TAG, "Timed out waiting for auto-save pipeline to complete, proceeding with manual save anyway")
                }

                val previousSlot = storageFacade.getCurrentSlot()
                storageFacade.setCurrentSlot(slot)

                try {
                    performGarbageCollection()

                    val snapshot = gameEngine.getStateSnapshot()
                    if (snapshot.gameData.sectName.isBlank()) {
                        Log.e(TAG, "=== saveGame FAILED === gameData not initialized (sectName is blank)")
                        storageFacade.setCurrentSlot(previousSlot)
                        showError("游戏数据未初始化")
                        return@launch
                    }
                    val updatedGameData = snapshot.gameData.copy(currentSlot = slot)
                    val saveData = trimSaveData(snapshot).copy(gameData = updatedGameData)

                    val saveResult = withTimeoutOrNull(30_000L) {
                        storageFacade.save(slot, saveData)
                    }

                    if (saveResult != null && saveResult.isSuccess) {
                        _saveSlots.value = storageFacade.getSaveSlotsSuspend()
                        showSuccess("游戏保存成功")

                        Log.i(TAG, "=== saveGame SUCCESS === " +
                            "sectName=${snapshot.gameData.sectName}, year=${snapshot.gameData.gameYear}, " +
                            "month=${snapshot.gameData.gameMonth}, day=${snapshot.gameData.gameDay}, " +
                            "spiritStones=${snapshot.gameData.spiritStones}, " +
                            "disciples=${saveData.disciples.size}, equipment=${saveData.equipmentInstances.size}, " +
                            "manuals=${saveData.manualInstances.size}, elapsed=${System.currentTimeMillis() - startTime}ms")
                    } else {
                        storageFacade.setCurrentSlot(previousSlot)
                        val errorMsg = if (saveResult == null) "保存超时，请重试" else "保存失败，请重试"
                        showError(errorMsg)
                        Log.e(TAG, "=== saveGame FAILED === ${if (saveResult == null) "timeout" else "save returned failure"}")
                    }
                } catch (e: OutOfMemoryError) {
                    Log.e(TAG, "=== saveGame FAILED === OutOfMemoryError", e)
                    storageFacade.setCurrentSlot(previousSlot)
                    showError("内存不足，保存失败。请关闭其他应用后重试。")
                } catch (e: Exception) {
                    Log.e(TAG, "=== saveGame FAILED === error=${e.message}", e)
                    storageFacade.setCurrentSlot(previousSlot)
                    showError("保存失败: ${e.message}")
                } finally {
                    saveLock.set(false)
                    saveLockAcquireTime.set(0)
                }
            } finally {
                try {
                    setSaveLoadState(isSaving = false, pendingSlot = null, pendingAction = null)
                } catch (resetEx: Exception) {
                    Log.e(TAG, "saveGame: Failed to reset saving state in finally block, forcing direct reset", resetEx)
                    try { stateManager.setSaving(false) } catch (e: Exception) { Log.w(TAG, "stateManager.setSaving(false) also failed: ${e.message}") }
                    _pendingSlot.value = null
                    _pendingAction.value = null
                }
            }
        }
    }

    private suspend fun waitForSaveLock(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (saveLock.compareAndSet(false, true)) {
                saveLockAcquireTime.set(System.currentTimeMillis())
                return true
            }
            delay(50)
        }
        return false
    }

    fun pauseAndSaveForBackground() {
        // 关键修复：先获取快照，再停游戏循环
        // 之前的逻辑先 stopGameLoop() 再获取快照，游戏循环停止后
        // 如果有异步操作修改了状态，快照可能为空
        val snapshot = try {
            gameEngine.getStateSnapshotSync()
        } catch (e: Exception) {
            Log.e(TAG, "pauseAndSaveForBackground: failed to get snapshot: ${e.message}")
            null
        }

        stopGameLoop()

        if (!_isGameLoaded) {
            Log.d(TAG, "pauseAndSaveForBackground: game not loaded, skipping")
            return
        }

        if (snapshot == null || snapshot.gameData.sectName.isBlank()) {
            Log.w(TAG, "pauseAndSaveForBackground: snapshot is null or not initialized, skipping")
            return
        }

        // 使用 ApplicationScopeProvider 确保保存协程不被 ViewModel 生命周期取消
        // 保存数据的构建必须在协程启动前同步完成，确保数据快照在协程启动前已捕获
        try {
            val autoSaveSlot = com.xianxia.sect.data.StorageConstants.AUTO_SAVE_SLOT
            val saveData = SaveData(
                gameData = snapshot.gameData,
                disciples = snapshot.disciples,
                equipmentStacks = snapshot.equipmentStacks,
                equipmentInstances = snapshot.equipmentInstances,
                manualStacks = snapshot.manualStacks,
                manualInstances = snapshot.manualInstances,
                pills = snapshot.pills,
                materials = snapshot.materials,
                herbs = snapshot.herbs,
                seeds = snapshot.seeds,
                teams = snapshot.teams,
                events = snapshot.events,
                battleLogs = snapshot.battleLogs,
                alliances = snapshot.alliances,
                productionSlots = snapshot.productionSlots
            )
            applicationScopeProvider.ioScope.launch {
                withTimeoutOrNull(5_000L) {
                    storageFacade.save(autoSaveSlot, saveData)
                } ?: run {
                    Log.w(TAG, "pauseAndSaveForBackground: save timeout, slot: $autoSaveSlot")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "pauseAndSaveForBackground error: ${e.message}", e)
        }
    }

    fun restartGame() {
        if (!saveLock.compareAndSet(false, true)) {
            Log.w(TAG, "Already saving, ignoring restartGame request")
            return
        }
        saveLockAcquireTime.set(System.currentTimeMillis())

        if (stateManager.state.value.isLoading || _isRestarting.value) {
            Log.w(TAG, "Already loading or restarting, ignoring restartGame request")
            saveLock.set(false)
            saveLockAcquireTime.set(0)
            return
        }

        if (!canPerformSaveOperation()) {
            Log.e(TAG, "=== restartGame FAILED === insufficient memory")
            showError("内存不足，无法重置。请关闭其他应用后重试。")
            saveLock.set(false)
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val wasRunning = _isTimeRunning.value
            var previousSlot = 1
            try {
                _isRestarting.value = true

                if (wasRunning) {
                    val stopped = gameEngineCore.stopGameLoopAndWait(5000)
                    if (!stopped) {
                        Log.e(TAG, "Failed to stop game loop within timeout")
                        showError("无法停止游戏循环，请重试")
                        return@launch
                    }
                    _isTimeRunning.value = false
                    Log.d(TAG, "Game loop stopped for restart operation")
                }

                performGarbageCollection()

                val currentData = gameEngine.gameData.value
                val sectName = currentData.sectName.ifBlank { "QingYunSect" }
                val currentSlot = currentData.currentSlot.let { if (it >= 0) it else 1 }
                previousSlot = storageFacade.getCurrentSlot()

                Log.i(TAG, "=== restartGame BEGIN === currentSlot=$currentSlot, previousSlot=$previousSlot, sectName=$sectName")

                storageFacade.setCurrentSlot(currentSlot)

                gameEngine.restartGameSuspend(sectName, currentSlot)

                setSaveLoadState(isSaving = true, pendingSlot = currentSlot, pendingAction = "save")

                val saveSuccess = performRestartSave(currentSlot, previousSlot)

                if (saveSuccess) {
                    Log.i(TAG, "=== restartGame SAVE SUCCESS === slot=$currentSlot")
                    showSuccess("游戏已重置")
                } else {
                    Log.e(TAG, "=== restartGame SAVE FAILED === slot=$currentSlot")
                    showError("游戏已重置，但保存失败，请手动保存")
                }

                setSaveLoadState(isSaving = false, pendingSlot = null, pendingAction = null)
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "=== restartGame FAILED === OutOfMemoryError", e)
                storageFacade.setCurrentSlot(previousSlot)
                showError("内存不足，重置失败。请关闭其他应用后重试。")
                setSaveLoadState(isSaving = false, pendingSlot = null, pendingAction = null)
            } catch (e: Exception) {
                Log.e(TAG, "=== restartGame FAILED === error=${e.message}", e)
                storageFacade.setCurrentSlot(previousSlot)
                showError(e.message ?: "重置游戏失败")
                setSaveLoadState(isSaving = false, pendingSlot = null, pendingAction = null)
            } finally {
                _isRestarting.value = false
                saveLock.set(false)
                saveLockAcquireTime.set(0)
                if (wasRunning) {
                    gameEngineCore.startGameLoop()
                    _isTimeRunning.value = true
                    Log.d(TAG, "Game loop restarted after restart operation")
                }
            }
        }
    }

    private suspend fun performRestartSave(slot: Int, previousSlot: Int): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val snapshot = gameEngine.getStateSnapshot()
                if (snapshot.gameData.sectName.isBlank()) {
                    Log.e(TAG, "performRestartSave: gameData not initialized")
                    storageFacade.setCurrentSlot(previousSlot)
                    return@withContext false
                }

                val saveData = SaveData(
                    gameData = snapshot.gameData,
                    disciples = snapshot.disciples,
                    equipmentStacks = snapshot.equipmentStacks,
                    equipmentInstances = snapshot.equipmentInstances,
                    manualStacks = snapshot.manualStacks,
                    manualInstances = snapshot.manualInstances,
                    pills = snapshot.pills,
                    materials = snapshot.materials,
                    herbs = snapshot.herbs,
                    seeds = snapshot.seeds,
                    teams = snapshot.teams,
                    events = snapshot.events,
                    battleLogs = snapshot.battleLogs,
                    alliances = snapshot.alliances,
                    productionSlots = snapshot.productionSlots
                )

                val success = withTimeoutOrNull(30_000L) {
                    storageFacade.save(slot, saveData).isSuccess
                }

                when (success) {
                    true -> {
                        _saveSlots.value = storageFacade.getSaveSlotsSuspend()
                        Log.i(TAG, "performRestartSave success for slot $slot")
                        true
                    }
                    null -> {
                        Log.e(TAG, "performRestartSave timeout for slot $slot")
                        storageFacade.setCurrentSlot(previousSlot)
                        if (storageFacade.isSaveCorruptedSuspend(slot)) {
                            storageFacade.restoreFromBackupIfCorrupted(slot)
                            Log.w(TAG, "Save may be corrupted, attempted to restore from backup")
                        }
                        false
                    }
                    false -> {
                        Log.e(TAG, "performRestartSave failed for slot $slot")
                        storageFacade.setCurrentSlot(previousSlot)
                        false
                    }
                }
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "performRestartSave OutOfMemoryError for slot $slot", e)
                storageFacade.setCurrentSlot(previousSlot)
                false
            } catch (e: Exception) {
                Log.e(TAG, "performRestartSave error for slot $slot", e)
                storageFacade.setCurrentSlot(previousSlot)
                false
            }
        }
    }

    fun setAutoSaveInterval(interval: Int) {
        _autoSaveInterval.value = interval
    }

    fun setAutoSaveIntervalMonths(interval: Int) {
        viewModelScope.launch {
            gameEngine.updateGameData { it.copy(autoSaveIntervalMonths = interval) }
        }
    }

    fun refreshSaveSlots() {
        viewModelScope.launch { _saveSlots.value = storageFacade.getSaveSlotsSuspend() }
    }

    fun setGameLoaded(loaded: Boolean) {
        _isGameLoaded = loaded
    }

    fun resumeGameLoop() {
        viewModelScope.launch {
            gameEngineCore.resume()
        }
        if (!_isGameLoaded || stateManager.state.value.isLoading) {
            Log.d(TAG, "resumeGameLoop: Skipping startGameLoop - isGameLoaded=$_isGameLoaded, isLoading=${stateManager.state.value.isLoading}")
            return
        }
        startGameLoop()
    }

    override fun onCleared() {
        Log.i(TAG, "SaveLoadViewModel cleared")

        var snapshotToSave: com.xianxia.sect.core.engine.GameStateSnapshot? = null
        try {
            val snapshot = gameEngine.getStateSnapshotSync()
            if (snapshot.gameData.sectName.isNotBlank()) {
                snapshotToSave = snapshot
                Log.d(TAG, "Captured game snapshot for exit save in SaveLoadViewModel")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture snapshot for exit save: ${e.message}")
        }

        if (stateManager.state.value.isSaving) {
            Log.i(TAG, "Waiting for current save operation to complete before exit")
            val waitStartTime = System.currentTimeMillis()
            val maxWaitTime = 2000L
            while (stateManager.state.value.isSaving && System.currentTimeMillis() - waitStartTime < maxWaitTime) {
                Thread.sleep(100)
            }
            if (stateManager.state.value.isSaving) {
                Log.w(TAG, "Save operation did not complete within ${maxWaitTime}ms, proceeding with exit save")
            }
        }

        runBlocking { gameEngineCore.stopGameLoopAndWait(2000) }
        stateManager.setLoadingDirect(false)
        stateManager.setSavingDirect(false)
        _pendingSlot.value = null
        _pendingAction.value = null
        _loadingProgress.value = PROGRESS_START

        if (snapshotToSave != null) {
            try {
                val autoSaveSlot = com.xianxia.sect.data.StorageConstants.AUTO_SAVE_SLOT
                val saveData = SaveData(
                    gameData = snapshotToSave.gameData,
                    disciples = snapshotToSave.disciples,
                    equipmentStacks = snapshotToSave.equipmentStacks,
                    equipmentInstances = snapshotToSave.equipmentInstances,
                    manualStacks = snapshotToSave.manualStacks,
                    manualInstances = snapshotToSave.manualInstances,
                    pills = snapshotToSave.pills,
                    materials = snapshotToSave.materials,
                    herbs = snapshotToSave.herbs,
                    seeds = snapshotToSave.seeds,
                    teams = snapshotToSave.teams,
                    events = snapshotToSave.events,
                    battleLogs = snapshotToSave.battleLogs,
                    alliances = snapshotToSave.alliances,
                    productionSlots = snapshotToSave.productionSlots
                )
                val result = runBlocking(Dispatchers.IO) {
                    withTimeoutOrNull(2_000L) {
                        storageFacade.save(autoSaveSlot, saveData)
                    } ?: SaveResult.failure(SaveError.TIMEOUT, "Save timeout on exit")
                }
                if (result.isSuccess) {
                    Log.i(TAG, "Exit save completed in SaveLoadViewModel, slot: $autoSaveSlot")
                } else {
                    Log.w(TAG, "Exit save failed in SaveLoadViewModel, slot: $autoSaveSlot")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exit save failed in SaveLoadViewModel: ${e.message}")
            }
        }

        stateManager.setPausedDirect(true)
        super.onCleared()
    }

    fun togglePause() {
        viewModelScope.launch {
            if (gameEngineCore.state.value.isPaused) {
                gameEngineCore.resume()
                if (!gameEngineCore.isGameLoopRunning) {
                    startGameLoop()
                }
            } else {
                gameEngineCore.pause()
            }
        }
    }

    private val _timeScale = MutableStateFlow(1)
    val timeScale: StateFlow<Int> = _timeScale.asStateFlow()

    val timeSpeed: StateFlow<Int> = timeScale

    val isPaused: StateFlow<Boolean> = gameEngineCore.state
        .map { it.isPaused }
        .stateIn(viewModelScope, SharingStarted.Lazily, true)

    fun setTimeSpeed(speed: Int) {
        _timeScale.value = speed
        if (gameEngineCore.state.value.isPaused) {
            viewModelScope.launch {
                gameEngineCore.resume()
                if (!gameEngineCore.isGameLoopRunning) {
                    startGameLoop()
                }
            }
        }
    }

    fun resetAllDisciplesStatus() {
        viewModelScope.launch {
            gameEngine.resetAllDisciplesStatus()
        }
    }
}
