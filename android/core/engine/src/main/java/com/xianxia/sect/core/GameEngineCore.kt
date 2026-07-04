package com.xianxia.sect.core.engine

import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.engine.BuildConfig
import android.os.Build
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.domain.exploration.ExplorationService
import com.xianxia.sect.core.engine.domain.settlement.SettlementCoordinator
import com.xianxia.sect.core.engine.system.SystemManager
import com.xianxia.sect.core.engine.system.TimeSystem
import com.xianxia.sect.core.engine.system.FocusDomain
import com.xianxia.sect.core.engine.system.InterfaceDomainMap
import com.xianxia.sect.core.engine.system.CultivationTickSystem
import com.xianxia.sect.core.engine.system.GameTimeClock
import com.xianxia.sect.core.concurrent.DeviceCapabilityProfiler
import com.xianxia.sect.core.concurrent.ParallelExecutionContext
import com.xianxia.sect.core.concurrent.ThermalController
import com.xianxia.sect.core.concurrent.BackgroundJobScheduler
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
    private val settlementCoordinator: SettlementCoordinator,
    private val scopeProvider: CoroutineScopeProvider,
    private val cultivationService: CultivationService,
    private val explorationService: ExplorationService,
    private val gameClock: GameTimeClock,
    private val profiler: DeviceCapabilityProfiler,
    private val thermalController: ThermalController,
    private val backgroundJobScheduler: BackgroundJobScheduler
) {

    /**
     * 任务完成检测回调，由 GameEngine 在构造后注入。
     * 每月结算时被调用，确保空闲期间任务完成也能被及时检测。
     */
    @Volatile
    internal var missionCheck: (suspend () -> Unit)? = null
    
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

    // 焦点分频：各域上次执行时间戳
    private val domainLastTickTime = java.util.concurrent.ConcurrentHashMap<FocusDomain, Long>()

    // 自适应降频因子
    @Volatile
    private var adaptiveSlowdownFactor = 1.0

    // ★ 插值因子（供 UI 平滑渲染，由 frame-driven 循环维护）
    @Volatile
    var currentAlpha: Float = 0f
        private set

    // ── 动态批量间隔 ──
    /** 当前批量域执行间隔（ms），Tab 切换后加速，稳定后恢复 */
    @Volatile
    private var batchIntervalMs: Long = 10_000L  // 默认 10s（原 30s）
    private var lastTabSwitchTime: Long = 0L
    private var lastBatchIntervalAdjustTime: Long = 0L
    private fun updateBatchInterval() {
        val now = System.currentTimeMillis()
        if (lastTabSwitchTime > 0 && now - lastTabSwitchTime < 3_000L) {
            batchIntervalMs = 5_000L
        } else if (batchIntervalMs > 10_000L) {
            batchIntervalMs = maxOf(10_000L, batchIntervalMs - 1_000L)
        }
    }
    /** UI 层调用：通知引擎 Tab 切换，加速批量轨 */
    fun onTabChanged(tabName: String) {
        lastTabSwitchTime = System.currentTimeMillis()
        batchIntervalMs = 5_000L
        stateStore.activeTab = tabName
    }

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

    /** forceCompleteSettlement 开始时间戳，用于检测死锁超时 */
    @Volatile
    private var forceCompleteStartTime: Long = 0L

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
        DomainLog.i(TAG, profiler.summary)
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
        DomainLog.i(TAG, "startGameLoop: isGameStarted=${gd.isGameStarted}, " +
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

                    // Step 5: 自适应等待
                    if (stepsExecuted == 0 && deltaNs < LOGIC_DT_NS) {
                        val waitMs = ((LOGIC_DT_NS - accumulatorNs) / 1_000_000).coerceIn(1L, 100L)
                        delay(waitMs)
                    } else {
                        val frameElapsedMs = (System.nanoTime() - nowNs) / 1_000_000
                        if (frameElapsedMs < TICK_INTERVAL_MS) {
                            antiFreezeDelay(TICK_INTERVAL_MS - frameElapsedMs, deltaNs / 1_000_000)
                        }
                    }
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
        backgroundJobScheduler.shutdown()
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
        // 清除残留结算状态，防止重启后 hasPendingWork 阻塞
        settlementCoordinator.cancelPendingWork()
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
            DomainLog.w(TAG, "EMERGENCY restart triggered from main thread")

            // 1. 取消旧游戏循环和看门狗
            gameLoopJob?.cancel()
            gameLoopJob = null
            stopWatchdog()

            // 2. 清除残留结算状态
            settlementCoordinator.cancelPendingWork()
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
        settlementCoordinator.cancelPendingWork()
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

    /** UI 层调用：重置批量结算时钟，推迟下一次 30s 全量结算 */
    fun onUserInteraction() {
        settlementCoordinator.resetBatchClock()
    }

    /**
     * 当玩家打开某个界面时调用，触发该域立即进入实时轨。
     */
    fun catchUpDomain(domain: FocusDomain) {
        domainLastTickTime.remove(domain)
    }

    /**
     * 从当前 Tab/Dialog/焦点弟子计算域集合。
     *
     * 委托给 [resolveDomainsFromView] 纯函数，便于单元测试。
     */
    private fun computeDomainsFromView(): Set<FocusDomain> =
        resolveDomainsFromView(
            stateStore.activeTab, stateStore.activeDialog,
            stateStore.activeSubDialogs
        )

    /** 获取当前活跃的关注域集合（基于 activeTab + dialog + 弟子焦点 + 实时轨） */
    private fun getActiveDomains(): Set<FocusDomain> {
        val viewDomains = computeDomainsFromView()
        val batchDomains = settlementCoordinator.batchRealtimeDomains
        val allDomains = viewDomains.toMutableSet()
        // 批量轨 ≥80% 槽位新增的域需清除追踪时间戳，
        // 确保 phasesSkippedForDomain 返回 1（而非旧时间戳算出的 N）。
        // viewDomains 中的域已由 setActiveTab/setActiveDialog 调用
        // catchUpDomain 清除，此处仅处理批量轨特有域。
        for (domain in batchDomains) {
            if (domain !in viewDomains && domain != FocusDomain.ALWAYS) {
                domainLastTickTime.remove(domain)
            }
        }
        allDomains.addAll(batchDomains)
        return allDomains
    }

    /** 判断某个域在本 tick 是否应执行（使用动态间隔） */
    private fun shouldExecuteDomain(domain: FocusDomain, activeDomains: Set<FocusDomain>): Boolean {
        if (domain == FocusDomain.ALWAYS) return true
        if (domain in activeDomains) return true

        val lastTime = domainLastTickTime[domain] ?: 0L
        return (System.currentTimeMillis() - lastTime) >= batchIntervalMs
    }

    /** 计算某域自上次执行后跳过了多少游戏旬 */
    private fun phasesSkippedForDomain(domain: FocusDomain): Int {
        if (domain == FocusDomain.ALWAYS) return 1
        val lastTime = domainLastTickTime[domain] ?: return 1
        if (lastTime == 0L) return 1
        val elapsedMs = System.currentTimeMillis() - lastTime
        val gameSpeed = stateStore.gameDataSnapshot.gameSpeed.coerceIn(1, 2)
        val msPerPhase = (2000L / gameSpeed).coerceAtLeast(500L)
        return (elapsedMs / msPerPhase).toInt().coerceAtLeast(1)
    }

    /** 记录域执行时间 */
    private fun markDomainExecuted(domain: FocusDomain) {
        if (domain != FocusDomain.ALWAYS) {
            domainLastTickTime[domain] = System.currentTimeMillis()
        }
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
            adaptiveSlowdownFactor = (adaptiveSlowdownFactor * 1.5).coerceAtMost(5.0)
        } else if (tickTime < TICK_TIME_BUDGET_MS / 2 && adaptiveSlowdownFactor > 1.0) {
            adaptiveSlowdownFactor = (adaptiveSlowdownFactor * 0.9).coerceAtLeast(1.0)
        }

        if (tickTime > TICK_WARNING_THRESHOLD_MS) {
            DomainLog.w(TAG, "Slow tick detected: ${tickTime}ms")
        }
    }
    
    private suspend fun tickInternal() {
        val isPaused = stateStore.isPaused.value
        val isLoading = stateStore.isLoading.value
        val isSaving = stateStore.isSaving.value
        if (isPaused || isLoading || isSaving) {
            checkAndResetStuckStates(isSaving, isLoading)
            gameClock.consumeDeadTime()  // 更新lastWallMs，防止恢复后时间跳变
            // Periodic diagnostic: log stuck state every 100th skipped tick
            if (_tickCount.value % 100 == 0L) {
                DomainLog.d(TAG, "tickInternal: tick #${_tickCount.value} skipped " +
                    "(isPaused=$isPaused, isLoading=$isLoading, isSaving=$isSaving)")
            }
            return
        }

        _tickCount.value++

        // ★ 动态调整批量间隔（Tab 切换后加速）
        updateBatchInterval()

        // 诊断：仅 Xiaomi 设备记录 tick 起始时间，用于检测 OEM 电源管理冻结
        val tickStartDiagnostic = if (OemPowerProfileProvider.currentManufacturer == OemManufacturer.XIAOMI)
            System.currentTimeMillis() else 0L

        // ── 基于 GameTimeClock 的固定步长旬推进 ──
        val tickResult = gameClock.tick(
            isSettlementPending = settlementCoordinator.hasPendingWork
        )

        var monthChanged = false
        var yearChanged = false

        // ★ 热控检查（每 tick 后执行 thermal check 间隔有 10s 冷却）
        thermalController.checkAndAdjust(_fps.value)

        for (phaseIndex in 1..tickResult.phasesToAdvance) {
            val currentPhase = stateStore.gameData.value.gamePhase
            val activeDomainsPerPhase = getActiveDomains()  // 每旬重新计算焦域

            // ★ Phase 1: 并行 pre-compute（在 stateStore.update 之前执行）
            //   创建只读快照，分发到 Dispatchers.Default 并行计算
            val parallelResults = if (profiler.enableParallelTick) {
                val ctx = ParallelExecutionContext.snapshot(stateStore, activeDomainsPerPhase)
                systemManager.executeParallelCompute(
                    ctx, activeDomainsPerPhase, ::phasesSkippedForDomain
                )
            } else {
                emptyList()
            }

            when {
                // 下旬 + 结算未完成 → 丢弃推进，等待结算
                currentPhase == GamePhase.LATE.value && tickResult.isSettlementPending -> {
                    gameClock.forceConsumeOnePhase()
                    break  // 后续旬也丢弃
                }

                // 正常旬推进
                else -> {
                    stateStore.update {
                        // ★ Phase 2: 应用并行计算结果（在系统执行之前写入）
                        for (result in parallelResults) {
                            result.apply(this)
                        }

                        val prevMonth = this.gameData.gameMonth
                        val prevYear = this.gameData.gameYear

                        // 执行当前旬的 tick（两档制 + 分旬调度）
                        // 注意：parallelResults 已为 supportsParallelTick 系统计算并写入了
                        // batchSettle 等重型操作。这些系统的 onPhaseTick 仍会被调用以执行
                        // 轻量操作（如突破检测、自动装备），它们通过内部标志跳过已完成的计算。
                        if (settlementCoordinator.hasPendingWork) {
                            systemManager.getSystem(TimeSystem::class)
                                .onPhaseTick(this, phasesToSettle = 1)
                            systemManager.getSystem(CultivationTickSystem::class)
                                .onPhaseTick(this, phasesToSettle = 1)
                        } else {
                            val phase1Based = this.gameData.gamePhase + 1
                            systemManager.onPhaseTickWithDomainFilter(
                                this, activeDomainsPerPhase, ::shouldExecuteDomain, ::markDomainExecuted,
                                currentPhase = phase1Based,
                                getPhasesToSettle = ::phasesSkippedForDomain
                            )
                        }

                        if (this.gameData.gameMonth != prevMonth) monthChanged = true
                        if (this.gameData.gameYear != prevYear) yearChanged = true

                        // 灵矿产出 phase 快照（月度结算用）
                        cultivationService.recordPhaseSnapshot(this)

                        // 自动存档检测（上旬触发，避免重复）
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
            }
        }

        // ── 月变/年变后处理（统一路径）──
        if (yearChanged) {
            cultivationService.processYearlyEvents()
        }
        if (monthChanged) {
            cultivationService.processMonthlyEvents()
            missionCheck?.invoke()
        }

        // 先完成待处理的结算（如有）— 每 tick 检查
        if (settlementCoordinator.hasPendingWork) {
            forceCompleteSettlement()
        }

        // 批量轨指纹检测（从主状态计算，无影子，使用动态间隔）
        settlementCoordinator.accumulateBatch(batchIntervalMs)

        // 年度结算
        if (yearChanged) {
            val shadow = stateStore.createSettlementShadow()
            settlementCoordinator.scheduleYearly(shadow)
            if (settlementCoordinator.hasPendingWork) {
                val completed = settlementCoordinator.executeStep()
                if (completed) settlementCoordinator.onSettlementComplete()
            }

            // 年俸发放（每年 1 月）
            if (stateStore.gameData.value.gameMonth == 1) {
                cultivationService.processAnnualSalary(
                    stateStore.gameData.value.gameYear
                )
            }
        }

        // 巡逻结果（空闲/活跃共同）
        val patrolResults = explorationService.consumePendingPatrolResults()
        for (result in patrolResults) {
            stateStore.setPendingBattleResult(result)
        }

        // 诊断日志：Xiaomi 设备记录超预算 tick，辅助定位 OEM 电源管理导致的冻结
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

    private var isForceCompleting = false

    private suspend fun forceCompleteSettlement() {
        if (isForceCompleting) {
            // 超时检测：如果 isForceCompleting 卡住超过 5 秒，强制重置
            if (forceCompleteStartTime > 0L &&
                System.currentTimeMillis() - forceCompleteStartTime > 5000L
            ) {
                DomainLog.e(TAG,
                    "forceCompleteSettlement: stale lock for " +
                    "${System.currentTimeMillis() - forceCompleteStartTime}ms, force resetting")
                isForceCompleting = false
                forceCompleteStartTime = 0L
            } else {
                return
            }
        }
        isForceCompleting = true
        forceCompleteStartTime = System.currentTimeMillis()
        try {
            while (settlementCoordinator.hasPendingWork) {
                settlementCoordinator.executeStep()
            }
            settlementCoordinator.onSettlementComplete()
        } finally {
            isForceCompleting = false
            forceCompleteStartTime = 0L
        }
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

/**
 * 焦点域判定纯函数 — 从 [InterfaceDomainMap] 读取界面→域映射。
 *
 * @param tab 当前 Tab（null 视为无）
 * @param dialog 当前 Dialog（null 视为无）
 * @return 应激活的 FocusDomain 集合（始终包含 ALWAYS）
 */
internal fun resolveDomainsFromView(
    tab: String?,
    dialog: String?,
    subDialogs: Set<String> = emptySet()
): Set<FocusDomain> {
    val domains = mutableSetOf(FocusDomain.ALWAYS)
    tab?.let { InterfaceDomainMap[it]?.let { d -> domains.add(d) } }
    dialog?.let { InterfaceDomainMap[it]?.let { d -> domains.add(d) } }
    for (sd in subDialogs) {
        InterfaceDomainMap[sd]?.let { d -> domains.add(d) }
    }
    return domains
}
