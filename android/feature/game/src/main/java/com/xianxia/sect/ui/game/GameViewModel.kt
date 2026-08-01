package com.xianxia.sect.ui.game

import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import com.xianxia.sect.core.domain.dialog.DialogManager
import com.xianxia.sect.core.domain.dialog.DialogType
import com.xianxia.sect.core.SectLevel
import com.xianxia.sect.core.config.BuildingConfigService
import com.xianxia.sect.core.engine.*
import com.xianxia.sect.core.engine.di.IoDispatcher
import com.xianxia.sect.core.engine.domain.building.BuildingFacade
import com.xianxia.sect.core.engine.domain.battle.BattleFacade
import com.xianxia.sect.core.engine.domain.diplomacy.DiplomacyFacade
import com.xianxia.sect.core.engine.domain.disciple.DiscipleFacade
import com.xianxia.sect.core.engine.domain.inventory.InventoryFacade
import com.xianxia.sect.core.engine.domain.production.ProductionFacade
import com.xianxia.sect.core.engine.domain.save.SaveFacade
import com.xianxia.sect.core.engine.service.AdService
import com.xianxia.sect.core.engine.service.AdPurpose
import com.xianxia.sect.core.engine.service.MailService
import com.xianxia.sect.core.engine.service.DailySignInService
import com.xianxia.sect.core.engine.service.ClaimResult
import com.xianxia.sect.core.engine.service.HighFrequencyData
import com.xianxia.sect.ui.game.buildBuildingDataArray
import com.xianxia.sect.ui.game.sect.RenderCommandBus
import com.xianxia.sect.core.util.GridSnapHelper
import com.xianxia.sect.core.engine.system.SystemManager
import com.xianxia.sect.core.audio.AudioConfig
import com.xianxia.sect.core.audio.AudioEngine
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import com.xianxia.sect.core.perf.ThermalMonitor
import com.xianxia.sect.core.perf.ThermalState
import com.xianxia.sect.core.registry.ForgeRecipeDatabase
import com.xianxia.sect.core.state.BattleResultUIData
import com.xianxia.sect.core.state.GameNotification
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.PendingBeastAttack
import com.xianxia.sect.core.state.PendingMarriageProposal
import com.xianxia.sect.ui.game.delegate.*
import com.xianxia.sect.ui.navigation.GameRoute
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class GameViewModel @Inject constructor(
    private val gameEngine: GameEngine,
    private val gameEngineCore: GameEngineCore,
    @ApplicationContext private val appContext: Context,
    private val systemManager: SystemManager,
    private val buildingConfigService: BuildingConfigService,
    private val mailService: MailService,
    private val dailySignInService: DailySignInService,
    private val discipleFacade: DiscipleFacade,
    private val productionFacade: ProductionFacade,
    private val inventoryFacade: InventoryFacade,
    private val buildingFacade: BuildingFacade,
    private val battleFacade: BattleFacade,
    private val diplomacyFacade: DiplomacyFacade,
    private val saveFacade: SaveFacade,
    private val thermalMonitor: ThermalMonitor,
    private val dialogManager: DialogManager,
    private val adService: AdService,
    private val audioConfig: AudioConfig,
    private val audioEngine: AudioEngine,
    private val ioDispatcher: IoDispatcher
) : BaseViewModel() {

    // ── 新提取的领域委托 ──

    val ads = AdsDelegate()
    val overlays = OverlayDelegate(gameEngine, viewModelScope)
    val bag = BagDelegate(gameEngine, dailySignInService, viewModelScope, dispatcher = ioDispatcher.dispatcher)
    val redeem = RedeemCodeDelegate(
        gameEngine, ::showSuccess, ::showError,
        onCapacityWarning = { msg -> showCapacityWarning(msg) }
    )
    val mail = MailDelegate(gameEngine, mailService, dailySignInService, ::showError)
    val signIn = SignInDelegate(gameEngine, dailySignInService, viewModelScope, sharingStarted) { showCapacityWarning(it) }
    val gameLoop = GameLoopDelegate(gameEngine, gameEngineCore, systemManager, viewModelScope, ::showError)
    val settings = SettingsDelegate(gameEngine, discipleFacade, audioConfig)

    // ── 既有领域委托 ──

    val planting = PlantingDelegate(gameEngine)
    val disciple = DiscipleDelegate(gameEngine, dispatcher = ioDispatcher.dispatcher)
    val navigation = NavigationDelegate(
        gameEngine, gameEngineCore,
        onNavigate = { _navigationEvents.trySend(it) }
    )
    val inventory = InventoryDelegate(gameEngine)
    val beastAttack = BeastAttackDelegate(
        gameEngine, viewModelScope, dispatcher = ioDispatcher.dispatcher,
        onMessage = { message, isError ->
            if (isError) showError(message) else showSuccess(message)
        }
    )
    val warnings = WarningDelegate(gameEngine, viewModelScope)
    val buildingDelegate = BuildingDelegate(
        gameEngine, buildingFacade, buildingConfigService, dispatcher = ioDispatcher.dispatcher,
        onDemolishSuccess = { msg -> showSuccess(msg) }
    )
    val sectDelegate = SectDelegate(
        gameEngine,
        onShowSuccess = { msg -> showSuccess(msg) },
        onShowError = { msg -> showError(msg) },
        onCapacityWarning = { msg -> showCapacityWarning(msg) },
        onNavigateToDialog = { route -> navigateToDialog(route) },
        onDismissDialog = { dismissDialog() }
    )
    val autoAssign = AutoAssignDelegate(gameEngine)
    val guide = GuideDelegate()

    // 引导任务已领取奖励的ID集合
    val guideClaimedRewardIds: StateFlow<Set<Int>> = gameData
        .map { it.guideClaimedRewardIds }
        .distinctUntilChanged()
        .stateIn(viewModelScope, sharingStarted, gameData.value.guideClaimedRewardIds)

    fun claimGuideReward(taskId: Int) {
        gameEngine.launchOnEngine {
            gameEngine.claimGuideReward(taskId)
        }
    }

    companion object {
        private const val TAG = "GameViewModel"
        /** 观看单次广告获得的突破修炼倍率加成 */
        private const val AD_BONUS_PER_AD = 0.05
    }

    // ── Dialog 状态管理 ──

    private val _navigationEvents = Channel<GameRoute>(Channel.BUFFERED)
    val navigationEvents: Flow<GameRoute> = _navigationEvents.receiveAsFlow()

    private val _dialogOpenTrigger = MutableSharedFlow<Unit>(replay = 0)

    val currentDialogType: StateFlow<DialogType> = dialogManager.currentDialog
        .map { entry -> entry?.type ?: DialogType.None }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DialogType.None)

    fun navigateToDialog(type: DialogType) {
        if (type is DialogType.None) return
        gameEngine.setActiveDialog(type.domainKey)
        dialogManager.open(type)
        _dialogOpenTrigger.tryEmit(Unit)
    }

    fun onUserInteraction() { gameEngine.notifyUserInteraction() }

    val renderFrameRate: StateFlow<Int> = gameEngineCore.renderFrameRate

    // ── 渲染命令总线（建筑数据直达推送，绕过 Compose 帧率门控） ──

    private val _renderCommandBus = RenderCommandBus()

    /** 获取渲染命令总线实例（由 MainGameScreen 注入到 NativeSurfaceView） */
    fun getRenderCommandBus(): RenderCommandBus = _renderCommandBus

    /** 建筑精灵尺寸缓存（运行时不变，供直达推送协程使用） */
    private val _buildingSpriteSizesCache: Map<String, GridSnapHelper.BuildingSize> by lazy {
        buildingDelegate.getAllBuildingSpriteSizes().mapValues {
            GridSnapHelper.BuildingSize(it.value.first, it.value.second)
        }
    }

    fun setGameScene(scene: GameEngineCore.GameScene) { gameEngineCore.onSceneChanged(scene) }

    init {
        // 建筑数据直达推送（绕过 Compose 反应式管线 + 帧率门控）
        viewModelScope.launch {
            gameEngine.gameData
                .map { it.placedBuildings }
                .distinctUntilChanged()
                .collect { buildings ->
                    val dataArray = if (buildings.isNotEmpty()) {
                        buildBuildingDataArray(buildings, _buildingSpriteSizesCache)
                    } else null
                    _renderCommandBus.postBuildingData(dataArray, buildings.size)
                }
        }
    }

    fun dismissDialog() {
        gameEngine.setActiveDialog(null)
        dialogManager.close()
        _dialogOpenTrigger.tryEmit(Unit)
    }

    fun activateSubDialogDomain(domainName: String) { gameEngine.pushSubDialogDomain(domainName) }

    fun deactivateSubDialogDomain(domainName: String) { gameEngine.popSubDialogDomain(domainName) }

    fun notifyUserInteraction() { gameEngine.notifyUserInteraction() }

    // ── Navigation 快捷方法 ──

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

    // ── BeastAttack / Warning ──

    suspend fun resolveBeastAttackPayTribute(beastLevelId: String) = beastAttack.resolveBeastAttackPayTribute(beastLevelId)
    suspend fun resolveBeastAttackFight(beastLevelId: String) = beastAttack.resolveBeastAttackFight(beastLevelId)
    fun clearPendingBeastAttacks() = beastAttack.clearPendingBeastAttacks()
    fun removePendingBeastAttack(beastLevelId: String) = beastAttack.removePendingBeastAttack(beastLevelId)

    // ── 婚姻提议审批 ─────────────────────────────────────────

    /**
     * 批准婚姻提议：通知引擎执行配对并移除待处理提议。
     */
    fun approveMarriage(maleId: String, femaleId: String) {
        gameEngine.launchOnEngine { gameEngine.approveMarriageProposal(maleId, femaleId) }
    }

    /**
     * 拒绝婚姻提议：通知引擎移除待处理提议，不执行配对。
     */
    fun rejectMarriage(maleId: String, femaleId: String) {
        gameEngine.launchOnEngine { gameEngine.rejectMarriageProposal(maleId, femaleId) }
    }

    // ── Beast View Lock（妖兽弹窗锁定） ───────────────────────

    /** 锁定妖兽：打开详情弹窗时调用，月度结算跳过 AI 攻击 */
    fun lockBeast(beastId: String) {
        gameEngine.launchOnEngine { gameEngine.lockBeastView(beastId) }
    }
    /** 解锁妖兽：关闭详情弹窗时调用，AI 可正常进攻 */
    fun unlockBeast(beastId: String) {
        if (beastId.isEmpty()) return
        gameEngine.launchOnEngine { gameEngine.unlockBeastView(beastId) }
    }

    val attackWarnings: StateFlow<List<AttackWarning>> get() = warnings.attackWarnings
    val shownWarningStageIds: StateFlow<List<String>> get() = warnings.shownWarningStageIds
    fun resolveAttackWarningAppease(sectId: String) = warnings.resolveAttackWarningAppease(sectId)
    fun resolveAttackWarningVassal(sectId: String) = warnings.resolveAttackWarningVassal(sectId)
    fun markWarningStageShown(stageKey: String) = warnings.markWarningStageShown(stageKey)

    fun enqueueBattleRewardCards() {
        gameEngine.launchOnEngine {
            val cards = gameEngine.pendingBattleRewardCards.value
            if (cards.isNotEmpty()) {
                dailySignInService.enqueueSignInCards(cards)
                gameEngine.clearPendingBattleRewardCards()
            }
        }
    }

    // ── Building Delegate ──

    fun placeBuilding(name: String, gridX: Int, gridY: Int, width: Int = 2, height: Int = 3) =
        buildingDelegate.placeBuilding(name, gridX, gridY, width, height)
    fun getBuildingCost(displayName: String): Long = buildingDelegate.getBuildingCost(displayName)
    fun getBuildingGridSize(displayName: String): Pair<Int, Int> = buildingDelegate.getBuildingGridSize(displayName)
    fun getBuildingSpriteSize(displayName: String): Pair<Int, Int> = buildingDelegate.getBuildingSpriteSize(displayName)
    fun getAllBuildingSpriteSizes(): Map<String, Pair<Int, Int>> = buildingDelegate.getAllBuildingSpriteSizes()
    fun batchPlaceBuilding(goldFingerState: com.xianxia.sect.ui.game.sect.GoldFingerState) =
        buildingDelegate.batchPlaceBuilding(goldFingerState)
    suspend fun moveBuilding(instanceId: String, newGridX: Int, newGridY: Int) =
        buildingDelegate.moveBuilding(instanceId, newGridX, newGridY)
    fun demolishBuilding(instanceId: String) = buildingDelegate.demolishBuilding(instanceId)
    fun fixupBuildingSizesIfNeeded() = buildingDelegate.fixupBuildingSizesIfNeeded()

    // ── 核心状态流 ──

    val gameData: StateFlow<GameData> get() = gameEngine.gameData

    val gameDataUi: StateFlow<GameData> = merge(
        gameEngine.gameData,
        _dialogOpenTrigger.map { gameEngine.gameData.value }
    ).distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, sharingStarted, gameEngine.gameData.value)

    val placedBuildings: StateFlow<List<GridBuildingData>> = gameData
        .map { it.placedBuildings }.distinctUntilChanged()
        .stateIn(viewModelScope, sharingStarted, emptyList())

    val elderSlots: StateFlow<ElderSlots?> = gameData
        .map { it.elderSlots }.distinctUntilChanged()
        .stateIn(viewModelScope, sharingStarted, null)

    val sectPolicies: StateFlow<SectPolicies> = gameData
        .map { it.sectPolicies }.distinctUntilChanged()
        .stateIn(viewModelScope, sharingStarted, SectPolicies())

    val manualProficiencies: StateFlow<Map<String, List<ManualProficiencyData>>> = gameData
        .map { it.manualProficiencies }.distinctUntilChanged()
        .stateIn(viewModelScope, sharingStarted, emptyMap())

    val residenceSlots: StateFlow<List<ResidenceSlot>> = gameData
        .map { it.residenceSlots }.distinctUntilChanged()
        .stateIn(viewModelScope, sharingStarted, emptyList())

    val highFreqState: StateFlow<GameStateStore.HighFreqState> get() = gameEngine.highFreqState
    val entityState: StateFlow<GameStateStore.EntityState> get() = gameEngine.entityState
    val configState: StateFlow<GameStateStore.ConfigState> get() = gameEngine.configState

    @Immutable
    data class GameScreenAggState(
        val gameData: GameData,
        val highFreq: GameStateStore.HighFreqState,
        val config: GameStateStore.ConfigState,
        val isPaused: Boolean
    )
    val gameScreenState: StateFlow<GameScreenAggState> = combine(
        gameEngine.gameData, highFreqState, configState, gameEngineCore.state.map { it.isPaused }
    ) { gd, hf, cfg, paused -> GameScreenAggState(gd, hf, cfg, paused) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000),
            GameScreenAggState(GameData(), GameStateStore.HighFreqState(), GameStateStore.ConfigState(), true))

    val pendingNotification: StateFlow<GameNotification?> get() = gameEngine.pendingNotification
    val notifications: StateFlow<List<GameNotification>> get() = gameEngine.notifications
    val rewardCardQueue: StateFlow<List<RewardCardItem>> get() = gameEngine.rewardCardQueue
    val warehouseFullEvent get() = gameEngine.warehouseFullEvent
    val discipleAggregates: StateFlow<List<DiscipleAggregate>> get() = gameEngine.discipleAggregates
    val sectCombatPower: StateFlow<Long> get() = gameEngine.sectCombatPower
    val thermalState: StateFlow<ThermalState> = thermalMonitor.thermalState
    val aiSectCombatPowers: StateFlow<Map<String, Long>> get() = gameEngine.aiSectCombatPowers
    val disciples: StateFlow<List<DiscipleAggregate>> = discipleAggregates

    val aliveDisciples: StateFlow<List<DiscipleAggregate>> = disciples
        .map { it.filter { d -> d.isAlive } }.distinctUntilChanged()
        .stateIn(viewModelScope, sharingStarted, emptyList())

    val playerSectLevel: StateFlow<Int> = gameData
        .map { data -> data.worldMapSects.find { it.isPlayerSect }?.level ?: SectLevel.SMALL }
        .distinctUntilChanged()
        .stateIn(viewModelScope, sharingStarted, SectLevel.SMALL)

    val sectLevelRewardClaimable: StateFlow<Boolean> = combine(gameData, playerSectLevel) { data, level ->
        val lastClaim = data.sectLevelClaimRecords.find { it.level == level }
        lastClaim == null || (System.currentTimeMillis() - lastClaim.claimedAtEpochMs) >= 7L * 24 * 60 * 60 * 1000
    }.distinctUntilChanged()
        .stateIn(viewModelScope, sharingStarted, false)

    fun navigateToSectLevelDetail() = sectDelegate.navigateToSectLevelDetail()
    fun renameSect(newName: String) = sectDelegate.renameSect(newName)
    fun claimSectLevelReward(level: Int) = sectDelegate.claimSectLevelReward(level)
    fun upgradeSectLevel() = sectDelegate.upgradeSectLevel()

    val recruitListAggregates: StateFlow<List<DiscipleAggregate>> = gameData
        // 按 id 去重兜底（引擎/数据层已保证不变量，防 LazyVerticalGrid 重复 key 异常）
        .map { data -> data.recruitList.distinctBy { it.id }.map { it.toAggregate() } }
        .stateIn(viewModelScope, sharingStarted, emptyList())

    val equipmentStacks: StateFlow<List<EquipmentStack>> = combine(
        gameEngine.equipmentStacks, gameEngine.disciples
    ) { stacks, disciples ->
        val bagStackIds = disciples.filter { it.isAlive }
            .flatMap { it.equipment.storageBagItems }
            .filter { it.itemType == "equipment_stack" }.map { it.itemId }.toSet()
        stacks.filter { it.id !in bagStackIds }
    }.stateIn(viewModelScope, sharingStarted, emptyList())

    val equipmentInstances: StateFlow<List<EquipmentInstance>> get() = gameEngine.equipmentInstances
    val manualStacks: StateFlow<List<ManualStack>> = combine(
        gameEngine.manualStacks, gameEngine.disciples
    ) { stacks, disciples ->
        val bagStackIds = disciples.filter { it.isAlive }
            .flatMap { it.equipment.storageBagItems }
            .filter { it.itemType == "manual_stack" }
            .map { it.itemId }.toSet()
        stacks.filter { it.id !in bagStackIds }
    }.stateIn(viewModelScope, sharingStarted, emptyList())
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
    val pendingMarriageProposals: StateFlow<List<PendingMarriageProposal>> get() = gameEngine.pendingMarriageProposals

    val alliances: StateFlow<List<Alliance>> = gameEngine.gameData
        .map { it.alliances }.stateIn(viewModelScope, sharingStarted, emptyList())

    val productionSlots: StateFlow<List<ProductionSlot>> get() = gameEngine.productionSlots
    val worldMapRenderData: StateFlow<WorldMapRenderData> get() = gameEngine.worldMapRenderData

    val alchemySlots: StateFlow<List<AlchemySlot>> = productionSlots.map { slots ->
        slots.filter { it.buildingType == BuildingType.ALCHEMY }.map { slot ->
            AlchemySlot(
                id = slot.id, slotIndex = slot.slotIndex,
                recipeId = slot.recipeId, recipeName = slot.recipeName,
                pillName = slot.outputItemName, pillRarity = slot.outputItemRarity,
                startYear = slot.startYear, startMonth = slot.startMonth, duration = slot.duration,
                status = when (slot.status) {
                    ProductionSlotStatus.IDLE -> AlchemySlotStatus.IDLE
                    ProductionSlotStatus.WORKING -> AlchemySlotStatus.WORKING
                    ProductionSlotStatus.COMPLETED -> AlchemySlotStatus.FINISHED
                },
                successRate = slot.successRate, requiredMaterials = slot.requiredMaterials,
                assignedDiscipleId = slot.assignedDiscipleId, assignedDiscipleName = slot.assignedDiscipleName,
                autoRestartEnabled = slot.autoRestartEnabled
            )
        }
    }.stateIn(viewModelScope, sharingStarted, emptyList())

    val highFrequencyData: StateFlow<HighFrequencyData> get() = gameEngine.highFrequencyData
    val realtimeCultivation: StateFlow<Map<String, Double>> get() = gameEngine.realtimeCultivation

    private val _selectedBuildingId = MutableStateFlow<String?>(null)
    val selectedBuildingId: StateFlow<String?> = _selectedBuildingId.asStateFlow()

    private val _selectedPlantSlotIndex = MutableStateFlow<Int?>(null)
    val selectedPlantSlotIndex: StateFlow<Int?> = _selectedPlantSlotIndex.asStateFlow()

    val forgeSlots: StateFlow<List<ForgeSlot>> = productionSlots.map { slots ->
        slots.filter { it.buildingType == BuildingType.FORGE }.map { slot ->
            val recipe = slot.recipeId?.let { ForgeRecipeDatabase.getRecipeById(it) }
            ForgeSlot(
                id = slot.id, slotIndex = slot.slotIndex,
                recipeId = slot.recipeId, recipeName = slot.recipeName,
                equipmentName = recipe?.name ?: "", equipmentRarity = recipe?.rarity ?: 1,
                startYear = slot.startYear, startMonth = slot.startMonth, duration = slot.duration,
                status = when (slot.status) {
                    ProductionSlotStatus.WORKING -> ForgeSlotStatus.WORKING
                    ProductionSlotStatus.COMPLETED -> ForgeSlotStatus.FINISHED
                    else -> ForgeSlotStatus.IDLE
                },
                successRate = slot.successRate,
                assignedDiscipleId = slot.assignedDiscipleId, assignedDiscipleName = slot.assignedDiscipleName,
                autoRestartEnabled = slot.autoRestartEnabled
            )
        }
    }.stateIn(viewModelScope, sharingStarted, emptyList())

    val allForgeRecipes: StateFlow<List<ForgeRecipeDatabase.ForgeRecipe>> = flow {
        emit(ForgeRecipeDatabase.getAllRecipes())
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val isPaused: StateFlow<Boolean> = gameEngineCore.state
        .map { it.isPaused }.stateIn(viewModelScope, SharingStarted.Lazily, true)

    val gameEventRecords: StateFlow<List<GameEventRecord>> = gameEngine.gameData
        .map { it.gameEventRecords }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val yearlyReports: StateFlow<List<YearlyReport>> = gameEngine.gameData
        .map { it.yearlyReports }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── 建筑/弟子详情 ──

    fun openBuildingDetailDialog(buildingId: String) { _selectedBuildingId.value = buildingId }

    fun assignDiscipleToBuilding(buildingId: String, slotIndex: Int, discipleId: String) =
        disciple.assignDiscipleToBuilding(buildingId, slotIndex, discipleId)

    // ── Disciple Delegate 快捷方法 ──

    fun recruitDiscipleFromList(discipleId: String) = disciple.recruitDiscipleFromList(discipleId)
    fun expelDisciple(discipleId: String) = disciple.expelDisciple(discipleId)
    fun releaseReflectionDisciple(discipleId: String) = disciple.releaseReflectionDisciple(discipleId)
    fun apprenticeToMaster(discipleId: String, masterId: String) = disciple.apprenticeToMaster(discipleId, masterId)
    fun renameDisciple(discipleId: String, newName: String) = disciple.renameDisciple(discipleId, newName)
    fun getLifeEvents(discipleId: String): List<String> = discipleFacade.getLifeEvents(discipleId)
    fun initializeLifeEvents(discipleId: String) = discipleFacade.initializeLifeEvents(discipleId)

    fun clearNotification() {
        gameEngine.consumeNotification()
        discipleFacade.clearPendingNotification()
    }

    fun clearRewardCardQueue(count: Int = Int.MAX_VALUE) { gameEngine.clearRewardCardQueue(count) }

    fun enterSect(sectId: String) { gameEngine.launchOnEngine { gameEngine.enterSect(sectId) } }

    suspend fun releaseTheftDisciple(discipleId: String): Int = disciple.releaseTheftDisciple(discipleId)
    fun toggleFollowDisciple(discipleId: String) = disciple.toggleFollowDisciple(discipleId)
    fun applyAdBreakthroughBonus(discipleId: String, bonus: Double) = disciple.applyAdBreakthroughBonus(discipleId, bonus)
    fun changeDiscipleType(discipleId: String, newType: String) = disciple.changeDiscipleType(discipleId, newType)
    fun toggleAutoEquipFromWarehouse(discipleId: String, enabled: Boolean) = disciple.toggleAutoEquipFromWarehouse(discipleId, enabled)
    fun toggleAutoLearnFromWarehouse(discipleId: String, enabled: Boolean) = disciple.toggleAutoLearnFromWarehouse(discipleId, enabled)
    suspend fun rewardItemsToDisciple(discipleId: String, items: List<RewardSelectedItem>) = disciple.rewardItemsToDisciple(discipleId, items)
    fun recruitAllDisciples() = disciple.recruitAllDisciples()
    fun rejectDiscipleFromList(discipleId: String) = disciple.rejectDiscipleFromList(discipleId)
    fun setAutoRecruitFilter(filter: Set<Int>) = disciple.setAutoRecruitFilter(filter)
    fun setAutoRejectFilter(filter: Set<Int>) = disciple.setAutoRejectFilter(filter)
    fun togglePrisonerFilter(rootCount: Int) = autoAssign.togglePrisonerFilter(rootCount)
    fun setPrisonerSpiritRootFilter(filter: Set<Int>) = autoAssign.setPrisonerSpiritRootFilter(filter)
    fun setDaoCompanionBannedRootCounts(counts: Set<Int>) = autoAssign.setDaoCompanionBannedRootCounts(counts)
    fun setDaoCompanionConsentRequired(required: Boolean) = autoAssign.setDaoCompanionConsentRequired(required)
    fun setAutoAssignSettings(
        mineFocused: Boolean, mineRootCounts: List<Int>, mineThreshold: Int,
        alchemyFocused: Boolean, alchemyRootCounts: List<Int>, alchemyThreshold: Int,
        forgeFocused: Boolean, forgeRootCounts: List<Int>, forgeThreshold: Int,
        singleResidenceFocused: Boolean = false, singleResidenceRootCounts: List<Int> = emptyList(), singleResidenceThreshold: Int = 1,
        multiResidenceFocused: Boolean = false, multiResidenceRootCounts: List<Int> = emptyList(), multiResidenceThreshold: Int = 1,
        plantFocused: Boolean = false, plantRootCounts: List<Int> = emptyList(), plantThreshold: Int = 1
    ) = autoAssign.setAutoAssignSettings(
        mineFocused, mineRootCounts, mineThreshold,
        alchemyFocused, alchemyRootCounts, alchemyThreshold,
        forgeFocused, forgeRootCounts, forgeThreshold,
        singleResidenceFocused, singleResidenceRootCounts, singleResidenceThreshold,
        multiResidenceFocused, multiResidenceRootCounts, multiResidenceThreshold,
        plantFocused, plantRootCounts, plantThreshold
    )
    fun setBreakthroughAutoPillSettings(focused: Boolean, rootCounts: Set<Int>) =
        autoAssign.setBreakthroughAutoPillSettings(focused, rootCounts)
    fun setAutoEquipSettings(focused: Boolean, rootCounts: Set<Int>) =
        autoAssign.setAutoEquipSettings(focused, rootCounts)
    fun setAutoLearnSettings(focused: Boolean, rootCounts: Set<Int>) =
        autoAssign.setAutoLearnSettings(focused, rootCounts)
    fun equipItem(discipleId: String, equipmentId: String) = disciple.equipItem(discipleId, equipmentId)
    fun unequipItem(discipleId: String, slot: EquipmentSlot) = disciple.unequipItem(discipleId, slot)
    fun unequipItem(discipleId: String, equipmentId: String) = disciple.unequipItem(discipleId, equipmentId)
    fun forgetManual(discipleId: String, instanceId: String) = disciple.forgetManual(discipleId, instanceId)
    fun replaceManual(discipleId: String, oldInstanceId: String, newStackId: String) = disciple.replaceManual(discipleId, oldInstanceId, newStackId)
    fun learnManual(discipleId: String, stackId: String) = disciple.learnManual(discipleId, stackId)

    // ── Inventory / Merchant ──

    fun buyFromMerchant(itemId: String, quantity: Int = 1) = inventory.buyFromMerchant(itemId, quantity)
    fun refreshTravelingMerchantManual() { gameEngine.launchOnEngine { gameEngine.refreshTravelingMerchantManual() } }
    fun grantMerchantRefreshChanceFromAd() { gameEngine.launchOnEngine { gameEngine.grantMerchantRefreshChanceFromAd() } }
    fun listItemsToMerchant(items: List<Pair<String, Int>>) = inventory.listItemsToMerchant(items)
    fun removePlayerListedItem(itemId: String) = inventory.removePlayerListedItem(itemId)

    fun recruitDisciple(disciple: DiscipleAggregate) = this@GameViewModel.disciple.recruitDisciple(disciple)
    fun plantOnSpiritField(buildingInstanceId: String, seedId: String, sectId: String) =
        planting.plantOnSpiritField(buildingInstanceId, seedId, sectId)
    fun plantOnSpiritFields(instanceIds: List<String>, seedId: String, sectId: String) =
        planting.plantOnSpiritFields(instanceIds, seedId, sectId)
    fun removePlantFromSpiritField(buildingInstanceId: String) =
        planting.removePlantFromSpiritField(buildingInstanceId)
    fun usePill(discipleId: String, pillId: String) = disciple.usePill(discipleId, pillId)
    fun usePill(discipleId: String, pill: Pill) = disciple.usePill(discipleId, pill)
    fun confiscateStorageBagItem(discipleId: String, item: StorageBagItem) = disciple.confiscateStorageBagItem(discipleId, item)
    fun getDiscipleById(id: String): DiscipleAggregate? = disciple.getDiscipleById(id)
    fun getLastChatYear(discipleId: String): Int? = disciple.getLastChatYear(discipleId)
    fun applyConversationEffects(
        discipleId: String, currentYear: Int, moralityDelta: Int, loyaltyDelta: Int,
        cultivationDelta: Double, intelligenceDelta: Int
    ) = disciple.applyConversationEffects(discipleId, currentYear, moralityDelta, loyaltyDelta, cultivationDelta, intelligenceDelta)

    @Suppress("DEPRECATION")
    fun getManualById(id: String): ManualInstance? = inventory.getManualById(id)
    fun getManualInstanceById(id: String): ManualInstance? = inventory.getManualInstanceById(id)
    fun getEquipmentInstanceById(id: String): EquipmentInstance? = inventory.getEquipmentInstanceById(id)
    fun toggleItemLock(itemId: String, itemType: String) = inventory.toggleItemLock(itemId, itemType)
    fun sellToMerchant(itemId: String, quantity: Int) = inventory.sellToMerchant(itemId, quantity)
    fun sellItem(itemId: String, itemType: String, quantity: Int) = inventory.sellItem(itemId, itemType, quantity)
    fun addAutoBuyEntries(entries: List<AutoBuyEntry>) = inventory.addAutoBuyEntries(entries)
    fun removeAutoBuyEntries(entries: List<AutoBuyEntry>) = inventory.removeAutoBuyEntries(entries)
    fun getAllAutoBuyableItems(): List<AutoBuyCatalogItem> = inventory.getAllAutoBuyableItems()
    fun getEquipmentById(id: String): EquipmentInstance? = inventory.getEquipmentById(id)
    fun getPillById(id: String): Pill? = inventory.getPillById(id)
    fun getMaterialById(id: String): Material? = inventory.getMaterialById(id)

    fun bulkSellItems(selectedRarities: Set<Int>, selectedTypes: Set<String>) {
        gameEngine.launchOnEngine {
            try {
                val operations = mutableListOf<GameEngine.BulkSellOperation>()
                val typeConfigs = listOf(
                    "EQUIPMENT" to (equipmentStacks.value as List<Any>),
                    "MANUAL" to (manualStacks.value as List<Any>),
                    "PILL" to (pills.value as List<Any>),
                    "MATERIAL" to (materials.value as List<Any>),
                    "HERB" to (herbs.value as List<Any>),
                    "SEED" to (seeds.value as List<Any>)
                )
                for ((typeName, items) in typeConfigs) {
                    if (!selectedTypes.contains(typeName)) continue
                    @Suppress("UNCHECKED_CAST")
                    (items as? List<*>)?.forEach { item ->
                        val rarity = when (item) {
                            is EquipmentStack -> item.rarity; is ManualStack -> item.rarity
                            is Pill -> item.rarity; is Material -> item.rarity
                            is Herb -> item.rarity; is Seed -> item.rarity
                            else -> return@forEach
                        }
                        val locked = item is EquipmentStack && item.isLocked || item is ManualStack && item.isLocked
                        if (selectedRarities.contains(rarity) && !locked) {
                            val qty = when (item) {
                                is EquipmentStack -> item.quantity; is ManualStack -> item.quantity
                                is Pill -> item.quantity; is Material -> item.quantity
                                is Herb -> item.quantity; is Seed -> item.quantity
                                else -> 1
                            }
                            operations.add(GameEngine.BulkSellOperation(item.id.toString(), "", qty, typeName.lowercase()))
                        }
                    }
                }
                if (operations.isEmpty()) {
                    withContext(Dispatchers.Main) { showError("没有符合条件的物品可出售（已排除锁定物品）") }
                    return@launchOnEngine
                }
                val result = gameEngine.bulkSellItems(operations)
                withContext(Dispatchers.Main) {
                    if (result.soldCount > 0) {
                        showSuccess("成功出售 ${result.soldCount} 件物品，获得 ${result.totalEarned} 灵石" +
                            result.failedItemNames.takeIf { it.isNotEmpty() }?.let { "\n以下物品出售失败：${it.joinToString("、")}" } ?: "")
                    } else showError("出售失败，物品可能已被锁定或不存在")
                }
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { withContext(Dispatchers.Main) { showError(e.message ?: "一键出售失败") } }
        }
    }

    fun startMission(mission: Mission, selectedDisciples: List<DiscipleAggregate>) {
        gameEngine.launchOnEngine {
            try { gameEngine.startMission(mission, selectedDisciples.map { it.toDisciple() }) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { withContext(Dispatchers.Main) { showError(e.message ?: "开始任务失败") } }
        }
    }

    val isGameOver: StateFlow<Boolean> = gameEngine.gameData
        .map { it.isGameOver }.distinctUntilChanged()
        .stateIn(viewModelScope, sharingStarted, false)

    fun openGameOverDialog() = navigation.openGameOverDialog()

    // ── Residence ──

    suspend fun assignToResidence(buildingInstanceId: String, slotIndex: Int, discipleId: String) =
        buildingDelegate.assignToResidence(buildingInstanceId, slotIndex, discipleId)
    suspend fun removeFromResidence(buildingInstanceId: String, slotIndex: Int) =
        buildingDelegate.removeFromResidence(buildingInstanceId, slotIndex)

    // ── 旧 API 兼容包装方法（委托给新 delegate） ──

    // AdsDelegate — 广告播放控制
    fun isAdOnCooldown(): Boolean = ads.isAdOnCooldown()
    fun isDailyAdLimitReached(): Boolean = ads.isDailyAdLimitReached()
    /** @deprecated 请使用 [tryMarkAdWatched] 替代 */
    @Deprecated("Use tryMarkAdWatched() instead")
    fun markAdWatched() = ads.markAdWatched()
    fun tryMarkAdWatched(): Boolean = ads.tryMarkAdWatched()

    /**
     * 播放突破修炼奖励广告。
     * 免广告特权用户在 [AdService] 实现层直接发放奖励。
     */
    fun watchAdForBreakthroughBonus(discipleId: String) {
        if (isDailyAdLimitReached()) return
        adService.watchAd(AdPurpose.BREAKTHROUGH_BONUS) {
            if (tryMarkAdWatched()) {
                applyAdBreakthroughBonus(discipleId, AD_BONUS_PER_AD)
            }
        }
    }

    /**
     * 播放商人刷新次数广告。
     * 免广告特权用户在 [AdService] 实现层直接发放奖励。
     */
    fun watchAdForMerchantRefresh() {
        if (isDailyAdLimitReached()) return
        adService.watchAd(AdPurpose.MERCHANT_REFRESH) {
            if (tryMarkAdWatched()) {
                grantMerchantRefreshChanceFromAd()
            }
        }
    }

    // OverlayDelegate
    val overlayOrder: List<TopOverlay> get() = overlays.overlayOrder
    fun pushOverlay(overlay: TopOverlay) = overlays.pushOverlay(overlay)
    fun popOverlay(overlay: TopOverlay) = overlays.popOverlay(overlay)
    val detailDisciple: StateFlow<DiscipleDetailRequest?> get() = overlays.detailDisciple
    fun showDiscipleDetail(request: DiscipleDetailRequest) = overlays.showDiscipleDetail(request)
    fun dismissDiscipleDetail() = overlays.dismissDiscipleDetail()
    fun navigateDiscipleDetail(disciple: DiscipleAggregate) = overlays.navigateDiscipleDetail(disciple)

    // BagDelegate
    val bagRewardCards: StateFlow<List<RewardCardItem>> get() = bag.bagRewardCards
    suspend fun openStorageBag(bagId: String): List<BattleRewardItem> = bag.openStorageBag(bagId)
    suspend fun openAllStorageBags(bagId: String): List<BattleRewardItem> = bag.openAllStorageBags(bagId)
    fun enqueueBagRewardCards() = bag.enqueueBagRewardCards()

    // RedeemCodeDelegate
    val showRedeemCodeDialog: StateFlow<Boolean> get() = redeem.showRedeemCodeDialog
    val redeemResult: StateFlow<RedeemResult?> get() = redeem.redeemResult
    fun openRedeemCodeDialog() = redeem.openRedeemCodeDialog()
    fun closeRedeemCodeDialog() = redeem.closeRedeemCodeDialog()
    fun redeemCode(code: String) = redeem.redeemCode(code)
    fun clearRedeemResult() = redeem.clearRedeemResult()

    // MailDelegate
    val mails: StateFlow<List<MailEntity>> get() = mail.mails
    val mailUnreadCount: StateFlow<Int> get() = mail.mailUnreadCount
    val mailRewardCards: StateFlow<List<RewardCardItem>> get() = mail.mailRewardCards
    fun markMailAsRead(mailId: String) = mail.markMailAsRead(mailId)
    fun claimMailAttachment(mailId: String, onResult: (ClaimResult) -> Unit = {}) = mail.claimMailAttachment(mailId, onResult)
    fun markAllMailsAsRead() = mail.markAllMailsAsRead()
    fun enqueueMailRewardCards() = mail.enqueueMailRewardCards()
    fun deleteAllReadAndClaimedMails() = mail.deleteAllReadAndClaimedMails()

    // SignInDelegate
    val signInState: StateFlow<SignInState> get() = signIn.signInState
    val canClaimToday: StateFlow<Boolean> get() = signIn.canClaimToday
    val heavenlyTrialClaimable: StateFlow<Boolean> get() = signIn.heavenlyTrialClaimable
    val anyActivityClaimable: StateFlow<Boolean> get() = signIn.anyActivityClaimable
    val claimedDaysCount: StateFlow<Int> get() = signIn.claimedDaysCount
    val claimedMilestones: StateFlow<List<Int>> get() = signIn.claimedMilestones
    val milestoneRewards: List<MilestoneReward> get() = signIn.milestoneRewards
    fun getRewardForWeekday(weekday: Int): DailySignInReward = signIn.getRewardForWeekday(weekday)
    fun getDayState(dayOfMonth: Int, signInState: SignInState): SignInDayState = signIn.getDayState(dayOfMonth, signInState)
    fun getDaysInMonth(): Int = signIn.getDaysInMonth()
    fun getWeekdayForDay(dayOfMonth: Int): Int = signIn.getWeekdayForDay(dayOfMonth)
    fun claimDailySignIn() = signIn.claimDailySignIn()

    /** 统一仓库容量不足提示框（GameOverlayHost 渲染，未来新增领取按钮直接调用） */
    override fun showCapacityWarning(message: String) = super.showCapacityWarning(message)
    fun enqueueRewardCards(cards: List<RewardCardItem>) = signIn.enqueueRewardCards(cards)

    // GameLoopDelegate
    val cultivationProgress: StateFlow<Float> get() = gameLoop.cultivationProgress
    val interpolationFactor: Float get() = gameLoop.interpolationFactor
    fun updateCultivationProgress(target: Float) = gameLoop.updateCultivationProgress(target)
    fun updateInterpolationFactor(alpha: Float) = gameLoop.updateInterpolationFactor(alpha)
    fun onMemoryPressure(level: Int) = gameLoop.onMemoryPressure(level)
    fun clearResources() = gameLoop.clearResources()

    // SettingsDelegate
    fun setPatrolBattleResultPopup(enabled: Boolean) = settings.setPatrolBattleResultPopup(enabled)
    fun setSoundEnabled(enabled: Boolean) {
        settings.setSoundEnabled(enabled)
    }
    fun setMusicEnabled(enabled: Boolean) {
        settings.setMusicEnabled(enabled)
        audioEngine.onSettingsChanged()
    }
    fun setAutoSellMidGradeForPurchase(enabled: Boolean) = settings.setAutoSellMidGradeForPurchase(enabled)
    fun setAutoSellHighGradeForPurchase(enabled: Boolean) = settings.setAutoSellHighGradeForPurchase(enabled)
    fun setShowAllAvailableDisciples(enabled: Boolean) = settings.setShowAllAvailableDisciples(enabled)
    fun releaseDiscipleFromAllSlotsAtomic(discipleId: String) {
        gameEngine.launchOnEngine { gameEngine.releaseDiscipleFromAllSlotsAtomic(discipleId) }
    }

    /**
     * 释放弟子为其分配新任务。根据当前状态决定释放方式：
     * - REFLECTING（思过中）→ 释放思过（调用 releaseReflectionDisciple）
     * - REFINING（血炼中）→ 中止血炼（不返还材料）
     * - 其他状态 → releaseDiscipleFromAllSlotsAtomic
     */
    fun releaseDiscipleForReassignment(discipleId: String) {
        val status = gameEngine.getDiscipleAggregate(discipleId)?.status ?: return
        when (status) {
            com.xianxia.sect.core.model.DiscipleStatus.REFLECTING -> {
                releaseReflectionDisciple(discipleId)
            }
            com.xianxia.sect.core.model.DiscipleStatus.REFINING -> {
                // 找到该弟子对应的血炼建筑实例 → 中止血炼（不返还材料）
                val gd = gameEngine.gameDataSnapshot
                val buildingInstanceId = gd?.activeBloodRefinements?.entries
                    ?.firstOrNull { it.value.discipleId == discipleId }
                    ?.key
                if (buildingInstanceId != null) {
                    gameEngine.launchOnEngine {
                        gameEngine.cancelBloodRefinement(buildingInstanceId, discipleId)
                    }
                } else {
                    releaseDiscipleFromAllSlotsAtomic(discipleId)
                }
            }
            else -> releaseDiscipleFromAllSlotsAtomic(discipleId)
        }
    }

    val showAllAvailableDisciplesSnapshot: Boolean get() = settings.showAllAvailableDisciplesSnapshot
    val battleAndExplorationIdsSnapshot: Set<String> get() = settings.battleAndExplorationIdsSnapshot
    fun setActiveTab(tab: String) = settings.setActiveTab(tab)
    fun consumeBloodRefiningMaterial(name: String, rarity: Int, quantity: Int) = settings.consumeBloodRefiningMaterial(name, rarity, quantity)
    fun setYearlySalary(realm: Int, amount: Int) = settings.setYearlySalary(realm, amount)
    fun setYearlySalaryEnabled(realm: Int, enabled: Boolean) = settings.setYearlySalaryEnabled(realm, enabled)

    override fun onCleared() {
        Log.i(TAG, "GameViewModel cleared, stopping game loop and releasing resources")
        dialogManager.close()
        gameEngine.setActiveDialog(null)
        gameLoop.clearResources()
        super.onCleared()
    }
}
