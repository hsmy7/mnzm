package com.xianxia.sect.ui.game

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import com.xianxia.sect.core.domain.dialog.DialogType
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.SectLevel
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.FixedSectGateway
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.PerformanceMode
import com.xianxia.sect.core.engine.clearPendingNotification
import com.xianxia.sect.core.engine.enterSect
import com.xianxia.sect.core.engine.notifyUserInteraction
import com.xianxia.sect.core.engine.popSubDialogDomain
import com.xianxia.sect.core.engine.pushSubDialogDomain
import com.xianxia.sect.core.engine.setActiveDialog
import com.xianxia.sect.core.engine.service.JadeSymbolRuntimeState
import com.xianxia.sect.core.engine.service.HighFrequencyData
import com.xianxia.sect.core.config.SectLevelRewardCooldown
import com.xianxia.sect.core.engine.system.SystemWallClock
import com.xianxia.sect.core.engine.system.WallClock
import com.xianxia.sect.ui.game.sect.RenderCommandBus
import com.xianxia.sect.ui.game.sect.SurfaceProviderFactory
import com.xianxia.sect.core.util.GridSnapHelper
import com.xianxia.sect.core.model.AlchemySlot
import com.xianxia.sect.core.model.AlchemySlotStatus
import com.xianxia.sect.core.model.Alliance
import com.xianxia.sect.core.model.AttackWarning
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.ForgeRecipe
import com.xianxia.sect.core.model.ForgeSlot
import com.xianxia.sect.core.model.ForgeSlotStatus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GameEventRecord
import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.MailEntity
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.RedeemResult
import com.xianxia.sect.core.model.ResidenceSlot
import com.xianxia.sect.core.model.RewardCardItem
import com.xianxia.sect.core.model.SectPolicies
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.StorageBag
import com.xianxia.sect.core.model.WorldMapRenderData
import com.xianxia.sect.core.model.YearlyReport
import com.xianxia.sect.core.model.spiritStones
import com.xianxia.sect.core.model.production.BuildingType
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.model.production.ProductionSlotStatus
import com.xianxia.sect.core.perf.GpuTier
import com.xianxia.sect.core.render.ClarityMode
import com.xianxia.sect.core.perf.ThermalState
import com.xianxia.sect.core.registry.ForgeRecipeDatabase
import com.xianxia.sect.core.state.BattleResultUIData
import com.xianxia.sect.core.state.GameNotification
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.PendingBeastAttack
import com.xianxia.sect.core.state.PendingMarriageProposal
import com.xianxia.sect.ui.game.delegate.AdsDelegate
import com.xianxia.sect.ui.game.delegate.PlantingDelegate
import com.xianxia.sect.ui.game.delegate.AutoAssignDelegate
import com.xianxia.sect.ui.game.delegate.BagDelegate
import com.xianxia.sect.ui.game.delegate.BeastAttackDelegate
import com.xianxia.sect.ui.game.delegate.BuildingDelegate
import com.xianxia.sect.ui.game.delegate.BuildingUpgradeDelegate
import com.xianxia.sect.ui.game.delegate.BattleRewardDelegate
import com.xianxia.sect.ui.game.delegate.LifeEventsDelegate
import com.xianxia.sect.ui.game.delegate.MerchantOpsDelegate
import com.xianxia.sect.ui.game.delegate.MissionDelegate
import com.xianxia.sect.ui.game.delegate.RoadDelegate
import com.xianxia.sect.ui.game.delegate.DiscipleDelegate
import com.xianxia.sect.ui.game.delegate.GameLoopDelegate
import com.xianxia.sect.ui.game.delegate.GuideDelegate
import com.xianxia.sect.ui.game.delegate.InventoryDelegate
import com.xianxia.sect.ui.game.delegate.MailDelegate
import com.xianxia.sect.ui.game.delegate.NavigationDelegate
import com.xianxia.sect.ui.game.delegate.OverlayDelegate
import com.xianxia.sect.ui.game.delegate.RedeemCodeDelegate
import com.xianxia.sect.ui.game.delegate.SectDelegate
import com.xianxia.sect.ui.game.delegate.SettingsDelegate
import com.xianxia.sect.ui.game.delegate.WarningDelegate
import com.xianxia.sect.ui.navigation.GameRoute
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import com.xianxia.sect.core.engine.onSceneChanged
import com.xianxia.sect.core.engine.setClarityMode
import com.xianxia.sect.core.engine.setPerformanceMode

