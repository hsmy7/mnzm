package com.xianxia.sect.core.engine

import android.util.Log
import com.xianxia.sect.BuildConfig
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.domain.exploration.ExplorationService
import com.xianxia.sect.core.engine.domain.settlement.SettlementCoordinator
import com.xianxia.sect.core.engine.system.SystemManager
import com.xianxia.sect.core.engine.system.TimeSystem
import com.xianxia.sect.core.engine.system.FocusDomain
import com.xianxia.sect.core.event.*
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.*
import com.xianxia.sect.core.perf.ThermalMonitor
import com.xianxia.sect.core.performance.UnifiedPerformanceMonitor
import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
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
    private val stateManager: UnifiedGameStateManager,
    private val eventBus: EventBusPort,
    private val unifiedPerformanceMonitor: UnifiedPerformanceMonitor,
    private val systemManager: SystemManager,
    private val settlementCoordinator: SettlementCoordinator,
    private val applicationScopeProvider: ApplicationScopeProvider,
    private val cultivationService: CultivationService,
    private val explorationService: ExplorationService,
    private val thermalMonitor: ThermalMonitor
) {
    
    companion object {
        private const val TAG = "GameEngineCore"
        private const val TICK_INTERVAL_MS = 100L
        private const val MIN_TICK_DELAY_MS = 16L
        private const val TICK_WARNING_THRESHOLD_MS = 100f
        private const val STUCK_STATE_TIMEOUT_MS = 30_000L
        private const val ADAPTIVE_MAX_INTERVAL_MS = 1000L
        private const val IDLE_TICK_INTERVAL_MS = 2000L
        private const val IDLE_DETECTION_MS = 60_000L
        private const val NON_FOCUS_TICK_INTERVAL = 30_000L
        private const val TICK_TIME_BUDGET_MS = 50L
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

    // 后台停止时保存状态
    @Volatile
    private var wasRunningBeforeBackground = false
    
    private val engineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        if (throwable !is CancellationException) {
            Log.e(TAG, "Unhandled exception in engine coroutine", throwable)
        }
    }
    private var engineJob = SupervisorJob(applicationScopeProvider.scope.coroutineContext[Job])
    private var engineScope: CoroutineScope = CoroutineScope(engineJob + Dispatchers.Default + engineExceptionHandler)

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
    
    private var phaseAccumulator = 0.0

    /** 上一次 tick 开始的墙上时间戳（ms），用于基于真实时间计算 phase 推进 */
    private var lastTickStartMs = 0L

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

    fun initialize() {
        if (isInitialized) {
            Log.w(TAG, "GameEngineCore already initialized")
            return
        }
        if (_autoSaveTrigger.isClosedForSend) {
            _autoSaveTrigger = Channel(capacity = Channel.BUFFERED)
        }
        systemManager.initializeAll()
        isInitialized = true
        Log.i(TAG, "GameEngineCore initialized")
    }
    
    fun startGameLoop() {
        if (gameLoopJob?.isActive == true) {
            Log.w(TAG, "Game loop already running")
            return
        }
        
        phaseAccumulator = 0.0
        lastTickStartMs = 0
        gameLoopStoppedSignal = CompletableDeferred()
        unifiedPerformanceMonitor.start()

        stateManager.setPausedDirect(false)
        Log.i(TAG, "Game state resumed (isPaused=false)")
        
        gameLoopJob = engineScope.launch {
            Log.i(TAG, "Starting game loop")
            
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
                    isIdle && !settlementCoordinator.hasPendingWork -> IDLE_TICK_INTERVAL_MS
                    adaptiveSlowdownFactor > 1.0 -> (TICK_INTERVAL_MS * adaptiveSlowdownFactor).toLong().coerceAtMost(ADAPTIVE_MAX_INTERVAL_MS)
                    else -> TICK_INTERVAL_MS
                }
                currentTickInterval = effectiveInterval
                val delayMs = (effectiveInterval - elapsed).coerceAtLeast(MIN_TICK_DELAY_MS)

                updateFps(actualFrameInterval)

                        delay(delayMs)
                    } catch (e: CancellationException) {
                        Log.i(TAG, "Game loop cancelled")
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in game loop", e)
                        delay(1000)
                    }
                }
            } finally {
                gameLoopStoppedSignal.complete(Unit)
                Log.i(TAG, "Game loop stopped signal sent")
            }
        }
    }
    
    fun stopGameLoop() {
        unifiedPerformanceMonitor.stop()
        stateManager.setPausedDirect(true)
        gameLoopJob?.cancel()
        gameLoopJob = null
        Log.i(TAG, "Game loop stop requested, isPaused=true")
    }
    
    suspend fun stopGameLoopAndWait(timeoutMs: Long = 5000): Boolean {
        stopGameLoop()
        return withTimeoutOrNull(timeoutMs) {
            gameLoopStoppedSignal.await()
            true
        } ?: run {
            Log.w(TAG, "Game loop did not stop within ${timeoutMs}ms")
            false
        }
    }
    
    fun shutdown() {
        stopGameLoop()
        deathEventJob?.cancel()
        deathEventJob = null
        systemManager.releaseAll()
        _autoSaveTrigger.close()
        engineJob.cancel()
        engineJob = SupervisorJob(applicationScopeProvider.scope.coroutineContext[Job])
        engineScope = CoroutineScope(engineJob + Dispatchers.Default + engineExceptionHandler)
        isInitialized = false
        Log.i(TAG, "GameEngineCore shutdown complete")
    }
    
    val isGameLoopRunning: Boolean get() = gameLoopJob?.isActive == true

    suspend fun pause() {
        stateManager.setPaused(true)
        cultivationService.resetHighFrequencyData()
    }
    
    suspend fun resume() {
        stateManager.setPaused(false)
    }

    fun pauseForBackground() {
        if (isGameLoopRunning) {
            wasRunningBeforeBackground = true
            stopGameLoop()
            Log.i(TAG, "Game loop stopped for background")
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
            stateManager.setPausedDirect(false)
            startGameLoop()
            Log.i(TAG, "Game loop resumed from background")
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
            "WarehouseBuilding", "Residence" -> domains.add(FocusDomain.BUILDINGS)
            "WorldMap" -> domains.add(FocusDomain.WORLD_MAP)
            "Diplomacy" -> domains.add(FocusDomain.DIPLOMACY)
            "MissionHall", "PatrolTower" -> domains.add(FocusDomain.EXPLORATION)
            "Warehouse" -> domains.add(FocusDomain.WAREHOUSE)
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

        tickInternal()

        val tickTime = (System.currentTimeMillis() - tickStartTime).toFloat()
        unifiedPerformanceMonitor.recordTick(tickTime)

        val entityCount = stateStore.disciplesSnapshot.size
        unifiedPerformanceMonitor.recordEntityCount(entityCount)

        if (tickTime > TICK_TIME_BUDGET_MS) {
            Log.w(TAG, "Tick over budget: ${tickTime}ms (budget=${TICK_TIME_BUDGET_MS}ms)")
            adaptiveSlowdownFactor = (adaptiveSlowdownFactor * 1.5).coerceAtMost(5.0)
        } else if (tickTime < TICK_TIME_BUDGET_MS / 2 && adaptiveSlowdownFactor > 1.0) {
            adaptiveSlowdownFactor = (adaptiveSlowdownFactor * 0.9).coerceAtLeast(1.0)
        }

        if (tickTime > TICK_WARNING_THRESHOLD_MS) {
            Log.w(TAG, "Slow tick detected: ${tickTime}ms")
        }
    }
    
    private suspend fun tickInternal() {
        val isPaused = stateStore.isPaused.value
        val isLoading = stateStore.isLoading.value
        val isSaving = stateStore.isSaving.value
        if (isPaused || isLoading || isSaving) {
            checkAndResetStuckStates(isSaving, isLoading)
            return
        }

        // 热管理：过热时降低负载
        if (thermalMonitor.shouldEmergencySave()) {
            Log.w(TAG, "Thermal status SEVERE+, triggering emergency save and skipping tick")
            _autoSaveTrigger.trySend(Unit)
            return
        }
        if (thermalMonitor.shouldReduceWorkload()) {
            // 跳过非关键计算（如 AI 更新、视觉效果）
            // 只执行核心时间推进
            Log.d(TAG, "Thermal throttling: skipping non-critical systems")
        }

        val activeDomains = getActiveDomains()

        _tickCount.value++

        // 基于真实墙上时间计算阶段推进，避免空闲/降频时游戏内时间变慢
        val now = System.currentTimeMillis()
        val elapsedMs = if (lastTickStartMs > 0) {
            (now - lastTickStartMs).coerceIn(0, 5000)
        } else {
            0L  // 首次 tick 使用固定公式兜底
        }
        lastTickStartMs = now

        var monthChanged = false
        var yearChanged = false

        stateStore.update {
            val speed = this.gameData.gameSpeed.coerceIn(1, 2)
            val phasesPerTick = if (elapsedMs > 0) {
                (GamePhase.PHASES_PER_MONTH.toDouble() * speed) * elapsedMs /
                    (GameConfig.Time.SECONDS_PER_REAL_MONTH * 1000.0)
            } else {
                // 首次 tick 兜底：基于预期的 100ms 间隔
                (GamePhase.PHASES_PER_MONTH.toDouble() * speed) /
                    (GameConfig.Time.SECONDS_PER_REAL_MONTH * GameConfig.Time.TICKS_PER_SECOND)
            }
            phaseAccumulator += phasesPerTick

            while (phaseAccumulator >= 1.0) {
                phaseAccumulator -= 1.0

                val prevMonth = this.gameData.gameMonth
                val prevYear = this.gameData.gameYear

                if (settlementCoordinator.hasPendingWork) {
                    systemManager.getSystem(TimeSystem::class).onPhaseTick(this)
                    cultivationService.recoverHpMpForAllDisciples(this)
                } else {
                    // 两档制：活跃域每 tick 执行，非活跃域最多 30 秒一次
                    systemManager.onPhaseTickWithDomainFilter(
                        this, activeDomains, ::shouldExecuteDomain, ::markDomainExecuted
                    )
                    // HP/MP 恢复跟 DISCIPLES 域走：活跃时每 phase，不活跃时 30s 一次
                    if (shouldExecuteDomain(FocusDomain.DISCIPLES, activeDomains)) {
                        cultivationService.recoverHpMpForAllDisciples(this)
                        markDomainExecuted(FocusDomain.DISCIPLES)
                    }
                }

                if (this.gameData.gameMonth != prevMonth) monthChanged = true
                if (this.gameData.gameYear != prevYear) yearChanged = true

                val gameDataSnapshot = this.gameData
                val autoSaveInterval = gameDataSnapshot.autoSaveIntervalMonths
                if (autoSaveInterval > 0) {
                    if (gameDataSnapshot.gamePhase == GamePhase.EARLY.value && gameDataSnapshot.gameMonth % autoSaveInterval == 0) {
                        val result = _autoSaveTrigger.trySend(Unit)
                        if (result.isSuccess) {
                            if (BuildConfig.DEBUG) Log.d(TAG, "Auto save triggered: year=${gameDataSnapshot.gameYear}, month=${gameDataSnapshot.gameMonth}, phase=${gameDataSnapshot.gamePhase}")
                        } else {
                            Log.w(TAG, "Auto save trigger failed to send: $result, year=${gameDataSnapshot.gameYear}, month=${gameDataSnapshot.gameMonth}")
                        }
                    }
                }
            }
        }

        if (settlementCoordinator.hasPendingWork && (monthChanged || yearChanged)) {
            forceCompleteSettlement()
        }

        if (yearChanged) {
            val shadow = stateStore.createShadow()
            settlementCoordinator.scheduleYearly(shadow)
        } else if (monthChanged) {
            val shadow = stateStore.createShadow()
            settlementCoordinator.scheduleMonthly(shadow)
        }

        if (settlementCoordinator.hasPendingWork) {
            val completed = settlementCoordinator.executeStep(timeBudgetMs = 1)
            if (completed) settlementCoordinator.onSettlementComplete()
        }

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
                settlementCoordinator.executeStep(timeBudgetMs = 5)
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
                Log.e(TAG, "isSaving has been true for ${now - savingStartTime}ms, force resetting")
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
                Log.e(TAG, "isLoading has been true for ${now - loadingStartTime}ms, force resetting")
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
        Log.w(TAG, "Force resetting stuck states: isSaving and isLoading -> false, cancelling active jobs")
        activeSaveJob?.cancel()
        activeSaveJob = null
        activeLoadJob?.cancel()
        activeLoadJob = null
        stateManager.setSavingDirect(false)
        stateManager.setLoadingDirect(false)
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
