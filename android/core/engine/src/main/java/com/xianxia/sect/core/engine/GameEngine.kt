package com.xianxia.sect.core.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.xianxia.sect.core.model.Alliance
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.RewardCardItem
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.StorageBag
import com.xianxia.sect.core.model.WorldMapRenderData
import com.xianxia.sect.core.state.BattleResultUIData
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.PendingBeastAttack
import com.xianxia.sect.core.engine.domain.cultivation.CultivationFacade
import com.xianxia.sect.core.engine.domain.economy.EconomyFacade
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentGate
import com.xianxia.sect.core.engine.domain.exploration.ExplorationFacade
import com.xianxia.sect.core.engine.service.SecretRealmService
import com.xianxia.sect.core.repository.GameHeavyDataPort
import com.xianxia.sect.core.repository.HeavyDataDecoder
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.service.EquipmentUpgradeService
import com.xianxia.sect.core.engine.service.FormulaService
import com.xianxia.sect.core.engine.service.JadeSymbolRuntimeState
import com.xianxia.sect.core.engine.service.JadeSymbolService
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.battle.CombatService
import com.xianxia.sect.core.engine.domain.building.BuildingService
import com.xianxia.sect.core.engine.domain.disciple.DiscipleService
import com.xianxia.sect.core.engine.domain.exploration.ExplorationService
import com.xianxia.sect.core.engine.domain.exploration.resolveBeastAttackFight
import com.xianxia.sect.core.engine.domain.diplomacy.AISectDiscipleManager
import com.xianxia.sect.core.engine.domain.diplomacy.DiplomacyService
import com.xianxia.sect.core.engine.domain.save.SaveService
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.engine.service.MailService
import com.xianxia.sect.core.engine.service.RedeemCodeService
import com.xianxia.sect.core.engine.service.AutoBuyService
import com.xianxia.sect.core.engine.domain.battle.BattleFacade
import com.xianxia.sect.core.engine.domain.building.BuildingFacade
import com.xianxia.sect.core.engine.domain.diplomacy.DiplomacyFacade
import com.xianxia.sect.core.engine.domain.disciple.DiscipleFacade
import com.xianxia.sect.core.engine.domain.inventory.InventoryFacade
import com.xianxia.sect.core.engine.domain.production.ProductionFacade
import com.xianxia.sect.core.engine.domain.road.RoadFacade
import com.xianxia.sect.core.engine.domain.save.SaveFacade
import com.xianxia.sect.core.engine.service.HighFrequencyData
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.wallet.SpiritStoneWallet

import javax.inject.Inject
import javax.inject.Singleton



typealias GiftResult = com.xianxia.sect.core.domain.favor.GiftResult
typealias ElderBonusData = FormulaService.ElderBonusData

data class GameStateSnapshot(
    val gameData: GameData,
    val disciples: List<Disciple>,
    val equipmentInstances: List<EquipmentInstance>,
    val manualStacks: List<ManualStack>,
    val manualInstances: List<ManualInstance>,
    val pills: List<Pill>,
    val materials: List<Material>,
    val herbs: List<Herb>,
    val seeds: List<Seed>,
    val storageBags: List<StorageBag> = emptyList(),
    val battleLogs: List<BattleLog>,
    val alliances: List<Alliance>,
    val productionSlots: List<com.xianxia.sect.core.model.production.ProductionSlot> = emptyList()
)

