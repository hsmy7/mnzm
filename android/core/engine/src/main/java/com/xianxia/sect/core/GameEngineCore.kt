package com.xianxia.sect.core.engine

import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.engine.BuildConfig
import android.os.Build
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.service.PolicyCostResult
import com.xianxia.sect.core.engine.domain.exploration.ExplorationService
import com.xianxia.sect.core.engine.system.SystemManager
import com.xianxia.sect.core.engine.system.TimeSystem
import com.xianxia.sect.core.engine.system.GameTimeClock
import com.xianxia.sect.core.concurrent.ThermalController
import com.xianxia.sect.core.event.*
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.*
import com.xianxia.sect.core.performance.UnifiedPerformanceMonitor
import com.xianxia.sect.core.util.CoroutineScopeProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ## GameEngineCore - 游戏循环控制器
 *
 * ### 架构层级定位（两层状态架构）
 *
 * ```
 * ┌─────────────────────────────────────────────────────────────────┐
 * │ Layer 2: UI (ViewModel/Compose)                                 │
 * │   - 通过 StateFlow 订阅 GameStateStore.unifiedState              │
 * │   - 开销: collectAsState() 触发 Compose 重组                     │
 * ├─────────────────────────────────────────────────────────────────┤
 * │ Layer 1: GameEngineCore + GameEngine                             │
 * │   - EngineCore: 游戏循环控制 (start/stop/tick)                   │
 * │   - Engine: 核心业务逻辑 (修炼/战斗/生产等)                        │
 * │   - 状态写入 GameStateStore → unifiedState Flow 自动派生          │
 * │   - 开销: MutableStateFlow.value 赋值触发下游订阅者               │
 * └─────────────────────────────────────────────────────────────────┘
 * ```
 *
 * ### 状态同步机制
 *
 * GameEngine 直接写入 GameStateStore 的各个 MutableStateFlow，
 * GameStateStore.unifiedState 通过 combine 自动派生，
 * UI 层订阅 unifiedState 即可获得最新状态，无需手动同步。
 */
