@file:Suppress("FileLength") // 私有辅助函数集中在本文件
package com.xianxia.sect.ui.game

import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.model.MapPreloadData
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.resetAllDisciplesStatus
import com.xianxia.sect.core.engine.resetLifecycleState
import com.xianxia.sect.core.engine.setPausedDirectOnEngine
import com.xianxia.sect.core.engine.setSaveLoadFlags
import com.xianxia.sect.core.state.BootPhase
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.RunState
import com.xianxia.sect.taptap.TapCloudSaveManager
import com.xianxia.sect.data.model.SaveSlot
import com.xianxia.sect.ui.components.AtlasResult
import com.xianxia.sect.core.engine.di.IoDispatcher
import com.xianxia.sect.ui.game.saveload.PersistenceFacade
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.coroutines.resume
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import com.xianxia.sect.core.engine.pause
import com.xianxia.sect.core.engine.resume

@HiltViewModel
class SaveLoadViewModel @Inject constructor(
    /** 以下依赖/状态字段 internal 化：同包流程扩展（SaveLoadViewModel*Ops）消费——§2.29 先例 */
    internal val gameEngine: GameEngine,
    internal val gameEngineCore: GameEngineCore,
    internal val stateStore: GameStateStore,
    private val gameClock: com.xianxia.sect.core.engine.system.GameTimeClock,
    internal val resourcePreloader: ResourcePreloader,
    internal val persistenceFacade: PersistenceFacade,
    internal val ioDispatcher: IoDispatcher
) : BaseViewModel() {

    // 领域委托实例 — 按职责拆分 save/load/restart 等逻辑

    companion object {
        private const val TAG = SaveLoadViewModelConstants.TAG
        private const val MB = SaveLoadViewModelConstants.MB
        private const val PROGRESS_START = SaveLoadViewModelConstants.PROGRESS_START
        private const val PROGRESS_SAVE_COMPLETE = SaveLoadViewModelConstants.PROGRESS_SAVE_COMPLETE
        private const val PROGRESS_DATA_PRELOAD = SaveLoadViewModelConstants.PROGRESS_DATA_PRELOAD
        private const val PROGRESS_SPRITE_PRELOAD = SaveLoadViewModelConstants.PROGRESS_SPRITE_PRELOAD
        const val PROGRESS_MAP_PRELOAD = SaveLoadViewModelConstants.PROGRESS_MAP_PRELOAD
        private const val PROGRESS_COMPLETE = SaveLoadViewModelConstants.PROGRESS_COMPLETE
        private const val GAME_LOOP_STOP_TIMEOUT_MS = SaveLoadViewModelConstants.GAME_LOOP_STOP_TIMEOUT_MS
    }

    /**
     * 启动 L2 后台精灵图预加载（不阻塞首帧）
     * 在 MainGameScreen 已显示后调用
     */
    fun launchL2Preload() {
        resourcePreloader.launchBackgroundPreload(viewModelScope) { sprites ->
            _l2Sprites.value = _l2Sprites.value + sprites
        }
    }

    internal val saveLock = AtomicBoolean(false)
    internal val loadLock = AtomicBoolean(false)
    internal val cloudDownloadLock = AtomicBoolean(false)

    // 游戏是否已加载 = RunState.PLAYING
    val isGameLoaded: Boolean get() = stateStore.runState.value == RunState.PLAYING

    internal val isRestartingFlow = MutableStateFlow(false)
    val isRestarting: StateFlow<Boolean> = isRestartingFlow.asStateFlow()

    internal val loadingProgressFlow = MutableStateFlow(0f)
    val loadingProgress: StateFlow<Float> = loadingProgressFlow.asStateFlow()

    internal val preloadedItemSpritesFlow = MutableStateFlow<Map<Int, ImageBitmap>>(emptyMap())
    val preloadedItemSprites: StateFlow<Map<Int, ImageBitmap>> = preloadedItemSpritesFlow.asStateFlow()

    internal val preloadedPortraitSpritesFlow = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val preloadedPortraitSprites: StateFlow<Map<String, ImageBitmap>> = preloadedPortraitSpritesFlow.asStateFlow()

    internal val preloadedUiSpritesFlow = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val preloadedUiSprites: StateFlow<Map<String, ImageBitmap>> = preloadedUiSpritesFlow.asStateFlow()

    internal val atlasResultFlow = MutableStateFlow<AtlasResult?>(null)
    val atlasResult: StateFlow<AtlasResult?> = atlasResultFlow.asStateFlow()

    /** L2 后台加载的剩余精灵图（异步累积，不阻塞首帧） */
    private val _l2Sprites = MutableStateFlow<Map<Int, ImageBitmap>>(emptyMap())
    val l2Sprites: StateFlow<Map<Int, ImageBitmap>> = _l2Sprites.asStateFlow()

    /** 预加载阶段标签（UI 展示用） */
    internal val preloadPhaseFlow = MutableStateFlow(SaveLoadViewModelConstants.PHASE_INIT)
    val preloadPhase: StateFlow<String> = preloadPhaseFlow.asStateFlow()

    /** 地图预加载数据 — 由加载管线在游戏循环启动后生成，GameActivity 消费 */
    internal val mapPreloadDataFlow = MutableStateFlow<MapPreloadData?>(null)
    val mapPreloadData: StateFlow<MapPreloadData?> = mapPreloadDataFlow.asStateFlow()

    /** 运行时状态：IDLE / LOADING / PLAYING / RELOADING */
    val runState: StateFlow<RunState> get() = stateStore.runState

    /** 启动序列阶段：UNINITIALIZED / DATA_READY / SYSTEMS_READY / MAP_READY / BOOT_COMPLETE */
    val bootPhase: StateFlow<BootPhase> get() = stateStore.bootPhase

    internal val saveSlotsFlow = MutableStateFlow<List<SaveSlot>>(emptyList())

    internal val pendingSlotFlow = MutableStateFlow<Int?>(null)
    val pendingSlot: StateFlow<Int?> = pendingSlotFlow.asStateFlow()

    internal val pendingActionFlow = MutableStateFlow<String?>(null)
    val pendingAction: StateFlow<String?> = pendingActionFlow.asStateFlow()

    // ── 云存档状态 ──
    internal val cloudSaveInfoFlow = MutableStateFlow(TapCloudSaveManager.CloudSaveInfo(false))
    val cloudSaveInfo: StateFlow<TapCloudSaveManager.CloudSaveInfo> = cloudSaveInfoFlow.asStateFlow()

    /**
     * 存档槽位列表（slot 0 云存档槽位合并真实云存档信息）。
     *
     * StorageEngine.getSaveSlots() 的 slot 0 是硬编码全 0 占位（游戏内存档
     * 对话框直接渲染会显示"第0年0月/弟子 0/灵石 0"）；此处用 [cloudSaveInfoFlow]
     * （checkCloudSave/上传/下载维护的真实云端摘要）覆盖占位字段，使游戏内
     * 展示与主菜单选择存档界面的云存档入口数据一致。
     */
    val saveSlots: StateFlow<List<SaveSlot>> =
        combine(saveSlotsFlow, cloudSaveInfoFlow) { slots, cloudInfo ->
            mergeCloudSlot(slots, cloudInfo)
        }.stateIn(viewModelScope, sharingStarted, emptyList())
    internal val cloudSaveInfoVersion = java.util.concurrent.atomic.AtomicInteger(0)

    internal val cloudSaveOperationStateFlow = MutableStateFlow<CloudSaveOperationState>(CloudSaveOperationState.Idle)
    val cloudSaveOperationState: StateFlow<CloudSaveOperationState> = cloudSaveOperationStateFlow.asStateFlow()

    val saveLoadState: StateFlow<SaveLoadState> = combine(
        stateStore.isSaving,
        stateStore.isLoading,
        pendingSlotFlow,
        pendingActionFlow
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

    internal val isTimeRunningFlow = MutableStateFlow(false)
    val isTimeRunning: StateFlow<Boolean> = isTimeRunningFlow.asStateFlow()

    /** 重开版本号，每次成功重开后递增，用于通知 UI 层强制重建烘焙管线 */
    internal val restartVersionFlow = MutableStateFlow(0)
    val restartVersion: StateFlow<Int> = restartVersionFlow.asStateFlow()

    init {
        // 加载存档元数据 — 运行在 IO 调度器上，避免主线程等待 Room 查询
        // 防御兜底: 存档槽读取失败重试一次后放弃(UI 显示空槽), IO/序列化异常不可枚举
        @Suppress("TooGenericExceptionCaught")
        viewModelScope.launch(ioDispatcher.dispatcher) {
            try {
                saveSlotsFlow.value = persistenceFacade.storageFacade.getSaveSlotsSuspend()
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                Log.e(TAG, "Failed to load save slots in init, retrying after delay", e)
                delay(500)
                try {
                    saveSlotsFlow.value = persistenceFacade.storageFacade.getSaveSlotsSuspend()
                } catch (e: CancellationException) { throw e }
                  catch (e2: Exception) {
                    Log.e(TAG, "Retry loading save slots also failed", e2)
                }
            }
        }

        // 看门狗病理复位事件 → 用户可见错误提示（静默失败玩家无从感知）
        viewModelScope.launch {
            gameEngineCore.stuckResetEvents.collect { message ->
                showError(message)
            }
        }

    }

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    fun resetSaveLoadState() {
        gameEngine.launchOnEngine {
            // Q-1：收敛到引擎原子入口（单事务设置两标志）
            try {
                gameEngine.setSaveLoadFlags(false, false)
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { Log.w(TAG, "resetSaveLoadState: setSaveLoadFlags failed: ${e.message}") }
        }
        pendingSlotFlow.value = null
        pendingActionFlow.value = null
    }

    fun isGameAlreadyLoaded(): Boolean {
        return isGameLoaded && gameEngine.gameData.value?.sectName?.isNotEmpty() == true
    }

    fun setLoadingProgress(progress: Float) {
        loadingProgressFlow.value = progress
    }

    /**
     * 启动流程是否正在进行（透传 [BootSequenceController.bootInProgress]）。
     *
     * UI 层监听此状态禁用云存档/重置等入口按钮
     *（防御性增强；入口层统一守卫已保证安全，此处仅为减少"点击后被拒绝"的体验）。
     */
    val bootInProgress: StateFlow<Boolean>
        get() = persistenceFacade.bootSequenceController.bootInProgress

    @Suppress("ReturnCount") // 并发守卫多入口（boot/云锁/重启/加载/保存/内存），多 return 为守卫风格
    fun startNewGame(sectName: String, slot: Int = 1) {
        // 统一 boot 守卫 + 云锁/重启/加载/保存互斥检查
        if (isBootOperationBlocked()) return
        if (cloudDownloadLock.get()) {
            Log.w(TAG, "Cloud save operation in progress, ignoring startNewGame request")
            showError("云存档操作进行中，请稍后开始新游戏")
            return
        }
        if (isRestartingFlow.value) {
            Log.w(TAG, "Restarting, ignoring startNewGame request")
            showError("游戏重置中，请稍后开始新游戏")
            return
        }
        if (loadLock.get() || saveLock.get()) {
            Log.w(TAG, "Save/load in progress, ignoring startNewGame request")
            showError("正在保存/读档中，请稍后开始新游戏")
            return
        }
        if (stateStore.isSaving.value) {
            Log.w(TAG, "Currently saving, ignoring startNewGame request")
            showError("正在保存中，请稍后开始新游戏")
            return
        }
        if (stateStore.isLoading.value && loadingProgressFlow.value < PROGRESS_COMPLETE) {
            Log.w(TAG, "Already loading with progress ${loadingProgressFlow.value}, ignoring startNewGame request")
            return
        }

        if (isGameLoaded) {
            Log.w(TAG, "Game already loaded, ignoring startNewGame request")
            return
        }

        Log.i(TAG, "=== startNewGame BEGIN === sectName=$sectName, slot=$slot")
        val startTime = System.currentTimeMillis()

        // job 身份由 perform* 内部 coroutineContext[Job] 自取，不经 lateinit
        // 捕获传入协程体——空闲 IO worker 可能先于调用线程赋值执行协程体，
        // 实参求值读未赋值 lateinit 即抛 UninitializedPropertyAccessException
        //（Dispatchers.IO 是 LimitedDispatcher）
        val job = viewModelScope.launch(ioDispatcher.dispatcher) {
            // 新游戏主流程
            performStartNewGame(sectName, slot, startTime)
        }
        gameEngineCore.registerActiveLoadJob(job)
    }

    @Suppress("TooGenericExceptionCaught", "ReturnCount") // 云下载自包含入口多守卫（boot/重启/保存/云锁/加载），多 return 为守卫风格
    fun loadGameFromSlot(slot: Int, fromCloudLoad: Boolean = false) {
        // boot 进行中禁止任何读档/云下载入口
        if (isBootOperationBlocked()) return
        // slot 0 = 从云端下载（带 saveLoadState 管理 + 结果反馈）
        if (slot == 0) {
            // 云会话下载自包含入口：直接执行 performCloudDownload，不经过
            // downloadFromCloudSave 入口——协程开头即置位 isLoading，
            // SaveSlotDialog 立即显示"读取中..."转圈，覆盖下载全程反馈。
            //（若在 cloudSaveOperationStateFlow.first{} 之后才置位，下载最耗时的
            // 阶段无任何反馈；且本入口不能先置位再走 downloadFromCloudSave，
            // 其自身的 isLoading 守卫会拒绝）。
            // 互斥守卫与 downloadFromCloudSave 对齐（boot/重启/保存/云锁/加载）。
            if (isRestartingFlow.value) {
                Log.w(TAG, "Restarting, ignoring cloud slot load request")
                showError("游戏重置中，请稍后读取云存档")
                return
            }
            if (saveLock.get()) {
                Log.w(TAG, "Save in progress, ignoring cloud slot load request")
                showError("正在保存中，请稍后读取云存档")
                return
            }
            if (!cloudDownloadLock.compareAndSet(false, true)) {
                Log.w(TAG, "Cloud download already in progress, ignoring")
                return
            }
            if (stateStore.isLoading.value) {
                Log.w(TAG, "Load in progress, ignoring cloud slot load request")
                cloudDownloadLock.set(false)
                showError("正在加载中，请稍后读取云存档")
                return
            }
            viewModelScope.launch(ioDispatcher.dispatcher) {
                resetCloudSaveOperationState()
                // 立即置位：下载/加载全程 SaveSlotDialog 显示"读取中..."转圈
                setSaveLoadState(isLoading = true, pendingSlot = 0, pendingAction = "load")
                try {
                    performCloudDownload()
                    // 等待云端操作完成（Downloading → Success/Error）
                    cloudSaveOperationStateFlow.first {
                        it is CloudSaveOperationState.Success || it is CloudSaveOperationState.Error
                    }
                    when (val state = cloudSaveOperationStateFlow.value) {
                        is CloudSaveOperationState.Success -> showSuccess(state.message)
                        is CloudSaveOperationState.Error -> showError(state.message)
                        else -> {} // Idle 不应出现
                    }
                } catch (e: CancellationException) { throw e }
                  catch (e: Exception) {
                    showError("下载失败: ${e.message}")
                } finally {
                    // performCloudDownload 的 finally 已释放锁，此处幂等兜底
                    cloudDownloadLock.set(false)
                    setSaveLoadState(isLoading = false, pendingSlot = null, pendingAction = null)
                }
            }
            return
        }
        // 从已缓存的存档元数据中查找 SaveSlot，兜底构造最小 SaveSlot
        val saveSlot = saveSlotsFlow.value.find { it.slot == slot }
            ?: SaveSlot(slot, "", 0L, 1, 1, "", 0, 0L)
        loadGameInternal(saveSlot, fromCloudLoad)
    }

    /**
     * 从云存档下载并加载游戏（主菜单云存档卡片入口）。
     *
     * 流程：
     * 1. 设置加载进度反馈（loadingProgressFlow=0.1f, "正在同步云存档..."）
     * 2. 下载云存档 (persistenceFacade.tapCloudSaveManager.downloadSave())
     * 3. 写入本地存储 (persistenceFacade.storageFacade.save)
     * 4. 调用 loadGameFromSlot(slot) 走正常 BootSequenceController 启动流程
     * 5. 失败时通过 showError() 展示错误
     *
     * 与 downloadFromCloudSave()（游戏内 SaveSlotDialog 使用）不同，
     * 此方法直接驱动 GameActivity 的 LoadingScreen 进度反馈。
     */
    @Suppress("ReturnCount") // 云读档多守卫（boot/重启/保存/云锁），多 return 为守卫风格
    fun loadFromCloudSave() {
        // boot/重启/保存进行中禁止云读档
        if (isBootOperationBlocked()) return
        if (isRestartingFlow.value) {
            Log.w(TAG, "Restarting, ignoring cloud load request")
            showError("游戏重置中，请稍后读取云存档")
            return
        }
        if (saveLock.get()) {
            Log.w(TAG, "Save in progress, ignoring cloud load request")
            showError("正在保存中，请稍后读取云存档")
            return
        }
        if (!cloudDownloadLock.compareAndSet(false, true)) {
            Log.w(TAG, "Cloud load already in progress, ignoring")
            return
        }
        viewModelScope.launch(ioDispatcher.dispatcher) {
            // 云读档主流程
            performCloudLoad()
        }
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
        isTimeRunningFlow.value = gameEngineCore.isGameLoopRunning
    }

    fun restartGame() {
        if (restartBlockedBeforeLocks()) return
        if (!acquireRestartLocks()) return

        // 入口同步置位（与 setSavingDirect 同模式）——
        // 关闭 "saveLock 已抢但协程未启动" 窗口内 load/save 的穿入
        isRestartingFlow.value = true

        // job 身份由 perform* 内部 coroutineContext[Job] 自取，
        // 不经 lateinit 捕获（避免 IO worker 抢跑读未赋值 lateinit）
        val job = viewModelScope.launch(ioDispatcher.dispatcher) {
            // 重启主流程
            performRestartGame(wasRunning = isTimeRunningFlow.value)
        }
        gameEngineCore.registerActiveLoadJob(job)
    }

    @Suppress("TooGenericExceptionCaught") // 防御兜底: 异常源跨IO/SDK不可枚举, 降级继续+日志留痕, 非静默吞噬
    fun refreshSaveSlots() {
        viewModelScope.launch {
            try {
                saveSlotsFlow.value = persistenceFacade.storageFacade.getSaveSlotsSuspend()
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
            Log.d(TAG, "resumeGameLoop: Skipping startGameLoop - isGameLoaded=$isGameLoaded, " +
                "isLoading=${stateStore.isLoading.value}")
            return
        }
        startGameLoop()
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // 清理阶段兜底日志，非业务异常
    override fun onCleared() {
        Log.i(TAG, "SaveLoadViewModel cleared")

        // 异步清理协程（NonCancellable 确保即使 viewModelScope 取消也执行完成）
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

        // 轻量同步清理：只清理内存状态，不等 I/O
        pendingSlotFlow.value = null
        pendingActionFlow.value = null
        loadingProgressFlow.value = PROGRESS_START

        // 重置生命周期状态，防止 Singleton GameStateStore 在下一次 Activity 创建时
        // 仍保持 PLAYING 导致 isGameLoaded == true 阻止新游戏/读档
        // 必须走引擎线程（onCleared 主线程直调违反双线程模型），
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
        // TOCTOU 防护：wasPaused 的读取与 pause/resume 写入必须同在引擎线程，
        // 原子化——否则主线程读旧值 + 异步派发，连续点击/并发写会读到陈旧值，
        // 第二次点击把刚设的暂停又恢复（表现即"点了暂停游戏还在跑"）。
        gameEngine.launchOnEngine {
            val wasPaused = stateStore.isPaused.value
            if (wasPaused) {
                gameEngineCore.resume()
            } else {
                gameEngineCore.pause()
            }
            // resume 且循环未运行时须重启循环（同引擎线程判断，避免与上面读取竞态）
            if (wasPaused && !gameEngineCore.isGameLoopRunning) {
                startGameLoop()
            }
        }
    }

    private val _timeScale = MutableStateFlow(1)
    val timeScale: StateFlow<Int> = _timeScale.asStateFlow()

    val timeSpeed: StateFlow<Int> = gameClock.speedFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, 1)

    // 使用 isPaused 窄流（零采样延迟）
    val isPaused: StateFlow<Boolean> = gameEngineCore.isPaused

    fun setTimeSpeed(speed: Int) {
        // UI 只有 1x/2x：封死 0（speed=0 会产生"tick 在跑但时间不动"的假运行，
        // 所有看门狗失明）。GameTimeClock 保留 0 内部语义（旧档/测试兼容），
        // 任何残留 0 由看门狗 FakeRunDetected 兜底自愈。
        val clamped = speed.coerceIn(1, 2)
        _timeScale.value = clamped  // UI 即时反馈
        gameClock.setSpeed(clamped)
        // 暂停中调速度不自动恢复。用户处于暂停态时调整倍速，
        // 期望的是"暂停不变，仅调整恢复后的速度"——自动 resume 会违背意图，
        // 导致"暂停却仍被解除"的观感（暂停按钮无效）。
        // 恢复时机由玩家显式点击"继续"控制。
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
