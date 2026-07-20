package com.xianxia.sect.ui.game

import android.content.Context
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.MapPreloadData
import com.xianxia.sect.core.engine.*
import com.xianxia.sect.core.engine.domain.save.SavePipeline
import com.xianxia.sect.core.state.*
import com.xianxia.sect.core.util.SectMapTileGenerator
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import com.xianxia.sect.data.facade.StorageFacade
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.model.SaveSlot
import com.xianxia.sect.data.unified.SaveError
import com.xianxia.sect.data.unified.SaveResult
import com.xianxia.sect.core.engine.domain.disciple.DiscipleSnapshotCache
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.ui.components.AtlasResult
import com.xianxia.sect.ui.game.saveload.SaveLoadLoadDelegate
import com.xianxia.sect.ui.game.saveload.SaveLoadPauseDelegate
import com.xianxia.sect.ui.game.saveload.SaveLoadRestartDelegate
import com.xianxia.sect.ui.game.saveload.SaveLoadSaveDelegate
import com.xianxia.sect.ui.game.saveload.SaveLoadStateDelegate
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
    private val stateStore: GameStateStore,
    private val savePipeline: SavePipeline,
    private val coroutineScopeProvider: CoroutineScopeProvider,
    private val buildingConfigService: BuildingConfigService,
    @ApplicationContext private val context: Context,
    private val gameClock: com.xianxia.sect.core.engine.system.GameTimeClock,
    private val resourcePreloader: ResourcePreloader,
    private val discipleSnapshotCache: DiscipleSnapshotCache,
    private val gameRngManager: GameRngManager,
    private val bootSequenceController: BootSequenceController,
    private val spiritStoneWallet: SpiritStoneWallet
) : BaseViewModel() {

    // 领域委托实例 — 按职责拆分 save/load/restart 等逻辑
    private val saveDelegate by lazy { SaveLoadSaveDelegate(gameEngine, storageFacade, stateStore, savePipeline) }
    private val loadDelegate by lazy {
        SaveLoadLoadDelegate(gameEngine, gameEngineCore, storageFacade, stateStore, savePipeline, buildingConfigService, spiritStoneWallet)
    }
    private val restartDelegate by lazy { SaveLoadRestartDelegate(gameEngine, gameEngineCore, storageFacade, stateStore) }
    private val pauseDelegate by lazy { SaveLoadPauseDelegate(gameEngineCore, gameClock) }
    private val stateDelegate by lazy { SaveLoadStateDelegate(stateStore) }

    companion object {
        private const val TAG = SaveLoadViewModelConstants.TAG
        private const val MB = SaveLoadViewModelConstants.MB
        private const val MAX_CONSECUTIVE_SAVE_FAILURES = SaveLoadViewModelConstants.MAX_CONSECUTIVE_SAVE_FAILURES
        private const val SAVE_LOCK_TIMEOUT_MS = SaveLoadViewModelConstants.SAVE_LOCK_TIMEOUT_MS

        private const val PROGRESS_START = SaveLoadViewModelConstants.PROGRESS_START
        private const val PROGRESS_ENGINE_INIT = SaveLoadViewModelConstants.PROGRESS_ENGINE_INIT
        private const val PROGRESS_DATA_LOAD = SaveLoadViewModelConstants.PROGRESS_DATA_LOAD
        private const val PROGRESS_SAVE_COMPLETE = SaveLoadViewModelConstants.PROGRESS_SAVE_COMPLETE
        private const val PROGRESS_RESTART_DATA_LOAD = SaveLoadViewModelConstants.PROGRESS_RESTART_DATA_LOAD
        private const val PROGRESS_DATA_PRELOAD = SaveLoadViewModelConstants.PROGRESS_DATA_PRELOAD
        private const val PROGRESS_SPRITE_PRELOAD = SaveLoadViewModelConstants.PROGRESS_SPRITE_PRELOAD
        private const val PROGRESS_GAME_LOOP_START = SaveLoadViewModelConstants.PROGRESS_GAME_LOOP_START
        const val PROGRESS_MAP_PRELOAD = SaveLoadViewModelConstants.PROGRESS_MAP_PRELOAD
        private const val PROGRESS_COMPLETE = SaveLoadViewModelConstants.PROGRESS_COMPLETE
    }

    private suspend fun preloadGameResources() {
        _preloadPhase.value = SaveLoadViewModelConstants.PHASE_DATA_PRELOAD
        val result = resourcePreloader.preloadGameResources(
            onProgress = { _loadingProgress.value = it },
            onPhase = { _preloadPhase.value = it }
        )
        _preloadedItemSprites.value = result.itemSprites
        _atlasResult.value = result.itemAtlas
        _preloadedPortraitSprites.value = result.portraitSprites
        _preloadedUiSprites.value = result.uiSprites
    }

    // generateMapPreloadData removed — now handled by BootSequenceController internally

    /**
     * 启动 L2 后台精灵图预加载（不阻塞首帧）
     * 在 MainGameScreen 已显示后调用
     */
    fun launchL2Preload() {
        resourcePreloader.launchBackgroundPreload(viewModelScope) { sprites ->
            _l2Sprites.value = _l2Sprites.value + sprites
        }
    }

    private val saveLock = AtomicBoolean(false)
    private val pendingAutoSave = AtomicReference<SavePipeline.SaveSource?>(null)
    private val saveLockAcquireTime = AtomicLong(0L)
    private val consecutiveSaveFailures = AtomicInteger(0)

    // 游戏是否已加载 = RunState.PLAYING
    val isGameLoaded: Boolean get() = stateStore.runState.value == RunState.PLAYING

    private val _isRestarting = MutableStateFlow(false)
    val isRestarting: StateFlow<Boolean> = _isRestarting.asStateFlow()

    private val _loadingProgress = MutableStateFlow(0f)
    val loadingProgress: StateFlow<Float> = _loadingProgress.asStateFlow()

    private val _preloadedItemSprites = MutableStateFlow<Map<Int, ImageBitmap>>(emptyMap())
    val preloadedItemSprites: StateFlow<Map<Int, ImageBitmap>> = _preloadedItemSprites.asStateFlow()

    private val _preloadedPortraitSprites = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val preloadedPortraitSprites: StateFlow<Map<String, ImageBitmap>> = _preloadedPortraitSprites.asStateFlow()

    private val _preloadedUiSprites = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val preloadedUiSprites: StateFlow<Map<String, ImageBitmap>> = _preloadedUiSprites.asStateFlow()

    private val _atlasResult = MutableStateFlow<AtlasResult?>(null)
    val atlasResult: StateFlow<AtlasResult?> = _atlasResult.asStateFlow()

    /** L2 后台加载的剩余精灵图（异步累积，不阻塞首帧） */
    private val _l2Sprites = MutableStateFlow<Map<Int, ImageBitmap>>(emptyMap())
    val l2Sprites: StateFlow<Map<Int, ImageBitmap>> = _l2Sprites.asStateFlow()

    /** 预加载阶段标签（UI 展示用） */
    private val _preloadPhase = MutableStateFlow(SaveLoadViewModelConstants.PHASE_INIT)
    val preloadPhase: StateFlow<String> = _preloadPhase.asStateFlow()

    /** 地图预加载数据 — 由加载管线在游戏循环启动后生成，GameActivity 消费 */
    private val _mapPreloadData = MutableStateFlow<MapPreloadData?>(null)
    val mapPreloadData: StateFlow<MapPreloadData?> = _mapPreloadData.asStateFlow()

    /** 游戏生命周期（纯运行时，不随存档保存） */
    val gameLifecycle: StateFlow<GameLifecycle> get() = stateStore.gameLifecycle

    /** 运行时状态：IDLE / LOADING / PLAYING / RELOADING */
    val runState: StateFlow<RunState> get() = stateStore.runState

    /** 启动序列阶段：UNINITIALIZED / DATA_READY / SYSTEMS_READY / MAP_READY / BOOT_COMPLETE */
    val bootPhase: StateFlow<BootPhase> get() = stateStore.bootPhase

    private val _saveSlots = MutableStateFlow<List<SaveSlot>>(emptyList())
    val saveSlots: StateFlow<List<SaveSlot>> = _saveSlots.asStateFlow()

    private val _autoSaveInterval = MutableStateFlow(5)
    val autoSaveInterval: StateFlow<Int> = _autoSaveInterval.asStateFlow()

    private val _pendingSlot = MutableStateFlow<Int?>(null)
    val pendingSlot: StateFlow<Int?> = _pendingSlot.asStateFlow()

    private val _pendingAction = MutableStateFlow<String?>(null)
    val pendingAction: StateFlow<String?> = _pendingAction.asStateFlow()

    val saveLoadState: StateFlow<SaveLoadState> = combine(
        stateStore.unifiedState,
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

    /** 重开版本号，每次成功重开后递增，用于通知 UI 层强制重建烘焙管线 */
    private val _restartVersion = MutableStateFlow(0)
    val restartVersion: StateFlow<Int> = _restartVersion.asStateFlow()

    init {
        // 加载存档元数据 — 运行在 IO 调度器上，避免主线程等待 Room 查询
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _saveSlots.value = storageFacade.getSaveSlotsSuspend()
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                Log.e(TAG, "Failed to load save slots in init, retrying after delay", e)
                delay(500)
                try {
                    _saveSlots.value = storageFacade.getSaveSlotsSuspend()
                } catch (e: CancellationException) { throw e }
                  catch (e2: Exception) {
                    Log.e(TAG, "Retry loading save slots also failed", e2)
                }
            }
        }

        // 系统自动存档收集 — 运行在 Default 调度器上，避免 BufferedChannel.hasNext()
        // 在主线程上挂起导致 ANR（见 Bugly #3042/#8024）。
        viewModelScope.launch(Dispatchers.Default) {
            try {
                gameEngineCore.autoSaveTrigger.collect { _ ->
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
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                Log.e(TAG, "autoSaveTrigger collector crashed, will restart on next trigger", e)
            }
        }

        // 管道保存结果收集 — 运行在 Default 调度器上
        viewModelScope.launch(Dispatchers.Default) {
            try {
                savePipeline.saveResults.collect { result ->
                    if (result.success) {
                        try {
                            _saveSlots.value = storageFacade.getSaveSlotsSuspend()
                        } catch (e: CancellationException) { throw e }
                          catch (e: Exception) {
                            Log.e(TAG, "Failed to refresh slots after pipeline save: ${e.message}", e)
                        }
                        Log.d(TAG, "Save slots refreshed after save completed: slot=${result.slot}, source=${result.source}")
                    }
                }
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                Log.e(TAG, "saveResults collector crashed", e)
            }
        }
    }

    private suspend fun setSaveLoadState(
        isSaving: Boolean? = null,
        isLoading: Boolean? = null,
        pendingSlot: Int? = _pendingSlot.value,
        pendingAction: String? = _pendingAction.value
    ) {
        val current = stateStore.unifiedState.value
        val finalIsSaving = isSaving ?: current.isSaving
        val finalIsLoading = isLoading ?: current.isLoading

        try { stateStore.update { this.isLoading = finalIsLoading } } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.w(TAG, "Failed to sync isLoading to stateStore: ${e.message}") }
        try { stateStore.update { this.isSaving = finalIsSaving } } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.w(TAG, "Failed to sync isSaving to stateStore: ${e.message}") }

        _pendingSlot.value = pendingSlot
        _pendingAction.value = pendingAction
    }

    fun cancelSaveLoad() {
        viewModelScope.launch { setSaveLoadState(isSaving = false, isLoading = false, pendingSlot = null, pendingAction = null) }
        saveLock.set(false)
    }

    fun resetSaveLoadState() {
        viewModelScope.launch {
            try { stateStore.update { isLoading = false } } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.w(TAG, "resetSaveLoadState: setLoading failed: ${e.message}") }
            try { stateStore.update { isSaving = false } } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.w(TAG, "resetSaveLoadState: setSaving failed: ${e.message}") }
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

    private fun trimSaveData(snapshot: com.xianxia.sect.core.engine.GameStateSnapshot): SaveData =
        SaveDataTrimmer.trimSaveData(snapshot)

    private fun startGameLoop() {
        gameEngineCore.startListening()
        gameEngineCore.startGameLoop()
        _isTimeRunning.value = true
        Log.d(TAG, "Game loop started via GameEngineCore")
    }

    private fun stopGameLoop() {
        try {
            gameEngineCore.stopGameLoop()
        } catch (e: CancellationException) { throw e }
          catch (e: Exception) {
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
                val autoSaveSlot = com.xianxia.sect.data.StorageConstants.AUTO_SAVE_SLOT

                if (source == SavePipeline.SaveSource.AUTO) {
                    val incrementalResult = withTimeoutOrNull(15_000L) {
                        storageFacade.incrementalSave(autoSaveSlot)
                    }

                    if (incrementalResult != null && incrementalResult.isSuccess) {
                        consecutiveSaveFailures.set(0)
                        Log.d(TAG, "Auto incremental save succeeded for slot: $autoSaveSlot")
                        try {
                            _saveSlots.value = storageFacade.getSaveSlotsSuspend()
                        } catch (e: CancellationException) { throw e }
                          catch (e: Exception) {
                            Log.e(TAG, "Failed to refresh slots after incremental save", e)
                        }
                        return@launch
                    }

                    Log.w(TAG, "Incremental auto-save failed or timed out for slot $autoSaveSlot, falling back to full save")
                }

                val snapshot = gameEngine.getStateSnapshot()

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
            } catch (e: CancellationException) {
                Log.w(TAG, "Auto save cancelled (source=$source)", e)
                throw e
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
                gameEngineCore.clearActiveSaveJob()
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
        }.also { gameEngineCore.registerActiveSaveJob(it) }
    }

    fun performAutoSave() {
        enqueueAutoSave(SavePipeline.SaveSource.AUTO)
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
        return isGameLoaded && gameEngine.gameData.value?.sectName?.isNotEmpty() == true
    }

    fun setLoadingProgress(progress: Float) {
        _loadingProgress.value = progress
    }

    fun startNewGame(sectName: String, slot: Int = 1) {
        if (stateStore.unifiedState.value.isLoading && _loadingProgress.value < PROGRESS_COMPLETE) {
            Log.w(TAG, "Already loading with progress ${_loadingProgress.value}, ignoring startNewGame request")
            return
        }

        if (isGameLoaded) {
            Log.w(TAG, "Game already loaded, ignoring startNewGame request")
            return
        }

        Log.i(TAG, "=== startNewGame BEGIN === sectName=$sectName, slot=$slot")
        val startTime = System.currentTimeMillis()

        viewModelScope.launch(Dispatchers.IO) {
            var needSlotRefresh = false
            var gameStarted = false
            try {
                setSaveLoadState(isLoading = true, pendingSlot = slot, pendingAction = "newgame")

                _loadingProgress.value = PROGRESS_START

                Log.d(TAG, "startNewGame: Calling gameEngine.createNewGame(sectName=$sectName, slot=$slot)")
                gameEngine.createNewGame(sectName, slot)
                Log.d(TAG, "startNewGame: Game engine created new game successfully, elapsed=${System.currentTimeMillis() - startTime}ms")

                // 初始化 RNG 系统种子（新世界使用 mapSeed 确保确定性随机序列）
                gameRngManager.initSystemSeed(gameEngine.gameData.value.mapSeed.toLong())
                Log.d(TAG, "startNewGame: GameRngManager initialized with mapSeed=${gameEngine.gameData.value.mapSeed}")

                storageFacade.setCurrentSlot(slot)
                Log.d(TAG, "Active slot set to $slot")

                _loadingProgress.value = PROGRESS_SAVE_COMPLETE
                var saveSuccess = performSynchronousSave(slot)
                if (!saveSuccess) {
                    Log.w(TAG, "startNewGame: First save attempt failed, retrying once for slot $slot")
                    delay(500)
                    saveSuccess = performSynchronousSave(slot)
                }
                needSlotRefresh = true
                if (!saveSuccess) {
                    Log.e(TAG, "=== startNewGame SAVE FAILED AFTER RETRY === aborting game start for slot $slot")
                    showError("保存失败，无法启动游戏。请检查存储空间后重试。")
                    return@launch
                }

                // BootSequenceController 统一处理：建筑修正、BootPhase 推进、资源预加载、
                // 弟子快照预热、确保重数据加载、游戏循环启动、地图生成、最终状态切换
                val bootResult = bootSequenceController.boot(
                    slot = slot,
                    onPreloadResources = { preloadGameResources() },
                    onProgress = { progress ->
                        _loadingProgress.value = PROGRESS_START + progress * (PROGRESS_COMPLETE - PROGRESS_START)
                    },
                    onMapReady = { mapData -> _mapPreloadData.value = mapData }
                )

                if (bootResult.isSuccess) {
                    gameStarted = true
                    _loadingProgress.value = PROGRESS_COMPLETE

                    val gd = gameEngine.gameData.value
                    Log.i(TAG, "=== startNewGame SUCCESS === " +
                        "sectName=${gd.sectName}, year=${gd.gameYear}, month=${gd.gameMonth}, phase=${gd.gamePhase}, " +
                        "spiritStones=${gd.spiritStones}, disciples=${gameEngine.disciples.value.size}, " +
                        "totalElapsed=${System.currentTimeMillis() - startTime}ms")
                } else {
                    val errorMsg = bootResult.exceptionOrNull()?.message ?: "启动失败"
                    showError(errorMsg)
                }
            } catch (e: CancellationException) {
                Log.w(TAG, "startNewGame cancelled")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "=== startNewGame FAILED === error=${e.message}", e)
                showError(e.message ?: "开始新游戏失败")
            } finally {
                try {
                    setSaveLoadState(isLoading = false, pendingSlot = null, pendingAction = null)
                } catch (e: CancellationException) { throw e }
                  catch (resetEx: Exception) {
                    Log.e(TAG, "startNewGame: Failed to reset loading state in finally block, forcing direct reset", resetEx)
                    try { stateStore.update { isLoading = false } } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.w(TAG, "stateStore.update { isLoading = false } also failed: ${e.message}") }
                    try { stateStore.update { isSaving = false } } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.w(TAG, "stateStore.update { isSaving = false } also failed: ${e.message}") }
                    _pendingSlot.value = null
                    _pendingAction.value = null
                }
                gameEngineCore.clearActiveLoadJob()
                if (!gameStarted) {
                    _loadingProgress.value = PROGRESS_START
                }
                if (needSlotRefresh) {
                    try {
                        _saveSlots.value = storageFacade.getSaveSlotsSuspend()
                    } catch (e: CancellationException) { throw e }
                      catch (e: Exception) {
                        Log.w(TAG, "startNewGame: Failed to refresh save slots after completion: ${e.message}")
                    }
                }
            }
        }.also { gameEngineCore.registerActiveLoadJob(it) }
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
                    try {
                        _saveSlots.value = storageFacade.getSaveSlotsSuspend()
                    } catch (e: CancellationException) { throw e }
                      catch (e: Exception) {
                        Log.e(TAG, "Failed to refresh slots after synchronous save: ${e.message}", e)
                    }
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
        } catch (e: CancellationException) { throw e }
          catch (e: Exception) {
            Log.e(TAG, "performSynchronousSave ERROR: ${e.message}", e)
            showError("保存错误：${e.message}")
            false
        }
    }

    fun loadGame(saveSlot: SaveSlot) {
        if (stateStore.unifiedState.value.isLoading) {
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

                performGarbageCollection()

                savePipeline.waitForCurrentSave(timeoutMs = 5_000L)

                Log.d(TAG, "Starting to load save data for slot ${saveSlot.slot}")
                val loadStartTime = System.currentTimeMillis()

                val saveData = withTimeoutOrNull(60_000L) {
                    try {
                        val data = storageFacade.load(saveSlot.slot).getOrNull()
                        Log.d(TAG, "Save data loaded in ${System.currentTimeMillis() - loadStartTime}ms")
                        data
                    } catch (e: CancellationException) { throw e }
                      catch (e: Exception) {
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
                    storageBags = saveData.storageBags,
                    teams = saveData.teams,
                    battleLogs = saveData.battleLogs,
                    alliances = saveData.alliances,
                    productionSlots = saveData.productionSlots
                )

                // 恢复 RNG 分区状态，确保读档后随机序列连续性
                val loadedGd = gameEngine.gameData.value
                if (loadedGd.rngStates.isNotEmpty()) {
                    gameRngManager.restoreStates(loadedGd.rngStates)
                    Log.d(TAG, "loadGame: Restored ${loadedGd.rngStates.size} RNG partition states")
                }

                // 建筑占地重叠/越界迁移（旧存档兼容，不在 BootSequenceController 中）
                loadDelegate.migrateOverflowBuildings()

                // BootSequenceController 统一处理：建筑修正、BootPhase 推进、资源预加载、
                // 弟子快照预热、确保重数据加载、游戏循环启动、地图生成、最终状态切换
                val bootResult = bootSequenceController.boot(
                    slot = effectiveSlot,
                    onPreloadResources = { preloadGameResources() },
                    onProgress = { progress ->
                        _loadingProgress.value = PROGRESS_START + progress * (PROGRESS_COMPLETE - PROGRESS_START)
                    },
                    onMapReady = { mapData -> _mapPreloadData.value = mapData },
                    onSuccess = { showSuccess("读档成功") }
                )

                if (bootResult.isSuccess) {
                    val gd = gameEngine.gameData.value
                    Log.i(TAG, "=== loadGame SUCCESS === " +
                        "sectName=${gd.sectName}, year=${gd.gameYear}, month=${gd.gameMonth}, phase=${gd.gamePhase}, " +
                        "spiritStones=${gd.spiritStones}, disciples=${gameEngine.disciples.value.size}, " +
                        "equipment=${gameEngine.equipmentInstances.value.size}, manuals=${gameEngine.manualInstances.value.size}, " +
                        "elapsed=${System.currentTimeMillis() - startTime}ms")
                } else {
                    val errorMsg = bootResult.exceptionOrNull()?.message ?: "读档失败"
                    showError(errorMsg)
                }
            } catch (e: CancellationException) {
                Log.w(TAG, "loadGame cancelled")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "=== loadGame FAILED === error=${e.message}", e)
                showError("加载游戏失败: ${e.message}")
            } finally {
                try {
                    setSaveLoadState(isLoading = false, pendingSlot = null, pendingAction = null)
                } catch (e: CancellationException) { throw e }
                  catch (resetEx: Exception) {
                    Log.e(TAG, "loadGame: Failed to reset loading state in finally block, forcing direct reset", resetEx)
                    try { stateStore.update { isLoading = false } } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.w(TAG, "stateStore.update { isLoading = false } also failed: ${e.message}") }
                    _pendingSlot.value = null
                    _pendingAction.value = null
                }
                gameEngineCore.clearActiveLoadJob()
            }
        }.also { gameEngineCore.registerActiveLoadJob(it) }
    }

    fun loadGameFromSlot(slot: Int) {
        // 从已缓存的存档元数据中查找 SaveSlot，兜底构造最小 SaveSlot
        val saveSlot = _saveSlots.value.find { it.slot == slot }
            ?: SaveSlot(slot, "", 0L, 1, 1, "", 0, 0L)
        loadGame(saveSlot)
    }

    fun saveGame(slotId: String? = null) {
        if (!isGameLoaded) {
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
                    Log.d(TAG, "saveGame snapshot: productionSlots=${snapshot.productionSlots.size}, " +
                        "gameData.productionSlots=${snapshot.gameData.productionSlots.size}, " +
                        "disciples=${snapshot.disciples.size}, equipment=${snapshot.equipmentInstances.size}")
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
                        try {
                            _saveSlots.value = storageFacade.getSaveSlotsSuspend()
                        } catch (e: CancellationException) { throw e }
                          catch (e: Exception) {
                            Log.e(TAG, "Failed to refresh slots after successful save: ${e.message}", e)
                        }
                        showSuccess("游戏保存成功")

                        Log.i(TAG, "=== saveGame SUCCESS === " +
                            "sectName=${snapshot.gameData.sectName}, year=${snapshot.gameData.gameYear}, " +
                            "month=${snapshot.gameData.gameMonth}, phase=${snapshot.gameData.gamePhase}, " +
                            "spiritStones=${snapshot.gameData.spiritStones}, " +
                            "disciples=${saveData.disciples.size}, equipment=${saveData.equipmentInstances.size}, " +
                            "manuals=${saveData.manualInstances.size}, elapsed=${System.currentTimeMillis() - startTime}ms")
                    } else {
                        storageFacade.setCurrentSlot(previousSlot)
                        val errorMsg = if (saveResult == null) "保存超时，请重试" else "保存失败，请重试"
                        showError(errorMsg)
                        Log.e(TAG, "=== saveGame FAILED === ${if (saveResult == null) "timeout" else "save returned failure"}")
                        try { _saveSlots.value = storageFacade.getSaveSlotsSuspend() } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.e(TAG, "Failed to refresh slots after save failure", e) }
                    }
                } catch (e: OutOfMemoryError) {
                    Log.e(TAG, "=== saveGame FAILED === OutOfMemoryError", e)
                    storageFacade.setCurrentSlot(previousSlot)
                    showError("内存不足，保存失败。请关闭其他应用后重试。")
                    try { _saveSlots.value = storageFacade.getSaveSlotsSuspend() } catch (e: CancellationException) { throw e } catch (e2: Exception) { Log.e(TAG, "Failed to refresh slots after OOM", e2) }
                } catch (e: CancellationException) {
                    Log.w(TAG, "saveGame cancelled")
                    storageFacade.setCurrentSlot(previousSlot)
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "=== saveGame FAILED === error=${e.message}", e)
                    storageFacade.setCurrentSlot(previousSlot)
                    showError("保存失败: ${e.message}")
                    try { _saveSlots.value = storageFacade.getSaveSlotsSuspend() } catch (e: CancellationException) { throw e } catch (e2: Exception) { Log.e(TAG, "Failed to refresh slots after save failure", e2) }
                } finally {
                    saveLock.set(false)
                    saveLockAcquireTime.set(0)
                }
            } finally {
                try {
                    setSaveLoadState(isSaving = false, pendingSlot = null, pendingAction = null)
                } catch (e: CancellationException) { throw e }
                  catch (resetEx: Exception) {
                    Log.e(TAG, "saveGame: Failed to reset saving state in finally block, forcing direct reset", resetEx)
                    try { stateStore.update { isSaving = false } } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.w(TAG, "stateStore.update { isSaving = false } also failed: ${e.message}") }
                    _pendingSlot.value = null
                    _pendingAction.value = null
                }
                gameEngineCore.clearActiveSaveJob()
            }
        }.also { gameEngineCore.registerActiveSaveJob(it) }
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

    /**
     * 暂停游戏循环（不保存）
     *
     * 仅停止游戏循环和后台结算工作，不触发存档。
     * 用于 onStop 等快速切后台场景，避免不完整序列化落盘覆盖正确存档。
     */
    fun pauseForBackground() {
        Log.d(TAG, "pauseForBackground: stopping game loop only")
        stopGameLoop()
        gameEngineCore.pauseForBackground()
    }

    /**
     * 恢复游戏循环（不加载存档）
     *
     * 仅恢复游戏循环，不触发存档加载。
     * 用于 onResume 等切回前台场景，与 [pauseForBackground] 对应。
     */
    fun resumeFromBackground() {
        Log.d(TAG, "resumeFromBackground: resuming game loop")
        gameEngineCore.resumeFromBackground()
        _isTimeRunning.value = gameEngineCore.isGameLoopRunning
    }

    fun restartGame() {
        if (!saveLock.compareAndSet(false, true)) {
            Log.w(TAG, "Already saving, ignoring restartGame request")
            return
        }
        saveLockAcquireTime.set(System.currentTimeMillis())

        if (stateStore.unifiedState.value.isLoading || _isRestarting.value) {
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

                // 重置 RNG 系统种子（重启即新世界，初始化确定性随机序列）
                gameRngManager.initSystemSeed(gameEngine.gameData.value.mapSeed.toLong())
                Log.d(TAG, "restartGame: GameRngManager initialized with mapSeed=${gameEngine.gameData.value.mapSeed}")

                setSaveLoadState(isSaving = true, pendingSlot = currentSlot, pendingAction = "save")

                val saveSuccess = performRestartSave(currentSlot, previousSlot)

                setSaveLoadState(isSaving = false, pendingSlot = null, pendingAction = null)

                if (saveSuccess) {
                    Log.i(TAG, "=== restartGame SAVE SUCCESS === slot=$currentSlot")
                    _restartVersion.value++
                    showSuccess("游戏已重置")
                } else {
                    Log.e(TAG, "=== restartGame SAVE FAILED === slot=$currentSlot")
                    showError("游戏已重置，但保存失败，请手动保存")
                }

                // BootSequenceController 统一处理生命周期、游戏循环重启、地图生成
                val bootResult = bootSequenceController.boot(
                    slot = currentSlot,
                    onPreloadResources = { preloadGameResources() },
                    onProgress = { progress ->
                        _loadingProgress.value = PROGRESS_START + progress * (PROGRESS_COMPLETE - PROGRESS_START)
                    },
                    onMapReady = { mapData -> _mapPreloadData.value = mapData }
                )

                if (bootResult.isSuccess) {
                    _isTimeRunning.value = true
                } else {
                    Log.e(TAG, "restartGame: boot sequence failed after restart, error=${bootResult.exceptionOrNull()?.message}")
                }
            } catch (e: CancellationException) {
                Log.w(TAG, "restartGame cancelled")
                throw e
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
                gameEngineCore.clearActiveSaveJob()
                // 兜底：若 boot() 未执行（提前 return），则仅记录日志
                // BootSequenceController.boot() 在内部已处理游戏循环恢复
                if (wasRunning && !_isTimeRunning.value) {
                    Log.w(TAG, "restartGame: game loop not running after restart finally, boot() may have failed")
                }
            }
        }.also { gameEngineCore.registerActiveSaveJob(it) }
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

                Log.d(TAG, "performRestartSave snapshot: productionSlots=${snapshot.productionSlots.size}, " +
                    "gameData.productionSlots=${snapshot.gameData.productionSlots.size}, " +
                    "disciples=${snapshot.disciples.size}")

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
                        battleLogs = snapshot.battleLogs,
                    alliances = snapshot.alliances,
                    productionSlots = snapshot.productionSlots,
                    storageBags = snapshot.storageBags
                )

                val success = withTimeoutOrNull(30_000L) {
                    storageFacade.save(slot, saveData).isSuccess
                }

                when (success) {
                    true -> {
                        try {
                            _saveSlots.value = storageFacade.getSaveSlotsSuspend()
                        } catch (e: CancellationException) { throw e }
                          catch (e: Exception) {
                            Log.e(TAG, "Failed to refresh slots after restart save: ${e.message}", e)
                        }
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
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
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
        viewModelScope.launch {
            try {
                _saveSlots.value = storageFacade.getSaveSlotsSuspend()
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                Log.e(TAG, "refreshSaveSlots failed", e)
            }
        }
    }

    fun setGameLoaded(loaded: Boolean) {
        // 生命周期由 BootSequenceController 管理，此方法保留用于外部兼容
        Log.d(TAG, "setGameLoaded($loaded) called — lifecycle managed by BootSequenceController, ignoring")
    }

    fun resumeGameLoop() {
        viewModelScope.launch {
            gameEngineCore.resume()
        }
        if (!isGameLoaded || stateStore.unifiedState.value.isLoading) {
            Log.d(TAG, "resumeGameLoop: Skipping startGameLoop - isGameLoaded=$isGameLoaded, isLoading=${stateStore.unifiedState.value.isLoading}")
            return
        }
        startGameLoop()
    }

    override fun onCleared() {
        Log.i(TAG, "SaveLoadViewModel cleared")

        // ★ 异步清理协程（NonCancellable 确保即使 viewModelScope 取消也执行完成）
        viewModelScope.launch(NonCancellable + Dispatchers.IO) {
            try {
                // 等待保存完成（最长 2 秒，挂起式等待不阻塞主线程）
                withTimeout(2000) {
                    while (stateStore.unifiedState.value.isSaving) {
                        delay(100)
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.w(TAG, "Save did not complete within 2s timeout, proceeding")
            }

            try {
                // 停止游戏循环（最长 3 秒）
                withTimeout(3000) {
                    gameEngineCore.stopGameLoopAndWait(2000)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.w(TAG, "Game loop did not stop within 3s timeout, force proceeding")
            }

            gameEngineCore.forceResetStuckStates()
        }

        // ★ 轻量同步清理：只清理内存状态，不等 I/O
        _pendingSlot.value = null
        _pendingAction.value = null
        _loadingProgress.value = PROGRESS_START

        // 重置生命周期状态，防止 Singleton GameStateStore 在下一次 Activity 创建时
        // 仍保持 PLAYING 导致 isGameLoaded == true 阻止新游戏/读档
        try {
            stateStore.resetBootPhase()
            stateStore.setIdle()
        } catch (_: Exception) {
            // 非关键清理，失败不影响主流程
        }

        stateStore.setPausedDirect(true)
        super.onCleared()
    }

    fun togglePause() {
        viewModelScope.launch {
            // 直接读取 stateStore.isPaused，绕过 unifiedState 的 50ms 采样延迟
            if (stateStore.isPaused.value) {
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

    val timeSpeed: StateFlow<Int> = gameClock.speedFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, 1)

    val isPaused: StateFlow<Boolean> = gameEngineCore.state
        .map { it.isPaused }
        .stateIn(viewModelScope, SharingStarted.Lazily, true)

    fun setTimeSpeed(speed: Int) {
        val clamped = speed.coerceIn(0, 2)
        _timeScale.value = clamped  // UI 即时反馈
        gameClock.setSpeed(clamped)
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
