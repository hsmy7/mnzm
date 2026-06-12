package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.*
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.domain.disciple.DiscipleSlotCleanup
import com.xianxia.sect.core.engine.domain.disciple.DiscipleService
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.engine.domain.building.HerbGardenSystem
import com.xianxia.sect.core.engine.domain.building.HerbGardenAuraService
import com.xianxia.sect.core.engine.domain.exploration.CaveExplorationSystem
import com.xianxia.sect.core.engine.domain.exploration.MissionSystem
import com.xianxia.sect.core.engine.domain.battle.AISectAttackManager
import com.xianxia.sect.core.engine.domain.battle.AISectGarrisonManager
import com.xianxia.sect.core.engine.domain.diplomacy.AISectDiscipleManager
import com.xianxia.sect.core.engine.SectWarehouseManager
import com.xianxia.sect.core.engine.WorldMapGenerator
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.registry.*
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.config.DiplomaticEventConfig
import com.xianxia.sect.core.util.BuildingNames
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.core.util.SpiritRootGenerator
import com.xianxia.sect.core.util.NameService
import com.xianxia.sect.core.util.PortraitPool
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.util.CoroutineScopeProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.random.Random
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import com.xianxia.sect.core.util.DomainLog

@Singleton
class CultivationSettlement @Inject constructor(
    private val stateStore: GameStateStore,
    private val inventorySystem: InventorySystem,
    private val inventoryConfig: InventoryConfig,
    private val battleSystem: BattleSystem,
    private val productionCoordinator: ProductionCoordinator,
    private val productionSlotRepository: ProductionSlotRepository,
    private val discipleService: DiscipleService,
    private val cultivationCore: CultivationCore,
    private val breakthroughHandler: DiscipleBreakthroughHandler,
    private val scopeProvider: CoroutineScopeProvider
) {
    private val scope get() = scopeProvider.scope

    // State accessors
    private var currentGameData: GameData
        get() = stateStore.currentTransactionMutableState()?.gameData ?: stateStore.gameData.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.gameData = value; return }
            scope.launch { stateStore.update { gameData = value } }
        }
    private var currentDisciples: List<Disciple>
        get() = stateStore.currentTransactionMutableState()?.disciples ?: stateStore.disciples.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.disciples = value; return }
            scope.launch { stateStore.update { disciples = value } }
        }
    private var pendingNotification: GameNotification?
        get() = stateStore.currentTransactionMutableState()?.pendingNotification
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.pendingNotification = value; return }
            if (value != null) stateStore.setPendingNotification(value)
            else stateStore.clearPendingNotification()
        }
    private var currentEquipmentStacks: List<EquipmentStack>
        get() = stateStore.currentTransactionMutableState()?.equipmentStacks ?: stateStore.equipmentStacks.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.equipmentStacks = value; return }
            scope.launch { stateStore.update { equipmentStacks = value } }
        }
    private var currentEquipmentInstances: List<EquipmentInstance>
        get() = stateStore.currentTransactionMutableState()?.equipmentInstances ?: stateStore.equipmentInstances.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.equipmentInstances = value; return }
            scope.launch { stateStore.update { equipmentInstances = value } }
        }
    private var currentManualInstances: List<ManualInstance>
        get() = stateStore.currentTransactionMutableState()?.manualInstances ?: stateStore.manualInstances.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.manualInstances = value; return }
            scope.launch { stateStore.update { manualInstances = value } }
        }
    private var currentHerbs: List<Herb>
        get() = stateStore.currentTransactionMutableState()?.herbs ?: stateStore.getCurrentHerbs()
        private set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.herbs = value; return }
            scope.launch { stateStore.update { herbs = value } }
        }
    private var currentMaterials: List<Material>
        get() = stateStore.currentTransactionMutableState()?.materials ?: stateStore.getCurrentMaterials()
        private set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.materials = value; return }
            scope.launch { stateStore.update { materials = value } }
        }
    private var currentSeeds: List<Seed>
        get() = stateStore.currentTransactionMutableState()?.seeds ?: stateStore.getCurrentSeeds()
        private set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.seeds = value; return }
            scope.launch { stateStore.update { seeds = value } }
        }
    private var currentBattleLogs: List<BattleLog>
        get() = stateStore.currentTransactionMutableState()?.battleLogs ?: stateStore.battleLogs.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.battleLogs = value; return }
            scope.launch { stateStore.update { battleLogs = value } }
        }
    private var currentTeams: List<ExplorationTeam>
        get() = stateStore.currentTransactionMutableState()?.teams ?: stateStore.teams.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.teams = value; return }
            scope.launch { stateStore.update { teams = value } }
        }

    private suspend fun updateHerbsSync(value: List<Herb>) {
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) { ts.herbs = value; return }
        stateStore.update { herbs = value }
    }
    private suspend fun updateMaterialsSync(value: List<Material>) {
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) { ts.materials = value; return }
        stateStore.update { materials = value }
    }
    private suspend fun updateSeedsSync(value: List<Seed>) {
        val ts = stateStore.currentTransactionMutableState()
        if (ts != null) { ts.seeds = value; return }
        stateStore.update { seeds = value }
    }

    // Salary tracking
    private val lastSalaryYear = mutableMapOf<String, Int>()

    // Diplomacy event counter
    internal var diplomacyEventsThisMonth = 0
    internal var diplomacyEventsMonth = 0

    fun processSalaryYearly(year: Int) {
        val data = currentGameData
        val salaryConfig = data.yearlySalary
        val enabledConfig = data.yearlySalaryEnabled
        val maxLoyalty = GameConfig.Disciple.MAX_LOYALTY
        var totalCost = 0L
        var anyChanged = false

        val updatedDisciples = currentDisciples.map { disciple ->
            if (!disciple.isAlive || enabledConfig[disciple.realm] != true) return@map disciple
            val lastYear = lastSalaryYear[disciple.id] ?: (year - 1)
            if (lastYear >= year) return@map disciple
            val elapsedYears = year - lastYear
            val yearlySalary = (salaryConfig[disciple.realm] ?: 0).toLong() * elapsedYears
            if (yearlySalary <= 0) return@map disciple
            if (disciple.skills.loyalty >= maxLoyalty) return@map disciple

            if (data.spiritStones >= totalCost + yearlySalary) {
                totalCost += yearlySalary
                anyChanged = true
                disciple.copyWith(
                    storageBagSpiritStones = disciple.equipment.storageBagSpiritStones + yearlySalary,
                    salaryPaidCount = disciple.skills.salaryPaidCount + elapsedYears,
                    loyalty = (disciple.skills.loyalty + elapsedYears).coerceAtMost(maxLoyalty)
                )
            } else {
                anyChanged = true
                disciple.copyWith(
                    salaryMissedCount = disciple.skills.salaryMissedCount + elapsedYears,
                    loyalty = (disciple.skills.loyalty - elapsedYears).coerceAtLeast(0)
                )
            }
        }

        if (anyChanged) {
            currentDisciples = updatedDisciples
            currentGameData = data.copy(spiritStones = data.spiritStones - totalCost)
            currentDisciples.filter { it.isAlive && enabledConfig[it.realm] == true }.forEach {
                lastSalaryYear[it.id] = year
            }
        }
    }

    fun settleSalaryOnBreakthrough(discipleId: String, currentYear: Int) {
        val disciple = currentDisciples.find { it.id == discipleId && it.isAlive } ?: return
        val data = currentGameData
        val enabledConfig = data.yearlySalaryEnabled
        if (enabledConfig[disciple.realm] != true) return

        val lastYear = lastSalaryYear[discipleId] ?: (currentYear - 1)
        if (lastYear >= currentYear) return

        val salaryConfig = data.yearlySalary
        val maxLoyalty = GameConfig.Disciple.MAX_LOYALTY
        if (disciple.skills.loyalty >= maxLoyalty) {
            lastSalaryYear[discipleId] = currentYear
            return
        }

        val elapsedYears = currentYear - lastYear
        val accumulated = (salaryConfig[disciple.realm] ?: 0).toLong() * elapsedYears
        if (accumulated <= 0) return

        if (data.spiritStones >= accumulated) {
            currentDisciples = currentDisciples.map {
                if (it.id == discipleId) it.copyWith(
                    storageBagSpiritStones = it.equipment.storageBagSpiritStones + accumulated,
                    salaryPaidCount = it.skills.salaryPaidCount + elapsedYears,
                    loyalty = (it.skills.loyalty + elapsedYears).coerceAtMost(maxLoyalty)
                ) else it
            }
            currentGameData = data.copy(spiritStones = data.spiritStones - accumulated)
        } else {
            currentDisciples = currentDisciples.map {
                if (it.id == discipleId) it.copyWith(
                    salaryMissedCount = it.skills.salaryMissedCount + elapsedYears,
                    loyalty = (it.skills.loyalty - elapsedYears).coerceAtLeast(0)
                ) else it
            }
        }
        lastSalaryYear[discipleId] = currentYear
    }

    fun processResidenceLoyalty() {
        val maxLoyalty = GameConfig.Disciple.MAX_LOYALTY
        val residentIds = currentGameData.residenceSlots.filter { it.isActive }.map { it.discipleId }.toSet()
        currentDisciples = currentDisciples.map { d ->
            if (d.id in residentIds && d.skills.loyalty < maxLoyalty) {
                d.copyWith(loyalty = (d.skills.loyalty + 1).coerceAtMost(maxLoyalty))
            } else d
        }
    }

    fun processPolicyCosts() {
        val data = currentGameData
        val policies = data.sectPolicies
        var currentStones = data.spiritStones
        var updatedPolicies = policies
        val disabledPolicies = mutableListOf<String>()
        val deductedPolicies = mutableListOf<Pair<String, Long>>()

        fun checkAndDeduct(cost: Long, name: String, isEnabled: Boolean, disable: (SectPolicies) -> SectPolicies) {
            if (!isEnabled) return@checkAndDeduct
            if (currentStones >= cost) {
                currentStones -= cost
                deductedPolicies.add(name to cost)
            } else {
                updatedPolicies = disable(updatedPolicies)
                disabledPolicies.add(name)
            }
        }

        checkAndDeduct(GameConfig.PolicyConfig.ENHANCED_SECURITY_COST.toLong(), "增强治安", policies.enhancedSecurity) { it.copy(enhancedSecurity = false) }
        checkAndDeduct(GameConfig.PolicyConfig.ALCHEMY_INCENTIVE_COST.toLong(), "丹道激励", policies.alchemyIncentive) { it.copy(alchemyIncentive = false) }
        checkAndDeduct(GameConfig.PolicyConfig.FORGE_INCENTIVE_COST.toLong(), "锻造激励", policies.forgeIncentive) { it.copy(forgeIncentive = false) }
        checkAndDeduct(GameConfig.PolicyConfig.HERB_CULTIVATION_COST.toLong(), "灵药培育", policies.herbCultivation) { it.copy(herbCultivation = false) }
        checkAndDeduct(GameConfig.PolicyConfig.CULTIVATION_SUBSIDY_COST.toLong(), "修行津贴", policies.cultivationSubsidy) { it.copy(cultivationSubsidy = false) }
        checkAndDeduct(GameConfig.PolicyConfig.MANUAL_RESEARCH_COST.toLong(), "功法研习", policies.manualResearch) { it.copy(manualResearch = false) }

        if (deductedPolicies.isNotEmpty()) {
            currentGameData = currentGameData.copy(spiritStones = currentStones)
        }
        if (disabledPolicies.isNotEmpty()) {
            currentGameData = currentGameData.copy(sectPolicies = updatedPolicies)
        }
    }

    fun processSpiritMineProduction() {
        val data = currentGameData
        val minerCount = data.spiritMineSlots.count { it.discipleId.isNotEmpty() }
        val baseOutput = GameConfig.Production.SPIRIT_MINE_BASE_OUTPUT_PER_MINER

        var miningBonus = 0.0
        data.spiritMineSlots.forEach { slot ->
            val discipleId = slot.discipleId
            if (discipleId.isNotEmpty()) {
                val disciple = currentDisciples.find { it.id == discipleId }
                if (disciple != null) {
                    val mining = DiscipleStatCalculator.getBaseStats(disciple).mining
                    if (mining > GameConfig.Production.SPIRIT_MINE_MINING_THRESHOLD) {
                        miningBonus += (mining - GameConfig.Production.SPIRIT_MINE_MINING_THRESHOLD) * GameConfig.Production.SPIRIT_MINE_MINING_BONUS_RATE
                    }
                }
            }
        }

        val avgMiningBonus = if (minerCount > 0) miningBonus / minerCount else 0.0
        val baseSpiritStones = minerCount * baseOutput.toDouble()
        val boostMultiplier = if (data.sectPolicies.spiritMineBoost) 1.2 else 1.0

        val deaconBonus = data.elderSlots.spiritMineDeaconDisciples.mapNotNull { slot ->
            slot.discipleId?.let { discipleId ->
                currentDisciples.find { it.id == discipleId }
            }
        }.sumOf { disciple ->
            val baseline = 80
            val diff = (DiscipleStatCalculator.getBaseStats(disciple).morality - baseline).coerceAtLeast(0)
            diff * 0.01
        }

        val totalSpiritStones = (baseSpiritStones * (1 + avgMiningBonus) * (1 + deaconBonus) * boostMultiplier).toInt()

        val minerIds = data.spiritMineSlots.filter { it.discipleId.isNotEmpty() }.map { it.discipleId }.toSet()
        currentDisciples = currentDisciples.map { d ->
            if (d.id in minerIds) {
                d.copyWith(loyalty = (d.skills.loyalty - 1).coerceAtLeast(0))
            } else d
        }

        if (totalSpiritStones > 0) {
            currentGameData = data.copy(
                spiritStones = data.spiritStones + totalSpiritStones
            )
        }
    }

    companion object {
        private const val TAG = "CultivationSettlement"
    }
}
