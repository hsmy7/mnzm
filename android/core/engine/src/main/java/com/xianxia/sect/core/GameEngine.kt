package com.xianxia.sect.core.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.*
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.service.FormulaService
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.engine.domain.battle.aisRngManager
import com.xianxia.sect.core.engine.domain.battle.enemyGenRngManager
import com.xianxia.sect.core.engine.domain.battle.teamComposerRngManager
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.battle.CombatService
import com.xianxia.sect.core.engine.domain.building.BuildingService
import com.xianxia.sect.core.engine.domain.disciple.DiscipleService
import com.xianxia.sect.core.engine.domain.exploration.ExplorationService
import com.xianxia.sect.core.engine.domain.exploration.LevelGenerator
import com.xianxia.sect.core.engine.domain.diplomacy.DiplomacyService
import com.xianxia.sect.core.engine.domain.save.SaveService
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.engine.service.MailService
import com.xianxia.sect.core.engine.service.RedeemCodeService
import com.xianxia.sect.core.engine.service.DailySignInService
import com.xianxia.sect.core.engine.service.AutoBuyService
import com.xianxia.sect.core.engine.domain.battle.BattleFacade
import com.xianxia.sect.core.engine.domain.building.BuildingFacade
import com.xianxia.sect.core.engine.domain.diplomacy.DiplomacyFacade
import com.xianxia.sect.core.engine.domain.disciple.DiscipleFacade
import com.xianxia.sect.core.engine.domain.inventory.InventoryFacade
import com.xianxia.sect.core.engine.domain.production.ProductionFacade
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
    val teams: List<ExplorationTeam>,
    val battleLogs: List<BattleLog>,
    val alliances: List<Alliance>,
    val productionSlots: List<com.xianxia.sect.core.model.production.ProductionSlot> = emptyList()
)

@Singleton
class GameEngine @Inject constructor(
    internal val gameEngineCore: GameEngineCore,
    internal val engineContextDispatcher: EngineContextDispatcher = gameEngineCore,
    internal val stateStore: GameStateStore,
    internal val inventorySystem: InventorySystem,
    internal val inventoryConfig: InventoryConfig,
    internal val battleSystem: BattleSystem,
    internal val productionCoordinator: ProductionCoordinator,
    internal val discipleService: DiscipleService,
    internal val combatService: CombatService,
    internal val explorationService: ExplorationService,
    internal val buildingService: BuildingService,
    internal val saveService: SaveService,
    internal val cultivationService: CultivationService,
    internal val diplomacyService: DiplomacyService,
    internal val redeemCodeService: RedeemCodeService,
    internal val formulaService: FormulaService,
    internal val mailService: MailService,
    internal val dailySignInService: DailySignInService,
    internal val autoBuyService: AutoBuyService,
    internal val heavyDataPort: com.xianxia.sect.core.repository.GameHeavyDataPort,
    internal val heavyDataDecoder: com.xianxia.sect.core.repository.HeavyDataDecoder,
    internal val discipleFacade: com.xianxia.sect.core.engine.domain.disciple.DiscipleFacade,
    internal val battleFacade: BattleFacade,
    internal val buildingFacade: BuildingFacade,
    internal val inventoryFacade: InventoryFacade,
    internal val diplomacyFacade: DiplomacyFacade,
    internal val productionFacade: ProductionFacade,
    internal val saveFacade: SaveFacade,
    internal val spiritStoneWallet: SpiritStoneWallet,
    internal val gameRngManager: GameRngManager,
    internal val assignmentGate: com.xianxia.sect.core.engine.domain.disciple.DiscipleAssignmentGate,
    internal val lawEnforcementProcessor: com.xianxia.sect.core.engine.service.LawEnforcementProcessor,
) {
    init {
        // 注入任务完成检测回调到 GameEngineCore，
        // 确保空闲期间任务完成也能被每月结算及时检测
        gameEngineCore.missionCheck = { checkAndProcessCompletedMissions() }

        // 初始化顶层 RNG 变量（给 object 单例使用）
        aisRngManager = gameRngManager
        enemyGenRngManager = gameRngManager
        teamComposerRngManager = gameRngManager
    }

    companion object { private const val TAG = "GameEngine" }

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

    // ── StateFlow delegates ─────────────────────────────────────────────
    val gameData: StateFlow<GameData> get() = stateStore.gameData
    val gameDataSnapshot: GameData get() = stateStore.gameDataSnapshot
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
    suspend fun resolveBeastAttackPayTribute(beastLevelId: String): Boolean {
        return explorationService.resolveBeastAttackPayTribute(beastLevelId)
    }
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
     */
    fun approveMarriageProposal(maleId: String, femaleId: String) {
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
     */
    fun rejectMarriageProposal(maleId: String, femaleId: String) {
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

    /** 锁定妖兽：玩家打开详情弹窗时，月度结算跳过该妖兽的 AI 攻击判定 */
    fun lockBeastView(beastId: String) {
        stateStore.update { gameData = gameData.copy(lockedBeastIds = gameData.lockedBeastIds + beastId) }
    }

    /** 解锁妖兽：玩家关闭详情弹窗后，AI 可正常进攻该妖兽 */
    fun unlockBeastView(beastId: String) {
        if (beastId.isEmpty()) return
        stateStore.update { gameData = gameData.copy(lockedBeastIds = gameData.lockedBeastIds - beastId) }
    }
    val teams: StateFlow<List<ExplorationTeam>> get() = stateStore.teams
    val discipleAggregates: StateFlow<List<DiscipleAggregate>> get() = stateStore.discipleAggregates
    val sectCombatPower: StateFlow<Long> get() = stateStore.sectCombatPower
    val aiSectCombatPowers: StateFlow<Map<String, Long>> get() = stateStore.aiSectCombatPowers
    val highFreqState: StateFlow<GameStateStore.HighFreqState> get() = stateStore.highFreqState
    val entityState: StateFlow<GameStateStore.EntityState> get() = stateStore.entityState
    val configState: StateFlow<GameStateStore.ConfigState> get() = stateStore.configState
    val highFrequencyData: StateFlow<HighFrequencyData> = cultivationService.getHighFrequencyData()
    val productionSlots: StateFlow<List<ProductionSlot>> = productionFacade.productionSlots

    val realtimeCultivation: StateFlow<Map<String, Double>> by lazy {
        cultivationService.getHighFrequencyData()
            .map { it.realtimeCultivation ?: emptyMap() }
            .stateIn(gameEngineCore.scopeForStateIn(), kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyMap())
    }

    val worldMapRenderData: StateFlow<WorldMapRenderData> by lazy {
        stateStore.gameData.map { data ->
            WorldMapRenderData(
                worldMapSects = data.worldMapSects,
                cultivatorCaves = data.cultivatorCaves ?: emptyList(),
                worldLevels = data.worldLevels ?: emptyList(),
                connectionEdges = LevelGenerator.buildConnectionEdges(data.worldMapSects)
            )
        }.distinctUntilChanged()
            .stateIn(gameEngineCore.scopeForStateIn(), kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), WorldMapRenderData())
    }

    // ── Nested types for backward compat ────────────────────────────────

    data class BulkSellOperation(val id: String, val name: String, val quantity: Int, val itemType: String)
    data class BulkSellResult(val soldCount: Int, val totalEarned: Long, val soldItemNames: List<String>, val failedItemNames: List<String>)
}