@Singleton
class GameEngine @Inject constructor(
    // 构造依赖按域归组为 Facade：扩展文件通过下方同名 internal val 访问器零改动访问
    internal val gameEngineCore: GameEngineCore,
    // 测试注入点（FakeEngineContextDispatcher 绕过 Mockito suspend 泛型限制）；
    // 生产恒等于 gameEngineCore
    internal val engineContextDispatcher: EngineContextDispatcher = gameEngineCore,
    internal val stateStore: GameStateStore,
    /**
     * RNG 分区管理器（AUTHORITATIVE 委托通道挂载点；Hilt 单例与
     * GameEngine/存档链路同实例）。默认新建仅供测试直构——生产由 Hilt
     * 注入全局单例。
     */
    internal val gameRngManager: GameRngManager,
    internal val explorationFacade: ExplorationFacade,
    internal val cultivationFacade: CultivationFacade,
    internal val economyFacade: EconomyFacade,
    internal val battleFacade: BattleFacade,
) {

    // ── 服务访问器（D1 归组转发，扩展文件零改动） ──
    internal val inventorySystem: InventorySystem get() = economyFacade.inventoryFacade.inventorySystem
    internal val inventoryConfig: InventoryConfig get() = economyFacade.inventoryFacade.inventoryConfig
    internal val battleSystem: BattleSystem get() = battleFacade.battleSystem
    internal val combatService: CombatService get() = battleFacade.combatService
    internal val assignmentGate: DiscipleAssignmentGate get() = battleFacade.assignmentGate
    internal val productionCoordinator: ProductionCoordinator get() = cultivationFacade.productionCoordinator
    internal val discipleService: DiscipleService get() = cultivationFacade.discipleService
    internal val equipmentUpgradeService: EquipmentUpgradeService get() = cultivationFacade.equipmentUpgradeService
    internal val explorationService: ExplorationService get() = explorationFacade.explorationService
    internal val secretRealmService: SecretRealmService get() = explorationFacade.secretRealmService
    internal val buildingService: BuildingService get() = cultivationFacade.buildingFacade.buildingService
    internal val saveService: SaveService get() = economyFacade.saveFacade.saveService
    internal val cultivationService: CultivationService get() = cultivationFacade.cultivationService
    internal val diplomacyService: DiplomacyService get() = explorationFacade.diplomacyService
    internal val redeemCodeService: RedeemCodeService get() = economyFacade.redeemCodeService
    internal val formulaService: FormulaService get() = cultivationFacade.formulaService
    internal val mailService: MailService get() = economyFacade.mailService
    internal val autoBuyService: AutoBuyService get() = economyFacade.autoBuyService
    internal val heavyDataPort: GameHeavyDataPort get() = economyFacade.saveFacade.heavyDataPort
    internal val heavyDataDecoder: HeavyDataDecoder get() = economyFacade.saveFacade.heavyDataDecoder
    internal val discipleFacade: DiscipleFacade get() = cultivationFacade.discipleFacade
    internal val buildingFacade: BuildingFacade get() = cultivationFacade.buildingFacade
    internal val roadFacade: RoadFacade get() = cultivationFacade.roadFacade
    internal val inventoryFacade: InventoryFacade get() = economyFacade.inventoryFacade
    internal val diplomacyFacade: DiplomacyFacade get() = explorationFacade.diplomacyFacade
    internal val productionFacade: ProductionFacade get() = cultivationFacade.productionFacade
    internal val saveFacade: SaveFacade get() = economyFacade.saveFacade
    internal val spiritStoneWallet: SpiritStoneWallet get() = economyFacade.spiritStoneWallet
    /** 玉符（氪金货币）在线时长结算服务 */
    internal val jadeSymbolService: JadeSymbolService get() = gameEngineCore.jadeSymbolServiceRef

    /** C++ 引擎镜像同步服务（StateSyncService；经 GameEngineCore 访问器取用） */
    internal val stateSyncService: com.xianxia.sect.core.nativebridge.StateSyncService
        get() = gameEngineCore.stateSyncServiceRef

    /** 镜像消费块投影态（R2.3 第二波；随 StateSyncService 同实例，UI 已迁块的来源） */
    internal val gameViewStore: com.xianxia.sect.core.gameview.GameViewStore
        get() = stateSyncService.gameViewStore

    /**
     * 游戏语义墙钟（SR-5）：日/周/过期阈值判据的取时入口，与 GameEngineCore 同实例。
     * 🔴 IN2：不参与存档新旧仲裁。
     */
    internal val wallClock: com.xianxia.sect.core.engine.system.WallClock
        get() = gameEngineCore.wallClock

    init {
        // 注入任务完成检测回调到 GameEngineCore，
        // 确保空闲期间任务完成也能被每月结算及时检测
        gameEngineCore.missionCheck = { checkAndProcessCompletedMissions() }

        // 注入建筑没收回调（P2-18 Stage 2 征伐环：玩家占领宗门被 AI 夺回 →
        // 事务外建筑拆除，与 AISectOccupationResolver 事务外拆除先例同型）
        gameEngineCore.seizedBuildingsHandler = { sectIds ->
            sectIds.forEach { buildingFacade.seizeBuildingsOfSect(it) }
        }

        // W4-C 随机源收敛：三处顶层可变 xxxRngManager（aisRngManager/
        // enemyGenRngManager/teamComposerRngManager）已改为形参必传，
        // 本处注入点随之移除（协议租约见 docs/parallel-batches-w4/protocol-lease.md）。
        // AI 弟子域随机源归一：摘除自持影子流，接入真源分区
        //（委托模式 → AI_SECT_MIRROR = C++ aiRng_ 本体；回退 → AI_SECT 本地等价）
        AISectDiscipleManager.initialize(gameRngManager)
    }


    @Volatile internal var heavyDataLoaded = false

    /**
     * 将协程派发到引擎线程执行。
     *
     * 所有 UI 层触发的引擎状态变更必须通过此方法派发到引擎线程，
     * 而非直接调用 stateStore.update{}——后者会在 Main 线程阻塞导致 ANR。
     *
     * 引擎线程已持有 stateStore 的 ReentrantLock，同一线程重入无竞争开销，
     * 对标 Unreal Engine AsyncTask(GameThread) / GLSurfaceView queueEvent 模式。
     */
    fun launchOnEngine(block: suspend CoroutineScope.() -> Unit): Job {
        return gameEngineCore.launchInScope(block)
    }

    /**
     * 构建存档快照（引擎线程采样）。
     *
     * RNG 线程契约：SaveFacadeImpl.getStateSnapshot
     * 内的 gameRngManager.exportStates() 会逐分区读取 C++ PCG 真相源状态，
     * 必须在引擎线程执行——本方法把"年变队列 flush + 存档前校验 + 玉符
     * checkpoint + RNG 导出 + 状态快照"整体收敛到引擎上下文，沿用既有
     * "引擎线程构建快照 → IO 线程写字节"模式。已在引擎线程调用时原地执行。
     *
     * 注意：本方法刻意为**成员函数**而非扩展函数——扩展函数体在 MockK
     * relaxed 环境下会真实执行并触达 mock 的 engineContextDispatcher（破坏
     * SaveLoadViewModelLoadTest 对 saveFacade 直通的 stub 语义）；成员函数
     * 在 mock 环境下整方法被 relaxed 拦截，行为与直通等价。
     */
    suspend fun buildSaveSnapshot(): GameStateSnapshot {
        // w3-13 通道关闭配套：存档自愈写面（worldMapSects/aiSectDisciples 已关闭
        // 回导）发生后全量重建 native 基线（§2.75④ "自愈后全量重建基线"）
        if (economyFacade.saveFacade.worldMapSelfHealPending) {
            rebaselineNativeMirror("存档自愈")
        }
        return engineContextDispatcher.withEngineContext {
            cultivationService.flushYearlyOpsQueue()
            saveFacade.getStateSnapshot()
        }
    }

    /**
     * 通用奖励卡片入队（内部空检查，空列表自动跳过）。
     *
     * 引擎线程写操作——UI 层必须经 [launchOnEngine] 包裹调用。
     * 原 DailySignInService.enqueueSignInCards 在签到功能移除后的通用替代入口。
     */
    fun enqueueRewardCards(cards: List<RewardCardItem>) {
        if (cards.isNotEmpty()) {
            stateStore.enqueueRewardCards(cards)
        }
    }

    // ── StateFlow delegates ─────────────────────────────────────────────
    val gameData: StateFlow<GameData> get() = stateStore.gameData
    val gameDataSnapshot: GameData get() = stateStore.gameDataSnapshot
    /** 玉符运行时状态（1Hz 节流，UI 徽章/倒计时订阅入口） */
    val jadeSymbolState: StateFlow<JadeSymbolRuntimeState> get() = gameEngineCore.jadeSymbolState

    /**
     * 离线回归报告（结算改造 2026-09-27 B7）：注入落地后发布一次，
     * UI 展示后调 [acknowledgeOfflineReturnReport] 清空。
     */
    val offlineReturnReport: StateFlow<OfflineReturnReport?>
        get() = gameEngineCore.offlineReturnReport

    /** UI 展示完离线回归面板后确认（清报告，防重复弹出） */
    fun acknowledgeOfflineReturnReport() {
        gameEngineCore.offlineReturnReportMutable.value = null
    }

    val discipleAggregatesSnapshot: List<DiscipleAggregate> get() = stateStore.discipleAggregatesSnapshot
    val discipleTables: DiscipleTables get() = stateStore.discipleTables
    val disciples: StateFlow<List<Disciple>> get() = stateStore.disciples
    val equipmentInstances: StateFlow<List<EquipmentInstance>> get() = stateStore.equipmentInstances
    val manualStacks: StateFlow<List<ManualStack>> get() = stateStore.manualStacks
    val manualInstances: StateFlow<List<ManualInstance>> get() = stateStore.manualInstances
    val pills: StateFlow<List<Pill>> get() = stateStore.pills
    val materials: StateFlow<List<Material>> get() = stateStore.materials
    val herbs: StateFlow<List<Herb>> get() = stateStore.herbs
    val seeds: StateFlow<List<Seed>> get() = stateStore.seeds
    val storageBags: StateFlow<List<StorageBag>> get() = stateStore.storageBags
    fun getCurrentSeeds(): List<Seed> = stateStore.getCurrentSeeds()
    fun getCurrentHerbs(): List<Herb> = stateStore.getCurrentHerbs()
    fun getCurrentMaterials(): List<Material> = stateStore.getCurrentMaterials()
    val battleLogs: StateFlow<List<BattleLog>> get() = stateStore.battleLogs
    val pendingBattleResult: StateFlow<BattleResultUIData?> get() = stateStore.pendingBattleResult
    val pendingBattleRewardCards: StateFlow<List<RewardCardItem>> get() = stateStore.pendingBattleRewardCards
    fun clearPendingBattleRewardCards() { stateStore.clearPendingBattleRewardCards() }
    val rewardCardQueue: StateFlow<List<RewardCardItem>> get() = stateStore.rewardCardQueue
    fun clearRewardCardQueue(count: Int = Int.MAX_VALUE) { stateStore.clearRewardCardQueue(count) }
    val pendingBeastAttacks: StateFlow<List<PendingBeastAttack>> get() = stateStore.pendingBeastAttacks
    fun clearPendingBeastAttacks() { stateStore.clearPendingBeastAttacks() }
    fun removePendingBeastAttack(beastLevelId: String) { stateStore.removePendingBeastAttack(beastLevelId) }
    suspend fun resolveBeastAttackFight(
        beastLevelId: String
    ): Boolean {
        // B18-P4：原 manualDefenders 形参已删——唯一消费者（遭遇战分支
        // selectBeastDefenders）随死臂清理退役，形参无消费者即死形参（detekt
        // UnusedParameter 实证）；UI 侧 BeastAttackDelegate 历来只传 beastLevelId
        val handled = explorationService.resolveBeastAttackFight(beastLevelId)
        // w3-13 通道关闭配套（§2.79）：迎战写面（worldMapSects 守军清理 §2.78 关闭、
        // worldLevels 在册保留）发生战斗即全量重建 native 基线回导 C++（用户迎战为
        // 低频动作）；未处理（妖兽已不在）零成本
        if (handled) rebaselineNativeMirror("妖兽迎战")
        return handled
    }
    val warehouseFullEvent get() = stateStore.warehouseFullEvent

    // ── 妖兽界面锁定 ──────────────────────────────────────────

    /**
     * 锁定妖兽：玩家打开详情弹窗时，月度结算跳过该妖兽的 AI 攻击判定。
     *
     * native 臂（batch-23）：AUTHORITATIVE 稳态写者归 C++
     * （`lockedBeastIds` 为 NativeGameState 顶层段，也是月结"锁定妖兽不被
     * AI 攻击"判据的输入）；失败/降级走 Kotlin 原写入。
     */
    fun lockBeastView(beastId: String) {
        if (lockBeastViewNative(beastId, locked = true)) return
        stateStore.update { gameData = gameData.copy(lockedBeastIds = gameData.lockedBeastIds + beastId) }
    }

    /**
     * 解锁妖兽：玩家关闭详情弹窗后，AI 可正常进攻该妖兽。
     *
     * native 臂（batch-23）：同 [lockBeastView]；空 id 由 native 臂与
     * Kotlin 原路径双重早退（语义不变）。
     */
    fun unlockBeastView(beastId: String) {
        if (beastId.isEmpty()) return
        if (lockBeastViewNative(beastId, locked = false)) return
        stateStore.update { gameData = gameData.copy(lockedBeastIds = gameData.lockedBeastIds - beastId) }
    }
    val discipleAggregates: StateFlow<List<DiscipleAggregate>> get() = stateStore.discipleAggregates
    val sectCombatPower: StateFlow<Long> get() = stateStore.sectCombatPower
    val aiSectCombatPowers: StateFlow<Map<String, Long>> get() = stateStore.aiSectCombatPowers
    /**
     * UI 消费块①「资源头部」取数入口（B18 后恒 GameViewStore 投影来源——镜像
     * 按封触及才重投）。"从整份 gameData 快照派生"的第一波回滚取数面已随臂
     * 删除（[com.xianxia.sect.core.gameview.GameViewStore.resourcesViewOf] 派生
     * 函数保留在 GameViewStore 供投影重投与测试 golden 使用）。
     */
    val resourcesHeader: StateFlow<com.xianxia.sect.core.gameview.ResourcesHeaderView> by lazy {
        gameViewStore.resourcesHeader
    }

    /**
     * 旬内连续进度流 [0,1]（B8 时间进度投影源；结算改造 2026-09-27）——
     * [GameTimeClock.phaseProgressFlow] 的公开面（gameClock 为 engine 模块
     * internal，feature 层经此消费）。AUTHORITATIVE 下每帧随 native 帧计划
     * 推送（INV-2 轴的旬内分量），OFF 回退臂随 tick 刷新；暂停恒 0。
     * 月进度投影（(旬 + 旬内进度)/3）在 ViewModel 层与本类 [resourcesHeader]
     * 的日历投影合成（§6.5 map+stateIn，UI 不驱动 tick）。
     */
    val phaseProgressFlow: StateFlow<Float> get() = gameEngineCore.gameClock.phaseProgressFlow

    val highFreqState: StateFlow<GameStateStore.HighFreqState> get() = stateStore.highFreqState
    val entityState: StateFlow<GameStateStore.EntityState> get() = stateStore.entityState
    /**
     * UI 消费块②「配置回声」取数入口（B18 后恒 GameViewStore 投影来源——本封
     * 变更集未触及配置字段即不重投）。既有 [configState]（GameStateStore 三层流）
     * 保留给未迁消费面，与投影同源不分叉；"从整份 gameData 快照派生"的回滚取数面
     * 已随臂删除。
     */
    val configEcho: StateFlow<com.xianxia.sect.core.gameview.ConfigEchoView> by lazy {
        gameViewStore.configEcho
    }

    /**
     * UI 消费块③「事件流（当前载体 = gameData.gameEventRecords）」取数入口
     * （B18 后恒 GameViewStore 投影来源——本封未携带事件记录即引用不变，消息栏
     * 不随每旬整份快照重算）；"从整份 gameData 快照派生"的回滚取数面已随臂删除。
     * proto 信封块 3 `eventFeed` 由 R2.4 产出后，本块来源改吃 typed 事件流。
     */
    val eventLog: StateFlow<com.xianxia.sect.core.gameview.EventLogView> by lazy {
        gameViewStore.eventLog
    }

    val configState: StateFlow<GameStateStore.ConfigState> get() = stateStore.configState
    /** 高频修炼数据（Q-2：对外只读，写入经 [updateHighFrequencyData]） */
    val highFrequencyData: StateFlow<HighFrequencyData> = cultivationService.getHighFrequencyData()
    val productionSlots: StateFlow<List<ProductionSlot>> = productionFacade.productionSlots

    val worldMapRenderData: StateFlow<WorldMapRenderData> by lazy {
        stateStore.gameData.map { data ->
            WorldMapRenderData(
                worldMapSects = data.worldMapSects,
                cultivatorCaves = data.cultivatorCaves ?: emptyList(),
                worldLevels = data.worldLevels ?: emptyList(),
                // 远古秘境为历战常驻活动（世界地图不再显示入口）
                secretRealm = null
            )
        }.distinctUntilChanged()
            .stateIn(gameEngineCore.scopeForStateIn(), kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
                WorldMapRenderData())
    }

    /**
     * 读档/新游戏/重启后同步 C++ native 引擎基线（AUTHORITATIVE 模式）。
     *
     * 必要性：读档/新游戏/重启路径只更新 Kotlin [GameStateStore]，若不把新档状态
     * 导入 C++ game-core，native 引擎会残留上一次会话状态
     * （`ensureAuthoritativeNative` 因 `nativeIsInitialized()==true` 跳过重新导入），
     * tick 反向镜像（`syncFromNative`/`applyDirtyFromNative`）
     * 会把残留旧档状态覆盖回 Kotlin。
     *
     * 调用方必须在引擎线程（`engineContextDispatcher.withEngineContext` 内）、
     * 状态装载完成之后调用；native 未加载/不可用时由 `loadNativeBaseline`
     * 内部守卫静默跳过，降级契约不变。
     */
    internal suspend fun syncNativeBaselineAfterLoad() {
        gameEngineCore.loadNativeBaseline(stateSyncService)
    }

    // ── Nested types（向后兼容别名） ────────────────────────────────────

    data class BulkSellOperation(val id: String, val name: String, val quantity: Int, val itemType: String)
    data class BulkSellResult(val soldCount: Int, val totalEarned: Long, val soldItemNames: List<String>,
        val failedItemNames: List<String>)
}