@Singleton
class GameEngineCore @Inject constructor(
    private val stateStore: GameStateStore,
    private val eventBus: EventBusPort,
    private val unifiedPerformanceMonitor: UnifiedPerformanceMonitor,
    private val systemManager: SystemManager,
    private val scopeProvider: CoroutineScopeProvider,
    private val cultivationService: CultivationService,
    private val explorationService: ExplorationService,
    private val gameClock: GameTimeClock,
    private val thermalController: ThermalController
) {

    /**
     * 任务完成检测回调，由 GameEngine 在构造后注入。
     * 每月结算时被调用，确保空闲期间任务完成也能被及时检测。
     */
    @Volatile
    internal var missionCheck: (suspend () -> Unit)? = null

    // ──────── 场景感知帧率控制（P1.3） ────────

    /**
     * 游戏场景枚举 — 用于动态调整帧率预算。
     */
    enum class GameScene(val displayName: String, val targetFrameTimeMs: Long) {
        /** 后台/息屏/无操作 — 最低帧率保电 */
        IDLE("后台", 100L),
        /** 地图滚动/惯性滑行 — 30fps 足够 */
        MAP_SCROLL("地图滚动", 33L),
        /** 正常游戏（Tab、对话框操作）— 60fps */
        GAMEPLAY("游戏", 16L),
        /** 战斗动画 — 60fps 优先 */
        BATTLE("战斗", 16L)
    }

    /** 当前游戏场景（UI 层通过 [onSceneChanged] 设置） */
    @Volatile
    var currentScene: GameScene = GameScene.GAMEPLAY
        private set

    /** 设置游戏场景，引擎据此调整帧率预算和等待时间 */
    fun onSceneChanged(scene: GameScene) {
        if (currentScene != scene) {
            DomainLog.i(TAG, "Scene changed: ${currentScene.displayName} → ${scene.displayName}")
            currentScene = scene
            updateRenderFrameRate()
        }
    }

    /** 场景帧时间预算（单位：ns，用于游戏等待自适应） */
    private val sceneFrameBudgetNs: Long
        get() = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(currentScene.targetFrameTimeMs)

    /** 渲染帧率发布（供 NativeSurfaceView/SoftwareCanvasBackend 参考） */
    private val _renderFrameRate = MutableStateFlow(60)
    val renderFrameRate: StateFlow<Int> = _renderFrameRate.asStateFlow()

    /** 渲染质量因子发布（供 SoftwareCanvasBackend/Compose UI 参考） */
    private val _renderingQualityFactor = MutableStateFlow(1.0f)
    val renderingQualityFactor: StateFlow<Float> = _renderingQualityFactor.asStateFlow()

    /** 是否关闭装饰层（热控降级时） */
    private val _decorationsDisabled = MutableStateFlow(false)
    val decorationsDisabled: StateFlow<Boolean> = _decorationsDisabled.asStateFlow()

    private fun updateRenderFrameRate() {
        // 热控建议帧率与场景帧率取其小（降级优先）
        val thermalFps = thermalController.recommendedTargetFps
        val sceneFps = when (currentScene) {
            GameScene.IDLE -> 10
            GameScene.MAP_SCROLL -> 30
            GameScene.GAMEPLAY -> 60
            GameScene.BATTLE -> 60
        }
        val effectiveFps = minOf(thermalFps, sceneFps)
        _renderFrameRate.value = effectiveFps
        _renderingQualityFactor.value = thermalController.renderingQualityFactor
        _decorationsDisabled.value = thermalController.particlesDisabled
    }

    /**
     * UI 层通知引擎：用户活跃（有触摸/操作），自动切换到 GAMEPLAY。
     * 闲置超过 30s 后自动切回 IDLE。
     */
    @Volatile
    private var lastUserActivityTimeNs: Long = 0L
    private val IDLE_TIMEOUT_NS = java.util.concurrent.TimeUnit.SECONDS.toNanos(30)

    /** 通知引擎用户有操作 */
    fun onUserActivity() {
        lastUserActivityTimeNs = System.nanoTime()
        if (currentScene == GameScene.IDLE) {
            onSceneChanged(GameScene.GAMEPLAY)
        }
    }

    /** 检查是否需要因闲置而降帧 */
    private fun checkIdleTimeout(nowNs: Long) {
        if (currentScene == GameScene.GAMEPLAY || currentScene == GameScene.MAP_SCROLL) {
            if (lastUserActivityTimeNs > 0 && (nowNs - lastUserActivityTimeNs) >= IDLE_TIMEOUT_NS) {
                onSceneChanged(GameScene.IDLE)
            }
        }
    }

    companion object {
        private const val TAG = "GameEngineCore"
        private const val TICK_INTERVAL_MS = 100L
        private const val MIN_TICK_DELAY_MS = 16L
        private const val TICK_WARNING_THRESHOLD_MS = 100f
        private const val STUCK_STATE_TIMEOUT_MS = 10_000L  // 从30s降至10s，更快恢复
        private const val ADAPTIVE_MAX_INTERVAL_MS = 1000L
        private const val TICK_TIME_BUDGET_MS = 50L
        // ★ 帧驱动 Accumulator 常量
        private val LOGIC_DT_NS = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(100)  // 逻辑步长 100ms
        private val MAX_ACCUMULATOR_NS = LOGIC_DT_NS * 5  // 最多累积 5 步
        // 自适应忙等等阈值
        private const val ANTI_FREEZE_TRIGGER_THRESHOLD = 3
        private const val ANTI_FREEZE_NORMAL_THRESHOLD = 20

        // 游戏引擎线程 — 非守护线程 + 最高优先级。
        // 红米K80 (HyperOS 2.0) 实测：守护线程会被电源管理挂起，
        // 导致触摸后游戏时间冻结。与看门狗线程对齐为非守护线程，
        // 优先级从 NORM-1 提升至 MAX，防止主线程重组抢占导致 delay() 续体饥饿。
        private val gameThreadFactory = ThreadFactory {
            val thread = Thread(it, "GameEngine-Thread")
            thread.priority = Thread.MAX_PRIORITY
            thread.isDaemon = false
            thread
        }

        private val GAME_DISPATCHER = Executors.newSingleThreadExecutor(gameThreadFactory)
            .asCoroutineDispatcher()

        // 看门狗专用线程工厂 — 非守护线程，独立于 Dispatchers.Default。
        // 荣耀 MagicOS 等 OEM 电源管理会挂起守护线程池中的线程，
        // 非守护线程可防止看门狗自身被冻结，确保检测→恢复链路完整。
        private val watchdogThreadFactory = ThreadFactory {
            val thread = Thread(it, "GameEngine-Watchdog")
            thread.priority = Thread.NORM_PRIORITY
            thread.isDaemon = false
            thread
        }

        private val WATCHDOG_DISPATCHER = Executors.newSingleThreadExecutor(watchdogThreadFactory)
            .asCoroutineDispatcher()

        /** 看门狗指数退避上限（毫秒） */
        internal const val WATCHDOG_MAX_BACKOFF_MS = 30_000L

        /**
         * 计算看门狗指数退避的下一个间隔。
         *
         * 规则：
         * - tick 有推进（已恢复）→ 重置为 [baseIntervalMs]
         * - tick 停滞 → 当前间隔翻倍，上限 [WATCHDOG_MAX_BACKOFF_MS]
         *
         * @param currentBackoffMs 当前退避间隔
         * @param baseIntervalMs 初始间隔（由 OemPowerProfile 驱动）
         * @param hasRecovered 本次检查 tick 是否有推进
         * @return 下一次检查的等待间隔
         */
        internal fun computeWatchdogBackoff(
            currentBackoffMs: Long,
            baseIntervalMs: Long,
            hasRecovered: Boolean
        ): Long {
            return if (hasRecovered) {
                baseIntervalMs
            } else {
                (currentBackoffMs * 2).coerceAtMost(WATCHDOG_MAX_BACKOFF_MS)
            }
        }
    }

    /** 看门狗异常处理器 — 防止看门狗因未处理异常静默死亡 */
    private val watchdogExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        if (throwable !is CancellationException) {
            DomainLog.e(TAG, "Watchdog: unhandled exception — watchdog may have died", throwable)
        }
    }

    private var currentTickInterval = TICK_INTERVAL_MS

    /** 可变的游戏循环调度器，紧急重启时可替换为新线程 */
    @Volatile
    private var gameDispatcher: CoroutineDispatcher = GAME_DISPATCHER

    /** 紧急重启中标志，防止重入 */
    @Volatile
    private var isEmergencyRestarting = false

    // ★ 插值因子（供 UI 平滑渲染，由 frame-driven 循环维护）
    @Volatile
    var currentAlpha: Float = 0f
        private set

    // ── 自适应忙等 ──
    @Volatile
    private var antiFreezeEnabled = false
    private var antiFreezeTriggerCount = 0
    private var consecutiveNormalTicks = 0
    private var lastActualElapsedMs = 0L

    // 后台停止时保存状态
    @Volatile
    private var wasRunningBeforeBackground = false
    
    private val engineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        if (throwable !is CancellationException) {
            DomainLog.e(TAG, "Unhandled exception in engine coroutine", throwable)
        }
    }
    private var engineJob = SupervisorJob(scopeProvider.scope.coroutineContext[Job])
    private var engineScope: CoroutineScope = CoroutineScope(engineJob + gameDispatcher + engineExceptionHandler)

    fun launchInScope(block: suspend CoroutineScope.() -> Unit): Job = engineScope.launch(block = block)

    fun scopeForStateIn(): CoroutineScope = engineScope
    private var gameLoopJob: Job? = null
    private var gameLoopStoppedSignal = CompletableDeferred<Unit>()
    private var deathEventJob: Job? = null
    
    private val _tickCount = MutableStateFlow(0L)
    val tickCount: StateFlow<Long> = _tickCount.asStateFlow()
    
    private val _fps = MutableStateFlow(0f)
    val fps: StateFlow<Float> = _fps.asStateFlow()
    
    private var lastFrameTime = System.currentTimeMillis()
    private var frameCount = 0
    private var fpsAccumulator = 0f
    
    val state: StateFlow<UnifiedGameState> get() = stateStore.unifiedState
    val events: Flow<DomainEvent> get() = eventBus.events

    @Volatile
    private var _autoSaveTrigger = Channel<Unit>(capacity = Channel.BUFFERED)
    val autoSaveTrigger: Flow<Unit> get() = _autoSaveTrigger.receiveAsFlow()
    
    private var isInitialized = false

    /** 记录 isSaving 变为 true 的时间戳，用于看门狗检测 */
    @Volatile
    private var savingStartTime: Long = 0L

    /** 记录 isLoading 变为 true 的时间戳，用于看门狗检测 */
    @Volatile
    private var loadingStartTime: Long = 0L

    /** 当前正在运行的保存协程 Job，用于看门狗强制取消 */
    @Volatile
    private var activeSaveJob: Job? = null

    /** 当前正在运行的加载协程 Job，用于看门狗强制取消 */
    @Volatile
    private var activeLoadJob: Job? = null

    /** 独立看门狗 Job — 运行在 Dispatchers.Default 上，监控游戏线程是否卡死 */
    private var watchdogJob: Job? = null

    /** 看门狗恢复尝试次数（跨重启累计，仅在 tick 推进时重置） */
    private var watchdogRecoveryAttempts = 0

    /** 看门狗连续失败次数达到此阈值后使用更长间隔，避免 OEM 永久挂起时频繁重启 */
    private val watchdogDegradedThreshold = 10

    fun initialize() {
        if (isInitialized) {
            DomainLog.w(TAG, "GameEngineCore already initialized")
            return
        }
        if (_autoSaveTrigger.isClosedForSend) {
            _autoSaveTrigger = Channel(capacity = Channel.BUFFERED)
        }
        systemManager.initializeAll()
        isInitialized = true
        DomainLog.i(TAG, "GameEngineCore initialized")
        DomainLog.i(TAG, "GameEngineCore initialized successfully")
    }
    
    fun startGameLoop() {
        if (gameLoopJob?.isActive == true) {
            DomainLog.w(TAG, "Game loop already running")
            return
        }

        // 全新启动时重置看门狗累计失败计数，防止跨 session 残留
        // 导致第二次进入游戏时看门狗以降级模式（30s 间隔）启动
        watchdogRecoveryAttempts = 0

        gameClock.start()
        gameLoopStoppedSignal = CompletableDeferred()
        unifiedPerformanceMonitor.start()

        stateStore.setPausedDirect(false)
        DomainLog.i(TAG, "Game state resumed (isPaused=false)")

        val gd = stateStore.gameDataSnapshot
        DomainLog.i(TAG, "startGameLoop: lifecycle=${stateStore.gameLifecycle.value}, " +
            "speed=${gameClock.speed}, " +
            "year=${gd.gameYear}, month=${gd.gameMonth}, " +
            "sectName=${gd.sectName}")

        // 启动独立看门狗，监控游戏线程是否被 PowerGenie 等 OEM 挂起
        startWatchdog()

        gameLoopJob = engineScope.launch {
            DomainLog.i(TAG, "Starting game loop")

            // 双重保险：线程工厂已设 MAX_PRIORITY，但部分 OEM 覆盖线程优先级。
            // Process.setThreadPriority(THREAD_PRIORITY_URGENT_AUDIO) 是 Linux
            // nice 值 -19（最高实时优先级），独立于 Java Thread.priority 体系，
            // 即使 OEM 修改了 Thread.priority 映射也依然生效。
            try {
                android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_URGENT_AUDIO
                )
                DomainLog.d(TAG, "Game thread priority: URGENT_AUDIO (-19)")
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                DomainLog.w(TAG, "Cannot set thread priority: ${e.message}")
            }

            // ★ 帧驱动 Accumulator 循环
            var accumulatorNs = 0L
            var lastFrameTimeNs = System.nanoTime()
            try {
                while (isActive) {
                    // Step 1: 计算 deltaTime
                    val nowNs = System.nanoTime()
                    var deltaNs = nowNs - lastFrameTimeNs
                    lastFrameTimeNs = nowNs
                    deltaNs = deltaNs.coerceAtMost(MAX_ACCUMULATOR_NS)

                    // Step 2: 暂停/加载时不积累
                    if (stateStore.isPaused.value || stateStore.isLoading.value) {
                        gameClock.consumeDeadTime()
                        delay(50)
                        accumulatorNs = 0L
                        continue
                    }

                    // Step 3: Accumulate + FixedUpdate
                    accumulatorNs += deltaNs
                    var stepsExecuted = 0
                    while (accumulatorNs >= LOGIC_DT_NS && stepsExecuted < 5) {
                        tickInternal()
                        accumulatorNs -= LOGIC_DT_NS
                        stepsExecuted++
                    }

                    // Step 4: 插值因子
                    currentAlpha = (accumulatorNs.toFloat() / LOGIC_DT_NS.toFloat()).coerceIn(0f, 1f)

                    // Step 5: 闲置超时检测
                    checkIdleTimeout(nowNs)

                    // Step 6: 场景感知自适应等待（P1.3）
                    if (stepsExecuted == 0 && deltaNs < LOGIC_DT_NS) {
                        // 无 tick 执行 → 按场景帧预算等待
                        val budgetMs = currentScene.targetFrameTimeMs
                        val consumedMs = (System.nanoTime() - nowNs) / 1_000_000
                        val waitMs = (budgetMs - consumedMs).coerceIn(1L, budgetMs)
                        delay(waitMs)
                    } else {
                        // 有 tick 执行 → 确保不超过场景帧预算
                        val frameElapsedMs = (System.nanoTime() - nowNs) / 1_000_000
                        val budgetMs = currentScene.targetFrameTimeMs
                        if (frameElapsedMs < budgetMs) {
                            antiFreezeDelay(budgetMs - frameElapsedMs, deltaNs / 1_000_000)
                        }
                    }
                    updateRenderFrameRate()
                }
            } finally {
                gameLoopStoppedSignal.complete(Unit)
                DomainLog.i(TAG, "Game loop stopped signal sent")
            }
        }
    }
    
    fun stopGameLoop() {
        unifiedPerformanceMonitor.stop()
        stopWatchdog()
        stateStore.setPausedDirect(true)
        gameLoopJob?.cancel()
        gameLoopJob = null
        DomainLog.i(TAG, "Game loop stop requested, isPaused=true")
    }
    
    suspend fun stopGameLoopAndWait(timeoutMs: Long = 5000): Boolean {
        stopGameLoop()
        return withTimeoutOrNull(timeoutMs) {
            gameLoopStoppedSignal.await()
            true
        } ?: run {
            DomainLog.w(TAG, "Game loop did not stop within ${timeoutMs}ms")
            false
        }
    }
    
    fun shutdown() {
        stopGameLoop()
        stopWatchdog()
        deathEventJob?.cancel()
        deathEventJob = null
        systemManager.releaseAll()
        _autoSaveTrigger.close()
        engineJob.cancel()
        // 不关闭 GAME_DISPATCHER：shutdown 后可能重新 start，需保持线程池可用
        // 若必须关闭，需同时重建 GAME_DISPATCHER（静态 val 无法替换，故此处仅 cancel job）
        // WATCHDOG_DISPATCHER 同理：shutdown 后可能重新 startGameLoop → startWatchdog
        engineJob = SupervisorJob(scopeProvider.scope.coroutineContext[Job])
        engineScope = CoroutineScope(engineJob + gameDispatcher + engineExceptionHandler)
        isInitialized = false
        DomainLog.i(TAG, "GameEngineCore shutdown complete")
    }

    // ── 独立看门狗 — 监控游戏线程是否被 PowerGenie 等 OEM 机制挂起 ──

    /**
     * 启动独立看门狗协程，运行在专用非守护线程上。
     *
     * 每 3-5 秒检查一次 tickCount 是否有推进。如果在游戏循环声明为活跃
     * 的状态下 tickCount 停滞，说明游戏线程可能被 OEM 省电机制挂起，
     * 触发恢复流程。
     *
     * 看门狗线程：
     * - 非守护线程（isDaemon=false）：荣耀 MagicOS 等 OEM 会挂起守护线程池
     *   中的线程，非守护线程可防止看门狗自身被冻结。
     * - 独立单线程执行器：与 Dispatchers.Default 线程池完全隔离。
     * - 检查间隔由 [OemPowerProfileProvider.current] 数据驱动：
     *   激进 OEM（Honor/vivo）3s，中等 OEM（Xiaomi/OPPO）4s，保守 OEM 5s。
     */
    private fun startWatchdog() {
        stopWatchdog()
        // 跨重启累计恢复尝试次数（仅在 tick 推进时清零，不在重启时重置）
        // 连续失败超过阈值时进入降级模式，使用更长间隔减少频繁重启
        val baseIntervalMs = OemPowerProfileProvider.current.watchdogIntervalMs
        val degradedMode = watchdogRecoveryAttempts >= watchdogDegradedThreshold
        val effectiveBaseMs = if (degradedMode) {
            (baseIntervalMs * 4).coerceAtLeast(30_000L)  // 降级模式：至少 30s 间隔
        } else {
            baseIntervalMs
        }
        if (degradedMode && watchdogRecoveryAttempts == watchdogDegradedThreshold) {
            DomainLog.w(TAG,
                "Watchdog: entering degraded mode after $watchdogRecoveryAttempts " +
                "consecutive failures — increasing check interval to ${effectiveBaseMs / 1000}s")
        }
        watchdogJob = CoroutineScope(
            WATCHDOG_DISPATCHER + SupervisorJob() + watchdogExceptionHandler
        ).launch {
            var lastTickCount = _tickCount.value
            // 当前退避间隔：失败后翻倍递增（如 3s→6s→12s→24s→30s 上限），成功后重置为初始间隔
            var currentBackoffMs = effectiveBaseMs
            while (isActive) {
                try {
                delay(currentBackoffMs)
                val currentTickCount = _tickCount.value
                val loopActive = gameLoopJob?.isActive == true
                if (currentTickCount == lastTickCount && loopActive && !stateStore.isPaused.value) {
                    watchdogRecoveryAttempts++
                    val atMaxBackoff = currentBackoffMs >= WATCHDOG_MAX_BACKOFF_MS
                    val shouldLog = !atMaxBackoff ||
                        watchdogRecoveryAttempts % 10 == 0
                    if (shouldLog) {
                        DomainLog.w(TAG,
                            "Watchdog: no tick progress in ${currentBackoffMs / 1000}s " +
                            "(attempt $watchdogRecoveryAttempts" +
                            if (degradedMode) ", degraded" else "" +
                            ")")
                    }
                    restartGameLoopInternal()
                    currentBackoffMs = computeWatchdogBackoff(
                        currentBackoffMs, effectiveBaseMs, hasRecovered = false
                    )
                } else if (currentTickCount != lastTickCount) {
                    watchdogRecoveryAttempts = 0
                    currentBackoffMs = computeWatchdogBackoff(
                        currentBackoffMs, baseIntervalMs, hasRecovered = true
                    )
                } else if (loopActive && stateStore.isPaused.value) {
                    // 游戏暂停中 tick 不推进，不触发重启，仅刷新基准计数
                    lastTickCount = currentTickCount
                    continue
                }
                lastTickCount = currentTickCount
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    DomainLog.e(TAG, "Watchdog: loop error, continuing", e)
                    delay(1000)
                }
            }
        }
    }

    /**
     * 内部重启游戏循环（不改变 paused 等外部状态）。
     * 在看门狗检测到卡死时调用。
     *
     * ## 看门狗自重启调用链
     * 本方法从 [startWatchdog] 的看门狗协程内调用，会触发以下链式调用：
     * `restartGameLoopInternal()` → [startGameLoop]() → [startWatchdog]()
     * → [stopWatchdog]() 取消当前看门狗 Job。
     *
     * 由于 Kotlin 协程取消是协作式的（仅在挂起点生效），当前看门狗协程
     * 在本方法返回后才会在下一个 `delay()` 处响应取消，因此重启流程
     * 可以完整执行完毕，不会出现"取消导致重启半途中断"的问题。
     */
    private fun restartGameLoopInternal() {
        DomainLog.w(TAG, "Watchdog: restarting game loop")
        // 取消当前循环
        gameLoopJob?.cancel()
        gameLoopJob = null
        // 重置可能卡住的状态
        forceResetStuckStates()
        // 消耗死区时间，防止重启后时间跳变
        gameClock.consumeDeadTime()
        // 重新设置 paused 为 false（如果之前被错误地卡在 true）
        stateStore.setPausedDirect(false)
        // 保存看门狗累计失败计数（startGameLoop 会将其重置为 0，
        // 但看门狗自恢复时应保留降级模式状态）
        val savedWatchdogAttempts = watchdogRecoveryAttempts
        // 重启循环
        startGameLoop()
        // 恢复看门狗计数，使降级模式在下次看门狗启动时得以保持
        watchdogRecoveryAttempts = savedWatchdogAttempts
    }

    /**
     * 从 UI 层（主线程）调用的游戏循环恢复。
     * 包装 [restartGameLoopInternal] 以允许从 GameViewModel 访问。
     */
    fun forceRestartGameLoop() {
        DomainLog.w(TAG, "External force restart requested")
        restartGameLoopInternal()
    }

    /**
     * 重启看门狗（如果已死亡）。
     * 由主线程健康监控器调用。
     */
    fun restartWatchdog() {
        if (watchdogJob?.isActive == true) return
        DomainLog.w(TAG, "Watchdog: restarting (was active=${watchdogJob?.isActive})")
        startWatchdog()
    }

    /**
     * 创建一个全新的游戏调度器（新线程）。
     * 用于 [emergencyRestartGameLoop]，当原 GAME_DISPATCHER 的线程被
     * OEM 电源管理挂起时，用全新线程替代。
     */
    private fun recreateGameDispatcher(): CoroutineDispatcher {
        val newDispatcher = Executors.newSingleThreadExecutor(ThreadFactory {
            val thread = Thread(it, "GameEngine-Thread")
            thread.priority = Thread.MAX_PRIORITY
            thread.isDaemon = false
            thread
        }).asCoroutineDispatcher()
        gameDispatcher = newDispatcher
        DomainLog.w(TAG, "Created new game dispatcher (old thread may be suspended by OEM)")
        return newDispatcher
    }

    /**
     * 紧急重启游戏循环——创建全新调度器线程，绕过 OEM 线程挂起。
     *
     * 与 [restartGameLoopInternal] 不同，此方法从主线程调用，
     * 创建一个全新的 GAME_DISPATCHER（新线程），确保不被
     * HyperOS 等 OEM 电源管理挂起的旧线程影响。
     */
    fun emergencyRestartGameLoop() {
        if (isEmergencyRestarting) {
            DomainLog.w(TAG, "EMERGENCY restart already in progress, skipping")
            return
        }
        isEmergencyRestarting = true
        try {
            val gd = stateStore.gameDataSnapshot
            DomainLog.e(TAG, "EMERGENCY restart: year=${gd.gameYear}, " +
                "month=${gd.gameMonth}, recruitList.size=" +
                "${gd.recruitList.size}, sectName=${gd.sectName}")

            // 1. 取消旧游戏循环和看门狗
            gameLoopJob?.cancel()
            gameLoopJob = null
            stopWatchdog()

            // 2. 重置卡住的状态
            forceResetStuckStates()

            // 3. 用全新调度器重建 engineScope
            engineJob = SupervisorJob(scopeProvider.scope.coroutineContext[Job])
            engineScope = CoroutineScope(engineJob + recreateGameDispatcher() + engineExceptionHandler)

            // 4. 消耗死区时间，防止时间跳变
            gameClock.consumeDeadTime()
            stateStore.setPausedDirect(false)

            // 5. 保存看门狗累计计数并重启循环
            val savedWatchdogAttempts = watchdogRecoveryAttempts
            startGameLoop()
            watchdogRecoveryAttempts = savedWatchdogAttempts

            DomainLog.i(TAG, "EMERGENCY restart complete")
        } finally {
            isEmergencyRestarting = false
        }
    }

    private fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    val isGameLoopRunning: Boolean get() = gameLoopJob?.isActive == true

    /** 直接读取暂停状态，绕过 unifiedState 的 50ms 采样延迟 */
    val isPausedDirect: Boolean get() = stateStore.isPaused.value

    suspend fun pause() {
        stateStore.update { isPaused = true }
        cultivationService.resetHighFrequencyData()
    }
    
    suspend fun resume() {
        stateStore.update { isPaused = false }
    }

    fun pauseForBackground() {
        if (isGameLoopRunning) {
            wasRunningBeforeBackground = true
            stopGameLoop()
            DomainLog.i(TAG, "Game loop stopped for background")
        } else {
            wasRunningBeforeBackground = false
        }
        engineScope.launch {
            cultivationService.resetHighFrequencyData()
        }
        _wasPausedByBackground = true
    }

    fun resumeFromBackground() {
        if (_wasPausedByBackground && wasRunningBeforeBackground) {
            stateStore.setPausedDirect(false)
            startGameLoop()
            DomainLog.i(TAG, "Game loop resumed from background")
        }
        clearBackgroundPauseFlag()
    }

    @Volatile
    private var _wasPausedByBackground = false
    val wasPausedByBackground: Boolean get() = _wasPausedByBackground

    fun clearBackgroundPauseFlag() {
        _wasPausedByBackground = false
    }

    /**
     * UI 层调用：通知引擎用户活跃。
     * 连续无操作 30s 后自动切到 IDLE 场景降帧保电。
     */
    fun onUserInteraction() {
        onUserActivity()
    }

    private suspend fun tick() {
        val tickStartTime = System.currentTimeMillis()
        val tickStartNanos = System.nanoTime()

        tickInternal()

        val tickDurationNanos = System.nanoTime() - tickStartNanos

        val tickTime = (System.currentTimeMillis() - tickStartTime).toFloat()
        unifiedPerformanceMonitor.recordTick(tickTime)

        val entityCount = stateStore.disciplesSnapshot.size
        unifiedPerformanceMonitor.recordEntityCount(entityCount)

        if (tickTime > TICK_TIME_BUDGET_MS) {
            DomainLog.w(TAG, "Tick over budget: ${tickTime}ms (budget=${TICK_TIME_BUDGET_MS}ms)")
        }

        if (tickTime > TICK_WARNING_THRESHOLD_MS) {
            DomainLog.w(TAG, "Slow tick detected: ${tickTime}ms")
        }
    }
    
    private suspend fun tickInternal() {
        if (skipTickIfNeeded()) return
        _tickCount.value++
        val tickStartDiagnostic = if (OemPowerProfileProvider.currentManufacturer == OemManufacturer.XIAOMI)
            System.currentTimeMillis() else 0L
        val tickResult = gameClock.tick(isSettlementPending = false)
        thermalController.checkAndAdjust(_fps.value)
        val (monthChanged, yearChanged) = processTickPhases(tickResult.phasesToAdvance)
        processMonthYearChange(monthChanged, yearChanged)
        val patrolResults = explorationService.consumePendingPatrolResults()
        for (result in patrolResults) {
            stateStore.setPendingBattleResult(result)
        }
        if (tickStartDiagnostic > 0) {
            val tickDuration = System.currentTimeMillis() - tickStartDiagnostic
            if (tickDuration > TICK_TIME_BUDGET_MS) {
                DomainLog.w(TAG,
                    "tick over budget: ${tickDuration}ms " +
                    "(budget=${TICK_TIME_BUDGET_MS}ms, " +
                    "month=${stateStore.gameData.value.gameMonth}, " +
                    "year=${stateStore.gameData.value.gameYear})")
            }
        }
    }
    
    private suspend fun skipTickIfNeeded(): Boolean {
        val isPaused = stateStore.isPaused.value
        val isLoading = stateStore.isLoading.value
        val isSaving = stateStore.isSaving.value
        if (!isPaused && !isLoading && !isSaving) return false
        checkAndResetStuckStates(isSaving, isLoading)
        gameClock.consumeDeadTime()
        if (_tickCount.value % 100 == 0L) {
            DomainLog.d(TAG, "tickInternal: tick #${_tickCount.value} skipped " +
                "(isPaused=$isPaused, isLoading=$isLoading, isSaving=$isSaving)")
        }
        return true
    }
    
    private suspend fun processTickPhases(phasesToAdvance: Int): Pair<Boolean, Boolean> {
        var monthChanged = false
        var yearChanged = false
        for (phaseIndex in 1..phasesToAdvance) {
            stateStore.update {
                // ★ 必须在 onPhaseTick 前捕获 prevMonth/prevYear，
                // 否则 onPhaseTick 已修改 gameMonth/gameYear，后续比较永远相等。
                val prevMonth = this.gameData.gameMonth
                val prevYear = this.gameData.gameYear
                systemManager.getSystem(TimeSystem::class)
                    .onPhaseTick(this, phasesToSettle = 1)
                checkBreakthroughsAndPills(this)
                if (this.gameData.gameMonth != prevMonth) monthChanged = true
                if (this.gameData.gameYear != prevYear) yearChanged = true
                val snapshot = this.gameData
                val interval = snapshot.autoSaveIntervalMonths
                if (interval > 0 &&
                    snapshot.gamePhase == GamePhase.EARLY.value &&
                    snapshot.gameMonth % interval == 0
                ) {
                    _autoSaveTrigger.trySend(Unit)
                }
            }
        }
        return Pair(monthChanged, yearChanged)
    }
    
    private suspend fun processMonthYearChange(monthChanged: Boolean, yearChanged: Boolean) {
        if (yearChanged) {
            cultivationService.processYearlyEvents()
            if (stateStore.gameData.value.gameMonth == 1) {
                cultivationService.processAnnualSalary(
                    stateStore.gameData.value.gameYear
                )
            }
        }
        if (monthChanged) {
            var policyResult: PolicyCostResult = PolicyCostResult.AllPaid
            stateStore.update {
                policyResult = cultivationService.processPolicyCosts(this)
            }
            if (policyResult is PolicyCostResult.SomeDisabled) {
                cultivationService.checkpointAllProduction()
                DomainLog.w(TAG, "tickInternal: policies auto-disabled due to insufficient " +
                    "spirit stones, checkpointAllProduction triggered")
            }
            cultivationService.processMonthlyEvents()
            stateStore.update { systemManager.onMonthlyEvent(this) }
            missionCheck?.invoke()
        }
    }

    /**
     * Level 1: 每旬最小检查 — 对标 RimWorld Rare Tick 模式。
     *
     * 每旬执行的操作：
     * 1) HP/MP 恢复
     * 2) 自动装备/学习
     * 3) 修炼经验累积（确保月中速率变化自动生效）
     * 4) 自动丹药到期补服
     * 5) 突破检测
     */
    private fun checkBreakthroughsAndPills(state: MutableGameState) {
        cultivationService.recoverHpMpForAllDisciples(state, phasesToSettle = 1)
        cultivationService.processAutoFromWarehouseRealtime(state)

        // 修炼累积：按当前速率累加1旬（不更新检查点）
        for (id in state.discipleTables.ids) {
            if (state.discipleTables.isAlive[id] != 1) continue
            cultivationService.accumulateCultivationPerPhase(id, state)
        }

        cultivationService.processAutoPillsRealtime(state)
        cultivationService.processBreakthroughs(state)
    }

    /**
     * 看门狗：检测 isSaving/isLoading 是否卡住超时，如果超时则强制重置。
     * 在 tickInternal() 每次跳过 tick 时调用。
     */
    private fun checkAndResetStuckStates(isSaving: Boolean, isLoading: Boolean) {
        val now = System.currentTimeMillis()

        // 跟踪 isSaving 变为 true 的时间
        if (isSaving) {
            if (savingStartTime == 0L) {
                savingStartTime = now
            } else if (now - savingStartTime > STUCK_STATE_TIMEOUT_MS) {
                DomainLog.e(TAG, "isSaving has been true for ${now - savingStartTime}ms, force resetting")
                forceResetStuckStates()
            }
        } else {
            savingStartTime = 0L
        }

        // 跟踪 isLoading 变为 true 的时间
        if (isLoading) {
            if (loadingStartTime == 0L) {
                loadingStartTime = now
            } else if (now - loadingStartTime > STUCK_STATE_TIMEOUT_MS) {
                DomainLog.e(TAG, "isLoading has been true for ${now - loadingStartTime}ms, force resetting")
                forceResetStuckStates()
            }
        } else {
            loadingStartTime = 0L
        }
    }

    /**
     * 注册当前正在运行的保存协程 Job，供看门狗强制取消。
     * 在 finally 块中应调用 [clearActiveSaveJob] 清除引用。
     */
    fun registerActiveSaveJob(job: Job) {
        activeSaveJob?.cancel()
        activeSaveJob = job
    }

    /** 清除保存协程 Job 引用（协程正常结束时调用） */
    fun clearActiveSaveJob() {
        activeSaveJob = null
    }

    /**
     * 注册当前正在运行的加载协程 Job，供看门狗强制取消。
     * 在 finally 块中应调用 [clearActiveLoadJob] 清除引用。
     */
    fun registerActiveLoadJob(job: Job) {
        activeLoadJob?.cancel()
        activeLoadJob = job
    }

    /** 清除加载协程 Job 引用（协程正常结束时调用） */
    fun clearActiveLoadJob() {
        activeLoadJob = null
    }

    /**
     * 强制重置 isSaving 和 isLoading 为 false。
     * 用于看门狗检测到状态卡住时调用，也可从外部调用作为紧急恢复手段。
     * 同时取消正在运行的 save/load 协程，防止并发写入。
     */
    fun forceResetStuckStates() {
        DomainLog.w(TAG, "Force resetting stuck states: isSaving and isLoading -> false, cancelling active jobs")
        activeSaveJob?.cancel()
        activeSaveJob = null
        activeLoadJob?.cancel()
        activeLoadJob = null
        stateStore.setSavingDirect(false)
        stateStore.setLoadingDirect(false)
        savingStartTime = 0L
        loadingStartTime = 0L
    }
    
    private fun updateFps(frameTime: Float) {
        frameCount++
        fpsAccumulator += frameTime

        if (frameCount >= 10) {
            _fps.value = 1000f / (fpsAccumulator / frameCount)
            frameCount = 0
            fpsAccumulator = 0f
        }
    }

    /**
     * 防挂起延迟：将等待时间拆分为微延迟 + 忙等循环。
     *
     * 华为 EMUI/HarmonyOS 的 PowerGenie（省电精灵）、荣耀 MagicOS、
     * vivo/iQOO OriginOS、小米 MIUI 神隐模式、OPPO ColorOS 等 OEM
     * 省电机制会检测线程"空闲"状态并将游戏线程挂起。
     *
     * 将 delay 拆分为 2ms 微间隔（远低于所有 OEM 的空闲检测窗口），
     * 并按 [OemPowerProfile] 配置周期性执行忙等循环，以 [SystemClock.elapsedRealtime]
     * 轮询保持线程 RUNNABLE，打破 OEM 空闲检测。
     *
     * API 33+：忙等循环内额外调用 [Thread.onSpinWait] 作为 CPU 优化提示。
     *
     * ## 参数来源
     * busyInterval / busyDuration 由 [OemPowerProfileProvider.current] 提供，
     * 数据驱动各厂商差异化配置：
     * - vivo/iQOO OriginOS 5：busyInterval=12, busyDuration=4ms（占空比 16.7%）
     * - Honor MagicOS / OPPO ColorOS：busyInterval=16, busyDuration=4ms（占空比 12.5%）
     * - 中等 OEM（Xiaomi MIUI）：busyInterval=32, busyDuration=3ms
     * - 保守 OEM（Samsung / 原生）：busyInterval=64, busyDuration=2ms
     *
     * 成本：保守 OEM 约 6-7% CPU；vivo 约 14% 单核 CPU（游戏线程），
     * 远优于游戏线程被 OEM 挂起导致时间完全冻结。
     *
     * 参考：
     * - dontkillmyapp.com — 各厂商电源管理机制分析
     * - Kotlin Slack #coroutines: delay() 精度 >30ms 抖动
     *   (https://slack-chats.kotlinlang.org/t/26866719)
     */
    /**
     * 自适应忙等延迟。
     *
     * 正常运行时不执行忙等（纯 delay），仅在检测到 tick 间隔异常（可能被 OEM 挂起）时
     * 自动启用分片忙等。恢复正常后自动禁用。
     *
     * @param totalMs 需要等待的总时长（ms）
     * @param actualElapsedMs 从上次 tick 到现在的实际墙钟间隔（ms），用于检测 OEM 挂起
     */
    private suspend fun antiFreezeDelay(totalMs: Long, actualElapsedMs: Long = 0L) {
        // ★ 自适应忙等检测
        if (actualElapsedMs > TICK_INTERVAL_MS * 2 && totalMs > 0) {
            antiFreezeTriggerCount++
            consecutiveNormalTicks = 0
            if (antiFreezeTriggerCount >= ANTI_FREEZE_TRIGGER_THRESHOLD && !antiFreezeEnabled) {
                antiFreezeEnabled = true
                DomainLog.w(TAG, "Anti-freeze enabled: ${antiFreezeTriggerCount} trigger events")
            }
        } else {
            consecutiveNormalTicks++
            antiFreezeTriggerCount = maxOf(0, antiFreezeTriggerCount - 1)
            if (antiFreezeEnabled && consecutiveNormalTicks >= ANTI_FREEZE_NORMAL_THRESHOLD) {
                antiFreezeEnabled = false
                DomainLog.i(TAG, "Anti-freeze disabled: ${consecutiveNormalTicks} normal ticks")
            }
        }

        if (antiFreezeEnabled) {
            doBusyWait(totalMs)
        } else {
            delay(totalMs.coerceAtLeast(1L))
        }
    }

    /** 分片忙等 — 仅在 antiFreezeEnabled 时执行 */
    private suspend fun doBusyWait(totalMs: Long) {
        val profile = OemPowerProfileProvider.current
        val microInterval = 2L
        val busyInterval = profile.antiFreezeBusyInterval
        val busyDuration = profile.antiFreezeBusyDuration
        var remaining = totalMs
        var cycleCount = 0L
        while (remaining > 0 && currentCoroutineContext().isActive) {
            val step = minOf(microInterval, remaining)
            delay(step)
            remaining -= step
            cycleCount++
            if (remaining > 0 && cycleCount % busyInterval == 0L) {
                val busyEnd = android.os.SystemClock.elapsedRealtime() + busyDuration
                while (android.os.SystemClock.elapsedRealtime() < busyEnd) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        Thread.onSpinWait()
                    } else {
                        @Suppress("UNUSED_EXPRESSION")
                        _tickCount.value
                    }
                }
            }
        }
    }

    fun createSnapshot(): GameStateSnapshot {
        val currentData = stateStore.gameData.value
        return GameStateSnapshot(
            gameData = currentData,
            disciples = stateStore.disciples.value,
            equipmentStacks = stateStore.equipmentStacks.value,
            equipmentInstances = stateStore.equipmentInstances.value,
            manualStacks = stateStore.manualStacks.value,
            manualInstances = stateStore.manualInstances.value,
            pills = stateStore.pills.value,
            materials = stateStore.materials.value,
            herbs = stateStore.herbs.value,
            seeds = stateStore.seeds.value,
            teams = stateStore.teams.value,
            battleLogs = stateStore.battleLogs.value,
            alliances = currentData.alliances
        )
    }
    
    suspend fun loadSnapshot(snapshot: GameStateSnapshot) {
        stateStore.loadFromSnapshot(
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
            storageBags = snapshot.storageBags,
            teams = snapshot.teams,
            battleLogs = snapshot.battleLogs
        )
    }
    
    fun startListening() {
        if (deathEventJob?.isActive == true) return
        deathEventJob = engineScope.launch {
            eventBus.events.collect { event ->
                if (event is DeathEvent) {
                    // 弟子陨落事件（消息系统已移除）
                }
            }
        }
    }
}

