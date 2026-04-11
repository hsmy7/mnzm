package com.xianxia.sect.ui.game

import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.data.BeastMaterialDatabase
import com.xianxia.sect.core.data.ForgeRecipeDatabase
import com.xianxia.sect.core.data.HerbDatabase
import com.xianxia.sect.core.data.PillRecipeDatabase
import com.xianxia.sect.core.engine.AISectAttackManager
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.coordinator.SavePipeline
import com.xianxia.sect.core.engine.service.HighFrequencyData
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.UnifiedGameStateManager
import com.xianxia.sect.core.event.EventBus
import com.xianxia.sect.core.performance.GamePerformanceMonitor
import com.xianxia.sect.core.engine.system.SystemManager
import com.xianxia.sect.data.facade.RefactoredStorageFacade
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.model.SaveSlot
import com.xianxia.sect.data.unified.SaveError
import com.xianxia.sect.data.unified.SaveResult
import com.xianxia.sect.network.NetworkUtils
import com.xianxia.sect.ui.state.DialogStateManager
import com.xianxia.sect.ui.state.DialogStateManager.DialogType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

@HiltViewModel
class GameViewModel @Inject constructor(
    private val gameEngine: GameEngine,
    private val gameEngineCore: GameEngineCore,
    private val storageFacade: RefactoredStorageFacade,
    val dialogStateManager: DialogStateManager,
    @ApplicationContext private val appContext: Context,
    private val stateManager: UnifiedGameStateManager,
    private val eventBus: EventBus,
    private val performanceMonitor: GamePerformanceMonitor,
    private val systemManager: SystemManager,
    private val savePipeline: SavePipeline
) : ViewModel() {

    companion object {
        private const val TAG = "GameViewModel"
        
        private const val MB = 1024 * 1024L
        private const val REALM_VICE_SECT_MASTER = 4
        
        // 加载进度常量
        private const val PROGRESS_START = 0f
        private const val PROGRESS_ENGINE_INIT = 0.2f
        private const val PROGRESS_DATA_LOAD = 0.3f
        private const val PROGRESS_SAVE_COMPLETE = 0.5f
        private const val PROGRESS_RESTART_DATA_LOAD = 0.6f
        private const val PROGRESS_GAME_LOOP_START = 0.8f
        private const val PROGRESS_COMPLETE = 1f
        
        // 加载完成显示时间（毫秒）
        private const val LOADING_COMPLETE_DISPLAY_DURATION = 300L
    }

    private fun openDialog(dialogType: DialogType, params: Map<String, Any?> = emptyMap()) {
        dialogStateManager.openDialog(dialogType, params)
    }
    
    /**
     * 关闭当前对话框的统一方法
     */
    fun closeCurrentDialog() {
        dialogStateManager.closeDialog()
    }

    fun openRecruitDialog() {
        openDialog(DialogType.Recruit)
    }

    fun openMerchantDialog() {
        openDialog(DialogType.Merchant)
    }

    fun openDiplomacyDialog() {
        openDialog(DialogType.Diplomacy)
    }

    fun openSpiritMineDialog() {
        openDialog(DialogType.SpiritMine)
    }

    fun openHerbGardenDialog() {
        openDialog(DialogType.HerbGarden)
    }

    fun openAlchemyDialog() {
        openDialog(DialogType.Alchemy)
    }

    fun openForgeDialog() {
        openDialog(DialogType.Forge)
    }

    fun openLibraryDialog() {
        openDialog(DialogType.Library)
    }

    fun openWenDaoPeakDialog() {
        openDialog(DialogType.WenDaoPeak)
    }

    fun openQingyunPeakDialog() {
        openDialog(DialogType.QingyunPeak)
    }

    fun openTianshuHallDialog() {
        openDialog(DialogType.TianshuHall)
    }

    fun openLawEnforcementHallDialog() {
        openDialog(DialogType.LawEnforcementHall)
    }

    fun openMissionHallDialog() {
        openDialog(DialogType.MissionHall)
    }

    fun openReflectionCliffDialog() {
        openDialog(DialogType.ReflectionCliff)
    }

    fun openInventoryDialog() {
        openDialog(DialogType.Inventory)
    }

    fun openEventLogDialog() {
        openDialog(DialogType.EventLog)
    }

    fun closeRecruitDialog() {
        closeCurrentDialog()
    }

    fun closeInventoryDialog() {
        closeCurrentDialog()
    }

    fun closeDiplomacyDialog() {
        closeCurrentDialog()
    }

    fun closeMerchantDialog() {
        closeCurrentDialog()
    }

    fun closeEventLogDialog() {
        closeCurrentDialog()
    }

    val gameData: StateFlow<GameData> = gameEngine.gameData
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), gameEngine.gameData.value ?: GameData())

    /**
     * 弟子聚合数据 - 用于 UI 层显示（推荐使用）
     *
     * 此属性将底层的 List<Disciple> 自动转换为 List<DiscipleAggregate>，
     * 确保 UI 层统一使用新的多表架构类型。
     */
    val discipleAggregates: StateFlow<List<DiscipleAggregate>> = gameEngine.disciples
        .map { discipleList -> discipleList.map { it.toAggregate() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val disciples: StateFlow<List<DiscipleAggregate>> = discipleAggregates

    /**
     * 可招募弟子聚合数据 - 响应式数据流
     *
     * 根据 gameData.recruitList 中的 ID 从全量弟子中筛选出可招募弟子。
     * 当招募列表或弟子数据变化时自动更新，确保 RecruitDialog 显示最新数据。
     */
    val recruitListAggregates: StateFlow<List<DiscipleAggregate>> = gameData
        .map { data -> data.recruitList.map { it.toAggregate() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val equipment: StateFlow<List<Equipment>> = gameEngine.equipment
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val manuals: StateFlow<List<Manual>> = gameEngine.manuals
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pills: StateFlow<List<Pill>> = gameEngine.pills
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val materials: StateFlow<List<Material>> = gameEngine.materials
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val herbs: StateFlow<List<Herb>> = gameEngine.herbs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val seeds: StateFlow<List<Seed>> = gameEngine.seeds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val teams: StateFlow<List<ExplorationTeam>> = gameEngine.teams
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val events: StateFlow<List<GameEvent>> = gameEngine.events
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val battleLogs: StateFlow<List<BattleLog>> = gameEngine.battleLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val alliances: StateFlow<List<Alliance>> = gameEngine.gameData
        .map { it.alliances }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val productionSlots: StateFlow<List<com.xianxia.sect.core.model.production.ProductionSlot>> = gameEngine.productionSlots
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val worldMapRenderData: StateFlow<WorldMapRenderData> = gameEngine.worldMapRenderData
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WorldMapRenderData())

    val alchemySlots: StateFlow<List<AlchemySlot>> = productionSlots
        .map { slots ->
            slots.filter { it.buildingType == com.xianxia.sect.core.model.production.BuildingType.ALCHEMY }.map { slot ->
                AlchemySlot(
                    id = slot.id,
                    slotIndex = slot.slotIndex,
                    recipeId = slot.recipeId,
                    recipeName = slot.recipeName,
                    pillName = slot.outputItemName,
                    pillRarity = slot.outputItemRarity,
                    startYear = slot.startYear,
                    startMonth = slot.startMonth,
                    duration = slot.duration,
                    status = when (slot.status) {
                        com.xianxia.sect.core.model.production.ProductionSlotStatus.IDLE -> AlchemySlotStatus.IDLE
                        com.xianxia.sect.core.model.production.ProductionSlotStatus.WORKING -> AlchemySlotStatus.WORKING
                        com.xianxia.sect.core.model.production.ProductionSlotStatus.COMPLETED -> AlchemySlotStatus.FINISHED
                    },
                    successRate = slot.successRate,
                    requiredMaterials = slot.requiredMaterials
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val highFrequencyData: StateFlow<HighFrequencyData> = gameEngine.highFrequencyData
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HighFrequencyData())

    val realtimeCultivation: StateFlow<Map<String, Double>> = gameEngine.realtimeCultivation
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val saveLock = AtomicBoolean(false)
    private val pendingAutoSave = AtomicBoolean(false)
    private val saveLockAcquireTime = AtomicLong(0L)
    private val consecutiveSaveFailures = AtomicInteger(0)
    private val MAX_CONSECUTIVE_SAVE_FAILURES = 3
    private val SAVE_LOCK_TIMEOUT_MS = 60_000L

    private val _loadingProgress = MutableStateFlow(0f)
    val loadingProgress: StateFlow<Float> = _loadingProgress.asStateFlow()
    
    data class SaveLoadState(
        val isSaving: Boolean = false,
        val isLoading: Boolean = false,
        val pendingSlot: Int? = null,
        val pendingAction: String? = null
    ) {
        val isBusy: Boolean get() = isSaving || isLoading
    }
    
    // UI专属元数据：槽位和操作类型（不属于引擎核心状态）
    private val _pendingSlot = MutableStateFlow<Int?>(null)
    private val _pendingAction = MutableStateFlow<String?>(null)
    
    // 派生自stateManager（唯一真相源）+ 本地UI元数据
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
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SaveLoadState())
    
    val isLoading: StateFlow<Boolean> = saveLoadState.map { it.isLoading }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    
    val isSaving: StateFlow<Boolean> = saveLoadState.map { it.isSaving }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    
    /**
     * 统一的状态设置入口（suspend，因为内部调用stateManager的suspend方法）
     * 以stateManager为唯一真相源，同步引擎层的loading/saving状态，
     * 同时更新本地UI元数据。未指定的布尔参数保持当前值不变。
     */
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
    
    fun resetSaveLoadStateAsync() {
        viewModelScope.launch(Dispatchers.IO) {
            resetSaveLoadState()
        }
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
            equipment = snapshot.equipment,
            manuals = snapshot.manuals,
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
    
    // 游戏已加载标志（用于Activity重建时判断是否需要重新加载）
    @Volatile private var _isGameLoaded = false
    
    // 重新开始状态（用于区分首次加载和重新开始，重新开始时不显示加载界面）
    private val _isRestarting = MutableStateFlow(false)
    val isRestarting: StateFlow<Boolean> = _isRestarting.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _successMessage = MutableStateFlow<String?>(null)
    val successMessage: StateFlow<String?> = _successMessage.asStateFlow()

    fun clearSuccessMessage() {
        _successMessage.value = null
    }

    private val _timeScale = MutableStateFlow(1)
    val timeScale: StateFlow<Int> = _timeScale.asStateFlow()

    // 防止重复点击标志
    private val _isStartingAlchemy = MutableStateFlow(false)
    private val _isStartingForge = MutableStateFlow(false)



    private val _saveSlots = MutableStateFlow<List<SaveSlot>>(emptyList())
    val saveSlots: StateFlow<List<SaveSlot>> = _saveSlots.asStateFlow()



    private val _autoSaveInterval = MutableStateFlow(5)
    val autoSaveInterval: StateFlow<Int> = _autoSaveInterval.asStateFlow()



    private val _selectedBuildingId = MutableStateFlow<String?>(null)
    val selectedBuildingId: StateFlow<String?> = _selectedBuildingId.asStateFlow()

    private val _selectedPlantSlotIndex = MutableStateFlow<Int?>(null)
    val selectedPlantSlotIndex: StateFlow<Int?> = _selectedPlantSlotIndex.asStateFlow()

    val forgeSlots: StateFlow<List<ForgeSlot>> = productionSlots
        .map { slots ->
            slots.filter { it.buildingType == com.xianxia.sect.core.model.production.BuildingType.FORGE }.map { slot ->
                val recipe = slot.recipeId?.let {
                    com.xianxia.sect.core.data.ForgeRecipeDatabase.getRecipeById(it)
                }
                ForgeSlot(
                    id = slot.id,
                    slotIndex = slot.slotIndex,
                    recipeId = slot.recipeId,
                    recipeName = slot.recipeName,
                    equipmentName = recipe?.name ?: "",
                    equipmentRarity = recipe?.rarity ?: 1,
                    startYear = slot.startYear,
                    startMonth = slot.startMonth,
                    duration = slot.duration,
                    status = when (slot.status) {
                        com.xianxia.sect.core.model.production.ProductionSlotStatus.WORKING -> ForgeSlotStatus.WORKING
                        com.xianxia.sect.core.model.production.ProductionSlotStatus.COMPLETED -> ForgeSlotStatus.FINISHED
                        else -> ForgeSlotStatus.IDLE
                    }
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allForgeRecipes: StateFlow<List<ForgeRecipeDatabase.ForgeRecipe>> = flow {
        emit(ForgeRecipeDatabase.getAllRecipes())
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())



    private val _battleTeamMoveMode = MutableStateFlow(false)
    val battleTeamMoveMode: StateFlow<Boolean> = _battleTeamMoveMode.asStateFlow()

    private val _battleTeamSlots = MutableStateFlow<List<BattleTeamSlot>>(buildList {
        repeat(2) { index ->
            add(BattleTeamSlot(index, slotType = BattleSlotType.ELDER))
        }
        repeat(8) { index ->
            add(BattleTeamSlot(index + 2, slotType = BattleSlotType.DISCIPLE))
        }
    })
    val battleTeamSlots: StateFlow<List<BattleTeamSlot>> = _battleTeamSlots.asStateFlow()

    private val _gameState = MutableStateFlow("LOADING")
    val gameState: StateFlow<String> = _gameState.asStateFlow()

    val isPaused: StateFlow<Boolean> = gameEngineCore.state
        .map { it.isPaused }
        .stateIn(viewModelScope, SharingStarted.Lazily, true)

    private val _gameLog = MutableStateFlow<List<String>>(emptyList())
    val gameLog: StateFlow<List<String>> = _gameLog.asStateFlow()

    private val _notifications = MutableStateFlow<List<String>>(emptyList())
    val notifications: StateFlow<List<String>> = _notifications.asStateFlow()

    init {
        _saveSlots.value = storageFacade.getSaveSlots()
        
        viewModelScope.launch {
            gameEngineCore.autoSaveTrigger.collect {
                try {
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

    private val _isTimeRunning = MutableStateFlow(false)
    val isTimeRunning: StateFlow<Boolean> = _isTimeRunning.asStateFlow()

    private fun startGameLoop() {
        gameEngineCore.startGameLoop()
        _isTimeRunning.value = true
        Log.d(TAG, "Game loop started via GameEngineCore")
    }

    private fun enqueueAutoSave(source: SavePipeline.SaveSource) {
        val lockAge = System.currentTimeMillis() - saveLockAcquireTime.get()
        if (saveLock.get() && saveLockAcquireTime.get() > 0 && lockAge > SAVE_LOCK_TIMEOUT_MS) {
            Log.e(TAG, "Save lock held for ${lockAge}ms, force releasing")
            saveLock.set(false)
        }

        if (!saveLock.compareAndSet(false, true)) {
            pendingAutoSave.set(true)
            Log.w(TAG, "Already saving, marking pending auto save (source=$source)")
            return
        }

        saveLockAcquireTime.set(System.currentTimeMillis())

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val snapshot = gameEngine.getStateSnapshot()
                val currentSlot = snapshot.gameData.currentSlot
                val updatedGameData = snapshot.gameData.copy(currentSlot = currentSlot)
                val saveData = trimSaveData(snapshot)

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

                if (currentSlot > 0) {
                    val playerRequest = SavePipeline.SaveRequest(
                        slot = currentSlot,
                        snapshot = snapshot,
                        source = source
                    )
                    savePipeline.enqueue(playerRequest)
                }

                gameEngine.updateGameData { updatedGameData }
                consecutiveSaveFailures.set(0)
                Log.d(TAG, "Auto save enqueued for slots: $autoSaveSlot, $currentSlot, source=$source")
            } catch (e: Exception) {
                val failures = consecutiveSaveFailures.incrementAndGet()
                Log.e(TAG, "Auto save failed (source=$source): ${e.message}", e)
                if (failures >= MAX_CONSECUTIVE_SAVE_FAILURES) {
                    _errorMessage.value = "自动保存连续失败，请手动保存或重启游戏"
                    consecutiveSaveFailures.set(0)
                }
            } finally {
                saveLock.set(false)
                if (pendingAutoSave.compareAndSet(true, false)) {
                    Log.d(TAG, "Processing pending auto save")
                    enqueueAutoSave(source)
                }
            }
        }
    }

    fun performAutoSave() {
        enqueueAutoSave(SavePipeline.SaveSource.AUTO)
    }

    /**
     * 创建当前游戏状态的存档数据
     * 用于紧急保存等场景
     * 注意：调用此函数前应确保游戏循环已暂停，避免锁竞争
     * @return 当前游戏状态的 SaveData 对象
     */
    suspend fun createSaveData(): SaveData {
        val snapshot = gameEngine.getStateSnapshot()
        return trimSaveData(snapshot)
    }
    
    /**
     * 创建当前游戏状态的存档数据（同步版本）
     * 用于崩溃处理等紧急场景，不使用协程锁，可能读取到不一致状态
     * 仅在紧急保存时使用，正常保存应使用 createSaveData()
     * @return 当前游戏状态的 SaveData 对象
     */
    fun createSaveDataSync(): SaveData {
        val snapshot = gameEngine.getStateSnapshotSync()
        return trimSaveData(snapshot)
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

    // 开始新游戏
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
                    _errorMessage.value = "保存失败，游戏已启动，请稍后手动保存"
                } else {
                    Log.d(TAG, "Game saved to slot $slot, elapsed=${System.currentTimeMillis() - startTime}ms")
                }
                
                _loadingProgress.value = PROGRESS_GAME_LOOP_START

                setSaveLoadState(isLoading = false, pendingSlot = slot, pendingAction = null)

                startGameLoop()
                Log.d(TAG, "Game loop started, isPaused=${isPaused.value}")
                
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

                _errorMessage.value = e.message ?: "开始新游戏失败"
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
                        _saveSlots.value = storageFacade.getSaveSlots()
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
                storageFacade.saveSyncWithResult(slot, saveData)
            }
            
            when {
                result == null -> {
                    Log.e(TAG, "performSynchronousSave TIMEOUT for slot $slot")
                    _errorMessage.value = "保存超时，请稍后手动保存"
                    false
                }
                result.isSuccess -> {
                    gameEngine.updateGameData { updatedGameData }
                    _saveSlots.value = storageFacade.getSaveSlots()
                    Log.i(TAG, "performSynchronousSave SUCCESS for slot $slot")
                    true
                }
                result is SaveResult.Failure && result.error == SaveError.KEY_DERIVATION_ERROR -> {
                    Log.e(TAG, "performSynchronousSave KEY_DERIVATION_ERROR for slot $slot")
                    _errorMessage.value = "密钥错误：${result.message}\n请尝试清除应用数据或联系支持"
                    false
                }
                result is SaveResult.Failure && result.error == SaveError.IO_ERROR -> {
                    Log.e(TAG, "performSynchronousSave IO_ERROR for slot $slot")
                    _errorMessage.value = "存储错误：${result.message}\n请检查存储空间或重启应用"
                    false
                }
                result is SaveResult.Failure -> {
                    Log.e(TAG, "performSynchronousSave FAILED for slot $slot: ${result.error} - ${result.message}")
                    _errorMessage.value = "保存失败，请稍后手动保存"
                    false
                }
                else -> {
                    Log.e(TAG, "performSynchronousSave FAILED for slot $slot: unknown error")
                    _errorMessage.value = "保存失败，请稍后手动保存"
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "performSynchronousSave ERROR: ${e.message}", e)
            _errorMessage.value = "保存错误：${e.message}"
            false
        }
    }

    fun isGameAlreadyLoaded(): Boolean {
        return _isGameLoaded && gameEngine.gameData.value?.sectName?.isNotEmpty() == true
    }

    fun loadGame(saveSlot: SaveSlot) {
        if (stateManager.state.value.isLoading) {
            Log.w(TAG, "Already loading, ignoring loadGame request")
            return
        }
        
        if (!canPerformSaveOperation()) {
            Log.e(TAG, "=== loadGame FAILED === insufficient memory")
            _errorMessage.value = "内存不足，无法读档。请关闭其他应用后重试。"
            return
        }
        
        Log.i(TAG, "=== loadGame BEGIN === slot=${saveSlot.slot}, sectName=${saveSlot.sectName}, " +
            "year=${saveSlot.gameYear}, month=${saveSlot.gameMonth}")
        val startTime = System.currentTimeMillis()
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // stateManager.setLoading(true) 由setSaveLoadState内部自动处理
                setSaveLoadState(isLoading = true, pendingSlot = saveSlot.slot, pendingAction = "load")

                if (_isGameLoaded) {
                    Log.i(TAG, "Game already loaded, will reload from slot ${saveSlot.slot}")
                    stopGameLoop()
                    _isGameLoaded = false
                }
                
                performGarbageCollection()
                
                Log.d(TAG, "Starting to load save data for slot ${saveSlot.slot}")
                val loadStartTime = System.currentTimeMillis()
                
                val saveData = withTimeoutOrNull(60_000L) {
                    try {
                        val data = storageFacade.loadSync(saveSlot.slot)
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
                    _errorMessage.value = if (elapsed >= 60_000L) "读档超时，请重试" else "存档为空或已损坏，请重试"
                    return@launch
                }
                
                val effectiveSlot = if (saveSlot.isAutoSave) {
                    saveData.gameData.currentSlot.let { if (it > 0) it else 1 }
                } else {
                    saveSlot.slot
                }
                storageFacade.setCurrentSlot(effectiveSlot)
                gameEngine.loadData(
                    gameData = saveData.gameData.copy(currentSlot = effectiveSlot),
                    disciples = saveData.disciples,
                    equipment = saveData.equipment,
                    manuals = saveData.manuals,
                    pills = saveData.pills,
                    materials = saveData.materials,
                    herbs = saveData.herbs,
                    seeds = saveData.seeds,
                    teams = saveData.teams,
                    events = saveData.events,
                    battleLogs = saveData.battleLogs,
                    alliances = saveData.alliances
                )
                
                // stateManager.setLoading(false) 已由setSaveLoadState内部自动处理
                setSaveLoadState(isLoading = false, pendingSlot = saveSlot.slot, pendingAction = null)
                startGameLoop()
                _isGameLoaded = true
                _successMessage.value = "读档成功"
                
                val gd = gameEngine.gameData.value
                Log.i(TAG, "=== loadGame SUCCESS === " +
                    "sectName=${gd.sectName}, year=${gd.gameYear}, month=${gd.gameMonth}, day=${gd.gameDay}, " +
                    "spiritStones=${gd.spiritStones}, disciples=${gameEngine.disciples.value.size}, " +
                    "equipment=${gameEngine.equipment.value.size}, manuals=${gameEngine.manuals.value.size}, " +
                    "elapsed=${System.currentTimeMillis() - startTime}ms")
            } catch (e: Exception) {
                Log.e(TAG, "=== loadGame FAILED === error=${e.message}", e)
                val partialGameData = gameEngine.gameData.value
                if (partialGameData.sectName.isNotEmpty() && gameEngine.disciples.value.isNotEmpty()) {
                    Log.w(TAG, "loadGame: Game data partially loaded, attempting to start game loop anyway")
                    try {
                        setSaveLoadState(isLoading = false, pendingSlot = saveSlot.slot, pendingAction = null)
                        // stateManager.setLoading(false) 将在finally中通过setSaveLoadState自动处理
                        startGameLoop()
                        _isGameLoaded = true
                    } catch (loopEx: Exception) {
                        Log.e(TAG, "loadGame: Failed to start game loop with partial data: ${loopEx.message}", loopEx)
                    }
                }
                _errorMessage.value = "加载游戏失败: ${e.message}"
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
            _errorMessage.value = "内存不足，无法读档。请关闭其他应用后重试。"
            return
        }
        
        Log.i(TAG, "=== loadGameFromSlot BEGIN === slot=$slot")
        val startTime = System.currentTimeMillis()
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 【修复】同步设置ViewModel层和StateManager层的loading状态
                // ViewModel层：用于UI显示（isLoading指示器）
                // StateManager层：由setSaveLoadState内部自动同步到stateManager
                setSaveLoadState(isLoading = true, pendingSlot = slot, pendingAction = "load")

                _loadingProgress.value = PROGRESS_START
                
                if (_isGameLoaded) {
                    Log.i(TAG, "Game already loaded, will reload from slot $slot")
                    stopGameLoop()
                    _isGameLoaded = false
                }
                
                performGarbageCollection()
                
                Log.d(TAG, "Starting to load save data for slot $slot")
                val loadStartTime = System.currentTimeMillis()
                
                val saveData = withTimeoutOrNull(60_000L) {
                    try {
                        val data = storageFacade.loadSync(slot)
                        Log.d(TAG, "Save data loaded in ${System.currentTimeMillis() - loadStartTime}ms")
                        data
                    } catch (e: Exception) {
                        Log.e(TAG, "Error loading save data: ${e.message}", e)
                        null
                    }
                }
                if (saveData != null) {
                    _loadingProgress.value = PROGRESS_DATA_LOAD
                    val effectiveSlot = if (slot == com.xianxia.sect.data.StorageConstants.AUTO_SAVE_SLOT) {
                        saveData.gameData.currentSlot.let { if (it > 0) it else 1 }
                    } else {
                        slot
                    }
                    storageFacade.setCurrentSlot(effectiveSlot)
                    gameEngine.loadData(
                        gameData = saveData.gameData.copy(currentSlot = effectiveSlot),
                        disciples = saveData.disciples,
                        equipment = saveData.equipment,
                        manuals = saveData.manuals,
                        pills = saveData.pills,
                        materials = saveData.materials,
                        herbs = saveData.herbs,
                        seeds = saveData.seeds,
                        teams = saveData.teams,
                        events = saveData.events,
                        battleLogs = saveData.battleLogs,
                        alliances = saveData.alliances
                    )
                    _loadingProgress.value = PROGRESS_GAME_LOOP_START

                    setSaveLoadState(isLoading = false, pendingSlot = slot, pendingAction = null)

                    // stateManager.setLoading(false) 已由setSaveLoadState内部自动处理
                    startGameLoop()
                    _isGameLoaded = true
                    _loadingProgress.value = PROGRESS_COMPLETE
                    
                    val gd = gameEngine.gameData.value
                    Log.i(TAG, "=== loadGameFromSlot SUCCESS === " +
                        "sectName=${gd.sectName}, year=${gd.gameYear}, month=${gd.gameMonth}, day=${gd.gameDay}, " +
                        "spiritStones=${gd.spiritStones}, disciples=${gameEngine.disciples.value.size}, " +
                        "equipment=${gameEngine.equipment.value.size}, manuals=${gameEngine.manuals.value.size}, " +
                        "elapsed=${System.currentTimeMillis() - startTime}ms")
                } else {
                    val elapsed = System.currentTimeMillis() - loadStartTime
                    Log.e(TAG, "=== loadGameFromSlot FAILED === timeout or null for slot $slot, elapsed=${elapsed}ms")
                    _errorMessage.value = if (elapsed >= 60_000L) "读档超时，请重试" else "存档为空或已损坏，请重试"
                }
            } catch (e: Exception) {
                Log.e(TAG, "=== loadGameFromSlot FAILED === error=${e.message}", e)

                // 【修复】增强异常处理：检查游戏数据是否部分加载
                // 如果loadData在中间步骤失败，gameData可能已经被部分初始化
                val partialGameData = gameEngine.gameData.value
                if (partialGameData.sectName.isNotEmpty() && gameEngine.disciples.value.isNotEmpty()) {
                    Log.w(TAG, "loadGameFromSlot: Game data partially loaded (sectName=${partialGameData.sectName}, disciples=${gameEngine.disciples.value.size}), attempting to start game loop anyway")
                    try {
                        setSaveLoadState(isLoading = false, pendingSlot = slot, pendingAction = null)
                        // stateManager.setLoading(false) 将在finally中通过setSaveLoadState自动处理
                        startGameLoop()
                        _isGameLoaded = true
                        Log.w(TAG, "loadGameFromSlot: Game loop started with partial data")
                    } catch (loopEx: Exception) {
                        Log.e(TAG, "loadGameFromSlot: Failed to start game loop with partial data: ${loopEx.message}", loopEx)
                    }
                }

                _errorMessage.value = "加载游戏失败: ${e.message}"
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
        if (!canPerformSaveOperation()) {
            Log.e(TAG, "=== saveGame FAILED === insufficient memory")
            _errorMessage.value = "内存不足，无法保存。请关闭其他应用后重试。"
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
                    _errorMessage.value = "保存操作繁忙，请稍后重试"
                    return@launch
                }

                val previousSlot = storageFacade.getCurrentSlot()
                storageFacade.setCurrentSlot(slot)

                try {
                    performGarbageCollection()

                    val snapshot = gameEngine.getStateSnapshot()
                    if (snapshot.gameData.sectName.isBlank()) {
                        Log.e(TAG, "=== saveGame FAILED === gameData not initialized (sectName is blank)")
                        storageFacade.setCurrentSlot(previousSlot)
                        _errorMessage.value = "游戏数据未初始化"
                        return@launch
                    }
                    val saveData = trimSaveData(snapshot)

                    val saveResult = withTimeoutOrNull(30_000L) {
                        storageFacade.saveSyncWithResult(slot, saveData)
                    }

                    if (saveResult != null && saveResult.isSuccess) {
                        _saveSlots.value = storageFacade.getSaveSlots()
                        _successMessage.value = "游戏保存成功"

                        Log.i(TAG, "=== saveGame SUCCESS === " +
                            "sectName=${snapshot.gameData.sectName}, year=${snapshot.gameData.gameYear}, " +
                            "month=${snapshot.gameData.gameMonth}, day=${snapshot.gameData.gameDay}, " +
                            "spiritStones=${snapshot.gameData.spiritStones}, " +
                            "disciples=${saveData.disciples.size}, equipment=${saveData.equipment.size}, " +
                            "manuals=${saveData.manuals.size}, elapsed=${System.currentTimeMillis() - startTime}ms")
                    } else {
                        storageFacade.setCurrentSlot(previousSlot)
                        val errorMsg = if (saveResult == null) "保存超时，请重试" else "保存失败，请重试"
                        _errorMessage.value = errorMsg
                        Log.e(TAG, "=== saveGame FAILED === ${if (saveResult == null) "timeout" else "save returned failure"}")
                    }
                } catch (e: OutOfMemoryError) {
                    Log.e(TAG, "=== saveGame FAILED === OutOfMemoryError", e)
                    storageFacade.setCurrentSlot(previousSlot)
                    _errorMessage.value = "内存不足，保存失败。请关闭其他应用后重试。"
                } catch (e: Exception) {
                    Log.e(TAG, "=== saveGame FAILED === error=${e.message}", e)
                    storageFacade.setCurrentSlot(previousSlot)
                    _errorMessage.value = "保存失败: ${e.message}"
                } finally {
                    saveLock.set(false)
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
            if (saveLock.compareAndSet(false, true)) return true
            delay(50)
        }
        return false
    }

    fun togglePause() {
        viewModelScope.launch {
            if (isPaused.value) {
                gameEngineCore.resume()
            } else {
                gameEngineCore.pause()
            }
        }
    }

    fun pauseGame() {
        viewModelScope.launch {
            gameEngineCore.pause()
        }
    }

    fun pauseAndSaveForBackground() {
        stopGameLoop()
        if (!_isGameLoaded) {
            Log.d(TAG, "pauseAndSaveForBackground: game not loaded, skipping")
            return
        }
        try {
            val snapshot = gameEngine.getStateSnapshotSync()
            val currentSlot = snapshot.gameData.currentSlot
            if (currentSlot <= 0) {
                Log.w(TAG, "pauseAndSaveForBackground: invalid currentSlot=$currentSlot, skipping")
                return
            }
            val autoSaveSlot = com.xianxia.sect.data.StorageConstants.AUTO_SAVE_SLOT
            val autoRequest = SavePipeline.SaveRequest(
                slot = autoSaveSlot,
                snapshot = snapshot,
                source = SavePipeline.SaveSource.AUTO
            )
            val autoEnqueued = savePipeline.enqueue(autoRequest)
            if (!autoEnqueued) {
                Log.w(TAG, "pauseAndSaveForBackground: auto save queue full, retrying after delay")
                viewModelScope.launch(Dispatchers.IO) {
                    kotlinx.coroutines.delay(2000)
                    savePipeline.enqueue(autoRequest)
                }
            }
            val playerRequest = SavePipeline.SaveRequest(
                slot = currentSlot,
                snapshot = snapshot,
                source = SavePipeline.SaveSource.AUTO
            )
            val playerEnqueued = savePipeline.enqueue(playerRequest)
            if (!playerEnqueued) {
                Log.w(TAG, "pauseAndSaveForBackground: player save queue full, retrying after delay")
                viewModelScope.launch(Dispatchers.IO) {
                    kotlinx.coroutines.delay(2000)
                    savePipeline.enqueue(playerRequest)
                }
            }
            Log.i(TAG, "pauseAndSaveForBackground: enqueued saves for slots $autoSaveSlot, $currentSlot")
        } catch (e: Exception) {
            Log.e(TAG, "pauseAndSaveForBackground error: ${e.message}", e)
        }
    }

    fun resumeGame() {
        viewModelScope.launch {
            gameEngineCore.resume()
        }
        if (!_isGameLoaded || stateManager.state.value.isLoading) {
            Log.d(TAG, "resumeGame: Skipping startGameLoop - isGameLoaded=$_isGameLoaded, isLoading=${stateManager.state.value.isLoading}")
            return
        }
        startGameLoop()
    }

    val timeSpeed: StateFlow<Int> = _timeScale

    fun setTimeSpeed(speed: Int) {
        _timeScale.value = speed
        if (isPaused.value) {
            viewModelScope.launch {
                gameEngineCore.resume()
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

    fun resetAllDisciplesStatus() {
        gameEngine.resetAllDisciplesStatus()
    }

    fun showSaveManagementDialog() {
        // ˢ�´浵��λ��Ϣ
        _saveSlots.value = storageFacade.getSaveSlots()
        openDialog(DialogType.Save)
    }

    fun dismissSaveDialog() {
        closeCurrentDialog()
    }

    fun restartGame() {
        if (!saveLock.compareAndSet(false, true)) {
            Log.w(TAG, "Already saving, ignoring restartGame request")
            return
        }
        
        if (stateManager.state.value.isLoading || _isRestarting.value) {
            Log.w(TAG, "Already loading or restarting, ignoring restartGame request")
            saveLock.set(false)
            return
        }
        
        if (!canPerformSaveOperation()) {
            Log.e(TAG, "=== restartGame FAILED === insufficient memory")
            _errorMessage.value = "内存不足，无法重置。请关闭其他应用后重试。"
            saveLock.set(false)
            return
        }
        
        viewModelScope.launch(Dispatchers.IO) {
            val wasRunning = _isTimeRunning.value
            var previousSlot = 1
            try {
                _isRestarting.value = true
                // stateManager.setLoading(true) 将在后续setSaveLoadState中自动处理
                
                if (wasRunning) {
                    val stopped = gameEngineCore.stopGameLoopAndWait(5000)
                    if (!stopped) {
                        Log.e(TAG, "Failed to stop game loop within timeout")
                        _errorMessage.value = "无法停止游戏循环，请重试"
                        return@launch
                    }
                    _isTimeRunning.value = false
                    Log.d(TAG, "Game loop stopped for restart operation")
                }
                
                performGarbageCollection()
                
                val currentData = gameEngine.gameData.value
                val sectName = currentData.sectName.ifBlank { "QingYunSect" }
                val currentSlot = currentData.currentSlot.coerceAtLeast(1)
                previousSlot = storageFacade.getCurrentSlot()
                
                Log.i(TAG, "=== restartGame BEGIN === currentSlot=$currentSlot, previousSlot=$previousSlot, sectName=$sectName")
                
                storageFacade.setCurrentSlot(currentSlot)
                
                gameEngine.restartGameSuspend(sectName, currentSlot)

                setSaveLoadState(isSaving = true, pendingSlot = currentSlot, pendingAction = "save")
                
                val saveSuccess = performRestartSave(currentSlot, previousSlot)
                
                if (saveSuccess) {
                    Log.i(TAG, "=== restartGame SAVE SUCCESS === slot=$currentSlot")
                    _successMessage.value = "游戏已重置"
                } else {
                    Log.e(TAG, "=== restartGame SAVE FAILED === slot=$currentSlot")
                    _errorMessage.value = "游戏已重置，但保存失败，请手动保存"
                }
                
                setSaveLoadState(isSaving = false, pendingSlot = null, pendingAction = null)
                // stateManager.setLoading(false) 由setSaveLoadState内部自动处理
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "=== restartGame FAILED === OutOfMemoryError", e)
                storageFacade.setCurrentSlot(previousSlot)
                _errorMessage.value = "内存不足，重置失败。请关闭其他应用后重试。"
                setSaveLoadState(isSaving = false, pendingSlot = null, pendingAction = null)
                // stateManager.setLoading(false) 由setSaveLoadState内部自动处理
            } catch (e: Exception) {
                Log.e(TAG, "=== restartGame FAILED === error=${e.message}", e)
                storageFacade.setCurrentSlot(previousSlot)
                _errorMessage.value = e.message ?: "重置游戏失败"
                setSaveLoadState(isSaving = false, pendingSlot = null, pendingAction = null)
                // stateManager.setLoading(false) 由setSaveLoadState内部自动处理
            } finally {
                _isRestarting.value = false
                saveLock.set(false)
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
                    equipment = snapshot.equipment,
                    manuals = snapshot.manuals,
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
                    storageFacade.saveSync(slot, saveData)
                }
                
                when (success) {
                    true -> {
                        _saveSlots.value = storageFacade.getSaveSlots()
                        Log.i(TAG, "performRestartSave success for slot $slot")
                        true
                    }
                    null -> {
                        Log.e(TAG, "performRestartSave timeout for slot $slot")
                        storageFacade.setCurrentSlot(previousSlot)
                        if (storageFacade.isSaveCorrupted(slot)) {
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



    // 建筑详情对话框 (带参数)
    fun showBuildingDetailDialog(buildingId: String) {
        _selectedBuildingId.value = buildingId
        openDialog(DialogType.BuildingDetail, mapOf("buildingId" to buildingId))
    }

    fun openBuildingDetailDialog(buildingId: String) {
        _selectedBuildingId.value = buildingId
        openDialog(DialogType.BuildingDetail, mapOf("buildingId" to buildingId))
    }

    fun closeBuildingDetailDialog() {
        closeCurrentDialog()
    }
    
    fun setViceSectMaster(discipleId: String) {
        viewModelScope.launch {
            val disciple = disciples.value.find { it.id == discipleId }
            if (disciple == null) {
                _errorMessage.value = "弟子不存在"
                return@launch
            }

            if (disciple.realm > REALM_VICE_SECT_MASTER) {
                _errorMessage.value = "副宗主需要达到炼虚境界"
                return@launch
            }

            val currentSlots = gameEngine.gameData.value.elderSlots
            val allElderIds = currentSlots.getAllElderIds()

            if (!allElderIds.contains(discipleId)) {
                _errorMessage.value = "副宗主需要由长老担任"
                return@launch
            }

            gameEngine.updateGameData {
                it.copy(elderSlots = it.elderSlots.copy(viceSectMaster = discipleId))
            }
        }
    }

    fun removeViceSectMaster() {
        viewModelScope.launch {
            gameEngine.updateGameData {
                it.copy(elderSlots = it.elderSlots.copy(viceSectMaster = ""))
            }
        }
    }
    
    fun improveSectRelation(sectId: String, action: String) {
        viewModelScope.launch {
            try {
                gameEngine.interactWithSect(sectId, action)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "操作失败"
            }
        }
    }
    
    fun formAlliance(sectId: String) {
        openAllianceDialog(sectId)
    }

    private val _showSectTradeDialog = MutableStateFlow(false)
    val showSectTradeDialog: StateFlow<Boolean> = _showSectTradeDialog.asStateFlow()

    private val _selectedTradeSectId = MutableStateFlow<String?>(null)
    val selectedTradeSectId: StateFlow<String?> = _selectedTradeSectId.asStateFlow()

    private val _sectTradeItems = MutableStateFlow<List<MerchantItem>>(emptyList())
    val sectTradeItems: StateFlow<List<MerchantItem>> = _sectTradeItems.asStateFlow()

    fun openSectTradeDialog(sectId: String) {
        _selectedTradeSectId.value = sectId
        _sectTradeItems.value = gameEngine.getOrRefreshSectTradeItems(sectId)
        _showSectTradeDialog.value = true
    }

    fun closeSectTradeDialog() {
        _showSectTradeDialog.value = false
        _selectedTradeSectId.value = null
    }
    
    // 送礼相关状态
    private val _showGiftDialog = MutableStateFlow(false)
    val showGiftDialog: StateFlow<Boolean> = _showGiftDialog.asStateFlow()
    
    private val _selectedGiftSectId = MutableStateFlow<String?>(null)
    val selectedGiftSectId: StateFlow<String?> = _selectedGiftSectId.asStateFlow()
    
    fun openGiftDialog(sectId: String) {
        _selectedGiftSectId.value = sectId
        _showGiftDialog.value = true
    }
    
    fun closeGiftDialog() {
        _showGiftDialog.value = false
        _selectedGiftSectId.value = null
    }
    
    private val _showScoutDialog = MutableStateFlow(false)
    val showScoutDialog: StateFlow<Boolean> = _showScoutDialog.asStateFlow()
    
    private val _selectedScoutSectId = MutableStateFlow<String?>(null)
    val selectedScoutSectId: StateFlow<String?> = _selectedScoutSectId.asStateFlow()
    
    fun openScoutDialog(sectId: String) {
        _selectedScoutSectId.value = sectId
        _showScoutDialog.value = true
    }
    
    fun closeScoutDialog() {
        _showScoutDialog.value = false
        _selectedScoutSectId.value = null
    }
    
    fun startScoutMission(memberIds: List<String>, sectId: String) {
        val data = gameData.value
        val targetSect = data.worldMapSects.find { it.id == sectId }
        if (targetSect != null) {
            gameEngine.startScoutMission(memberIds, targetSect, data.gameYear, data.gameMonth, data.gameDay)
            closeScoutDialog()
        }
    }
    
    fun getEligibleScoutDisciples(): List<DiscipleAggregate> {
        return disciples.value.filter { 
            it.isAlive && it.status == DiscipleStatus.IDLE 
        }
    }
    
    fun giftSpiritStones(sectId: String, tier: Int) {
        viewModelScope.launch {
            try {
                gameEngine.giftSpiritStones(sectId, tier)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "送礼失败"
            }
        }
    }
    
    fun giftItem(sectId: String, itemId: String, itemType: String, quantity: Int) {
        viewModelScope.launch {
            try {
                gameEngine.giftItem(sectId, itemId, itemType, quantity)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "送礼失败"
            }
        }
    }

    // ==================== 结盟系统 ====================
    
    private val _showAllianceDialog = MutableStateFlow(false)
    val showAllianceDialog: StateFlow<Boolean> = _showAllianceDialog.asStateFlow()
    
    private val _selectedAllianceSectId = MutableStateFlow<String?>(null)
    val selectedAllianceSectId: StateFlow<String?> = _selectedAllianceSectId.asStateFlow()
    
    private val _showEnvoyDiscipleSelectDialog = MutableStateFlow(false)
    val showEnvoyDiscipleSelectDialog: StateFlow<Boolean> = _showEnvoyDiscipleSelectDialog.asStateFlow()
    
    fun openAllianceDialog(sectId: String) {
        _selectedAllianceSectId.value = sectId
        _showAllianceDialog.value = true
    }
    
    fun closeAllianceDialog() {
        _showAllianceDialog.value = false
        _selectedAllianceSectId.value = null
    }
    
    fun openEnvoyDiscipleSelectDialog() {
        _showEnvoyDiscipleSelectDialog.value = true
    }
    
    fun closeEnvoyDiscipleSelectDialog() {
        _showEnvoyDiscipleSelectDialog.value = false
    }

    private val _showOuterTournamentDialog = MutableStateFlow(false)
    val showOuterTournamentDialog: StateFlow<Boolean> = _showOuterTournamentDialog.asStateFlow()

    private var _isOuterTournamentManuallyClosed = false

    fun openOuterTournamentDialog() {
        if (_isOuterTournamentManuallyClosed) {
            return
        }
        _showOuterTournamentDialog.value = true
    }

    fun resetOuterTournamentClosedFlag() {
        _isOuterTournamentManuallyClosed = false
    }

    fun closeOuterTournamentDialog() {
        _isOuterTournamentManuallyClosed = true
        _showOuterTournamentDialog.value = false
        viewModelScope.launch {
            gameEngine.updateGameData { data ->
                data.copy(pendingCompetitionResults = emptyList())
            }
        }
    }

    fun promoteSelectedDisciplesToInner(selectedDiscipleIds: Set<String>) {
        viewModelScope.launch {
            try {
                val promotedToInnerIds = mutableListOf<String>()
                selectedDiscipleIds.forEach { discipleId ->
                    gameEngine.updateDisciple(discipleId) { disciple ->
                        if (disciple.discipleType == "outer") {
                            promotedToInnerIds.add(discipleId)
                            disciple.copy(discipleType = "inner")
                        } else {
                            disciple
                        }
                    }
                }

                if (promotedToInnerIds.isNotEmpty()) {
                    val currentSpiritMineSlots = gameEngine.gameData.value.spiritMineSlots
                    var slotsChanged = false
                    val updatedSpiritMineSlots = currentSpiritMineSlots.map { slot ->
                        if (slot.discipleId in promotedToInnerIds) {
                            slotsChanged = true
                            slot.copy(discipleId = "", discipleName = "")
                        } else {
                            slot
                        }
                    }
                    if (slotsChanged) {
                        gameEngine.updateGameData { it.copy(spiritMineSlots = updatedSpiritMineSlots) }
                    }
                    gameEngine.syncAllDiscipleStatuses()
                }

                closeOuterTournamentDialog()
            } catch (e: Exception) {
                _errorMessage.value = "晋升弟子失败: ${e.message}"
                closeOuterTournamentDialog()
            }
        }
    }
    
    fun requestAlliance(sectId: String, envoyDiscipleId: String) {
        viewModelScope.launch {
            try {
                val (success, message) = gameEngine.requestAlliance(sectId, envoyDiscipleId)
                if (success) {
                    closeEnvoyDiscipleSelectDialog()
                    closeAllianceDialog()
                } else {
                    _errorMessage.value = message
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "结盟失败"
            }
        }
    }
    
    fun dissolveAlliance(sectId: String) {
        viewModelScope.launch {
            try {
                val (success, message) = gameEngine.dissolveAlliance(sectId)
                if (success) {
                    closeAllianceDialog()
                } else {
                    _errorMessage.value = message
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "解除结盟失败"
            }
        }
    }
    
    fun getEligibleEnvoyDisciples(sectLevel: Int): List<DiscipleAggregate> {
        val requiredRealm = gameEngine.getEnvoyRealmRequirement(sectLevel)
        return disciples.value.filter { 
            it.isAlive && 
            it.status == DiscipleStatus.IDLE &&
            it.realm <= requiredRealm
        }
    }
    
    fun getAllianceCost(sectLevel: Int): Long {
        return gameEngine.getAllianceCost(sectLevel)
    }
    
    fun isAlly(sectId: String): Boolean {
        return gameEngine.isAlly(sectId)
    }
    
    fun getAllianceRemainingYears(sectId: String): Int {
        return gameEngine.getAllianceRemainingYears(sectId)
    }
    
    fun getPlayerAllies(): List<WorldSect> {
        val allyIds = gameEngine.getPlayerAllies()
        val data = gameData.value
        return data.worldMapSects.filter { allyIds.contains(it.id) }
    }

    fun buyFromSectTrade(itemId: String, quantity: Int = 1) {
        viewModelScope.launch {
            try {
                val sectId = _selectedTradeSectId.value ?: return@launch
                gameEngine.buyFromSectTrade(sectId, itemId, quantity)
                _sectTradeItems.value = gameEngine.getOrRefreshSectTradeItems(sectId)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "购买失败"
            }
        }
    }

    fun openSalaryConfigDialog() {
        openDialog(DialogType.SalaryConfig)
    }

    fun closeSalaryConfigDialog() {
        closeCurrentDialog()
    }

    private val _showRedeemCodeDialog = MutableStateFlow(false)
    val showRedeemCodeDialog: StateFlow<Boolean> = _showRedeemCodeDialog.asStateFlow()

    private val _redeemResult = MutableStateFlow<RedeemResult?>(null)
    val redeemResult: StateFlow<RedeemResult?> = _redeemResult.asStateFlow()

    fun openRedeemCodeDialog() {
        _showRedeemCodeDialog.value = true
        _redeemResult.value = null
    }

    fun closeRedeemCodeDialog() {
        _showRedeemCodeDialog.value = false
        _redeemResult.value = null
    }

    fun redeemCode(code: String) {
        viewModelScope.launch {
            try {
                val currentGameData = gameEngine.gameData.value
                val result = gameEngine.redeemCode(
                    code = code,
                    usedCodes = currentGameData.usedRedeemCodes,
                    currentYear = currentGameData.gameYear,
                    currentMonth = currentGameData.gameMonth
                )
                _redeemResult.value = result
                if (result.success) {
                    _successMessage.value = result.message
                } else {
                    _errorMessage.value = result.message
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error redeeming code", e)
                _errorMessage.value = "兑换失败：${e.message}"
            }
        }
    }

    fun clearRedeemResult() {
        _redeemResult.value = null
    }

    // ������Ի��򷽷�?
    fun openSectManagementDialog() {
        openDialog(DialogType.SectManagement)
    }

    fun closeSectManagementDialog() {
        closeCurrentDialog()
    }

    fun assignElder(slotType: String, discipleId: String) {
        viewModelScope.launch {
            try {
                val disciple = disciples.value.find { it.id == discipleId }
                if (disciple == null) {
                    _errorMessage.value = "弟子不存在"
                    return@launch
                }
                
                val minRealm = when (slotType) {
                    "viceSectMaster" -> 4
                    "lawEnforcementElder" -> 5
                    else -> 6
                }
                if (disciple.realm > minRealm) {
                    val realmName = when (minRealm) {
                        4 -> "炼虚"
                        5 -> "化神"
                        6 -> "元婴"
                        else -> "元婴"
                    }
                    val positionName = when (slotType) {
                        "viceSectMaster" -> "副宗主"
                        else -> "长老"
                    }
                    _errorMessage.value = "${positionName}需要达到${realmName}境界"
                    return@launch
                }
                
                val currentGameData = gameEngine.gameData.value
                val elderSlots = currentGameData.elderSlots
                
                val allElderIds = elderSlots.getAllElderIds()
                val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

                if (allElderIds.contains(discipleId)) {
                    _errorMessage.value = "该弟子已担任长老职位"
                    return@launch
                }

                if (allDirectDiscipleIds.contains(discipleId)) {
                    _errorMessage.value = "该弟子已是其他长老的亲传弟子"
                    return@launch
                }
                
                val newElderSlots = when (slotType) {
                    "herbGarden" -> elderSlots.copy(
                        herbGardenElder = discipleId,
                        herbGardenDisciples = emptyList()
                    )
                    "alchemy" -> elderSlots.copy(
                        alchemyElder = discipleId,
                        alchemyDisciples = emptyList()
                    )
                    "forge" -> elderSlots.copy(
                        forgeElder = discipleId,
                        forgeDisciples = emptyList()
                    )
                    "viceSectMaster" -> elderSlots.copy(
                        viceSectMaster = discipleId
                    )
                    "outerElder" -> elderSlots.copy(
                        outerElder = discipleId
                    )
                    "preachingElder" -> elderSlots.copy(
                        preachingElder = discipleId,
                        preachingMasters = emptyList()
                    )
                    "lawEnforcementElder" -> elderSlots.copy(
                        lawEnforcementElder = discipleId
                    )
                    "innerElder" -> elderSlots.copy(
                        innerElder = discipleId
                    )
                    "qingyunPreachingElder" -> elderSlots.copy(
                        qingyunPreachingElder = discipleId,
                        qingyunPreachingMasters = emptyList()
                    )
                    else -> elderSlots
                }
                gameEngine.updateElderSlots(newElderSlots)
                gameEngine.syncAllDiscipleStatuses()
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "任命失败"
            }
        }
    }

    fun removeElder(slotType: String) {
        viewModelScope.launch {
            try {
                val currentGameData = gameEngine.gameData.value
                val elderSlots = currentGameData.elderSlots
                val newElderSlots = when (slotType) {
                    "herbGarden" -> elderSlots.copy(
                        herbGardenElder = "",
                        herbGardenDisciples = emptyList()
                    )
                    "alchemy" -> elderSlots.copy(
                        alchemyElder = "",
                        alchemyDisciples = emptyList()
                    )
                    "forge" -> elderSlots.copy(
                        forgeElder = "",
                        forgeDisciples = emptyList()
                    )
                    "viceSectMaster" -> elderSlots.copy(
                        viceSectMaster = ""
                    )
                    "outerElder" -> elderSlots.copy(
                        outerElder = ""
                    )
                    "preachingElder" -> elderSlots.copy(
                        preachingElder = "",
                        preachingMasters = emptyList()
                    )
                    "lawEnforcementElder" -> elderSlots.copy(
                        lawEnforcementElder = ""
                    )
                    "innerElder" -> elderSlots.copy(
                        innerElder = ""
                    )
                    "qingyunPreachingElder" -> elderSlots.copy(
                        qingyunPreachingElder = "",
                        qingyunPreachingMasters = emptyList()
                    )
                    else -> elderSlots
                }
                gameEngine.updateElderSlots(newElderSlots)
                gameEngine.syncAllDiscipleStatuses()
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "卸任失败"
            }
        }
    }

    fun getViceSectMaster(): DiscipleAggregate? {
        val viceSectMasterId = gameEngine.gameData.value?.elderSlots?.viceSectMaster
        return getElderDisciple(viceSectMasterId)
    }

    fun toggleSpiritMineBoost(): Boolean {
        val currentGameData = gameEngine.gameData.value ?: return false
        viewModelScope.launch {
            gameEngine.updateGameData { it.copy(sectPolicies = it.sectPolicies.copy(spiritMineBoost = !it.sectPolicies.spiritMineBoost)) }
        }
        return true
    }

    fun isSpiritMineBoostEnabled(): Boolean {
        return gameEngine.gameData.value?.sectPolicies?.spiritMineBoost ?: false
    }

    fun getSpiritMineBoostEffect(): Double {
        return GameConfig.PolicyConfig.SPIRIT_MINE_BOOST_BASE_EFFECT
    }

    fun toggleEnhancedSecurity(): Boolean {
        val currentGameData = gameEngine.gameData.value ?: return false
        val currentPolicies = currentGameData.sectPolicies
        val requiredStones = GameConfig.PolicyConfig.ENHANCED_SECURITY_COST

        if (!currentPolicies.enhancedSecurity) {
            viewModelScope.launch {
                gameEngine.updateGameData {
                    if (it.spiritStones < requiredStones) {
                        _errorMessage.value = "灵石不足${requiredStones}，无法开启增强治安政策"
                        it
                    } else {
                        it.copy(
                            spiritStones = it.spiritStones - requiredStones,
                            sectPolicies = it.sectPolicies.copy(enhancedSecurity = true)
                        )
                    }
                }
            }
        } else {
            viewModelScope.launch {
                gameEngine.updateGameData { it.copy(sectPolicies = it.sectPolicies.copy(enhancedSecurity = false)) }
            }
        }
        return true
    }

    fun isEnhancedSecurityEnabled(): Boolean {
        return gameEngine.gameData.value?.sectPolicies?.enhancedSecurity ?: false
    }

    fun getEnhancedSecurityBaseBonus(): Double {
        return GameConfig.PolicyConfig.ENHANCED_SECURITY_BASE_EFFECT
    }

    fun toggleAlchemyIncentive(): Boolean {
        val currentGameData = gameEngine.gameData.value ?: return false
        val currentPolicies = currentGameData.sectPolicies
        val requiredStones = GameConfig.PolicyConfig.ALCHEMY_INCENTIVE_COST

        if (!currentPolicies.alchemyIncentive) {
            viewModelScope.launch {
                gameEngine.updateGameData {
                    if (it.spiritStones < requiredStones) {
                        _errorMessage.value = "灵石不足${requiredStones}，无法开启丹道激励政策"
                        it
                    } else {
                        it.copy(
                            spiritStones = it.spiritStones - requiredStones,
                            sectPolicies = it.sectPolicies.copy(alchemyIncentive = true)
                        )
                    }
                }
            }
        } else {
            viewModelScope.launch {
                gameEngine.updateGameData { it.copy(sectPolicies = it.sectPolicies.copy(alchemyIncentive = false)) }
            }
        }
        return true
    }

    fun isAlchemyIncentiveEnabled(): Boolean {
        return gameEngine.gameData.value?.sectPolicies?.alchemyIncentive ?: false
    }

    fun toggleForgeIncentive(): Boolean {
        val currentGameData = gameEngine.gameData.value ?: return false
        val currentPolicies = currentGameData.sectPolicies
        val requiredStones = GameConfig.PolicyConfig.FORGE_INCENTIVE_COST

        if (!currentPolicies.forgeIncentive) {
            viewModelScope.launch {
                gameEngine.updateGameData {
                    if (it.spiritStones < requiredStones) {
                        _errorMessage.value = "灵石不足${requiredStones}，无法开启锻造激励政策"
                        it
                    } else {
                        it.copy(
                            spiritStones = it.spiritStones - requiredStones,
                            sectPolicies = it.sectPolicies.copy(forgeIncentive = true)
                        )
                    }
                }
            }
        } else {
            viewModelScope.launch {
                gameEngine.updateGameData { it.copy(sectPolicies = it.sectPolicies.copy(forgeIncentive = false)) }
            }
        }
        return true
    }

    fun isForgeIncentiveEnabled(): Boolean {
        return gameEngine.gameData.value?.sectPolicies?.forgeIncentive ?: false
    }

    fun toggleHerbCultivation(): Boolean {
        val currentGameData = gameEngine.gameData.value ?: return false
        val currentPolicies = currentGameData.sectPolicies
        val requiredStones = GameConfig.PolicyConfig.HERB_CULTIVATION_COST

        if (!currentPolicies.herbCultivation) {
            viewModelScope.launch {
                gameEngine.updateGameData {
                    if (it.spiritStones < requiredStones) {
                        _errorMessage.value = "灵石不足${requiredStones}，无法开启灵药培育政策"
                        it
                    } else {
                        it.copy(
                            spiritStones = it.spiritStones - requiredStones,
                            sectPolicies = it.sectPolicies.copy(herbCultivation = true)
                        )
                    }
                }
            }
        } else {
            viewModelScope.launch {
                gameEngine.updateGameData { it.copy(sectPolicies = it.sectPolicies.copy(herbCultivation = false)) }
            }
        }
        return true
    }

    fun isHerbCultivationEnabled(): Boolean {
        return gameEngine.gameData.value?.sectPolicies?.herbCultivation ?: false
    }

    fun toggleCultivationSubsidy(): Boolean {
        val currentGameData = gameEngine.gameData.value ?: return false
        val currentPolicies = currentGameData.sectPolicies
        val requiredStones = GameConfig.PolicyConfig.CULTIVATION_SUBSIDY_COST

        if (!currentPolicies.cultivationSubsidy) {
            viewModelScope.launch {
                gameEngine.updateGameData {
                    if (it.spiritStones < requiredStones) {
                        _errorMessage.value = "灵石不足${requiredStones}，无法开启修行津贴政策"
                        it
                    } else {
                        it.copy(
                            spiritStones = it.spiritStones - requiredStones,
                            sectPolicies = it.sectPolicies.copy(cultivationSubsidy = true)
                        )
                    }
                }
            }
        } else {
            viewModelScope.launch {
                gameEngine.updateGameData { it.copy(sectPolicies = it.sectPolicies.copy(cultivationSubsidy = false)) }
            }
        }
        return true
    }

    fun isCultivationSubsidyEnabled(): Boolean {
        return gameEngine.gameData.value?.sectPolicies?.cultivationSubsidy ?: false
    }

    fun toggleManualResearch(): Boolean {
        val currentGameData = gameEngine.gameData.value ?: return false
        val currentPolicies = currentGameData.sectPolicies
        val requiredStones = GameConfig.PolicyConfig.MANUAL_RESEARCH_COST

        if (!currentPolicies.manualResearch) {
            viewModelScope.launch {
                gameEngine.updateGameData {
                    if (it.spiritStones < requiredStones) {
                        _errorMessage.value = "灵石不足${requiredStones}，无法开启功法研习政策"
                        it
                    } else {
                        it.copy(
                            spiritStones = it.spiritStones - requiredStones,
                            sectPolicies = it.sectPolicies.copy(manualResearch = true)
                        )
                    }
                }
            }
        } else {
            viewModelScope.launch {
                gameEngine.updateGameData { it.copy(sectPolicies = it.sectPolicies.copy(manualResearch = false)) }
            }
        }
        return true
    }

    fun isManualResearchEnabled(): Boolean {
        return gameEngine.gameData.value?.sectPolicies?.manualResearch ?: false
    }

    fun getViceSectMasterIntelligenceBonus(): Double {
        val viceSectMaster = getViceSectMaster() ?: return 0.0
        val intelligence = viceSectMaster.intelligence
        val baseIntelligence = GameConfig.PolicyConfig.VICE_SECT_MASTER_INTELLIGENCE_BASE
        val step = GameConfig.PolicyConfig.VICE_SECT_MASTER_INTELLIGENCE_STEP
        val bonusPerStep = GameConfig.PolicyConfig.VICE_SECT_MASTER_INTELLIGENCE_BONUS_PER_STEP
        return ((intelligence - baseIntelligence) / step.toDouble() * bonusPerStep).coerceAtLeast(0.0)
    }

    fun getElderDisciple(elderId: String?): DiscipleAggregate? {
        if (elderId == null) return null
        return discipleAggregates.value.find { it.id == elderId }
    }
    
    fun assignDirectDisciple(elderSlotType: String, slotIndex: Int, discipleId: String) {
        viewModelScope.launch {
            try {
                val disciple = disciples.value.find { it.id == discipleId }
                if (disciple == null) {
                    _errorMessage.value = "弟子不存在"
                    return@launch
                }
                
                val currentGameData = gameEngine.gameData.value
                val elderSlots = currentGameData.elderSlots
                
                val allElderIds = elderSlots.getAllElderIds()
                val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

                if (allElderIds.contains(discipleId)) {
                    _errorMessage.value = "该弟子已担任长老职位"
                    return@launch
                }

                if (allDirectDiscipleIds.contains(discipleId)) {
                    _errorMessage.value = "该弟子已是其他长老的亲传弟子"
                    return@launch
                }

                gameEngine.assignDirectDisciple(
                    elderSlotType = elderSlotType,
                    slotIndex = slotIndex,
                    discipleId = discipleId,
                    discipleName = disciple.name,
                    discipleRealm = disciple.realmName,
                    discipleSpiritRootColor = disciple.spiritRoot.countColor
                )
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "分配失败"
            }
        }
    }
    
    fun removeDirectDisciple(elderSlotType: String, slotIndex: Int) {
        viewModelScope.launch {
            try {
                gameEngine.removeDirectDisciple(elderSlotType, slotIndex)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "卸任失败"
            }
        }
    }

    fun getOuterElder(): DiscipleAggregate? {
        val outerElderId = gameEngine.gameData.value?.elderSlots?.outerElder
        return getElderDisciple(outerElderId)
    }

    fun getPreachingElder(): DiscipleAggregate? {
        val preachingElderId = gameEngine.gameData.value?.elderSlots?.preachingElder
        return getElderDisciple(preachingElderId)
    }

    fun getPreachingMasters(): List<DirectDiscipleSlot> {
        return gameEngine.gameData.value?.elderSlots?.preachingMasters ?: emptyList()
    }

    fun getLawEnforcementElder(): DiscipleAggregate? {
        val elderId = gameEngine.gameData.value?.elderSlots?.lawEnforcementElder
        return getElderDisciple(elderId)
    }

    fun getLawEnforcementDisciples(): List<DirectDiscipleSlot> {
        return gameEngine.gameData.value?.elderSlots?.lawEnforcementDisciples ?: emptyList()
    }

    fun getLawEnforcementReserveDisciples(): List<DirectDiscipleSlot> {
        return gameEngine.gameData.value?.elderSlots?.lawEnforcementReserveDisciples ?: emptyList()
    }

    fun getLawEnforcementReserveDisciplesWithInfo(): List<DiscipleAggregate> {
        val reserveSlots = gameEngine.gameData.value?.elderSlots?.lawEnforcementReserveDisciples ?: emptyList()
        val reserveIds = reserveSlots.mapNotNull { it.discipleId }.toSet()
        return discipleAggregates.value
            .filter { it.id in reserveIds }
            .sortedByDescending { it.intelligence }
    }

    fun addReserveDisciple(discipleId: String) {
        viewModelScope.launch {
            try {
                val disciple = disciples.value.find { it.id == discipleId }
                if (disciple == null) {
                    _errorMessage.value = "弟子不存在"
                    return@launch
                }
                
                if (hasDisciplePosition(discipleId)) {
                    val position = getDisciplePosition(discipleId)
                    _errorMessage.value = "该弟子已担任${position}，不可同时担任多个职务"
                    return@launch
                }
                
                if (isReserveDisciple(discipleId)) {
                    _errorMessage.value = "该弟子已是其他部门的储备弟子"
                    return@launch
                }
                
                val currentReserveDisciples = gameEngine.gameData.value?.elderSlots?.lawEnforcementReserveDisciples ?: emptyList()
                if (currentReserveDisciples.any { it.discipleId == discipleId }) {
                    _errorMessage.value = "该弟子已是储备弟子"
                    return@launch
                }

                val newIndex = if (currentReserveDisciples.isEmpty()) 0 else currentReserveDisciples.maxOf { it.index } + 1
                val newSlot = DirectDiscipleSlot(
                    index = newIndex,
                    discipleId = discipleId,
                    discipleName = disciple.name,
                    discipleRealm = disciple.realmName,
                    discipleSpiritRootColor = disciple.spiritRoot.countColor
                )

                val updatedReserveDisciples = currentReserveDisciples + newSlot
                gameEngine.updateGameData { it.copy(elderSlots = it.elderSlots.copy(lawEnforcementReserveDisciples = updatedReserveDisciples)) }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "添加失败"
            }
        }
    }

    fun addReserveDisciples(discipleIds: List<String>) {
        viewModelScope.launch {
            try {
                val currentReserveDisciples = gameEngine.gameData.value?.elderSlots?.lawEnforcementReserveDisciples ?: emptyList()
                val existingIds = currentReserveDisciples.mapNotNull { it.discipleId }.toSet()
                
                val newSlots = mutableListOf<DirectDiscipleSlot>()
                var nextIndex = if (currentReserveDisciples.isEmpty()) 0 else currentReserveDisciples.maxOf { it.index } + 1
                
                for (discipleId in discipleIds) {
                    if (discipleId in existingIds) continue
                    
                    val disciple = disciples.value.find { it.id == discipleId }
                    if (disciple == null) continue
                    
                    if (hasDisciplePosition(discipleId)) continue
                    
                    if (isReserveDisciple(discipleId)) continue
                    
                    newSlots.add(
                        DirectDiscipleSlot(
                            index = nextIndex,
                            discipleId = discipleId,
                            discipleName = disciple.name,
                            discipleRealm = disciple.realmName,
                            discipleSpiritRootColor = disciple.spiritRoot.countColor
                        )
                    )
                    nextIndex++
                }
                
                if (newSlots.isNotEmpty()) {
                    val updatedReserveDisciples = currentReserveDisciples + newSlots
                    gameEngine.updateGameData { it.copy(elderSlots = it.elderSlots.copy(lawEnforcementReserveDisciples = updatedReserveDisciples)) }
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "添加失败"
            }
        }
    }

    fun removeReserveDisciple(discipleId: String) {
        viewModelScope.launch {
            try {
                val currentReserveDisciples = gameEngine.gameData.value?.elderSlots?.lawEnforcementReserveDisciples ?: emptyList()
                val updatedReserveDisciples = currentReserveDisciples.filter { it.discipleId != discipleId }
                gameEngine.updateGameData { it.copy(elderSlots = it.elderSlots.copy(lawEnforcementReserveDisciples = updatedReserveDisciples)) }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "移除失败"
            }
        }
    }

    private fun isSelectableDisciple(disciple: DiscipleAggregate): Boolean {
        return disciple.isAlive &&
               disciple.discipleType == "inner" &&
               disciple.age >= 5 &&
               disciple.realmLayer > 0 &&
               disciple.status == DiscipleStatus.IDLE
    }

    fun getAvailableDisciplesForSelection(): List<DiscipleAggregate> {
        return disciples.value.filter { disciple ->
            disciple.isAlive &&
            disciple.status == DiscipleStatus.IDLE &&
            disciple.realmLayer > 0
        }
    }

    private fun ElderSlots.getAllElderIds(): List<String> {
        return listOf(
            viceSectMaster,
            herbGardenElder,
            alchemyElder,
            forgeElder,
            outerElder,
            preachingElder,
            lawEnforcementElder,
            innerElder,
            qingyunPreachingElder
        ).filter { !it.isNullOrBlank() }
    }

    private fun ElderSlots.getAllDirectDiscipleIds(): List<String> {
        return listOf(
            herbGardenDisciples,
            alchemyDisciples,
            forgeDisciples,
            preachingMasters,
            lawEnforcementDisciples,
            lawEnforcementReserveDisciples,
            qingyunPreachingMasters,
            spiritMineDeaconDisciples
        ).flatten().mapNotNull { it.discipleId }
    }

    fun getAvailableDisciplesForLawEnforcementElder(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

        return discipleAggregates.value
            .filter { isSelectableDisciple(it) && it.realm <= 5 && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun getAvailableDisciplesForLawEnforcementDisciple(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

        return discipleAggregates.value
            .filter { isSelectableDisciple(it) && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
            .sortedByDescending { it.intelligence }
    }

    fun getAvailableDisciplesForLawEnforcementReserve(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

        return discipleAggregates.value
            .filter { isSelectableDisciple(it) && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun getOuterDisciples(): List<DiscipleAggregate> {
        return disciples.value.filter { it.isAlive && it.discipleType == "outer" }
    }

    fun getAvailableDisciplesForOuterElder(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

        return disciples.value
            .filter {
                it.isAlive &&
                it.discipleType == "inner" &&
                it.realm <= 6 &&
                it.age >= 5 &&
                it.realmLayer > 0 &&
                it.status == DiscipleStatus.IDLE &&
                !allElderIds.contains(it.id) &&
                !allDirectDiscipleIds.contains(it.id)
            }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun getAvailableDisciplesForPreachingElder(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

        return disciples.value
            .filter { isSelectableDisciple(it) && it.realm <= 6 && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun getAvailableDisciplesForPreachingMaster(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameData.value?.elderSlots ?: return emptyList()
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

        return disciples.value
            .filter { isSelectableDisciple(it) && it.realm <= 7 && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun getInnerElder(): DiscipleAggregate? {
        val innerElderId = gameEngine.gameData.value?.elderSlots?.innerElder
        return getElderDisciple(innerElderId)
    }

    fun getQingyunPreachingElder(): DiscipleAggregate? {
        val preachingElderId = gameEngine.gameData.value?.elderSlots?.qingyunPreachingElder
        return getElderDisciple(preachingElderId)
    }

    fun getQingyunPreachingMasters(): List<DirectDiscipleSlot> {
        return gameEngine.gameData.value?.elderSlots?.qingyunPreachingMasters ?: emptyList()
    }

    fun getInnerDisciples(): List<DiscipleAggregate> {
        return disciples.value.filter { it.isAlive && it.discipleType == "inner" }
    }

    fun getAlchemyReserveDisciples(): List<DirectDiscipleSlot> {
        return gameEngine.gameData.value?.elderSlots?.alchemyReserveDisciples ?: emptyList()
    }

    fun getAlchemyReserveDisciplesWithInfo(): List<DiscipleAggregate> {
        val reserveSlots = gameEngine.gameData.value?.elderSlots?.alchemyReserveDisciples ?: emptyList()
        val reserveIds = reserveSlots.mapNotNull { it.discipleId }.toSet()
        return discipleAggregates.value
            .filter { it.id in reserveIds }
            .sortedByDescending { it.pillRefining }
    }

    fun getHerbGardenReserveDisciples(): List<DirectDiscipleSlot> {
        return gameEngine.gameData.value?.elderSlots?.herbGardenReserveDisciples ?: emptyList()
    }

    fun getHerbGardenReserveDisciplesWithInfo(): List<DiscipleAggregate> {
        val reserveSlots = gameEngine.gameData.value?.elderSlots?.herbGardenReserveDisciples ?: emptyList()
        val reserveIds = reserveSlots.mapNotNull { it.discipleId }.toSet()
        return disciples.value
            .filter { it.id in reserveIds }
            .sortedByDescending { it.spiritPlanting }
    }

    fun addAlchemyReserveDisciples(discipleIds: List<String>) {
        viewModelScope.launch {
            try {
                val currentReserveDisciples = gameEngine.gameData.value?.elderSlots?.alchemyReserveDisciples?.toMutableList() ?: mutableListOf()
                val existingIds = currentReserveDisciples.mapNotNull { it.discipleId }.toSet()
                val initialSize = currentReserveDisciples.size

                discipleIds.forEach { discipleId ->
                    if (existingIds.contains(discipleId)) return@forEach

                    val disciple = disciples.value.find { it.id == discipleId } ?: return@forEach

                    if (hasDisciplePosition(discipleId)) return@forEach

                    if (isReserveDisciple(discipleId)) return@forEach

                    val newIndex = if (currentReserveDisciples.isEmpty()) 0 else currentReserveDisciples.maxOf { it.index } + 1
                    val newSlot = DirectDiscipleSlot(
                        index = newIndex,
                        discipleId = discipleId,
                        discipleName = disciple.name,
                        discipleRealm = disciple.realmName,
                        discipleSpiritRootColor = disciple.spiritRoot.countColor
                    )
                    currentReserveDisciples.add(newSlot)
                }

                val addedCount = currentReserveDisciples.size - initialSize
                if (addedCount > 0) {
                    gameEngine.updateGameData { it.copy(elderSlots = it.elderSlots.copy(alchemyReserveDisciples = currentReserveDisciples)) }
                } else {
                    _errorMessage.value = "没有可添加的弟子"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "添加失败"
            }
        }
    }

    fun removeAlchemyReserveDisciple(discipleId: String) {
        viewModelScope.launch {
            try {
                val currentReserveDisciples = gameEngine.gameData.value?.elderSlots?.alchemyReserveDisciples ?: emptyList()
                val updatedReserveDisciples = currentReserveDisciples.filter { it.discipleId != discipleId }
                gameEngine.updateGameData { it.copy(elderSlots = it.elderSlots.copy(alchemyReserveDisciples = updatedReserveDisciples)) }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "移除失败"
            }
        }
    }

    fun addHerbGardenReserveDisciples(discipleIds: List<String>) {
        viewModelScope.launch {
            try {
                val currentReserveDisciples = gameEngine.gameData.value?.elderSlots?.herbGardenReserveDisciples?.toMutableList() ?: mutableListOf()
                val existingIds = currentReserveDisciples.mapNotNull { it.discipleId }.toSet()
                var addedCount = 0

                discipleIds.forEach { discipleId ->
                    if (existingIds.contains(discipleId)) return@forEach

                    val disciple = disciples.value.find { it.id == discipleId } ?: return@forEach

                    if (hasDisciplePosition(discipleId)) return@forEach

                    if (isReserveDisciple(discipleId)) return@forEach

                    val newIndex = if (currentReserveDisciples.isEmpty()) 0 else currentReserveDisciples.maxOf { it.index } + 1
                    val newSlot = DirectDiscipleSlot(
                        index = newIndex,
                        discipleId = discipleId,
                        discipleName = disciple.name,
                        discipleRealm = disciple.realmName,
                        discipleSpiritRootColor = disciple.spiritRoot.countColor
                    )
                    currentReserveDisciples.add(newSlot)
                    addedCount++
                }

                if (addedCount > 0) {
                    gameEngine.updateGameData { it.copy(elderSlots = it.elderSlots.copy(herbGardenReserveDisciples = currentReserveDisciples)) }
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "添加失败"
            }
        }
    }

    fun removeHerbGardenReserveDisciple(discipleId: String) {
        viewModelScope.launch {
            try {
                val currentReserveDisciples = gameEngine.gameData.value?.elderSlots?.herbGardenReserveDisciples ?: emptyList()
                val updatedReserveDisciples = currentReserveDisciples.filter { it.discipleId != discipleId }
                gameEngine.updateGameData { it.copy(elderSlots = it.elderSlots.copy(herbGardenReserveDisciples = updatedReserveDisciples)) }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "移除失败"
            }
        }
    }

    fun getAvailableDisciplesForHerbGardenReserve(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameData.value?.elderSlots ?: return emptyList()
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds() +
            elderSlots.alchemyReserveDisciples.mapNotNull { it.discipleId } +
            elderSlots.herbGardenReserveDisciples.mapNotNull { it.discipleId }

        return disciples.value
            .filter {
                it.isAlive &&
                it.discipleType == "inner" &&
                it.realmLayer > 0 &&
                it.status == DiscipleStatus.IDLE &&
                !allElderIds.contains(it.id) &&
                !allDirectDiscipleIds.contains(it.id)
            }
            .sortedByDescending { it.spiritPlanting }
    }

    fun getAvailableDisciplesForAlchemyReserve(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds() +
            elderSlots.alchemyReserveDisciples.mapNotNull { it.discipleId } +
            elderSlots.forgeReserveDisciples.mapNotNull { it.discipleId }

        return discipleAggregates.value
            .filter {
                it.isAlive &&
                it.discipleType == "inner" &&
                it.realmLayer > 0 &&
                it.status == DiscipleStatus.IDLE &&
                !allElderIds.contains(it.id) &&
                !allDirectDiscipleIds.contains(it.id)
            }
            .sortedByDescending { it.pillRefining }
    }

    fun getForgeReserveDisciples(): List<DirectDiscipleSlot> {
        return gameEngine.gameData.value.elderSlots.forgeReserveDisciples
    }

    fun getForgeReserveDisciplesWithInfo(): List<DiscipleAggregate> {
        val reserveSlots = gameEngine.gameData.value.elderSlots.forgeReserveDisciples
        val reserveIds = reserveSlots.mapNotNull { it.discipleId }.toSet()
        return discipleAggregates.value
            .filter { it.id in reserveIds && it.status != DiscipleStatus.REFLECTING }
            .sortedByDescending { it.artifactRefining }
    }

    fun addForgeReserveDisciples(discipleIds: List<String>) {
        viewModelScope.launch {
            try {
                val currentReserveDisciples = gameEngine.gameData.value.elderSlots.forgeReserveDisciples.toMutableList()
                val existingIds = currentReserveDisciples.mapNotNull { it.discipleId }.toSet()
                var addedCount = 0
                
                discipleIds.forEach { discipleId ->
                    if (existingIds.contains(discipleId)) return@forEach
                    
                    val disciple = disciples.value.find { it.id == discipleId } ?: return@forEach
                    
                    if (hasDisciplePosition(discipleId)) return@forEach
                    
                    if (isReserveDisciple(discipleId)) return@forEach
                    
                    val newIndex = if (currentReserveDisciples.isEmpty()) 0 else currentReserveDisciples.maxOf { it.index } + 1
                    val newSlot = DirectDiscipleSlot(
                        index = newIndex,
                        discipleId = discipleId,
                        discipleName = disciple.name,
                        discipleRealm = disciple.realmName,
                        discipleSpiritRootColor = disciple.spiritRoot.countColor
                    )
                    currentReserveDisciples.add(newSlot)
                    addedCount++
                }
                
                if (addedCount > 0) {
                    val updatedElderSlots = gameEngine.gameData.value.elderSlots.copy(
                        forgeReserveDisciples = currentReserveDisciples
                    )
                    gameEngine.updateGameData { it.copy(elderSlots = updatedElderSlots) }
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "添加失败"
            }
        }
    }

    fun removeForgeReserveDisciple(discipleId: String) {
        viewModelScope.launch {
            try {
                val currentReserveDisciples = gameEngine.gameData.value.elderSlots.forgeReserveDisciples
                val updatedReserveDisciples = currentReserveDisciples.filter { it.discipleId != discipleId }
                val updatedElderSlots = gameEngine.gameData.value.elderSlots.copy(
                    forgeReserveDisciples = updatedReserveDisciples
                )
                gameEngine.updateGameData { it.copy(elderSlots = updatedElderSlots) }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "移除失败"
            }
        }
    }

    fun getAvailableDisciplesForForgeReserve(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds() +
            elderSlots.alchemyReserveDisciples.mapNotNull { it.discipleId } +
            elderSlots.forgeReserveDisciples.mapNotNull { it.discipleId }

        return discipleAggregates.value
            .filter {
                it.isAlive &&
                it.discipleType == "inner" &&
                it.realmLayer > 0 &&
                it.status == DiscipleStatus.IDLE &&
                !allElderIds.contains(it.id) &&
                !allDirectDiscipleIds.contains(it.id)
            }
            .sortedByDescending { it.artifactRefining }
    }

    // ==================== 灵矿执事管理 ====================

    fun getSpiritMineDeaconDisciples(): List<DirectDiscipleSlot> {
        return gameEngine.gameData.value.elderSlots.spiritMineDeaconDisciples
    }

    fun getAvailableDisciplesForSpiritMineDeacon(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

        // 灵矿执事只能由内门弟子担任
        return discipleAggregates.value
            .filter {
                it.isAlive &&
                it.discipleType == "inner" &&
                it.age >= 5 &&
                it.realmLayer > 0 &&
                it.status == DiscipleStatus.IDLE &&
                !allElderIds.contains(it.id) &&
                !allDirectDiscipleIds.contains(it.id)
            }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun assignSpiritMineDeacon(slotIndex: Int, discipleId: String) {
        viewModelScope.launch {
            try {
                val disciple = disciples.value.find { it.id == discipleId }
                if (disciple == null) {
                    _errorMessage.value = "弟子不存在"
                    return@launch
                }

                // 检查是否为内门弟子
                if (disciple.discipleType != "inner") {
                    _errorMessage.value = "执事只能由内门弟子担任"
                    return@launch
                }

                val currentGameData = gameEngine.gameData.value
                val elderSlots = currentGameData.elderSlots

                // 检查是否已在其他职位
                val allElderIds = elderSlots.getAllElderIds()
                val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

                if (allElderIds.contains(discipleId)) {
                    _errorMessage.value = "该弟子已担任长老职位"
                    return@launch
                }

                if (allDirectDiscipleIds.contains(discipleId)) {
                    _errorMessage.value = "该弟子已是其他长老的亲传弟子"
                    return@launch
                }

                // 添加执事
                val currentDeacons = elderSlots.spiritMineDeaconDisciples.toMutableList()
                val existingSlot = currentDeacons.find { it.index == slotIndex }
                
                val newSlot = DirectDiscipleSlot(
                    index = slotIndex,
                    discipleId = discipleId,
                    discipleName = disciple.name,
                    discipleRealm = disciple.realmName,
                    discipleSpiritRootColor = disciple.spiritRoot.countColor
                )

                if (existingSlot != null) {
                    currentDeacons[currentDeacons.indexOf(existingSlot)] = newSlot
                } else {
                    currentDeacons.add(newSlot)
                }

                val updatedElderSlots = elderSlots.copy(spiritMineDeaconDisciples = currentDeacons)
                gameEngine.updateGameData { it.copy(elderSlots = updatedElderSlots) }
                
                gameEngine.updateDiscipleStatus(discipleId, DiscipleStatus.DEACONING)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "任命失败"
            }
        }
    }

    fun removeSpiritMineDeacon(slotIndex: Int) {
        viewModelScope.launch {
            try {
                val currentGameData = gameEngine.gameData.value
                val elderSlots = currentGameData.elderSlots
                
                // 获取被移除的执事ID
                val removedDeaconId = elderSlots.spiritMineDeaconDisciples.find { it.index == slotIndex }?.discipleId
                
                val currentDeacons = elderSlots.spiritMineDeaconDisciples.filter { it.index != slotIndex }
                val updatedElderSlots = elderSlots.copy(spiritMineDeaconDisciples = currentDeacons)
                gameEngine.updateGameData { it.copy(elderSlots = updatedElderSlots) }
                
                // 恢复弟子状态为空闲
                removedDeaconId?.let { gameEngine.updateDiscipleStatus(it, DiscipleStatus.IDLE) }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "卸任失败"
            }
        }
    }

    fun validateSpiritMineData() {
        gameEngine.validateAndFixSpiritMineData()
    }

    fun getAvailableDisciplesForInnerElder(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

        return disciples.value
            .filter { isSelectableDisciple(it) && it.realm <= 6 && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun getAvailableDisciplesForQingyunPreachingElder(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

        return disciples.value
            .filter { isSelectableDisciple(it) && it.realm <= 6 && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun getAvailableDisciplesForQingyunPreachingMaster(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

        return disciples.value
            .filter { isSelectableDisciple(it) && it.realm <= 7 && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun openWorldMapDialog() {
        openDialog(DialogType.WorldMap)
    }

    fun closeWorldMapDialog() {
        closeCurrentDialog()
    }

    fun openSecretRealmDialog() {
        openDialog(DialogType.SecretRealm)
    }

    fun closeSecretRealmDialog() {
        closeCurrentDialog()
    }

    fun openBattleLogDialog() {
        openDialog(DialogType.BattleLog)
    }

    fun closeBattleLogDialog() {
        closeCurrentDialog()
    }

    fun dispatchTeamToDungeon(dungeonId: String, discipleIds: List<String>) {
        viewModelScope.launch {
            try {
                val dungeonConfig = GameConfig.Dungeons.get(dungeonId)
                if (dungeonConfig == null) {
                    _errorMessage.value = "秘境不存在"
                    return@launch
                }

                val teamName = "${dungeonConfig.name}探索队"
                val duration = 24

                gameEngine.startExploration(teamName, discipleIds, dungeonId, duration)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "派遣失败"
            }
        }
    }

    // 招募弟子
    fun recruitDisciple() {
        viewModelScope.launch {
            try {
                gameEngine.recruitDisciple()
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "招募弟子失败"
            }
        }
    }

    fun assignDiscipleToBuilding(buildingId: String, slotIndex: Int, discipleId: String) {
        viewModelScope.launch {
            try {
                gameEngine.assignDiscipleToBuilding(buildingId, slotIndex, discipleId)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "分配失败"
            }
        }
    }

    fun getAvailableDisciplesForSpiritMining(): List<DiscipleAggregate> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = elderSlots.getAllElderIds()
        val allDirectDiscipleIds = elderSlots.getAllDirectDiscipleIds()

        val assignedMiningIds = gameEngine.gameData.value.spiritMineSlots.mapNotNull { it.discipleId }.toSet()

        return discipleAggregates.value
            .filter {
                it.isAlive &&
                it.discipleType == "outer" &&
                it.realmLayer > 0 &&
                it.status == DiscipleStatus.IDLE &&
                !allElderIds.contains(it.id) &&
                !allDirectDiscipleIds.contains(it.id) &&
                !assignedMiningIds.contains(it.id)
            }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun assignDisciplesToSpiritMineSlots(selectedDisciples: List<DiscipleAggregate>) {
        viewModelScope.launch {
            try {
                // 将 DiscipleAggregate 转换回 Disciple ID 列表用于内部处理
                val discipleIds = selectedDisciples.map { it.id }
                val disciplesToAssign = disciples.value.filter { it.id in discipleIds }
                assignDisciplesToEmptyMineSlotsInternal(disciplesToAssign)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "分配失败"
            }
        }
    }

    fun removeDiscipleFromSpiritMineSlot(slotIndex: Int) {
        viewModelScope.launch {
            try {
                val currentGameData = gameEngine.gameData.value
                val currentSlots = currentGameData.spiritMineSlots.toMutableList()

                if (slotIndex < currentSlots.size) {
                    val discipleId = currentSlots[slotIndex].discipleId
                    discipleId?.let {
                        gameEngine.updateDiscipleStatus(it, DiscipleStatus.IDLE)
                    }

                    currentSlots[slotIndex] = currentSlots[slotIndex].copy(
                        discipleId = "",
                        discipleName = ""
                    )
                    gameEngine.updateSpiritMineSlots(currentSlots)
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "卸任失败"
            }
        }
    }

    fun autoAssignSpiritMineMiners() {
        viewModelScope.launch {
            try {
                val availableDisciples = getAvailableDisciplesForSpiritMining()
                if (availableDisciples.isEmpty()) return@launch
                // 将 DiscipleAggregate 转换回 Disciple 列表用于内部处理
                val discipleIds = availableDisciples.map { it.id }
                val disciplesToAssign = disciples.value.filter { it.id in discipleIds }
                assignDisciplesToEmptyMineSlotsInternal(disciplesToAssign)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "一键任命失败"
            }
        }
    }

    private suspend fun assignDisciplesToEmptyMineSlotsInternal(disciples: List<DiscipleAggregate>) {
        val currentGameData = gameEngine.gameData.value
        var currentSlots = currentGameData.spiritMineSlots.toMutableList()
        while (currentSlots.size < 12) {
            currentSlots.add(SpiritMineSlot(index = currentSlots.size))
        }
        val disciplesToAssign = disciples.take(currentSlots.count { it.discipleId.isEmpty() })
        for (disciple in disciplesToAssign) {
            val targetSlotIndex = currentSlots.indexOfFirst { it.discipleId.isEmpty() }
            if (targetSlotIndex == -1) break
            currentSlots[targetSlotIndex] = currentSlots[targetSlotIndex].copy(
                discipleId = disciple.id,
                discipleName = disciple.name
            )
            gameEngine.updateDiscipleStatus(disciple.id, DiscipleStatus.MINING)
        }
        gameEngine.updateSpiritMineSlots(currentSlots)
    }

    fun assignDiscipleToLibrarySlot(slotIndex: Int, discipleId: String, discipleName: String) {
        viewModelScope.launch {
            try {
                gameEngine.assignDiscipleToLibrarySlot(slotIndex, discipleId, discipleName)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "分配失败"
            }
        }
    }

    fun removeDiscipleFromLibrarySlot(slotIndex: Int) {
        viewModelScope.launch {
            try {
                gameEngine.removeDiscipleFromLibrarySlot(slotIndex)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "卸任失败"
            }
        }
    }

    fun startAlchemy(slotIndex: Int, recipe: PillRecipeDatabase.PillRecipe) {
        // 防止重复点击
        if (_isStartingAlchemy.value) return
        _isStartingAlchemy.value = true

        viewModelScope.launch {
            try {
                gameEngine.startAlchemy(slotIndex, recipe.id)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "开始炼制失败"
            } finally {
                _isStartingAlchemy.value = false
            }
        }
    }

    fun collectAlchemyResult(slotIndex: Int): AlchemyResult? {
        return gameEngine.collectAlchemyResult(slotIndex)
    }

    fun clearAlchemySlot(slotIndex: Int) {
        viewModelScope.launch {
            gameEngine.clearAlchemySlot(slotIndex)
        }
    }

    fun startForge(slotIndex: Int, recipe: ForgeRecipeDatabase.ForgeRecipe) {
        // 防止重复点击
        if (_isStartingForge.value) return
        _isStartingForge.value = true

        viewModelScope.launch {
            try {
                gameEngine.startForging(slotIndex, recipe.id)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "开始锻造失败"
            } finally {
                _isStartingForge.value = false
            }
        }
    }

    fun collectForgeResult(slotIndex: Int) {
        viewModelScope.launch {
            gameEngine.collectForgeResult(slotIndex)
        }
    }

    fun clearForgeSlot(slotIndex: Int) {
        viewModelScope.launch {
            gameEngine.clearForgeSlot(slotIndex)
        }
    }

    // 药园管理
    fun startHerbGardenPlanting(slotIndex: Int, seedId: String) {
        viewModelScope.launch {
            try {
                gameEngine.startManualPlanting(slotIndex, seedId)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "种植失败"
            }
        }
    }

    fun selectPlantSlot(slotIndex: Int) {
        _selectedPlantSlotIndex.value = slotIndex
    }

    fun clearPlantSlot(slotIndex: Int) {
        viewModelScope.launch {
            gameEngine.clearPlantSlot(slotIndex)
        }
    }

    fun autoPlantAllSlots() {
        viewModelScope.launch {
            try {
                val productionSlots = gameEngine.productionSlots.value
                val herbGardenSlots = productionSlots.filter {
                    it.buildingType == com.xianxia.sect.core.model.production.BuildingType.HERB_GARDEN
                }
                val idleSlots = herbGardenSlots.filter { it.status == com.xianxia.sect.core.model.production.ProductionSlotStatus.IDLE }
                if (idleSlots.isEmpty()) {
                    _errorMessage.value = "没有空闲的种植槽位"
                    return@launch
                }

                var plantedCount = 0
                for (slot in idleSlots) {
                    val currentSeeds = gameEngine.seeds.value
                        .filter { it.quantity > 0 }
                        .sortedByDescending { it.rarity }

                    val seedToPlant = currentSeeds.firstOrNull() ?: break

                    gameEngine.startManualPlanting(slot.slotIndex, seedToPlant.id)
                    plantedCount++
                }

                if (plantedCount > 0) {
                    _successMessage.value = "自动种植完成，已种植${plantedCount}个槽位"
                } else {
                    _errorMessage.value = "仓库中没有可用的种子"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "自动种植失败"
            }
        }
    }

    fun autoAlchemyAllSlots() {
        viewModelScope.launch {
            try {
                val productionSlots = gameEngine.productionSlots.value
                val alchemySlots = productionSlots.filter {
                    it.buildingType == com.xianxia.sect.core.model.production.BuildingType.ALCHEMY
                }
                val idleSlotIndices = alchemySlots
                    .filter { it.status == com.xianxia.sect.core.model.production.ProductionSlotStatus.IDLE }
                    .map { it.slotIndex }

                if (idleSlotIndices.isEmpty()) {
                    _errorMessage.value = "没有空闲的炼丹槽位"
                    return@launch
                }

                val allRecipes = PillRecipeDatabase.getAllRecipes().sortedByDescending { it.rarity }
                var startedCount = 0

                for (slotIndex in idleSlotIndices) {
                    val currentHerbs = gameEngine.herbs.value
                    val recipeToStart = allRecipes.firstOrNull { recipe ->
                        recipe.materials.all { (materialId, requiredQuantity) ->
                            val herbData = HerbDatabase.getHerbById(materialId)
                            val herbName = herbData?.name
                            val herbRarity = herbData?.rarity ?: 1
                            val herb = currentHerbs.find { it.name == herbName && it.rarity == herbRarity }
                            herb != null && herb.quantity >= requiredQuantity
                        }
                    }

                    if (recipeToStart == null) break

                    val success = gameEngine.startAlchemy(slotIndex, recipeToStart.id)
                    if (success) startedCount++
                }

                if (startedCount > 0) {
                    _successMessage.value = "自动炼丹完成，已启动${startedCount}个槽位"
                } else {
                    _errorMessage.value = "没有足够的草药进行炼丹"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "自动炼丹失败"
            }
        }
    }

    fun autoForgeAllSlots() {
        viewModelScope.launch {
            try {
                val productionSlots = gameEngine.productionSlots.value
                val forgeSlots = productionSlots.filter {
                    it.buildingType == com.xianxia.sect.core.model.production.BuildingType.FORGE
                }
                val maxSlotCount = 3

                val idleSlotIndices = (0 until maxSlotCount).filter { idx ->
                    val slot = forgeSlots.find { it.slotIndex == idx }
                    slot == null || slot.status == com.xianxia.sect.core.model.production.ProductionSlotStatus.IDLE
                }

                if (idleSlotIndices.isEmpty()) {
                    _errorMessage.value = "没有空闲的锻造槽位"
                    return@launch
                }

                val allRecipes = ForgeRecipeDatabase.getAllRecipes().sortedByDescending { it.rarity }
                var startedCount = 0

                for (slotIndex in idleSlotIndices) {
                    val currentMaterials = gameEngine.materials.value
                    val materialIndex = currentMaterials.groupBy { it.name to it.rarity }
                        .mapValues { (_, list) -> list.sumOf { it.quantity } }

                    val recipeToStart = allRecipes.firstOrNull { recipe ->
                        recipe.materials.all { (materialId, requiredQuantity) ->
                            val materialData = BeastMaterialDatabase.getMaterialById(materialId)
                            materialData != null && run {
                                val available = materialIndex[materialData.name to materialData.rarity] ?: 0
                                available >= requiredQuantity
                            }
                        }
                    }

                    if (recipeToStart == null) break

                    val success = gameEngine.startForging(slotIndex, recipeToStart.id)
                    if (success) startedCount++
                }

                if (startedCount > 0) {
                    _successMessage.value = "自动炼器完成，已启动${startedCount}个槽位"
                } else {
                    _errorMessage.value = "没有足够的材料进行锻造"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "自动炼器失败"
            }
        }
    }

    // 弟子招募相关
    fun recruitDiscipleFromList(discipleId: String) {
        if (recruitingDiscipleIds.contains(discipleId)) {
            return
        }
        recruitingDiscipleIds.add(discipleId)

        viewModelScope.launch {
            try {
                gameEngine.recruitDiscipleFromList(discipleId)
            } finally {
                recruitingDiscipleIds.remove(discipleId)
            }
        }
    }

    fun expelDisciple(discipleId: String) {
        viewModelScope.launch {
            gameEngine.expelDisciple(discipleId)
        }
    }

    fun rewardItemsToDisciple(discipleId: String, items: List<RewardSelectedItem>) {
        viewModelScope.launch {
            gameEngine.rewardItemsToDisciple(discipleId, items)
        }
    }

    fun recruitAllDisciples() {
        viewModelScope.launch {
            val success = gameEngine.recruitAllFromList()
            if (!success) {
                _errorMessage.value = "无弟子可招募"
            }
        }
    }

    fun rejectDiscipleFromList(discipleId: String) {
        viewModelScope.launch {
            gameEngine.removeFromRecruitList(discipleId)
        }
    }

    fun equipItem(discipleId: String, equipmentId: String) {
        viewModelScope.launch {
            try {
                gameEngine.equipItem(discipleId, equipmentId)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "装备失败"
            }
        }
    }

    fun unequipItem(discipleId: String, slot: EquipmentSlot) {
        viewModelScope.launch {
            try {
                gameEngine.unequipItem(discipleId, slot)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "卸下装备失败"
            }
        }
    }

    fun unequipItem(discipleId: String, equipmentId: String) {
        viewModelScope.launch {
            try {
                gameEngine.unequipItemById(discipleId, equipmentId)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "卸下装备失败"
            }
        }
    }

    fun forgetManual(discipleId: String, manualId: String) {
        viewModelScope.launch {
            try {
                gameEngine.forgetManual(discipleId, manualId)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "遗忘功法失败"
            }
        }
    }

    fun replaceManual(discipleId: String, oldManualId: String, newManualId: String) {
        viewModelScope.launch {
            try {
                gameEngine.replaceManual(discipleId, oldManualId, newManualId)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "功法替换失败"
            }
        }
    }

    fun learnManual(discipleId: String, manualId: String) {
        viewModelScope.launch {
            try {
                gameEngine.learnManual(discipleId, manualId)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "学习功法失败"
            }
        }
    }

    fun recallTeam(teamId: String) {
        viewModelScope.launch {
            try {
                gameEngine.recallTeam(teamId)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "召回失败"
            }
        }
    }

    fun startCaveExploration(cave: CultivatorCave, selectedDisciples: List<DiscipleAggregate>) {
        viewModelScope.launch {
            gameEngine.startCaveExploration(cave, selectedDisciples.map { it.toDisciple() })
        }
    }

    fun buyFromMerchant(itemId: String, quantity: Int = 1) {
        viewModelScope.launch {
            try {
                gameEngine.buyMerchantItem(itemId, quantity)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "购买失败"
            }
        }
    }

    fun listItemsToMerchant(items: List<Pair<String, Int>>) {
        viewModelScope.launch {
            try {
                gameEngine.listItemsToMerchant(items)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "上架失败"
            }
        }
    }

    fun removePlayerListedItem(itemId: String) {
        viewModelScope.launch {
            try {
                gameEngine.removePlayerListedItem(itemId)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "下架失败"
            }
        }
    }

    // 招募相关
    private val recruitingDiscipleIds = mutableSetOf<String>()

    fun recruitDisciple(disciple: DiscipleAggregate) {
        if (recruitingDiscipleIds.contains(disciple.id)) {
            return
        }
        recruitingDiscipleIds.add(disciple.id)

        viewModelScope.launch {
            try {
                gameEngine.recruitDiscipleFromList(disciple.id)
            } finally {
                recruitingDiscipleIds.remove(disciple.id)
            }
        }
    }

    fun plantSeed(slotIndex: Int, seed: com.xianxia.sect.core.model.Seed) {
        viewModelScope.launch {
            try {
                gameEngine.startManualPlanting(slotIndex, seed.id)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "种植失败"
            }
        }
    }

    fun setMonthlySalary(realm: Int, amount: Int) {
        viewModelScope.launch {
            val data = gameEngine.gameData.value
            val newSalary = data.monthlySalary.toMutableMap()
            newSalary[realm] = amount
            gameEngine.updateMonthlySalary(newSalary)
        }
    }

    fun setMonthlySalaryEnabled(realm: Int, enabled: Boolean) {
        viewModelScope.launch {
            gameEngine.updateMonthlySalaryEnabled(realm, enabled)
        }
    }

    fun usePill(discipleId: String, pillId: String) {
        viewModelScope.launch {
            try {
                gameEngine.usePill(discipleId, pillId)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "使用丹药失败"
            }
        }
    }

    fun usePill(discipleId: String, pill: Pill) {
        viewModelScope.launch {
            try {
                gameEngine.usePill(discipleId, pill.id)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "使用丹药失败"
            }
        }
    }

    fun clearErrorMessage() {
        _errorMessage.value = null
    }

    fun getDiscipleById(id: String): DiscipleAggregate? {
        return discipleAggregates.value.find { it.id == id }
    }

    fun getManualById(id: String): Manual? {
        return manuals.value.find { it.id == id }
    }

    // 一键出售物品（原子性操作，排除锁定物品）
    fun bulkSellItems(
        selectedRarities: Set<Int>,
        selectedTypes: Set<String>
    ) {
        viewModelScope.launch {
            try {
                val equippedIds = mutableSetOf<String>()
                val learnedManualIds = mutableSetOf<String>()
                disciples.value.filter { it.isAlive }.forEach { disciple ->
                    disciple.weaponId?.let { equippedIds.add(it) }
                    disciple.armorId?.let { equippedIds.add(it) }
                    disciple.bootsId?.let { equippedIds.add(it) }
                    disciple.accessoryId?.let { equippedIds.add(it) }
                    learnedManualIds.addAll(disciple.manualIds)
                }
                
                val sellOperations = mutableListOf<SuspendableSellOperation>()
                
                if (selectedTypes.contains("EQUIPMENT")) {
                    equipment.value.filter { 
                        selectedRarities.contains(it.rarity) && 
                        !equippedIds.contains(it.id) && 
                        !it.isLocked
                    }.forEach { item ->
                        sellOperations.add(SuspendableSellOperation.Equipment(item.id, item.name, (item.basePrice * 0.8).toInt()))
                    }
                }

                if (selectedTypes.contains("MANUAL")) {
                    manuals.value.filter { 
                        selectedRarities.contains(it.rarity) && 
                        !learnedManualIds.contains(it.id) &&
                        !it.isLocked
                    }.forEach { item ->
                        sellOperations.add(SuspendableSellOperation.Manual(item.id, item.name, item.quantity, (item.basePrice * item.quantity * 0.8).toInt()))
                    }
                }

                if (selectedTypes.contains("PILL")) {
                    pills.value.filter { 
                        selectedRarities.contains(it.rarity) && !it.isLocked
                    }.forEach { item ->
                        sellOperations.add(SuspendableSellOperation.Pill(item.id, item.name, item.quantity, (item.basePrice * item.quantity * 0.8).toInt()))
                    }
                }

                if (selectedTypes.contains("MATERIAL")) {
                    materials.value.filter { 
                        selectedRarities.contains(it.rarity) && !it.isLocked
                    }.forEach { item ->
                        sellOperations.add(SuspendableSellOperation.Material(item.id, item.name, item.quantity, (item.basePrice * item.quantity * 0.8).toInt()))
                    }
                }

                if (selectedTypes.contains("HERB")) {
                    herbs.value.filter { 
                        selectedRarities.contains(it.rarity) && !it.isLocked
                    }.forEach { item ->
                        sellOperations.add(SuspendableSellOperation.Herb(item.id, item.name, item.quantity, (item.basePrice * item.quantity * 0.8).toInt()))
                    }
                }

                if (selectedTypes.contains("SEED")) {
                    seeds.value.filter { 
                        selectedRarities.contains(it.rarity) && !it.isLocked
                    }.forEach { item ->
                        sellOperations.add(SuspendableSellOperation.Seed(item.id, item.name, item.quantity, (item.basePrice * item.quantity * 0.8).toInt()))
                    }
                }

                if (sellOperations.isEmpty()) {
                    _errorMessage.value = "没有符合条件的物品可出售（已排除锁定物品）"
                    return@launch
                }

                var totalValue = 0
                val soldItems = mutableListOf<String>()
                val executedOperations = mutableListOf<SuspendableSellOperation>()
                
                try {
                    for (op in sellOperations) {
                        val success = when (op) {
                            is SuspendableSellOperation.Equipment -> {
                                gameEngine.sellEquipment(op.id)
                            }
                            is SuspendableSellOperation.Manual -> {
                                gameEngine.sellManual(op.id, op.quantity)
                            }
                            is SuspendableSellOperation.Pill -> {
                                gameEngine.sellPill(op.id, op.quantity)
                            }
                            is SuspendableSellOperation.Material -> {
                                gameEngine.sellMaterial(op.id, op.quantity)
                            }
                            is SuspendableSellOperation.Herb -> {
                                gameEngine.sellHerb(op.id, op.quantity)
                            }
                            is SuspendableSellOperation.Seed -> {
                                gameEngine.sellSeed(op.id, op.quantity)
                            }
                        }
                        
                        if (success) {
                            totalValue += op.price
                            soldItems.add(op.displayName)
                            executedOperations.add(op)
                        }
                    }
                    
                    if (totalValue > 0) {
                        gameEngine.addSpiritStones(totalValue)
                    }
                } catch (e: Exception) {
                    _errorMessage.value = "出售过程中发生错误: ${e.message}"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "一键出售失败"
            }
        }
    }
    
    private sealed class SuspendableSellOperation {
        abstract val id: String
        abstract val displayName: String
        abstract val price: Int
        
        data class Equipment(override val id: String, val name: String, override val price: Int) : SuspendableSellOperation() {
            override val displayName: String = name
        }
        data class Manual(override val id: String, val name: String, val quantity: Int, override val price: Int) : SuspendableSellOperation() {
            override val displayName: String = "$name x$quantity"
        }
        data class Pill(override val id: String, val name: String, val quantity: Int, override val price: Int) : SuspendableSellOperation() {
            override val displayName: String = "$name x$quantity"
        }
        data class Material(override val id: String, val name: String, val quantity: Int, override val price: Int) : SuspendableSellOperation() {
            override val displayName: String = "$name x$quantity"
        }
        data class Herb(override val id: String, val name: String, val quantity: Int, override val price: Int) : SuspendableSellOperation() {
            override val displayName: String = "$name x$quantity"
        }
        data class Seed(override val id: String, val name: String, val quantity: Int, override val price: Int) : SuspendableSellOperation() {
            override val displayName: String = "$name x$quantity"
        }
    }

    fun getEquipmentById(id: String): Equipment? {
        return equipment.value.find { it.id == id }
    }

    fun getPillById(id: String): Pill? {
        return pills.value.find { it.id == id }
    }

    fun getMaterialById(id: String): Material? {
        return materials.value.find { it.id == id }
    }

    fun startMission(mission: com.xianxia.sect.core.model.Mission, selectedDisciples: List<DiscipleAggregate>) {
        viewModelScope.launch {
            try {
                gameEngine.startMission(mission, selectedDisciples.map { it.toDisciple() })
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "开始任务失败"
            }
        }
    }

    /**
     * 处理内存压力回调
     * @param level 内存压力级别，来自 ComponentCallbacks2
     * 注意：保存操作由 GameActivity 统一处理（带防抖），这里只负责释放内存
     */
    @Suppress("DEPRECATION")
    fun onMemoryPressure(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                Log.w(TAG, "内存压力: $level，释放内存资源")
                gameEngine.releaseMemory(level)
            }
        }
    }

    /**
     * 清理资源
     * 用于释放内存和清理状态
     */
    fun clearResources() {
        Log.i(TAG, "清理 GameViewModel 资源")
        stopGameLoop()
        _errorMessage.value = null
        _successMessage.value = null
    }

    override fun onCleared() {
        Log.i(TAG, "GameViewModel cleared, stopping game loop and performing auto save")
        
        runBlocking { setSaveLoadState(isLoading = false, isSaving = false, pendingSlot = null, pendingAction = null) }
        _loadingProgress.value = PROGRESS_START
        
        clearResources()
        
        if (stateManager.state.value.isSaving) {
            Log.i(TAG, "Waiting for current save operation to complete")
            val waitStartTime = System.currentTimeMillis()
            val maxWaitTime = 5000L
            while (stateManager.state.value.isSaving && System.currentTimeMillis() - waitStartTime < maxWaitTime) {
                Thread.sleep(100)
            }
            if (stateManager.state.value.isSaving) {
                Log.w(TAG, "Save operation still in progress after timeout, proceeding with exit save")
            } else {
                Log.i(TAG, "Previous save operation completed")
            }
        }
        
        try {
            val snapshot = gameEngine.getStateSnapshotSync()
            if (snapshot.gameData.sectName.isBlank()) {
                Log.w(TAG, "Game data not initialized, skipping exit save")
            } else {
                val currentSlot = snapshot.gameData.currentSlot
                if (currentSlot in 1..6) {
                    val saveData = SaveData(
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
                        alliances = snapshot.alliances,
                        productionSlots = snapshot.productionSlots
                    )
                    val result = runBlocking(Dispatchers.IO) {
                        withTimeoutOrNull(3_000L) {
                            storageFacade.save(currentSlot, saveData)
                        } ?: SaveResult.failure(SaveError.TIMEOUT, "Save timeout on exit")
                    }
                    if (result.isSuccess) {
                        Log.i(TAG, "Auto save on exit completed, slot: $currentSlot")
                    } else {
                        Log.w(TAG, "Auto save on exit skipped (another save in progress), slot: $currentSlot")
                    }
                } else {
                    Log.w(TAG, "Invalid slot $currentSlot, skipping exit save")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Auto save on exit failed: ${e.message}")
        }
        
        gameEngine.destroy()
        super.onCleared()
    }

    fun openBattleTeamDialog() {
        val existingTeam = gameEngine.gameData.value.battleTeam
        if (existingTeam != null) {
            _battleTeamSlots.value = existingTeam.slots
        } else {
            _battleTeamSlots.value = buildList {
                repeat(2) { index ->
                    add(BattleTeamSlot(index, slotType = BattleSlotType.ELDER))
                }
                repeat(8) { index ->
                    add(BattleTeamSlot(index + 2, slotType = BattleSlotType.DISCIPLE))
                }
            }
        }
        openDialog(DialogType.BattleTeam)
    }

    fun closeBattleTeamDialog() {
        closeCurrentDialog()
    }

    fun getAvailableEldersForBattleTeam(): List<DiscipleAggregate> {
        val currentSlotDiscipleIds = _battleTeamSlots.value.mapNotNull { it.discipleId }
        val occupiedIds = getWorkStatusPositionIds() + currentSlotDiscipleIds
        
        return disciples.value
            .filter { disciple ->
                disciple.isAlive &&
                disciple.discipleType == "inner" &&
                disciple.realmLayer > 0 &&
                disciple.age >= 5 &&
                disciple.status == DiscipleStatus.IDLE &&
                disciple.realm <= 5 &&
                !occupiedIds.contains(disciple.id)
            }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun getAvailableDisciplesForBattleTeam(): List<DiscipleAggregate> {
        val currentSlotDiscipleIds = _battleTeamSlots.value.mapNotNull { it.discipleId }
        val occupiedIds = getWorkStatusPositionIds() + currentSlotDiscipleIds
        
        return disciples.value
            .filter { disciple ->
                disciple.isAlive &&
                disciple.realmLayer > 0 &&
                disciple.status == DiscipleStatus.IDLE &&
                !occupiedIds.contains(disciple.id)
            }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun assignDiscipleToBattleTeamSlot(slotIndex: Int, disciple: DiscipleAggregate) {
        val currentSlots = _battleTeamSlots.value.toMutableList()
        
        if (slotIndex < 0 || slotIndex >= currentSlots.size) {
            _errorMessage.value = "槽位索引无效"
            return
        }
        
        val slotType = currentSlots[slotIndex].slotType
        
        if (slotType == BattleSlotType.ELDER && disciple.realm > 5) {
            _errorMessage.value = "战斗长老需要达到化神境界"
            return
        }
        
        val existingSlot = currentSlots.find { it.discipleId == disciple.id }
        if (existingSlot != null) {
            currentSlots[existingSlot.index] = BattleTeamSlot(existingSlot.index, slotType = existingSlot.slotType)
        }
        currentSlots[slotIndex] = BattleTeamSlot(
            index = slotIndex,
            discipleId = disciple.id,
            discipleName = disciple.name,
            discipleRealm = disciple.realmName,
            slotType = slotType
        )
        _battleTeamSlots.value = currentSlots
    }

    fun removeDiscipleFromBattleTeamSlot(slotIndex: Int) {
        val currentSlots = _battleTeamSlots.value.toMutableList()
        
        if (slotIndex < 0 || slotIndex >= currentSlots.size) {
            return
        }
        
        val slotType = currentSlots[slotIndex].slotType
        currentSlots[slotIndex] = BattleTeamSlot(slotIndex, slotType = slotType)
        _battleTeamSlots.value = currentSlots
    }

    fun createBattleTeam(): Boolean {
        val currentSlots = _battleTeamSlots.value
        val filledSlots = currentSlots.count { it.discipleId.isNotEmpty() }
        
        if (filledSlots < 10) {
            _errorMessage.value = "必须满10名弟子才可组建队伍"
            return false
        }

        val elderSlots = currentSlots.filter { it.slotType == BattleSlotType.ELDER }
        if (elderSlots.any { it.discipleId.isEmpty() }) {
            _errorMessage.value = "必须填满长老槽位才可组建队伍"
            return false
        }

        val existingTeam = gameEngine.gameData.value.battleTeam
        if (existingTeam != null && existingTeam.isAtSect) {
            _errorMessage.value = "宗门地址上已存在战斗队伍"
            return false
        }

        viewModelScope.launch {
            try {
                val battleTeam = BattleTeam(
                    id = java.util.UUID.randomUUID().toString(),
                    name = "战斗队伍",
                    slots = currentSlots,
                    isAtSect = true,
                    status = "idle"
                )
                
                gameEngine.updateGameData { it.copy(battleTeam = battleTeam) }
                gameEngine.syncAllDiscipleStatuses()
                
                closeCurrentDialog()
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "组建队伍失败"
            }
        }
        return true
    }

    fun disbandBattleTeam(): Boolean {
        val team = gameEngine.gameData.value.battleTeam
        if (team == null) {
            _errorMessage.value = "没有可解散的战斗队伍"
            return false
        }
        
        if (!team.isIdle) {
            _errorMessage.value = "队伍正在移动或战斗中，无法解散"
            return false
        }
        
        if (!team.isAtSect) {
            _errorMessage.value = "队伍不在宗门，无法解散"
            return false
        }
        
        viewModelScope.launch {
            try {
                gameEngine.updateGameData { it.copy(battleTeam = null) }
                gameEngine.syncAllDiscipleStatuses()
                
                _battleTeamSlots.value = buildList {
                    repeat(2) { index ->
                        add(BattleTeamSlot(index, slotType = BattleSlotType.ELDER))
                    }
                    repeat(8) { index ->
                        add(BattleTeamSlot(index + 2, slotType = BattleSlotType.DISCIPLE))
                    }
                }
                
                closeCurrentDialog()
                _successMessage.value = "战斗队伍已解散"
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "解散队伍失败"
            }
        }
        return true
    }

    fun hasBattleTeam(): Boolean {
        return gameEngine.gameData.value.battleTeam != null
    }

    fun returnStationedBattleTeam() {
        val team = gameEngine.gameData.value.battleTeam
        if (team == null) {
            _errorMessage.value = "没有可召回的战斗队伍"
            return
        }
        
        if (!team.isAtSect) {
            _errorMessage.value = "队伍不在宗门，无法召回"
            return
        }
        
        if (!team.isIdle) {
            _errorMessage.value = "队伍正在移动或战斗中，无法召回"
            return
        }
        
        viewModelScope.launch {
            try {
                gameEngine.updateGameData { it.copy(battleTeam = null) }
                gameEngine.syncAllDiscipleStatuses()
                
                _battleTeamSlots.value = buildList {
                    repeat(2) { index ->
                        add(BattleTeamSlot(index, slotType = BattleSlotType.ELDER))
                    }
                    repeat(8) { index ->
                        add(BattleTeamSlot(index + 2, slotType = BattleSlotType.DISCIPLE))
                    }
                }
                
                closeCurrentDialog()
                _successMessage.value = "战斗队伍已召回"
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "召回队伍失败"
            }
        }
    }

    fun hasBattleTeamAtSect(): Boolean {
        val team = gameEngine.gameData.value.battleTeam
        return team != null && team.isAtSect
    }

    fun startBattleTeamMoveMode() {
        val team = gameEngine.gameData.value.battleTeam
        if (team == null || !team.isIdle || !team.isAtSect) {
            _errorMessage.value = "战斗队伍无法移动"
            return
        }
        _battleTeamMoveMode.value = true
        closeCurrentDialog()
    }

    fun cancelBattleTeamMoveMode() {
        _battleTeamMoveMode.value = false
    }

    fun selectBattleTeamTarget(targetSectId: String) {
        val data = gameEngine.gameData.value
        val playerSect = data.worldMapSects.find { it.isPlayerSect }
        val targetSect = data.worldMapSects.find { it.id == targetSectId }
        
        if (playerSect == null || targetSect == null) {
            _errorMessage.value = "无效的目标宗门"
            _battleTeamMoveMode.value = false
            return
        }
        
        if (targetSect.isPlayerSect) {
            _errorMessage.value = "不能攻击自己的宗门"
            _battleTeamMoveMode.value = false
            return
        }
        
        if (targetSect.isPlayerOccupied) {
            _errorMessage.value = "该宗门已被占领"
            _battleTeamMoveMode.value = false
            return
        }
        
        if (!playerSect.connectedSectIds.contains(targetSectId)) {
            _errorMessage.value = "目标宗门不在可达路线上"
            _battleTeamMoveMode.value = false
            return
        }
        
        viewModelScope.launch {
            gameEngine.startBattleTeamMove(targetSectId)
            _battleTeamMoveMode.value = false
        }
    }

    fun getMovableTargetSectIds(): List<String> {
        val data = gameEngine.gameData.value
        val playerSect = data.worldMapSects.find { it.isPlayerSect } ?: return emptyList()
        
        return data.worldMapSects.filter { sect ->
            !sect.isPlayerSect && !sect.isPlayerOccupied && 
            AISectAttackManager.isRouteConnected(playerSect, sect, data)
        }.map { it.id }
    }

    fun getDisciplePosition(discipleId: String): String? {
        val gameData = gameEngine.gameData.value
        val elderSlots = gameData.elderSlots

        if (elderSlots.viceSectMaster == discipleId) return "副掌门"
        if (elderSlots.herbGardenElder == discipleId) return "灵药宛长老"
        if (elderSlots.alchemyElder == discipleId) return "丹鼎殿长老"
        if (elderSlots.forgeElder == discipleId) return "天工峰长老"
        if (elderSlots.outerElder == discipleId) return "外门执事"
        if (elderSlots.preachingElder == discipleId) return "问道峰传道长老"
        if (elderSlots.lawEnforcementElder == discipleId) return "执法长老"
        if (elderSlots.innerElder == discipleId) return "内门执事"
        if (elderSlots.qingyunPreachingElder == discipleId) return "青云峰传道长老"

        if (elderSlots.preachingMasters.any { it.discipleId == discipleId }) return "问道峰传道师"
        if (elderSlots.qingyunPreachingMasters.any { it.discipleId == discipleId }) return "青云峰传道师"

        if (elderSlots.herbGardenDisciples.any { it.discipleId == discipleId }) return "灵药宛亲传弟子"
        if (elderSlots.alchemyDisciples.any { it.discipleId == discipleId }) return "丹鼎殿亲传弟子"
        if (elderSlots.forgeDisciples.any { it.discipleId == discipleId }) return "天工峰亲传弟子"

        if (elderSlots.lawEnforcementDisciples.any { it.discipleId == discipleId }) return "执法弟子"

        if (gameData.spiritMineSlots.any { it.discipleId == discipleId }) return "采矿弟子"
        if (elderSlots.spiritMineDeaconDisciples.any { it.discipleId == discipleId }) return "灵矿执事"

        return null
    }

    fun hasDisciplePosition(discipleId: String): Boolean {
        return getDisciplePosition(discipleId) != null
    }

    private fun getWorkStatusPositionIds(): List<String> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        return listOfNotNull(elderSlots.viceSectMaster) +
               elderSlots.preachingMasters.mapNotNull { it.discipleId } +
               elderSlots.qingyunPreachingMasters.mapNotNull { it.discipleId } +
               elderSlots.lawEnforcementDisciples.mapNotNull { it.discipleId } +
               elderSlots.spiritMineDeaconDisciples.mapNotNull { it.discipleId }
    }

    fun isReserveDisciple(discipleId: String): Boolean {
        val elderSlots = gameEngine.gameData.value.elderSlots
        return elderSlots.lawEnforcementReserveDisciples.any { it.discipleId == discipleId } ||
               elderSlots.herbGardenReserveDisciples.any { it.discipleId == discipleId } ||
               elderSlots.alchemyReserveDisciples.any { it.discipleId == discipleId } ||
               elderSlots.forgeReserveDisciples.any { it.discipleId == discipleId }
    }

    fun isPositionWorkStatus(discipleId: String): Boolean {
        return getWorkStatusPositionIds().contains(discipleId)
    }

    fun getAvailableDisciplesForBattleTeamSlot(): List<DiscipleAggregate> {
        val battleTeam = gameEngine.gameData.value.battleTeam ?: return emptyList()
        val currentSlotDiscipleIds = battleTeam.slots.mapNotNull { it.discipleId }
        
        return disciples.value.filter { disciple ->
            disciple.isAlive &&
            disciple.realmLayer > 0 &&
            disciple.status == DiscipleStatus.IDLE &&
            !currentSlotDiscipleIds.contains(disciple.id) &&
            !isPositionWorkStatus(disciple.id)
        }.sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun addDiscipleToBattleTeamSlot(slotIndex: Int, discipleId: String) {
        val battleTeam = gameEngine.gameData.value.battleTeam ?: return
        val disciple = disciples.value.find { it.id == discipleId } ?: return
        
        val updatedSlots = battleTeam.slots.map { slot ->
            if (slot.index == slotIndex) {
                slot.copy(
                    discipleId = disciple.id,
                    discipleName = disciple.name,
                    discipleRealm = disciple.realmName,
                    isAlive = true
                )
            } else {
                slot
            }
        }
        
        viewModelScope.launch {
            gameEngine.updateGameData { it.copy(battleTeam = battleTeam.copy(slots = updatedSlots)) }
            gameEngine.updateDiscipleStatus(discipleId, DiscipleStatus.IN_TEAM)
        }
    }
}


