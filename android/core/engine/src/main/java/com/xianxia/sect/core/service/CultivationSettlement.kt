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
import kotlin.random.Random
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import com.xianxia.sect.core.util.DomainLog

@Singleton
@GameService("CultivationSettlement")
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

    // Salary tracking
    private val lastSalaryYear = mutableMapOf<String, Int>()

    // Diplomacy event counter
    internal var diplomacyEventsThisMonth = 0
    internal var diplomacyEventsMonth = 0

    fun processSalaryYearly(year: Int) {
        val data = stateStore.gameData.value
        val salaryConfig = data.yearlySalary
        val enabledConfig = data.yearlySalaryEnabled
        val maxLoyalty = GameConfig.Disciple.MAX_LOYALTY
        var totalCost = 0L
        var anyChanged = false

        val disciplesSnapshot = stateStore.disciples.value
        val updatedDisciples = disciplesSnapshot.map { disciple ->
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
                disciple.copy(
                    equipment = disciple.equipment.copy(
                        storageBagSpiritStones = disciple.equipment.storageBagSpiritStones + yearlySalary
                    ),
                    skills = disciple.skills.copy(
                        salaryPaidCount = disciple.skills.salaryPaidCount + elapsedYears,
                        loyalty = (disciple.skills.loyalty + elapsedYears).coerceAtMost(maxLoyalty)
                    )
                )
            } else {
                anyChanged = true
                disciple.copy(
                    skills = disciple.skills.copy(
                        salaryMissedCount = disciple.skills.salaryMissedCount + elapsedYears,
                        loyalty = (disciple.skills.loyalty - elapsedYears).coerceAtLeast(0)
                    )
                )
            }
        }

        if (anyChanged) {
            val finalData = data.copy(spiritStones = data.spiritStones - totalCost)
            scope.launch { stateStore.update {
                gameData = finalData
                discipleTables.clear()
                updatedDisciples.forEach { discipleTables.insert(it) }
            } }
            updatedDisciples.filter { it.isAlive && enabledConfig[it.realm] == true }.forEach {
                lastSalaryYear[it.id] = year
            }
        }
    }

    fun settleSalaryOnBreakthrough(discipleId: String, currentYear: Int) {
        val disciplesSnapshot = stateStore.disciples.value
        val disciple = disciplesSnapshot.find { it.id == discipleId && it.isAlive } ?: return
        val data = stateStore.gameData.value
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
            val updatedDisciples = disciplesSnapshot.map {
                if (it.id == discipleId) it.copy(
                    equipment = it.equipment.copy(
                        storageBagSpiritStones = it.equipment.storageBagSpiritStones + accumulated
                    ),
                    skills = it.skills.copy(
                        salaryPaidCount = it.skills.salaryPaidCount + elapsedYears,
                        loyalty = (it.skills.loyalty + elapsedYears).coerceAtMost(maxLoyalty)
                    )
                ) else it
            }
            val finalData = data.copy(spiritStones = data.spiritStones - accumulated)
            scope.launch { stateStore.update {
                gameData = finalData
                discipleTables.clear()
                updatedDisciples.forEach { discipleTables.insert(it) }
            } }
        } else {
            val updatedDisciples = disciplesSnapshot.map {
                if (it.id == discipleId) it.copy(
                    skills = it.skills.copy(
                        salaryMissedCount = it.skills.salaryMissedCount + elapsedYears,
                        loyalty = (it.skills.loyalty - elapsedYears).coerceAtLeast(0)
                    )
                ) else it
            }
            scope.launch { stateStore.update {
                discipleTables.clear()
                updatedDisciples.forEach { discipleTables.insert(it) }
            } }
        }
        lastSalaryYear[discipleId] = currentYear
    }

    fun processResidenceLoyalty() {
        val maxLoyalty = GameConfig.Disciple.MAX_LOYALTY
        val data = stateStore.gameData.value
        val residentIds = data.residenceSlots.filter { it.isActive }.map { it.discipleId }.toSet()
        val updatedDisciples = stateStore.disciples.value.map { d ->
            if (d.id in residentIds && d.skills.loyalty < maxLoyalty) {
                d.copy(skills = d.skills.copy(loyalty = (d.skills.loyalty + 1).coerceAtMost(maxLoyalty)))
            } else d
        }
        scope.launch { stateStore.update {
            discipleTables.clear()
            updatedDisciples.forEach { discipleTables.insert(it) }
        } }
    }

    /**
     * 政策月度灵石扣除。
     * 直接操作影子状态，同步执行。
     */
    fun processPolicyCosts(state: MutableGameState) {
        val data = state.gameData
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

        if (deductedPolicies.isNotEmpty() || disabledPolicies.isNotEmpty()) {
            var updatedGameData = data
            if (deductedPolicies.isNotEmpty()) {
                updatedGameData = updatedGameData.copy(spiritStones = currentStones)
            }
            if (disabledPolicies.isNotEmpty()) {
                updatedGameData = updatedGameData.copy(sectPolicies = updatedPolicies)
            }
            state.gameData = updatedGameData
        }
    }

    /**
     * 灵矿月度产出结算。
     * 直接操作影子状态，同步执行，不使用异步协程。
     */
    fun processSpiritMineProduction(state: MutableGameState) {
        val data = state.gameData
        val tables = state.discipleTables
        val minerCount = data.spiritMineSlots.count { it.discipleId.isNotEmpty() }
        val baseOutput = GameConfig.Production.SPIRIT_MINE_BASE_OUTPUT_PER_MINER

        var miningBonus = 0.0
        data.spiritMineSlots.forEach { slot ->
            val discipleId = slot.discipleId
            if (discipleId.isNotEmpty()) {
                val idInt = discipleId.toIntOrNull() ?: return@forEach
                if (tables.ids.contains(idInt) && tables.isAlive[idInt] == 1) {
                    val mining = tables.minings[idInt] ?: 0
                    if (mining > GameConfig.Production.SPIRIT_MINE_MINING_THRESHOLD) {
                        miningBonus += (mining - GameConfig.Production.SPIRIT_MINE_MINING_THRESHOLD) *
                            GameConfig.Production.SPIRIT_MINE_MINING_BONUS_RATE
                    }
                }
            }
        }

        val avgMiningBonus = if (minerCount > 0) miningBonus / minerCount else 0.0
        val baseSpiritStones = minerCount * baseOutput.toDouble()
        val boostMultiplier = if (data.sectPolicies.spiritMineBoost) 1.2 else 1.0

        val deaconBonus = data.elderSlots.spiritMineDeaconDisciples.mapNotNull { slot ->
            slot.discipleId?.let { discipleId ->
                val idInt = discipleId.toIntOrNull()
                if (idInt != null && tables.ids.contains(idInt) && tables.isAlive[idInt] == 1) {
                    tables.assemble(idInt)
                } else null
            }
        }.sumOf { disciple ->
            val baseline = GameConfig.PolicyConfig.ELDER_SKILL_BASELINE
            val diff = (DiscipleStatCalculator.getBaseStats(disciple).morality - baseline)
                .coerceAtLeast(0)
            diff * 0.01
        }

        val totalSpiritStones = (
            baseSpiritStones * (1 + avgMiningBonus) * (1 + deaconBonus) * boostMultiplier
        ).toInt()

        // 矿工忠诚度 -1（直接操作组件表）
        data.spiritMineSlots.forEach { slot ->
            val discipleId = slot.discipleId
            if (discipleId.isNotEmpty()) {
                val idInt = discipleId.toIntOrNull() ?: return@forEach
                if (tables.ids.contains(idInt)) {
                    val current = tables.loyalties[idInt] ?: 0
                    tables.loyalties[idInt] = (current - 1).coerceAtLeast(0)
                }
            }
        }

        // 灵石产出
        if (totalSpiritStones > 0) {
            state.gameData = data.copy(spiritStones = data.spiritStones + totalSpiritStones)
        }
    }

    companion object {
        private const val TAG = "CultivationSettlement"
    }
}
