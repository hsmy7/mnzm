package com.xianxia.sect.core.engine

import android.util.Log
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.subsystem.*
import com.xianxia.sect.core.event.*
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.*
import com.xianxia.sect.core.performance.GamePerformanceMonitor
import com.xianxia.sect.data.facade.RefactoredStorageFacade
import com.xianxia.sect.data.unified.SaveResult
import com.xianxia.sect.data.model.SaveData
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass

@Singleton
class GameEngineCore @Inject constructor(
    private val stateManager: UnifiedGameStateManager,
    private val eventBus: EventBus,
    private val performanceMonitor: GamePerformanceMonitor,
    private val gameEngine: GameEngine,
    private val storageFacade: RefactoredStorageFacade,
    subsystems: Set<@JvmSuppressWildcards GameSubsystem>
) {
    
    companion object {
        private const val TAG = "GameEngineCore"
        private const val TICK_INTERVAL_MS = 200L
        private const val TICK_WARNING_THRESHOLD_MS = 50f
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var gameLoopJob: Job? = null
    
    private val _tickCount = MutableStateFlow(0L)
    val tickCount: StateFlow<Long> = _tickCount.asStateFlow()
    
    private val _fps = MutableStateFlow(0f)
    val fps: StateFlow<Float> = _fps.asStateFlow()
    
    private var lastTickTime = System.currentTimeMillis()
    private var frameCount = 0
    private var fpsAccumulator = 0f
    
    val state: StateFlow<UnifiedGameState> get() = stateManager.state
    val events: Flow<DomainEvent> get() = eventBus.events
    
    private val subsystemsMap: MutableMap<KClass<out GameSubsystem>, GameSubsystem> = mutableMapOf()
    private val sortedSubsystems: List<GameSubsystem>
    
    private var dayAccumulator = 0.0
    private var autoSaveDayCounter = 0
    
    init {
        subsystems.forEach { subsystem ->
            subsystemsMap[subsystem::class] = subsystem
            subsystem.initialize()
            Log.i(TAG, "Registered subsystem: ${subsystem.systemName} (priority: ${subsystem.priority})")
        }
        sortedSubsystems = subsystems.sortedBy { it.priority }
    }
    
    fun initialize() {
        Log.i(TAG, "GameEngineCore initialized with ${subsystemsMap.size} subsystems")
    }
    
    @Suppress("UNCHECKED_CAST")
    fun <T : GameSubsystem> getSubsystem(type: KClass<T>): T? {
        return subsystemsMap[type] as? T
    }
    
    inline fun <reified T : GameSubsystem> getSubsystem(): T? = getSubsystem(T::class)
    
    fun registerSubsystem(subsystem: GameSubsystem) {
        subsystemsMap[subsystem::class] = subsystem
        subsystem.initialize()
        Log.i(TAG, "Registered subsystem: ${subsystem.systemName}")
    }
    
    fun unregisterSubsystem(type: KClass<out GameSubsystem>) {
        val subsystem = subsystemsMap.remove(type)
        subsystem?.dispose()
        Log.i(TAG, "Unregistered subsystem: ${subsystem?.systemName}")
    }
    
    fun startGameLoop() {
        if (gameLoopJob?.isActive == true) {
            Log.w(TAG, "Game loop already running")
            return
        }
        
        dayAccumulator = 0.0
        autoSaveDayCounter = 0
        
        gameLoopJob = scope.launch {
            Log.i(TAG, "Starting game loop")
            
            while (isActive) {
                try {
                    val startTime = System.currentTimeMillis()
                    
                    tick()
                    
                    val elapsed = System.currentTimeMillis() - startTime
                    val delayMs = (TICK_INTERVAL_MS - elapsed).coerceAtLeast(0)
                    
                    updateFps(elapsed.toFloat())
                    
                    delay(delayMs)
                } catch (e: CancellationException) {
                    Log.i(TAG, "Game loop cancelled")
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error in game loop", e)
                    delay(1000)
                }
            }
        }
    }
    
    fun stopGameLoop() {
        gameLoopJob?.cancel()
        gameLoopJob = null
        Log.i(TAG, "Game loop stopped")
    }
    
    suspend fun pause() {
        stateManager.setPaused(true)
    }
    
    suspend fun resume() {
        stateManager.setPaused(false)
    }
    
    private suspend fun tick() {
        val tickStartTime = System.currentTimeMillis()
        
        tickInternal()
        
        val tickTime = (System.currentTimeMillis() - tickStartTime).toFloat()
        performanceMonitor.recordTick(tickTime)
        
        val entityCount = stateManager.currentState.disciples.size
        performanceMonitor.recordEntityCount(entityCount)
        
        if (tickTime > TICK_WARNING_THRESHOLD_MS) {
            Log.w(TAG, "Slow tick detected: ${tickTime}ms")
        }
    }
    
    private suspend fun tickInternal() {
        val currentState = stateManager.currentState
        if (currentState.isPaused || currentState.isLoading || currentState.isSaving) {
            return
        }
        
        _tickCount.value++
        
        gameEngine.processSecondTick()
        
        val daysPerTick = GameConfig.Time.DAYS_PER_MONTH.toDouble() / 
            (GameConfig.Time.SECONDS_PER_REAL_MONTH * GameConfig.Time.TICKS_PER_SECOND)
        dayAccumulator += daysPerTick
        
        while (dayAccumulator >= 1.0) {
            dayAccumulator -= 1.0
            gameEngine.advanceDay()
            autoSaveDayCounter++
            
            val autoSaveInterval = gameEngine.gameData.value.autoSaveIntervalMonths
            if (autoSaveInterval > 0 && autoSaveDayCounter >= autoSaveInterval * GameConfig.Time.DAYS_PER_MONTH) {
                autoSaveDayCounter = 0
                performAutoSave()
            }
        }
        
        syncEngineStateToStateManager()
    }
    
    private suspend fun syncEngineStateToStateManager() {
        val engineSnapshot = gameEngine.getStateSnapshotSync()
        stateManager.updateStateSync { currentState ->
            currentState.copy(
                gameData = engineSnapshot.gameData,
                disciples = engineSnapshot.disciples,
                equipment = engineSnapshot.equipment,
                teams = engineSnapshot.teams,
                events = engineSnapshot.events,
                battleLogs = engineSnapshot.battleLogs
            )
        }
    }
    
    suspend fun performAutoSave() {
        try {
            stateManager.setSaving(true)
            val engineSnapshot = gameEngine.getStateSnapshotSync()
            val saveData = SaveData(
                gameData = engineSnapshot.gameData,
                disciples = engineSnapshot.disciples,
                equipment = engineSnapshot.equipment,
                manuals = engineSnapshot.manuals,
                pills = engineSnapshot.pills,
                materials = engineSnapshot.materials,
                herbs = engineSnapshot.herbs,
                seeds = engineSnapshot.seeds,
                teams = engineSnapshot.teams,
                events = engineSnapshot.events,
                battleLogs = engineSnapshot.battleLogs,
                alliances = engineSnapshot.alliances
            )
            val currentSlot = engineSnapshot.gameData.currentSlot
            val result = storageFacade.save(currentSlot, saveData)
            if (result.isSuccess) {
                Log.i(TAG, "Auto save completed for slot $currentSlot")
            } else {
                Log.e(TAG, "Auto save failed for slot $currentSlot: $result")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Auto save failed", e)
        } finally {
            stateManager.setSaving(false)
        }
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
        val currentState = stateManager.currentState
        return GameStateSnapshot(
            gameData = currentState.gameData,
            disciples = currentState.disciples,
            equipment = currentState.equipment,
            manuals = currentState.manuals,
            pills = currentState.pills,
            materials = currentState.materials,
            herbs = currentState.herbs,
            seeds = currentState.seeds,
            teams = currentState.teams,
            events = currentState.events,
            battleLogs = currentState.battleLogs,
            alliances = currentState.alliances
        )
    }
    
    suspend fun loadSnapshot(snapshot: GameStateSnapshot) {
        val gameState = GameState(
            gameData = snapshot.gameData,
            disciples = snapshot.disciples,
            equipment = snapshot.equipment,
            manuals = snapshot.manuals,
            pills = snapshot.pills,
            materials = snapshot.materials,
            herbs = snapshot.herbs,
            seeds = snapshot.seeds,
            teams = snapshot.teams,
            events = snapshot.events,
            battleLogs = snapshot.battleLogs,
            alliances = snapshot.alliances
        )
        
        stateManager.loadState(UnifiedGameState.fromGameState(gameState))
    }
    
    data class GameStateSnapshot(
        val gameData: GameData,
        val disciples: List<Disciple>,
        val equipment: List<Equipment>,
        val manuals: List<Manual>,
        val pills: List<Pill>,
        val materials: List<Material>,
        val herbs: List<Herb>,
        val seeds: List<Seed>,
        val teams: List<ExplorationTeam>,
        val events: List<GameEvent>,
        val battleLogs: List<BattleLog>,
        val alliances: List<Alliance>
    )
    
    fun dispose() {
        stopGameLoop()
        subsystemsMap.values.forEach { it.dispose() }
        scope.cancel()
        Log.i(TAG, "GameEngineCore disposed")
    }
}
