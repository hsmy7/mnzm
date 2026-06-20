package com.xianxia.sect.ui.game

import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.xianxia.sect.ui.game.building.BuildingDef
import com.xianxia.sect.ui.game.building.BuildingRegistry
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.SectLevel
import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.registry.ForgeRecipeDatabase
import com.xianxia.sect.core.perf.ThermalMonitor
import com.xianxia.sect.core.perf.ThermalState
import com.xianxia.sect.core.engine.*
import com.xianxia.sect.core.engine.domain.disciple.DiscipleFacade
import com.xianxia.sect.core.engine.domain.production.ProductionFacade
import com.xianxia.sect.core.engine.domain.inventory.InventoryFacade
import com.xianxia.sect.core.engine.domain.building.BuildingFacade
import com.xianxia.sect.core.engine.domain.battle.BattleFacade
import com.xianxia.sect.core.engine.domain.diplomacy.DiplomacyFacade
import com.xianxia.sect.core.engine.domain.save.SaveFacade
import com.xianxia.sect.core.engine.service.HighFrequencyData
import com.xianxia.sect.core.engine.service.DailySignInService
import com.xianxia.sect.core.engine.service.ClaimResult
import com.xianxia.sect.core.engine.service.ClaimDailyResult
import com.xianxia.sect.core.engine.system.SystemError
import com.xianxia.sect.core.engine.system.SystemManager
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.BattleResultUIData
import com.xianxia.sect.core.state.GameNotification
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.PendingBeastAttack
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
    private val buildingConfigService: BuildingConfigService,
    private val mailService: com.xianxia.sect.core.engine.service.MailService,
    private val dailySignInService: DailySignInService,
    private val discipleFacade: DiscipleFacade,
    private val productionFacade: ProductionFacade,
    private val inventoryFacade: InventoryFacade,
    private val buildingFacade: BuildingFacade,
    private val battleFacade: BattleFacade,
    private val diplomacyFacade: DiplomacyFacade,
    private val saveFacade: SaveFacade,
    private val thermalMonitor: ThermalMonitor
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

    private val _dialogOpenTrigger = MutableSharedFlow<Unit>(replay = 0)

    fun navigateToDialog(route: DialogRoute) {
        _currentDialogRoute.value = route
        gameEngine.setActiveDialog(route.toString())
        viewModelScope.launch {
            _dialogOpenTrigger.emit(Unit)
        }
    }

    /** 通知引擎有用户交互 — 防止拖动地图等触控操作被误判为空闲 */
    fun onUserInteraction() {
        gameEngine.notifyUserInteraction()
    }

    fun dismissDialog() {
        _currentDialogRoute.value = DialogRoute.None
        gameEngine.setActiveDialog(null)
    }

    fun notifyUserInteraction() {
        gameEngine.notifyUserInteraction()
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

    fun openBloodRefiningPoolDialog(buildingInstanceId: String = "") = navigation.openBloodRefiningPoolDialog(buildingInstanceId.ifEmpty { "" })

    fun openWorldMapDialog() = navigation.openWorldMapDialog()

    fun openRecruitDialog() = navigation.openRecruitDialog()

    fun openMerchantDialog() = navigation.openMerchantDialog()

    fun openDiplomacyDialog() = navigation.openDiplomacyDialog()

    fun attackWorldLevel(levelId: String, discipleIds: List<String?>) = navigation.attackWorldLevel(levelId, discipleIds)

    fun openBattleLogDialog() = navigation.openBattleLogDialog()

    fun dismissBattleResult() = navigation.dismissBattleResult()

    fun resolveBeastAttackPayTribute(beastLevelId: String) {
        gameEngine.resolveBeastAttackPayTribute(beastLevelId)
    }

    fun resolveBeastAttackFight(beastLevelId: String) {
        viewModelScope.launch {
            gameEngine.resolveBeastAttackFight(beastLevelId)
        }
    }

    fun clearPendingBeastAttacks() {
        gameEngine.clearPendingBeastAttacks()
    }

    fun enqueueBattleRewardCards() {
        val cards = gameEngine.pendingBattleRewardCards.value
        if (cards.isNotEmpty()) {
            dailySignInService.enqueueSignInCards(cards)
            gameEngine.clearPendingBattleRewardCards()
        }
    }

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

                @Suppress("DEPRECATION")
                val updatedProductionSlots = if (newProductionSlot != null)
                    data.productionSlots + newProductionSlot else data.productionSlots
                data.copy(
                    spiritStones = data.spiritStones - cost,
                    placedBuildings = data.placedBuildings + newBuilding,
                    spiritFieldPlants = data.spiritFieldPlants + newSpiritFieldPlants,
                    spiritMineSlots = data.spiritMineSlots + newSlots,
                    patrolSlots = if (newPatrolSlots.isNotEmpty()) data.patrolSlots + newPatrolSlots else data.patrolSlots,
                    patrolConfigs = if (newPatrolConfigs.isNotEmpty()) newPatrolConfigs else data.patrolConfigs,
                    residenceSlots = data.residenceSlots + newResidenceSlots,
                    productionSlots = updatedProductionSlots
                )
            }

            if (newProductionSlot != null) {
                buildingFacade.addProductionSlot(newProductionSlot)
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
        viewModelScope.launch { buildingFacade.moveBuildingDirect(instanceId, newGridX, newGridY) }
    }

    fun demolishBuilding(instanceId: String) {
        viewModelScope.launch {
            val snapshot = gameEngine.gameDataSnapshot
            val building = snapshot.placedBuildings.find { it.instanceId == instanceId } ?: return@launch
            val config = buildingConfigService.getBuildingConfigByDisplayName(building.displayName)
            val cost = config?.cost ?: 1000L
            val refund = cost / 2

            gameEngine.removeBuilding(instanceId, refund)
            showSuccess("已拆除${building.displayName}，返还灵石×$refund")
        }
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
    val gameDataUi: StateFlow<GameData> = merge(
        gameEngine.gameData.sample(100),
        _dialogOpenTrigger.map { gameEngine.gameData.value ?: GameData() }
    ).stateIn(viewModelScope, sharingStarted, gameEngine.gameData.value ?: GameData())

    val placedBuildings: StateFlow<List<GridBuildingData>> = gameData
        .map { it.placedBuildings }
        .distinctUntilChanged()
        .stateIn(viewModelScope, sharingStarted, emptyList())

    val elderSlots: StateFlow<ElderSlots?> = gameData
        .map { it.elderSlots }
        .distinctUntilChanged()
        .stateIn(viewModelScope, sharingStarted, null)

    val sectPolicies: StateFlow<SectPolicies> = gameData
        .map { it.sectPolicies }
        .distinctUntilChanged()
        .stateIn(viewModelScope, sharingStarted, SectPolicies())

    val manualProficiencies: StateFlow<Map<String, List<ManualProficiencyData>>> = gameData
        .map { it.manualProficiencies }
        .distinctUntilChanged()
        .stateIn(viewModelScope, sharingStarted, emptyMap())

    val residenceSlots: StateFlow<List<ResidenceSlot>> = gameData
        .map { it.residenceSlots }
        .distinctUntilChanged()
        .stateIn(viewModelScope, sharingStarted, emptyList())

    val highFreqState: StateFlow<GameStateStore.HighFreqState> get() = gameEngine.highFreqState
    val entityState: StateFlow<GameStateStore.EntityState> get() = gameEngine.entityState
    val configState: StateFlow<GameStateStore.ConfigState> get() = gameEngine.configState

    val pendingNotification: StateFlow<GameNotification?> get() = gameEngine.pendingNotification
    val rewardCardQueue: StateFlow<List<RewardCardItem>> get() = gameEngine.rewardCardQueue

    val warehouseFullEvent get() = gameEngine.warehouseFullEvent

    /**
     * 弟子聚合数据 - 用于 UI 层显示（推荐使用）
     *
     * 此属性将底层的 List<Disciple> 自动转换为 List<DiscipleAggregate>，
     * 确保 UI 层统一使用新的多表架构类型。
     */
    val discipleAggregates: StateFlow<List<DiscipleAggregate>> get() = gameEngine.discipleAggregates

    val sectCombatPower: StateFlow<Long> get() = gameEngine.sectCombatPower

    /** 设备热状态 — 供 UI 层自适应分辨率使用 */
    val thermalState: StateFlow<ThermalState> = thermalMonitor.thermalState

    val aiSectCombatPowers: StateFlow<Map<String, Long>> get() = gameEngine.aiSectCombatPowers

    val disciples: StateFlow<List<DiscipleAggregate>> = discipleAggregates

    val aliveDisciples: StateFlow<List<DiscipleAggregate>> = disciples
        .map { it.filter { d -> d.isAlive } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, sharingStarted, emptyList())

    /**
     * 玩家宗门等级 — 只升不降，取自 WorldSect.level。
     * 小型=0（无化神及以上），中型=1（有化神），大型=2（有炼虚/合体），顶级=3（有大乘及以上）
     * 自 v4.0.12 起改为玩家手动升级，月度 tick 不再自动升级。
     */
    val playerSectLevel: StateFlow<Int> = gameData
        .map { data ->
            data.worldMapSects.find { it.isPlayerSect }?.level ?: SectLevel.SMALL
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, sharingStarted, SectLevel.SMALL)

    /** 宗门等级每周奖励是否可领取（驱动红点） */
    val sectLevelRewardClaimable: StateFlow<Boolean> = combine(
        gameData, playerSectLevel
    ) { data, level ->
        val lastClaim = data.sectLevelClaimRecords.find { it.level == level }
        lastClaim == null || (System.currentTimeMillis() - lastClaim.claimedAtEpochMs) >= 7L * 24 * 60 * 60 * 1000
    }.distinctUntilChanged()
        .stateIn(viewModelScope, sharingStarted, false)

    /** 打开宗门等级详情界面 */
    fun navigateToSectLevelDetail() {
        navigateToDialog(DialogRoute.SectLevelDetail)
    }

    /** 领取宗门等级每周奖励（成功时由 RewardCardHost 播放飞出动画，不弹 toast） */
    fun claimSectLevelReward(level: Int) {
        viewModelScope.launch {
            val result = gameEngine.claimSectLevelReward(level)
            when (result) {
                is com.xianxia.sect.core.engine.SectLevelClaimResult.Success -> {
                    // 奖励已通过 GameEngine 入队 rewardCardQueue，
                    // RewardCardHost 在 MainGameScreen 中自动播放飞出动画
                }
                is com.xianxia.sect.core.engine.SectLevelClaimResult.AlreadyClaimed ->
                    showError("本周已领取过该等级奖励")
                is com.xianxia.sect.core.engine.SectLevelClaimResult.Error ->
                    showError(result.message)
            }
        }
    }

    /** 手动升级宗门等级 */
    fun upgradeSectLevel() {
        viewModelScope.launch {
            val result = gameEngine.upgradeSectLevel()
            when (result) {
                is com.xianxia.sect.core.engine.SectLevelUpgradeResult.Success ->
                    showSuccess("宗门晋升至${SectLevel.levelName(result.newLevel)}!")
                is com.xianxia.sect.core.engine.SectLevelUpgradeResult.AlreadyMaxLevel ->
                    showSuccess("已达最高等级")
                is com.xianxia.sect.core.engine.SectLevelUpgradeResult.ConditionsNotMet ->
                    showError("条件未满足: ${result.unmetConditions.joinToString("、")}")
                is com.xianxia.sect.core.engine.SectLevelUpgradeResult.Error ->
                    showError(result.message)
            }
        }
    }

    /**
     * 可招募弟子聚合数据 - 响应式数据流
     *
     * 根据 gameData.recruitList 中的 ID 从全量弟子中筛选出可招募弟子。
     * 当招募列表或弟子数据变化时自动更新，确保 RecruitDialog 显示最新数据。
     */
    val recruitListAggregates: StateFlow<List<DiscipleAggregate>> = gameData
        .map { data -> data.recruitList.map { it.toAggregate() } }
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

    val equipmentInstances: StateFlow<List<EquipmentInstance>> get() = gameEngine.equipmentInstances

    val manualStacks: StateFlow<List<ManualStack>> get() = gameEngine.manualStacks

    val manualInstances: StateFlow<List<ManualInstance>> get() = gameEngine.manualInstances

    val pills: StateFlow<List<Pill>> get() = gameEngine.pills

    val materials: StateFlow<List<Material>> get() = gameEngine.materials

    val herbs: StateFlow<List<Herb>> get() = gameEngine.herbs

    val seeds: StateFlow<List<Seed>> get() = gameEngine.seeds

    val storageBags: StateFlow<List<StorageBag>> get() = gameEngine.storageBags

    val teams: StateFlow<List<ExplorationTeam>> get() = gameEngine.teams

    val battleLogs: StateFlow<List<BattleLog>> get() = gameEngine.battleLogs

    val pendingBattleResult: StateFlow<BattleResultUIData?> get() = gameEngine.pendingBattleResult
    val pendingBattleRewardCards: StateFlow<List<RewardCardItem>> get() = gameEngine.pendingBattleRewardCards
    val pendingBeastAttacks: StateFlow<List<PendingBeastAttack>> get() = gameEngine.pendingBeastAttacks

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
        _detailDisciple.update { it?.copy(disciple = target) }
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
                    successRate = slot.successRate,
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
        discipleFacade.clearPendingNotification()
    }

    fun clearRewardCardQueue(count: Int = Int.MAX_VALUE) {
        gameEngine.clearRewardCardQueue(count)
    }

    fun enterSect(sectId: String) {
        viewModelScope.launch { gameEngine.enterSect(sectId) }
    }

    fun expelTheftDisciple(discipleId: String) = disciple.expelTheftDisciple(discipleId)

    fun imprisonTheftDisciple(discipleId: String, currentYear: Int) = viewModelScope.launch { disciple.imprisonTheftDisciple(discipleId, currentYear) }

    suspend fun releaseTheftDisciple(discipleId: String): Int = disciple.releaseTheftDisciple(discipleId)

    fun onLoyaltyDialogDismissed() = disciple.onLoyaltyDialogDismissed()

    fun toggleFollowDisciple(discipleId: String) = disciple.toggleFollowDisciple(discipleId)

    fun applyAdBreakthroughBonus(discipleId: String, bonus: Double) = disciple.applyAdBreakthroughBonus(discipleId, bonus)

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

    fun setAutoAssignSettings(
        mineFocused: Boolean, mineRootCounts: List<Int>, mineThreshold: Int,
        plantFocused: Boolean, plantRootCounts: List<Int>, plantThreshold: Int,
        alchemyFocused: Boolean, alchemyRootCounts: List<Int>, alchemyThreshold: Int,
        forgeFocused: Boolean, forgeRootCounts: List<Int>, forgeThreshold: Int
    ) {
        viewModelScope.launch {
            gameEngine.updateGameData { it.copy(sectPolicies = it.sectPolicies.copy(
                autoMineFocused = mineFocused,
                autoMineRootCounts = mineRootCounts,
                autoMineThreshold = mineThreshold,
                autoPlantFocused = plantFocused,
                autoPlantRootCounts = plantRootCounts,
                autoPlantThreshold = plantThreshold,
                autoAlchemyFocused = alchemyFocused,
                autoAlchemyRootCounts = alchemyRootCounts,
                autoAlchemyThreshold = alchemyThreshold,
                autoForgeFocused = forgeFocused,
                autoForgeRootCounts = forgeRootCounts,
                autoForgeThreshold = forgeThreshold
            )) }
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
            discipleFacade.approveMarriage(maleId, femaleId)
            discipleFacade.clearPendingNotification()
        }
    }

    fun rejectMarriage() {
        discipleFacade.clearPendingNotification()
    }

    fun equipItem(discipleId: String, equipmentId: String) = disciple.equipItem(discipleId, equipmentId)

    fun unequipItem(discipleId: String, slot: EquipmentSlot) = disciple.unequipItem(discipleId, slot)

    fun unequipItem(discipleId: String, equipmentId: String) = disciple.unequipItem(discipleId, equipmentId)

    fun forgetManual(discipleId: String, instanceId: String) = disciple.forgetManual(discipleId, instanceId)

    fun replaceManual(discipleId: String, oldInstanceId: String, newStackId: String) = disciple.replaceManual(discipleId, oldInstanceId, newStackId)

    fun learnManual(discipleId: String, stackId: String) = disciple.learnManual(discipleId, stackId)

    fun buyFromMerchant(itemId: String, quantity: Int = 1) = inventory.buyFromMerchant(itemId, quantity)

    fun listItemsToMerchant(items: List<Pair<String, Int>>) = inventory.listItemsToMerchant(items)

    fun removePlayerListedItem(itemId: String) = inventory.removePlayerListedItem(itemId)

    private var pendingBagCards: List<RewardCardItem> = emptyList()

    private val _bagRewardCards = MutableStateFlow<List<RewardCardItem>>(emptyList())
    val bagRewardCards: StateFlow<List<RewardCardItem>> = _bagRewardCards.asStateFlow()

    suspend fun openStorageBag(bagId: String): List<BattleRewardItem> {
        val (rewards, cards) = gameEngine.openStorageBag(bagId)
        pendingBagCards = cards
        _bagRewardCards.value = cards
        return rewards
    }

    suspend fun openAllStorageBags(bagId: String): List<BattleRewardItem> {
        val bag = storageBags.value.find { it.id == bagId } ?: return emptyList()
        val totalQty = bag.quantity
        val allRewards = mutableListOf<BattleRewardItem>()
        val allCards = mutableListOf<RewardCardItem>()
        repeat(totalQty) {
            val (rewards, cards) = gameEngine.openStorageBag(bagId)
            allRewards.addAll(rewards)
            allCards.addAll(cards)
        }
        pendingBagCards = allCards
        _bagRewardCards.value = allCards
        return allRewards
    }

    fun enqueueBagRewardCards() {
        if (pendingBagCards.isNotEmpty()) {
            dailySignInService.enqueueSignInCards(pendingBagCards)
            pendingBagCards = emptyList()
            _bagRewardCards.value = emptyList()
        }
    }

    fun recruitDisciple(disciple: DiscipleAggregate) = this@GameViewModel.disciple.recruitDisciple(disciple)

    fun plantSeed(slotIndex: Int, seed: com.xianxia.sect.core.model.Seed) {
        viewModelScope.launch {
            try {
                buildingFacade.startManualPlanting(slotIndex, seed.id)
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

    fun setYearlySalary(realm: Int, amount: Int) {
        viewModelScope.launch {
            val data = gameEngine.gameData.value
            val newSalary = data.yearlySalary.toMutableMap()
            newSalary[realm] = amount
            gameEngine.updateYearlySalary(newSalary)
        }
    }

    fun setYearlySalaryEnabled(realm: Int, enabled: Boolean) {
        viewModelScope.launch {
            discipleFacade.updateYearlySalaryEnabled(realm, enabled)
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

    fun sellToMerchant(itemId: String, quantity: Int) = inventory.sellToMerchant(itemId, quantity)

    fun sellItem(itemId: String, itemType: String, quantity: Int) = inventory.sellItem(itemId, itemType, quantity)

    // ── 自动购买 ────────────────────────────────────────────────────

    fun addAutoBuyEntries(entries: List<AutoBuyEntry>) = inventory.addAutoBuyEntries(entries)

    fun removeAutoBuyEntries(entries: List<AutoBuyEntry>) = inventory.removeAutoBuyEntries(entries)

    fun getAllAutoBuyableItems(): List<AutoBuyCatalogItem> = inventory.getAllAutoBuyableItems()

    fun consumeBloodRefiningMaterial(name: String, rarity: Int, quantity: Int) {
        viewModelScope.launch {
            gameEngine.consumeMaterialByName(name, rarity, quantity)
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

    fun getEquipmentById(id: String): EquipmentInstance? = inventory.getEquipmentById(id)

    fun getPillById(id: String): Pill? = inventory.getPillById(id)

    fun getMaterialById(id: String): Material? = inventory.getMaterialById(id)

    fun startMission(mission: com.xianxia.sect.core.model.Mission, selectedDisciples: List<DiscipleAggregate>) {
        viewModelScope.launch {
            try {
                // Phase3: planned feature
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

    private val currentSlotId: Int get() = gameEngine.gameData.value?.slotId ?: 0

    val mails: StateFlow<List<MailEntity>> get() = mailService.activeMails

    val mailUnreadCount: StateFlow<Int> get() = mailService.unreadCount

    fun markMailAsRead(mailId: String) {
        viewModelScope.launch {
            mailService.markAsRead(mailId)
        }
    }

    private val _mailRewardCards = MutableStateFlow<List<RewardCardItem>>(emptyList())
    val mailRewardCards: StateFlow<List<RewardCardItem>> = _mailRewardCards.asStateFlow()
    private val mailCardQueueMutex = Mutex()

    fun claimMailAttachment(mailId: String, onResult: (com.xianxia.sect.core.engine.service.ClaimResult) -> Unit = {}) {
        viewModelScope.launch {
            val result = mailService.claimAttachment(mailId, currentSlotId)
            if (result is ClaimResult.Success && result.cards.isNotEmpty()) {
                _mailRewardCards.value = result.cards
            }
            onResult(result)
        }
    }

    fun markAllMailsAsRead() {
        viewModelScope.launch {
            val result = mailService.markAllAsRead(currentSlotId)
            if (result.cards.isNotEmpty()) {
                _mailRewardCards.value = result.cards
            }
            if (result.skippedCount > 0) {
                showError(result.skipReasons.first())
            }
        }
    }

    fun enqueueMailRewardCards() {
        viewModelScope.launch {
            mailCardQueueMutex.withLock {
                val cards = _mailRewardCards.value
                if (cards.isNotEmpty()) {
                    dailySignInService.enqueueSignInCards(cards)
                    _mailRewardCards.value = emptyList()
                }
            }
        }
    }

    fun deleteAllReadAndClaimedMails() {
        viewModelScope.launch {
            mailService.deleteAllReadAndClaimed(currentSlotId)
        }
    }

    // region DailySignIn

    val signInState: StateFlow<SignInState> = gameEngine.gameData
        .map { it.signInState }
        .distinctUntilChanged()
        .stateIn(viewModelScope, sharingStarted, SignInState())

    val canClaimToday: StateFlow<Boolean> = signInState
        .map { state ->
            val calendar = java.util.Calendar.getInstance()
            val today = calendar.get(java.util.Calendar.DAY_OF_MONTH)
            today !in state.claimedDays
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, sharingStarted, true)

    /** 天道试炼任意关卡有可领取的通关奖励 */
    val heavenlyTrialClaimable: StateFlow<Boolean> = gameEngine.gameData
        .map { data ->
            val s = data.heavenlyTrialState
            (0 until 8).any { s.canClaimReward(it) }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, sharingStarted, false)

    /** 任一活动有可领取项（签到或试炼），驱动主界面"活动"按钮红点 */
    val anyActivityClaimable: StateFlow<Boolean> =
        kotlinx.coroutines.flow.combine(
            canClaimToday, heavenlyTrialClaimable
        ) { signIn, trial -> signIn || trial }
            .distinctUntilChanged()
            .stateIn(viewModelScope, sharingStarted, false)

    val claimedDaysCount: StateFlow<Int> = signInState
        .map { it.claimedDays.size }
        .distinctUntilChanged()
        .stateIn(viewModelScope, sharingStarted, 0)

    val claimedMilestones: StateFlow<List<Int>> = signInState
        .map { it.claimedMilestones }
        .distinctUntilChanged()
        .stateIn(viewModelScope, sharingStarted, emptyList())

    val milestoneRewards: List<MilestoneReward> = dailySignInService.getMilestoneRewards()

    fun getRewardForWeekday(weekday: Int): DailySignInReward = dailySignInService.getRewardForWeekday(weekday)

    fun getDayState(dayOfMonth: Int, signInState: SignInState): SignInDayState = dailySignInService.getDayState(dayOfMonth, signInState)

    fun getDaysInMonth(): Int = dailySignInService.getDaysInMonth()

    fun getWeekdayForDay(dayOfMonth: Int): Int = dailySignInService.getWeekdayForDay(dayOfMonth)

    private val _signInCapacityWarning = MutableStateFlow<String?>(null)
    val signInCapacityWarning: StateFlow<String?> = _signInCapacityWarning.asStateFlow()

    fun dismissCapacityWarning() {
        _signInCapacityWarning.value = null
    }

    fun claimDailySignIn() {
        viewModelScope.launch {
            val result = dailySignInService.claimDailySignIn()
            when (result) {
                is ClaimDailyResult.Success -> {
                    dailySignInService.enqueueSignInCards(result.cards)
                }
                is ClaimDailyResult.SuccessWithMilestones -> {
                    dailySignInService.enqueueSignInCards(result.cards)
                }
                is ClaimDailyResult.AlreadyClaimed -> {
                    // 已签到，无需提示
                }
                is ClaimDailyResult.CapacityInsufficient -> {
                    _signInCapacityWarning.value = result.message
                }
            }
        }
    }

    /** 将奖励卡片推入队列，触发屏幕中央动效（试炼通关等外部调用） */
    fun enqueueRewardCards(cards: List<RewardCardItem>) {
        dailySignInService.enqueueSignInCards(cards)
    }

    // endregion

    // region Residence

    fun assignToResidence(buildingInstanceId: String, slotIndex: Int, discipleId: String) {
        viewModelScope.launch {
            val discipleName = gameEngine.getDiscipleAggregate(discipleId)?.name ?: ""
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
                val canAfford = data.spiritStones >= 50000L
                if (!canAfford) return@updateGameData data
                data.copy(
                    spiritStones = data.spiritStones - 50000L,
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

