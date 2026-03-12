package com.xianxia.sect.ui.game

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.data.ForgeRecipeDatabase
import com.xianxia.sect.core.data.PillRecipeDatabase
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

    val warTeams: StateFlow<List<WarTeam>> = gameEngine.warTeams
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

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _successMessage = MutableStateFlow<String?>(null)
    val successMessage: StateFlow<String?> = _successMessage.asStateFlow()

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

    private val _showWarHallDialog = MutableStateFlow(false)
    val showWarHallDialog: StateFlow<Boolean> = _showWarHallDialog.asStateFlow()

    private val _showRecruitDialog = MutableStateFlow(false)
    val showRecruitDialog: StateFlow<Boolean> = _showRecruitDialog.asStateFlow()

    private val _showTournamentDialog = MutableStateFlow(false)
    val showTournamentDialog: StateFlow<Boolean> = _showTournamentDialog.asStateFlow()

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

    private val _showReflectionCliffDialog = MutableStateFlow(false)
    val showReflectionCliffDialog: StateFlow<Boolean> = _showReflectionCliffDialog.asStateFlow()

    private val _showQingyunPeakDialog = MutableStateFlow(false)
    val showQingyunPeakDialog: StateFlow<Boolean> = _showQingyunPeakDialog.asStateFlow()

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
    private var monthAccumulator = 0
    private var autoSaveMonthCounter = 0

    init {
        _saveSlots.value = saveManager.getSaveSlots()
    }

    private val _isTimeRunning = MutableStateFlow(false)
    val isTimeRunning: StateFlow<Boolean> = _isTimeRunning.asStateFlow()

    private fun startGameLoop() {
        gameLoopJob?.cancel()
        secondTickJob?.cancel()

        monthAccumulator = 0
        autoSaveMonthCounter = 0

        _isTimeRunning.value = true
        _isPaused.value = false

        Log.d(TAG, "Game loop started")

        gameLoopJob = viewModelScope.launch {
            while (isActive) {
                try {
                    delay(GameConfig.Time.TICK_INTERVAL)

                    if (!_isPaused.value) {
                        try {
                            gameEngine.processSecondTick()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in processSecondTick", e)
                        }

                        monthAccumulator++
                        val daysPerSecond = _timeScale.value
                        val ticksPerDay = GameConfig.Time.TICKS_PER_SECOND
                        
                        if (monthAccumulator >= ticksPerDay) {
                            monthAccumulator = 0
                            
                            for (i in 0 until daysPerSecond) {
                                try {
                                    gameEngine.advanceDay()
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error in advanceDay", e)
                                }
                            }
                            
                            autoSaveMonthCounter++
                            val autoSaveInterval = gameEngine.gameData.value.autoSaveIntervalMonths
                            if (autoSaveInterval > 0 && autoSaveMonthCounter >= autoSaveInterval * 30) {
                                autoSaveMonthCounter = 0
                                try {
                                    performAutoSave()
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error in performAutoSave", e)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in game loop", e)
                }
            }
        }
    }
    
    fun performAutoSave() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 使用带锁的状态快照获取方法，确保线程安全
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
                    warTeams = snapshot.warTeams,
                    alliances = snapshot.alliances,
                    supportTeams = snapshot.supportTeams
                )

                val success = saveManager.save(currentSlot, saveData)
                if (success) {
                    withContext(Dispatchers.Main) {
                        _saveSlots.value = saveManager.getSaveSlots()
                    }
                    Log.d(TAG, "Auto save completed for slot: $currentSlot")
                } else {
                    Log.e(TAG, "Auto save failed for slot: $currentSlot")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Auto save failed: ${e.message}", e)
            }
        }
    }

    private fun stopGameLoop() {
        viewModelScope.launch {
            try {
                gameLoopJob?.cancelAndJoin()
                secondTickJob?.cancelAndJoin()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping game loop", e)
            } finally {
                gameLoopJob = null
                secondTickJob = null
                _isTimeRunning.value = false
                _isPaused.value = true
            }
        }
    }

    // 开始新游戏
    fun startNewGame(sectName: String, slot: Int = 1) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                Log.d(TAG, "Starting new game: sectName=$sectName, slot=$slot")
                
                gameEngine.createNewGame(sectName)
                gameEngine.updateGameData { it.copy(currentSlot = slot) }
                Log.d(TAG, "Game engine created new game")
                
                saveGame(slot.toString())
                Log.d(TAG, "Game saved to slot $slot")
                
                startGameLoop()
                Log.d(TAG, "Game loop started, isPaused=${_isPaused.value}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start new game: ${e.message}", e)
                _errorMessage.value = e.message ?: "开始新游戏失败"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadGame(saveSlot: SaveSlot) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val saveData = saveManager.load(saveSlot.slot)
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
                        warTeams = saveData.warTeams ?: emptyList(),
                        alliances = saveData.alliances ?: emptyList(),
                        supportTeams = saveData.supportTeams ?: emptyList()
                    )
                    startGameLoop()
                } else {
                    _errorMessage.value = "存档为空或不存在"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "加载游戏失败"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadGameFromSlot(slot: Int) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val saveData = saveManager.load(slot)
                if (saveData != null) {
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
                        warTeams = saveData.warTeams ?: emptyList(),
                        alliances = saveData.alliances ?: emptyList(),
                        supportTeams = saveData.supportTeams ?: emptyList()
                    )
                    startGameLoop()
                } else {
                    _errorMessage.value = "存档为空或不存在"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "加载游戏失败"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun saveGame(slotId: String? = null): Boolean {
        return try {
            val slot = slotId?.toIntOrNull() ?: gameEngine.gameData.value.currentSlot
            val currentGameData = gameEngine.gameData.value
            if (currentGameData.sectName.isBlank()) {
                _errorMessage.value = "游戏数据未初始化"
                return false
            }
            val updatedGameData = currentGameData.copy(currentSlot = slot)
            val saveData = SaveData(
                gameData = updatedGameData,
                disciples = gameEngine.disciples.value,
                equipment = gameEngine.equipment.value,
                manuals = gameEngine.manuals.value,
                pills = gameEngine.pills.value,
                materials = gameEngine.materials.value,
                herbs = gameEngine.herbs.value,
                seeds = gameEngine.seeds.value,
                teams = gameEngine.teams.value,
                slots = gameEngine.buildingSlots.value,
                events = gameEngine.events.value,
                battleLogs = gameEngine.battleLogs.value,
                warTeams = gameEngine.warTeams.value,
                alliances = gameEngine.gameData.value.alliances,
                supportTeams = gameEngine.gameData.value.supportTeams
            )
            val success = saveManager.save(slot, saveData)
            if (success) {
                gameEngine.updateGameData { updatedGameData }
                _saveSlots.value = saveManager.getSaveSlots()
            } else {
                _errorMessage.value = "保存失败"
            }
            success
        } catch (e: Exception) {
            _errorMessage.value = e.message ?: "保存失败"
            false
        }
    }

    fun saveToSlot(slotIndex: Int) {
        viewModelScope.launch {
            try {
                saveGame(slotIndex.toString())
                _showSaveDialog.value = false
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "保存失败"
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

    fun showSaveManagementDialog() {
        // ˢ�´浵��λ��Ϣ
        _saveSlots.value = saveManager.getSaveSlots()
        _showSaveDialog.value = true
    }

    fun dismissSaveDialog() {
        _showSaveDialog.value = false
    }

    fun restartGame() {
        viewModelScope.launch {
            try {
                _isLoading.value = true

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
                _isLoading.value = false
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

    fun openWarHallDialog() {
        _showWarHallDialog.value = true
    }

    fun closeWarHallDialog() {
        _showWarHallDialog.value = false
    }

    fun openRecruitDialog() {
        _showRecruitDialog.value = true
    }

    fun closeRecruitDialog() {
        _showRecruitDialog.value = false
    }

    fun openTournamentDialog() {
        _showTournamentDialog.value = true
    }

    fun closeTournamentDialog() {
        _showTournamentDialog.value = false
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
                val result = gameEngine.giftSpiritStones(sectId, tier)
                if (result.success) {
                    _successMessage.value = result.message
                } else {
                    _successMessage.value = result.message
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "送礼失败"
            }
        }
    }
    
    fun giftItem(sectId: String, itemId: String, itemType: String, quantity: Int) {
        viewModelScope.launch {
            try {
                val result = gameEngine.giftItem(sectId, itemId, itemType, quantity)
                _successMessage.value = result.message
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
                    _successMessage.value = message
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
                    _successMessage.value = message
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
                    _successMessage.value = message
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
                _successMessage.value = "购买成功"
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
                    elderSlots.spiritMineElder,
                    elderSlots.recruitElder,
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
                    elderSlots.spiritMineDisciples,
                    elderSlots.recruitDisciples,
                    elderSlots.preachingMasters,
                    elderSlots.lawEnforcementDisciples,
                    elderSlots.lawEnforcementReserveDisciples,
                    elderSlots.qingyunPreachingMasters
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
                    "spiritMine" -> elderSlots.copy(
                        spiritMineElder = discipleId,
                        spiritMineDisciples = emptyList()
                    )
                    "recruit" -> elderSlots.copy(
                        recruitElder = discipleId,
                        recruitDisciples = emptyList()
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
                    "spiritMine" -> elderSlots.copy(
                        spiritMineElder = null,
                        spiritMineDisciples = emptyList()
                    )
                    "recruit" -> elderSlots.copy(
                        recruitElder = null,
                        recruitDisciples = emptyList()
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
        return 0.30
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
                    elderSlots.libraryElder,
                    elderSlots.spiritMineElder,
                    elderSlots.recruitElder
                )
                val allDirectDiscipleIds = listOf(
                    elderSlots.herbGardenDisciples,
                    elderSlots.alchemyDisciples,
                    elderSlots.forgeDisciples,
                    elderSlots.libraryDisciples,
                    elderSlots.spiritMineDisciples,
                    elderSlots.recruitDisciples
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
    
    fun assignInnerDisciple(elderSlotType: String, slotIndex: Int, discipleId: String) {
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
                    elderSlots.libraryElder,
                    elderSlots.spiritMineElder,
                    elderSlots.recruitElder
                )
                val allDirectDiscipleIds = listOf(
                    elderSlots.herbGardenDisciples,
                    elderSlots.alchemyDisciples,
                    elderSlots.forgeDisciples,
                    elderSlots.libraryDisciples,
                    elderSlots.spiritMineDisciples,
                    elderSlots.recruitDisciples
                ).flatten().mapNotNull { it.discipleId }
                val allInnerDiscipleIds = listOf(
                    elderSlots.forgeInnerDisciples,
                    elderSlots.alchemyInnerDisciples,
                    elderSlots.herbGardenInnerDisciples
                ).flatten().mapNotNull { it.discipleId }
                
                if (allElderIds.contains(discipleId)) {
                    _errorMessage.value = "该弟子已担任长老职位"
                    return@launch
                }
                
                if (allDirectDiscipleIds.contains(discipleId)) {
                    _errorMessage.value = "该弟子已是其他长老的亲传弟子"
                    return@launch
                }
                
                if (allInnerDiscipleIds.contains(discipleId)) {
                    _errorMessage.value = "该弟子已是内门弟子"
                    return@launch
                }
                
                gameEngine.assignInnerDisciple(
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
    
    fun removeInnerDisciple(elderSlotType: String, slotIndex: Int) {
        viewModelScope.launch {
            try {
                gameEngine.removeInnerDisciple(elderSlotType, slotIndex)
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

    private fun isEligibleForPosition(disciple: Disciple): Boolean {
        return disciple.isAlive &&
               disciple.age >= 5 &&
               disciple.discipleType == "inner" &&
               disciple.realmLayer > 0 &&
               disciple.status == DiscipleStatus.IDLE
    }

    fun getAvailableDisciplesForLawEnforcementElder(): List<Disciple> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = listOf(
            elderSlots.viceSectMaster,
            elderSlots.herbGardenElder,
            elderSlots.alchemyElder,
            elderSlots.forgeElder,
            elderSlots.libraryElder,
            elderSlots.spiritMineElder,
            elderSlots.recruitElder,
            elderSlots.outerElder,
            elderSlots.preachingElder,
            elderSlots.lawEnforcementElder
        ).filterNotNull()

        val allDirectDiscipleIds = listOf(
            elderSlots.herbGardenDisciples,
            elderSlots.alchemyDisciples,
            elderSlots.forgeDisciples,
            elderSlots.libraryDisciples,
            elderSlots.spiritMineDisciples,
            elderSlots.recruitDisciples,
            elderSlots.preachingMasters,
            elderSlots.lawEnforcementDisciples,
            elderSlots.lawEnforcementReserveDisciples
        ).flatten().mapNotNull { it.discipleId }

        return disciples.value
            .filter { isEligibleForPosition(it) && it.realm <= 6 && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
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
            elderSlots.spiritMineElder,
            elderSlots.recruitElder,
            elderSlots.outerElder,
            elderSlots.preachingElder,
            elderSlots.lawEnforcementElder
        ).filterNotNull()

        val allDirectDiscipleIds = listOf(
            elderSlots.herbGardenDisciples,
            elderSlots.alchemyDisciples,
            elderSlots.forgeDisciples,
            elderSlots.libraryDisciples,
            elderSlots.spiritMineDisciples,
            elderSlots.recruitDisciples,
            elderSlots.preachingMasters,
            elderSlots.lawEnforcementDisciples,
            elderSlots.lawEnforcementReserveDisciples
        ).flatten().mapNotNull { it.discipleId }

        return disciples.value
            .filter { isEligibleForPosition(it) && it.realm <= 7 && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
            .sortedWith(compareBy({ it.realm }, { -it.realmLayer }))
    }

    fun getAvailableDisciplesForLawEnforcementReserve(): List<Disciple> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = listOf(
            elderSlots.viceSectMaster,
            elderSlots.herbGardenElder,
            elderSlots.alchemyElder,
            elderSlots.forgeElder,
            elderSlots.libraryElder,
            elderSlots.spiritMineElder,
            elderSlots.recruitElder,
            elderSlots.outerElder,
            elderSlots.preachingElder,
            elderSlots.lawEnforcementElder
        ).filterNotNull()

        val allDirectDiscipleIds = listOf(
            elderSlots.herbGardenDisciples,
            elderSlots.alchemyDisciples,
            elderSlots.forgeDisciples,
            elderSlots.libraryDisciples,
            elderSlots.spiritMineDisciples,
            elderSlots.recruitDisciples,
            elderSlots.preachingMasters,
            elderSlots.lawEnforcementDisciples,
            elderSlots.lawEnforcementReserveDisciples
        ).flatten().mapNotNull { it.discipleId }

        return disciples.value
            .filter { isEligibleForPosition(it) && it.realm <= 7 && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
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
            elderSlots.spiritMineElder,
            elderSlots.recruitElder,
            elderSlots.outerElder,
            elderSlots.preachingElder,
            elderSlots.lawEnforcementElder
        ).filterNotNull()

        val allDirectDiscipleIds = listOf(
            elderSlots.herbGardenDisciples,
            elderSlots.alchemyDisciples,
            elderSlots.forgeDisciples,
            elderSlots.libraryDisciples,
            elderSlots.spiritMineDisciples,
            elderSlots.recruitDisciples,
            elderSlots.preachingMasters,
            elderSlots.lawEnforcementDisciples
        ).flatten().mapNotNull { it.discipleId }

        return disciples.value
            .filter { isEligibleForPosition(it) && it.realm <= 6 && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
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
            elderSlots.spiritMineElder,
            elderSlots.recruitElder,
            elderSlots.outerElder,
            elderSlots.preachingElder,
            elderSlots.lawEnforcementElder
        ).filterNotNull()

        val allDirectDiscipleIds = listOf(
            elderSlots.herbGardenDisciples,
            elderSlots.alchemyDisciples,
            elderSlots.forgeDisciples,
            elderSlots.libraryDisciples,
            elderSlots.spiritMineDisciples,
            elderSlots.recruitDisciples,
            elderSlots.preachingMasters,
            elderSlots.lawEnforcementDisciples
        ).flatten().mapNotNull { it.discipleId }

        return disciples.value
            .filter { isEligibleForPosition(it) && it.realm <= 6 && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
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
            elderSlots.spiritMineElder,
            elderSlots.recruitElder,
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
            elderSlots.spiritMineDisciples,
            elderSlots.recruitDisciples,
            elderSlots.preachingMasters,
            elderSlots.lawEnforcementDisciples,
            elderSlots.lawEnforcementReserveDisciples,
            elderSlots.qingyunPreachingMasters
        ).flatten().mapNotNull { it.discipleId }

        return disciples.value
            .filter { isEligibleForPosition(it) && it.realm <= 7 && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
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

    fun getAvailableDisciplesForInnerElder(): List<Disciple> {
        val elderSlots = gameEngine.gameData.value.elderSlots
        val allElderIds = listOf(
            elderSlots.viceSectMaster,
            elderSlots.herbGardenElder,
            elderSlots.alchemyElder,
            elderSlots.forgeElder,
            elderSlots.libraryElder,
            elderSlots.spiritMineElder,
            elderSlots.recruitElder,
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
            elderSlots.spiritMineDisciples,
            elderSlots.recruitDisciples,
            elderSlots.preachingMasters,
            elderSlots.lawEnforcementDisciples,
            elderSlots.lawEnforcementReserveDisciples,
            elderSlots.qingyunPreachingMasters
        ).flatten().mapNotNull { it.discipleId }

        return disciples.value
            .filter { isEligibleForPosition(it) && it.realm <= 6 && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
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
            elderSlots.spiritMineElder,
            elderSlots.recruitElder,
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
            elderSlots.spiritMineDisciples,
            elderSlots.recruitDisciples,
            elderSlots.preachingMasters,
            elderSlots.lawEnforcementDisciples,
            elderSlots.lawEnforcementReserveDisciples,
            elderSlots.qingyunPreachingMasters
        ).flatten().mapNotNull { it.discipleId }

        return disciples.value
            .filter { isEligibleForPosition(it) && it.realm <= 6 && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
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
            elderSlots.spiritMineElder,
            elderSlots.recruitElder,
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
            elderSlots.spiritMineDisciples,
            elderSlots.recruitDisciples,
            elderSlots.preachingMasters,
            elderSlots.lawEnforcementDisciples,
            elderSlots.lawEnforcementReserveDisciples,
            elderSlots.qingyunPreachingMasters
        ).flatten().mapNotNull { it.discipleId }

        return disciples.value
            .filter { isEligibleForPosition(it) && it.realm <= 7 && !allElderIds.contains(it.id) && !allDirectDiscipleIds.contains(it.id) }
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
                _successMessage.value = "成功招募弟子"
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "招募弟子失败"
            }
        }
    }

    fun assignDiscipleToBuilding(discipleId: String?, buildingId: String) {
        viewModelScope.launch {
            _successMessage.value = "弟子已分配"
        }
    }

    fun assignDiscipleToBuilding(buildingId: String, slotIndex: Int, discipleId: String) {
        viewModelScope.launch {
            try {
                gameEngine.assignDiscipleToBuilding(buildingId, slotIndex, discipleId)
                _successMessage.value = "弟子已分配"
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "分配失败"
            }
        }
    }

    fun removeDiscipleFromSpiritMine(slotIndex: Int) {
        viewModelScope.launch {
            _successMessage.value = "弟子已移除"
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
                _successMessage.value = "弟子已分配到灵矿"
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
                    _successMessage.value = "弟子已从灵矿卸任"
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
                _successMessage.value = "弟子已分配到藏经阁"
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "分配失败"
            }
        }
    }

    fun removeDiscipleFromLibrarySlot(slotIndex: Int) {
        viewModelScope.launch {
            try {
                gameEngine.removeDiscipleFromLibrarySlot(slotIndex)
                _successMessage.value = "弟子已从藏经阁卸任"
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "卸任失败"
            }
        }
    }

    fun assignDiscipleToSpiritMine(slotIndex: Int, discipleId: String) {
        viewModelScope.launch {
            _successMessage.value = "弟子已分配"
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
        // 防止重复点击导致重复招募
        if (recruitingDiscipleIds.contains(discipleId)) {
            return
        }
        recruitingDiscipleIds.add(discipleId)

        viewModelScope.launch {
            try {
                val recruitList = gameEngine.gameData.value.recruitList
                val disciple = recruitList.find { it.id == discipleId }
                if (disciple != null) {
                    val success = gameEngine.recruitDiscipleFromList(disciple)
                    if (success) {
                        _successMessage.value = "招募成功"
                    }
                }
            } finally {
                recruitingDiscipleIds.remove(discipleId)
            }
        }
    }

    fun expelDisciple(discipleId: String) {
        viewModelScope.launch {
            gameEngine.expelDisciple(discipleId)
            _successMessage.value = "已逐出弟子"
        }
    }

    fun rewardItemsToDisciple(discipleId: String, items: List<RewardSelectedItem>) {
        viewModelScope.launch {
            gameEngine.rewardItemsToDisciple(discipleId, items)
            _successMessage.value = "已赏赐${items.size}件道具"
        }
    }

    fun recruitAllDisciples() {
        viewModelScope.launch {
            val success = gameEngine.recruitAllFromList()
            if (success) {
                _successMessage.value = "所有弟子已招募"
            } else {
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
                _successMessage.value = "弟子已拒绝"
            }
        }
    }

    fun getRecruitList(): List<Disciple> {
        return gameEngine.gameData.value.recruitList
    }

    // 装备管理
    fun equipItem(discipleId: String, equipmentId: String) {
        viewModelScope.launch {
            try {
                gameEngine.equipItem(discipleId, equipmentId)
                _successMessage.value = "装备成功"
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "装备失败"
            }
        }
    }

    fun unequipItem(discipleId: String, slot: EquipmentSlot) {
        viewModelScope.launch {
            try {
                gameEngine.unequipItem(discipleId, slot)
                _successMessage.value = "装备已卸下"
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "卸下装备失败"
            }
        }
    }

    fun unequipItem(discipleId: String, equipmentId: String) {
        viewModelScope.launch {
            try {
                gameEngine.unequipItemById(discipleId, equipmentId)
                _successMessage.value = "装备已卸下"
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "卸下装备失败"
            }
        }
    }

    // 功法管理
    fun forgetManual(discipleId: String, manualId: String) {
        viewModelScope.launch {
            try {
                gameEngine.forgetManual(discipleId, manualId)
                _successMessage.value = "功法已遗忘"
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "遗忘功法失败"
            }
        }
    }

    fun replaceManual(discipleId: String, oldManualId: String, newManualId: String) {
        viewModelScope.launch {
            try {
                gameEngine.replaceManual(discipleId, oldManualId, newManualId)
                _successMessage.value = "功法替换成功"
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "功法替换失败"
            }
        }
    }

    fun learnManual(discipleId: String, manualId: String) {
        viewModelScope.launch {
            try {
                gameEngine.learnManual(discipleId, manualId)
                _successMessage.value = "功法学习成功"
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "学习功法失败"
            }
        }
    }

    // 仓库管理
    fun sortWarehouse(category: String) {
        // TODO: 实现仓库排序
    }

    // 召回探索队伍
    fun recallTeam(teamId: String) {
        viewModelScope.launch {
            try {
                gameEngine.recallTeam(teamId)
                _successMessage.value = "队伍已召回"
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "召回失败"
            }
        }
    }

    fun startExploration(name: String, memberIds: List<String>, location: String, duration: Int) {
        viewModelScope.launch {
            _successMessage.value = "开始探索"
        }
    }

    fun exploreSect(sectId: String) {
        viewModelScope.launch {
            _successMessage.value = "探索完成"
        }
    }

    fun interactWithSect(sectId: String, action: String) {
        viewModelScope.launch {
            _successMessage.value = "外交行动: $action"
        }
    }

    // 大比设置
    fun setTournamentAutoHold(enabled: Boolean) {
        viewModelScope.launch {
            gameEngine.toggleTournamentAutoHold(enabled)
            _successMessage.value = if (enabled) "已开启自动举办" else "已关闭自动举办"
        }
    }

    fun setTournamentRealmEnabled(realm: Int, enabled: Boolean) {
        viewModelScope.launch {
            gameEngine.toggleTournamentRealm(realm, enabled)
        }
    }

    fun updateTournamentReward(realm: Int, reward: com.xianxia.sect.core.model.TournamentReward) {
        viewModelScope.launch {
            gameEngine.updateTournamentRewards(realm, reward)
        }
    }

    fun holdTournament() {
        viewModelScope.launch {
            gameEngine.startTournament(9)
            _successMessage.value = "大比已举办"
        }
    }

    // 战堂队伍管理
    fun disbandWarTeam(teamId: String) {
        viewModelScope.launch {
            gameEngine.disbandWarTeam(teamId)
            _successMessage.value = "队伍已解散"
        }
    }

    fun createWarTeam(name: String, leaderId: String, memberIds: List<String>) {
        viewModelScope.launch {
            gameEngine.createWarTeam(name, memberIds)
            _successMessage.value = "队伍 $name 已创建"
        }
    }

    // 行商相关
    fun refreshMerchant() {
        viewModelScope.launch {
            _successMessage.value = "行商已刷新"
        }
    }

    fun buyFromMerchant(itemId: String, quantity: Int = 1) {
        viewModelScope.launch {
            try {
                gameEngine.buyMerchantItem(itemId, quantity)
                _successMessage.value = "购买成功"
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "购买失败"
            }
        }
    }

    // 交易行相关
    fun buyFromTradingHall(itemId: String) {
        viewModelScope.launch {
            _successMessage.value = "购买成功"
        }
    }

    fun sellToTradingHall(itemId: String) {
        viewModelScope.launch {
            _successMessage.value = "出售成功"
        }
    }

    // 招募相关
    private val recruitingDiscipleIds = mutableSetOf<String>()

    fun recruitDisciple(disciple: com.xianxia.sect.core.model.Disciple) {
        // 防止重复点击导致重复招募
        if (recruitingDiscipleIds.contains(disciple.id)) {
            return
        }
        recruitingDiscipleIds.add(disciple.id)

        viewModelScope.launch {
            try {
                val success = gameEngine.recruitDiscipleFromList(disciple)
                if (success) {
                    _successMessage.value = "成功招募 ${disciple.name}"
                }
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
                _successMessage.value = "收获成功"
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

    // 丹药使用
    fun usePill(discipleId: String, pillId: String) {
        viewModelScope.launch {
            try {
                gameEngine.usePill(discipleId, pillId)
                _successMessage.value = "丹药使用成功"
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "使用丹药失败"
            }
        }
    }

    fun usePill(discipleId: String, pill: Pill) {
        viewModelScope.launch {
            try {
                gameEngine.usePill(discipleId, pill.id)
                _successMessage.value = "丹药使用成功"
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "使用丹药失败"
            }
        }
    }

    // 错误处理
    fun clearError() {
        _errorMessage.value = null
    }

    fun clearSuccessMessage() {
        _successMessage.value = null
    }

    fun clearErrorMessage() {
        _errorMessage.value = null
    }

    fun clearMessage() {
        _successMessage.value = null
        _errorMessage.value = null
    }

    fun updateMonthlySalaryEnabled(realm: Int, enabled: Boolean) {
        // TODO: 实现月俸功能状态更新
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
                    _successMessage.value = "一键出售成功，获得 ${totalValue} 灵石"
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

    override fun onCleared() {
        super.onCleared()
        Log.i(TAG, "GameViewModel cleared, stopping game loop and performing auto save")
        
        // Stop game loop immediately to prevent further state updates
        stopGameLoop()
        
        try {
            val currentGameData = gameEngine.gameData.value
            val currentSlot = currentGameData.currentSlot
            val saveData = SaveData(
                gameData = currentGameData,
                disciples = gameEngine.disciples.value,
                equipment = gameEngine.equipment.value,
                manuals = gameEngine.manuals.value,
                pills = gameEngine.pills.value,
                materials = gameEngine.materials.value,
                herbs = gameEngine.herbs.value,
                seeds = gameEngine.seeds.value,
                teams = gameEngine.teams.value,
                slots = gameEngine.buildingSlots.value,
                events = gameEngine.events.value,
                battleLogs = gameEngine.battleLogs.value,
                warTeams = gameEngine.warTeams.value,
                alliances = gameEngine.gameData.value.alliances,
                supportTeams = gameEngine.gameData.value.supportTeams
            )
            saveManager.save(currentSlot, saveData)
            Log.i(TAG, "Auto save on exit completed, slot: $currentSlot")
        } catch (e: Exception) {
            Log.e(TAG, "Auto save on exit failed: ${e.message}")
        }
    }
}


