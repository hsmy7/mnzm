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

    /**
     * 打开指定路由的对话框，并通知引擎当前激活的对话框。
     *
     * @param route 目标对话框路由
     */
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

    /** 关闭当前对话框，将路由重置为 [DialogRoute.None] 并清空引擎侧激活状态 */
    fun dismissDialog() {
        _currentDialogRoute.value = DialogRoute.None
        gameEngine.setActiveDialog(null)
    }

    /** 通知引擎有用户交互（与 [onUserInteraction] 等价，保留以兼容旧调用点） */
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

    /** 关闭所有对话框，委托给 [NavigationDelegate] */
    fun closeAllDialogs() = navigation.closeAllDialogs()

    /** 打开灵矿场对话框，委托给 [NavigationDelegate] */
    fun openSpiritMineDialog(mineIndex: Int = 0) = navigation.openSpiritMineDialog(mineIndex)

    /** 打开灵田对话框，委托给 [NavigationDelegate] */
    fun openHerbGardenDialog() = navigation.openHerbGardenDialog()

    /** 打开炼丹房对话框，委托给 [NavigationDelegate] */
    fun openAlchemyDialog(buildingIndex: Int = 0) = navigation.openAlchemyDialog(buildingIndex)

    /** 打开炼器坊对话框，委托给 [NavigationDelegate] */
    fun openForgeDialog(buildingIndex: Int = 0) = navigation.openForgeDialog(buildingIndex)

    /** 打开藏经阁对话框，委托给 [NavigationDelegate] */
    fun openLibraryDialog() = navigation.openLibraryDialog()

    /** 打开问道峰对话框，委托给 [NavigationDelegate] */
    fun openWenDaoPeakDialog() = navigation.openWenDaoPeakDialog()

    /** 打开青云峰对话框，委托给 [NavigationDelegate] */
    fun openQingyunPeakDialog() = navigation.openQingyunPeakDialog()

    /** 打开天枢殿对话框，委托给 [NavigationDelegate] */
    fun openTianshuHallDialog() = navigation.openTianshuHallDialog()

    /** 打开执法堂对话框，委托给 [NavigationDelegate] */
    fun openLawEnforcementHallDialog() = navigation.openLawEnforcementHallDialog()

    /** 打开任务堂对话框，委托给 [NavigationDelegate] */
    fun openMissionHallDialog() = navigation.openMissionHallDialog()

    /** 打开思过崖对话框，委托给 [NavigationDelegate] */
    fun openReflectionCliffDialog() = navigation.openReflectionCliffDialog()

    /** 打开巡视楼对话框，委托给 [NavigationDelegate] */
    fun openPatrolTowerDialog(buildingInstanceId: String = "") = navigation.openPatrolTowerDialog(buildingInstanceId.ifEmpty { "" })

    /** 打开血炼池对话框，委托给 [NavigationDelegate] */
    fun openBloodRefiningPoolDialog(buildingInstanceId: String = "") = navigation.openBloodRefiningPoolDialog(buildingInstanceId.ifEmpty { "" })

    /** 打开世界地图对话框，委托给 [NavigationDelegate] */
    fun openWorldMapDialog() = navigation.openWorldMapDialog()

    /** 打开招募弟子对话框，委托给 [NavigationDelegate] */
    fun openRecruitDialog() = navigation.openRecruitDialog()

    /** 打开坊市对话框，委托给 [NavigationDelegate] */
    fun openMerchantDialog() = navigation.openMerchantDialog()

    /** 打开外交对话框，委托给 [NavigationDelegate] */
    fun openDiplomacyDialog() = navigation.openDiplomacyDialog()

    /**
     * 攻击世界关卡，委托给 [NavigationDelegate]
     *
     * @param levelId 关卡 ID
     * @param discipleIds 参战弟子 ID 列表（元素可为 null 表示空位）
     */
    fun attackWorldLevel(levelId: String, discipleIds: List<String?>) = navigation.attackWorldLevel(levelId, discipleIds)

    /** 打开战斗记录对话框，委托给 [NavigationDelegate] */
    fun openBattleLogDialog() = navigation.openBattleLogDialog()

    /** 关闭战斗结果展示，委托给 [NavigationDelegate] */
    fun dismissBattleResult() = navigation.dismissBattleResult()

    /**
     * 处理兽袭事件 — 选择进贡物资以平息该兽袭。
     *
     * @param beastLevelId 兽袭关卡 ID
     */
    fun resolveBeastAttackPayTribute(beastLevelId: String) {
        gameEngine.resolveBeastAttackPayTribute(beastLevelId)
    }

    /**
     * 处理兽袭事件 — 选择战斗抵抗。
     *
     * @param beastLevelId 兽袭关卡 ID
     */
    fun resolveBeastAttackFight(beastLevelId: String) {
        viewModelScope.launch {
            gameEngine.resolveBeastAttackFight(beastLevelId)
        }
    }

    /** 清空所有待处理的兽袭事件 */
    fun clearPendingBeastAttacks() {
        gameEngine.clearPendingBeastAttacks()
    }

    // AI宗门进攻预警
    val attackWarnings: StateFlow<List<AttackWarning>> = gameEngine.gameData
        .map { it.activeAttackWarnings }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            emptyList()
        )

    val shownWarningStageIds: StateFlow<List<String>> = gameEngine.gameData
        .map { it.shownWarningStageIds }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            emptyList()
        )

    /**
     * 处理 AI 宗门进攻预警 — 选择安抚（支付资源）以避免进攻。
     *
     * @param sectId 进攻方宗门 ID
     */
    fun resolveAttackWarningAppease(sectId: String) {
        viewModelScope.launch {
            gameEngine.appeaseAttackingSect(sectId)
        }
    }

    /**
     * 处理 AI 宗门进攻预警 — 选择成为附庸以避免进攻。
     *
     * @param sectId 进攻方宗门 ID
     */
    fun resolveAttackWarningVassal(sectId: String) {
        viewModelScope.launch {
            gameEngine.becomeVassalOfAttacker(sectId)
        }
    }

    /**
     * 标记某预警阶段已展示过，避免重复弹出。
     *
     * @param stageKey 预警阶段标识
     */
    fun markWarningStageShown(stageKey: String) {
        viewModelScope.launch {
            gameEngine.markWarningStageShown(stageKey)
        }
    }

    /** 将待处理的战斗奖励卡片入队签到服务，触发飞出动画 */
    fun enqueueBattleRewardCards() {
        val cards = gameEngine.pendingBattleRewardCards.value
        if (cards.isNotEmpty()) {
            dailySignInService.enqueueSignInCards(cards)
            gameEngine.clearPendingBattleRewardCards()
        }
    }

    /**
     * 在网格上放置建筑，扣除灵石并初始化对应的产出/居住/种植槽位。
     *
     * @param name 建筑显示名（用于查找配置）
     * @param gridX 网格 X 坐标
     * @param gridY 网格 Y 坐标
     * @param width 网格宽度（已废弃，实际尺寸由配置决定）
     * @param height 网格高度（已废弃，实际尺寸由配置决定）
     */
    fun placeBuilding(name: String, gridX: Int, gridY: Int, width: Int = 2, height: Int = 3) {
        viewModelScope.launch {
            val config = buildingConfigService.getBuildingConfigByDisplayName(name)
            val cost = config?.cost ?: 1000L
            val (gridW, gridH) = buildingConfigService.getBuildingGridSize(name)

            // Pre-compute new building instance ID so production slot can reference it
            val newBuildingInstanceId = java.util.UUID.randomUUID().toString()

            // 在 updateGameData 闭包外保留对新 ProductionSlot 的引用，
            // 但其 idx 在闭包内基于当前 data 计算以保证原子性
            var newProductionSlot: ProductionSlot? = null

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

                val newBuilding = GridBuildingData(
                    buildingId = name,
                    displayName = name,
                    gridX = gridX,
                    gridY = gridY,
                    width = gridW,
                    height = gridH,
                    sectId = activeId,
                    instanceId = newBuildingInstanceId
                )

                // 在事务闭包内基于当前 data 计算 idx，避免并发放置同类型建筑时
                // idx 重复（历史 bug：idx 在闭包外基于快照计算，与闭包内 data 非原子）
                newProductionSlot = when (name) {
                    BuildingDef.ALCHEMY.displayName -> {
                        val idx = data.placedBuildings.count { it.displayName == BuildingDef.ALCHEMY.displayName }
                        ProductionSlot.createIdle(slotIndex = idx, buildingType = BuildingType.ALCHEMY, buildingId = "alchemy")
                            .copy(buildingInstanceId = newBuildingInstanceId)
                    }
                    BuildingDef.FORGE.displayName -> {
                        val idx = data.placedBuildings.count { it.displayName == BuildingDef.FORGE.displayName }
                        ProductionSlot.createIdle(slotIndex = idx, buildingType = BuildingType.FORGE, buildingId = "forge")
                            .copy(buildingInstanceId = newBuildingInstanceId)
                    }
                    else -> null
                }

                // 创建灵矿场槽位时写入 buildingInstanceId，用于建筑移除时精确匹配
                val newSlots = if (name == BuildingDef.SPIRIT_MINE.displayName) {
                    val nextIndex = data.spiritMineSlots.size
                    (0 until 3).map { SpiritMineSlot(index = nextIndex + it, buildingInstanceId = newBuilding.instanceId) }
                } else emptyList()

                // 创建巡视楼槽位时写入 buildingInstanceId
                val newPatrolSlots = if (name == BuildingDef.PATROL_TOWER.displayName) {
                    val nextIndex = data.patrolSlots.size
                    val slotCount = buildingConfigService.getSlotCountByDisplayName(name)
                    (0 until slotCount).map { PatrolSlot(index = nextIndex + it, buildingInstanceId = newBuilding.instanceId) }
                } else emptyList()

                val newPatrolConfigs = if (name == BuildingDef.PATROL_TOWER.displayName) {
                    data.patrolConfigs + PatrolConfig()
                } else emptyList()

                val newResidenceSlots = when (name) {
                    BuildingDef.SINGLE_RESIDENCE.displayName -> (0 until 1).map { ResidenceSlot(buildingInstanceId = newBuilding.instanceId, slotIndex = it) }
                    BuildingDef.MULTI_RESIDENCE.displayName -> (0 until 4).map { ResidenceSlot(buildingInstanceId = newBuilding.instanceId, slotIndex = it) }
                    else -> emptyList()
                }

                val newSpiritFieldPlants = if (name == BuildingDef.SPIRIT_FIELD.displayName) {
                    listOf(SpiritFieldPlant(buildingInstanceId = newBuilding.instanceId, sectId = activeId))
                } else emptyList()

                @Suppress("DEPRECATION")
                val slot = newProductionSlot
                val updatedProductionSlots = if (slot != null)
                    data.productionSlots + slot else data.productionSlots
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

            newProductionSlot?.let { slot ->
                buildingFacade.addProductionSlot(slot)
            }
        }
    }

    /**
     * 查询建筑放置所需灵石。
     *
     * @param displayName 建筑显示名
     * @return 灵石消耗；查无配置时返回默认值 1000
     */
    fun getBuildingCost(displayName: String): Long {
        return buildingConfigService.getBuildingConfigByDisplayName(displayName)?.cost ?: 1000L
    }

    /**
     * 查询建筑网格尺寸。
     *
     * @param displayName 建筑显示名
     * @return (宽, 高) 的网格单元数
     */
    fun getBuildingGridSize(displayName: String): Pair<Int, Int> {
        return buildingConfigService.getBuildingGridSize(displayName)
    }

    /**
     * 移动已放置的建筑到新坐标。
     *
     * @param instanceId 建筑实例 ID
     * @param newGridX 新网格 X 坐标
     * @param newGridY 新网格 Y 坐标
     */
    fun moveBuilding(instanceId: String, newGridX: Int, newGridY: Int) {
        viewModelScope.launch { buildingFacade.moveBuildingDirect(instanceId, newGridX, newGridY) }
    }

    /**
     * 拆除建筑，返还一半造价并提示。
     *
     * @param instanceId 建筑实例 ID
     */
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

    /**
     * 判断弟子是否已分配到某个职位。
     *
     * @param discipleId 弟子 ID
     * @return true 表示已分配职位
     */
    fun hasDisciplePosition(discipleId: String): Boolean {
        return disciplePositionQuery.hasDisciplePosition(discipleId)
    }

    /**
     * 获取弟子的职位描述。
     *
     * @param discipleId 弟子 ID
     * @return 职位描述；未分配时返回 null
     */
    fun getDisciplePosition(discipleId: String): String? {
        return disciplePositionQuery.getDisciplePosition(discipleId)
    }

    /**
     * 判断弟子是否为预备弟子（无职位）。
     *
     * @param discipleId 弟子 ID
     * @return true 表示是预备弟子
     */
    fun isReserveDisciple(discipleId: String): Boolean {
        return disciplePositionQuery.isReserveDisciple(discipleId)
    }

    /**
     * 判断弟子当前职位是否为工作状态。
     *
     * @param discipleId 弟子 ID
     * @return true 表示职位处于工作状态
     */
    fun isPositionWorkStatus(discipleId: String): Boolean {
        return disciplePositionQuery.isPositionWorkStatus(discipleId)
    }

    // 防止重复点击标志

    // 顶层 inline overlay z-order 列表（最后的 = 最顶层）
    private val _overlayOrder = mutableStateListOf<TopOverlay>()
    val overlayOrder: List<TopOverlay> get() = _overlayOrder

    /**
     * 将 overlay 推入栈顶（若已存在则先移除再添加），使其渲染在最顶层。
     *
     * @param overlay 要置顶的 overlay 类型
     */
    fun pushOverlay(overlay: TopOverlay) {
        _overlayOrder.remove(overlay)
        _overlayOrder.add(overlay)
    }

    /**
     * 从 overlay 列表中移除指定 overlay。
     *
     * @param overlay 要移除的 overlay 类型
     */
    fun popOverlay(overlay: TopOverlay) {
        _overlayOrder.remove(overlay)
    }

    // 弟子详情 — 顶层全屏覆盖，统一所有触发入口
    private val _detailDisciple = MutableStateFlow<DiscipleDetailRequest?>(null)
    val detailDisciple: StateFlow<DiscipleDetailRequest?> = _detailDisciple.asStateFlow()

    /**
     * 显示弟子详情全屏覆盖，并设置引擎聚焦弟子。
     *
     * @param request 弟子详情请求，包含目标弟子及全量弟子列表
     */
    fun showDiscipleDetail(request: DiscipleDetailRequest) {
        _detailDisciple.value = request
        gameEngine.setFocusedDiscipleId(request.disciple.id)
        pushOverlay(TopOverlay.DISCIPLE_DETAIL)
    }

    /** 关闭弟子详情覆盖，并清空引擎聚焦弟子 */
    fun dismissDiscipleDetail() {
        _detailDisciple.value = null
        gameEngine.setFocusedDiscipleId(null)
        popOverlay(TopOverlay.DISCIPLE_DETAIL)
    }

    /**
     * 在弟子详情覆盖中切换当前展示的弟子。
     *
     * @param disciple 目标弟子聚合数据
     */
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

    /**
     * 打开建筑详情对话框（带参数）。
     *
     * @param buildingId 建筑实例 ID
     */
    fun openBuildingDetailDialog(buildingId: String) {
        _selectedBuildingId.value = buildingId
    }

    /** 招募弟子（无参重载），委托给 [DiscipleDelegate] */
    fun recruitDisciple() = disciple.recruitDisciple()

    /**
     * 将弟子分配到指定建筑的槽位，委托给 [DiscipleDelegate]
     *
     * @param buildingId 建筑实例 ID
     * @param slotIndex 槽位序号
     * @param discipleId 弟子 ID
     */
    fun assignDiscipleToBuilding(buildingId: String, slotIndex: Int, discipleId: String) = disciple.assignDiscipleToBuilding(buildingId, slotIndex, discipleId)

    /**
     * 从可招募列表中招募指定弟子，委托给 [DiscipleDelegate]
     *
     * @param discipleId 弟子 ID
     */
    fun recruitDiscipleFromList(discipleId: String) = disciple.recruitDiscipleFromList(discipleId)

    /**
     * 驱逐弟子，委托给 [DiscipleDelegate]
     *
     * @param discipleId 弟子 ID
     */
    fun expelDisciple(discipleId: String) = disciple.expelDisciple(discipleId)

    /** 拜师：将 discipleId 设为 masterId 的徒弟，委托给 [DiscipleDelegate] */
    fun apprenticeToMaster(discipleId: String, masterId: String) = disciple.apprenticeToMaster(discipleId, masterId)

    /** 清空弟子侧待处理通知 */
    fun clearNotification() {
        discipleFacade.clearPendingNotification()
    }

    /**
     * 清空奖励卡片队列。
     *
     * @param count 清除数量，默认全部
     */
    fun clearRewardCardQueue(count: Int = Int.MAX_VALUE) {
        gameEngine.clearRewardCardQueue(count)
    }

    /**
     * 进入指定宗门（世界地图）。
     *
     * @param sectId 宗门 ID
     */
    fun enterSect(sectId: String) {
        viewModelScope.launch { gameEngine.enterSect(sectId) }
    }

    /**
     * 驱逐行窃弟子，委托给 [DiscipleDelegate]
     *
     * @param discipleId 弟子 ID
     */
    fun expelTheftDisciple(discipleId: String) = disciple.expelTheftDisciple(discipleId)

    /**
     * 关押行窃弟子，委托给 [DiscipleDelegate]
     *
     * @param discipleId 弟子 ID
     * @param currentYear 当前游戏年份
     */
    fun imprisonTheftDisciple(discipleId: String, currentYear: Int) = viewModelScope.launch { disciple.imprisonTheftDisciple(discipleId, currentYear) }

    /**
     * 释放关押的行窃弟子，委托给 [DiscipleDelegate]
     *
     * @param discipleId 弟子 ID
     * @return 释放结果状态码
     */
    suspend fun releaseTheftDisciple(discipleId: String): Int = disciple.releaseTheftDisciple(discipleId)

    /** 忠诚度对话框关闭时的回调，委托给 [DiscipleDelegate] */
    fun onLoyaltyDialogDismissed() = disciple.onLoyaltyDialogDismissed()

    /**
     * 切换弟子跟随状态，委托给 [DiscipleDelegate]
     *
     * @param discipleId 弟子 ID
     */
    fun toggleFollowDisciple(discipleId: String) = disciple.toggleFollowDisciple(discipleId)

    /**
     * 应用广告突破加成，委托给 [DiscipleDelegate]
     *
     * @param discipleId 弟子 ID
     * @param bonus 加成系数
     */
    fun applyAdBreakthroughBonus(discipleId: String, bonus: Double) = disciple.applyAdBreakthroughBonus(discipleId, bonus)

    /**
     * 修改弟子类型，委托给 [DiscipleDelegate]
     *
     * @param discipleId 弟子 ID
     * @param newType 新类型标识
     */
    fun changeDiscipleType(discipleId: String, newType: String) = disciple.changeDiscipleType(discipleId, newType)

    /**
     * 切换弟子自动从仓库装备的开关，委托给 [DiscipleDelegate]
     *
     * @param discipleId 弟子 ID
     * @param enabled 是否启用
     */
    fun toggleAutoEquipFromWarehouse(discipleId: String, enabled: Boolean) = disciple.toggleAutoEquipFromWarehouse(discipleId, enabled)

    /**
     * 切换弟子自动从仓库学习功法的开关，委托给 [DiscipleDelegate]
     *
     * @param discipleId 弟子 ID
     * @param enabled 是否启用
     */
    fun toggleAutoLearnFromWarehouse(discipleId: String, enabled: Boolean) = disciple.toggleAutoLearnFromWarehouse(discipleId, enabled)

    /**
     * 向弟子发放奖励物品，委托给 [DiscipleDelegate]
     *
     * @param discipleId 弟子 ID
     * @param items 奖励物品列表
     */
    suspend fun rewardItemsToDisciple(discipleId: String, items: List<RewardSelectedItem>) = disciple.rewardItemsToDisciple(discipleId, items)

    /** 一次性招募所有可招募弟子，委托给 [DiscipleDelegate] */
    fun recruitAllDisciples() = disciple.recruitAllDisciples()

    /**
     * 从可招募列表中拒绝指定弟子，委托给 [DiscipleDelegate]
     *
     * @param discipleId 弟子 ID
     */
    fun rejectDiscipleFromList(discipleId: String) = disciple.rejectDiscipleFromList(discipleId)

    /**
     * 设置自动招募弟子的境界过滤，委托给 [DiscipleDelegate]
     *
     * @param filter 允许招募的境界集合
     */
    fun setAutoRecruitFilter(filter: Set<Int>) = disciple.setAutoRecruitFilter(filter)

    /**
     * 设置禁止结为道侣的灵根数集合。
     *
     * @param counts 禁止的灵根数集合
     */
    fun setDaoCompanionBannedRootCounts(counts: Set<Int>) {
        viewModelScope.launch {
            gameEngine.updateGameData { it.copy(daoCompanionBannedRootCounts = counts) }
        }
    }

    /**
     * 设置道侣结成是否需要玩家同意。
     *
     * @param required true 表示需要玩家同意
     */
    fun setDaoCompanionConsentRequired(required: Boolean) {
        viewModelScope.launch {
            gameEngine.updateGameData { it.copy(daoCompanionConsentRequired = required) }
        }
    }

    /**
     * 批量设置各生产建筑的自动分配策略（聚焦/灵根过滤/境界阈值）。
     *
     * @param mineFocused 灵矿是否聚焦
     * @param mineRootCounts 灵矿允许的灵根数
     * @param mineThreshold 灵矿境界阈值
     * @param plantFocused 灵田是否聚焦
     * @param plantRootCounts 灵田允许的灵根数
     * @param plantThreshold 灵田境界阈值
     * @param alchemyFocused 炼丹是否聚焦
     * @param alchemyRootCounts 炼丹允许的灵根数
     * @param alchemyThreshold 炼丹境界阈值
     * @param forgeFocused 炼器是否聚焦
     * @param forgeRootCounts 炼器允许的灵根数
     * @param forgeThreshold 炼器境界阈值
     */
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

    /**
     * 设置突破时自动使用丹药的策略。
     *
     * @param focused 是否聚焦
     * @param rootCounts 允许自动使用丹药的灵根数集合
     */
    fun setBreakthroughAutoPillSettings(focused: Boolean, rootCounts: Set<Int>) {
        viewModelScope.launch {
            gameEngine.updateGameData { it.copy(breakthroughAutoPillFocused = focused, breakthroughAutoPillRootCounts = rootCounts) }
        }
    }

    /**
     * 设置自动从仓库装备的策略。
     *
     * @param focused 是否聚焦
     * @param rootCounts 允许自动装备的灵根数集合
     */
    fun setAutoEquipSettings(focused: Boolean, rootCounts: Set<Int>) {
        viewModelScope.launch {
            gameEngine.updateGameData { it.copy(autoEquipFromWarehouseFocused = focused, autoEquipFromWarehouseRootCounts = rootCounts) }
        }
    }

    /**
     * 设置自动从仓库学习功法的策略。
     *
     * @param focused 是否聚焦
     * @param rootCounts 允许自动学习的灵根数集合
     */
    fun setAutoLearnSettings(focused: Boolean, rootCounts: Set<Int>) {
        viewModelScope.launch {
            gameEngine.updateGameData { it.copy(autoLearnFromWarehouseFocused = focused, autoLearnFromWarehouseRootCounts = rootCounts) }
        }
    }

    /**
     * 设置巡视战斗结果弹窗开关。
     *
     * @param enabled 是否弹窗
     */
    fun setPatrolBattleResultPopup(enabled: Boolean) {
        viewModelScope.launch {
            gameEngine.updateGameData { it.copy(patrolBattleResultPopup = enabled) }
        }
    }

    /**
     * 设置购买时自动出售中品物品的开关。
     *
     * @param enabled 是否启用
     */
    fun setAutoSellMidGradeForPurchase(enabled: Boolean) {
        viewModelScope.launch {
            gameEngine.updateGameData {
                it.copy(autoSellMidGradeForPurchase = enabled)
            }
        }
    }

    /**
     * 设置购买时自动出售上品物品的开关。
     *
     * @param enabled 是否启用
     */
    fun setAutoSellHighGradeForPurchase(enabled: Boolean) {
        viewModelScope.launch {
            gameEngine.updateGameData {
                it.copy(autoSellHighGradeForPurchase = enabled)
            }
        }
    }

    /**
     * 设置当前激活的界面 Tab。
     *
     * @param tab Tab 标识
     */
    fun setActiveTab(tab: String) {
        gameEngine.setActiveTab(tab)
    }

    /**
     * 同意两名弟子结为道侣，并清空待处理通知。
     *
     * @param maleId 男方弟子 ID
     * @param femaleId 女方弟子 ID
     */
    fun approveMarriage(maleId: String, femaleId: String) {
        viewModelScope.launch {
            discipleFacade.approveMarriage(maleId, femaleId)
            discipleFacade.clearPendingNotification()
        }
    }

    /** 拒绝当前道侣申请，仅清空待处理通知 */
    fun rejectMarriage() {
        discipleFacade.clearPendingNotification()
    }

    /**
     * 为弟子装备指定装备，委托给 [DiscipleDelegate]
     *
     * @param discipleId 弟子 ID
     * @param equipmentId 装备实例 ID
     */
    fun equipItem(discipleId: String, equipmentId: String) = disciple.equipItem(discipleId, equipmentId)

    /**
     * 按槽位卸下弟子装备，委托给 [DiscipleDelegate]
     *
     * @param discipleId 弟子 ID
     * @param slot 装备槽位
     */
    fun unequipItem(discipleId: String, slot: EquipmentSlot) = disciple.unequipItem(discipleId, slot)

    /**
     * 按装备 ID 卸下弟子装备，委托给 [DiscipleDelegate]
     *
     * @param discipleId 弟子 ID
     * @param equipmentId 装备实例 ID
     */
    fun unequipItem(discipleId: String, equipmentId: String) = disciple.unequipItem(discipleId, equipmentId)

    /**
     * 遗忘弟子已学功法，委托给 [DiscipleDelegate]
     *
     * @param discipleId 弟子 ID
     * @param instanceId 功法实例 ID
     */
    fun forgetManual(discipleId: String, instanceId: String) = disciple.forgetManual(discipleId, instanceId)

    /**
     * 替换弟子功法（旧实例换新堆叠），委托给 [DiscipleDelegate]
     *
     * @param discipleId 弟子 ID
     * @param oldInstanceId 旧功法实例 ID
     * @param newStackId 新功法堆叠 ID
     */
    fun replaceManual(discipleId: String, oldInstanceId: String, newStackId: String) = disciple.replaceManual(discipleId, oldInstanceId, newStackId)

    /**
     * 学习功法，委托给 [DiscipleDelegate]
     *
     * @param discipleId 弟子 ID
     * @param stackId 功法堆叠 ID
     */
    fun learnManual(discipleId: String, stackId: String) = disciple.learnManual(discipleId, stackId)

    /**
     * 从坊市购买物品，委托给 [InventoryDelegate]
     *
     * @param itemId 物品 ID
     * @param quantity 购买数量
     */
    fun buyFromMerchant(itemId: String, quantity: Int = 1) = inventory.buyFromMerchant(itemId, quantity)

    /**
     * 将物品挂到坊市出售，委托给 [InventoryDelegate]
     *
     * @param items (物品 ID, 数量) 列表
     */
    fun listItemsToMerchant(items: List<Pair<String, Int>>) = inventory.listItemsToMerchant(items)

    /**
     * 从坊市下架玩家挂售的物品，委托给 [InventoryDelegate]
     *
     * @param itemId 物品 ID
     */
    fun removePlayerListedItem(itemId: String) = inventory.removePlayerListedItem(itemId)

    private var pendingBagCards: List<RewardCardItem> = emptyList()

    private val _bagRewardCards = MutableStateFlow<List<RewardCardItem>>(emptyList())
    val bagRewardCards: StateFlow<List<RewardCardItem>> = _bagRewardCards.asStateFlow()

    /**
     * 打开一个储物袋，返回奖励列表并缓存奖励卡片。
     *
     * @param bagId 储物袋 ID
     * @return 奖励物品列表
     */
    suspend fun openStorageBag(bagId: String): List<BattleRewardItem> {
        val (rewards, cards) = gameEngine.openStorageBag(bagId)
        pendingBagCards = cards
        _bagRewardCards.value = cards
        return rewards
    }

    /**
     * 打开指定储物袋的全部数量，聚合奖励与卡片。
     *
     * @param bagId 储物袋 ID
     * @return 全部奖励物品列表
     */
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

    /** 将缓存的储物袋奖励卡片入队签到服务，触发飞出动画 */
    fun enqueueBagRewardCards() {
        if (pendingBagCards.isNotEmpty()) {
            dailySignInService.enqueueSignInCards(pendingBagCards)
            pendingBagCards = emptyList()
            _bagRewardCards.value = emptyList()
        }
    }

    /**
     * 招募指定弟子（带聚合数据重载），委托给 [DiscipleDelegate]
     *
     * @param disciple 弟子聚合数据
     */
    fun recruitDisciple(disciple: DiscipleAggregate) = this@GameViewModel.disciple.recruitDisciple(disciple)

    /**
     * 在指定槽位种植种子。
     *
     * @param slotIndex 槽位序号
     * @param seed 种子
     */
    fun plantSeed(slotIndex: Int, seed: com.xianxia.sect.core.model.Seed) {
        viewModelScope.launch {
            try {
                buildingFacade.startManualPlanting(slotIndex, seed.id)
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                showError(e.message ?: "种植失败")
            }
        }
    }

    /**
     * 在指定灵田种植种子，委托给 [PlantingDelegate]
     *
     * @param buildingInstanceId 灵田建筑实例 ID
     * @param seedId 种子 ID
     * @param sectId 宗门 ID
     */
    fun plantOnSpiritField(buildingInstanceId: String, seedId: String, sectId: String) =
        planting.plantOnSpiritField(buildingInstanceId, seedId, sectId)

    /**
     * 在多个灵田上批量种植同一种子，委托给 [PlantingDelegate]
     *
     * @param instanceIds 灵田建筑实例 ID 列表
     * @param seedId 种子 ID
     * @param sectId 宗门 ID
     */
    fun plantOnSpiritFields(instanceIds: List<String>, seedId: String, sectId: String) =
        planting.plantOnSpiritFields(instanceIds, seedId, sectId)

    /**
     * 移除灵田上的作物，委托给 [PlantingDelegate]
     *
     * @param buildingInstanceId 灵田建筑实例 ID
     */
    fun removePlantFromSpiritField(buildingInstanceId: String) =
        planting.removePlantFromSpiritField(buildingInstanceId)

    /**
     * 设置指定境界的年薪俸禄。
     *
     * @param realm 境界
     * @param amount 年薪数额
     */
    fun setYearlySalary(realm: Int, amount: Int) {
        viewModelScope.launch {
            val data = gameEngine.gameData.value
            val newSalary = data.yearlySalary.toMutableMap()
            newSalary[realm] = amount
            gameEngine.updateYearlySalary(newSalary)
        }
    }

    /**
     * 启用/禁用指定境界的年薪发放。
     *
     * @param realm 境界
     * @param enabled 是否启用
     */
    fun setYearlySalaryEnabled(realm: Int, enabled: Boolean) {
        viewModelScope.launch {
            discipleFacade.updateYearlySalaryEnabled(realm, enabled)
        }
    }

    /**
     * 让弟子服用指定丹药（按 ID），委托给 [DiscipleDelegate]
     *
     * @param discipleId 弟子 ID
     * @param pillId 丹药 ID
     */
    fun usePill(discipleId: String, pillId: String) = disciple.usePill(discipleId, pillId)

    /**
     * 让弟子服用指定丹药（按对象），委托给 [DiscipleDelegate]
     *
     * @param discipleId 弟子 ID
     * @param pill 丹药对象
     */
    fun usePill(discipleId: String, pill: Pill) = disciple.usePill(discipleId, pill)

    /**
     * 没收弟子储物袋中的物品，委托给 [DiscipleDelegate]
     *
     * @param discipleId 弟子 ID
     * @param item 储物袋物品
     */
    fun confiscateStorageBagItem(discipleId: String, item: StorageBagItem) = disciple.confiscateStorageBagItem(discipleId, item)

    /**
     * 按 ID 查询弟子聚合数据，委托给 [DiscipleDelegate]
     *
     * @param id 弟子 ID
     * @return 弟子聚合数据；不存在时返回 null
     */
    fun getDiscipleById(id: String): DiscipleAggregate? = disciple.getDiscipleById(id)

    /**
     * 按 ID 查询功法实例（已废弃，使用 [getManualInstanceById]）。
     *
     * @param id 功法实例 ID
     * @return 功法实例；不存在时返回 null
     */
    @Suppress("DEPRECATION")
    fun getManualById(id: String): ManualInstance? = inventory.getManualById(id)

    /**
     * 按 ID 查询功法实例，委托给 [InventoryDelegate]
     *
     * @param id 功法实例 ID
     * @return 功法实例；不存在时返回 null
     */
    fun getManualInstanceById(id: String): ManualInstance? = inventory.getManualInstanceById(id)

    /**
     * 按 ID 查询装备实例，委托给 [InventoryDelegate]
     *
     * @param id 装备实例 ID
     * @return 装备实例；不存在时返回 null
     */
    fun getEquipmentInstanceById(id: String): EquipmentInstance? = inventory.getEquipmentInstanceById(id)

    /**
     * 切换物品锁定状态，委托给 [InventoryDelegate]
     *
     * @param itemId 物品 ID
     * @param itemType 物品类型
     */
    fun toggleItemLock(itemId: String, itemType: String) = inventory.toggleItemLock(itemId, itemType)

    /**
     * 向坊市出售物品，委托给 [InventoryDelegate]
     *
     * @param itemId 物品 ID
     * @param quantity 出售数量
     */
    fun sellToMerchant(itemId: String, quantity: Int) = inventory.sellToMerchant(itemId, quantity)

    /**
     * 通用出售物品，委托给 [InventoryDelegate]
     *
     * @param itemId 物品 ID
     * @param itemType 物品类型
     * @param quantity 出售数量
     */
    fun sellItem(itemId: String, itemType: String, quantity: Int) = inventory.sellItem(itemId, itemType, quantity)

    // ── 自动购买 ────────────────────────────────────────────────────

    /**
     * 批量添加自动购买条目，委托给 [InventoryDelegate]
     *
     * @param entries 自动购买条目列表
     */
    fun addAutoBuyEntries(entries: List<AutoBuyEntry>) = inventory.addAutoBuyEntries(entries)

    /**
     * 批量移除自动购买条目，委托给 [InventoryDelegate]
     *
     * @param entries 自动购买条目列表
     */
    fun removeAutoBuyEntries(entries: List<AutoBuyEntry>) = inventory.removeAutoBuyEntries(entries)

    /**
     * 获取所有可自动购买的物品目录，委托给 [InventoryDelegate]
     *
     * @return 可自动购买物品列表
     */
    fun getAllAutoBuyableItems(): List<AutoBuyCatalogItem> = inventory.getAllAutoBuyableItems()

    /**
     * 消耗血炼材料（按名称、品质、数量）。
     *
     * @param name 材料名称
     * @param rarity 材料品质
     * @param quantity 消耗数量
     */
    fun consumeBloodRefiningMaterial(name: String, rarity: Int, quantity: Int) {
        viewModelScope.launch {
            gameEngine.consumeMaterialByName(name, rarity, quantity)
        }
    }

    /**
     * 按品质与类型批量出售物品（自动跳过锁定物品）。
     *
     * @param selectedRarities 选中的品质集合
     * @param selectedTypes 选中的物品类型集合（如 "EQUIPMENT"、"PILL"）
     */
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
                } catch (e: CancellationException) { throw e }
                  catch (e: Exception) {
                    showError("出售过程中发生错误: ${e.message}")
                }
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                showError(e.message ?: "一键出售失败")
            }
        }
    }

    /**
     * 按 ID 查询装备实例，委托给 [InventoryDelegate]
     *
     * @param id 装备实例 ID
     * @return 装备实例；不存在时返回 null
     */
    fun getEquipmentById(id: String): EquipmentInstance? = inventory.getEquipmentById(id)

    /**
     * 按 ID 查询丹药，委托给 [InventoryDelegate]
     *
     * @param id 丹药 ID
     * @return 丹药；不存在时返回 null
     */
    fun getPillById(id: String): Pill? = inventory.getPillById(id)

    /**
     * 按 ID 查询材料，委托给 [InventoryDelegate]
     *
     * @param id 材料 ID
     * @return 材料；不存在时返回 null
     */
    fun getMaterialById(id: String): Material? = inventory.getMaterialById(id)

    /**
     * 开始任务，派遣指定弟子组队执行。
     *
     * @param mission 任务对象
     * @param selectedDisciples 参与弟子列表
     */
    fun startMission(mission: com.xianxia.sect.core.model.Mission, selectedDisciples: List<DiscipleAggregate>) {
        viewModelScope.launch {
            try {
                // Phase3: planned feature
                gameEngine.startMission(mission, selectedDisciples.map { it.toDisciple() })
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
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
        } catch (e: CancellationException) { throw e }
          catch (e: Exception) {
            Log.w(TAG, "stopGameLoop failed: ${e.message}")
        }
    }

    /** 打开年薪配置对话框，委托给 [NavigationDelegate] */
    fun openSalaryConfigDialog() = navigation.openSalaryConfigDialog()

    val isGameOver: StateFlow<Boolean> = gameEngine.gameData
        .map { it.isGameOver }
        .distinctUntilChanged()
        .stateIn(viewModelScope, sharingStarted, false)

    /** 打开游戏结束对话框，委托给 [NavigationDelegate] */
    fun openGameOverDialog() = navigation.openGameOverDialog()

    private val _showRedeemCodeDialog = MutableStateFlow(false)
    val showRedeemCodeDialog: StateFlow<Boolean> = _showRedeemCodeDialog.asStateFlow()

    private val _redeemResult = MutableStateFlow<RedeemResult?>(null)
    val redeemResult: StateFlow<RedeemResult?> = _redeemResult.asStateFlow()

    /** 打开兑换码对话框，并清空之前的兑换结果 */
    fun openRedeemCodeDialog() {
        _showRedeemCodeDialog.value = true
        _redeemResult.value = null
    }

    /** 关闭兑换码对话框，并清空兑换结果 */
    fun closeRedeemCodeDialog() {
        _showRedeemCodeDialog.value = false
        _redeemResult.value = null
    }

    /**
     * 兑换兑换码，结果写入 [_redeemResult] 并提示。
     *
     * @param code 兑换码字符串
     */
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
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                Log.e(TAG, "Error redeeming code", e)
                showError("兑换失败: ${e.message}")
            }
        }
    }

    /** 清空兑换结果状态 */
    fun clearRedeemResult() {
        _redeemResult.value = null
    }

    private val currentSlotId: Int get() = gameEngine.gameData.value?.slotId ?: 0

    val mails: StateFlow<List<MailEntity>> get() = mailService.activeMails

    val mailUnreadCount: StateFlow<Int> get() = mailService.unreadCount

    /**
     * 将指定邮件标记为已读。
     *
     * @param mailId 邮件 ID
     */
    fun markMailAsRead(mailId: String) {
        viewModelScope.launch {
            mailService.markAsRead(mailId)
        }
    }

    private val _mailRewardCards = MutableStateFlow<List<RewardCardItem>>(emptyList())
    val mailRewardCards: StateFlow<List<RewardCardItem>> = _mailRewardCards.asStateFlow()
    private val mailCardQueueMutex = Mutex()

    /**
     * 领取邮件附件，奖励卡片写入 [_mailRewardCards]。
     *
     * @param mailId 邮件 ID
     * @param onResult 结果回调，默认空实现
     */
    fun claimMailAttachment(mailId: String, onResult: (com.xianxia.sect.core.engine.service.ClaimResult) -> Unit = {}) {
        viewModelScope.launch {
            val result = mailService.claimAttachment(mailId, currentSlotId)
            if (result is ClaimResult.Success && result.cards.isNotEmpty()) {
                _mailRewardCards.value = result.cards
            }
            onResult(result)
        }
    }

    /**
     * 将当前存档所有邮件标记为已读，奖励卡片写入 [_mailRewardCards]。
     * 跳过项会通过 [showError] 提示首个跳过原因。
     */
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

    /** 将缓存的邮件奖励卡片入队签到服务（加锁保护并发） */
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

    /** 删除当前存档所有已读且已领取附件的邮件 */
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

    /**
     * 查询指定星期几对应的签到奖励。
     *
     * @param weekday 星期几（0-6）
     * @return 当日签到奖励
     */
    fun getRewardForWeekday(weekday: Int): DailySignInReward = dailySignInService.getRewardForWeekday(weekday)

    /**
     * 查询某日在当前签到状态下的展示状态。
     *
     * @param dayOfMonth 月内日期
     * @param signInState 当前签到状态
     * @return 该日的展示状态
     */
    fun getDayState(dayOfMonth: Int, signInState: SignInState): SignInDayState = dailySignInService.getDayState(dayOfMonth, signInState)

    /**
     * 获取当前月份的天数。
     *
     * @return 当月天数
     */
    fun getDaysInMonth(): Int = dailySignInService.getDaysInMonth()

    /**
     * 查询某日对应的星期几。
     *
     * @param dayOfMonth 月内日期
     * @return 星期几（0-6）
     */
    fun getWeekdayForDay(dayOfMonth: Int): Int = dailySignInService.getWeekdayForDay(dayOfMonth)

    private val _signInCapacityWarning = MutableStateFlow<String?>(null)
    val signInCapacityWarning: StateFlow<String?> = _signInCapacityWarning.asStateFlow()

    /** 关闭签到容量不足警告 */
    fun dismissCapacityWarning() {
        _signInCapacityWarning.value = null
    }

    /**
     * 领取每日签到奖励。
     * 成功时将奖励卡片入队；容量不足时写入 [_signInCapacityWarning]。
     */
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

    /**
     * 将弟子分配到指定住所槽位（先从其他槽位移除该弟子）。
     *
     * @param buildingInstanceId 住所建筑实例 ID
     * @param slotIndex 槽位序号
     * @param discipleId 弟子 ID
     */
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

    /**
     * 从指定住所槽位移除弟子。
     *
     * @param buildingInstanceId 住所建筑实例 ID
     * @param slotIndex 槽位序号
     */
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

    /**
     * 判断指定住所是否可升级（仅"单人住所"可升级）。
     *
     * @param buildingInstanceId 住所建筑实例 ID
     * @return true 表示可升级
     */
    fun canUpgradeResidence(buildingInstanceId: String): Boolean {
        val data = gameEngine.gameData.value ?: return false
        val building = data.placedBuildings.find { it.instanceId == buildingInstanceId } ?: return false
        return building.displayName == "单人住所"
    }

    /**
     * 将"单人住所"升级为"中级单人住所"，消耗 50000 灵石。
     *
     * @param buildingInstanceId 住所建筑实例 ID
     */
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

