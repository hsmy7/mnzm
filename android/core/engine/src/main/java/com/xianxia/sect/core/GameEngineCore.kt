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
import com.xianxia.sect.core.engine.system.GameTimeClock
import com.xianxia.sect.core.event.*
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.*
import com.xianxia.sect.core.perf.ThermalMonitor
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
    private val thermalMonitor: ThermalMonitor,
    private val lazyEvaluationDispatcher: LazyEvaluationDispatcher,
    private val gameClock: GameTimeClock
) {
    
    companion object {
        private const val TAG = "GameEngineCore"
        private const val TICK_INTERVAL_MS = 100L
        private const val MIN_TICK_DELAY_MS = 16L
        private const val TICK_WARNING_THRESHOLD_MS = 100f
        private const val STUCK_STATE_TIMEOUT_MS = 10_000L  // 从30s降至10s，更快恢复
        private const val ADAPTIVE_MAX_INTERVAL_MS = 1000L
        private const val IDLE_TICK_INTERVAL_MS = 2000L
        private const val IDLE_DETECTION_MS = 30_000L
        private const val NON_FOCUS_TICK_INTERVAL = 30_000L
        private const val TICK_TIME_BUDGET_MS = 50L

        private val gameThreadFactory = ThreadFactory {
            val thread = Thread(it, "GameEngine-Thread")
            thread.priority = Thread.NORM_PRIORITY - 1  // 略低于正常优先级，避免抢占 UI
            thread.isDaemon = true
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
    }

    private var currentTickInterval = TICK_INTERVAL_MS
    private var consecutiveOverruns = 0

    // 焦点分频：各域上次执行时间戳
    private val domainLastTickTime = java.util.concurrent.ConcurrentHashMap<FocusDomain, Long>()

    // 上次用户交互时间戳
    @Volatile
    private var lastUserInteractionTime = System.currentTimeMillis()

    // 自适应降频因子
    @Volatile
    private var adaptiveSlowdownFactor = 1.0

    // 空闲状态焦点域切换
    @Volatile
    private var previousFocusDomain: FocusDomain? = null

    @Volatile
    private var isInIdleState = false

    // 后台停止时保存状态
    @Volatile
    private var wasRunningBeforeBackground = false
    
    private val engineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        if (throwable !is CancellationException) {
            DomainLog.e(TAG, "Unhandled exception in engine coroutine", throwable)
        }
    }
    private var engineJob = SupervisorJob(scopeProvider.scope.coroutineContext[Job])
    private var engineScope: CoroutineScope = CoroutineScope(engineJob + GAME_DISPATCHER + engineExceptionHandler)

    fun launchInScope(block: suspend CoroutineScope.() -> Unit): Job = engineScope.launch(block = block)

    fun scopeForStateIn(): CoroutineScope = engineScope
    private var gameLoopJob: Job? = null
    private var gameLoopStoppedSignal = CompletableDeferred<Unit>()
    private var deathEventJob: Job? = null
    
    private val _tickCount = MutableStateFlow(0L)
    val tickCount: StateFlow<Long> = _tickCount.asStateFlow()
    
    private val _fps = MutableStateFlow(0f)
    val fps: StateFlow<Float> = _fps.asStateFlow()
    
    private var lastTickTime = System.currentTimeMillis()
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

    /** 看门狗恢复尝试次数 */
    private var watchdogRecoveryAttempts = 0

    /** 看门狗最大恢复尝试次数 */
    private val maxWatchdogRecoveries = 5

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
    }
    
    fun startGameLoop() {
        if (gameLoopJob?.isActive == true) {
            DomainLog.w(TAG, "Game loop already running")
            return
        }

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

            // ADPF Hint Session 必须在游戏线程内创建，确保 myTid() 返回
            // 游戏引擎线程的 TID（而非调用 startGameLoop 的线程）
            thermalMonitor.createHintSession(100_000_000L)  // 100ms target

            // 提升游戏线程优先级以对抗 OEM 省电策略
            // （华为 PowerGenie、小米神隐模式等会对低优先级线程进行 CPU 挂起）
            // Android 12+ 需要 RAISED_THREAD_PRIORITY 权限（signature 级别），
            // 普通应用无法获得；改用 Process.setThreadPriority 作为替代
            val originalPriority = Thread.currentThread().priority
            try {
                Thread.currentThread().priority = Thread.NORM_PRIORITY + 1
                DomainLog.d(TAG, "Game thread priority: $originalPriority → ${Thread.NORM_PRIORITY + 1}")
            } catch (e: SecurityException) {
                // Android 12+ 会静默失败，改用 Process API（不需要特殊权限）
                try {
                    android.os.Process.setThreadPriority(
                        android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY
                    )
                    DomainLog.d(TAG, "Game thread priority set via Process API: URGENT_DISPLAY")
                } catch (e: CancellationException) { throw e }
                  catch (e2: Exception) {
                    DomainLog.w(TAG, "Cannot raise thread priority: ${e2.message}")
                }
            }

            try {
                while (isActive) {
                    try {
                val startTime = System.currentTimeMillis()

                tick()

                val currentTime = System.currentTimeMillis()
                val actualFrameInterval = (currentTime - lastFrameTime).toFloat()
                lastFrameTime = currentTime

                val elapsed = currentTime - startTime
                val isIdle = (System.currentTimeMillis() - lastUserInteractionTime) > IDLE_DETECTION_MS
                val effectiveInterval = when {
                    thermalMonitor.shouldEmergencySave() -> 500L
                    thermalMonitor.shouldReduceWorkload() -> 200L
                    thermalMonitor.isLightThrottle() -> 150L
                    adaptiveSlowdownFactor > 1.0 -> (TICK_INTERVAL_MS * adaptiveSlowdownFactor).toLong().coerceAtMost(ADAPTIVE_MAX_INTERVAL_MS)
                    else -> TICK_INTERVAL_MS
                }
                currentTickInterval = effectiveInterval
                val delayMs = (effectiveInterval - elapsed).coerceAtLeast(MIN_TICK_DELAY_MS)

                updateFps(actualFrameInterval)

                        // 微延迟 + spin-wait 替代单次长 delay：
                        // 华为等 OEM 的省电策略会检测线程"空闲"状态并挂起。
                        // 4ms 微间隔 + onSpinWait 保持 CPU 活跃，防止被标记为空闲。
                        antiFreezeDelay(delayMs)
                    } catch (e: CancellationException) {
                        DomainLog.i(TAG, "Game loop cancelled")
                        throw e
                    } catch (e: Exception) {
                        DomainLog.e(TAG, "Error in game loop", e)
                        delay(1000)
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
        thermalMonitor.closeHintSession()
        deathEventJob?.cancel()
        deathEventJob = null
        systemManager.releaseAll()
        _autoSaveTrigger.close()
        engineJob.cancel()
        // 不关闭 GAME_DISPATCHER：shutdown 后可能重新 start，需保持线程池可用
        // 若必须关闭，需同时重建 GAME_DISPATCHER（静态 val 无法替换，故此处仅 cancel job）
        // WATCHDOG_DISPATCHER 同理：shutdown 后可能重新 startGameLoop → startWatchdog
        engineJob = SupervisorJob(scopeProvider.scope.coroutineContext[Job])
        engineScope = CoroutineScope(engineJob + GAME_DISPATCHER + engineExceptionHandler)
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
        watchdogRecoveryAttempts = 0
        // 看门狗间隔由 OemPowerProfile 数据驱动：激进 OEM（Honor/vivo）3s，保守 OEM 5s
        val intervalMs = OemPowerProfileProvider.current.watchdogIntervalMs
        watchdogJob = CoroutineScope(WATCHDOG_DISPATCHER + SupervisorJob()).launch {
            var lastTickCount = _tickCount.value
            while (isActive) {
                delay(intervalMs)
                val currentTickCount = _tickCount.value
                val loopActive = gameLoopJob?.isActive == true
                if (currentTickCount == lastTickCount && loopActive) {
                    watchdogRecoveryAttempts++
                    DomainLog.w(TAG,
                        "Watchdog: no tick progress in ${intervalMs / 1000}s " +
                        "(attempt $watchdogRecoveryAttempts/$maxWatchdogRecoveries)")
                    if (watchdogRecoveryAttempts <= maxWatchdogRecoveries) {
                        // 尝试恢复：重启游戏循环
                        restartGameLoopInternal()
                    } else {
                        DomainLog.e(TAG,
                            "Watchdog: max recovery attempts ($maxWatchdogRecoveries) reached. " +
                            "Giving up. Game thread may be permanently suspended by OEM.")
                    }
                } else if (currentTickCount != lastTickCount) {
                    // tick 有推进，重置计数器
                    watchdogRecoveryAttempts = 0
                }
                lastTickCount = currentTickCount
            }
        }
    }

    /**
     * 内部重启游戏循环（不改变 paused 等外部状态）。
     * 在看门狗检测到卡死时调用。
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
        // 重启循环
        startGameLoop()
    }

    private fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    val isGameLoopRunning: Boolean get() = gameLoopJob?.isActive == true

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

    /** UI 层调用：记录用户交互时间，唤醒空闲状态 */
    fun onUserInteraction() {
        lastUserInteractionTime = System.currentTimeMillis()
        // 空闲恢复：恢复之前的焦点域
        if (isInIdleState && previousFocusDomain != null) {
            isInIdleState = false
            previousFocusDomain = null
        }
    }

    /**
     * 当玩家打开某个界面时调用，触发该域立即追赶结算。
     */
    fun catchUpDomain(domain: FocusDomain) {
        domainLastTickTime.remove(domain)
        onUserInteraction()
    }

    /** 获取当前活跃的关注域集合（基于 activeTab + dialog + 弟子焦点） */
    private fun getActiveDomains(): Set<FocusDomain> {
        // 空闲检测：30s 无交互 → 所有系统按 BACKGROUND 域频率执行
        val idleTimeMs = System.currentTimeMillis() - lastUserInteractionTime
        if (idleTimeMs > IDLE_DETECTION_MS) {
            if (!isInIdleState) {
                isInIdleState = true
            }
            return setOf(FocusDomain.ALWAYS, FocusDomain.BACKGROUND)
        }

        val tab = stateStore.activeTab
        val dialog = stateStore.activeDialog
        val focusedDiscipleId = stateStore.focusedDiscipleId

        val domains = mutableSetOf(FocusDomain.ALWAYS)

        when (tab) {
            "OVERVIEW" -> {
                domains.add(FocusDomain.DISCIPLES)
                domains.add(FocusDomain.BUILDINGS)
            }
            "DISCIPLES" -> domains.add(FocusDomain.DISCIPLES)
            "BUILDINGS" -> domains.add(FocusDomain.BUILDINGS)
            "WAREHOUSE" -> domains.add(FocusDomain.WAREHOUSE)
            "SETTINGS" -> {}
        }

        if (focusedDiscipleId != null) {
            domains.add(FocusDomain.DISCIPLES)
        }

        when (dialog) {
            "Alchemy", "Forge", "HerbGarden", "SpiritMine",
            "WarehouseBuilding" -> domains.add(FocusDomain.BUILDINGS)
            "WorldMap" -> domains.add(FocusDomain.WORLD_MAP)
            "Diplomacy" -> {
                domains.add(FocusDomain.DIPLOMACY)
                domains.add(FocusDomain.DISCIPLES)
            }
            "MissionHall", "PatrolTower" -> {
                domains.add(FocusDomain.EXPLORATION)
                domains.add(FocusDomain.DISCIPLES)
            }
            "Warehouse" -> domains.add(FocusDomain.WAREHOUSE)
            // 显示弟子卡片（境界、忠诚度需实时结算）
            "Recruit", "Residence", "Library", "WenDaoPeak", "QingyunPeak",
            "TianshuHall", "LawEnforcementHall", "ReflectionCliff",
            "BloodRefiningPool", "BattleLog" -> domains.add(FocusDomain.DISCIPLES)
        }

        return domains
    }

    /** 判断某个域在本 tick 是否应执行 */
    private fun shouldExecuteDomain(domain: FocusDomain, activeDomains: Set<FocusDomain>): Boolean {
        if (domain == FocusDomain.ALWAYS) return true
        if (domain in activeDomains) return true

        val lastTime = domainLastTickTime[domain] ?: 0L
        return (System.currentTimeMillis() - lastTime) >= NON_FOCUS_TICK_INTERVAL
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
        thermalMonitor.reportActualWorkDuration(tickDurationNanos)

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

        // 热管理：过热时降低负载
        if (thermalMonitor.shouldEmergencySave()) {
            DomainLog.w(TAG, "Thermal status SEVERE+, triggering emergency save and skipping tick")
            _autoSaveTrigger.trySend(Unit)
            gameClock.consumeDeadTime()  // 更新lastWallMs，防止恢复后时间跳变
            return
        }
        if (thermalMonitor.shouldReduceWorkload()) {
            // 跳过非关键计算（如 AI 更新、视觉效果）
            // 只执行核心时间推进
            DomainLog.d(TAG, "Thermal throttling: skipping non-critical systems")
            thermalMonitor.updateTargetDuration(200_000_000L)  // 200ms target
        } else {
            thermalMonitor.updateTargetDuration(100_000_000L)  // 100ms target
        }

        val activeDomains = getActiveDomains()

        _tickCount.value++

        // ── 基于 GameTimeClock 的固定步长旬推进 ──
        val tickResult = gameClock.tick(
            isSettlementPending = settlementCoordinator.hasPendingWork
        )

        var monthChanged = false
        var yearChanged = false

        for (phaseIndex in 1..tickResult.phasesToAdvance) {
            val currentPhase = stateStore.gameData.value.gamePhase
            val activeDomainsPerPhase = getActiveDomains()  // 每旬重新计算焦域

            when {
                // 下旬 + 结算未完成 → 丢弃推进，等待结算
                currentPhase == GamePhase.LATE.value && tickResult.isSettlementPending -> {
                    gameClock.forceConsumeOnePhase()
                    break  // 后续旬也丢弃
                }

                // 正常旬推进
                else -> {
                    stateStore.update {
                        val prevMonth = this.gameData.gameMonth
                        val prevYear = this.gameData.gameYear

                        // 执行当前旬的 tick（两档制 + 分旬调度）
                        if (settlementCoordinator.hasPendingWork) {
                            systemManager.getSystem(TimeSystem::class).onPhaseTick(this)
                        } else {
                            val phase1Based = this.gameData.gamePhase + 1
                            systemManager.onPhaseTickWithDomainFilter(
                                this, activeDomainsPerPhase, ::shouldExecuteDomain, ::markDomainExecuted,
                                currentPhase = phase1Based,
                                lazyEvaluationDispatcher = lazyEvaluationDispatcher
                            )
                            if (shouldExecuteDomain(FocusDomain.DISCIPLES, activeDomainsPerPhase)) {
                                cultivationService.recoverHpMpForAllDisciples(this)
                                markDomainExecuted(FocusDomain.DISCIPLES)
                            }
                        }

                        if (this.gameData.gameMonth != prevMonth) monthChanged = true
                        if (this.gameData.gameYear != prevYear) yearChanged = true

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

        // 月变/年变 → 调度结算
        if (settlementCoordinator.hasPendingWork && (monthChanged || yearChanged)) {
            forceCompleteSettlement()
        }
        // 年变/月变 → 在创建结算影子前，先将年度/月度事件应用到真实游戏状态
        // （内部方法使用 stateStore.update{}，不能在 shadow transaction 中调用）
        if (yearChanged) {
            cultivationService.processYearlyEvents()
        }
        if (monthChanged) {
            cultivationService.processMonthlyEvents()
        }
        if (yearChanged) {
            val shadow = stateStore.createSettlementShadow()
            settlementCoordinator.scheduleYearly(shadow)
        } else if (monthChanged) {
            val shadow = stateStore.createSettlementShadow()
            settlementCoordinator.scheduleMonthly(shadow)
        }
        if (settlementCoordinator.hasPendingWork) {
            val completed = settlementCoordinator.executeStep()
            if (completed) settlementCoordinator.onSettlementComplete()
        }

        // 巡逻结果
        val patrolResults = explorationService.consumePendingPatrolResults()
        for (result in patrolResults) {
            stateStore.setPendingBattleResult(result)
        }
    }

    private var isForceCompleting = false

    private suspend fun forceCompleteSettlement() {
        if (isForceCompleting) return
        isForceCompleting = true
        try {
            while (settlementCoordinator.hasPendingWork) {
                settlementCoordinator.executeStep()
            }
            settlementCoordinator.onSettlementComplete()
        } finally {
            isForceCompleting = false
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
    private suspend fun antiFreezeDelay(totalMs: Long) {
        val profile = OemPowerProfileProvider.current
        val microInterval = 2L
        // 数据驱动：激进 OEM 更频繁忙等以突破更窄的空闲检测窗口
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
                // 忙等循环：周期性保持线程 RUNNABLE 打破 OEM 空闲检测。
                // API 33+：忙等循环内调用 Thread.onSpinWait() 作为 CPU 优化提示。
                val busyEnd = android.os.SystemClock.elapsedRealtime() + busyDuration
                while (android.os.SystemClock.elapsedRealtime() < busyEnd) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        Thread.onSpinWait()
                    }
                }
            }
        }
    }

    // 保留旧方法名作为兼容性委托
    @Deprecated("Use antiFreezeDelay instead", ReplaceWith("antiFreezeDelay(totalMs)"))
    private suspend fun pollWithShortDelay(totalMs: Long) {
        antiFreezeDelay(totalMs)
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
