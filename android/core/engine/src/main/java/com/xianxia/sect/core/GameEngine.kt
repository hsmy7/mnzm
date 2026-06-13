package com.xianxia.sect.core.engine

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.*
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.service.FormulaService
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.battle.CombatService
import com.xianxia.sect.core.engine.domain.building.BuildingService
import com.xianxia.sect.core.engine.domain.disciple.DiscipleService
import com.xianxia.sect.core.engine.domain.exploration.ExplorationService
import com.xianxia.sect.core.engine.domain.diplomacy.DiplomacyService
import com.xianxia.sect.core.engine.domain.save.SaveService
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.engine.service.MailService
import com.xianxia.sect.core.engine.service.RedeemCodeService
import com.xianxia.sect.core.engine.service.DailySignInService
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
import javax.inject.Inject
import javax.inject.Singleton

typealias GiftResult = DiplomacyService.GiftResult
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
    internal val heavyDataPort: com.xianxia.sect.core.repository.GameHeavyDataPort,
    internal val heavyDataDecoder: com.xianxia.sect.core.repository.HeavyDataDecoder,
    internal val discipleFacade: com.xianxia.sect.core.engine.domain.disciple.DiscipleFacade,
    internal val battleFacade: BattleFacade,
    internal val buildingFacade: BuildingFacade,
    internal val inventoryFacade: InventoryFacade,
    internal val diplomacyFacade: DiplomacyFacade,
    internal val productionFacade: ProductionFacade,
    internal val saveFacade: SaveFacade
) {
    companion object { private const val TAG = "GameEngine" }

    @Volatile internal var heavyDataLoaded = false

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
    val rewardCardQueue: StateFlow<List<RewardCardItem>> get() = stateStore.rewardCardQueue
    fun clearRewardCardQueue(count: Int = Int.MAX_VALUE) { stateStore.clearRewardCardQueue(count) }
    val warehouseFullEvent get() = stateStore.warehouseFullEvent
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
            WorldMapRenderData(worldMapSects = data.worldMapSects, cultivatorCaves = data.cultivatorCaves ?: emptyList(), worldLevels = data.worldLevels ?: emptyList())
        }.distinctUntilChanged()
            .stateIn(gameEngineCore.scopeForStateIn(), kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), WorldMapRenderData())
    }

    // ── Nested types for backward compat ────────────────────────────────

    data class BulkSellOperation(val id: String, val name: String, val quantity: Int, val itemType: String)
    data class BulkSellResult(val soldCount: Int, val totalEarned: Long, val soldItemNames: List<String>, val failedItemNames: List<String>)
}
