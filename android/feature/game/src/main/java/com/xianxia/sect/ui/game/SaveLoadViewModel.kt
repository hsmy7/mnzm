package com.xianxia.sect.ui.game

import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.MapPreloadData
import com.xianxia.sect.core.engine.*
import com.xianxia.sect.core.engine.domain.diplomacy.AISectDiscipleManager
import com.xianxia.sect.core.state.*
import com.xianxia.sect.taptap.TapCloudSaveManager
import com.xianxia.sect.data.StorageConstants
import com.xianxia.sect.data.integrity.IntegrityResult
import com.xianxia.sect.data.integrity.SaveValidator
import com.xianxia.sect.data.migration.MigrationResult
import com.xianxia.sect.data.migration.SaveDataVersionMigrator
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.serialization.unified.SaveDataReconciler
import com.xianxia.sect.data.model.SaveSlot
import com.xianxia.sect.data.unified.SaveError
import com.xianxia.sect.data.unified.SaveResult
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.ui.components.AtlasResult
import com.xianxia.sect.ui.game.saveload.SaveLoadLoadDelegate
import com.xianxia.sect.ui.game.saveload.SaveLoadPauseDelegate
import com.xianxia.sect.ui.game.saveload.SaveLoadRestartDelegate
import com.xianxia.sect.ui.game.saveload.SaveLoadSaveDelegate
import com.xianxia.sect.core.engine.di.IoDispatcher
import com.xianxia.sect.ui.game.saveload.PersistenceFacade
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@HiltViewModel
class SaveLoadViewModel @Inject constructor(
    private val gameEngine: GameEngine,
    private val gameEngineCore: GameEngineCore,
    private val stateStore: GameStateStore,
    private val coroutineScopeProvider: CoroutineScopeProvider,
    private val gameClock: com.xianxia.sect.core.engine.system.GameTimeClock,
    private val resourcePreloader: ResourcePreloader,
    private val persistenceFacade: PersistenceFacade,
    private val ioDispatcher: IoDispatcher
) : BaseViewModel() {

    // 领域委托实例 — 按职责拆分 save/load/restart 等逻辑
    private val saveDelegate by lazy { SaveLoadSaveDelegate(gameEngine, persistenceFacade.storageFacade, stateStore) }
    private val loadDelegate by lazy {
        SaveLoadLoadDelegate(gameEngine, gameEngineCore, persistenceFacade.storageFacade, stateStore,
            persistenceFacade.buildingConfigService, persistenceFacade.spiritStoneWallet)
    }
    private val restartDelegate by lazy { SaveLoadRestartDelegate(gameEngine, gameEngineCore, persistenceFacade.storageFacade, stateStore, dispatcher = ioDispatcher.dispatcher) }
    private val pauseDelegate by lazy { SaveLoadPauseDelegate(gameEngineCore, gameClock) }

    companion object {
        private const val TAG = SaveLoadViewModelConstants.TAG
        private const val MB = SaveLoadViewModelConstants.MB
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
    private val loadLock = AtomicBoolean(false)
    private val cloudDownloadLock = AtomicBoolean(false)

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

    /** 运行时状态：IDLE / LOADING / PLAYING / RELOADING */
    val runState: StateFlow<RunState> get() = stateStore.runState

    /** 启动序列阶段：UNINITIALIZED / DATA_READY / SYSTEMS_READY / MAP_READY / BOOT_COMPLETE */
    val bootPhase: StateFlow<BootPhase> get() = stateStore.bootPhase

    private val _saveSlots = MutableStateFlow<List<SaveSlot>>(emptyList())
    val saveSlots: StateFlow<List<SaveSlot>> = _saveSlots.asStateFlow()

    private val _pendingSlot = MutableStateFlow<Int?>(null)
    val pendingSlot: StateFlow<Int?> = _pendingSlot.asStateFlow()

    private val _pendingAction = MutableStateFlow<String?>(null)
    val pendingAction: StateFlow<String?> = _pendingAction.asStateFlow()

    // ── 云存档状态 ──
    private val _cloudSaveInfo = MutableStateFlow(TapCloudSaveManager.CloudSaveInfo(false))
    val cloudSaveInfo: StateFlow<TapCloudSaveManager.CloudSaveInfo> = _cloudSaveInfo.asStateFlow()
    private val cloudSaveInfoVersion = java.util.concurrent.atomic.AtomicInteger(0)

    private val _cloudSaveOperationState = MutableStateFlow<CloudSaveOperationState>(CloudSaveOperationState.Idle)
    val cloudSaveOperationState: StateFlow<CloudSaveOperationState> = _cloudSaveOperationState.asStateFlow()

    fun isCloudSaveAvailable(): Boolean = persistenceFacade.sessionManager.isLoggedIn

    // P-8：unifiedState（20Hz 锁竞争 + 50ms 采样延迟）→ 独立窄流直连（零延迟）
    val saveLoadState: StateFlow<SaveLoadState> = combine(
        stateStore.isSaving,
        stateStore.isLoading,
        _pendingSlot,
        _pendingAction
    ) { isSaving, isLoading, slot, action ->
        SaveLoadState(
            isSaving = isSaving,
            isLoading = isLoading,
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
        viewModelScope.launch(ioDispatcher.dispatcher) {
            try {
                _saveSlots.value = persistenceFacade.storageFacade.getSaveSlotsSuspend()
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                Log.e(TAG, "Failed to load save slots in init, retrying after delay", e)
                delay(500)
                try {
                    _saveSlots.value = persistenceFacade.storageFacade.getSaveSlotsSuspend()
                } catch (e: CancellationException) { throw e }
                  catch (e2: Exception) {
                    Log.e(TAG, "Retry loading save slots also failed", e2)
                }
            }
        }

        // T12（2026-08-05）：看门狗病理复位事件 → 用户可见错误提示
        // （此前 isSaving/isLoading 卡死被看门狗复位时静默失败，无任何反馈）
        viewModelScope.launch {
            gameEngineCore.stuckResetEvents.collect { message ->
                showError(message)
            }
        }

    }

    private suspend fun setSaveLoadState(
        isSaving: Boolean? = null,
        isLoading: Boolean? = null,
        pendingSlot: Int? = _pendingSlot.value,
        pendingAction: String? = _pendingAction.value
    ) {
        // Q-1：isSaving/isLoading 同步收敛到引擎原子入口（单事务设置两标志）
        val finalIsSaving = isSaving ?: stateStore.isSaving.value
        val finalIsLoading = isLoading ?: stateStore.isLoading.value
        if (isSaving != null || isLoading != null) {
            try {
                gameEngine.setSaveLoadFlags(finalIsSaving, finalIsLoading)
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                Log.w(TAG, "Failed to sync save/load flags to stateStore: ${e.message}")
            }
        }

        _pendingSlot.value = pendingSlot
        _pendingAction.value = pendingAction
    }

    fun cancelSaveLoad() {
        gameEngine.launchOnEngine { setSaveLoadState(isSaving = false, isLoading = false, pendingSlot = null, pendingAction = null) }
    }

    /**
     * C4（2026-08-05）：finally 归属化复位——clearActiveLoadJob 归属判定与标志复位原子化。
     * 被新操作取代的协程（owned=false）不复位标志、不清理注册，避免旧协程 finally
     * 抹掉新操作的在途状态；S1 语义保留（NonCancellable 保证取消路径复位）。
     */
    private suspend fun resetOwnedLoadState(operation: String) {
        // Bugly #11021/#14002：身份从调用方传入改为协程体自取——
        // 本函数仅在 perform* 的 finally 内调用（必在 launch 协程体内），
        // coroutineContext[Job] 与 launch 返回的 job 是同一实例
        val job = kotlin.coroutines.coroutineContext[Job] ?: return
        val owned = gameEngineCore.clearActiveLoadJob(job)
        if (!owned) return
        try {
            withContext(NonCancellable) {
                gameEngine.setSaveLoadFlags(false, false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "$operation: Failed to reset save/load state in finally block", e)
            _pendingSlot.value = null
            _pendingAction.value = null
        }
    }

    fun resetSaveLoadState() {
        gameEngine.launchOnEngine {
            // Q-1：收敛到引擎原子入口（单事务设置两标志）
            try {
                gameEngine.setSaveLoadFlags(false, false)
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { Log.w(TAG, "resetSaveLoadState: setSaveLoadFlags failed: ${e.message}") }
        }
        _pendingSlot.value = null
        _pendingAction.value = null
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
        if (stateStore.isLoading.value && _loadingProgress.value < PROGRESS_COMPLETE) {
            Log.w(TAG, "Already loading with progress ${_loadingProgress.value}, ignoring startNewGame request")
            return
        }

        if (isGameLoaded) {
            Log.w(TAG, "Game already loaded, ignoring startNewGame request")
            return
        }

        Log.i(TAG, "=== startNewGame BEGIN === sectName=$sectName, slot=$slot")
        val startTime = System.currentTimeMillis()

        // Bugly #11021/#14002：不再用 lateinit 捕获 job 传入协程体——空闲 IO worker
        // 可能先于调用线程赋值执行协程体，实参求值读 lateinit 即抛
        // UninitializedPropertyAccessException（Dispatchers.IO 是 LimitedDispatcher，
        // 与崩溃栈吻合）。身份清理改为 perform* 内部 coroutineContext[Job] 自取
        val job = viewModelScope.launch(ioDispatcher.dispatcher) {
            // C-8：新游戏主流程提取（行为逐行一致）
            performStartNewGame(sectName, slot, startTime)
        }
        gameEngineCore.registerActiveLoadJob(job)
    }

    /**
     * C-8：新游戏主流程（startNewGame 协程体提取）。
     * 创建新游戏 → RNG 播种 → 首存（失败重试一次）→ BootSequenceController 启动。
     */
    private suspend fun performStartNewGame(sectName: String, slot: Int, startTime: Long) {
            var needSlotRefresh = false
            var gameStarted = false
            try {
                setSaveLoadState(isLoading = true, pendingSlot = slot, pendingAction = "newgame")

                _loadingProgress.value = PROGRESS_START

                Log.d(TAG, "startNewGame: Calling gameEngine.createNewGame(sectName=$sectName, slot=$slot)")
                gameEngine.createNewGame(sectName, slot)
                Log.d(TAG, "startNewGame: Game engine created new game successfully, elapsed=${System.currentTimeMillis() - startTime}ms")

                // RNG 播种已收敛到 GameEngine.createNewGame 内部（引擎线程）：
                // initSystemSeed（8 分区）与 AISectDiscipleManager.initForSlot 均在引擎侧完成，
                // 避免 UI 协程与引擎线程 RNG 消费/播种并发竞争（P0-1b）
                Log.d(TAG, "startNewGame: RNG seeded with mapSeed=${gameEngine.gameData.value.mapSeed}")

                persistenceFacade.storageFacade.setCurrentSlot(slot)
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
                    return
                }

                // BootSequenceController 统一处理：建筑修正、BootPhase 推进、资源预加载、
                // 弟子快照预热、确保重数据加载、游戏循环启动、地图生成、最终状态切换
                val bootResult = persistenceFacade.bootSequenceController.boot(
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

                    // ★ 白名单福利：1000 万灵石永久邮件（每档一次，非白名单自动跳过）
                    gameEngine.sendWhitelistBonus(slot)

                    // ★ 专属福利：定向用户 1000 万灵石 + 10 单灵根弟子邮件
                    //（2026-09-04 截止，每档一次，非目标用户自动跳过）
                    gameEngine.sendExclusiveBonus(slot)

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
                // S1 修复：NonCancellable 保证取消路径复位（详见 performLoadToSlot finally 注释）
                // C4（2026-08-05）：归属化复位（被取代的协程不复位标志/不清理）
                resetOwnedLoadState("startNewGame")
                if (!gameStarted) {
                    _loadingProgress.value = PROGRESS_START
                }
                if (needSlotRefresh) {
                    try {
                        _saveSlots.value = persistenceFacade.storageFacade.getSaveSlotsSuspend()
                    } catch (e: CancellationException) { throw e }
                      catch (e: Exception) {
                        Log.w(TAG, "startNewGame: Failed to refresh save slots after completion: ${e.message}")
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
                persistenceFacade.storageFacade.save(slot, saveData)
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
                        _saveSlots.value = persistenceFacade.storageFacade.getSaveSlotsSuspend()
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

    fun loadGame(saveSlot: SaveSlot) = loadGameInternal(saveSlot, fromCloudLoad = false)

    /**
     * C1 修复（2026-08-05）：读档内部入口——[fromCloudLoad]=true 时仅绕过
     * `cloudDownloadLock` 守卫（云读档路径已持有该锁，云档已落盘后加载内存；
     * 其余守卫照常，绕过入口仅内部可达）。
     */
    @Suppress("ReturnCount") // 读档多守卫（云锁/重启/加载/保存/loadLock/内存），多 return 为守卫风格
    private fun loadGameInternal(saveSlot: SaveSlot, fromCloudLoad: Boolean) {
        // 2026-08-04 对抗性审查修复（B3）：云存档操作进行中禁止本地读档——
        // 原实现不查 cloudDownloadLock，云读档下载期间点本地档会与云下载
        // 并发写 DB/内存，导致"内存=本地档、DB=云档"静默分歧
        if (!fromCloudLoad && cloudDownloadLock.get()) {
            Log.w(TAG, "Cloud save operation in progress, ignoring loadGame request")
            showError("云存档操作进行中，请稍后读档")
            return
        }
        // C4 配套（2026-08-05）：restart 的 stopGameLoopAndWait 窗口内读档被立即拒绝——
        // 否则读档注册会取消 restart 协程（游戏循环停在已停止状态）
        if (_isRestarting.value) {
            Log.w(TAG, "Restarting, ignoring loadGame request")
            showError("游戏重置中，请稍后读档")
            return
        }
        if (stateStore.isLoading.value) {
            Log.w(TAG, "Already loading, ignoring loadGame request")
            return
        }
        if (stateStore.isSaving.value) {
            Log.w(TAG, "Currently saving, ignoring loadGame request")
            showError("正在保存中，请稍后读档")
            return
        }
        if (!loadLock.compareAndSet(false, true)) {
            Log.w(TAG, "loadLock busy, ignoring loadGame request")
            return
        }

        if (!canPerformSaveOperation()) {
            Log.e(TAG, "=== loadGame FAILED === insufficient memory")
            loadLock.set(false)
            showError("内存不足，无法读档。请关闭其他应用后重试。")
            return
        }

        Log.i(TAG, "=== loadGame BEGIN === slot=${saveSlot.slot}, sectName=${saveSlot.sectName}, " +
            "year=${saveSlot.gameYear}, month=${saveSlot.gameMonth}")
        val startTime = System.currentTimeMillis()

        // Bugly #11021/#14002：job 身份由 perform* 内部 coroutineContext[Job] 自取，
        // 不再 lateinit 捕获（避免 IO worker 抢跑读未赋值 lateinit）
        val job = viewModelScope.launch(ioDispatcher.dispatcher) {
            // C-8：读档主流程提取（行为逐行一致）
            performLoadToSlot(saveSlot, startTime)
        }
        gameEngineCore.registerActiveLoadJob(job)
    }

    /**
     * C-8：读档主流程（loadGame 协程体提取）。
     * 读取存档 → 引擎加载 → RNG 恢复 → BootSequenceController 启动。
     */
    private suspend fun performLoadToSlot(saveSlot: SaveSlot, startTime: Long) {
            try {
                setSaveLoadState(isLoading = true, pendingSlot = saveSlot.slot, pendingAction = "load")

                performGarbageCollection()

                Log.d(TAG, "Starting to load save data for slot ${saveSlot.slot}")
                val loadStartTime = System.currentTimeMillis()

                val saveData = withTimeoutOrNull(60_000L) {
                    try {
                        val data = persistenceFacade.storageFacade.load(saveSlot.slot).getOrNull()
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
                    return
                }

                val effectiveSlot = saveSlot.slot
                persistenceFacade.storageFacade.setCurrentSlot(effectiveSlot)
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

                // RNG 分区恢复已收敛到 GameStateStoreImpl.loadFromSnapshot 锁内
                // （状态 + RNG 原子切换，P0-1），此处不再重复 restoreStates
                val loadedGd = gameEngine.gameData.value
                // 初始化 AI 宗门 RNG（基于地图种子确保确定性）
                AISectDiscipleManager.initForSlot(loadedGd.mapSeed.toLong())

                // 建筑占地重叠/越界迁移（旧存档兼容，不在 BootSequenceController 中）
                loadDelegate.migrateOverflowBuildings()

                // BootSequenceController 统一处理：建筑修正、BootPhase 推进、资源预加载、
                // 弟子快照预热、确保重数据加载、游戏循环启动、地图生成、最终状态切换
                val bootResult = persistenceFacade.bootSequenceController.boot(
                    slot = effectiveSlot,
                    onPreloadResources = { preloadGameResources() },
                    onProgress = { progress ->
                        _loadingProgress.value = PROGRESS_START + progress * (PROGRESS_COMPLETE - PROGRESS_START)
                    },
                    onMapReady = { mapData -> _mapPreloadData.value = mapData },
                    onSuccess = { showSuccess("读档成功") }
                )

                if (bootResult.isSuccess) {
                    // ★ 白名单福利：1000 万灵石永久邮件（每档一次，非白名单自动跳过）
                    gameEngine.sendWhitelistBonus(effectiveSlot)

                    // ★ 专属福利：定向用户 1000 万灵石 + 10 单灵根弟子邮件
                    //（2026-09-04 截止，每档一次，非目标用户自动跳过）
                    gameEngine.sendExclusiveBonus(effectiveSlot)

                    val gd = gameEngine.gameData.value
                    Log.i(TAG, "=== loadGame SUCCESS === " +
                        "sectName=${gd.sectName}, year=${gd.gameYear}, month=${gd.gameMonth}, phase=${gd.gamePhase}, " +
                        "spiritStones=${gd.spiritStones}, disciples=${gameEngine.disciples.value.size}, " +
                        "equipment=${gameEngine.equipmentInstances.value.size}, manuals=${gameEngine.manualInstances.value.size}, " +
                        "elapsed=${System.currentTimeMillis() - startTime}ms")
                } else {
                    val errorMsg = bootResult.exceptionOrNull()?.message ?: "读档失败"
                    showError(errorMsg)
                    // 2026-08-04 对抗性审查修复（B4）：boot 失败后清空地图预加载数据——
                    // 游戏内读档失败路径若残留旧 mapPreloadData，Crossfade 仍显示
                    // MainGameScreen（引擎已停、runState=IDLE）→ "冻结的游戏画面"
                    _mapPreloadData.value = null
                }
            } catch (e: CancellationException) {
                Log.w(TAG, "loadGame cancelled")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "=== loadGame FAILED === error=${e.message}", e)
                showError("加载游戏失败: ${e.message}")
            } catch (e: OutOfMemoryError) {
                // C3-c（2026-08-05）：OOM 是 Error 非 Exception——crafted 大 id 弟子
                // 扩容平铺表直接崩溃。统一走用户可感知的失败提示，finally 复位不受影响
                Log.e(TAG, "=== loadGame FAILED === OutOfMemoryError: ${e.message}", e)
                showError("内存不足，读档失败。请关闭其他应用后重试。")
            } finally {
                // S1 修复：finally 复位必须用 NonCancellable——setSaveLoadFlags 是挂起函数，
                // 看门狗取消协程后挂起调用立即抛 CancellationException 截断 finally，
                // 导致 loadLock 泄漏（读档永久拒绝）与 isSaving/isLoading 永不复位。
                // C4（2026-08-05）：归属化复位（被取代的协程不复位标志）；loadLock 为操作私有无条件释放
                resetOwnedLoadState("loadGame")
                loadLock.set(false)
            }
    }

    fun loadGameFromSlot(slot: Int, fromCloudLoad: Boolean = false) {
        // slot 0 = 从云端下载（带 saveLoadState 管理 + 结果反馈）
        if (slot == 0) {
            viewModelScope.launch(ioDispatcher.dispatcher) {
                resetCloudSaveOperationState()
                try {
                    downloadFromCloudSave()
                    // 等待云端操作完成（Downloading → Success/Error）
                    _cloudSaveOperationState.first {
                        it is CloudSaveOperationState.Success || it is CloudSaveOperationState.Error
                    }
                    // C2 修复（2026-08-05）：isLoading 占位移到下载完成之后——
                    // 原实现在下载前置位，被 downloadFromCloudSave 自身的 isLoading 守卫
                    // （L1339）恒真拒绝，SettingsTab 云槽位读取必失败。
                    // 下载期互斥由 cloudDownloadLock 承担（loadGame/saveGame 入口均查）
                    setSaveLoadState(isLoading = true, pendingSlot = 0, pendingAction = "load")
                    when (val state = _cloudSaveOperationState.value) {
                        is CloudSaveOperationState.Success -> showSuccess(state.message)
                        is CloudSaveOperationState.Error -> showError(state.message)
                        else -> {} // Idle 不应出现
                    }
                } catch (e: CancellationException) { throw e }
                  catch (e: Exception) {
                    showError("下载失败: ${e.message}")
                } finally {
                    setSaveLoadState(isLoading = false, pendingSlot = null, pendingAction = null)
                }
            }
            return
        }
        // 从已缓存的存档元数据中查找 SaveSlot，兜底构造最小 SaveSlot
        val saveSlot = _saveSlots.value.find { it.slot == slot }
            ?: SaveSlot(slot, "", 0L, 1, 1, "", 0, 0L)
        loadGameInternal(saveSlot, fromCloudLoad)
    }

    /**
     * 从云存档下载并加载游戏（主菜单云存档卡片入口）。
     *
     * 流程：
     * 1. 设置加载进度反馈（_loadingProgress=0.1f, "正在同步云存档..."）
     * 2. 下载云存档 (persistenceFacade.tapCloudSaveManager.downloadSave())
     * 3. 写入本地存储 (persistenceFacade.storageFacade.save)
     * 4. 调用 loadGameFromSlot(slot) 走正常 BootSequenceController 启动流程
     * 5. 失败时通过 showError() 展示错误
     *
     * 与 downloadFromCloudSave()（游戏内 SaveSlotDialog 使用）不同，
     * 此方法直接驱动 GameActivity 的 LoadingScreen 进度反馈。
     */
    fun loadFromCloudSave() {
        if (!cloudDownloadLock.compareAndSet(false, true)) {
            Log.w(TAG, "Cloud load already in progress, ignoring")
            return
        }
        viewModelScope.launch(ioDispatcher.dispatcher) {
            // C-8：云读档主流程提取（行为逐行一致）
            performCloudLoad()
        }
    }

    /** C-8：云读档主流程（loadFromCloudSave 协程体提取）。 */
    @Suppress("ReturnCount") // 云档多失败守卫（空数据/损坏/写入失败），多 return 为守卫风格
    private suspend fun performCloudLoad() {
            try {
                _loadingProgress.value = 0.1f
                _preloadPhase.value = SaveLoadViewModelConstants.PHASE_CLOUD_SYNC

                // 2026-08-04 对抗性审查修复（B3）：与本地读档/保存的并发互斥
                // 由 loadGame/saveGame 的 cloudDownloadLock 检查保证（本路径全程
                // 持有 cloudDownloadLock）——不设 isLoading（挂起设置会引入
                // 纯 JVM 测试环境无法恢复的协程挂起点，且主菜单场景循环未启动
                // isLoading 无冻结语义）

                if (!isCloudSaveAvailable()) {
                    showError("请先登录 TapTap")
                    return
                }

                val result = persistenceFacade.tapCloudSaveManager.downloadSave()

                when (result) {
                    is TapCloudSaveManager.CloudSaveResult.Success -> handleCloudLoadSuccess(result)
                    is TapCloudSaveManager.CloudSaveResult.NetworkError ->
                        showError("网络错误: ${result.message}")
                    is TapCloudSaveManager.CloudSaveResult.AuthRequired ->
                        showError("请先登录 TapTap 账号")
                    is TapCloudSaveManager.CloudSaveResult.SerializationError ->
                        showError("存档数据异常: ${result.message}")
                    is TapCloudSaveManager.CloudSaveResult.FileTooLarge ->
                        showError("云存档文件过大，无法下载")
                    is TapCloudSaveManager.CloudSaveResult.NoSaveExists ->
                        showError("云存档不存在")
                    is TapCloudSaveManager.CloudSaveResult.VersionMismatch ->
                        showError("云存档来自版本 ${result.cloudVersion}，当前版本 ${result.currentVersion} 不支持加载")
                    is TapCloudSaveManager.CloudSaveResult.UnknownError ->
                        showError("未知错误: ${result.message}")
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
              catch (e: OutOfMemoryError) {
                // 2026-08-04 对抗性审查修复（A9）：恶意/异常超大云档反序列化 OOM——
                // OutOfMemoryError 不是 Exception，原实现直接崩溃；降级为错误提示
                Log.e(TAG, "云存档数据过大导致内存不足", e)
                showError("云存档数据过大，内存不足，无法加载")
            } catch (e: Exception) {
                Log.e(TAG, "loadFromCloudSave failed", e)
                showError("加载云存档失败: ${e.message}")
            } finally {
                cloudDownloadLock.set(false)
            }
    }

    fun saveGame(slotId: String? = null) {
        val slot = slotId?.toIntOrNull() ?: gameEngine.gameData.value?.currentSlot ?: 1

        // slot 0 = 上传至云端（带 saveLoadState 管理 + 结果反馈）
        if (slot == 0) {
            saveToCloudViaSlot()
            return
        }

        if (!isGameLoaded) {
            Log.w(TAG, "Game not loaded, ignoring saveGame request")
            return
        }

        // 2026-08-04 对抗性审查修复（B3）：云存档操作进行中禁止本地保存——
        // 原实现不查 cloudDownloadLock，云下载落盘与本地保存并发写同一槽位
        if (cloudDownloadLock.get()) {
            Log.w(TAG, "Cloud save operation in progress, ignoring saveGame request")
            showError("云存档操作进行中，请稍后保存")
            return
        }

        // C4 配套（2026-08-05）：restart 的 stopGameLoopAndWait 窗口内保存被立即拒绝——
        // 否则保存注册会取消 restart 协程（isSaving 未置时守卫通过，restart 被杀）
        if (_isRestarting.value) {
            Log.w(TAG, "Restarting, ignoring saveGame request")
            showError("游戏重置中，请稍后保存")
            return
        }

        // S12 修复（对抗性审查）：isSaving 守卫——原无此检查，快速连点两次保存时
        // 后一次会覆写前一次的 isSaving 标志（保存 B 期间 tick 恢复 → torn 存档）
        if (stateStore.isSaving.value) {
            Log.w(TAG, "Currently saving, ignoring saveGame request")
            showError("正在保存中，请稍后")
            return
        }

        if (stateStore.isLoading.value || loadLock.get()) {
            Log.w(TAG, "Load in progress, ignoring saveGame request")
            showError("正在读档中，请稍后保存")
            return
        }

        if (!canPerformSaveOperation()) {
            Log.e(TAG, "=== saveGame FAILED === insufficient memory")
            showError("内存不足，无法保存。请关闭其他应用后重试。")
            return
        }

        // C5 修复（2026-08-05）：isSaving 同步占位——关闭双 tap 窗口
        // （原实现 L820 检查通过后到协程内异步置位之间存在窗口，第二发可穿过守卫
        // 注册取消第一发）；协程内 setSaveLoadState(isSaving=true) 为幂等重设
        stateStore.setSavingDirect(true)
        _pendingSlot.value = slot
        _pendingAction.value = "save"

        Log.i(TAG, "=== saveGame BEGIN === slot=$slot, slotId=$slotId")
        val startTime = System.currentTimeMillis()

        // Bugly #11021/#14002：job 身份由 perform* 内部 coroutineContext[Job] 自取，
        // 不再 lateinit 捕获（避免 IO worker 抢跑读未赋值 lateinit）
        val job = viewModelScope.launch(ioDispatcher.dispatcher) {
            // C-8：本地保存流程提取（行为逐行一致）
            val previousSlot = persistenceFacade.storageFacade.getCurrentSlot()
            performLocalSaveToSlot(slot, previousSlot, startTime)
        }
        gameEngineCore.registerActiveLoadJob(job) // T14（2026-08-05）：保存协程注册，看门狗可取消复位
    }

    /**
     * C-8：本地保存主流程（saveGame 协程体提取）。
     * 快照 → 校验 → 保存 → 结果反馈 → 失败回滚 currentSlot。
     */
    private suspend fun performLocalSaveToSlot(slot: Int, previousSlot: Int, startTime: Long) {
            setSaveLoadState(isSaving = true, pendingSlot = slot, pendingAction = "save")

            try {
                if (!waitForSaveLock(timeoutMs = 5000)) {
                    Log.e(TAG, "=== saveGame FAILED === saveLock busy after timeout")
                    showError("保存操作繁忙，请稍后重试")
                    return
                }

                val previousSlot = persistenceFacade.storageFacade.getCurrentSlot()
                persistenceFacade.storageFacade.setCurrentSlot(slot)

                try {
                    performGarbageCollection()

                    val snapshot = gameEngine.getStateSnapshot()
                    Log.d(TAG, "saveGame snapshot: productionSlots=${snapshot.productionSlots.size}, " +
                        "gameData.productionSlots=${snapshot.gameData.productionSlots.size}, " +
                        "disciples=${snapshot.disciples.size}, equipment=${snapshot.equipmentInstances.size}")
                    if (snapshot.gameData.sectName.isBlank()) {
                        Log.e(TAG, "=== saveGame FAILED === gameData not initialized (sectName is blank)")
                        persistenceFacade.storageFacade.setCurrentSlot(previousSlot)
                        showError("游戏数据未初始化")
                        return
                    }
                    val updatedGameData = snapshot.gameData.copy(currentSlot = slot)
                    val saveData = trimSaveData(snapshot).copy(gameData = updatedGameData)

                    val saveResult = withTimeoutOrNull(30_000L) {
                        persistenceFacade.storageFacade.save(slot, saveData)
                    }

                    if (saveResult != null && saveResult.isSuccess) {
                        try {
                            _saveSlots.value = persistenceFacade.storageFacade.getSaveSlotsSuspend()
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
                        persistenceFacade.storageFacade.setCurrentSlot(previousSlot)
                        val errorMsg = if (saveResult == null) "保存超时，请重试" else "保存失败，请重试"
                        showError(errorMsg)
                        Log.e(TAG, "=== saveGame FAILED === ${if (saveResult == null) "timeout" else "save returned failure"}")
                        try { _saveSlots.value = persistenceFacade.storageFacade.getSaveSlotsSuspend() } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.e(TAG, "Failed to refresh slots after save failure", e) }
                    }
                } catch (e: OutOfMemoryError) {
                    Log.e(TAG, "=== saveGame FAILED === OutOfMemoryError", e)
                    persistenceFacade.storageFacade.setCurrentSlot(previousSlot)
                    showError("内存不足，保存失败。请关闭其他应用后重试。")
                    try { _saveSlots.value = persistenceFacade.storageFacade.getSaveSlotsSuspend() } catch (e: CancellationException) { throw e } catch (e2: Exception) { Log.e(TAG, "Failed to refresh slots after OOM", e2) }
                } catch (e: CancellationException) {
                    Log.w(TAG, "saveGame cancelled")
                    persistenceFacade.storageFacade.setCurrentSlot(previousSlot)
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "=== saveGame FAILED === error=${e.message}", e)
                    persistenceFacade.storageFacade.setCurrentSlot(previousSlot)
                    showError("保存失败: ${e.message}")
                    try { _saveSlots.value = persistenceFacade.storageFacade.getSaveSlotsSuspend() } catch (e: CancellationException) { throw e } catch (e2: Exception) { Log.e(TAG, "Failed to refresh slots after save failure", e2) }
                } finally {
                    saveLock.set(false)
                }
            } finally {
                // S1 修复：NonCancellable 保证取消路径复位（详见 performLoadToSlot finally 注释）
                // C4（2026-08-05）：归属化复位（被取代的协程不复位标志）
                resetOwnedLoadState("saveGame")
            }
    }

    /**
     * C-8：slot=0 云保存流程（带 saveLoadState 管理 + 结果反馈）。
     * 从 saveGame 拆分（2026-08-02），行为逐行一致。
     */
    private fun saveToCloudViaSlot() {
        viewModelScope.launch(ioDispatcher.dispatcher) {
            resetCloudSaveOperationState()
            setSaveLoadState(isSaving = true, pendingSlot = 0, pendingAction = "save")
            try {
                uploadToCloudSave()
                // 等待云端操作完成（Uploading → Success/Error）
                _cloudSaveOperationState.first {
                    it is CloudSaveOperationState.Success || it is CloudSaveOperationState.Error
                }
                when (val state = _cloudSaveOperationState.value) {
                    is CloudSaveOperationState.Success -> {
                        showSuccess(state.message)
                        try {
                            _saveSlots.value = persistenceFacade.storageFacade.getSaveSlotsSuspend()
                        } catch (e: CancellationException) { throw e }
                          catch (e: Exception) {
                            Log.w(TAG, "Failed to refresh slots after cloud save", e)
                        }
                    }
                    is CloudSaveOperationState.Error -> showError(state.message)
                    else -> {} // Idle 不应出现
                }
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                showError("上传失败: ${e.message}")
            } finally {
                setSaveLoadState(isSaving = false, pendingSlot = null, pendingAction = null)
            }
        }
    }

    private suspend fun waitForSaveLock(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (saveLock.compareAndSet(false, true)) {
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
        // T16（2026-08-05）：boot 失败（runState=IDLE）后内存残留新档数据，
        // 若此入口可达会用残留数据覆写磁盘——防御性守卫（与 loadGame 同模式）
        if (!isGameLoaded) {
            Log.w(TAG, "Game not loaded, ignoring restartGame request")
            return
        }
        if (!saveLock.compareAndSet(false, true)) {
            Log.w(TAG, "Already saving, ignoring restartGame request")
            return
        }

        // T2（2026-08-05）：restart 与 load 完整互斥——load 已抢 loadLock 时拒绝，
        // 防止 load 的 clear+insert 与 restart 的引擎重置/存档并发；取锁序
        // saveLock→loadLock，loadGame 只取 loadLock、saveGame 只读两锁，无死锁
        if (!loadLock.compareAndSet(false, true)) {
            Log.w(TAG, "Load in progress, ignoring restartGame request")
            saveLock.set(false)
            showError("正在读档中，请稍后重置")
            return
        }

        if (stateStore.isLoading.value) {
            Log.w(TAG, "Already loading, ignoring restartGame request")
            _isRestarting.value = false
            loadLock.set(false)
            saveLock.set(false)
            return
        }

        if (!canPerformSaveOperation()) {
            Log.e(TAG, "=== restartGame FAILED === insufficient memory")
            showError("内存不足，无法重置。请关闭其他应用后重试。")
            _isRestarting.value = false
            loadLock.set(false)
            saveLock.set(false)
            return
        }

        // T2（2026-08-05）：同步置位（与 C5 setSavingDirect 同模式）——
        // 关闭 "saveLock 已抢但协程未启动" 窗口内 load/save 的穿入
        _isRestarting.value = true

        // Bugly #11021/#14002：job 身份由 perform* 内部 coroutineContext[Job] 自取，
        // 不再 lateinit 捕获（避免 IO worker 抢跑读未赋值 lateinit）
        val job = viewModelScope.launch(ioDispatcher.dispatcher) {
            // C-8：重启主流程提取（行为逐行一致）
            performRestartGame(wasRunning = _isTimeRunning.value)
        }
        gameEngineCore.registerActiveLoadJob(job)
    }

    /**
     * C-8：重启主流程（restartGame 协程体提取）。
     * 停止循环 → 重置引擎 → RNG 重新播种 → 重启存档 → BootSequenceController 启动。
     */
    private suspend fun performRestartGame(wasRunning: Boolean) {
            var previousSlot = 1
            try {
                _isRestarting.value = true

                if (wasRunning) {
                    val stopped = gameEngineCore.stopGameLoopAndWait(5000)
                    if (!stopped) {
                        Log.e(TAG, "Failed to stop game loop within timeout")
                        showError("无法停止游戏循环，请重试")
                        return
                    }
                    _isTimeRunning.value = false
                    Log.d(TAG, "Game loop stopped for restart operation")
                }

                performGarbageCollection()

                val currentData = gameEngine.gameData.value
                val sectName = currentData.sectName.ifBlank { "QingYunSect" }
                val currentSlot = currentData.currentSlot.let { if (it >= 0) it else 1 }
                previousSlot = persistenceFacade.storageFacade.getCurrentSlot()

                Log.i(TAG, "=== restartGame BEGIN === currentSlot=$currentSlot, previousSlot=$previousSlot, sectName=$sectName")

                persistenceFacade.storageFacade.setCurrentSlot(currentSlot)

                gameEngine.restartGameSuspend(sectName, currentSlot)

                // 重置 RNG 系统种子（重启即新世界，初始化确定性随机序列）
                persistenceFacade.gameRngManager.initSystemSeed(gameEngine.gameData.value.mapSeed.toLong())
                AISectDiscipleManager.initForSlot(gameEngine.gameData.value.mapSeed.toLong())
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
                val bootResult = persistenceFacade.bootSequenceController.boot(
                    slot = currentSlot,
                    onPreloadResources = { preloadGameResources() },
                    onProgress = { progress ->
                        _loadingProgress.value = PROGRESS_START + progress * (PROGRESS_COMPLETE - PROGRESS_START)
                    },
                    onMapReady = { mapData -> _mapPreloadData.value = mapData }
                )

                if (bootResult.isSuccess) {
                    _isTimeRunning.value = true
                    // 重开即新档：与主菜单新游戏路径一致，注入白名单福利
                    gameEngine.sendWhitelistBonus(currentSlot)

                    // 专属福利：定向用户邮件（2026-09-04 截止，每档一次，非目标用户自动跳过）
                    gameEngine.sendExclusiveBonus(currentSlot)
                } else {
                    Log.e(TAG, "restartGame: boot sequence failed after restart, error=${bootResult.exceptionOrNull()?.message}")
                }
            } catch (e: CancellationException) {
                Log.w(TAG, "restartGame cancelled")
                throw e
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "=== restartGame FAILED === OutOfMemoryError", e)
                persistenceFacade.storageFacade.setCurrentSlot(previousSlot)
                showError("内存不足，重置失败。请关闭其他应用后重试。")
                setSaveLoadState(isSaving = false, pendingSlot = null, pendingAction = null)
            } catch (e: Exception) {
                Log.e(TAG, "=== restartGame FAILED === error=${e.message}", e)
                persistenceFacade.storageFacade.setCurrentSlot(previousSlot)
                showError(e.message ?: "重置游戏失败")
                setSaveLoadState(isSaving = false, pendingSlot = null, pendingAction = null)
            } finally {
                _isRestarting.value = false
                // T2（2026-08-05）：loadLock 与 saveLock 成对复位（入口同步抢锁，
                // 协程结束/取消统一释放，防泄漏）
                loadLock.set(false)
                // C4 修复（2026-08-05）：restart 补上归属化清理 + 标志复位——
                // 原实现漏调 clearActiveLoadJob，且取消路径（isSaving=true 后
                // CancellationException）不复位标志导致 isSaving 泄漏
                resetOwnedLoadState("restartGame")
                saveLock.set(false)
                // 兜底：若 boot() 未执行（提前 return），则仅记录日志
                // BootSequenceController.boot() 在内部已处理游戏循环恢复
                if (wasRunning && !_isTimeRunning.value) {
                    Log.w(TAG, "restartGame: game loop not running after restart finally, boot() may have failed")
                }
            }
    }

    private suspend fun performRestartSave(slot: Int, previousSlot: Int): Boolean {
        return withContext(ioDispatcher.dispatcher) {
            try {
                val snapshot = gameEngine.getStateSnapshot()
                if (snapshot.gameData.sectName.isBlank()) {
                    Log.e(TAG, "performRestartSave: gameData not initialized")
                    persistenceFacade.storageFacade.setCurrentSlot(previousSlot)
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
                    storageBags = snapshot.storageBags,
                    // 2026-08-01 对抗性审查修复：restart 保存缺该标志会使删表守卫失效，
                    // 旧世界堆叠残留泄漏进新世界
                    stacksSerialized = true
                )

                val success = withTimeoutOrNull(30_000L) {
                    persistenceFacade.storageFacade.save(slot, saveData).isSuccess
                }

                when (success) {
                    true -> {
                        try {
                            _saveSlots.value = persistenceFacade.storageFacade.getSaveSlotsSuspend()
                        } catch (e: CancellationException) { throw e }
                          catch (e: Exception) {
                            Log.e(TAG, "Failed to refresh slots after restart save: ${e.message}", e)
                        }
                        Log.i(TAG, "performRestartSave success for slot $slot")
                        true
                    }
                    null -> {
                        Log.e(TAG, "performRestartSave timeout for slot $slot")
                        persistenceFacade.storageFacade.setCurrentSlot(previousSlot)
                        if (persistenceFacade.storageFacade.isSaveCorruptedSuspend(slot)) {
                            persistenceFacade.storageFacade.restoreFromBackupIfCorrupted(slot)
                            Log.w(TAG, "Save may be corrupted, attempted to restore from backup")
                        }
                        false
                    }
                    false -> {
                        Log.e(TAG, "performRestartSave failed for slot $slot")
                        persistenceFacade.storageFacade.setCurrentSlot(previousSlot)
                        false
                    }
                }
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "performRestartSave OutOfMemoryError for slot $slot", e)
                persistenceFacade.storageFacade.setCurrentSlot(previousSlot)
                false
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                Log.e(TAG, "performRestartSave error for slot $slot", e)
                persistenceFacade.storageFacade.setCurrentSlot(previousSlot)
                false
            }
        }
    }

    fun refreshSaveSlots() {
        viewModelScope.launch {
            try {
                _saveSlots.value = persistenceFacade.storageFacade.getSaveSlotsSuspend()
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
        gameEngine.launchOnEngine {
            gameEngineCore.resume()
        }
        if (!isGameLoaded || stateStore.isLoading.value) {
            Log.d(TAG, "resumeGameLoop: Skipping startGameLoop - isGameLoaded=$isGameLoaded, isLoading=${stateStore.isLoading.value}")
            return
        }
        startGameLoop()
    }

    // ── 云存档操作方法 ──

    fun checkCloudSave() {
        val fetchVersion = cloudSaveInfoVersion.incrementAndGet()
        viewModelScope.launch(ioDispatcher.dispatcher) {
            try {
                // 老玩家首次使用：清理旧版本残留的孤立存档
                persistenceFacade.tapCloudSaveManager.oneTimeCleanup()
                // 查询云端存档信息
                val info = persistenceFacade.tapCloudSaveManager.checkCloudSave()
                if (fetchVersion == cloudSaveInfoVersion.get()) {
                    _cloudSaveInfo.value = info
                }
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                Log.w(TAG, "checkCloudSave failed", e)
            }
        }
    }

    fun uploadToCloudSave() {
        if (_cloudSaveOperationState.value is CloudSaveOperationState.Uploading ||
            _cloudSaveOperationState.value is CloudSaveOperationState.Downloading) return
        viewModelScope.launch(ioDispatcher.dispatcher) {
            // C-8：云上传主流程提取（行为逐行一致）
            performCloudUpload()
        }
    }

    /** C-8：云上传主流程（uploadToCloudSave 协程体提取）。 */
    private suspend fun performCloudUpload() {
            if (!isCloudSaveAvailable()) {
                _cloudSaveOperationState.value = CloudSaveOperationState.Error("请先登录TapTap账号")
                return
            }

            _cloudSaveOperationState.value = CloudSaveOperationState.Uploading

            try {
                val snapshot = gameEngine.getStateSnapshot()
                val saveData = createSaveDataFromSnapshot(snapshot)

                val result = persistenceFacade.tapCloudSaveManager.uploadSave(saveData)

                _cloudSaveOperationState.value = when (result) {
                    is TapCloudSaveManager.CloudSaveResult.Success -> {
                        // 直接用刚上传的 saveData 构造 CloudSaveInfo，避免 TapTap API 最终一致性延迟
                        val gd = saveData.gameData
                        val cloudInfo = TapCloudSaveManager.CloudSaveInfo(
                            hasSaveData = true,
                            lastModifiedTime = System.currentTimeMillis(),
                            description = "第${gd.gameYear}年${gd.gameMonth}月 ${gd.sectName}",
                            gameYear = gd.gameYear,
                            gameMonth = gd.gameMonth,
                            sectName = gd.sectName,
                            discipleCount = saveData.disciples.size,
                            spiritStones = gd.spiritStones,
                            appVersion = GameConfig.Game.VERSION
                        )
                        _cloudSaveInfo.value = cloudInfo
                        // 持久化到本地缓存，避免关闭对话框后 checkCloudSave() 因 API 延迟返回空
                        persistenceFacade.tapCloudSaveManager.saveCloudSaveInfoToLocal(cloudInfo)
                        cloudSaveInfoVersion.incrementAndGet()
                        CloudSaveOperationState.Success("云存档上传成功")
                    }
                    is TapCloudSaveManager.CloudSaveResult.NetworkError ->
                        CloudSaveOperationState.Error("网络错误: ${result.message}")
                    is TapCloudSaveManager.CloudSaveResult.AuthRequired ->
                        CloudSaveOperationState.Error("请先登录TapTap账号")
                    is TapCloudSaveManager.CloudSaveResult.SerializationError ->
                        CloudSaveOperationState.Error("序列化失败: ${result.message}")
                    is TapCloudSaveManager.CloudSaveResult.FileTooLarge -> {
                        val actualMb = result.actualBytes / (1024 * 1024)
                        CloudSaveOperationState.Error("存档过大(${actualMb}MB)，无法上传")
                    }
                    is TapCloudSaveManager.CloudSaveResult.NoSaveExists ->
                        CloudSaveOperationState.Error("保存失败")
                    is TapCloudSaveManager.CloudSaveResult.VersionMismatch ->
                        CloudSaveOperationState.Error("版本不兼容: ${result.cloudVersion}")
                    is TapCloudSaveManager.CloudSaveResult.UnknownError ->
                        CloudSaveOperationState.Error("未知错误: ${result.message}")
                }
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                _cloudSaveOperationState.value = CloudSaveOperationState.Error("上传失败: ${e.message}")
            }
    }

    fun downloadFromCloudSave() {
        if (!cloudDownloadLock.compareAndSet(false, true)) {
            Log.w(TAG, "Cloud download already in progress, ignoring")
            return
        }
        // 2026-08-04 修复：与本地读档/加载重叠保护——云下载期间若有读档进行中，
        // 下载数据与加载状态并发会破坏状态一致性
        if (stateStore.isLoading.value) {
            Log.w(TAG, "Load in progress, ignoring cloud download request")
            _cloudSaveOperationState.value = CloudSaveOperationState.Error("正在加载中，请稍后")
            cloudDownloadLock.set(false)
            return
        }
        viewModelScope.launch(ioDispatcher.dispatcher) {
            // C-8：云下载主流程提取（行为逐行一致）
            performCloudDownload()
        }
    }

    /** C-8：云下载主流程（downloadFromCloudSave 协程体提取）。 */
    @Suppress("ReturnCount") // 云档多失败守卫（空数据/损坏/写入失败），多 return 为守卫风格
    private suspend fun performCloudDownload() {
            try {
                if (!isCloudSaveAvailable()) {
                    _cloudSaveOperationState.value = CloudSaveOperationState.Error("请先登录TapTap账号")
                    return
                }

                _cloudSaveOperationState.value = CloudSaveOperationState.Downloading

                // 2026-08-04 对抗性审查修复（B5）：下载期间不设 isLoading——
                // 并发互斥由 loadGame/saveGame 的 cloudDownloadLock 检查保证；
                // 下载前备份（backupCurrentSlotBeforeCloudLoad）保护本地旧档
                val result = persistenceFacade.tapCloudSaveManager.downloadSave()

                when (result) {
                    is TapCloudSaveManager.CloudSaveResult.Success -> handleCloudDownloadSuccess(result)
                    is TapCloudSaveManager.CloudSaveResult.NoSaveExists ->
                        _cloudSaveOperationState.value = CloudSaveOperationState.Error("云存档不存在")
                    is TapCloudSaveManager.CloudSaveResult.NetworkError ->
                        _cloudSaveOperationState.value = CloudSaveOperationState.Error("网络错误: ${result.message}")
                    is TapCloudSaveManager.CloudSaveResult.AuthRequired ->
                        _cloudSaveOperationState.value = CloudSaveOperationState.Error("请先登录TapTap账号")
                    is TapCloudSaveManager.CloudSaveResult.SerializationError ->
                        _cloudSaveOperationState.value = CloudSaveOperationState.Error("反序列化失败: ${result.message}")
                    is TapCloudSaveManager.CloudSaveResult.VersionMismatch ->
                        _cloudSaveOperationState.value = CloudSaveOperationState.Error("版本不兼容: ${result.cloudVersion}")
                    is TapCloudSaveManager.CloudSaveResult.UnknownError ->
                        _cloudSaveOperationState.value = CloudSaveOperationState.Error("未知错误: ${result.message}")
                    is TapCloudSaveManager.CloudSaveResult.FileTooLarge ->
                        _cloudSaveOperationState.value = CloudSaveOperationState.Error("云存档文件过大")
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
              catch (e: OutOfMemoryError) {
                // 2026-08-04 对抗性审查修复（A9）：恶意/异常超大云档反序列化 OOM 降级
                Log.e(TAG, "云存档数据过大导致内存不足", e)
                _cloudSaveOperationState.value =
                    CloudSaveOperationState.Error("云存档数据过大，内存不足，无法加载")
            } catch (e: Exception) {
                _cloudSaveOperationState.value = CloudSaveOperationState.Error("下载失败: ${e.message}")
            } finally {
                cloudDownloadLock.set(false)
            }
    }


    /** 下载覆盖前备份当前存档（触发 SaveFileManager 原子写入创建 .bak 快照；失败非阻断） */
    private suspend fun backupCurrentSlotBeforeCloudLoad(slot: Int) {
        try {
            val currentData = persistenceFacade.storageFacade.load(slot).getOrNull()
            if (currentData != null) {
                persistenceFacade.storageFacade.save(slot, currentData)
                Log.d(TAG, "Backup: preserved current save for slot $slot before cloud download")
            }
        } catch (e: CancellationException) { throw e }
          catch (e: Exception) {
            Log.w(TAG, "Failed to backup slot $slot before cloud download", e)
        }
    }

    /** 云读档 Success 分支：管线 → 落盘 → 刷新 → 读档（2026-08-04 提取，控制主函数复杂度） */
    @Suppress("ReturnCount") // 云档多失败守卫（空数据/损坏/写入失败），多 return 为守卫风格
    private suspend fun handleCloudLoadSuccess(result: TapCloudSaveManager.CloudSaveResult.Success) {
        val saveData = result.saveData
        if (saveData == null) {
            showError("云存档数据为空")
            return
        }

        // 云档管线统一（2026-08-04 修复）：与本地读档同语义——
        // 版本迁移 → 完整性校验（损坏拒绝/可修复继续）→ 堆叠重建。
        // 原实现直接落盘+读档，旧云档缺字段静默取默认值、修炼值未缩放
        val migration = SaveDataVersionMigrator.migrate(saveData)
        if (migration is MigrationResult.Rejected) {
            // T10（2026-08-04）：saveVersion 越界（负数/伪造高版本）显式拒绝
            showError("云存档版本异常：${migration.reason}")
            return
        }
        var processed = (migration as MigrationResult.Migrated).data
        val validation = SaveValidator.validate(processed)
        when (validation) {
            is IntegrityResult.Corrupted -> {
                showError("云存档数据损坏，无法加载")
                return
            }
            is IntegrityResult.Repaired -> {
                Log.w(TAG, "云存档完整性修复 ${validation.details.size} 项")
                processed = validation.data
            }
            is IntegrityResult.Passed -> {}
        }
        processed = SaveDataReconciler.reconcileStacks(processed)

        // 从云存档数据中提取 slot，写入本地
        // 2026-08-04 对抗性审查修复（A15）：currentSlot 越界（0/负值/超大——老版本
        // 云档 currentSlot=0 是常见场景）不再折叠到槽位 1 覆盖玩家存档，改用当前槽位
        val rawSlot = processed.gameData.currentSlot
        val slot = if (rawSlot in 1..StorageConstants.DEFAULT_MAX_SLOTS) {
            rawSlot
        } else {
            Log.w(TAG, "云存档 currentSlot=$rawSlot 越界，使用当前槽位")
            persistenceFacade.storageFacade.getCurrentSlot()
        }
        persistenceFacade.storageFacade.setCurrentSlot(slot)

        // 下载覆盖前备份当前存档（触发 StorageEngine SaveFileManager 原子写入创建 .bak 快照）
        backupCurrentSlotBeforeCloudLoad(slot)

        // 2026-08-04 修复：检查写入结果——原实现忽略结果，写库失败后
        // 继续读档会读到旧数据（或报"存档为空"），玩家误以为云档已加载
        val saveResult = persistenceFacade.storageFacade.save(slot, processed)
        if (saveResult.isFailure) {
            showError("云存档写入本地失败，请重试")
            return
        }

        // 刷新存档元数据缓存
        try {
            _saveSlots.value = persistenceFacade.storageFacade.getSaveSlotsSuspend()
        } catch (e: CancellationException) { throw e }
          catch (e: Exception) {
            Log.w(TAG, "loadFromCloudSave: failed to refresh save slots", e)
        }

        // 走正常读档流程（BootSequenceController.boot + 资源预加载）
        // C1 修复（2026-08-05）：fromCloudLoad=true 绕过 loadGameInternal 的
        // cloudDownloadLock 守卫——本路径仍持有该锁（performCloudLoad 全程持有），
        // 原实现被守卫拒绝：云档已落盘但内存加载永不执行，主菜单云读档必失败
        loadGameFromSlot(slot, fromCloudLoad = true)
    }

    /** 云下载 Success 分支：管线 → 落盘 → 内存加载 → boot（2026-08-04 提取，控制主函数复杂度） */
    @Suppress("ReturnCount") // 云档多失败守卫（空数据/损坏/写入失败），多 return 为守卫风格
    private suspend fun handleCloudDownloadSuccess(result: TapCloudSaveManager.CloudSaveResult.Success) {
        val saveData = result.saveData
        if (saveData == null) {
            _cloudSaveOperationState.value = CloudSaveOperationState.Error("云存档为空")
            return
        }

        // 跨版本兼容提示：云端存档版本与当前版本不同时仅警告不阻止
        val cloudVersion = _cloudSaveInfo.value?.appVersion ?: ""
        if (cloudVersion.isNotBlank() && cloudVersion != GameConfig.Game.VERSION) {
            Log.w(TAG, "云存档版本 $cloudVersion ≠ 当前版本 ${GameConfig.Game.VERSION}，可能不兼容")
        }

        // 云档管线统一（2026-08-04 修复）：与本地读档同语义——
        // 版本迁移 → 完整性校验（损坏拒绝/可修复继续）→ 堆叠重建。
        // 原实现绕过 saveVersion 迁移，旧云档修炼值未缩放
        val migration = SaveDataVersionMigrator.migrate(saveData)
        if (migration is MigrationResult.Rejected) {
            // T10（2026-08-04）：saveVersion 越界（负数/伪造高版本）显式拒绝
            _cloudSaveOperationState.value =
                CloudSaveOperationState.Error("云存档版本异常：${migration.reason}")
            return
        }
        var processed = (migration as MigrationResult.Migrated).data
        val validation = SaveValidator.validate(processed)
        when (validation) {
            is IntegrityResult.Corrupted -> {
                _cloudSaveOperationState.value =
                    CloudSaveOperationState.Error("云存档数据损坏，无法加载")
                return
            }
            is IntegrityResult.Repaired -> {
                Log.w(TAG, "云存档完整性修复 ${validation.details.size} 项")
                processed = validation.data
            }
            is IntegrityResult.Passed -> {}
        }
        // 旧格式云存档无堆叠数据：从实例重建兜底（2026-08-01 堆叠序列化缺陷修复）
        val reconciled = SaveDataReconciler.reconcileStacks(processed)
        val effectiveSlot = persistenceFacade.storageFacade.getCurrentSlot()

        // 2026-08-04 对抗性审查修复（B5）：下载覆盖前备份当前存档（对齐主菜单云读档路径）
        backupCurrentSlotBeforeCloudLoad(effectiveSlot)

        // 2026-08-04 修复：持久化本地 DB——原实现只加载内存不落盘，
        // 重启后回到旧档（玩家误以为下载失败）；与主菜单云读档
        // （performCloudLoad）语义对齐。写入失败则中止，不进入内存加载
        val saveResult = persistenceFacade.storageFacade.save(effectiveSlot, reconciled)
        if (saveResult.isFailure) {
            _cloudSaveOperationState.value =
                CloudSaveOperationState.Error("云存档写入本地失败，请重试")
            return
        }

        applyCloudSaveToEngine(reconciled, effectiveSlot)
    }

    /** 云下载后的内存加载 + boot（2026-08-04 提取，控制 handleCloudDownloadSuccess 行数） */
    private suspend fun applyCloudSaveToEngine(reconciled: SaveData, effectiveSlot: Int) {
        gameEngine.loadData(
            gameData = reconciled.gameData.copy(currentSlot = effectiveSlot),
            disciples = reconciled.disciples,
            equipmentStacks = reconciled.equipmentStacks,
            equipmentInstances = reconciled.equipmentInstances,
            manualStacks = reconciled.manualStacks,
            manualInstances = reconciled.manualInstances,
            pills = reconciled.pills,
            materials = reconciled.materials,
            herbs = reconciled.herbs,
            seeds = reconciled.seeds,
            storageBags = reconciled.storageBags,
            teams = reconciled.teams,
            battleLogs = reconciled.battleLogs,
            alliances = reconciled.alliances,
            productionSlots = reconciled.productionSlots
        )

        val bootResult = persistenceFacade.bootSequenceController.boot(
            slot = effectiveSlot,
            onPreloadResources = { preloadGameResources() },
            onProgress = { progress ->
                _loadingProgress.value = PROGRESS_START + progress * (PROGRESS_COMPLETE - PROGRESS_START)
            },
            onMapReady = { mapData -> _mapPreloadData.value = mapData }
        )

        if (bootResult.isSuccess) {
            // 与本地读档/新游戏路径一致：注入白名单福利
            gameEngine.sendWhitelistBonus(effectiveSlot)

            // 专属福利：定向用户邮件（2026-09-04 截止，每档一次，非目标用户自动跳过）
            gameEngine.sendExclusiveBonus(effectiveSlot)

            _cloudSaveOperationState.value = CloudSaveOperationState.Success("云存档下载成功")
            _cloudSaveInfo.value = persistenceFacade.tapCloudSaveManager.checkCloudSave()
        } else {
            _cloudSaveOperationState.value = CloudSaveOperationState.Error(
                "读取云存档失败: ${bootResult.exceptionOrNull()?.message}"
            )
        }
    }


    fun resetCloudSaveOperationState() {
        _cloudSaveOperationState.value = CloudSaveOperationState.Idle
    }

    private suspend fun createSaveDataFromSnapshot(snapshot: com.xianxia.sect.core.engine.GameStateSnapshot): SaveData {
        return SaveDataTrimmer.trimSaveData(snapshot)
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // 清理阶段兜底日志，非业务异常
    override fun onCleared() {
        Log.i(TAG, "SaveLoadViewModel cleared")

        // ★ 异步清理协程（NonCancellable 确保即使 viewModelScope 取消也执行完成）
        viewModelScope.launch(NonCancellable + ioDispatcher.dispatcher) {
            try {
                // 等待保存完成（最长 2 秒，挂起式等待不阻塞主线程）
                withTimeout(2000) {
                    while (stateStore.isSaving.value) {
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
        // 2026-08-01：走引擎线程（onCleared 主线程直调违反双线程模型），
        // NonCancellable 保证清理不被取消
        viewModelScope.launch(NonCancellable) {
            try {
                gameEngine.resetLifecycleState()
                gameEngine.setPausedDirectOnEngine(true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "resetLifecycleState failed", e)
            }
        }
        super.onCleared()
    }

    fun togglePause() {
        val wasPaused = stateStore.isPaused.value
        gameEngine.launchOnEngine {
            if (wasPaused) {
                gameEngineCore.resume()
            } else {
                gameEngineCore.pause()
            }
        }
        if (wasPaused && !gameEngineCore.isGameLoopRunning) {
            startGameLoop()
        }
    }

    private val _timeScale = MutableStateFlow(1)
    val timeScale: StateFlow<Int> = _timeScale.asStateFlow()

    val timeSpeed: StateFlow<Int> = gameClock.speedFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, 1)

    // S6 修复（对抗性审查）：P-8 漏迁——改用 isPaused 窄流（零采样延迟）
    val isPaused: StateFlow<Boolean> = gameEngineCore.isPaused

    fun setTimeSpeed(speed: Int) {
        // UI 只有 1x/2x：封死 0（speed=0 会产生"tick 在跑但时间不动"的假运行，
        // 所有看门狗失明）。GameTimeClock 保留 0 内部语义（旧档/测试兼容），
        // 任何残留 0 由看门狗 FakeRunDetected 兜底自愈。
        val clamped = speed.coerceIn(1, 2)
        _timeScale.value = clamped  // UI 即时反馈
        gameClock.setSpeed(clamped)
        if (gameEngineCore.isPausedDirect) {
            gameEngine.launchOnEngine {
                gameEngineCore.resume()
            }
            if (!gameEngineCore.isGameLoopRunning) {
                startGameLoop()
            }
        }
    }

    fun resetAllDisciplesStatus() {
        gameEngine.launchOnEngine {
            gameEngine.resetAllDisciplesStatus()
        }
    }
}

sealed class CloudSaveOperationState {
    data object Idle : CloudSaveOperationState()
    data object Uploading : CloudSaveOperationState()
    data object Downloading : CloudSaveOperationState()
    data class Success(val message: String) : CloudSaveOperationState()
    data class Error(val message: String) : CloudSaveOperationState()
}
