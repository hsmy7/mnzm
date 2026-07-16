package com.xianxia.sect.ui.game

import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import com.xianxia.sect.core.util.DomainLog
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
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
import com.xianxia.sect.core.domain.dialog.DialogManager
import com.xianxia.sect.core.domain.dialog.DialogType
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
    private val thermalMonitor: ThermalMonitor,
    private val dialogManager: DialogManager          // ← NEW
) : BaseViewModel() {

    val planting = com.xianxia.sect.ui.game.delegate.PlantingDelegate(gameEngine, viewModelScope)
    val disciple = com.xianxia.sect.ui.game.delegate.DiscipleDelegate(gameEngine, viewModelScope)
    val navigation = com.xianxia.sect.ui.game.delegate.NavigationDelegate(
        gameEngine, gameEngineCore, viewModelScope,
        onNavigate = { _navigationEvents.trySend(it) }
    )
    val inventory = com.xianxia.sect.ui.game.delegate.InventoryDelegate(gameEngine, viewModelScope)

    // ── 新提取的 Delegate (Phase 2) ──
    val beastAttack = com.xianxia.sect.ui.game.delegate.BeastAttackDelegate(gameEngine, viewModelScope)
    val warnings = com.xianxia.sect.ui.game.delegate.WarningDelegate(gameEngine, viewModelScope)
    val buildingDelegate = com.xianxia.sect.ui.game.delegate.BuildingDelegate(
        gameEngine, buildingFacade, buildingConfigService, viewModelScope,
        onDemolishSuccess = { msg -> showSuccess(msg) }
    )
    val sectDelegate = com.xianxia.sect.ui.game.delegate.SectDelegate(
        gameEngine, viewModelScope,
        onShowSuccess = { msg -> showSuccess(msg) },
        onShowError = { msg -> showError(msg) },
        onNavigateToDialog = { route -> navigateToDialog(route) },
        onDismissDialog = { dismissDialog() }
    )
    val autoAssign = com.xianxia.sect.ui.game.delegate.AutoAssignDelegate(gameEngine, viewModelScope)

    companion object {
        private const val TAG = "GameViewModel"

        /** 广告冷却时间（毫秒） */
        private const val AD_COOLDOWN_MS = 60_000L
    }

    /** 广告冷却截止时间戳（System.currentTimeMillis），在此时间之前不可播放广告 */
    private var adCooldownUntilMs: Long = 0L

    /** 广告是否在冷却中 */
    fun isAdOnCooldown(): Boolean = System.currentTimeMillis() < adCooldownUntilMs

    /** 标记广告已观看，进入冷却 */
    fun markAdWatched() {
        adCooldownUntilMs = System.currentTimeMillis() + AD_COOLDOWN_MS
    }



    // ── Dialog 状态管理（通过 DialogManager） ──

    /** NavigationDelegate 通过此 Channel 发送导航事件 */
    private val _navigationEvents = Channel<GameRoute>(Channel.BUFFERED)
    val navigationEvents: Flow<GameRoute> = _navigationEvents.receiveAsFlow()

    private val _dialogOpenTrigger = MutableSharedFlow<Unit>(replay = 0)

    /** 当前对话框类型（由 DialogManager 驱动，直接映射 DialogType） */
    val currentDialogType: StateFlow<DialogType> = dialogManager.currentDialog
        .map { entry -> entry?.type ?: DialogType.None }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DialogType.None)

    /**
     * 打开指定对话框 — 委托给 DialogManager。
     */
    fun navigateToDialog(type: DialogType) {
        if (type is DialogType.None) return  // None 不打开任何对话框
        // 先设置引擎状态，再打开 UI（失败安全：引擎若抛异常则 UI 不动）
        gameEngine.setActiveDialog(type.domainKey)
        dialogManager.open(type)
        _dialogOpenTrigger.tryEmit(Unit)
    }

    /** 通知引擎有用户交互 — 防止拖动地图等触控操作被误判为空闲 */
    fun onUserInteraction() {
        gameEngine.notifyUserInteraction()
    }

    /** 引擎渲染帧率（热控+场景综合），供 NativeSurfaceView 帧率控制 */
    val renderFrameRate: StateFlow<Int> = gameEngineCore.renderFrameRate

    /** 设置游戏场景（GAMEPLAY=60fps, MAP_SCROLL=30fps, IDLE=10fps） */
    fun setGameScene(scene: GameEngineCore.GameScene) {
        gameEngineCore.onSceneChanged(scene)
    }

    /** 关闭当前对话框，将路由重置为 None 并清空引擎侧激活状态 */
    fun dismissDialog() {
        // 先清除引擎状态，再关闭 UI（失败安全）
        gameEngine.setActiveDialog(null)
        dialogManager.close()
        _dialogOpenTrigger.tryEmit(Unit)
    }

    /**
     * 激活子界面焦点域。子界面进入组合时调用，
     * 委托给 [GameEngine.pushSubDialogDomain]。
     */
    fun activateSubDialogDomain(domainName: String) {
        gameEngine.pushSubDialogDomain(domainName)
    }

    /**
     * 停用子界面焦点域。子界面离开组合时调用，
     * 委托给 [GameEngine.popSubDialogDomain]。
     */
    fun deactivateSubDialogDomain(domainName: String) {
        gameEngine.popSubDialogDomain(domainName)
    }

    /** 通知引擎有用户交互（与 [onUserInteraction] 等价，保留以兼容旧调用点） */
    fun notifyUserInteraction() {
        gameEngine.notifyUserInteraction()
    }

    init {
        // 系统异常收集 — 运行在 Default 调度器上，避免 BufferedChannel.hasNext()
        // 在主线程上挂起导致 ANR（见 Bugly #5011）。
        viewModelScope.launch(Dispatchers.Default) {
            systemManager.errors.collect { error ->
                val msg = error.error.stackTraceToString()
                Log.e(TAG, "System error in ${error.systemName} (${error.tickType}): $msg")
                // 主动上报 Bugly，避免已捕获异常不可见
                try {
                    val crashReport = Class.forName("com.tencent.bugly.crashreport.CrashReport")
                    crashReport.getMethod("postCatchedException", Throwable::class.java)
                        .invoke(null, error.error)
                } catch (e: Exception) {
                    Log.w(TAG, "Bugly not available, skipping postCatchedException", e)
                }
                showError("系统异常：${error.systemName}")
            }
        }

        // 主线程健康监控：检测游戏循环是否被 OEM 电源管理挂起
        // 当 HyperOS 等挂起后台线程后，此协程在主线程上运行不受影响，
        // 可检测 tickCount 停滞并通过 emergencyRestartGameLoop 恢复。
        launchMainThreadHealthCheck()
    }

    /**
     * 在主线程上定期检查 tickCount 是否推进。若 6 秒无推进且游戏循环声称
     * 运行中，触发紧急重启（创建全新调度器线程绕过 OEM 挂起）。
     */
    private fun launchMainThreadHealthCheck() {
        viewModelScope.launch(Dispatchers.Main) {
            var lastTick = 0L
            var stallCount = 0
            while (isActive) {
                delay(1000)
                try {
                    val currentTick = gameEngineCore.tickCount.value
                    // ★ 暂停中 tick 不推进属正常行为，不触发紧急重启
                    if (gameEngineCore.isPausedDirect) {
                        stallCount = 0
                        lastTick = currentTick
                        continue
                    }
                    if (currentTick == lastTick && gameEngineCore.isGameLoopRunning) {
                        stallCount++
                        if (stallCount >= 3) {
                            Log.w(TAG, "HealthCheck: game loop stalled for ${stallCount}s, emergency restarting")
                            gameEngineCore.emergencyRestartGameLoop()
                            stallCount = 0
                        }
                    } else {
                        stallCount = 0
                    }
                    lastTick = currentTick
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    DomainLog.e(TAG, "HealthCheck: error", e)
                }
            }
        }
    }

    /**
     * 关闭当前对话框 — 现已统一通过 [dismissDialog] 关闭。
     * @deprecated 直接调用 [dismissDialog] 替代
     */
    @Deprecated("Use dismissDialog() instead", ReplaceWith("dismissDialog()"), level = DeprecationLevel.ERROR)
    fun closeCurrentDialog() {
        dismissDialog()
    }

    /**
     * 关闭所有对话框 — 当前行为等价于 [dismissDialog]（无栈）。
     * @deprecated 直接调用 [dismissDialog] 替代
     */
    @Deprecated("Use dismissDialog() instead", ReplaceWith("dismissDialog()"), level = DeprecationLevel.ERROR)
    fun closeAllDialogs() {
        dismissDialog()
    }

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

    /** @see [BeastAttackDelegate.resolveBeastAttackPayTribute] */
    suspend fun resolveBeastAttackPayTribute(beastLevelId: String) = beastAttack.resolveBeastAttackPayTribute(beastLevelId)

    /** @see [BeastAttackDelegate.resolveBeastAttackFight] */
    fun resolveBeastAttackFight(beastLevelId: String) = beastAttack.resolveBeastAttackFight(beastLevelId)

    /** @see [BeastAttackDelegate.clearPendingBeastAttacks] */
    fun clearPendingBeastAttacks() = beastAttack.clearPendingBeastAttacks()

    /** @see [WarningDelegate.attackWarnings] */
    val attackWarnings: StateFlow<List<AttackWarning>> get() = warnings.attackWarnings

    /** @see [WarningDelegate.shownWarningStageIds] */
    val shownWarningStageIds: StateFlow<List<String>> get() = warnings.shownWarningStageIds

    /** @see [WarningDelegate.resolveAttackWarningAppease] */
    fun resolveAttackWarningAppease(sectId: String) = warnings.resolveAttackWarningAppease(sectId)

    /** @see [WarningDelegate.resolveAttackWarningVassal] */
    fun resolveAttackWarningVassal(sectId: String) = warnings.resolveAttackWarningVassal(sectId)

    /** @see [WarningDelegate.markWarningStageShown] */
    fun markWarningStageShown(stageKey: String) = warnings.markWarningStageShown(stageKey)

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
    /** @see [BuildingDelegate.placeBuilding] */
    fun placeBuilding(name: String, gridX: Int, gridY: Int, width: Int = 2, height: Int = 3) =
        buildingDelegate.placeBuilding(name, gridX, gridY, width, height)

    /** @see [BuildingDelegate.getBuildingCost] */
    fun getBuildingCost(displayName: String): Long = buildingDelegate.getBuildingCost(displayName)

    /** @see [BuildingDelegate.getBuildingGridSize] */
    fun getBuildingGridSize(displayName: String): Pair<Int, Int> = buildingDelegate.getBuildingGridSize(displayName)

    /** @see [BuildingDelegate.getBuildingSpriteSize] */
    fun getBuildingSpriteSize(displayName: String): Pair<Int, Int> = buildingDelegate.getBuildingSpriteSize(displayName)

    /** @see [BuildingDelegate.getAllBuildingSpriteSizes] */
    fun getAllBuildingSpriteSizes(): Map<String, Pair<Int, Int>> = buildingDelegate.getAllBuildingSpriteSizes()

    /** @see [BuildingDelegate.batchPlaceBuilding] */
    fun batchPlaceBuilding(goldFingerState: com.xianxia.sect.ui.game.sect.GoldFingerState) =
        buildingDelegate.batchPlaceBuilding(goldFingerState)

    /** @see [BuildingDelegate.moveBuilding] */
    suspend fun moveBuilding(instanceId: String, newGridX: Int, newGridY: Int) =
        buildingDelegate.moveBuilding(instanceId, newGridX, newGridY)

    /** @see [BuildingDelegate.demolishBuilding] */
    fun demolishBuilding(instanceId: String) = buildingDelegate.demolishBuilding(instanceId)

    /** @see [BuildingDelegate.fixupBuildingSizesIfNeeded] */
    fun fixupBuildingSizesIfNeeded() = buildingDelegate.fixupBuildingSizesIfNeeded()
            // (已提取到 BuildingDelegate)

    val gameData: StateFlow<GameData> get() = gameEngine.gameData

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    // 运行在 Default 调度器上，使 sample(100) 内部的 BufferedChannel
    // 不在主线程上挂起（见 Bugly #3042/#8024）。
    val gameDataUi: StateFlow<GameData> = merge(
        gameEngine.gameData.sample(100),
        _dialogOpenTrigger.map { gameEngine.gameData.value }
    ).flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, sharingStarted, gameEngine.gameData.value)

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

    /** @see [SectDelegate.navigateToSectLevelDetail] */
    fun navigateToSectLevelDetail() = sectDelegate.navigateToSectLevelDetail()

    /** @see [SectDelegate.renameSect] */
    fun renameSect(newName: String) = sectDelegate.renameSect(newName)

    /** @see [SectDelegate.claimSectLevelReward] */
    fun claimSectLevelReward(level: Int) = sectDelegate.claimSectLevelReward(level)

    /** @see [SectDelegate.upgradeSectLevel] */
    fun upgradeSectLevel() = sectDelegate.upgradeSectLevel()

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

    /** 修改弟子名称，委托给 [DiscipleDelegate] */
    fun renameDisciple(discipleId: String, newName: String) = disciple.renameDisciple(discipleId, newName)

    /** 获取弟子日志事件列表 */
    fun getLifeEvents(discipleId: String): List<String> =
        discipleFacade.getLifeEvents(discipleId)

    /** 初始化弟子日志（生成合成历史事件，仅当尚无日志时生效） */
    fun initializeLifeEvents(discipleId: String) =
        discipleFacade.initializeLifeEvents(discipleId)

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
    /** @see [AutoAssignDelegate.setDaoCompanionBannedRootCounts] */
    fun setDaoCompanionBannedRootCounts(counts: Set<Int>) = autoAssign.setDaoCompanionBannedRootCounts(counts)

    /**
     * 设置道侣结成是否需要玩家同意。
     *
     * @param required true 表示需要玩家同意
     */
    /** @see [AutoAssignDelegate.setDaoCompanionConsentRequired] */
    fun setDaoCompanionConsentRequired(required: Boolean) = autoAssign.setDaoCompanionConsentRequired(required)

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
    /** @see [AutoAssignDelegate.setAutoAssignSettings] */
    fun setAutoAssignSettings(
        mineFocused: Boolean, mineRootCounts: List<Int>, mineThreshold: Int,
        alchemyFocused: Boolean, alchemyRootCounts: List<Int>, alchemyThreshold: Int,
        forgeFocused: Boolean, forgeRootCounts: List<Int>, forgeThreshold: Int
    ) = autoAssign.setAutoAssignSettings(mineFocused, mineRootCounts, mineThreshold,
        alchemyFocused, alchemyRootCounts, alchemyThreshold,
        forgeFocused, forgeRootCounts, forgeThreshold)

    /**
     * 设置突破时自动使用丹药的策略。
     *
     * @param focused 是否聚焦
     * @param rootCounts 允许自动使用丹药的灵根数集合
     */
    /** @see [AutoAssignDelegate.setBreakthroughAutoPillSettings] */
    fun setBreakthroughAutoPillSettings(focused: Boolean, rootCounts: Set<Int>) =
        autoAssign.setBreakthroughAutoPillSettings(focused, rootCounts)

    /**
     * 设置自动从仓库装备的策略。
     *
     * @param focused 是否聚焦
     * @param rootCounts 允许自动装备的灵根数集合
     */
    /** @see [AutoAssignDelegate.setAutoEquipSettings] */
    fun setAutoEquipSettings(focused: Boolean, rootCounts: Set<Int>) = autoAssign.setAutoEquipSettings(focused, rootCounts)

    /**
     * 设置自动从仓库学习功法的策略。
     *
     * @param focused 是否聚焦
     * @param rootCounts 允许自动学习的灵根数集合
     */
    /** @see [AutoAssignDelegate.setAutoLearnSettings] */
    fun setAutoLearnSettings(focused: Boolean, rootCounts: Set<Int>) = autoAssign.setAutoLearnSettings(focused, rootCounts)

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
     * 设置弟子脱离宗门弹窗开关。
     *
     * @param enabled 是否弹窗
     */
    fun setDiscipleDesertionPopup(enabled: Boolean) {
        viewModelScope.launch {
            gameEngine.updateGameData {
                it.copy(discipleDesertionPopup = enabled)
            }
        }
    }

    /**
     * 设置弟子选择界面"显示所有可用弟子"的开关。
     * 勾选后选择界面将同时显示非空闲弟子（但始终排除思过/任务/战斗中弟子），
     * 选中非空闲弟子时将自动释放其原槽位。
     *
     * @param enabled 是否显示所有可用弟子（而非仅空闲中）
     */
    fun setShowAllAvailableDisciples(enabled: Boolean) {
        viewModelScope.launch {
            gameEngine.updateGameData {
                it.copy(showAllAvailableDisciples = enabled)
            }
        }
    }

    /**
     * 从所有槽位原子性地释放指定弟子，将其状态重置为 IDLE。
     * 用于"显示所有可用弟子"功能中选中非空闲弟子时的自动释放。
     *
     * 注意：血炼中(REFINING)弟子被释放时，血炼已消耗的灵石和材料不返还。
     */
    /**
     * 释放指定弟子所有槽位引用并重置为空闲（用于"显示所有可用弟子"功能）。
     * 调用方需在协程作用域中调用（scope.launch { viewModel.releaseDiscipleFromAllSlotsAtomic(id) }）。
     */
    suspend fun releaseDiscipleFromAllSlotsAtomic(discipleId: String) {
        gameEngine.releaseDiscipleFromAllSlotsAtomic(discipleId)
    }

    /** 当前"显示所有可用弟子"开关状态 */
    val showAllAvailableDisciplesSnapshot: Boolean
        get() = gameEngine.gameDataSnapshot?.showAllAvailableDisciples ?: false

    /** 当前战斗中/探索中弟子 ID 集合（供 filterByDiscipleStatus 排除使用） */
    val battleAndExplorationIdsSnapshot: Set<String>
        get() {
            val data = gameEngine.gameDataSnapshot ?: return emptySet()
            val battleIds = data.battleTeams.flatMap { t ->
                t.slots.mapNotNull { s -> s.discipleId.takeIf(String::isNotEmpty) }
            }
            val explorationIds = gameEngine.teams.value?.flatMap { it.memberIds } ?: emptyList()
            return (battleIds + explorationIds).toSet()
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
        val allRewards = mutableListOf<BattleRewardItem>()
        val allCards = mutableListOf<RewardCardItem>()
        while (true) {
            val bag = storageBags.value.find { it.id == bagId } ?: break
            if (bag.quantity <= 0) break
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

    // ═══════════════════════════
    // 交谈效果
    // ═══════════════════════════

    /** 获取弟子上次交谈年份，null 表示从未交谈获得效果 */
    fun getLastChatYear(discipleId: String): Int? = disciple.getLastChatYear(discipleId)

    /** 应用交谈效果并记录冷却年份 */
    fun applyConversationEffects(
        discipleId: String,
        currentYear: Int,
        moralityDelta: Int,
        loyaltyDelta: Int,
        cultivationDelta: Double,
        intelligenceDelta: Int
    ) = disciple.applyConversationEffects(
        discipleId, currentYear,
        moralityDelta, loyaltyDelta, cultivationDelta, intelligenceDelta
    )

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
    /** @see [BuildingDelegate.assignToResidence] */
    fun assignToResidence(buildingInstanceId: String, slotIndex: Int, discipleId: String) =
        buildingDelegate.assignToResidence(buildingInstanceId, slotIndex, discipleId)

    /** @see [BuildingDelegate.removeFromResidence] */
    fun removeFromResidence(buildingInstanceId: String, slotIndex: Int) =
        buildingDelegate.removeFromResidence(buildingInstanceId, slotIndex)

    /** @see [BuildingDelegate.canUpgradeResidence] */
    fun canUpgradeResidence(buildingInstanceId: String): Boolean =
        buildingDelegate.canUpgradeResidence(buildingInstanceId)

    /** @see [BuildingDelegate.upgradeSingleResidence] */
    fun upgradeSingleResidence(buildingInstanceId: String) =
        buildingDelegate.upgradeSingleResidence(buildingInstanceId)

    // endregion

    override fun onCleared() {
        Log.i(TAG, "GameViewModel cleared, stopping game loop and releasing resources")
        // Activity 销毁时清除对话框状态，防止 @Singleton DialogManager 跨生命周期残留
        dialogManager.close()
        gameEngine.setActiveDialog(null)
        clearResources()
        super.onCleared()
    }

    // ── 平滑动画驱动（R20） ──

    /** 修炼进度平滑动画值（60fps 过渡） */
    private val _cultivationProgress = MutableStateFlow(0f)
    val cultivationProgress: StateFlow<Float> = _cultivationProgress.asStateFlow()

    /** 从游戏 tick 更新修炼进度（值突变），由 Animatable 平滑过渡到 UI */
    fun updateCultivationProgress(target: Float) {
        viewModelScope.launch {
            // 渐变动画：每次更新在 100ms 内平滑过渡到目标值
            val current = _cultivationProgress.value
            val diff = target - current
            val steps = 6   // 100ms / 16ms ≈ 6 帧
            for (i in 1..steps) {
                _cultivationProgress.value = current + diff * i / steps
                delay(16)
            }
            _cultivationProgress.value = target  // 最终对齐
        }
    }

    /** 旬进度条插值因子（由 R1 frame-driven 循环提供） */
    @Volatile
    var interpolationFactor: Float = 0f
        private set

    /** 由 GameEngineCore 每帧更新 */
    fun updateInterpolationFactor(alpha: Float) {
        interpolationFactor = alpha
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

