package com.xianxia.sect.ui.game

import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import com.xianxia.sect.ui.game.building.BuildingDef
import com.xianxia.sect.ui.game.building.BuildingRegistry
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
import com.xianxia.sect.ui.navigation.DialogRoute
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

    val planting = com.xianxia.sect.ui.game.delegate.PlantingDelegate(gameEngine, viewModelScope)
    val disciple = com.xianxia.sect.ui.game.delegate.DiscipleDelegate(gameEngine, viewModelScope)
    val navigation = com.xianxia.sect.ui.game.delegate.NavigationDelegate(
        gameEngine, gameEngineCore, viewModelScope,
        onNavigate = { _navigationEvents.trySend(it) },
        onPopBack = { _popBackEvents.trySend(it) }
    )
    val inventory = com.xianxia.sect.ui.game.delegate.InventoryDelegate(gameEngine, viewModelScope)

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

    private val _currentDialogRoute = MutableStateFlow<DialogRoute>(DialogRoute.None)
    val currentDialogRoute: StateFlow<DialogRoute> = _currentDialogRoute.asStateFlow()

    fun navigateToDialog(route: DialogRoute) {
        _currentDialogRoute.value = route
    }

    fun dismissDialog() {
        _currentDialogRoute.value = DialogRoute.None
    }

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
    fun closeCurrentDialog() = navigation.closeCurrentDialog()

    fun closeAllDialogs() = navigation.closeAllDialogs()

    fun openSpiritMineDialog(mineIndex: Int = 0) = navigation.openSpiritMineDialog(mineIndex)

    fun openHerbGardenDialog() = navigation.openHerbGardenDialog()

    fun openAlchemyDialog(buildingIndex: Int = 0) = navigation.openAlchemyDialog(buildingIndex)

    fun openForgeDialog(buildingIndex: Int = 0) = navigation.openForgeDialog(buildingIndex)

    fun openLibraryDialog() = navigation.openLibraryDialog()

    fun openWenDaoPeakDialog() = navigation.openWenDaoPeakDialog()

    fun openQingyunPeakDialog() = navigation.openQingyunPeakDialog()

    fun openTianshuHallDialog() = navigation.openTianshuHallDialog()

    fun openLawEnforcementHallDialog() = navigation.openLawEnforcementHallDialog()

    fun openMissionHallDialog() = navigation.openMissionHallDialog()

    fun openReflectionCliffDialog() = navigation.openReflectionCliffDialog()

    fun openPatrolTowerDialog(buildingInstanceId: String = "") = navigation.openPatrolTowerDialog(buildingInstanceId.ifEmpty { "" })

    fun openWorldMapDialog() = navigation.openWorldMapDialog()

    fun openRecruitDialog() = navigation.openRecruitDialog()

    fun openMerchantDialog() = navigation.openMerchantDialog()

    fun openDiplomacyDialog() = navigation.openDiplomacyDialog()

    fun attackWorldLevel(levelId: String, discipleIds: List<String?>) = navigation.attackWorldLevel(levelId, discipleIds)

    fun openBattleLogDialog() = navigation.openBattleLogDialog()

    fun dismissBattleResult() = navigation.dismissBattleResult()

    fun placeBuilding(name: String, gridX: Int, gridY: Int, width: Int = 2, height: Int = 3) {
        viewModelScope.launch {
            val config = buildingConfigService.getBuildingConfigByDisplayName(name)
            val cost = config?.cost ?: 1000L
            val (gridW, gridH) = buildingConfigService.getBuildingGridSize(name)

            // Pre-compute new production slot for alchemy/forge multi-build
            val newProductionSlot = when (name) {
                BuildingDef.ALCHEMY.displayName -> {
                    val idx = gameEngine.gameDataSnapshot.placedBuildings.count { it.displayName == BuildingDef.ALCHEMY.displayName }
                    ProductionSlot.createIdle(slotIndex = idx, buildingType = BuildingType.ALCHEMY, buildingId = "alchemy")
                }
                BuildingDef.FORGE.displayName -> {
                    val idx = gameEngine.gameDataSnapshot.placedBuildings.count { it.displayName == BuildingDef.FORGE.displayName }
                    ProductionSlot.createIdle(slotIndex = idx, buildingType = BuildingType.FORGE, buildingId = "forge")
                }
                else -> null
            }

            val activeId = gameEngine.currentActiveSectId()
            gameEngine.updateGameData { data ->
                when {
                    BuildingRegistry.hasNoLimit(name) -> {
                        // No build limit
                    }
                    else -> {
                        if (data.placedBuildings.any { it.displayName == name && it.sectId == activeId }) return@updateGameData data
                    }
                }
                if (data.spiritStones < cost) return@updateGameData data

                val newSlots = if (name == BuildingDef.SPIRIT_MINE.displayName) {
                    val nextIndex = data.spiritMineSlots.size
                    (0 until 3).map { SpiritMineSlot(index = nextIndex + it) }
                } else emptyList()

                val newPatrolSlots = if (name == BuildingDef.PATROL_TOWER.displayName) {
                    val nextIndex = data.patrolSlots.size
                    val slotCount = buildingConfigService.getSlotCountByDisplayName(name)
                    (0 until slotCount).map { PatrolSlot(index = nextIndex + it) }
                } else emptyList()

                val newPatrolConfigs = if (name == BuildingDef.PATROL_TOWER.displayName) {
                    data.patrolConfigs + PatrolConfig()
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
                    BuildingDef.SINGLE_RESIDENCE.displayName -> (0 until 1).map { ResidenceSlot(buildingInstanceId = newBuilding.instanceId, slotIndex = it) }
                    BuildingDef.MULTI_RESIDENCE.displayName -> (0 until 4).map { ResidenceSlot(buildingInstanceId = newBuilding.instanceId, slotIndex = it) }
                    else -> emptyList()
                }

                val newSpiritFieldPlants = if (name == BuildingDef.SPIRIT_FIELD.displayName) {
                    listOf(SpiritFieldPlant(buildingInstanceId = newBuilding.instanceId, sectId = activeId))
                } else emptyList()

                data.copy(
                    spiritStones = data.spiritStones - cost,
                    placedBuildings = data.placedBuildings + newBuilding,
                    spiritFieldPlants = data.spiritFieldPlants + newSpiritFieldPlants,
                    spiritMineSlots = data.spiritMineSlots + newSlots,
                    patrolSlots = if (newPatrolSlots.isNotEmpty()) data.patrolSlots + newPatrolSlots else data.patrolSlots,
                    patrolConfigs = if (newPatrolConfigs.isNotEmpty()) newPatrolConfigs else data.patrolConfigs,
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

    val gameData: StateFlow<GameData> get() = gameEngine.gameData

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    val gameDataUi: StateFlow<GameData> = gameEngine.gameData
        .sample(400)
        .stateIn(viewModelScope, sharingStarted, gameEngine.gameData.value ?: GameData())

    val placedBuildings: StateFlow<List<GridBuildingData>> = gameData
        .map { it.placedBuildings }
        .distinctUntilChanged()
        .stateIn(viewModelScope, sharingStarted, emptyList())

    val pendingNotification: StateFlow<GameNotification?> get() = gameEngine.pendingNotification

    val warehouseFullEvent get() = gameEngine.warehouseFullEvent

    /**
     * 弟子聚合数据 - 用于 UI 层显示（推荐使用）
     *
     * 此属性将底层的 List<Disciple> 自动转换为 List<DiscipleAggregate>，
     * 确保 UI 层统一使用新的多表架构类型。
     */
    val discipleAggregates: StateFlow<List<DiscipleAggregate>> get() = gameEngine.discipleAggregates

    val sectCombatPower: StateFlow<Long> get() = gameEngine.sectCombatPower

    val aiSectCombatPowers: StateFlow<Map<String, Long>> get() = gameEngine.aiSectCombatPowers

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

    val equipment: StateFlow<List<EquipmentInstance>> get() = gameEngine.equipmentInstances

    val manuals: StateFlow<List<ManualInstance>> get() = gameEngine.manualInstances

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

    val equipmentInstances: StateFlow<List<EquipmentInstance>> get() = gameEngine.equipmentInstances

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

    val manualInstances: StateFlow<List<ManualInstance>> get() = gameEngine.manualInstances

    val pills: StateFlow<List<Pill>> get() = gameEngine.pills

    val materials: StateFlow<List<Material>> get() = gameEngine.materials

    val herbs: StateFlow<List<Herb>> get() = gameEngine.herbs

    val seeds: StateFlow<List<Seed>> get() = gameEngine.seeds

    val teams: StateFlow<List<ExplorationTeam>> get() = gameEngine.teams

    val battleLogs: StateFlow<List<BattleLog>> get() = gameEngine.battleLogs

    val pendingBattleResult: StateFlow<BattleResultUIData?> get() = gameEngine.pendingBattleResult

    val alliances: StateFlow<List<Alliance>> = gameEngine.gameData
        .map { it.alliances }
        .stateIn(viewModelScope, sharingStarted, emptyList())

    val productionSlots: StateFlow<List<com.xianxia.sect.core.model.production.ProductionSlot>> get() = gameEngine.productionSlots

    val worldMapRenderData: StateFlow<WorldMapRenderData> get() = gameEngine.worldMapRenderData

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

    val highFrequencyData: StateFlow<HighFrequencyData> get() = gameEngine.highFrequencyData

    val realtimeCultivation: StateFlow<Map<String, Double>> get() = gameEngine.realtimeCultivation

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
        gameEngine.setFocusedDiscipleId(request.disciple.id)
        pushOverlay(TopOverlay.DISCIPLE_DETAIL)
    }

    fun dismissDiscipleDetail() {
        _detailDisciple.value = null
        gameEngine.setFocusedDiscipleId(null)
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

    fun recruitDisciple() = disciple.recruitDisciple()

    fun assignDiscipleToBuilding(buildingId: String, slotIndex: Int, discipleId: String) = disciple.assignDiscipleToBuilding(buildingId, slotIndex, discipleId)

    fun recruitDiscipleFromList(discipleId: String) = disciple.recruitDiscipleFromList(discipleId)

    fun expelDisciple(discipleId: String) = disciple.expelDisciple(discipleId)

    fun clearNotification() {
        gameEngine.clearPendingNotification()
    }

    fun enterSect(sectId: String) {
        gameEngine.enterSect(sectId)
    }

    fun expelTheftDisciple(discipleId: String) = disciple.expelTheftDisciple(discipleId)

    fun imprisonTheftDisciple(discipleId: String, currentYear: Int) = disciple.imprisonTheftDisciple(discipleId, currentYear)

    fun releaseTheftDisciple(discipleId: String): Int = disciple.releaseTheftDisciple(discipleId)

    fun onLoyaltyDialogDismissed() = disciple.onLoyaltyDialogDismissed()

    fun toggleFollowDisciple(discipleId: String) = disciple.toggleFollowDisciple(discipleId)

    fun changeDiscipleType(discipleId: String, newType: String) = disciple.changeDiscipleType(discipleId, newType)

    fun toggleAutoEquipFromWarehouse(discipleId: String, enabled: Boolean) = disciple.toggleAutoEquipFromWarehouse(discipleId, enabled)

    fun toggleAutoLearnFromWarehouse(discipleId: String, enabled: Boolean) = disciple.toggleAutoLearnFromWarehouse(discipleId, enabled)

    suspend fun rewardItemsToDisciple(discipleId: String, items: List<RewardSelectedItem>) = disciple.rewardItemsToDisciple(discipleId, items)

    fun recruitAllDisciples() = disciple.recruitAllDisciples()

    fun rejectDiscipleFromList(discipleId: String) = disciple.rejectDiscipleFromList(discipleId)

    fun setAutoRecruitFilter(filter: Set<Int>) = disciple.setAutoRecruitFilter(filter)

    fun setDaoCompanionBannedRootCounts(counts: Set<Int>) {
        viewModelScope.launch {
            gameEngine.updateGameData { it.copy(daoCompanionBannedRootCounts = counts) }
        }
    }

    fun setDaoCompanionConsentRequired(required: Boolean) {
        viewModelScope.launch {
            gameEngine.updateGameData { it.copy(daoCompanionConsentRequired = required) }
        }
    }

    fun setBreakthroughAutoPillSettings(focused: Boolean, rootCounts: Set<Int>) {
        viewModelScope.launch {
            gameEngine.updateGameData { it.copy(breakthroughAutoPillFocused = focused, breakthroughAutoPillRootCounts = rootCounts) }
        }
    }

    fun setAutoEquipSettings(focused: Boolean, rootCounts: Set<Int>) {
        viewModelScope.launch {
            gameEngine.updateGameData { it.copy(autoEquipFromWarehouseFocused = focused, autoEquipFromWarehouseRootCounts = rootCounts) }
        }
    }

    fun setAutoLearnSettings(focused: Boolean, rootCounts: Set<Int>) {
        viewModelScope.launch {
            gameEngine.updateGameData { it.copy(autoLearnFromWarehouseFocused = focused, autoLearnFromWarehouseRootCounts = rootCounts) }
        }
    }

    fun setPatrolBattleResultPopup(enabled: Boolean) {
        viewModelScope.launch {
            gameEngine.updateGameData { it.copy(patrolBattleResultPopup = enabled) }
        }
    }

    fun setActiveTab(tab: String) {
        gameEngine.setActiveTab(tab)
    }

    fun approveMarriage(maleId: String, femaleId: String) {
        viewModelScope.launch {
            gameEngine.approveMarriage(maleId, femaleId)
            gameEngine.clearPendingNotification()
        }
    }

    fun rejectMarriage() {
        gameEngine.clearPendingNotification()
    }

    fun equipItem(discipleId: String, equipmentId: String) = disciple.equipItem(discipleId, equipmentId)

    fun unequipItem(discipleId: String, slot: EquipmentSlot) = disciple.unequipItem(discipleId, slot)

    fun unequipItem(discipleId: String, equipmentId: String) = disciple.unequipItem(discipleId, equipmentId)

    fun forgetManual(discipleId: String, instanceId: String) = disciple.forgetManual(discipleId, instanceId)

    fun replaceManual(discipleId: String, oldInstanceId: String, newStackId: String) = disciple.replaceManual(discipleId, oldInstanceId, newStackId)

    fun learnManual(discipleId: String, stackId: String) = disciple.learnManual(discipleId, stackId)

    fun recallTeam(teamId: String) {
        viewModelScope.launch {
            try {
                gameEngine.recallTeam(teamId)
            } catch (e: Exception) {
                showError(e.message ?: "召回失败")
            }
        }
    }

    fun buyFromMerchant(itemId: String, quantity: Int = 1) = inventory.buyFromMerchant(itemId, quantity)

    fun listItemsToMerchant(items: List<Pair<String, Int>>) = inventory.listItemsToMerchant(items)

    fun removePlayerListedItem(itemId: String) = inventory.removePlayerListedItem(itemId)

    fun recruitDisciple(disciple: DiscipleAggregate) = this@GameViewModel.disciple.recruitDisciple(disciple)

    fun plantSeed(slotIndex: Int, seed: com.xianxia.sect.core.model.Seed) {
        viewModelScope.launch {
            try {
                gameEngine.startManualPlanting(slotIndex, seed.id)
            } catch (e: Exception) {
                showError(e.message ?: "种植失败")
            }
        }
    }

    fun plantOnSpiritField(buildingInstanceId: String, seedId: String, sectId: String) =
        planting.plantOnSpiritField(buildingInstanceId, seedId, sectId)

    fun plantOnSpiritFields(instanceIds: List<String>, seedId: String, sectId: String) =
        planting.plantOnSpiritFields(instanceIds, seedId, sectId)

    fun removePlantFromSpiritField(buildingInstanceId: String) =
        planting.removePlantFromSpiritField(buildingInstanceId)

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

    fun usePill(discipleId: String, pillId: String) = disciple.usePill(discipleId, pillId)

    fun usePill(discipleId: String, pill: Pill) = disciple.usePill(discipleId, pill)

    fun confiscateStorageBagItem(discipleId: String, item: StorageBagItem) = disciple.confiscateStorageBagItem(discipleId, item)

    fun getDiscipleById(id: String): DiscipleAggregate? = disciple.getDiscipleById(id)

    @Suppress("DEPRECATION")
    fun getManualById(id: String): ManualInstance? = inventory.getManualById(id)

    fun getManualInstanceById(id: String): ManualInstance? = inventory.getManualInstanceById(id)

    fun getEquipmentInstanceById(id: String): EquipmentInstance? = inventory.getEquipmentInstanceById(id)

    fun toggleItemLock(itemId: String, itemType: String) = inventory.toggleItemLock(itemId, itemType)

    fun sellItem(itemId: String, itemType: String, quantity: Int) = inventory.sellItem(itemId, itemType, quantity)
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

    fun getEquipmentById(id: String): EquipmentInstance? = inventory.getEquipmentById(id)

    fun getPillById(id: String): Pill? = inventory.getPillById(id)

    fun getMaterialById(id: String): Material? = inventory.getMaterialById(id)

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

    fun openSalaryConfigDialog() = navigation.openSalaryConfigDialog()

    val isGameOver: StateFlow<Boolean> = gameEngine.gameData
        .map { it.isGameOver }
        .distinctUntilChanged()
        .stateIn(viewModelScope, sharingStarted, false)

    fun openGameOverDialog() = navigation.openGameOverDialog()

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

