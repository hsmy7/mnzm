package com.xianxia.sect.core.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.put
import com.xianxia.sect.core.nativebridge.ActionIds
import com.xianxia.sect.core.model.Alliance
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GameEventCategory
import com.xianxia.sect.core.model.GameEventType
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
import com.xianxia.sect.core.state.GameNotification
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.PendingBeastAttack
import com.xianxia.sect.core.state.PendingMarriageProposal
import com.xianxia.sect.core.state.recordGameEvent
import com.xianxia.sect.core.engine.domain.cultivation.CultivationFacade
import com.xianxia.sect.core.engine.domain.economy.EconomyFacade
import com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentGate
import com.xianxia.sect.core.engine.domain.exploration.ExplorationFacade
import com.xianxia.sect.core.engine.service.LawEnforcementProcessor
import com.xianxia.sect.core.engine.service.SecretRealmService
import com.xianxia.sect.core.repository.GameHeavyDataPort
import com.xianxia.sect.core.repository.HeavyDataDecoder
import com.xianxia.sect.core.engine.service.CultivationService
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
    val equipmentStacks: List<EquipmentStack>,
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
    internal val lawEnforcementProcessor: LawEnforcementProcessor get() = cultivationFacade.lawEnforcementProcessor
    /** 玉符（氪金货币）在线时长结算服务 */
    internal val jadeSymbolService: JadeSymbolService get() = gameEngineCore.jadeSymbolServiceRef

    /** C++ 引擎镜像同步服务（StateSyncService；经 GameEngineCore 访问器取用） */
    internal val stateSyncService: com.xianxia.sect.core.nativebridge.StateSyncService
        get() = gameEngineCore.stateSyncServiceRef

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
    val discipleAggregatesSnapshot: List<DiscipleAggregate> get() = stateStore.discipleAggregatesSnapshot
    val discipleTables: DiscipleTables get() = stateStore.discipleTables
    val disciples: StateFlow<List<Disciple>> get() = stateStore.disciples
    val equipmentStacks: StateFlow<List<EquipmentStack>> get() = stateStore.equipmentStacks
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
    val pendingNotification: StateFlow<GameNotification?> get() = stateStore.pendingNotification
    val notifications: StateFlow<List<GameNotification>> get() = stateStore.notifications
    fun consumeNotification(): GameNotification? = stateStore.consumeNotification()
    val rewardCardQueue: StateFlow<List<RewardCardItem>> get() = stateStore.rewardCardQueue
    fun clearRewardCardQueue(count: Int = Int.MAX_VALUE) { stateStore.clearRewardCardQueue(count) }
    val pendingBeastAttacks: StateFlow<List<PendingBeastAttack>> get() = stateStore.pendingBeastAttacks
    val pendingMarriageProposals: StateFlow<List<PendingMarriageProposal>> get() = stateStore.pendingMarriageProposals
    fun clearPendingMarriageProposals() { stateStore.clearPendingMarriageProposals() }
    fun clearPendingBeastAttacks() { stateStore.clearPendingBeastAttacks() }
    fun removePendingBeastAttack(beastLevelId: String) { stateStore.removePendingBeastAttack(beastLevelId) }
    suspend fun resolveBeastAttackFight(
        beastLevelId: String,
        manualDefenders: List<Disciple>? = null
    ): Boolean {
        return explorationService.resolveBeastAttackFight(beastLevelId, manualDefenders)
    }
    val warehouseFullEvent get() = stateStore.warehouseFullEvent

    // ── 婚姻提议审批 ──────────────────────────────────────────

    /**
     * 批准婚姻提议：在 stateStore.update 事务内原子执行配对 + 从待处理列表移除。
     *
     * 防御性检查：若任一方已有道侣则跳过配对，仅清理提议避免静默覆盖。
     *
     * native 臂（W4-A·w3-02）：C++ 事务 `DISCIPLE_LIFECYCLE_MARRY_APPROVE`
     * （batch-14 就绪地基——handler 与 3 个 GTest 早已在位，Kotlin 侧此前零引用）
     * 完成 partnerIds 双向绑定 + MARRIAGE 事件直写；提议移除留 Kotlin
     * （pendingMarriageProposals 为运行态字段，非快照协议）。
     * NotFound 失败信封（提议残留 + 弟子已亡/被逐边界：Kotlin 原路径写幽灵列
     * 条目，SoA 行式存储无法表达）→ 回退 Kotlin 原路径保行为（batch-14
     * 既有口径，不得为下沉改变该边界语义）。
     */
    fun approveMarriageProposal(maleId: String, femaleId: String) {
        // 提议存在性前置 + 名字捕获（事件文案材料；提议列表为 Kotlin 运行态，
        // 移除动作仍在后续事务内按 id 重查——防捕获后列表已变的悬挂移除）
        val proposal = stateStore.pendingMarriageProposals.value.find {
            it.maleId == maleId && it.femaleId == femaleId
        } ?: return
        if (tryDiscipleOpNative(ActionIds.DISCIPLE_LIFECYCLE_MARRY_APPROVE) {
                put("maleId", maleId)
                put("femaleId", femaleId)
                put("maleName", proposal.maleName)
                put("femaleName", proposal.femaleName)
            } != null) {
            stateStore.update {
                val current = pendingMarriageProposals.find {
                    it.maleId == maleId && it.femaleId == femaleId
                } ?: return@update
                pendingMarriageProposals = pendingMarriageProposals - current
            }
            return
        }
        stateStore.update {
            val maleIdInt = maleId.toIntOrNull() ?: return@update
            val femaleIdInt = femaleId.toIntOrNull() ?: return@update
            val proposal = pendingMarriageProposals.find {
                it.maleId == maleId && it.femaleId == femaleId
            } ?: return@update
            // 防御性检查：任一方已有道侣则跳过配对
            if (discipleTables.partnerIds.getOrNull(maleIdInt) != null ||
                discipleTables.partnerIds.getOrNull(femaleIdInt) != null
            ) {
                pendingMarriageProposals = pendingMarriageProposals - proposal
                return@update
            }
            discipleTables.partnerIds[maleIdInt] = femaleId
            discipleTables.partnerIds[femaleIdInt] = maleId
            recordGameEvent(
                com.xianxia.sect.core.model.GameEventCategory.SECT,
                com.xianxia.sect.core.model.GameEventType.MARRIAGE,
                "弟子${proposal.maleName}与弟子${proposal.femaleName}结为道侣",
                maleId, proposal.maleName
            )
            pendingMarriageProposals = pendingMarriageProposals - proposal
        }
    }

    /**
     * 拒绝婚姻提议：仅从待处理列表移除，不进行配对。
     *
     * native 臂（W4-A·w3-02）：C++ 事务 `DISCIPLE_LIFECYCLE_MARRY_REJECT`
     * （1750，disciple_lifecycle_tx.h）直写 MARRIAGE 拒绝事件——零弟子表写入、
     * 零 RNG、无失败臂（弟子行不存在边界不产生幽灵列，可直达）；提议移除留
     * Kotlin（运行态字段）。
     */
    fun rejectMarriageProposal(maleId: String, femaleId: String) {
        val proposal = stateStore.pendingMarriageProposals.value.find {
            it.maleId == maleId && it.femaleId == femaleId
        } ?: return
        if (tryDiscipleOpNative(ActionIds.DISCIPLE_LIFECYCLE_MARRY_REJECT) {
                put("maleId", maleId)
                put("femaleId", femaleId)
                put("maleName", proposal.maleName)
                put("femaleName", proposal.femaleName)
            } != null) {
            stateStore.update {
                val current = pendingMarriageProposals.find {
                    it.maleId == maleId && it.femaleId == femaleId
                } ?: return@update
                pendingMarriageProposals = pendingMarriageProposals - current
            }
            return
        }
        stateStore.update {
            val proposal = pendingMarriageProposals.find {
                it.maleId == maleId && it.femaleId == femaleId
            } ?: return@update
            pendingMarriageProposals = pendingMarriageProposals - proposal
            recordGameEvent(
                com.xianxia.sect.core.model.GameEventCategory.SECT,
                com.xianxia.sect.core.model.GameEventType.MARRIAGE,
                "弟子${proposal.maleName}拒绝与弟子${proposal.femaleName}结为道侣",
                maleId, proposal.maleName
            )
        }
    }

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
    val highFreqState: StateFlow<GameStateStore.HighFreqState> get() = stateStore.highFreqState
    val entityState: StateFlow<GameStateStore.EntityState> get() = stateStore.entityState
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
