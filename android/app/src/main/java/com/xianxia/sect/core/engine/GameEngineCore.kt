package com.xianxia.sect.core.engine

import android.util.Log
import com.xianxia.sect.BuildConfig
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.service.ExplorationService
import com.xianxia.sect.core.engine.settlement.SettlementCoordinator
import com.xianxia.sect.core.engine.system.SystemManager
import com.xianxia.sect.core.engine.system.TimeSystem
import com.xianxia.sect.core.event.*
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.*
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
    private val applicationScopeProvider: ApplicationScopeProvider
) {
    
    companion object {
        private const val TAG = "GameEngineCore"
        private const val TICK_INTERVAL_MS = 1000L
        private const val MIN_TICK_DELAY_MS = 50L
        private const val TICK_WARNING_THRESHOLD_MS = 100f
        private const val STUCK_STATE_TIMEOUT_MS = 30_000L
        private const val ADAPTIVE_MAX_INTERVAL_MS = 2000L
    }

    private var currentTickInterval = TICK_INTERVAL_MS
    private var consecutiveOverruns = 0
    
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
                val delayMs = (currentTickInterval - elapsed).coerceAtLeast(MIN_TICK_DELAY_MS)

                // 自适应：连续超时则降频，正常则恢复
                if (elapsed > currentTickInterval) {
                    consecutiveOverruns++
                    if (consecutiveOverruns > 3) {
                        currentTickInterval = (currentTickInterval * 1.5).toLong().coerceAtMost(ADAPTIVE_MAX_INTERVAL_MS)
                        consecutiveOverruns = 0
                    }
                } else if (consecutiveOverruns == 0 && currentTickInterval > TICK_INTERVAL_MS) {
                    currentTickInterval = (currentTickInterval * 0.8).toLong().coerceAtLeast(TICK_INTERVAL_MS)
                } else {
                    consecutiveOverruns = 0
                }

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
        systemManager.getSystem(CultivationService::class).resetHighFrequencyData()
    }
    
    suspend fun resume() {
        stateManager.setPaused(false)
    }

    fun pauseForBackground() {
        if (!stateStore.unifiedState.value.isPaused) {
            stateManager.setPausedDirect(true)
            settlementCoordinator.cancelPendingWork()
            engineScope.launch {
                systemManager.getSystem(CultivationService::class).resetHighFrequencyData()
            }
            _wasPausedByBackground = true
        } else {
            _wasPausedByBackground = false
        }
    }

    @Volatile
    private var _wasPausedByBackground = false
    val wasPausedByBackground: Boolean get() = _wasPausedByBackground

    fun clearBackgroundPauseFlag() {
        _wasPausedByBackground = false
    }
    
    private suspend fun tick() {
        val tickStartTime = System.currentTimeMillis()
        
        tickInternal()
        
        val tickTime = (System.currentTimeMillis() - tickStartTime).toFloat()
        unifiedPerformanceMonitor.recordTick(tickTime)
        
        val entityCount = stateStore.unifiedState.value.disciples.size
        unifiedPerformanceMonitor.recordEntityCount(entityCount)
        
        if (tickTime > TICK_WARNING_THRESHOLD_MS) {
            Log.w(TAG, "Slow tick detected: ${tickTime}ms")
        }
    }
    
    private suspend fun tickInternal() {
        val currentState = stateStore.unifiedState.value
        if (currentState.isPaused || currentState.isLoading || currentState.isSaving) {
            checkAndResetStuckStates(currentState)
            return
        }

        _tickCount.value++

        var monthChanged = false
        var yearChanged = false

        stateStore.update {
            val speed = this.gameData.gameSpeed.coerceIn(1, 2)
            val phasesPerTick = (GamePhase.PHASES_PER_MONTH.toDouble() * speed) /
                (GameConfig.Time.SECONDS_PER_REAL_MONTH * GameConfig.Time.TICKS_PER_SECOND)
            phaseAccumulator += phasesPerTick

            while (phaseAccumulator >= 1.0) {
                phaseAccumulator -= 1.0

                val prevMonth = this.gameData.gameMonth
                val prevYear = this.gameData.gameYear

                if (settlementCoordinator.hasPendingWork) {
                    systemManager.getSystem(TimeSystem::class).onPhaseTick(this)
                } else {
                    systemManager.onPhaseTick(this)
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

        val patrolResults = systemManager.getSystem<ExplorationService>().consumePendingPatrolResults()
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
    private fun checkAndResetStuckStates(currentState: UnifiedGameState) {
        val now = System.currentTimeMillis()

        // 跟踪 isSaving 变为 true 的时间
        if (currentState.isSaving) {
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
        if (currentState.isLoading) {
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
     * 强制重置 isSaving 和 isLoading 为 false。
     * 用于看门狗检测到状态卡住时调用，也可从外部调用作为紧急恢复手段。
     */
    fun forceResetStuckStates() {
        Log.w(TAG, "Force resetting stuck states: isSaving and isLoading -> false")
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
