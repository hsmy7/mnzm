package com.xianxia.sect.ui.game

import android.content.ComponentCallbacks2
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.data.ForgeRecipeDatabase
import com.xianxia.sect.core.data.PillRecipeDatabase
import com.xianxia.sect.core.engine.AISectAttackManager
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.model.*
import com.xianxia.sect.data.GameRepository
import com.xianxia.sect.data.SaveManager
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.model.SaveSlot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class GameViewModel @Inject constructor(
    private val repository: GameRepository,
    private val gameEngine: GameEngine,
    private val saveManager: SaveManager
) : ViewModel() {

    companion object {
        private const val TAG = "GameViewModel"
        
        private const val MB = 1024 * 1024L
        
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

    val gameData: StateFlow<GameData> = gameEngine.gameData
        .stateIn(viewModelScope, SharingStarted.Eagerly, gameEngine.gameData.value)

    val disciples: StateFlow<List<Disciple>> = gameEngine.disciples
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val equipment: StateFlow<List<Equipment>> = gameEngine.equipment
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val manuals: StateFlow<List<Manual>> = gameEngine.manuals
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val pills: StateFlow<List<Pill>> = gameEngine.pills
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val materials: StateFlow<List<Material>> = gameEngine.materials
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val herbs: StateFlow<List<Herb>> = gameEngine.herbs
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val seeds: StateFlow<List<Seed>> = gameEngine.seeds
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val teams: StateFlow<List<ExplorationTeam>> = gameEngine.teams
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val buildingSlots: StateFlow<List<BuildingSlot>> = gameEngine.buildingSlots
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val events: StateFlow<List<GameEvent>> = gameEngine.events
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val battleLogs: StateFlow<List<BattleLog>> = gameEngine.battleLogs
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val alchemySlots: StateFlow<List<AlchemySlot>> = gameEngine.alchemySlots
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val highFrequencyData: StateFlow<GameEngine.HighFrequencyData> = gameEngine.highFrequencyData
        .stateIn(viewModelScope, SharingStarted.Eagerly, GameEngine.HighFrequencyData())

    val realtimeCultivation: StateFlow<Map<String, Double>> = gameEngine.realtimeCultivation
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    // 加载状态
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // 保存状态
    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _loadingProgress = MutableStateFlow(0f)
    val loadingProgress: StateFlow<Float> = _loadingProgress.asStateFlow()
    
    // 游戏已加载标志（用于Activity重建时判断是否需要重新加载）
    private var _isGameLoaded = false
    
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

    private val _showSaveDialog = MutableStateFlow(false)
    val showSaveDialog: StateFlow<Boolean> = _showSaveDialog.asStateFlow()

    private val _showMonthlySalaryDialog = MutableStateFlow(false)
    val showMonthlySalaryDialog: StateFlow<Boolean> = _showMonthlySalaryDialog.asStateFlow()

    private val _autoSaveInterval = MutableStateFlow(5)
    val autoSaveInterval: StateFlow<Int> = _autoSaveInterval.asStateFlow()

    private val _showBuildingDetailDialog = MutableStateFlow(false)
    val showBuildingDetailDialog: StateFlow<Boolean> = _showBuildingDetailDialog.asStateFlow()

    private val _selectedBuildingId = MutableStateFlow<String?>(null)
    val selectedBuildingId: StateFlow<String?> = _selectedBuildingId.asStateFlow()

    private val _selectedPlantSlotIndex = MutableStateFlow<Int?>(null)
    val selectedPlantSlotIndex: StateFlow<Int?> = _selectedPlantSlotIndex.asStateFlow()

    val forgeSlots: StateFlow<List<ForgeSlot>> = gameEngine.buildingSlots
        .map { slots -> 
            slots.filter { it.buildingId == "forge" }.map { buildingSlot ->
                val recipe = buildingSlot.recipeId?.let { 
                    com.xianxia.sect.core.data.ForgeRecipeDatabase.getRecipeById(it) 
                }
                ForgeSlot(
                    id = buildingSlot.id,
                    slotIndex = buildingSlot.slotIndex,
                    recipeId = buildingSlot.recipeId,
                    recipeName = buildingSlot.recipeName,
                    equipmentName = recipe?.name ?: "",
                    equipmentRarity = recipe?.rarity ?: 1,
                    startYear = buildingSlot.startYear,
                    startMonth = buildingSlot.startMonth,
                    duration = buildingSlot.duration,
                    status = when (buildingSlot.status) {
                        com.xianxia.sect.core.model.SlotStatus.WORKING -> ForgeSlotStatus.WORKING
                        com.xianxia.sect.core.model.SlotStatus.COMPLETED -> ForgeSlotStatus.FINISHED
                        else -> ForgeSlotStatus.IDLE
                    }
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _showAlchemyDialog = MutableStateFlow(false)
    val showAlchemyDialog: StateFlow<Boolean> = _showAlchemyDialog.asStateFlow()

    private val _showForgeDialog = MutableStateFlow(false)
    val showForgeDialog: StateFlow<Boolean> = _showForgeDialog.asStateFlow()

    private val _showHerbGardenDialog = MutableStateFlow(false)
    val showHerbGardenDialog: StateFlow<Boolean> = _showHerbGardenDialog.asStateFlow()

    private val _showSpiritMineDialog = MutableStateFlow(false)
    val showSpiritMineDialog: StateFlow<Boolean> = _showSpiritMineDialog.asStateFlow()

    private val _showLibraryDialog = MutableStateFlow(false)
    val showLibraryDialog: StateFlow<Boolean> = _showLibraryDialog.asStateFlow()

    private val _showWenDaoPeakDialog = MutableStateFlow(false)
    val showWenDaoPeakDialog: StateFlow<Boolean> = _showWenDaoPeakDialog.asStateFlow()

    private val _showRecruitDialog = MutableStateFlow(false)
    val showRecruitDialog: StateFlow<Boolean> = _showRecruitDialog.asStateFlow()

    private val _showDiplomacyDialog = MutableStateFlow(false)
    val showDiplomacyDialog: StateFlow<Boolean> = _showDiplomacyDialog.asStateFlow()

    private val _showInventoryDialog = MutableStateFlow(false)
    val showInventoryDialog: StateFlow<Boolean> = _showInventoryDialog.asStateFlow()

    private val _showMerchantDialog = MutableStateFlow(false)
    val showMerchantDialog: StateFlow<Boolean> = _showMerchantDialog.asStateFlow()

    private val _showEventLogDialog = MutableStateFlow(false)
    val showEventLogDialog: StateFlow<Boolean> = _showEventLogDialog.asStateFlow()

    private val _showSalaryConfigDialog = MutableStateFlow(false)
    val showSalaryConfigDialog: StateFlow<Boolean> = _showSalaryConfigDialog.asStateFlow()

    // 宗门管理对话框状态
    private val _showSectManagementDialog = MutableStateFlow(false)
    val showSectManagementDialog: StateFlow<Boolean> = _showSectManagementDialog.asStateFlow()

    private val _showWorldMapDialog = MutableStateFlow(false)
    val showWorldMapDialog: StateFlow<Boolean> = _showWorldMapDialog.asStateFlow()

    private val _showSecretRealmDialog = MutableStateFlow(false)
    val showSecretRealmDialog: StateFlow<Boolean> = _showSecretRealmDialog.asStateFlow()

    private val _showTianshuHallDialog = MutableStateFlow(false)
    val showTianshuHallDialog: StateFlow<Boolean> = _showTianshuHallDialog.asStateFlow()

    private val _showLawEnforcementHallDialog = MutableStateFlow(false)
    val showLawEnforcementHallDialog: StateFlow<Boolean> = _showLawEnforcementHallDialog.asStateFlow()

    private val _showMissionHallDialog = MutableStateFlow(false)
    val showMissionHallDialog: StateFlow<Boolean> = _showMissionHallDialog.asStateFlow()

    private val _showReflectionCliffDialog = MutableStateFlow(false)
    val showReflectionCliffDialog: StateFlow<Boolean> = _showReflectionCliffDialog.asStateFlow()

    private val _showQingyunPeakDialog = MutableStateFlow(false)
    val showQingyunPeakDialog: StateFlow<Boolean> = _showQingyunPeakDialog.asStateFlow()

    private val _showBattleTeamDialog = MutableStateFlow(false)
    val showBattleTeamDialog: StateFlow<Boolean> = _showBattleTeamDialog.asStateFlow()

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

    private val _isPaused = MutableStateFlow(true)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _gameLog = MutableStateFlow<List<String>>(emptyList())
    val gameLog: StateFlow<List<String>> = _gameLog.asStateFlow()

    private val _notifications = MutableStateFlow<List<String>>(emptyList())
    val notifications: StateFlow<List<String>> = _notifications.asStateFlow()

    private var gameLoopJob: Job? = null
    private var secondTickJob: Job? = null
    private var dayAccumulator = 0.0
    private var autoSaveMonthCounter = 0

    // 游戏循环错误计数器
    private var consecutiveErrorCount = 0
    private var totalErrorCount = 0
    private val maxConsecutiveErrors = 10 // 连续错误超过此数值时暂停游戏

    init {
        _saveSlots.value = saveManager.getSaveSlots()
    }

    private fun canPerformSaveOperation(): Boolean {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        
        val usedMemory = totalMemory - freeMemory
        val availableMemory = maxMemory - usedMemory
        
        val threshold = maxOf(
            (maxMemory * 0.1).toLong(),
            100 * 1024 * 1024L
        )

        Log.d(TAG, "Memory status: max=${maxMemory / MB}MB, " +
                "total=${totalMemory / MB}MB, free=${freeMemory / MB}MB, " +
                "used=${usedMemory / MB}MB, available=${availableMemory / MB}MB, " +
                "threshold=${threshold / MB}MB")

        return availableMemory > threshold
    }

    private suspend fun performGarbageCollection() {
        Log.d(TAG, "Performing garbage collection before save/load")
        withContext(Dispatchers.IO) {
            System.gc()
            delay(100)
        }
    }

    private val _isTimeRunning = MutableStateFlow(false)
    val isTimeRunning: StateFlow<Boolean> = _isTimeRunning.asStateFlow()

    private fun startGameLoop() {
        gameLoopJob?.cancel()
        secondTickJob?.cancel()

        dayAccumulator = 0.0
        autoSaveMonthCounter = 0
        consecutiveErrorCount = 0
        totalErrorCount = 0

        _isTimeRunning.value = true
        _isPaused.value = false

        Log.d(TAG, "Game loop started")

        gameLoopJob = viewModelScope.launch {
            while (isActive) {
                try {
                    delay(GameConfig.Time.TICK_INTERVAL)

                    if (!_isPaused.value) {
                        var tickErrorOccurred = false
                        
                        // 处理 processSecondTick
                        try {
                            gameEngine.processSecondTick()
                            consecutiveErrorCount = 0 // 成功执行后重置连续错误计数
                        } catch (e: Exception) {
                            tickErrorOccurred = true
                            consecutiveErrorCount++
                            totalErrorCount++
                            val gameData = gameEngine.gameData.value
                            Log.e(TAG, "Error in processSecondTick (consecutive: $consecutiveErrorCount, total: $totalErrorCount) - " +
                                "Game: Year=${gameData.gameYear}, Month=${gameData.gameMonth}, Day=${gameData.gameDay}, " +
                                "Sect=${gameData.sectName}", e)
                        }

                        val daysPerTick = GameConfig.Time.DAYS_PER_MONTH.toDouble() / 
                            (GameConfig.Time.SECONDS_PER_REAL_MONTH * GameConfig.Time.TICKS_PER_SECOND)
                        dayAccumulator += daysPerTick * _timeScale.value
                        
                        while (dayAccumulator >= 1.0) {
                            dayAccumulator -= 1.0
                            try {
                                gameEngine.advanceDay()
                                consecutiveErrorCount = 0
                            } catch (e: Exception) {
                                tickErrorOccurred = true
                                consecutiveErrorCount++
                                totalErrorCount++
                                val gameData = gameEngine.gameData.value
                                Log.e(TAG, "Error in advanceDay (consecutive: $consecutiveErrorCount, total: $totalErrorCount) - " +
                                    "Game: Year=${gameData.gameYear}, Month=${gameData.gameMonth}, Day=${gameData.gameDay}, " +
                                    "Sect=${gameData.sectName}", e)
                            }
                            autoSaveMonthCounter++
                        }
                        
                        val autoSaveInterval = gameEngine.gameData.value.autoSaveIntervalMonths
                        if (autoSaveInterval > 0 && autoSaveMonthCounter >= autoSaveInterval * GameConfig.Time.DAYS_PER_MONTH) {
                            autoSaveMonthCounter = 0
                            try {
                                performAutoSave()
                            } catch (e: Exception) {
                                Log.e(TAG, "Error in performAutoSave", e)
                            }
                        }
                        
                        // 检查连续错误次数，过多时暂停游戏
                        if (consecutiveErrorCount >= maxConsecutiveErrors) {
                            Log.e(TAG, "Game loop paused due to too many consecutive errors ($consecutiveErrorCount)")
                            _isPaused.value = true
                            _errorMessage.value = "游戏因连续错误已暂停，请检查日志或重新加载存档"
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in game loop outer try-catch", e)
                }
            }
        }
    }
    
    fun performAutoSave() {
        viewModelScope.launch {
            try {
                val snapshot = gameEngine.getStateSnapshot()
                val currentSlot = snapshot.gameData.currentSlot
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
                    slots = snapshot.buildingSlots,
                    events = snapshot.events,
                    battleLogs = snapshot.battleLogs,
                    alliances = snapshot.alliances,
                    supportTeams = snapshot.supportTeams,
                    alchemySlots = snapshot.alchemySlots
                )

                val success = saveManager.saveAsync(currentSlot, saveData)
                if (success) {
                    _saveSlots.value = saveManager.getSaveSlots()
                    Log.d(TAG, "Auto save completed for slot: $currentSlot")
                } else {
                    Log.e(TAG, "Auto save failed for slot: $currentSlot")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Auto save failed: ${e.message}", e)
            }
        }
    }

    /**
     * 创建当前游戏状态的存档数据
     * 用于紧急保存等场景
     * @return 当前游戏状态的 SaveData 对象
     */
    suspend fun createSaveData(): SaveData {
        val snapshot = gameEngine.getStateSnapshot()
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
            slots = snapshot.buildingSlots,
            events = snapshot.events,
            battleLogs = snapshot.battleLogs,
            alliances = snapshot.alliances,
            supportTeams = snapshot.supportTeams,
            alchemySlots = snapshot.alchemySlots
        )
    }
    
    /**
     * 创建当前游戏状态的存档数据（同步版本）
     * 用于崩溃处理等紧急场景，不使用协程锁，可能读取到不一致状态
     * 仅在紧急保存时使用，正常保存应使用 createSaveData()
     * @return 当前游戏状态的 SaveData 对象
     */
    fun createSaveDataSync(): SaveData {
        val snapshot = gameEngine.getStateSnapshotSync()
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
            slots = snapshot.buildingSlots,
            events = snapshot.events,
            battleLogs = snapshot.battleLogs,
            alliances = snapshot.alliances,
            supportTeams = snapshot.supportTeams,
            alchemySlots = snapshot.alchemySlots
        )
    }

    private fun stopGameLoop() {
        try {
            gameLoopJob?.cancel()
            secondTickJob?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping game loop", e)
        } finally {
            gameLoopJob = null
            secondTickJob = null
            _isTimeRunning.value = false
            _isPaused.value = true
        }
    }

    // 开始新游戏
    fun startNewGame(sectName: String, slot: Int = 1) {
        if (_isLoading.value && _loadingProgress.value < PROGRESS_COMPLETE) {
            Log.w(TAG, "Already loading with progress ${_loadingProgress.value}, ignoring startNewGame request")
            return
        }
        
        if (_isGameLoaded) {
            Log.w(TAG, "Game already loaded, ignoring startNewGame request")
            return
        }
        
        Log.i(TAG, "=== startNewGame BEGIN === sectName=$sectName, slot=$slot")
        val startTime = System.currentTimeMillis()
        
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _loadingProgress.value = PROGRESS_START
                
                _loadingProgress.value = PROGRESS_ENGINE_INIT
                gameEngine.createNewGame(sectName)
                gameEngine.updateGameData { it.copy(currentSlot = slot) }
                Log.d(TAG, "Game engine created new game, elapsed=${System.currentTimeMillis() - startTime}ms")
                
                _loadingProgress.value = PROGRESS_SAVE_COMPLETE
                saveGame(slot.toString())
                Log.d(TAG, "Game saved to slot $slot, elapsed=${System.currentTimeMillis() - startTime}ms")
                
                _loadingProgress.value = PROGRESS_GAME_LOOP_START
                startGameLoop()
                Log.d(TAG, "Game loop started, isPaused=${_isPaused.value}")
                
                _isGameLoaded = true
                _loadingProgress.value = PROGRESS_COMPLETE
                
                // 记录游戏状态快照
                val gd = gameEngine.gameData.value
                Log.i(TAG, "=== startNewGame SUCCESS === " +
                    "sectName=${gd.sectName}, year=${gd.gameYear}, month=${gd.gameMonth}, day=${gd.gameDay}, " +
                    "spiritStones=${gd.spiritStones}, disciples=${gameEngine.disciples.value.size}, " +
                    "totalElapsed=${System.currentTimeMillis() - startTime}ms")
            } catch (e: Exception) {
                Log.e(TAG, "=== startNewGame FAILED === error=${e.message}", e)
                _errorMessage.value = e.message ?: "开始新游戏失败"
            } finally {
                delay(LOADING_COMPLETE_DISPLAY_DURATION) // 确保用户能看到 100% 状态
                _isLoading.value = false
                _loadingProgress.value = PROGRESS_START
            }
        }
    }

    fun isGameAlreadyLoaded(): Boolean {
        return _isGameLoaded && gameEngine.gameData.value.sectName.isNotEmpty()
    }

    fun loadGame(saveSlot: SaveSlot) {
        if (_isLoading.value) {
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
        
        viewModelScope.launch {
            _isLoading.value = true
            try {
                if (_isGameLoaded) {
                    Log.i(TAG, "Game already loaded, will reload from slot ${saveSlot.slot}")
                    stopGameLoop()
                    _isGameLoaded = false
                }
                
                performGarbageCollection()
                
                val saveData = withTimeoutOrNull(30_000L) {
                    saveManager.loadAsync(saveSlot.slot)
                }
                if (saveData == null) {
                    Log.e(TAG, "=== loadGame FAILED === timeout or null for slot ${saveSlot.slot}")
                    _errorMessage.value = "读档超时或存档为空，请重试"
                    return@launch
                }
                if (saveData != null) {
                    gameEngine.loadData(
                        gameData = saveData.gameData.copy(currentSlot = saveSlot.slot),
                        disciples = saveData.disciples,
                        equipment = saveData.equipment,
                        manuals = saveData.manuals,
                        pills = saveData.pills,
                        materials = saveData.materials,
                        herbs = saveData.herbs,
                        seeds = saveData.seeds,
                        teams = saveData.teams,
                        slots = saveData.slots,
                        events = saveData.events,
                        battleLogs = saveData.battleLogs,
                        alliances = saveData.alliances ?: emptyList(),
                        supportTeams = saveData.supportTeams?.map { team ->
                            if (team.duration == 0 && team.status == "moving") {
                                team.copy(duration = 1, moveProgress = 1f, status = "stationed")
                            } else {
                                team
                            }
                        } ?: emptyList(),
                        alchemySlots = saveData.alchemySlots
                    )
                    startGameLoop()
                    _isGameLoaded = true
                    _successMessage.value = "读档成功"
                    
                    val gd = gameEngine.gameData.value
                    Log.i(TAG, "=== loadGame SUCCESS === " +
                        "sectName=${gd.sectName}, year=${gd.gameYear}, month=${gd.gameMonth}, day=${gd.gameDay}, " +
                        "spiritStones=${gd.spiritStones}, disciples=${gameEngine.disciples.value.size}, " +
                        "equipment=${gameEngine.equipment.value.size}, manuals=${gameEngine.manuals.value.size}, " +
                        "elapsed=${System.currentTimeMillis() - startTime}ms")
                } else {
                    Log.e(TAG, "=== loadGame FAILED === saveData is null for slot ${saveSlot.slot}")
                    _errorMessage.value = "存档为空或不存在"
                }
            } catch (e: Exception) {
                Log.e(TAG, "=== loadGame FAILED === error=${e.message}", e)
                _errorMessage.value = "加载游戏失败: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadGameFromSlot(slot: Int) {
        if (_isLoading.value) {
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
        
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _loadingProgress.value = PROGRESS_START
                
                if (_isGameLoaded) {
                    Log.i(TAG, "Game already loaded, will reload from slot $slot")
                    stopGameLoop()
                    _isGameLoaded = false
                }
                
                performGarbageCollection()
                
                val saveData = withTimeoutOrNull(30_000L) {
                    saveManager.loadAsync(slot)
                }
                if (saveData != null) {
                    _loadingProgress.value = PROGRESS_DATA_LOAD
                    gameEngine.loadData(
                        gameData = saveData.gameData.copy(currentSlot = slot),
                        disciples = saveData.disciples,
                        equipment = saveData.equipment,
                        manuals = saveData.manuals,
                        pills = saveData.pills,
                        materials = saveData.materials,
                        herbs = saveData.herbs,
                        seeds = saveData.seeds,
                        teams = saveData.teams,
                        slots = saveData.slots,
                        events = saveData.events,
                        battleLogs = saveData.battleLogs,
                        alliances = saveData.alliances ?: emptyList(),
                        supportTeams = saveData.supportTeams?.map { team ->
                            if (team.duration == 0 && team.status == "moving") {
                                team.copy(duration = 1, moveProgress = 1f, status = "stationed")
                            } else {
                                team
                            }
                        } ?: emptyList(),
                        alchemySlots = saveData.alchemySlots
                    )
                    _loadingProgress.value = PROGRESS_GAME_LOOP_START
                    startGameLoop()
                    _isGameLoaded = true
                    _loadingProgress.value = PROGRESS_COMPLETE
                    
                    val gd = gameEngine.gameData.value
                    Log.i(TAG, "=== loadGameFromSlot SUCCESS === " +
                        "sectName=${gd.sectName}, year=${gd.gameYear}, month=${gd.gameMonth}, day=${gd.gameDay}, " +
                        "spiritStones=${gd.spiritStones}, disciples=${gameEngine.disciples.value.size}, " +
                        "equipment=${gameEngine.equipment.value.size}, manuals=${gameEngine.manuals.value.size}, " +
                        "elapsed=${System.currentTimeMillis() - startTime}ms")
                } else if (saveData == null) {
                    Log.e(TAG, "=== loadGameFromSlot FAILED === timeout after 30 seconds")
                    _errorMessage.value = "读档超时，请重试"
                } else {
                    Log.e(TAG, "=== loadGameFromSlot FAILED === saveData is null for slot $slot")
                    _errorMessage.value = "存档为空或不存在"
                }
            } catch (e: Exception) {
                Log.e(TAG, "=== loadGameFromSlot FAILED === error=${e.message}", e)
                _errorMessage.value = "加载游戏失败: ${e.message}"
            } finally {
                delay(LOADING_COMPLETE_DISPLAY_DURATION)
                _isLoading.value = false
                _loadingProgress.value = PROGRESS_START
            }
        }
    }

    fun saveGame(slotId: String? = null) {
        if (_isSaving.value) {
            Log.w(TAG, "Already saving, ignoring saveGame request")
            return
        }
        
        if (!canPerformSaveOperation()) {
            Log.e(TAG, "=== saveGame FAILED === insufficient memory")
            _errorMessage.value = "内存不足，无法保存。请关闭其他应用后重试。"
            return
        }
        
        val slot = slotId?.toIntOrNull() ?: gameEngine.gameData.value.currentSlot
        Log.i(TAG, "=== saveGame BEGIN === slot=$slot, slotId=$slotId")
        val startTime = System.currentTimeMillis()
        
        viewModelScope.launch {
            _isSaving.value = true
            try {
                performGarbageCollection()
                val snapshot = gameEngine.getStateSnapshot()
                if (snapshot.gameData.sectName.isBlank()) {
                    Log.e(TAG, "=== saveGame FAILED === gameData not initialized (sectName is blank)")
                    _errorMessage.value = "游戏数据未初始化"
                    return@launch
                }
                val updatedGameData = snapshot.gameData.copy(currentSlot = slot)
                val saveData = SaveData(
                    gameData = updatedGameData,
                    disciples = snapshot.disciples,
                    equipment = snapshot.equipment,
                    manuals = snapshot.manuals,
                    pills = snapshot.pills,
                    materials = snapshot.materials,
                    herbs = snapshot.herbs,
                    seeds = snapshot.seeds,
                    teams = snapshot.teams,
                    slots = snapshot.buildingSlots,
                    events = snapshot.events,
                    battleLogs = snapshot.battleLogs,
                    alliances = snapshot.alliances,
                    supportTeams = snapshot.supportTeams,
                    alchemySlots = snapshot.alchemySlots
                )
                val success = withTimeoutOrNull(30_000L) {
                    saveManager.saveAsync(slot, saveData)
                }
                if (success == true) {
                    gameEngine.updateGameData { updatedGameData }
                    _saveSlots.value = saveManager.getSaveSlots()
                    _successMessage.value = "游戏保存成功"
                    
                    Log.i(TAG, "=== saveGame SUCCESS === " +
                        "sectName=${updatedGameData.sectName}, year=${updatedGameData.gameYear}, " +
                        "month=${updatedGameData.gameMonth}, day=${updatedGameData.gameDay}, " +
                        "spiritStones=${updatedGameData.spiritStones}, " +
                        "disciples=${saveData.disciples.size}, equipment=${saveData.equipment.size}, " +
                        "manuals=${saveData.manuals.size}, elapsed=${System.currentTimeMillis() - startTime}ms")
                } else if (success == null) {
                    Log.e(TAG, "=== saveGame FAILED === timeout after 30 seconds")
                    if (saveManager.isSaveCorrupted(slot)) {
                        saveManager.restoreFromBackup(slot)
                        Log.w(TAG, "Save may be corrupted, attempted to restore from backup")
                    }
                    _errorMessage.value = "保存超时，请重试"
                } else {
                    Log.e(TAG, "=== saveGame FAILED === saveManager.saveAsync returned false")
                    _errorMessage.value = "保存失败，请重试"
                }
            } catch (e: Exception) {
                Log.e(TAG, "=== saveGame FAILED === error=${e.message}", e)
                _errorMessage.value = "保存失败: ${e.message}"
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun saveToSlot(slotIndex: Int) {
        if (_isSaving.value) {
            Log.w(TAG, "Already saving, ignoring saveToSlot request")
            return
        }
        
        if (!canPerformSaveOperation()) {
            Log.e(TAG, "=== saveToSlot FAILED === insufficient memory")
            _errorMessage.value = "内存不足，无法保存。请关闭其他应用后重试。"
            return
        }
        
        Log.i(TAG, "=== saveToSlot BEGIN === slotIndex=$slotIndex")
        val startTime = System.currentTimeMillis()
        
        viewModelScope.launch {
            try {
                _isSaving.value = true
                performGarbageCollection()
                val slot = slotIndex
                val snapshot = gameEngine.getStateSnapshot()
                if (snapshot.gameData.sectName.isBlank()) {
                    Log.e(TAG, "=== saveToSlot FAILED === gameData not initialized (sectName is blank)")
                    _errorMessage.value = "游戏数据未初始化"
                    return@launch
                }
                val updatedGameData = snapshot.gameData.copy(currentSlot = slot)
                val saveData = SaveData(
                    gameData = updatedGameData,
                    disciples = snapshot.disciples,
                    equipment = snapshot.equipment,
                    manuals = snapshot.manuals,
                    pills = snapshot.pills,
                    materials = snapshot.materials,
                    herbs = snapshot.herbs,
                    seeds = snapshot.seeds,
                    teams = snapshot.teams,
                    slots = snapshot.buildingSlots,
                    events = snapshot.events,
                    battleLogs = snapshot.battleLogs,
                    alliances = snapshot.alliances,
                    supportTeams = snapshot.supportTeams,
                    alchemySlots = snapshot.alchemySlots
                )
                val success = withTimeoutOrNull(30_000L) {
                    saveManager.saveAsync(slot, saveData)
                }
                if (success == true) {
                    gameEngine.updateGameData { updatedGameData }
                    _saveSlots.value = saveManager.getSaveSlots()
                    _successMessage.value = "游戏保存成功"
                    _showSaveDialog.value = false
                    
                    Log.i(TAG, "=== saveToSlot SUCCESS === " +
                        "sectName=${updatedGameData.sectName}, year=${updatedGameData.gameYear}, " +
                        "month=${updatedGameData.gameMonth}, day=${updatedGameData.gameDay}, " +
                        "spiritStones=${updatedGameData.spiritStones}, " +
                        "disciples=${saveData.disciples.size}, elapsed=${System.currentTimeMillis() - startTime}ms")
                } else if (success == null) {
                    Log.e(TAG, "=== saveToSlot FAILED === timeout after 30 seconds")
                    _errorMessage.value = "保存超时，请重试"
                } else {
                    Log.e(TAG, "=== saveToSlot FAILED === saveManager.saveAsync returned false")
                    _errorMessage.value = "保存失败，请重试"
                }
            } catch (e: Exception) {
                Log.e(TAG, "=== saveToSlot FAILED === error=${e.message}", e)
                _errorMessage.value = "保存失败: ${e.message}"
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun togglePause() {
        _isPaused.value = !_isPaused.value
    }

    fun pauseGame() {
        _isPaused.value = true
        // 可选：如果需要完全停止游戏循环，可以在这里调用stopGameLoop()
        // stopGameLoop()
    }

    fun resumeGame() {
        _isPaused.value = false
        // 如果之前停止了游戏循环，需要在这里重新启动
        // if (gameLoopJob == null) {
        //     startGameLoop()
        // }
    }

    fun setGameSpeed(speed: String) {
        // TODO: ʵ����Ϸ�ٶ�����
    }

    fun setTimeScale(scale: Int) {
        _timeScale.value = scale
    }

    val timeSpeed: StateFlow<Int> = _timeScale

    fun setTimeSpeed(speed: Int) {
        _timeScale.value = speed
    }

    fun setAutoSaveInterval(interval: Int) {
        _autoSaveInterval.value = interval
    }

    fun setAutoSaveIntervalMonths(interval: Int) {
        gameEngine.updateGameData { it.copy(autoSaveIntervalMonths = interval) }
    }

    fun resetAllDisciplesStatus() {
        gameEngine.resetAllDisciplesStatus()
    }

    fun showSaveManagementDialog() {
        // ˢ�´浵��λ��Ϣ
        _saveSlots.value = saveManager.getSaveSlots()
        _showSaveDialog.value = true
    }

    fun dismissSaveDialog() {
        _showSaveDialog.value = false
    }

    fun restartGame() {
        if (_isLoading.value || _isRestarting.value) {
            Log.w(TAG, "Already loading or restarting, ignoring restartGame request")
            return
        }
        
        viewModelScope.launch {
            try {
                _isRestarting.value = true

                val currentData = gameEngine.gameData.value
                val sectName = currentData.sectName.ifBlank { "QingYunSect" }
                val currentSlot = currentData.currentSlot.coerceAtLeast(1)
                
                gameEngine.restartGame()
                gameEngine.updateGameData { it.copy(sectName = sectName, currentSlot = currentSlot) }
                
                saveGame(currentSlot.toString())
                
                startGameLoop()
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to restart game"
            } finally {
                _isRestarting.value = false
            }
            return@launch
        }
    }

    fun showMonthlySalaryDialog() {
        _showMonthlySalaryDialog.value = true
    }

    fun dismissMonthlySalaryDialog() {
        _showMonthlySalaryDialog.value = false
    }

    // �������?
    fun showBuildingDetailDialog(buildingId: String) {
        _selectedBuildingId.value = buildingId
        _showBuildingDetailDialog.value = true
    }

    fun openBuildingDetailDialog(buildingId: String) {
        _selectedBuildingId.value = buildingId
        _showBuildingDetailDialog.value = true
    }

    fun closeBuildingDetailDialog() {
        _showBuildingDetailDialog.value = false
    }

    fun openAlchemyDialog() {
        _showAlchemyDialog.value = true
    }

    fun closeAlchemyDialog() {
        _showAlchemyDialog.value = false
    }

    fun openForgeDialog() {
        _showForgeDialog.value = true
    }

    fun closeForgeDialog() {
        _showForgeDialog.value = false
    }

    fun openHerbGardenDialog() {
        _showHerbGardenDialog.value = true
    }

    fun closeHerbGardenDialog() {
        _showHerbGardenDialog.value = false
    }

    fun openSpiritMineDialog() {
        _showSpiritMineDialog.value = true
    }

    fun closeSpiritMineDialog() {
        _showSpiritMineDialog.value = false
    }

    fun openLibraryDialog() {
        _showLibraryDialog.value = true
    }

    fun closeLibraryDialog() {
        _showLibraryDialog.value = false
    }

    fun openWenDaoPeakDialog() {
        _showWenDaoPeakDialog.value = true
    }

    fun closeWenDaoPeakDialog() {
        _showWenDaoPeakDialog.value = false
    }

    fun openRecruitDialog() {
        _showRecruitDialog.value = true
    }

    fun closeRecruitDialog() {
        _showRecruitDialog.value = false
    }

    fun openDiplomacyDialog() {
        _showDiplomacyDialog.value = true
    }

    fun closeDiplomacyDialog() {
        _showDiplomacyDialog.value = false
    }

    fun openTianshuHallDialog() {
        _showTianshuHallDialog.value = true
    }

    fun closeTianshuHallDialog() {
        _showTianshuHallDialog.value = false
    }

    fun openLawEnforcementHallDialog() {
        _showLawEnforcementHallDialog.value = true
    }

    fun closeLawEnforcementHallDialog() {
        _showLawEnforcementHallDialog.value = false
    }

    fun openMissionHallDialog() {
        _showMissionHallDialog.value = true
    }

    fun closeMissionHallDialog() {
        _showMissionHallDialog.value = false
    }

    fun openReflectionCliffDialog() {
        _showReflectionCliffDialog.value = true
    }

    fun closeReflectionCliffDialog() {
        _showReflectionCliffDialog.value = false
    }

    fun openQingyunPeakDialog() {
        _showQingyunPeakDialog.value = true
    }

    fun closeQingyunPeakDialog() {
        _showQingyunPeakDialog.value = false
    }
    
    fun setViceSectMaster(discipleId: String) {
        val disciple = disciples.value.find { it.id == discipleId }
        if (disciple == null) {
            _errorMessage.value = "弟子不存在"
            return
        }
        
        if (disciple.realm > 4) {
            _errorMessage.value = "副宗主需要达到炼虚境界"
            return
        }
        
        val currentSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = listOf(
            currentSlots.herbGardenElder,
            currentSlots.alchemyElder,
            currentSlots.forgeElder,
            currentSlots.libraryElder,
            currentSlots.outerElder,
            currentSlots.preachingElder,
            currentSlots.lawEnforcementElder,
            currentSlots.innerElder,
            currentSlots.qingyunPreachingElder
        ).filterNotNull()
        
        if (!allElderIds.contains(discipleId)) {
            _errorMessage.value = "副宗主需要由长老担任"
            return
        }
        
        gameEngine.updateGameData { 
            it.copy(elderSlots = currentSlots.copy(viceSectMaster = discipleId))
        }
    }
    
    fun removeViceSectMaster() {
        val currentSlots = gameEngine.gameData.value.elderSlots
        gameEngine.updateGameData { 
            it.copy(elderSlots = currentSlots.copy(viceSectMaster = null))
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
    
    fun getEligibleScoutDisciples(): List<Disciple> {
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
    
    private val _showRequestSupportDialog = MutableStateFlow(false)
    val showRequestSupportDialog: StateFlow<Boolean> = _showRequestSupportDialog.asStateFlow()
    
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
    
    fun openRequestSupportDialog() {
        _showRequestSupportDialog.value = true
    }
    
    fun closeRequestSupportDialog() {
        _showRequestSupportDialog.value = false
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
    
    fun requestSupport(allySectId: String, envoyDiscipleId: String) {
        viewModelScope.launch {
            try {
                val (success, message) = gameEngine.requestSupport(allySectId, envoyDiscipleId)
                if (success) {
                    closeRequestSupportDialog()
                } else {
                    _errorMessage.value = message
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "求援失败"
            }
        }
    }
    
    fun getEligibleEnvoyDisciples(sectLevel: Int): List<Disciple> {
        val requiredRealm = gameEngine.getEnvoyRealmRequirement(sectLevel)
        return disciples.value.filter { 
            it.isAlive && 
            it.status == DiscipleStatus.IDLE &&
            it.realm <= requiredRealm
        }
    }
    
    fun getEligibleRequestDisciples(): List<Disciple> {
        return disciples.value.filter { 
            it.isAlive && 
            it.status == DiscipleStatus.IDLE &&
            it.realm <= 7
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
        return gameEngine.getPlayerAllies()
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

    fun openMerchantDialog() {
        _showMerchantDialog.value = true
    }

    fun closeMerchantDialog() {
        _showMerchantDialog.value = false
    }

    fun openInventoryDialog() {
        _showInventoryDialog.value = true
    }

    fun closeInventoryDialog() {
        _showInventoryDialog.value = false
    }

    fun openEventLogDialog() {
        _showEventLogDialog.value = true
    }

    fun closeEventLogDialog() {
        _showEventLogDialog.value = false
    }

    fun openSalaryConfigDialog() {
        _showSalaryConfigDialog.value = true
    }

    fun closeSalaryConfigDialog() {
        _showSalaryConfigDialog.value = false
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
        _showSectManagementDialog.value = true
    }

    fun closeSectManagementDialog() {
        _showSectManagementDialog.value = false
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
                    else -> 6
                }
                if (disciple.realm > minRealm) {
                    val realmName = when (minRealm) {
                        4 -> "炼虚"
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
                
                val allElderIds = listOf(
                    elderSlots.viceSectMaster,
                    elderSlots.herbGardenElder,
                    elderSlots.alchemyElder,
                    elderSlots.forgeElder,
                    elderSlots.libraryElder,
                    elderSlots.outerElder,
                    elderSlots.preachingElder,
                    elderSlots.lawEnforcementElder,
                    elderSlots.innerElder,
                    elderSlots.qingyunPreachingElder
                )
                val allDirectDiscipleIds = listOf(
                    elderSlots.herbGardenDisciples,
                    elderSlots.alchemyDisciples,
                    elderSlots.forgeDisciples,
                    elderSlots.libraryDisciples,
                    elderSlots.preachingMasters,
                    elderSlots.lawEnforcementDisciples,
                    elderSlots.lawEnforcementReserveDisciples,
                    elderSlots.qingyunPreachingMasters,
                    elderSlots.spiritMineDeaconDisciples
                ).flatten().mapNotNull { it.discipleId }

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
                    "library" -> elderSlots.copy(
                        libraryElder = discipleId,
                        libraryDisciples = emptyList()
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
                        lawEnforcementElder = discipleId,
                        lawEnforcementDisciples = emptyList()
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
                        herbGardenElder = null,
                        herbGardenDisciples = emptyList()
                    )
                    "alchemy" -> elderSlots.copy(
                        alchemyElder = null,
                        alchemyDisciples = emptyList()
                    )
                    "forge" -> elderSlots.copy(
                        forgeElder = null,
                        forgeDisciples = emptyList()
                    )
                    "library" -> elderSlots.copy(
                        libraryElder = null,
                        libraryDisciples = emptyList()
                    )
                    "viceSectMaster" -> elderSlots.copy(
                        viceSectMaster = null
                    )
                    "outerElder" -> elderSlots.copy(
                        outerElder = null
                    )
                    "preachingElder" -> elderSlots.copy(
                        preachingElder = null,
                        preachingMasters = emptyList()
                    )
                    "lawEnforcementElder" -> elderSlots.copy(
                        lawEnforcementElder = null,
                        lawEnforcementDisciples = emptyList()
                    )
                    "innerElder" -> elderSlots.copy(
                        innerElder = null
                    )
                    "qingyunPreachingElder" -> elderSlots.copy(
                        qingyunPreachingElder = null,
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

    fun getViceSectMaster(): Disciple? {
        val viceSectMasterId = gameEngine.gameData.value.elderSlots.viceSectMaster
        return getElderDisciple(viceSectMasterId)
    }

    fun toggleSpiritMineBoost() {
        val currentPolicies = gameEngine.gameData.value.sectPolicies
        gameEngine.updateGameData { it.copy(sectPolicies = currentPolicies.copy(spiritMineBoost = !currentPolicies.spiritMineBoost)) }
    }

    fun isSpiritMineBoostEnabled(): Boolean {
        return gameEngine.gameData.value.sectPolicies.spiritMineBoost
    }

    fun getSpiritMineBoostEffect(): Double {
        return 0.2
    }

    fun toggleEnhancedSecurity(): Boolean {
        val currentPolicies = gameEngine.gameData.value.sectPolicies
        val currentStones = gameEngine.gameData.value.spiritStones
        
        if (!currentPolicies.enhancedSecurity && currentStones < 5000) {
            _errorMessage.value = "灵石不足5000，无法开启增强治安政策"
            return false
        }
        
        gameEngine.updateGameData { it.copy(sectPolicies = currentPolicies.copy(enhancedSecurity = !currentPolicies.enhancedSecurity)) }
        return true
    }

    fun isEnhancedSecurityEnabled(): Boolean {
        return gameEngine.gameData.value.sectPolicies.enhancedSecurity
    }

    fun getEnhancedSecurityBaseBonus(): Double {
        return 0.20
    }

    fun toggleAlchemyIncentive(): Boolean {
        val currentPolicies = gameEngine.gameData.value.sectPolicies
        val currentStones = gameEngine.gameData.value.spiritStones
        
        if (!currentPolicies.alchemyIncentive && currentStones < 3000) {
            _errorMessage.value = "灵石不足3000，无法开启丹道激励政策"
            return false
        }
        
        gameEngine.updateGameData { it.copy(sectPolicies = currentPolicies.copy(alchemyIncentive = !currentPolicies.alchemyIncentive)) }
        return true
    }

    fun isAlchemyIncentiveEnabled(): Boolean {
        return gameEngine.gameData.value.sectPolicies.alchemyIncentive
    }

    fun toggleForgeIncentive(): Boolean {
        val currentPolicies = gameEngine.gameData.value.sectPolicies
        val currentStones = gameEngine.gameData.value.spiritStones
        
        if (!currentPolicies.forgeIncentive && currentStones < 3000) {
            _errorMessage.value = "灵石不足3000，无法开启锻造激励政策"
            return false
        }
        
        gameEngine.updateGameData { it.copy(sectPolicies = currentPolicies.copy(forgeIncentive = !currentPolicies.forgeIncentive)) }
        return true
    }

    fun isForgeIncentiveEnabled(): Boolean {
        return gameEngine.gameData.value.sectPolicies.forgeIncentive
    }

    fun toggleHerbCultivation(): Boolean {
        val currentPolicies = gameEngine.gameData.value.sectPolicies
        val currentStones = gameEngine.gameData.value.spiritStones
        
        if (!currentPolicies.herbCultivation && currentStones < 3000) {
            _errorMessage.value = "灵石不足3000，无法开启灵药培育政策"
            return false
        }
        
        gameEngine.updateGameData { it.copy(sectPolicies = currentPolicies.copy(herbCultivation = !currentPolicies.herbCultivation)) }
        return true
    }

    fun isHerbCultivationEnabled(): Boolean {
        return gameEngine.gameData.value.sectPolicies.herbCultivation
    }

    fun toggleCultivationSubsidy(): Boolean {
        val currentPolicies = gameEngine.gameData.value.sectPolicies
        val currentStones = gameEngine.gameData.value.spiritStones
        
        if (!currentPolicies.cultivationSubsidy && currentStones < 4000) {
            _errorMessage.value = "灵石不足4000，无法开启修行津贴政策"
            return false
        }
        
        gameEngine.updateGameData { it.copy(sectPolicies = currentPolicies.copy(cultivationSubsidy = !currentPolicies.cultivationSubsidy)) }
        return true
    }

    fun isCultivationSubsidyEnabled(): Boolean {
        return gameEngine.gameData.value.sectPolicies.cultivationSubsidy
    }

    fun toggleManualResearch(): Boolean {
        val currentPolicies = gameEngine.gameData.value.sectPolicies
        val currentStones = gameEngine.gameData.value.spiritStones
        
        if (!currentPolicies.manualResearch && currentStones < 4000) {
            _errorMessage.value = "灵石不足4000，无法开启功法研习政策"
            return false
        }
        
        gameEngine.updateGameData { it.copy(sectPolicies = currentPolicies.copy(manualResearch = !currentPolicies.manualResearch)) }
        return true
    }

    fun isManualResearchEnabled(): Boolean {
        return gameEngine.gameData.value.sectPolicies.manualResearch
    }

    fun getViceSectMasterIntelligenceBonus(): Double {
        val viceSectMaster = getViceSectMaster() ?: return 0.0
        val intelligence = viceSectMaster.intelligence
        return ((intelligence - 50) / 5.0 * 0.01).coerceAtLeast(0.0)
    }

    fun getElderDisciple(elderId: String?): Disciple? {
        if (elderId == null) return null
        return disciples.value.find { it.id == elderId }
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
                
                val allElderIds = listOf(
                    elderSlots.herbGardenElder,
                    elderSlots.alchemyElder,
                    elderSlots.forgeElder,
                    elderSlots.libraryElder
                )
                val allDirectDiscipleIds = listOf(
                    elderSlots.herbGardenDisciples,
                    elderSlots.alchemyDisciples,
                    elderSlots.forgeDisciples,
                    elderSlots.libraryDisciples,
                    elderSlots.spiritMineDeaconDisciples
                ).flatten().mapNotNull { it.discipleId }

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

    fun getOuterElder(): Disciple? {
        val outerElderId = gameEngine.gameData.value.elderSlots.outerElder
        return getElderDisciple(outerElderId)
    }

    fun getPreachingElder(): Disciple? {
        val preachingElderId = gameEngine.gameData.value.elderSlots.preachingElder
        return getElderDisciple(preachingElderId)
    }

    fun getPreachingMasters(): List<DirectDiscipleSlot> {
        return gameEngine.gameData.value.elderSlots.preachingMasters
    }

    fun getLawEnforcementElder(): Disciple? {
        val elderId = gameEngine.gameData.value.elderSlots.lawEnforcementElder
        return getElderDisciple(elderId)
    }

    fun getLawEnforcementDisciples(): List<DirectDiscipleSlot> {
        return gameEngine.gameData.value.elderSlots.lawEnforcementDisciples
    }

    fun getLawEnforcementReserveDisciples(): List<DirectDiscipleSlot> {
        return gameEngine.gameData.value.elderSlots.lawEnforcementReserveDisciples
    }

    fun getLawEnforcementReserveDisciplesWithInfo(): List<Disciple> {
        val reserveSlots = gameEngine.gameData.value.elderSlots.lawEnforcementReserveDisciples
        val reserveIds = reserveSlots.mapNotNull { it.discipleId }.toSet()
        return disciples.value
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
                
                val currentReserveDisciples = gameEngine.gameData.value.elderSlots.lawEnforcementReserveDisciples
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
                val updatedElderSlots = gameEngine.gameData.value.elderSlots.copy(
                    lawEnforcementReserveDisciples = updatedReserveDisciples
                )
                gameEngine.updateGameData { it.copy(elderSlots = updatedElderSlots) }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "添加失败"
            }
        }
    }

    fun addReserveDisciples(discipleIds: List<String>) {
        viewModelScope.launch {
            try {
                val currentReserveDisciples = gameEngine.gameData.value.elderSlots.lawEnforcementReserveDisciples
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
                    val updatedElderSlots = gameEngine.gameData.value.elderSlots.copy(
                        lawEnforcementReserveDisciples = updatedReserveDisciples
                    )
                    gameEngine.updateGameData { it.copy(elderSlots = updatedElderSlots) }
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "添加失败"
            }
        }
    }

    fun removeReserveDisciple(discipleId: String) {
        viewModelScope.launch {
            try {
                val currentReserveDisciples = gameEngine.gameData.value.elderSlots.lawEnforcementReserveDisciples
                val updatedReserveDisciples = currentReserveDisciples.filter { it.discipleId != discipleId }
                val updatedElderSlots = gameEngine.gameData.value.elderSlots.copy(
                    lawEnforcementReserveDisciples = updatedReserveDisciples
                )
                gameEngine.updateGameData { it.copy(elderSlots = updatedElderSlots) }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "移除失败"
            }
        }
    }

    private fun isSelectableDisciple(disciple: Disciple): Boolean {
        return disciple.isAlive &&
               disciple.discipleType == "inner" &&
               disciple.age >= 5 &&
               disciple.realmLayer > 0 &&
               disciple.status == DiscipleStatus.IDLE
    }

    fun getAvailableDisciplesForSelection(): List<Disciple> {
        return disciples.value.filter { disciple ->
            disciple.isAlive &&
            disciple.status == DiscipleStatus.IDLE &&
            disciple.realmLayer > 0
        }
    }

    fun getAvailableDisciplesForLawEnforcementElder(): List<Disciple> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = listOf(
            elderSlots.viceSectMaster,
            elderSlots.herbGardenElder,
            elderSlots.alchemyElder,
            elderSlots.forgeElder,
            elderSlots.libraryElder,
            elderSlots.outerElder,
            elderSlots.preachingElder,
            elderSlots.lawEnforcementElder
        ).filterNotNull()

        val allDirectDiscipleIds = listOf(
            elderSlots.herbGardenDisciples,
            elderSlots.alchemyDisciples,
            elderSlots.forgeDisciples,
            elderSlots.libraryDisciples,
            elderSlots.preachingMasters,
            elderSlots.lawEnforcementDisciples,
            elderSlots.lawEnforcementReserveDisciples,
            elderSlots.spiritMineDeaconDisciples
        ).flatten().mapNotNull { it.discipleId }

        return disciples.value
            .filter { isSelectableDisciple(it) && it.realm <= 5 && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun getAvailableDisciplesForLawEnforcementDisciple(): List<Disciple> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = listOf(
            elderSlots.viceSectMaster,
            elderSlots.herbGardenElder,
            elderSlots.alchemyElder,
            elderSlots.forgeElder,
            elderSlots.libraryElder,
            elderSlots.outerElder,
            elderSlots.preachingElder,
            elderSlots.lawEnforcementElder
        ).filterNotNull()

        val allDirectDiscipleIds = listOf(
            elderSlots.herbGardenDisciples,
            elderSlots.alchemyDisciples,
            elderSlots.forgeDisciples,
            elderSlots.libraryDisciples,
            elderSlots.preachingMasters,
            elderSlots.lawEnforcementDisciples,
            elderSlots.lawEnforcementReserveDisciples,
            elderSlots.spiritMineDeaconDisciples
        ).flatten().mapNotNull { it.discipleId }

        return disciples.value
            .filter { isSelectableDisciple(it) && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
            .sortedByDescending { it.intelligence }
    }

    fun getAvailableDisciplesForLawEnforcementReserve(): List<Disciple> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = listOf(
            elderSlots.viceSectMaster,
            elderSlots.herbGardenElder,
            elderSlots.alchemyElder,
            elderSlots.forgeElder,
            elderSlots.libraryElder,
            elderSlots.outerElder,
            elderSlots.preachingElder,
            elderSlots.lawEnforcementElder
        ).filterNotNull()

        val allDirectDiscipleIds = listOf(
            elderSlots.herbGardenDisciples,
            elderSlots.alchemyDisciples,
            elderSlots.forgeDisciples,
            elderSlots.libraryDisciples,
            elderSlots.preachingMasters,
            elderSlots.lawEnforcementDisciples,
            elderSlots.lawEnforcementReserveDisciples,
            elderSlots.spiritMineDeaconDisciples
        ).flatten().mapNotNull { it.discipleId }

        return disciples.value
            .filter { isSelectableDisciple(it) && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun getOuterDisciples(): List<Disciple> {
        return disciples.value.filter { it.isAlive && it.discipleType == "outer" }
    }

    fun getAvailableDisciplesForOuterElder(): List<Disciple> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = listOf(
            elderSlots.viceSectMaster,
            elderSlots.herbGardenElder,
            elderSlots.alchemyElder,
            elderSlots.forgeElder,
            elderSlots.libraryElder,
            elderSlots.outerElder,
            elderSlots.preachingElder,
            elderSlots.lawEnforcementElder
        ).filterNotNull()

        val allDirectDiscipleIds = listOf(
            elderSlots.herbGardenDisciples,
            elderSlots.alchemyDisciples,
            elderSlots.forgeDisciples,
            elderSlots.libraryDisciples,
            elderSlots.preachingMasters,
            elderSlots.lawEnforcementDisciples,
            elderSlots.spiritMineDeaconDisciples
        ).flatten().mapNotNull { it.discipleId }

        return disciples.value
            .filter {
                it.isAlive &&
                it.discipleType == "inner" &&
                it.realm <= 5 &&
                it.age >= 5 &&
                it.realmLayer > 0 &&
                it.status == DiscipleStatus.IDLE &&
                !allElderIds.contains(it.id) &&
                !allDirectDiscipleIds.contains(it.id)
            }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun getAvailableDisciplesForPreachingElder(): List<Disciple> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = listOf(
            elderSlots.viceSectMaster,
            elderSlots.herbGardenElder,
            elderSlots.alchemyElder,
            elderSlots.forgeElder,
            elderSlots.libraryElder,
            elderSlots.outerElder,
            elderSlots.preachingElder,
            elderSlots.lawEnforcementElder
        ).filterNotNull()

        val allDirectDiscipleIds = listOf(
            elderSlots.herbGardenDisciples,
            elderSlots.alchemyDisciples,
            elderSlots.forgeDisciples,
            elderSlots.libraryDisciples,
            elderSlots.preachingMasters,
            elderSlots.lawEnforcementDisciples,
            elderSlots.spiritMineDeaconDisciples
        ).flatten().mapNotNull { it.discipleId }

        return disciples.value
            .filter { isSelectableDisciple(it) && it.realm <= 5 && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun getAvailableDisciplesForPreachingMaster(): List<Disciple> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = listOf(
            elderSlots.viceSectMaster,
            elderSlots.herbGardenElder,
            elderSlots.alchemyElder,
            elderSlots.forgeElder,
            elderSlots.libraryElder,
            elderSlots.outerElder,
            elderSlots.preachingElder,
            elderSlots.lawEnforcementElder,
            elderSlots.innerElder,
            elderSlots.qingyunPreachingElder
        ).filterNotNull()

        val allDirectDiscipleIds = listOf(
            elderSlots.herbGardenDisciples,
            elderSlots.alchemyDisciples,
            elderSlots.forgeDisciples,
            elderSlots.libraryDisciples,
            elderSlots.preachingMasters,
            elderSlots.lawEnforcementDisciples,
            elderSlots.lawEnforcementReserveDisciples,
            elderSlots.qingyunPreachingMasters,
            elderSlots.spiritMineDeaconDisciples
        ).flatten().mapNotNull { it.discipleId }

        return disciples.value
            .filter { isSelectableDisciple(it) && it.realm <= 6 && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun getInnerElder(): Disciple? {
        val innerElderId = gameEngine.gameData.value.elderSlots.innerElder
        return getElderDisciple(innerElderId)
    }

    fun getQingyunPreachingElder(): Disciple? {
        val preachingElderId = gameEngine.gameData.value.elderSlots.qingyunPreachingElder
        return getElderDisciple(preachingElderId)
    }

    fun getQingyunPreachingMasters(): List<DirectDiscipleSlot> {
        return gameEngine.gameData.value.elderSlots.qingyunPreachingMasters
    }

    fun getInnerDisciples(): List<Disciple> {
        return disciples.value.filter { it.isAlive && it.discipleType == "inner" }
    }

    fun getAlchemyReserveDisciples(): List<DirectDiscipleSlot> {
        return gameEngine.gameData.value.elderSlots.alchemyReserveDisciples
    }

    fun getAlchemyReserveDisciplesWithInfo(): List<Disciple> {
        val reserveSlots = gameEngine.gameData.value.elderSlots.alchemyReserveDisciples
        val reserveIds = reserveSlots.mapNotNull { it.discipleId }.toSet()
        return disciples.value
            .filter { it.id in reserveIds }
            .sortedByDescending { it.pillRefining }
    }

    fun getHerbGardenReserveDisciples(): List<DirectDiscipleSlot> {
        return gameEngine.gameData.value.elderSlots.herbGardenReserveDisciples
    }

    fun getHerbGardenReserveDisciplesWithInfo(): List<Disciple> {
        val reserveSlots = gameEngine.gameData.value.elderSlots.herbGardenReserveDisciples
        val reserveIds = reserveSlots.mapNotNull { it.discipleId }.toSet()
        return disciples.value
            .filter { it.id in reserveIds }
            .sortedByDescending { it.spiritPlanting }
    }

    fun addAlchemyReserveDisciples(discipleIds: List<String>) {
        viewModelScope.launch {
            try {
                val currentReserveDisciples = gameEngine.gameData.value.elderSlots.alchemyReserveDisciples.toMutableList()
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
                    val updatedElderSlots = gameEngine.gameData.value.elderSlots.copy(
                        alchemyReserveDisciples = currentReserveDisciples
                    )
                    gameEngine.updateGameData { it.copy(elderSlots = updatedElderSlots) }
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
                val currentReserveDisciples = gameEngine.gameData.value.elderSlots.alchemyReserveDisciples
                val updatedReserveDisciples = currentReserveDisciples.filter { it.discipleId != discipleId }
                val updatedElderSlots = gameEngine.gameData.value.elderSlots.copy(
                    alchemyReserveDisciples = updatedReserveDisciples
                )
                gameEngine.updateGameData { it.copy(elderSlots = updatedElderSlots) }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "移除失败"
            }
        }
    }

    fun addHerbGardenReserveDisciples(discipleIds: List<String>) {
        viewModelScope.launch {
            try {
                val currentReserveDisciples = gameEngine.gameData.value.elderSlots.herbGardenReserveDisciples.toMutableList()
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
                        herbGardenReserveDisciples = currentReserveDisciples
                    )
                    gameEngine.updateGameData { it.copy(elderSlots = updatedElderSlots) }
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "添加失败"
            }
        }
    }

    fun removeHerbGardenReserveDisciple(discipleId: String) {
        viewModelScope.launch {
            try {
                val currentReserveDisciples = gameEngine.gameData.value.elderSlots.herbGardenReserveDisciples
                val updatedReserveDisciples = currentReserveDisciples.filter { it.discipleId != discipleId }
                val updatedElderSlots = gameEngine.gameData.value.elderSlots.copy(
                    herbGardenReserveDisciples = updatedReserveDisciples
                )
                gameEngine.updateGameData { it.copy(elderSlots = updatedElderSlots) }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "移除失败"
            }
        }
    }

    fun getAvailableDisciplesForHerbGardenReserve(): List<Disciple> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = listOfNotNull(
            elderSlots.viceSectMaster,
            elderSlots.herbGardenElder,
            elderSlots.alchemyElder,
            elderSlots.forgeElder,
            elderSlots.libraryElder,
            elderSlots.outerElder,
            elderSlots.preachingElder,
            elderSlots.lawEnforcementElder,
            elderSlots.innerElder,
            elderSlots.qingyunPreachingElder
        )

        val allDirectDiscipleIds = listOf(
            elderSlots.herbGardenDisciples,
            elderSlots.alchemyDisciples,
            elderSlots.forgeDisciples,
            elderSlots.libraryDisciples,
            elderSlots.preachingMasters,
            elderSlots.lawEnforcementDisciples,
            elderSlots.lawEnforcementReserveDisciples,
            elderSlots.qingyunPreachingMasters,
            elderSlots.spiritMineDeaconDisciples,
            elderSlots.alchemyReserveDisciples,
            elderSlots.herbGardenReserveDisciples
        ).flatten().mapNotNull { it.discipleId }

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

    fun getAvailableDisciplesForAlchemyReserve(): List<Disciple> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = listOfNotNull(
            elderSlots.viceSectMaster,
            elderSlots.herbGardenElder,
            elderSlots.alchemyElder,
            elderSlots.forgeElder,
            elderSlots.libraryElder,
            elderSlots.outerElder,
            elderSlots.preachingElder,
            elderSlots.lawEnforcementElder,
            elderSlots.innerElder,
            elderSlots.qingyunPreachingElder
        )

        val allDirectDiscipleIds = listOf(
            elderSlots.herbGardenDisciples,
            elderSlots.alchemyDisciples,
            elderSlots.forgeDisciples,
            elderSlots.libraryDisciples,
            elderSlots.preachingMasters,
            elderSlots.lawEnforcementDisciples,
            elderSlots.lawEnforcementReserveDisciples,
            elderSlots.qingyunPreachingMasters,
            elderSlots.spiritMineDeaconDisciples,
            elderSlots.alchemyReserveDisciples,
            elderSlots.forgeReserveDisciples
        ).flatten().mapNotNull { it.discipleId }

        return disciples.value
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

    fun getForgeReserveDisciplesWithInfo(): List<Disciple> {
        val reserveSlots = gameEngine.gameData.value.elderSlots.forgeReserveDisciples
        val reserveIds = reserveSlots.mapNotNull { it.discipleId }.toSet()
        return disciples.value
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

    fun getAvailableDisciplesForForgeReserve(): List<Disciple> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = listOfNotNull(
            elderSlots.viceSectMaster,
            elderSlots.herbGardenElder,
            elderSlots.alchemyElder,
            elderSlots.forgeElder,
            elderSlots.libraryElder,
            elderSlots.outerElder,
            elderSlots.preachingElder,
            elderSlots.lawEnforcementElder,
            elderSlots.innerElder,
            elderSlots.qingyunPreachingElder
        )

        val allDirectDiscipleIds = listOf(
            elderSlots.herbGardenDisciples,
            elderSlots.alchemyDisciples,
            elderSlots.forgeDisciples,
            elderSlots.libraryDisciples,
            elderSlots.preachingMasters,
            elderSlots.lawEnforcementDisciples,
            elderSlots.lawEnforcementReserveDisciples,
            elderSlots.qingyunPreachingMasters,
            elderSlots.spiritMineDeaconDisciples,
            elderSlots.alchemyReserveDisciples,
            elderSlots.forgeReserveDisciples
        ).flatten().mapNotNull { it.discipleId }

        return disciples.value
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

    fun getAvailableDisciplesForSpiritMineDeacon(): List<Disciple> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = listOf(
            elderSlots.viceSectMaster,
            elderSlots.herbGardenElder,
            elderSlots.alchemyElder,
            elderSlots.forgeElder,
            elderSlots.libraryElder,
            elderSlots.outerElder,
            elderSlots.preachingElder,
            elderSlots.lawEnforcementElder,
            elderSlots.innerElder,
            elderSlots.qingyunPreachingElder
        ).filterNotNull()

        val allDirectDiscipleIds = listOf(
            elderSlots.herbGardenDisciples,
            elderSlots.alchemyDisciples,
            elderSlots.forgeDisciples,
            elderSlots.libraryDisciples,
            elderSlots.preachingMasters,
            elderSlots.lawEnforcementDisciples,
            elderSlots.lawEnforcementReserveDisciples,
            elderSlots.qingyunPreachingMasters,
            elderSlots.spiritMineDeaconDisciples
        ).flatten().mapNotNull { it.discipleId }

        // 灵矿执事只能由内门弟子担任
        return disciples.value
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
                val allElderIds = listOf(
                    elderSlots.viceSectMaster,
                    elderSlots.herbGardenElder,
                    elderSlots.alchemyElder,
                    elderSlots.forgeElder,
                    elderSlots.libraryElder,
                    elderSlots.outerElder,
                    elderSlots.preachingElder,
                    elderSlots.lawEnforcementElder,
                    elderSlots.innerElder,
                    elderSlots.qingyunPreachingElder
                ).filterNotNull()

                val allDirectDiscipleIds = listOf(
                    elderSlots.herbGardenDisciples,
                    elderSlots.alchemyDisciples,
                    elderSlots.forgeDisciples,
                    elderSlots.libraryDisciples,
                    elderSlots.preachingMasters,
                    elderSlots.lawEnforcementDisciples,
                    elderSlots.lawEnforcementReserveDisciples,
                    elderSlots.qingyunPreachingMasters,
                    elderSlots.spiritMineDeaconDisciples
                ).flatten().mapNotNull { it.discipleId }

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
                
                // 更新弟子状态为采矿中
                gameEngine.updateDiscipleStatus(discipleId, DiscipleStatus.MINING)
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

    fun getAvailableDisciplesForInnerElder(): List<Disciple> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = listOf(
            elderSlots.viceSectMaster,
            elderSlots.herbGardenElder,
            elderSlots.alchemyElder,
            elderSlots.forgeElder,
            elderSlots.libraryElder,
            elderSlots.outerElder,
            elderSlots.preachingElder,
            elderSlots.lawEnforcementElder,
            elderSlots.innerElder,
            elderSlots.qingyunPreachingElder
        ).filterNotNull()

        val allDirectDiscipleIds = listOf(
            elderSlots.herbGardenDisciples,
            elderSlots.alchemyDisciples,
            elderSlots.forgeDisciples,
            elderSlots.libraryDisciples,
            elderSlots.preachingMasters,
            elderSlots.lawEnforcementDisciples,
            elderSlots.lawEnforcementReserveDisciples,
            elderSlots.qingyunPreachingMasters,
            elderSlots.spiritMineDeaconDisciples
        ).flatten().mapNotNull { it.discipleId }

        return disciples.value
            .filter { isSelectableDisciple(it) && it.realm <= 5 && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun getAvailableDisciplesForQingyunPreachingElder(): List<Disciple> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = listOf(
            elderSlots.viceSectMaster,
            elderSlots.herbGardenElder,
            elderSlots.alchemyElder,
            elderSlots.forgeElder,
            elderSlots.libraryElder,
            elderSlots.outerElder,
            elderSlots.preachingElder,
            elderSlots.lawEnforcementElder,
            elderSlots.innerElder,
            elderSlots.qingyunPreachingElder
        ).filterNotNull()

        val allDirectDiscipleIds = listOf(
            elderSlots.herbGardenDisciples,
            elderSlots.alchemyDisciples,
            elderSlots.forgeDisciples,
            elderSlots.libraryDisciples,
            elderSlots.preachingMasters,
            elderSlots.lawEnforcementDisciples,
            elderSlots.lawEnforcementReserveDisciples,
            elderSlots.qingyunPreachingMasters,
            elderSlots.spiritMineDeaconDisciples
        ).flatten().mapNotNull { it.discipleId }

        return disciples.value
            .filter { isSelectableDisciple(it) && it.realm <= 5 && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun getAvailableDisciplesForQingyunPreachingMaster(): List<Disciple> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = listOf(
            elderSlots.viceSectMaster,
            elderSlots.herbGardenElder,
            elderSlots.alchemyElder,
            elderSlots.forgeElder,
            elderSlots.libraryElder,
            elderSlots.outerElder,
            elderSlots.preachingElder,
            elderSlots.lawEnforcementElder,
            elderSlots.innerElder,
            elderSlots.qingyunPreachingElder
        ).filterNotNull()

        val allDirectDiscipleIds = listOf(
            elderSlots.herbGardenDisciples,
            elderSlots.alchemyDisciples,
            elderSlots.forgeDisciples,
            elderSlots.libraryDisciples,
            elderSlots.preachingMasters,
            elderSlots.lawEnforcementDisciples,
            elderSlots.lawEnforcementReserveDisciples,
            elderSlots.qingyunPreachingMasters,
            elderSlots.spiritMineDeaconDisciples
        ).flatten().mapNotNull { it.discipleId }

        return disciples.value
            .filter { isSelectableDisciple(it) && it.realm <= 6 && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun openWorldMapDialog() {
        _showWorldMapDialog.value = true
    }

    fun closeWorldMapDialog() {
        _showWorldMapDialog.value = false
    }

    fun openSecretRealmDialog() {
        _showSecretRealmDialog.value = true
    }

    fun closeSecretRealmDialog() {
        _showSecretRealmDialog.value = false
    }

    private val _showBattleLogDialog = MutableStateFlow(false)
    val showBattleLogDialog: StateFlow<Boolean> = _showBattleLogDialog.asStateFlow()

    fun openBattleLogDialog() {
        _showBattleLogDialog.value = true
    }

    fun closeBattleLogDialog() {
        _showBattleLogDialog.value = false
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

    fun selectSpiritMineSlot(slotIndex: Int) {
        // TODO: 实现灵矿选择
    }

    // 招募弟子
    fun recruitDisciple(name: String, aptitude: Int = 50) {
        viewModelScope.launch {
            try {
                gameEngine.recruitDisciple()
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "招募弟子失败"
            }
        }
    }

    fun assignDiscipleToBuilding(discipleId: String?, buildingId: String) {
        viewModelScope.launch {
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

    fun removeDiscipleFromSpiritMine(slotIndex: Int) {
        viewModelScope.launch {
        }
    }

    fun assignDiscipleToSpiritMineSlot(slotIndex: Int, discipleId: String, discipleName: String) {
        viewModelScope.launch {
            try {
                if (gameEngine.isDiscipleAssignedToSpiritMine(discipleId)) {
                    _errorMessage.value = "该弟子已在灵矿任职"
                    return@launch
                }

                val currentGameData = gameEngine.gameData.value
                val currentSlots = currentGameData.spiritMineSlots.toMutableList()

                while (currentSlots.size <= slotIndex) {
                    currentSlots.add(SpiritMineSlot(index = currentSlots.size))
                }

                val existingDiscipleId = currentSlots.getOrNull(slotIndex)?.discipleId
                existingDiscipleId?.let { oldId ->
                    gameEngine.updateDiscipleStatus(oldId, DiscipleStatus.IDLE)
                }

                val output = 100

                currentSlots[slotIndex] = currentSlots[slotIndex].copy(
                    discipleId = discipleId,
                    discipleName = discipleName,
                    output = output
                )

                gameEngine.updateSpiritMineSlots(currentSlots)
                gameEngine.updateDiscipleStatus(discipleId, DiscipleStatus.MINING)
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
                        discipleId = null,
                        discipleName = "",
                        output = 100
                    )
                    gameEngine.updateSpiritMineSlots(currentSlots)
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "卸任失败"
            }
        }
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

    fun assignDiscipleToSpiritMine(slotIndex: Int, discipleId: String) {
        viewModelScope.launch {
        }
    }

    // 炼丹室相关
    fun selectAlchemySlot(slot: AlchemySlot) {
        // TODO: 实现炼丹室选择
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

    // 天工峰相关
    fun selectForgeSlot(slot: ForgeSlot) {
        // TODO: 实现天工峰选择
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

    // 弟子招募相关
    fun recruitDiscipleFromList(discipleId: String) {
        if (recruitingDiscipleIds.contains(discipleId)) {
            return
        }
        recruitingDiscipleIds.add(discipleId)

        viewModelScope.launch {
            try {
                val recruitList = gameEngine.gameData.value.recruitList
                val disciple = recruitList.find { it.id == discipleId }
                if (disciple != null) {
                    gameEngine.recruitDiscipleFromList(disciple)
                }
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
            val recruitList = gameEngine.gameData.value.recruitList
            val disciple = recruitList.find { it.id == discipleId }
            if (disciple != null) {
                gameEngine.removeFromRecruitList(disciple)
            }
        }
    }

    fun getRecruitList(): List<Disciple> {
        return gameEngine.gameData.value.recruitList
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

    fun sortWarehouse(category: String) {
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

    fun startExploration(name: String, memberIds: List<String>, location: String, duration: Int) {
        viewModelScope.launch {
        }
    }

    fun startCaveExploration(cave: CultivatorCave, selectedDisciples: List<Disciple>) {
        viewModelScope.launch {
            gameEngine.startCaveExploration(cave, selectedDisciples)
        }
    }

    fun exploreSect(sectId: String) {
        viewModelScope.launch {
        }
    }

    fun interactWithSect(sectId: String, action: String) {
        viewModelScope.launch {
        }
    }

    fun refreshMerchant() {
        viewModelScope.launch {
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

    fun buyFromTradingHall(itemId: String) {
        viewModelScope.launch {
        }
    }

    fun sellToTradingHall(itemId: String) {
        viewModelScope.launch {
        }
    }

    // 招募相关
    private val recruitingDiscipleIds = mutableSetOf<String>()

    fun recruitDisciple(disciple: com.xianxia.sect.core.model.Disciple) {
        if (recruitingDiscipleIds.contains(disciple.id)) {
            return
        }
        recruitingDiscipleIds.add(disciple.id)

        viewModelScope.launch {
            try {
                gameEngine.recruitDiscipleFromList(disciple)
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

    fun harvestHerb(slotIndex: Int) {
        viewModelScope.launch {
            try {
                gameEngine.harvestHerb(slotIndex)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "收获失败"
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

    fun updateMonthlySalaryEnabled(realm: Int, enabled: Boolean) {
    }

    fun getDiscipleById(id: String): Disciple? {
        return disciples.value.find { it.id == id }
    }

    fun getManualById(id: String): Manual? {
        return manuals.value.find { it.id == id }
    }

    // 一键出售物品
    fun bulkSellItems(
        selectedRarities: Set<Int>,
        selectedTypes: Set<String>
    ) {
        viewModelScope.launch {
            try {
                var totalValue = 0
                val soldItems = mutableListOf<String>()

                // 获取所有存活弟子穿戴的装备ID和学习的功法ID
                val equippedIds = mutableSetOf<String>()
                val learnedManualIds = mutableSetOf<String>()
                disciples.value.filter { it.isAlive }.forEach { disciple ->
                    disciple.weaponId?.let { equippedIds.add(it) }
                    disciple.armorId?.let { equippedIds.add(it) }
                    disciple.bootsId?.let { equippedIds.add(it) }
                    disciple.accessoryId?.let { equippedIds.add(it) }
                    learnedManualIds.addAll(disciple.manualIds)
                }

                // 出售装备（排除已穿戴的）
                if (selectedTypes.contains("EQUIPMENT")) {
                    val itemsToSell = equipment.value.filter { 
                        selectedRarities.contains(it.rarity) && !equippedIds.contains(it.id)
                    }
                    itemsToSell.forEach { item ->
                        val sellPrice = (item.basePrice * 0.8).toInt()
                        totalValue += sellPrice
                        soldItems.add(item.name)
                        gameEngine.sellEquipment(item.id)
                    }
                }

                // 出售功法（排除已学习的）
                if (selectedTypes.contains("MANUAL")) {
                    val itemsToSell = manuals.value.filter { 
                        selectedRarities.contains(it.rarity) && !learnedManualIds.contains(it.id)
                    }
                    itemsToSell.forEach { item ->
                        val sellPrice = (item.basePrice * 0.8).toInt()
                        totalValue += sellPrice
                        soldItems.add(item.name)
                        gameEngine.sellManual(item.id)
                    }
                }

                // 出售丹药
                if (selectedTypes.contains("PILL")) {
                    val itemsToSell = pills.value.filter { selectedRarities.contains(it.rarity) }
                    itemsToSell.forEach { item ->
                        val sellPrice = (item.basePrice * item.quantity * 0.8).toInt()
                        totalValue += sellPrice
                        soldItems.add("${item.name} x${item.quantity}")
                        gameEngine.sellPill(item.id, item.quantity)
                    }
                }

                // 出售材料
                if (selectedTypes.contains("MATERIAL")) {
                    val itemsToSell = materials.value.filter { selectedRarities.contains(it.rarity) }
                    itemsToSell.forEach { item ->
                        val sellPrice = (item.basePrice * item.quantity * 0.8).toInt()
                        totalValue += sellPrice
                        soldItems.add("${item.name} x${item.quantity}")
                        gameEngine.sellMaterial(item.id, item.quantity)
                    }
                }

                // 出售灵药
                if (selectedTypes.contains("HERB")) {
                    val itemsToSell = herbs.value.filter { selectedRarities.contains(it.rarity) }
                    itemsToSell.forEach { item ->
                        val sellPrice = (item.basePrice * item.quantity * 0.8).toInt()
                        totalValue += sellPrice
                        soldItems.add("${item.name} x${item.quantity}")
                        gameEngine.sellHerb(item.id, item.quantity)
                    }
                }

                // 出售种子
                if (selectedTypes.contains("SEED")) {
                    val itemsToSell = seeds.value.filter { selectedRarities.contains(it.rarity) }
                    itemsToSell.forEach { item ->
                        val sellPrice = (item.basePrice * item.quantity * 0.8).toInt()
                        totalValue += sellPrice
                        soldItems.add("${item.name} x${item.quantity}")
                        gameEngine.sellSeed(item.id, item.quantity)
                    }
                }

                // 增加灵石
                if (totalValue > 0) {
                    gameEngine.addSpiritStones(totalValue)
                    gameEngine.syncListedItemsWithInventory()
                } else {
                    _errorMessage.value = "没有符合条件的物品可出售"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "一键出售失败"
            }
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

    fun startMission(mission: com.xianxia.sect.core.model.Mission, selectedDisciples: List<Disciple>) {
        viewModelScope.launch {
            try {
                gameEngine.startMission(mission, selectedDisciples)
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
    fun onMemoryPressure(level: Int) {
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                Log.w(TAG, "内存压力: TRIM_MEMORY_MODERATE，系统内存紧张")
                gameEngine.releaseMemory(level)
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                Log.e(TAG, "内存压力: $level，释放内存资源")
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
        super.onCleared()
        Log.i(TAG, "GameViewModel cleared, stopping game loop and performing auto save")
        
        clearResources()
        
        try {
            val snapshot = gameEngine.getStateSnapshotSync()
            val currentSlot = snapshot.gameData.currentSlot
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
                slots = snapshot.buildingSlots,
                events = snapshot.events,
                battleLogs = snapshot.battleLogs,
                alliances = snapshot.alliances,
                supportTeams = snapshot.supportTeams,
                alchemySlots = snapshot.alchemySlots
            )
            saveManager.save(currentSlot, saveData)
            Log.i(TAG, "Auto save on exit completed, slot: $currentSlot")
        } catch (e: Exception) {
            Log.e(TAG, "Auto save on exit failed: ${e.message}")
        }
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
        _showBattleTeamDialog.value = true
    }

    fun closeBattleTeamDialog() {
        _showBattleTeamDialog.value = false
    }

    fun getAvailableEldersForBattleTeam(): List<Disciple> {
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

    fun getAvailableDisciplesForBattleTeam(): List<Disciple> {
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

    fun assignDiscipleToBattleTeamSlot(slotIndex: Int, disciple: Disciple) {
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
        val filledSlots = currentSlots.count { it.discipleId != null }
        
        if (filledSlots < 10) {
            _errorMessage.value = "必须满10名弟子才可组建队伍"
            return false
        }

        val elderSlots = currentSlots.filter { it.slotType == BattleSlotType.ELDER }
        if (elderSlots.any { it.discipleId == null }) {
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
                
                _showBattleTeamDialog.value = false
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
                
                _showBattleTeamDialog.value = false
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
        _showBattleTeamDialog.value = false
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
        if (elderSlots.libraryElder == discipleId) return "藏经阁长老"
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
        if (elderSlots.libraryDisciples.any { it.discipleId == discipleId }) return "藏经阁亲传弟子"

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

    fun getAvailableDisciplesForBattleTeamSlot(slotIndex: Int): List<Disciple> {
        val battleTeam = gameEngine.gameData.value.battleTeam ?: return emptyList()
        val currentSlotDiscipleIds = battleTeam.slots.mapNotNull { it.discipleId }
        
        return disciples.value.filter { disciple ->
            disciple.isAlive &&
            disciple.realmLayer > 0 &&
            disciple.status == DiscipleStatus.IDLE &&
            !currentSlotDiscipleIds.contains(disciple.id) &&
            !isPositionWorkStatus(disciple.id)
        }.sortedWith(compareBy({ -it.realm }, { -it.realmLayer }))
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
            gameEngine.updateDiscipleStatus(discipleId, DiscipleStatus.BATTLE)
        }
    }
}