@HiltViewModel
class GameViewModel @Inject constructor(
    private val gameEngine: GameEngine,
    private val audioServices: GameVmAudioServices,
    private val coreServices: GameVmCoreServices,
    private val uiServices: GameVmUiServices,
    private val delegateServices: GameVmDelegateServices,
    private val surfaceProviderFactory: SurfaceProviderFactory,
    /**
     * 游戏语义墙钟（SR-5）：周奖励徽章判据取时。默认 [SystemWallClock] 供测试直构，
     * 生产由 Hilt 注入 CalibratedWallClock；判据本体与引擎闸门同源
     * （[SectLevelRewardCooldown]）。
     */
    private val wallClock: WallClock = SystemWallClock
) : BaseViewModel() {

    /**
     * 邮件过期文案的取时点（SR-5）：UI 侧不裸读系统钟，与引擎过期判据共用同一
     * 注入墙钟实例，避免"引擎未过期、列表显示已过期"的分歧。
     */
    fun mailDisplayNowMs(): Long = wallClock.currentTimeMillis()

    // ── 新提取的领域委托 ──

    val ads = AdsDelegate(uiServices.adService, gameEngine)
    val overlays = OverlayDelegate(gameEngine)
    val bag = BagDelegate(
        gameEngine,
        dispatcher = delegateServices.ioDispatcher.dispatcher
    )
    val redeem = RedeemCodeDelegate(
        gameEngine, ::showSuccess, ::showError,
        onCapacityWarning = { msg -> showCapacityWarning(msg) }
    )
    val mail = MailDelegate(gameEngine, delegateServices.mailService, ::showError)
    val gameLoop = GameLoopDelegate(
        gameEngine, coreServices.gameEngineCore, coreServices.systemManager, viewModelScope, ::showError
    )
    val settings = SettingsDelegate(gameEngine, delegateServices.discipleFacade, audioServices.audioConfig)

    // ── 既有领域委托 ──

    val planting = PlantingDelegate(gameEngine)
    val disciple = DiscipleDelegate(
        gameEngine,
        dispatcher = delegateServices.ioDispatcher.dispatcher,
        // 招募被拦截时可见化（防抖/重复点击等不再静默；与招募失败弹窗同语义）
        onRecruitBlocked = { reason -> showError(reason) }
    )
    val navigation = NavigationDelegate(
        gameEngine, coreServices.gameEngineCore,
        onNavigate = { _navigationEvents.trySend(it) }
    )
    val inventory = InventoryDelegate(gameEngine)
    val beastAttack = BeastAttackDelegate(
        gameEngine, dispatcher = delegateServices.ioDispatcher.dispatcher,
        onMessage = { message, isError ->
            if (isError) showError(message) else showSuccess(message)
        }
    )
    val warnings = WarningDelegate(gameEngine, viewModelScope)
    val buildingDelegate = BuildingDelegate(
        gameEngine, delegateServices.buildingFacade, delegateServices.buildingConfigService,
        dispatcher = delegateServices.ioDispatcher.dispatcher,
        onDemolishSuccess = { msg -> showSuccess(msg) }
    )
    val buildingUpgradeDelegate = BuildingUpgradeDelegate(
        gameEngine,
        onUpgradeSuccess = { msg -> showSuccess(msg) },
        onUpgradeError = { msg -> showError(msg) }
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
    val guide = GuideDelegate(gameEngine)
    val road = RoadDelegate(gameEngine, ::showError)
    val merchant = MerchantOpsDelegate(gameEngine, onSuccess = ::showSuccess, onError = ::showError)
    val battleRewards = BattleRewardDelegate(gameEngine)
    val mission = MissionDelegate(gameEngine, onError = ::showError)
    val lifeEvents = LifeEventsDelegate(gameEngine, delegateServices.discipleFacade)

    // 引导任务已领取奖励的ID集合
    val guideClaimedRewardIds: StateFlow<Set<Int>> = gameData
        .map { it.guideClaimedRewardIds }
        .distinctUntilChanged()
        .stateIn(viewModelScope, sharingStarted, gameData.value.guideClaimedRewardIds)

    // 已关注物品键集合（由 gameData.watchedItemIds 派生，键格式 "type:name"）
    val watchedItemIds: StateFlow<Set<String>> = gameData
        .map { it.watchedItemIds.toSet() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, sharingStarted, gameData.value.watchedItemIds.toSet())

    /**
     * 灵石三品阶总量（仓库页窄流数据）。
     *
     * R2.3 第二波逐块迁移·块①「资源头部」：来源由整份 gameData 快照 map 换成
     * [GameEngine.resourcesHeader] 投影流——镜像未按封触及头部时不产生新值，
     * UI 消费语义与取值逐字段不变（两臂共用同一视图构造函数）。
     */
    val spiritStoneTotals: StateFlow<SpiritStoneTotals> = gameEngine.resourcesHeader
        .map { SpiritStoneTotals(it.spiritStones, it.midGradeSpiritStones, it.highGradeSpiritStones) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, sharingStarted, SpiritStoneTotals(0, 0, 0))

    companion object {
        private const val TAG = "GameViewModel"
    }

    /** 灵石三品阶总量（低/中/高），仓库页窄流数据 */
    @Immutable
    data class SpiritStoneTotals(
        val low: Long,
        val mid: Long,
        val high: Long
    )

    // ── Dialog 状态管理 ──

    private val _navigationEvents = Channel<GameRoute>(Channel.BUFFERED)
    val navigationEvents: Flow<GameRoute> = _navigationEvents.receiveAsFlow()

    private val _dialogOpenTrigger = MutableSharedFlow<Unit>(replay = 0)

    val currentDialogType: StateFlow<DialogType> = uiServices.dialogManager.currentDialog
        .map { entry -> entry?.type ?: DialogType.None }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DialogType.None)

    /**
     * 玉符运行时状态（1Hz 节流流；徽章数量与对话框红色倒计时的订阅源）。
     * 源已 StateFlow + 1Hz 节流，无需 sample/distinctUntilChanged。
     */
    val jadeSymbolState: StateFlow<JadeSymbolRuntimeState> = gameEngine.jadeSymbolState
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            JadeSymbolRuntimeState(
                total = 0, today = 0,
                remainingMs = GameConfig.Jade.INTERVAL_MS, capped = false
            )
        )

    fun navigateToDialog(type: DialogType) {
        if (type is DialogType.None) return
        gameEngine.setActiveDialog(type.domainKey)
        uiServices.dialogManager.open(type)
        _dialogOpenTrigger.tryEmit(Unit)
    }

    fun onUserInteraction() { gameEngine.notifyUserInteraction() }

    val renderFrameRate: StateFlow<Int> = coreServices.gameEngineCore.renderFrameRate

    /** 渲染质量因子（热控 + 节能模式低画质），供 SoftwareCanvasBackend 消费 */
    val renderingQualityFactor: StateFlow<Float> = coreServices.gameEngineCore.renderingQualityFactor

    /** 装饰层关闭标志（热控降级 + 节能模式），供 SoftwareCanvasBackend 消费 */
    val decorationsDisabled: StateFlow<Boolean> = coreServices.gameEngineCore.decorationsDisabled

    /** 引擎核心访问器（渲染线程 fps 反馈等跨层接线用） */
    val gameEngineCore: GameEngineCore get() = coreServices.gameEngineCore

    // ── 渲染命令总线（建筑数据直达推送，绕过 Compose 帧率门控） ──

    private val _renderCommandBus = RenderCommandBus()

    /** 获取渲染命令总线实例（由 MainGameScreen 注入到 NativeSurfaceView） */
    fun getRenderCommandBus(): RenderCommandBus = _renderCommandBus

    /**
     * 获取平台 surface 提供者工厂（Hilt 注入；由 MainGameScreen 组装进
     * [SectMapViewportParams]，替换 NativeSurfaceView 默认 surfaceProvider）。
     */
    fun getSurfaceProviderFactory(): SurfaceProviderFactory = surfaceProviderFactory

    /**
     * 获取 GPU 能力档位（RenderScalePolicy 决策输入）。
     * GpuTierDetector 首次调用执行检测（EGL 查询，~几十 ms），后续 O(1) 缓存返回。
     */
    fun getGpuTier(): GpuTier = delegateServices.gpuTierDetector.detect()

    /** 建筑精灵尺寸缓存（运行时不变，供直达推送协程使用） */
    private val _buildingSpriteSizesCache: Map<String, GridSnapHelper.BuildingSize> by lazy {
        buildingDelegate.getAllBuildingSpriteSizes().mapValues {
            GridSnapHelper.BuildingSize(it.value.first, it.value.second)
        }
    }

    fun setGameScene(scene: GameEngineCore.GameScene) { coreServices.gameEngineCore.onSceneChanged(scene) }

    // ── 性能模式（三档：节能/均衡/性能，设备级持久化） ──

    private val _performanceMode = MutableStateFlow(
        PerformanceMode.fromStorage(delegateServices.sessionManager.performanceMode)
    )
    val performanceMode: StateFlow<PerformanceMode> = _performanceMode.asStateFlow()

    /** 系统 GameMode 覆盖（BATTERY→节能/PERFORMANCE→性能）；用户手动选择时清除 */
    @Volatile
    private var systemModeOverride: PerformanceMode? = null

    /**
     * 用户手动设置性能模式：写入引擎 + 设备级持久化 + UI 状态，并清除系统覆盖。
     * 无条件同步（幂等写）——覆盖期间 UI 显示的档位可能来自系统，点击需真正生效。
     */
    fun setPerformanceMode(mode: PerformanceMode) {
        systemModeOverride = null
        coreServices.gameEngineCore.setPerformanceMode(mode)
        delegateServices.sessionManager.performanceMode = mode.name
        _performanceMode.value = mode
    }

    /**
     * 系统 GameMode 覆盖（Android 12+ 省电/性能模式）。
     * 只改引擎与 UI 显示，**不写持久化**（玩家显式选择优先，重启后恢复用户档）；
     * 玩家在设置界面手动选择任意档位时自动清除覆盖。
     *
     * @param mode 系统映射的模式（null = 系统无覆盖，恢复用户档）
     */
    fun setSystemGameModeOverride(mode: PerformanceMode?) {
        if (mode == null) {
            if (systemModeOverride != null) {
                systemModeOverride = null
                val userMode = PerformanceMode.fromStorage(delegateServices.sessionManager.performanceMode)
                coreServices.gameEngineCore.setPerformanceMode(userMode)
                _performanceMode.value = userMode
            }
            return
        }
        if (systemModeOverride == mode) return
        systemModeOverride = mode
        coreServices.gameEngineCore.setPerformanceMode(mode)
        _performanceMode.value = mode
    }

    // ── 自选清晰度（五档：极低/低/中/高/极高，设备级持久化，默认中） ──

    private val _clarityMode = MutableStateFlow(
        ClarityMode.fromStorage(delegateServices.sessionManager.clarityMode)
    )
    val clarityMode: StateFlow<ClarityMode> = _clarityMode.asStateFlow()

    /** 用户设置自选清晰度：写入引擎（重算渲染质量）+ 设备级持久化 + UI 状态。 */
    fun setClarityMode(mode: ClarityMode) {
        coreServices.gameEngineCore.setClarityMode(mode)
        delegateServices.sessionManager.clarityMode = mode.name
        _clarityMode.value = mode
    }

    /** 移动中建筑实例 ID 通道：总线渲染排除与 Compose 交互索引同源。 */
    private val _movingBuildingInstanceId = MutableStateFlow<String?>(null)

    /** B2 一次性诊断标记：activeSectId 失配已记录（防日志刷屏） */
    private var sectMismatchWarned = false

    /**
     * 设置/清除正在移动（拖拽中或等待确认）的建筑实例 ID。
     *
     * MainGameScreen 把该建筑从点击索引/占用检测临时排除（effectivePlacedBuildings），
     * 总线若不排除会继续渲染旧位置 → 拖拽窗口期双渲染 + 该建筑点不中 + 其格子可叠建。
     */
    fun setMovingBuildingInstanceId(instanceId: String?) {
        _movingBuildingInstanceId.value = instanceId
    }

    init {
        // 启动同步：引擎帧率策略与持久化设置对齐（GameActivity 生命周期顺序不依赖）
        coreServices.gameEngineCore.setPerformanceMode(_performanceMode.value)

        // 建筑数据直达推送（绕过 Compose 反应式管线 + 帧率门控）
        // 总线必须与 MainGameScreen 的点击索引/瓦片标记同源——
        // 只推送 activeSectId 匹配的建筑（placedBuildings 是跨宗门全局列表，enterSect 只改
        // activeSectId），否则非活跃宗门的建筑被渲染出来但不可点击、拆除模式无法选中。
        // distinctUntilChanged 的键必须是 (activeSectId, placedBuildings, movingId) 三元组：
        // enterSect 切换宗门后 placedBuildings 不变，单键无法触发重推；movingId 变化
        // （拖拽开始/确认/取消）同样需要重推以保持与 Compose 交互索引同源。
        viewModelScope.launch {
            combine(gameEngine.gameData, _movingBuildingInstanceId) { gd, movingId ->
                Triple(gd.activeSectId, gd.placedBuildings, movingId)
            }
                .distinctUntilChanged()
                .collect { (activeSectId, allBuildings, movingId) ->
                    // 与 MainGameScreen 点击/瓦片/渲染帧使用同一同源谓词
                    // buildingsInSectScope（只保留 activeSectId 作用域建筑），
                    // 移动中建筑额外排除（与 effectivePlacedBuildings 语义一致）
                    val buildings = buildingsInSectScope(allBuildings, activeSectId)
                        .filter { it.instanceId != movingId }
                    // B2 一次性诊断：activeSectId 非空但该宗门建筑 0 且存在本宗(sectId="")建筑 →
                    // 会话内 sectId 失配（boot 归一化在 worldSects 为空时会跳过）
                    if (!sectMismatchWarned && activeSectId.isNotEmpty() && buildings.isEmpty()) {
                        val homeCount = allBuildings.count { it.sectId.isEmpty() }
                        if (homeCount > 0) {
                            sectMismatchWarned = true
                            DomainLog.w(
                                TAG,
                                "R2 疑似 sectId 失配: activeSectId=\"$activeSectId\" 该宗门建筑=${buildings.size} " +
                                    "本宗建筑=$homeCount 全局建筑=${allBuildings.size}"
                            )
                        }
                    }
                    // 空宗门必须推空数组而非 null——
                    // 渲染端 `busSnapshot?.data ?: frame.buildingData` 在总线为 null 时
                    // 回退帧率门控的旧 frame，进入无建筑宗门会闪现/残留前宗门建筑
                    // 固定结构（宗门入口门楼/阶梯）追加尾部：与 MainGameScreen buildingDataArray 同源，
                    // 否则总线数据覆盖帧数据时结构丢失（真机实测阶梯/门楼不显示）
                    val dataArray = buildBuildingDataArray(buildings, _buildingSpriteSizesCache) +
                        FixedSectGateway.renderEntries()
                    _renderCommandBus.postBuildingData(dataArray, buildings.size + FixedSectGateway.count)
                }
        }
    }

    fun dismissDialog() {
        gameEngine.setActiveDialog(null)
        uiServices.dialogManager.close()
        _dialogOpenTrigger.tryEmit(Unit)
    }

    fun activateSubDialogDomain(domainName: String) { gameEngine.pushSubDialogDomain(domainName) }

    fun deactivateSubDialogDomain(domainName: String) { gameEngine.popSubDialogDomain(domainName) }

    val acknowledgedBeastAttackIds: StateFlow<Set<String>> get() = warnings.acknowledgedBeastAttackIds

    val attackWarnings: StateFlow<List<AttackWarning>> get() = warnings.attackWarnings
    val shownWarningStageIds: StateFlow<List<String>> get() = warnings.shownWarningStageIds
    val gameData: StateFlow<GameData> get() = gameEngine.gameData

    val gameDataUi: StateFlow<GameData> = merge(
        gameEngine.gameData,
        _dialogOpenTrigger.map { gameEngine.gameData.value }
    ).distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, sharingStarted, gameEngine.gameData.value)

    // 每宗独立地图 + 进入宗门转场：由 SectMapController 承担（降低本类职责）
    private val sectMapController = SectMapController(gameData, viewModelScope)
    val sectMapData: StateFlow<SectMapState?> get() = sectMapController.sectMapData
    val sectTransitionActive: StateFlow<Boolean> get() = sectMapController.sectTransitionActive

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
    /**
     * 配置块（R2.3 第二波逐块迁移·块②「配置回声」）：来源换成
     * [GameEngine.configEcho] 投影后按 UI 既有类型形状还原
     * [GameStateStore.ConfigState]——字段集与取值逐字段等价
     * （gameSpeed 属运行态，两臂都不由镜像供给）。
     */
    val configState: StateFlow<GameStateStore.ConfigState> = gameEngine.configEcho
        .map {
            GameStateStore.ConfigState(
                sectPolicies = it.sectPolicies,
                yearlySalary = it.yearlySalary,
                yearlySalaryEnabled = it.yearlySalaryEnabled,
                elderSlots = it.elderSlots,
                placedBuildings = it.placedBuildings,
                autoRecruitSpiritRootFilter = it.autoRecruitSpiritRootFilter
            )
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, sharingStarted, GameStateStore.ConfigState())

    @Immutable
    data class GameScreenAggState(
        val gameData: GameData,
        val highFreq: GameStateStore.HighFreqState,
        val config: GameStateStore.ConfigState,
        val isPaused: Boolean
    )
    val gameScreenState: StateFlow<GameScreenAggState> = combine(
        // unifiedState（20Hz 锁竞争）→ isPaused 窄流直连
        gameEngine.gameData, highFreqState, configState, coreServices.gameEngineCore.isPaused
    ) { gd, hf, cfg, paused -> GameScreenAggState(gd, hf, cfg, paused) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000),
            GameScreenAggState(GameData(), GameStateStore.HighFreqState(), GameStateStore.ConfigState(), true))

    val pendingNotification: StateFlow<GameNotification?> get() = gameEngine.pendingNotification
    val notifications: StateFlow<List<GameNotification>> get() = gameEngine.notifications
    val rewardCardQueue: StateFlow<List<RewardCardItem>> get() = gameEngine.rewardCardQueue
    val warehouseFullEvent get() = gameEngine.warehouseFullEvent
    // 按 id 去重兜底（引擎已保证不变量：读档归一化 + 运行时守卫），
    // 防损坏存档的重复/空 id 弟子触发 LazyVerticalGrid 重复 key 崩溃（Bugly #5079/#3091）
    val discipleAggregates: StateFlow<List<DiscipleAggregate>> = gameEngine.discipleAggregates
        .map { aggregates -> aggregates.distinctBy { it.id } }
        .stateIn(viewModelScope, sharingStarted, emptyList())
    val sectCombatPower: StateFlow<Long> get() = gameEngine.sectCombatPower
    val thermalState: StateFlow<ThermalState> = coreServices.thermalMonitor.thermalState
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
        // SR-5 C3：与引擎领取闸门同源判据（收敛前此处内联硬编码 7 天，可与闸门分歧）
        SectLevelRewardCooldown.isClaimable(
            lastClaimedAtEpochMs = SectLevelRewardCooldown.lastClaimedAt(data.sectLevelClaimRecords, level),
            nowMs = wallClock.currentTimeMillis()
        )
    }.distinctUntilChanged()
        .stateIn(viewModelScope, sharingStarted, false)

    val recruitListAggregates: StateFlow<List<DiscipleAggregate>> = gameData
        // 按 id 去重兜底（引擎/数据层已保证不变量，防 LazyVerticalGrid 重复 key 异常）
        .map { data -> data.recruitList.distinctBy { it.id }.map { it.toAggregate() } }
        .stateIn(viewModelScope, sharingStarted, emptyList())

    // 袋物品独立存储：物理不在仓库堆叠中，直接透传无需过滤
    val equipmentStacks: StateFlow<List<EquipmentStack>> get() = gameEngine.equipmentStacks

    val equipmentInstances: StateFlow<List<EquipmentInstance>> get() = gameEngine.equipmentInstances

    val manualStacks: StateFlow<List<ManualStack>> get() = gameEngine.manualStacks
    val manualInstances: StateFlow<List<ManualInstance>> get() = gameEngine.manualInstances
    val pills: StateFlow<List<Pill>> get() = gameEngine.pills
    val materials: StateFlow<List<Material>> get() = gameEngine.materials
    val herbs: StateFlow<List<Herb>> get() = gameEngine.herbs
    val seeds: StateFlow<List<Seed>> get() = gameEngine.seeds
    val storageBags: StateFlow<List<StorageBag>> get() = gameEngine.storageBags
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

    // isPaused 用窄流直连（零采样延迟）——50ms 采样流与 togglePause 直读
    // 混用会导致快速双击被吞
    val isPaused: StateFlow<Boolean> = coreServices.gameEngineCore.isPaused

    /**
     * 消息栏事件流（R2.3 第二波逐块迁移·块③「事件流当前载体」）：来源换成
     * [GameEngine.eventLog] 投影——gameEventRecords 未被本封变更集触及时投影
     * 引用不变，UI 不再随每旬整份 gameData 换引用而重算；proto `eventFeed`
     * （信封块 3）由 R2.4 产出后本块改吃 typed 事件流。
     */
    val gameEventRecords: StateFlow<List<GameEventRecord>> = gameEngine.eventLog
        .map { it.records }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val yearlyReports: StateFlow<List<YearlyReport>> = gameEngine.gameData
        .map { it.yearlyReports }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── 建筑/弟子详情 ──

    fun openBuildingDetailDialog(buildingId: String) { _selectedBuildingId.value = buildingId }

    fun clearNotification() {
        gameEngine.consumeNotification()
        delegateServices.discipleFacade.clearPendingNotification()
    }

    fun enterSect(sectId: String) {
        // 进入宗门转场：开启（目标宗门地图就绪且至少播放 1 秒后自动关闭），
        // 随后引擎切换 activeSectId；最小播放时长保证转场不闪断，界面就绪即关（不依赖视频播完）
        sectMapController.beginSectTransition(sectId)
        gameEngine.launchOnEngine { gameEngine.enterSect(sectId) }
    }

    val isGameOver: StateFlow<Boolean> = gameEngine.gameData
        .map { it.isGameOver }.distinctUntilChanged()
        .stateIn(viewModelScope, sharingStarted, false)

    /**
     * 个性化广告开关状态（设置界面订阅）。
     * 合规要求（TapADN SDK 合规使用说明）：App 内提供退出个性化广告能力。
     */
    val personalizedAdsEnabled: StateFlow<Boolean> get() = ads.personalizedAdsEnabled

    val overlayOrder: List<TopOverlay> get() = overlays.overlayOrder
    val detailDisciple: StateFlow<DiscipleDetailRequest?> get() = overlays.detailDisciple
    val bagRewardCards: StateFlow<List<RewardCardItem>> get() = bag.bagRewardCards
    val showRedeemCodeDialog: StateFlow<Boolean> get() = redeem.showRedeemCodeDialog
    val redeemResult: StateFlow<RedeemResult?> get() = redeem.redeemResult
    val mails: StateFlow<List<MailEntity>> get() = mail.mails
    val mailUnreadCount: StateFlow<Int> get() = mail.mailUnreadCount
    val mailRewardCards: StateFlow<List<RewardCardItem>> get() = mail.mailRewardCards
    /** 统一仓库容量不足提示框（GameOverlayHost 渲染，未来新增领取按钮直接调用） */
    override fun showCapacityWarning(message: String) = super.showCapacityWarning(message)

    val cultivationProgress: StateFlow<Float> get() = gameLoop.cultivationProgress
    val interpolationFactor: Float get() = gameLoop.interpolationFactor
    fun setMusicEnabled(enabled: Boolean) {
        settings.setMusicEnabled(enabled)
        audioServices.audioEngine.onSettingsChanged()
    }
    val showAllAvailableDisciplesSnapshot: Boolean get() = settings.showAllAvailableDisciplesSnapshot
    val battleAndExplorationIdsSnapshot: Set<String> get() = settings.battleAndExplorationIdsSnapshot
    override fun onCleared() {
        Log.i(TAG, "GameViewModel cleared, stopping game loop and releasing resources")
        uiServices.dialogManager.close()
        gameEngine.setActiveDialog(null)
        gameLoop.clearResources()
        super.onCleared()
    }
}

