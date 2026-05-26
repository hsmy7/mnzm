package com.xianxia.sect.ui.game

import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.registry.ForgeRecipeDatabase
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.service.HighFrequencyData
import com.xianxia.sect.core.engine.system.SystemError
import com.xianxia.sect.core.engine.system.SystemManager
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.BattleResultUIData
import com.xianxia.sect.core.state.GameNotification
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.usecase.DisciplePositionQueryUseCase
import com.xianxia.sect.ui.navigation.GameRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import javax.inject.Inject

@HiltViewModel
class GameViewModel @Inject constructor(
    private val gameEngine: GameEngine,
    private val gameEngineCore: GameEngineCore,
    @ApplicationContext private val appContext: Context,
    private val systemManager: SystemManager,
    private val disciplePositionQuery: DisciplePositionQueryUseCase,
    private val buildingConfigService: BuildingConfigService
) : BaseViewModel() {

    companion object {
        private const val TAG = "GameViewModel"
    }

    // Navigation events — emitted by ViewModel for code-triggered dialogs (GameOver, etc.)
    private val _navigationEvents = Channel<GameRoute>(Channel.BUFFERED)
    val navigationEvents: Flow<GameRoute> = _navigationEvents.receiveAsFlow()

    // Pop-back events — emitted by closeCurrentDialog() for dialog-internal dismissal
    // null = pop one entry, non-null route = pop to that route (e.g. "empty" for root)
    private val _popBackEvents = Channel<String?>(Channel.CONFLATED)
    val popBackEvents: Flow<String?> = _popBackEvents.receiveAsFlow()

    init {
        viewModelScope.launch {
            systemManager.errors.collect { error ->
                Log.e(TAG, "System error in ${error.systemName} (${error.tickType}): ${error.error.message}")
                showError("系统异常：${error.systemName}")
            }
        }
    }

    /**
     * 关闭当前对话框 — dialogs call this internally
     */
    fun closeCurrentDialog() {
        _popBackEvents.trySend(null)
    }

    fun closeAllDialogs() {
        _popBackEvents.trySend("empty")
    }

    fun openSpiritMineDialog(mineIndex: Int = 0) {
        _navigationEvents.trySend(GameRoute.SpiritMine)
    }

    fun openHerbGardenDialog() {
        _navigationEvents.trySend(GameRoute.HerbGarden)
    }

    fun openAlchemyDialog(buildingIndex: Int = 0) {
        _navigationEvents.trySend(GameRoute.Alchemy)
    }

    fun openForgeDialog(buildingIndex: Int = 0) {
        _navigationEvents.trySend(GameRoute.Forge)
    }

    fun openLibraryDialog() {
        _navigationEvents.trySend(GameRoute.Library)
    }

    fun openWenDaoPeakDialog() {
        _navigationEvents.trySend(GameRoute.WenDaoPeak)
    }

    fun openQingyunPeakDialog() {
        _navigationEvents.trySend(GameRoute.QingyunPeak)
    }

    fun openTianshuHallDialog() {
        _navigationEvents.trySend(GameRoute.TianshuHall)
    }

    fun openLawEnforcementHallDialog() {
        _navigationEvents.trySend(GameRoute.LawEnforcementHall)
    }

    fun openMissionHallDialog() {
        _navigationEvents.trySend(GameRoute.MissionHall)
    }

    fun openReflectionCliffDialog() {
        _navigationEvents.trySend(GameRoute.ReflectionCliff)
    }

    fun openWorldMapDialog() {
        _navigationEvents.trySend(GameRoute.WorldMap)
    }

    fun openRecruitDialog() {
        _navigationEvents.trySend(GameRoute.Recruit)
    }

    fun openMerchantDialog() {
        _navigationEvents.trySend(GameRoute.Merchant)
    }

    fun openDiplomacyDialog() {
        _navigationEvents.trySend(GameRoute.Diplomacy)
    }

    fun attackWorldLevel(levelId: String, discipleIds: List<String?>) {
        viewModelScope.launch {
            gameEngine.attackWorldLevel(levelId, discipleIds)
        }
    }

    fun openBattleLogDialog() {
        _navigationEvents.trySend(GameRoute.BattleLog)
    }

    fun dismissBattleResult() {
        gameEngine.clearPendingBattleResult()
    }

    fun placeBuilding(name: String, gridX: Int, gridY: Int, width: Int = 2, height: Int = 3) {
        viewModelScope.launch {
            val config = buildingConfigService.getBuildingConfigByDisplayName(name)
            val cost = config?.cost ?: 1000L
            val (gridW, gridH) = buildingConfigService.getBuildingGridSize(name)

            // Pre-compute new production slot for alchemy/forge multi-build
            val newProductionSlot = when (name) {
                "炼丹炉" -> {
                    val idx = gameEngine.gameDataSnapshot.placedBuildings.count { it.displayName == "炼丹炉" }
                    ProductionSlot.createIdle(slotIndex = idx, buildingType = BuildingType.ALCHEMY, buildingId = "alchemy")
                }
                "锻造坊" -> {
                    val idx = gameEngine.gameDataSnapshot.placedBuildings.count { it.displayName == "锻造坊" }
                    ProductionSlot.createIdle(slotIndex = idx, buildingType = BuildingType.FORGE, buildingId = "forge")
                }
                else -> null
            }

            val activeId = gameEngine.currentActiveSectId()
            gameEngine.updateGameData { data ->
                when (name) {
                    "灵矿场" -> {
                        val currentMines = data.placedBuildings.count { it.displayName == "灵矿场" && it.sectId == activeId }
                        if (currentMines >= GameConfig.Production.MAX_SPIRIT_MINE_COUNT) return@updateGameData data
                    }
                    "炼丹炉" -> {
                        val cnt = data.placedBuildings.count { it.displayName == "炼丹炉" && it.sectId == activeId }
                        if (cnt >= GameConfig.Production.MAX_ALCHEMY_FURNACE_COUNT) return@updateGameData data
                    }
                    "锻造坊" -> {
                        val cnt = data.placedBuildings.count { it.displayName == "锻造坊" && it.sectId == activeId }
                        if (cnt >= GameConfig.Production.MAX_FORGE_WORKSHOP_COUNT) return@updateGameData data
                    }
                    "单人住所", "多人住所", "灵田" -> {
                        // No build limit for residences and spirit fields
                    }
                    else -> {
                        if (data.placedBuildings.any { it.displayName == name && it.sectId == activeId }) return@updateGameData data
                    }
                }
                if (data.spiritStones < cost) return@updateGameData data

                val newSlots = if (name == "灵矿场") {
                    val nextIndex = data.spiritMineSlots.size
                    (0 until 3).map { SpiritMineSlot(index = nextIndex + it) }
                } else emptyList()

                val newBuilding = GridBuildingData(
                    buildingId = name,
                    displayName = name,
                    gridX = gridX,
                    gridY = gridY,
                    width = gridW,
                    height = gridH,
                    sectId = activeId
                ).withInstanceId()

                val newResidenceSlots = when (name) {
                    "单人住所" -> (0 until 1).map { ResidenceSlot(buildingInstanceId = newBuilding.instanceId, slotIndex = it) }
                    "多人住所" -> (0 until 4).map { ResidenceSlot(buildingInstanceId = newBuilding.instanceId, slotIndex = it) }
                    else -> emptyList()
                }

                val newSpiritFieldPlants = if (name == "灵田") {
                    listOf(SpiritFieldPlant(buildingInstanceId = newBuilding.instanceId, sectId = activeId))
                } else emptyList()

                data.copy(
                    spiritStones = data.spiritStones - cost,
                    placedBuildings = data.placedBuildings + newBuilding,
                    spiritFieldPlants = data.spiritFieldPlants + newSpiritFieldPlants,
                    spiritMineSlots = data.spiritMineSlots + newSlots,
                    residenceSlots = data.residenceSlots + newResidenceSlots,
                    // TODO: 后续统一到 Repository 单一数据源，移除对 GameData.productionSlots 的写入
                    productionSlots = if (newProductionSlot != null)
                        data.productionSlots + newProductionSlot else data.productionSlots
                )
            }

            if (newProductionSlot != null) {
                gameEngine.addProductionSlot(newProductionSlot)
            }
        }
    }

    fun getBuildingCost(displayName: String): Long {
        return buildingConfigService.getBuildingConfigByDisplayName(displayName)?.cost ?: 1000L
    }

    fun getBuildingGridSize(displayName: String): Pair<Int, Int> {
        return buildingConfigService.getBuildingGridSize(displayName)
    }

    fun moveBuilding(instanceId: String, newGridX: Int, newGridY: Int) {
        gameEngine.moveBuildingDirect(instanceId, newGridX, newGridY)
    }

    /** 修正已有存档中的建筑尺寸（存档加载后调用） */
    fun fixupBuildingSizesIfNeeded() {
        viewModelScope.launch {
            gameEngine.updateGameData { data ->
                val fixed = buildingConfigService.fixupBuildingSizes(data.placedBuildings)
                val withIds = GridBuildingData.ensureAllHaveInstanceId(fixed)
                if (withIds != data.placedBuildings) data.copy(placedBuildings = withIds) else data
            }
        }
    }

    val gameData: StateFlow<GameData> = gameEngine.gameData
        .stateIn(viewModelScope, sharingStarted, gameEngine.gameData.value ?: GameData())

    val placedBuildings: StateFlow<List<GridBuildingData>> = gameData
        .map { it.placedBuildings }
        .distinctUntilChanged()
        .stateIn(viewModelScope, sharingStarted, emptyList())

    val pendingNotification: StateFlow<GameNotification?> get() = gameEngine.pendingNotification

    /**
     * 弟子聚合数据 - 用于 UI 层显示（推荐使用）
     *
     * 此属性将底层的 List<Disciple> 自动转换为 List<DiscipleAggregate>，
     * 确保 UI 层统一使用新的多表架构类型。
     */
    val discipleAggregates: StateFlow<List<DiscipleAggregate>> = gameEngine.discipleAggregates
        .stateIn(viewModelScope, sharingStarted, emptyList())

    val disciples: StateFlow<List<DiscipleAggregate>> = discipleAggregates

    /**
     * 可招募弟子聚合数据 - 响应式数据流
     *
     * 根据 gameData.recruitList 中的 ID 从全量弟子中筛选出可招募弟子。
     * 当招募列表或弟子数据变化时自动更新，确保 RecruitDialog 显示最新数据。
     */
    val recruitListAggregates: StateFlow<List<DiscipleAggregate>> = gameData
        .map { data -> data.recruitList.map { it.toAggregate() } }
        .stateIn(viewModelScope, sharingStarted, emptyList())

    val equipment: StateFlow<List<EquipmentInstance>> = gameEngine.equipmentInstances
        .stateIn(viewModelScope, sharingStarted, emptyList())

    val manuals: StateFlow<List<ManualInstance>> = gameEngine.manualInstances
        .stateIn(viewModelScope, sharingStarted, emptyList())

    val equipmentStacks: StateFlow<List<EquipmentStack>> = combine(
        gameEngine.equipmentStacks,
        gameEngine.disciples
    ) { stacks, disciples ->
        val bagStackIds = disciples.filter { it.isAlive }
            .flatMap { it.equipment.storageBagItems }
            .filter { it.itemType == "equipment_stack" }
            .map { it.itemId }
            .toSet()
        stacks.filter { it.id !in bagStackIds }
    }.stateIn(viewModelScope, sharingStarted, emptyList())

    val equipmentInstances: StateFlow<List<EquipmentInstance>> = gameEngine.equipmentInstances
        .stateIn(viewModelScope, sharingStarted, emptyList())

    val manualStacks: StateFlow<List<ManualStack>> = combine(
        gameEngine.manualStacks,
        gameEngine.disciples
    ) { stacks, disciples ->
        val bagStackIds = disciples.filter { it.isAlive }
            .flatMap { it.equipment.storageBagItems }
            .filter { it.itemType == "manual_stack" }
            .map { it.itemId }
            .toSet()
        stacks.filter { it.id !in bagStackIds }
    }.stateIn(viewModelScope, sharingStarted, emptyList())

    val manualInstances: StateFlow<List<ManualInstance>> = gameEngine.manualInstances
        .stateIn(viewModelScope, sharingStarted, emptyList())

    val pills: StateFlow<List<Pill>> = gameEngine.pills
        .stateIn(viewModelScope, sharingStarted, emptyList())

    val materials: StateFlow<List<Material>> = gameEngine.materials
        .stateIn(viewModelScope, sharingStarted, emptyList())

    val herbs: StateFlow<List<Herb>> = gameEngine.herbs
        .stateIn(viewModelScope, sharingStarted, emptyList())

    val seeds: StateFlow<List<Seed>> = gameEngine.seeds
        .stateIn(viewModelScope, sharingStarted, emptyList())

    val teams: StateFlow<List<ExplorationTeam>> = gameEngine.teams
        .stateIn(viewModelScope, sharingStarted, emptyList())

    val battleLogs: StateFlow<List<BattleLog>> = gameEngine.battleLogs
        .stateIn(viewModelScope, sharingStarted, emptyList())

    val pendingBattleResult: StateFlow<BattleResultUIData?> = gameEngine.pendingBattleResult
        .stateIn(viewModelScope, sharingStarted, null)

    val alliances: StateFlow<List<Alliance>> = gameEngine.gameData
        .map { it.alliances }
        .stateIn(viewModelScope, sharingStarted, emptyList())

    val productionSlots: StateFlow<List<com.xianxia.sect.core.model.production.ProductionSlot>> = gameEngine.productionSlots
        .stateIn(viewModelScope, sharingStarted, emptyList())

    val worldMapRenderData: StateFlow<WorldMapRenderData> = gameEngine.worldMapRenderData
        .stateIn(viewModelScope, sharingStarted, WorldMapRenderData())

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
                    requiredMaterials = slot.requiredMaterials,
                    assignedDiscipleId = slot.assignedDiscipleId,
                    assignedDiscipleName = slot.assignedDiscipleName,
                    autoRestartEnabled = slot.autoRestartEnabled
                )
            }
        }
        .stateIn(viewModelScope, sharingStarted, emptyList())

    val highFrequencyData: StateFlow<HighFrequencyData> = gameEngine.highFrequencyData
        .stateIn(viewModelScope, sharingStarted, HighFrequencyData())

    val realtimeCultivation: StateFlow<Map<String, Double>> = gameEngine.realtimeCultivation
        .stateIn(viewModelScope, sharingStarted, emptyMap())

    fun hasDisciplePosition(discipleId: String): Boolean {
        return disciplePositionQuery.hasDisciplePosition(discipleId)
    }

    fun getDisciplePosition(discipleId: String): String? {
        return disciplePositionQuery.getDisciplePosition(discipleId)
    }

    fun isReserveDisciple(discipleId: String): Boolean {
        return disciplePositionQuery.isReserveDisciple(discipleId)
    }

    fun isPositionWorkStatus(discipleId: String): Boolean {
        return disciplePositionQuery.isPositionWorkStatus(discipleId)
    }

    // 防止重复点击标志

    // 顶层 inline overlay z-order 列表（最后的 = 最顶层）
    private val _overlayOrder = mutableStateListOf<TopOverlay>()
    val overlayOrder: List<TopOverlay> get() = _overlayOrder

    fun pushOverlay(overlay: TopOverlay) {
        _overlayOrder.remove(overlay)
        _overlayOrder.add(overlay)
    }

    fun popOverlay(overlay: TopOverlay) {
        _overlayOrder.remove(overlay)
    }

    // 弟子详情 — 顶层全屏覆盖，统一所有触发入口
    private val _detailDisciple = MutableStateFlow<DiscipleDetailRequest?>(null)
    val detailDisciple: StateFlow<DiscipleDetailRequest?> = _detailDisciple.asStateFlow()

    fun showDiscipleDetail(request: DiscipleDetailRequest) {
        _detailDisciple.value = request
        pushOverlay(TopOverlay.DISCIPLE_DETAIL)
    }

    fun dismissDiscipleDetail() {
        _detailDisciple.value = null
        popOverlay(TopOverlay.DISCIPLE_DETAIL)
    }

    fun navigateDiscipleDetail(disciple: DiscipleAggregate) {
        val current = _detailDisciple.value ?: return
        val target = current.allDisciples.find { it.id == disciple.id } ?: disciple
        _detailDisciple.value = current.copy(disciple = target)
    }

    private val _selectedBuildingId = MutableStateFlow<String?>(null)
    val selectedBuildingId: StateFlow<String?> = _selectedBuildingId.asStateFlow()

    private val _selectedPlantSlotIndex = MutableStateFlow<Int?>(null)
    val selectedPlantSlotIndex: StateFlow<Int?> = _selectedPlantSlotIndex.asStateFlow()

    val forgeSlots: StateFlow<List<ForgeSlot>> = productionSlots
        .map { slots ->
            slots.filter { it.buildingType == com.xianxia.sect.core.model.production.BuildingType.FORGE }.map { slot ->
                val recipe = slot.recipeId?.let {
                    com.xianxia.sect.core.registry.ForgeRecipeDatabase.getRecipeById(it)
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
                    },
                    assignedDiscipleId = slot.assignedDiscipleId,
                    assignedDiscipleName = slot.assignedDiscipleName,
                    autoRestartEnabled = slot.autoRestartEnabled
                )
            }
        }
        .stateIn(viewModelScope, sharingStarted, emptyList())

    val plantSlots: StateFlow<List<com.xianxia.sect.core.model.production.ProductionSlot>> = productionSlots
        .map { slots -> slots.filter { it.buildingType == com.xianxia.sect.core.model.production.BuildingType.HERB_GARDEN } }
        .stateIn(viewModelScope, sharingStarted, emptyList())

    val allForgeRecipes: StateFlow<List<ForgeRecipeDatabase.ForgeRecipe>> = flow {
        emit(ForgeRecipeDatabase.getAllRecipes())
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _gameState = MutableStateFlow("LOADING")
    val gameState: StateFlow<String> = _gameState.asStateFlow()

    val isPaused: StateFlow<Boolean> = gameEngineCore.state
        .map { it.isPaused }
        .stateIn(viewModelScope, SharingStarted.Lazily, true)

    private val _gameLog = MutableStateFlow<List<String>>(emptyList())
    val gameLog: StateFlow<List<String>> = _gameLog.asStateFlow()

    private val _notifications = MutableStateFlow<List<String>>(emptyList())
    val notifications: StateFlow<List<String>> = _notifications.asStateFlow()

    // 建筑详情对话框 (带参数)
    fun openBuildingDetailDialog(buildingId: String) {
        _selectedBuildingId.value = buildingId
    }

    // 招募弟子
    fun recruitDisciple() {
        viewModelScope.launch {
            try {
                gameEngine.recruitDisciple()
            } catch (e: Exception) {
                showError(e.message ?: "招募弟子失败")
            }
        }
    }

    fun assignDiscipleToBuilding(buildingId: String, slotIndex: Int, discipleId: String) {
        viewModelScope.launch {
            try {
                gameEngine.assignDiscipleToBuilding(buildingId, slotIndex, discipleId)
            } catch (e: Exception) {
                showError(e.message ?: "分配失败")
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

    fun clearNotification() {
        gameEngine.clearPendingNotification()
    }

    fun enterSect(sectId: String) {
        gameEngine.enterSect(sectId)
    }

    fun expelTheftDisciple(discipleId: String) {
        viewModelScope.launch {
            gameEngine.expelTheftDisciple(discipleId)
            gameEngine.clearPendingNotification()
        }
    }

    fun imprisonTheftDisciple(discipleId: String, currentYear: Int) {
        gameEngine.imprisonTheftDisciple(discipleId, currentYear)
        gameEngine.clearPendingNotification()
    }

    fun releaseTheftDisciple(discipleId: String): Int {
        return gameEngine.releaseTheftDisciple(discipleId)
    }

    fun onLoyaltyDialogDismissed() {
        gameEngine.clearPendingNotification()
    }

    fun toggleFollowDisciple(discipleId: String) {
        viewModelScope.launch {
            gameEngine.updateDisciple(discipleId) { disciple ->
                val currentFollowed = disciple.statusData["followed"] == "true"
                val newStatusData = disciple.statusData.toMutableMap().apply {
                    if (currentFollowed) remove("followed") else this["followed"] = "true"
                }
                disciple.copy(statusData = newStatusData)
            }
        }
    }

    fun changeDiscipleType(discipleId: String, newType: String) {
        viewModelScope.launch {
            gameEngine.updateDisciple(discipleId) { it.copy(discipleType = newType) }
            gameEngine.syncAllDiscipleStatuses()
        }
    }

    fun toggleAutoEquipFromWarehouse(discipleId: String, enabled: Boolean) {
        viewModelScope.launch {
            gameEngine.updateDisciple(discipleId) { disciple ->
                disciple.copyWith(autoEquipFromWarehouse = enabled)
            }
        }
    }

    fun toggleAutoLearnFromWarehouse(discipleId: String, enabled: Boolean) {
        viewModelScope.launch {
            gameEngine.updateDisciple(discipleId) { disciple ->
                disciple.copyWith(autoLearnFromWarehouse = enabled)
            }
        }
    }

    suspend fun rewardItemsToDisciple(discipleId: String, items: List<RewardSelectedItem>) {
        gameEngine.rewardItemsToDisciple(discipleId, items)
    }

    fun recruitAllDisciples() {
        viewModelScope.launch {
            val success = gameEngine.recruitAllFromList()
            if (!success) {
                showError("无弟子可招募")
            }
        }
    }

    fun rejectDiscipleFromList(discipleId: String) {
        viewModelScope.launch {
            gameEngine.removeFromRecruitList(discipleId)
        }
    }

    fun setAutoRecruitFilter(filter: Set<Int>) {
        viewModelScope.launch {
            gameEngine.updateGameData { gd ->
                gd.copy(autoRecruitSpiritRootFilter = filter)
            }
        }
    }

    fun equipItem(discipleId: String, equipmentId: String) {
        viewModelScope.launch {
            try {
                gameEngine.equipItem(discipleId, equipmentId)
            } catch (e: Exception) {
                showError(e.message ?: "装备失败")
            }
        }
    }

    fun unequipItem(discipleId: String, slot: EquipmentSlot) {
        viewModelScope.launch {
            try {
                gameEngine.unequipItem(discipleId, slot)
            } catch (e: Exception) {
                showError(e.message ?: "卸下装备失败")
            }
        }
    }

    fun unequipItem(discipleId: String, equipmentId: String) {
        viewModelScope.launch {
            try {
                gameEngine.unequipItemById(discipleId, equipmentId)
            } catch (e: Exception) {
                showError(e.message ?: "卸下装备失败")
            }
        }
    }

    fun forgetManual(discipleId: String, instanceId: String) {
        viewModelScope.launch {
            try {
                gameEngine.forgetManual(discipleId, instanceId)
            } catch (e: Exception) {
                showError(e.message ?: "遗忘功法失败")
            }
        }
    }

    fun replaceManual(discipleId: String, oldInstanceId: String, newStackId: String) {
        viewModelScope.launch {
            try {
                gameEngine.replaceManual(discipleId, oldInstanceId, newStackId)
            } catch (e: Exception) {
                showError(e.message ?: "功法替换失败")
            }
        }
    }

    fun learnManual(discipleId: String, stackId: String) {
        viewModelScope.launch {
            try {
                gameEngine.learnManual(discipleId, stackId)
            } catch (e: Exception) {
                showError(e.message ?: "学习功法失败")
            }
        }
    }

    fun recallTeam(teamId: String) {
        viewModelScope.launch {
            try {
                gameEngine.recallTeam(teamId)
            } catch (e: Exception) {
                showError(e.message ?: "召回失败")
            }
        }
    }

    fun buyFromMerchant(itemId: String, quantity: Int = 1) {
        viewModelScope.launch {
            try {
                gameEngine.buyMerchantItem(itemId, quantity)
            } catch (e: Exception) {
                showError(e.message ?: "购买失败")
            }
        }
    }

    fun listItemsToMerchant(items: List<Pair<String, Int>>) {
        viewModelScope.launch {
            try {
                gameEngine.listItemsToMerchant(items)
            } catch (e: Exception) {
                showError(e.message ?: "上架失败")
            }
        }
    }

    fun removePlayerListedItem(itemId: String) {
        viewModelScope.launch {
            try {
                gameEngine.removePlayerListedItem(itemId)
            } catch (e: Exception) {
                showError(e.message ?: "下架失败")
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
                showError(e.message ?: "种植失败")
            }
        }
    }

    fun plantOnSpiritField(buildingInstanceId: String, seedId: String, sectId: String) {
        viewModelScope.launch {
            try {
                gameEngine.plantOnSpiritField(buildingInstanceId, seedId, sectId)
            } catch (e: Exception) {
                showError(e.message ?: "种植失败")
            }
        }
    }

    fun removePlantFromSpiritField(buildingInstanceId: String) {
        viewModelScope.launch {
            try {
                gameEngine.removePlantFromSpiritField(buildingInstanceId)
            } catch (e: Exception) {
                showError(e.message ?: "铲除失败")
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
                showError(e.message ?: "使用丹药失败")
            }
        }
    }

    fun usePill(discipleId: String, pill: Pill) {
        viewModelScope.launch {
            try {
                gameEngine.usePill(discipleId, pill.id)
            } catch (e: Exception) {
                showError(e.message ?: "使用丹药失败")
            }
        }
    }

    fun confiscateStorageBagItem(discipleId: String, item: StorageBagItem) {
        viewModelScope.launch {
            try {
                gameEngine.confiscateStorageBagItem(discipleId, item)
            } catch (e: Exception) {
                showError(e.message ?: "没收物品失败")
            }
        }
    }

    fun getDiscipleById(id: String): DiscipleAggregate? {
        return discipleAggregates.value.find { it.id == id }
    }

    @Suppress("DEPRECATION")
    fun getManualById(id: String): ManualInstance? {
        return manuals.value.find { it.id == id }
    }

    fun getManualInstanceById(id: String): ManualInstance? {
        return manualInstances.value.find { it.id == id }
    }

    fun getEquipmentInstanceById(id: String): EquipmentInstance? {
        return equipmentInstances.value.find { it.id == id }
    }

    fun toggleItemLock(itemId: String, itemType: String) {
        viewModelScope.launch {
            gameEngine.toggleItemLock(itemId, itemType)
        }
    }

    fun sellItem(itemId: String, itemType: String, quantity: Int) {
        viewModelScope.launch {
            val result = when (itemType) {
                "equipment" -> gameEngine.sellEquipment(itemId, quantity)
                "manual" -> gameEngine.sellManual(itemId, quantity)
                "pill" -> gameEngine.sellPill(itemId, quantity)
                "material" -> gameEngine.sellMaterial(itemId, quantity)
                "herb" -> gameEngine.sellHerb(itemId, quantity)
                "seed" -> gameEngine.sellSeed(itemId, quantity)
                else -> false
            }
            if (!result) {
                showError("售卖失败，物品可能已被锁定或不存在")
            }
        }
    }
    fun bulkSellItems(
        selectedRarities: Set<Int>,
        selectedTypes: Set<String>
    ) {
        viewModelScope.launch {
            try {
                val operations = mutableListOf<GameEngine.BulkSellOperation>()
                
                if (selectedTypes.contains("EQUIPMENT")) {
                    equipmentStacks.value.filter { 
                        selectedRarities.contains(it.rarity) && 
                        !it.isLocked
                    }.forEach { item ->
                        operations.add(GameEngine.BulkSellOperation(item.id, item.name, item.quantity, "equipment"))
                    }
                }

                if (selectedTypes.contains("MANUAL")) {
                    manualStacks.value.filter { 
                        selectedRarities.contains(it.rarity) && 
                        !it.isLocked
                    }.forEach { item ->
                        operations.add(GameEngine.BulkSellOperation(item.id, item.name, item.quantity, "manual"))
                    }
                }

                if (selectedTypes.contains("PILL")) {
                    pills.value.filter { 
                        selectedRarities.contains(it.rarity) && !it.isLocked
                    }.forEach { item ->
                        operations.add(GameEngine.BulkSellOperation(item.id, item.name, item.quantity, "pill"))
                    }
                }

                if (selectedTypes.contains("MATERIAL")) {
                    materials.value.filter { 
                        selectedRarities.contains(it.rarity) && !it.isLocked
                    }.forEach { item ->
                        operations.add(GameEngine.BulkSellOperation(item.id, item.name, item.quantity, "material"))
                    }
                }

                if (selectedTypes.contains("HERB")) {
                    herbs.value.filter { 
                        selectedRarities.contains(it.rarity) && !it.isLocked
                    }.forEach { item ->
                        operations.add(GameEngine.BulkSellOperation(item.id, item.name, item.quantity, "herb"))
                    }
                }

                if (selectedTypes.contains("SEED")) {
                    seeds.value.filter { 
                        selectedRarities.contains(it.rarity) && !it.isLocked
                    }.forEach { item ->
                        operations.add(GameEngine.BulkSellOperation(item.id, item.name, item.quantity, "seed"))
                    }
                }

                if (operations.isEmpty()) {
                    showError("没有符合条件的物品可出售（已排除锁定物品）")
                    return@launch
                }

                try {
                    val result = gameEngine.bulkSellItems(operations)
                    if (result.soldCount > 0) {
                        val msg = buildString {
                            append("成功出售 ${result.soldCount} 件物品，获得 ${result.totalEarned} 灵石")
                            if (result.failedItemNames.isNotEmpty()) {
                                append("\n以下物品出售失败：${result.failedItemNames.joinToString("、")}")
                            }
                        }
                        showSuccess(msg)
                    } else {
                        showError("出售失败，物品可能已被锁定或不存在")
                    }
                } catch (e: Exception) {
                    showError("出售过程中发生错误: ${e.message}")
                }
            } catch (e: Exception) {
                showError(e.message ?: "一键出售失败")
            }
        }
    }

    fun getEquipmentById(id: String): EquipmentInstance? {
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
                // TODO(U-01 Phase3): GameEngine.startMission 应接受 DiscipleAggregate
                gameEngine.startMission(mission, selectedDisciples.map { it.toDisciple() })
            } catch (e: Exception) {
                showError(e.message ?: "开始任务失败")
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
        try {
            gameEngineCore.stopGameLoop()
        } catch (e: Exception) {
            Log.w(TAG, "stopGameLoop failed: ${e.message}")
        }
    }

    fun openSalaryConfigDialog() {
        _navigationEvents.trySend(GameRoute.SalaryConfig)
    }

    val isGameOver: StateFlow<Boolean> = gameEngine.gameData
        .map { it.isGameOver }
        .distinctUntilChanged()
        .stateIn(viewModelScope, sharingStarted, false)

    fun openGameOverDialog() {
        viewModelScope.launch {
            try {
                gameEngineCore.pause()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to pause game engine on game over", e)
            }
            _navigationEvents.send(GameRoute.GameOver)
        }
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
                    showSuccess(result.message)
                } else {
                    showError(result.message)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error redeeming code", e)
                showError("兑换失败: ${e.message}")
            }
        }
    }

    fun clearRedeemResult() {
        _redeemResult.value = null
    }

    // region Residence

    fun assignToResidence(buildingInstanceId: String, slotIndex: Int, discipleId: String) {
        viewModelScope.launch {
            val discipleName = gameEngine.discipleAggregatesSnapshot.find { it.id == discipleId }?.name ?: ""
            gameEngine.updateGameData { data ->
                // Remove disciple from any other residence slot first
                val cleared = data.residenceSlots.map { slot ->
                    if (slot.discipleId == discipleId) slot.copy(discipleId = "", discipleName = "") else slot
                }.toMutableList()

                // Find and update the target slot (or add it)
                val existingIndex = cleared.indexOfFirst {
                    it.buildingInstanceId == buildingInstanceId && it.slotIndex == slotIndex
                }
                val newSlot = ResidenceSlot(
                    buildingInstanceId = buildingInstanceId,
                    slotIndex = slotIndex,
                    discipleId = discipleId,
                    discipleName = discipleName
                )
                if (existingIndex >= 0) {
                    cleared[existingIndex] = newSlot
                } else {
                    cleared.add(newSlot)
                }
                data.copy(residenceSlots = cleared)
            }
        }
    }

    fun removeFromResidence(buildingInstanceId: String, slotIndex: Int) {
        viewModelScope.launch {
            gameEngine.updateGameData { data ->
                data.copy(
                    residenceSlots = data.residenceSlots.map { slot ->
                        if (slot.buildingInstanceId == buildingInstanceId && slot.slotIndex == slotIndex)
                            slot.copy(discipleId = "", discipleName = "")
                        else slot
                    }
                )
            }
        }
    }

    fun canUpgradeResidence(buildingInstanceId: String): Boolean {
        val data = gameEngine.gameData.value ?: return false
        val building = data.placedBuildings.find { it.instanceId == buildingInstanceId } ?: return false
        return building.displayName == "单人住所"
    }

    fun upgradeSingleResidence(buildingInstanceId: String) {
        viewModelScope.launch {
            gameEngine.updateGameData { data ->
                val canAfford = data.spiritStones >= 5000L
                if (!canAfford) return@updateGameData data
                data.copy(
                    spiritStones = data.spiritStones - 5000L,
                    placedBuildings = data.placedBuildings.map { b ->
                        if (b.instanceId == buildingInstanceId && b.displayName == "单人住所")
                            b.copy(displayName = "中级单人住所")
                        else b
                    }
                )
            }
        }
    }

    // endregion

    override fun onCleared() {
        Log.i(TAG, "GameViewModel cleared, stopping game loop and releasing resources")

        // 停止游戏循环并释放资源
        // 保存逻辑由 SaveLoadViewModel.onCleared() 统一负责，避免重复保存导致竞态条件
        clearResources()

        super.onCleared()
    }

}

/**
 * 弟子详情全屏覆盖请求。
 * 所有触发入口统一通过 [GameViewModel.showDiscipleDetail] 发送，
 * 由 MainGameScreen 在最顶层渲染。
 */
data class DiscipleDetailRequest(
    val disciple: DiscipleAggregate,
    val allDisciples: List<DiscipleAggregate>,
    val onNavigateToDisciple: ((DiscipleAggregate) -> Unit)? = null
)

/** 顶层 inline overlay 类型，用于 z-order 排序。渲染顺序即列表顺序（最后的在最顶层）。 */
enum class TopOverlay {
    DISCIPLE_DETAIL,
    BATTLE_RESULT,
    BATTLE_LOG_DETAIL
}

