package com.xianxia.sect.core.engine.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.data.*
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.engine.BattleSystem
import com.xianxia.sect.core.engine.BattleMemberData
import com.xianxia.sect.core.engine.production.ProductionCoordinator
import com.xianxia.sect.core.engine.HerbGardenSystem
import com.xianxia.sect.core.data.HerbDatabase
import com.xianxia.sect.core.engine.CaveExplorationSystem
import com.xianxia.sect.core.engine.AICaveTeamGenerator
import com.xianxia.sect.core.engine.SectWarehouseManager
import com.xianxia.sect.core.engine.WorldMapGenerator
import com.xianxia.sect.core.engine.CaveGenerator
import com.xianxia.sect.core.engine.MSTEdge
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.core.engine.AISectAttackManager
import com.xianxia.sect.core.engine.AISectDiscipleManager
import com.xianxia.sect.core.engine.AIBattleWinner
import com.xianxia.sect.core.engine.DiscipleStatCalculator
import com.xianxia.sect.core.engine.DiscipleEquipmentManager
import com.xianxia.sect.core.engine.DiscipleManualManager
import com.xianxia.sect.core.engine.DisciplePillManager
import com.xianxia.sect.core.engine.EquipmentNurtureSystem
import com.xianxia.sect.core.engine.ManualProficiencySystem
import com.xianxia.sect.core.engine.MissionSystem
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.di.ApplicationScopeProvider
import android.util.Log
import com.xianxia.sect.core.util.BuildingNames
import com.xianxia.sect.core.util.GameRandom
import com.xianxia.sect.core.util.NameService
import com.xianxia.sect.core.engine.system.GameSystem
import com.xianxia.sect.core.engine.system.SystemPriority
import com.xianxia.sect.core.state.MutableGameState
import javax.inject.Inject
import javax.inject.Singleton

data class HighFrequencyData(
    val lastUpdateTime: Long = 0L,
    val lastCultivationTime: Long = 0L,
    val cultivationPerSecond: Double = 0.0,
    val totalDisciples: Int = 0,
    val lastBreakthroughCheckTime: Long = 0L,
    val timestamp: Long = 0L,
    val cultivationUpdates: Map<String, Double> = emptyMap(),
    val realtimeCultivation: Map<String, Double>? = null
)

@SystemPriority(order = 200)
@Singleton
class CultivationService @Inject constructor(
    private val stateStore: GameStateStore,
    private val inventorySystem: InventorySystem,
    private val inventoryConfig: InventoryConfig,
    private val battleSystem: BattleSystem,
    private val productionCoordinator: ProductionCoordinator,
    private val productionSlotRepository: ProductionSlotRepository,
    private val eventService: EventService,
    private val applicationScopeProvider: ApplicationScopeProvider
) : GameSystem {
    private val scope get() = applicationScopeProvider.scope
    companion object {
        private const val TAG = "CultivationService"
        private const val TRAVELING_MERCHANT_ITEM_COUNT = 40
        private const val MERCHANT_PITY_THRESHOLD = 10

        private val RARITY_PROBABILITIES = mapOf(
            6 to 0.003,
            5 to 0.027,
            4 to 0.05,
            3 to 0.12,
            2 to 0.40,
            1 to 0.40
        )
    }

    private var currentGameData: GameData
        get() = stateStore.currentTransactionMutableState()?.gameData ?: stateStore.gameData.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.gameData = value; return }
            scope.launch(Dispatchers.IO) { stateStore.update { gameData = value } }
        }

    private var currentDisciples: List<Disciple>
        get() = stateStore.currentTransactionMutableState()?.disciples ?: stateStore.disciples.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.disciples = value; return }
            scope.launch(Dispatchers.IO) { stateStore.update { disciples = value } }
        }
    private var currentEquipmentStacks: List<EquipmentStack>
        get() = stateStore.currentTransactionMutableState()?.equipmentStacks ?: stateStore.equipmentStacks.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.equipmentStacks = value; return }
            scope.launch(Dispatchers.IO) { stateStore.update { equipmentStacks = value } }
        }

    private var currentEquipmentInstances: List<EquipmentInstance>
        get() = stateStore.currentTransactionMutableState()?.equipmentInstances ?: stateStore.equipmentInstances.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.equipmentInstances = value; return }
            scope.launch(Dispatchers.IO) { stateStore.update { equipmentInstances = value } }
        }

    private var currentManualStacks: List<ManualStack>
        get() = stateStore.currentTransactionMutableState()?.manualStacks ?: stateStore.manualStacks.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.manualStacks = value; return }
            scope.launch(Dispatchers.IO) { stateStore.update { manualStacks = value } }
        }

    private var currentManualInstances: List<ManualInstance>
        get() = stateStore.currentTransactionMutableState()?.manualInstances ?: stateStore.manualInstances.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.manualInstances = value; return }
            scope.launch(Dispatchers.IO) { stateStore.update { manualInstances = value } }
        }

    private var currentPills: List<Pill>
        get() = stateStore.currentTransactionMutableState()?.pills ?: stateStore.pills.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.pills = value; return }
            scope.launch(Dispatchers.IO) { stateStore.update { pills = value } }
        }

    private var currentMaterials: List<Material>
        get() = stateStore.currentTransactionMutableState()?.materials ?: stateStore.getCurrentMaterials()
        private set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.materials = value; return }
            scope.launch(Dispatchers.IO) { stateStore.update { materials = value } }
        }

    private var currentHerbs: List<Herb>
        get() = stateStore.currentTransactionMutableState()?.herbs ?: stateStore.getCurrentHerbs()
        private set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.herbs = value; return }
            scope.launch(Dispatchers.IO) { stateStore.update { herbs = value } }
        }

    private var currentSeeds: List<Seed>
        get() = stateStore.currentTransactionMutableState()?.seeds ?: stateStore.getCurrentSeeds()
        private set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.seeds = value; return }
            scope.launch(Dispatchers.IO) { stateStore.update { seeds = value } }
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

    private var currentEvents: List<GameEvent>
        get() = stateStore.currentTransactionMutableState()?.events ?: stateStore.events.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.events = value; return }
            scope.launch(Dispatchers.IO) { stateStore.update { events = value } }
        }

    private var currentBattleLogs: List<BattleLog>
        get() = stateStore.currentTransactionMutableState()?.battleLogs ?: stateStore.battleLogs.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.battleLogs = value; return }
            scope.launch(Dispatchers.IO) { stateStore.update { battleLogs = value } }
        }

    private var currentTeams: List<ExplorationTeam>
        get() = stateStore.currentTransactionMutableState()?.teams ?: stateStore.teams.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.teams = value; return }
            scope.launch(Dispatchers.IO) { stateStore.update { teams = value } }
        }

    private var _highFrequencyData = MutableStateFlow(HighFrequencyData())

    override val systemName: String = "CultivationService"

    override fun initialize() {
        Log.d(TAG, "CultivationService initialized as GameSystem")
    }

    override fun release() {
        Log.d(TAG, "CultivationService released")
    }

    override suspend fun clearForSlot(slotId: Int) {
        resetHighFrequencyData()
    }

    override suspend fun onSecondTick(state: MutableGameState) {
        updateRealtimeCultivation(System.currentTimeMillis(), state)
    }

    override suspend fun onDayTick(state: MutableGameState) {
        val data = state.gameData
        processDailyEvents(data.gameDay, data.gameMonth, data.gameYear)
    }

    override suspend fun onMonthTick(state: MutableGameState) {
        val data = state.gameData
        processMonthlyEvents(data.gameYear, data.gameMonth)
    }

    override suspend fun onYearTick(state: MutableGameState) {
        val data = state.gameData
        processYearlyEvents(data.gameYear)
    }

    fun getHighFrequencyData(): StateFlow<HighFrequencyData> = _highFrequencyData

    fun resetHighFrequencyData() {
        _highFrequencyData.value = HighFrequencyData()
    }

    /**
     * Update realtime cultivation data
     */
    fun updateRealtimeCultivation(currentTimeMillis: Long, state: MutableGameState? = null) {
        val data = state?.gameData ?: currentGameData

        val currentHfd = _highFrequencyData.value
        val lastUpdateTime = currentHfd.lastUpdateTime

        if (lastUpdateTime <= 0) {
            _highFrequencyData.value = currentHfd.copy(
                lastUpdateTime = currentTimeMillis,
                lastCultivationTime = currentTimeMillis
            )
            return
        }

        val elapsedMillis = currentTimeMillis - lastUpdateTime
        if (elapsedMillis < 1000) return

        val elapsedSeconds = elapsedMillis / 1000.0
        val gameSpeed = 1.0

        val idleDisciples = (state?.disciples ?: currentDisciples).filter { it.isAlive && it.status != DiscipleStatus.IN_TEAM }

        if (idleDisciples.isEmpty()) {
            _highFrequencyData.value = currentHfd.copy(lastUpdateTime = currentTimeMillis)
            return
        }
        
        val cultivationUpdates = mutableMapOf<String, Double>()
        val gainMap = mutableMapOf<String, Double>()
        
        val equipmentInstanceMap = (state?.equipmentInstances ?: currentEquipmentInstances).associateBy { it.id }
        val manualInstanceMap = (state?.manualInstances ?: currentManualInstances).associateBy { it.id }

            idleDisciples.forEach { disciple ->
                val cultivationPerSecond = calculateDiscipleCultivationPerSecond(disciple, data)
                val adjustedCultivationPerSecond = cultivationPerSecond * gameSpeed
                val gainedCultivation = adjustedCultivationPerSecond * elapsedSeconds
                
                val newCultivation = disciple.cultivation + gainedCultivation
                
                gainMap[disciple.id] = gainedCultivation
                cultivationUpdates[disciple.id] = newCultivation
            }

            var updatedManualProficiencies = data.manualProficiencies.toMutableMap()
            val equipmentInstanceUpdates = mutableMapOf<String, EquipmentInstance>()
            val updatedDisciples = (state?.disciples ?: currentDisciples).map { disciple ->
                gainMap[disciple.id]?.let { gain ->
                    var d = disciple.copy(cultivation = (disciple.cultivation + gain).coerceIn(0.0, Double.MAX_VALUE))

                    val inLibrary = data.librarySlots.any { it.discipleId == disciple.id }
                    val libraryBonus = if (inLibrary) ManualProficiencySystem.LIBRARY_PROFICIENCY_BONUS_RATE else 0.0
                    val baseProficiencyRate = if (data.sectPolicies.manualResearch) 6.0 else 5.0
                    val proficiencyGain = baseProficiencyRate * (1.0 + libraryBonus) * elapsedSeconds
                    d.manualIds.forEach { manualId ->
                        val manual = manualInstanceMap[manualId] ?: return@forEach
                        val proficiencyList = updatedManualProficiencies.getOrPut(disciple.id) { mutableListOf<ManualProficiencyData>() } as MutableList<ManualProficiencyData>
                        val existingIndex = proficiencyList.indexOfFirst { it.manualId == manualId }
                        val existing = if (existingIndex >= 0) proficiencyList[existingIndex] else null
                        val maxProf = ManualProficiencySystem.getMaxProficiency(manual.rarity)
                        val newProficiency = if (existing != null) {
                            (existing.proficiency + proficiencyGain).coerceAtMost(maxProf)
                        } else {
                            proficiencyGain.coerceAtMost(maxProf)
                        }
                        val newMasteryLevel = ManualProficiencySystem.MasteryLevel.fromProficiency(newProficiency, manual.rarity).level
                        val updated = ManualProficiencyData(
                            manualId = manualId,
                            manualName = manual.name,
                            proficiency = newProficiency,
                            maxProficiency = maxProf.toInt(),
                            level = newMasteryLevel,
                            masteryLevel = newMasteryLevel
                        )
                        if (existingIndex >= 0) {
                            proficiencyList[existingIndex] = updated
                        } else {
                            proficiencyList.add(updated)
                        }
                    }

                    val nurtureGain = 5.0 * elapsedSeconds

                    d.equipment.weaponId?.let { eqId ->
                        val eq = equipmentInstanceMap[eqId] ?: return@let
                        val result = EquipmentNurtureSystem.updateNurtureExp(eq, nurtureGain)
                        equipmentInstanceUpdates[eqId] = result.equipment
                    }
                    d.equipment.armorId?.let { eqId ->
                        val eq = equipmentInstanceMap[eqId] ?: return@let
                        val result = EquipmentNurtureSystem.updateNurtureExp(eq, nurtureGain)
                        equipmentInstanceUpdates[eqId] = result.equipment
                    }
                    d.equipment.bootsId?.let { eqId ->
                        val eq = equipmentInstanceMap[eqId] ?: return@let
                        val result = EquipmentNurtureSystem.updateNurtureExp(eq, nurtureGain)
                        equipmentInstanceUpdates[eqId] = result.equipment
                    }
                    d.equipment.accessoryId?.let { eqId ->
                        val eq = equipmentInstanceMap[eqId] ?: return@let
                        val result = EquipmentNurtureSystem.updateNurtureExp(eq, nurtureGain)
                        equipmentInstanceUpdates[eqId] = result.equipment
                    }

                    d
                } ?: disciple
            }

            if (state != null) state.disciples = updatedDisciples else currentDisciples = updatedDisciples
            if (equipmentInstanceUpdates.isNotEmpty()) {
                if (state != null) {
                    state.equipmentInstances = state.equipmentInstances.map { eq -> equipmentInstanceUpdates[eq.id] ?: eq }
                } else {
                    currentEquipmentInstances = currentEquipmentInstances.map { eq -> equipmentInstanceUpdates[eq.id] ?: eq }
                }
            }
            if (updatedManualProficiencies != data.manualProficiencies) {
                val updatedData = data.copy(manualProficiencies = updatedManualProficiencies)
                if (state != null) state.gameData = updatedData else currentGameData = updatedData
            }
            
            val totalCultivationPerSecond = idleDisciples.sumOf { disciple ->
                calculateDiscipleCultivationPerSecond(disciple, data)
            }

            _highFrequencyData.value = currentHfd.copy(
                lastUpdateTime = currentTimeMillis,
                cultivationPerSecond = totalCultivationPerSecond * gameSpeed,
                totalDisciples = idleDisciples.size,
                cultivationUpdates = cultivationUpdates,
                realtimeCultivation = cultivationUpdates
            )

            processRealtimeBreakthroughs((state?.disciples ?: currentDisciples).filter { it.isAlive && it.status != DiscipleStatus.IN_TEAM }, data)
    }

    /**
     * 子嗣产出系统 - 每天处理一次
     *
     * 条件：女性弟子、有道侣(partnerId!=null)、道侣存活、未处于冷却期
     * 概率：每天 0.08%
     * 冷却：lastChildYear 距今不到1年则跳过
     */
    private fun processChildBirth(currentYear: Int) {
        val allDisciples = currentDisciples
        val discipleMap = allDisciples.associateBy { it.id }

        // 筛选符合条件的女性弟子
        val eligibleMothers = allDisciples.filter { mother ->
            mother.isAlive &&
            mother.gender == "female" &&
            mother.social.partnerId != null &&
            (currentYear - mother.social.lastChildYear >= 1)
        }

        for (mother in eligibleMothers) {
            val fatherId = mother.social.partnerId ?: continue
            val father = discipleMap[fatherId]

            // 道侣必须存活
            if (father == null || !father.isAlive) continue

            // 0.08% 概率诞下子嗣
            if (GameRandom.nextDouble() < 0.0008) {
                val child = createChild(mother, father, currentYear)
                val updatedList = allDisciples + child
                currentDisciples = updatedList.map {
                    if (it.id == mother.id) it.copyWith(lastChildYear = currentYear) else it
                }

                val genderText = if (child.gender == "male") "子" else "女"
                eventService.addGameEvent("${mother.name} 诞下一${genderText} ${child.name}", EventType.SUCCESS)

                return
            }
        }
    }

    /**
     * 创建子嗣弟子
     *
     * @param mother 母亲弟子
     * @param father 父亲弟子
     * @param currentYear 当前年份
     * @return 新创建的子嗣弟子
     */
    private fun createChild(mother: Disciple, father: Disciple, currentYear: Int): Disciple {
        val id = java.util.UUID.randomUUID().toString()
        val gender = if (GameRandom.nextBoolean()) "male" else "female"

        val fatherSurname = if (father.surname.isNotEmpty()) father.surname else NameService.extractSurname(father.name)
        val existingNames = (currentDisciples + currentGameData.recruitList).map { it.name }.toSet()
        val nameResult = NameService.inheritName(fatherSurname, gender, existingNames)

        val allSpiritRootTypes = listOf("metal", "wood", "water", "fire", "earth")
        val rootCount = when (GameRandom.nextInt(100)) {
            in 0..4 -> 1
            in 5..24 -> 2
            in 25..54 -> 3
            in 55..84 -> 4
            else -> 5
        }
        val shuffled = allSpiritRootTypes.toMutableList()
        for (i in shuffled.indices) {
            val j = GameRandom.nextInt(i, shuffled.size)
            val tmp = shuffled[i]
            shuffled[i] = shuffled[j]
            shuffled[j] = tmp
        }
        val spiritRootType = shuffled.take(rootCount).joinToString(",")

        // 基础属性方差（与 recruitDisciple 一致的范围）
        val hpVariance = GameRandom.nextInt(-50, 51)
        val mpVariance = GameRandom.nextInt(-50, 51)
        val physicalAttackVariance = GameRandom.nextInt(-50, 51)
        val magicAttackVariance = GameRandom.nextInt(-50, 51)
        val physicalDefenseVariance = GameRandom.nextInt(-50, 51)
        val magicDefenseVariance = GameRandom.nextInt(-50, 51)
        val speedVariance = GameRandom.nextInt(-50, 51)

        val spiritRootCount = spiritRootType.split(",").size
        val comprehension = when (spiritRootCount) {
            1 -> GameRandom.nextInt(80, 101)
            2 -> GameRandom.nextInt(60, 101)
            3 -> GameRandom.nextInt(40, 101)
            4 -> GameRandom.nextInt(20, 101)
            else -> GameRandom.nextInt(1, 101)
        }

        val disciple = Disciple(
            id = id,
            name = nameResult.fullName,
            surname = nameResult.surname,
            gender = gender,
            age = 1,
            realm = 9,
            realmLayer = 0, // 未成年，无境界
            spiritRootType = spiritRootType,
            status = DiscipleStatus.IDLE,
            discipleType = "outer",
            talentIds = com.xianxia.sect.core.data.TalentDatabase.generateTalentsForDisciple().map { it.id },
            combat = com.xianxia.sect.core.model.CombatAttributes(
                hpVariance = hpVariance,
                mpVariance = mpVariance,
                physicalAttackVariance = physicalAttackVariance,
                magicAttackVariance = magicAttackVariance,
                physicalDefenseVariance = physicalDefenseVariance,
                magicDefenseVariance = magicDefenseVariance,
                speedVariance = speedVariance
            ),
            social = com.xianxia.sect.core.model.SocialData(
                parentId1 = mother.id,
                parentId2 = father.id
            ),
            skills = com.xianxia.sect.core.model.SkillStats(
                intelligence = GameRandom.nextInt(1, 101),
                charm = GameRandom.nextInt(1, 101),
                loyalty = GameRandom.nextInt(1, 101),
                comprehension = comprehension,
                morality = GameRandom.nextInt(1, 101),
                artifactRefining = GameRandom.nextInt(1, 101),
                pillRefining = GameRandom.nextInt(1, 101),
                spiritPlanting = GameRandom.nextInt(1, 101),
                teaching = GameRandom.nextInt(1, 101)
            )
        ).apply {
            val baseStats = Disciple.calculateBaseStatsWithVariance(
                hpVariance, mpVariance, physicalAttackVariance, magicAttackVariance,
                physicalDefenseVariance, magicDefenseVariance, speedVariance
            )
            combat.baseHp = baseStats.baseHp
            combat.baseMp = baseStats.baseMp
            combat.basePhysicalAttack = baseStats.basePhysicalAttack
            combat.baseMagicAttack = baseStats.baseMagicAttack
            combat.basePhysicalDefense = baseStats.basePhysicalDefense
            combat.baseMagicDefense = baseStats.baseMagicDefense
            combat.baseSpeed = baseStats.baseSpeed

            // 计算寿命天赋加成（如"寿元绵长"/"寿元亏损"）
            val talentEffects = TalentDatabase.calculateTalentEffects(talentIds)
            val lifespanBonus = talentEffects["lifespan"] ?: 0.0
            val baseLifespan = GameConfig.Realm.get(realm).maxAge
            lifespan = (baseLifespan * (1.0 + lifespanBonus)).toInt().coerceAtLeast(1)
        }

        return disciple
    }

    /**
     * Calculate cultivation gain per second for a disciple
     */
    private fun calculateDiscipleCultivationPerSecond(disciple: Disciple, data: GameData): Double {
        val buildingBonus = calculateBuildingCultivationBonus(disciple, data)

        val allDisciples = currentDisciples.associateBy { it.id }
        val (wenDaoElderBonus, wenDaoMastersBonus) = calculatePreachingBonuses(disciple, data, allDisciples, "outer")
        val (qingyunElderBonus, qingyunMastersBonus) = calculatePreachingBonuses(disciple, data, allDisciples, "inner")

        var cultivationSubsidyBonus = 0.0
        if (data.sectPolicies.cultivationSubsidy && disciple.realm > 5) {
            cultivationSubsidyBonus = GameConfig.PolicyConfig.CULTIVATION_SUBSIDY_BASE_EFFECT
        }

        val manualInstanceMap = currentManualInstances.associateBy { it.id }
        val allProficiencies = data.manualProficiencies.mapValues { (_, list) ->
            list.associateBy { it.manualId }
        }
        val discipleProficiencies = allProficiencies[disciple.id] ?: emptyMap()

        return DiscipleStatCalculator.calculateCultivationSpeed(
            disciple = disciple,
            manuals = manualInstanceMap,
            manualProficiencies = discipleProficiencies,
            buildingBonus = buildingBonus,
            preachingElderBonus = wenDaoElderBonus + qingyunElderBonus,
            preachingMastersBonus = wenDaoMastersBonus + qingyunMastersBonus,
            cultivationSubsidyBonus = cultivationSubsidyBonus
        ).coerceIn(1.0, 1000.0)
    }

    private fun calculatePreachingBonuses(
        disciple: Disciple,
        data: GameData,
        allDisciples: Map<String, Disciple>,
        targetDiscipleType: String
    ): Pair<Double, Double> {
        val elderSlots = data.elderSlots
        val preachingElder = when (targetDiscipleType) {
            "outer" -> elderSlots.preachingElder.let { id -> if (id.isNotEmpty()) allDisciples[id] else null }
            "inner" -> elderSlots.qingyunPreachingElder.let { id -> if (id.isNotEmpty()) allDisciples[id] else null }
            else -> null
        }
        val preachingMasters = when (targetDiscipleType) {
            "outer" -> elderSlots.preachingMasters.mapNotNull { slot -> slot.discipleId?.let { allDisciples[it] } }
            "inner" -> elderSlots.qingyunPreachingMasters.mapNotNull { slot -> slot.discipleId?.let { allDisciples[it] } }
            else -> emptyList()
        }

        return DiscipleStatCalculator.calculatePreachingBonus(
            disciple = disciple,
            targetDiscipleType = targetDiscipleType,
            preachingElder = preachingElder,
            preachingMasters = preachingMasters
        )
    }

    /**
     * Process realtime breakthrough checks
     *
     * Uses maxCultivation as threshold (consistent with Disciple.canBreakthrough()).
     * When cultivation >= maxCultivation, auto attempt breakthrough.
     * If disciple doesn't meet soul power requirement for major breakthrough,
     * cultivation is NOT reset - disciple waits until soul power is sufficient.
     */
    private fun processRealtimeBreakthroughs(idleDisciples: List<Disciple>, data: GameData) {
        val candidates = idleDisciples.filter { disciple ->
            disciple.realm > 0 && disciple.cultivation >= disciple.maxCultivation
        }

        if (candidates.isEmpty()) return

        val candidateIds = candidates.map { it.id }.toSet()
        val updatedDisciples = currentDisciples.map { disciple ->
            if (disciple.id !in candidateIds) return@map disciple
            if (disciple.cultivation < disciple.maxCultivation || disciple.realm <= 0) return@map disciple

            var newCultivation = disciple.cultivation
            var newRealm = disciple.realm
            var newRealmLayer = disciple.realmLayer
            var newBreakthroughFailCount = disciple.combat.breakthroughFailCount
            var newLifespan = disciple.lifespan
            var shouldContinue = true

            while (shouldContinue && newRealm > 0) {
                val currentMaxCultivation = if (newRealm == 0) Double.MAX_VALUE else {
                    val base = GameConfig.Realm.get(newRealm).cultivationBase
                    base * (1.0 + (newRealmLayer - 1) * 0.2)
                }
                if (newCultivation < currentMaxCultivation) break

                val isMajorBreakthrough = newRealmLayer >= GameConfig.Realm.get(newRealm).maxLayers
                if (isMajorBreakthrough && !DiscipleStatCalculator.meetsSoulPowerRequirement(newRealm, newRealmLayer, disciple.equipment.soulPower)) {
                    shouldContinue = false
                    continue
                }

                val success = tryBreakthrough(disciple)
                if (success) {
                    newCultivation = 0.0
                    newBreakthroughFailCount = 0
                    if (newRealmLayer < 9) {
                        newRealmLayer++
                    } else {
                        newRealm--
                        newRealmLayer = 1
                    }
                    newLifespan += getLifespanGainForRealm(newRealm)

                    // 寿命天赋影响突破增寿
                    val lifespanTalentBonus = TalentDatabase.calculateTalentEffects(disciple.talentIds)["lifespan"] ?: 0.0
                    if (lifespanTalentBonus != 0.0) {
                        val extraLifespan = (getLifespanGainForRealm(newRealm) * lifespanTalentBonus).toInt()
                        newLifespan += extraLifespan
                    }
                } else {
                    newCultivation = 0.0
                    newBreakthroughFailCount++
                    shouldContinue = false
                }
            }

            disciple.copyWith(
                cultivation = newCultivation,
                realm = newRealm,
                realmLayer = newRealmLayer,
                lifespan = newLifespan,
                breakthroughFailCount = newBreakthroughFailCount
            )
        }

        currentDisciples = updatedDisciples
    }

    private fun getLifespanGainForRealm(realm: Int): Int {
        return when (realm) {
            8 -> 50
            7 -> 100
            6 -> 200
            5 -> 400
            4 -> 800
            3 -> 1500
            2 -> 3000
            1 -> 5000
            0 -> 10000
            else -> 0
        }
    }

    /**
     * Advance game time by one day
     */
    suspend fun advanceDay(state: MutableGameState? = null) {
        val data = state?.gameData ?: currentGameData
        var newDay = data.gameDay + 1
        var newMonth = data.gameMonth
        var newYear = data.gameYear
        var monthChanged = false

        if (newDay > 30) {
            newDay = 1
            newMonth++
            monthChanged = true
            if (newMonth > 12) {
                newMonth = 1
                newYear++
            }
        }

        val isYearChanged = newYear > data.gameYear

        val updatedData = data.copy(
            gameDay = newDay,
            gameMonth = newMonth,
            gameYear = newYear
        )
        if (state != null) state.gameData = updatedData else currentGameData = updatedData

        processDailyEvents(newDay, newMonth, newYear)

        if (isYearChanged) {
            processYearlyEvents(newYear)
        }

        if (monthChanged) {
            processMonthlyEvents(newYear, newMonth)
        }
    }

    /**
     * Advance game time by one month
     */
    suspend fun advanceMonth(state: MutableGameState? = null) {
        val data = state?.gameData ?: currentGameData
        var newMonth = data.gameMonth + 1
        var newYear = data.gameYear

        if (newMonth > 12) {
            newMonth = 1
            newYear++
        }

        val isYearChanged = newYear > data.gameYear

        val updatedData = data.copy(
            gameMonth = newMonth,
            gameYear = newYear,
            gameDay = 1
        )
        if (state != null) state.gameData = updatedData else currentGameData = updatedData

        if (isYearChanged) {
            processYearlyEvents(newYear)
        }

        processMonthlyEvents(newYear, newMonth)
    }

    /**
     * Advance game time by one year
     */
    suspend fun advanceYear(state: MutableGameState? = null) {
        val data = state?.gameData ?: currentGameData
        val newYear = data.gameYear + 1

        val updatedData = data.copy(
            gameYear = newYear,
            gameMonth = 1,
            gameDay = 1
        )
        if (state != null) state.gameData = updatedData else currentGameData = updatedData

        processYearlyEvents(newYear)

        processMonthlyEvents(newYear, 1)
    }

    /**
     * Process daily events
     */
    private suspend fun processDailyEvents(day: Int, month: Int, year: Int) {
        // Update exploration teams movement
        updateExplorationTeamsMovement(day, month, year)

        // Update cave exploration teams movement
        updateCaveExplorationTeamsMovement(day, month, year)

        // Process AI battle team movement
        processAIBattleTeamMovement()

        processPlayerBattleTeamMovement()

        // Check exploration arrivals
        checkExplorationArrivals()

        // Process child birth (子嗣产出)
        processChildBirth(year)

        // Daily recovery: disciples recover 1% HP and MP (except those in battle)
        processDailyRecovery()

        // Pill effect duration decay (daily)
        processPillDurationDecay()

        // Disciple auto-use items (daily)
        processAutoUseItems(year, month, day)
    }

    private fun processPillDurationDecay() {
        currentDisciples = currentDisciples.map { disciple ->
            var updated = disciple

            if (updated.cultivationSpeedDuration > 0) {
                val newDuration = updated.cultivationSpeedDuration - 1
                if (newDuration <= 0) {
                    updated = updated.copy(
                        cultivationSpeedBonus = 0.0,
                        cultivationSpeedDuration = 0
                    )
                } else {
                    updated = updated.copy(cultivationSpeedDuration = newDuration)
                }
            }

            if (updated.pillEffects.pillEffectDuration > 0) {
                val newDuration = updated.pillEffects.pillEffectDuration - 1
                if (newDuration <= 0) {
                    updated = updated.copy(pillEffects = PillEffects())
                } else {
                    updated = updated.copy(pillEffects = updated.pillEffects.copy(pillEffectDuration = newDuration))
                }
            }

            updated
        }
    }

    private fun processAutoUseItems(year: Int, month: Int, day: Int) {
        val equipmentStacksList = currentEquipmentStacks
        val equipmentInstancesMap = currentEquipmentInstances.associateBy { it.id }
        val manualStacksList = currentManualStacks
        val manualInstancesMap = currentManualInstances.associateBy { it.id }

        currentDisciples = currentDisciples.map { disciple ->
            if (!disciple.isAlive) return@map disciple

            var updatedDisciple = disciple

            val pillResult = DisciplePillManager.processAutoUsePills(
                disciple = updatedDisciple,
                gameYear = year,
                gameMonth = month,
                gameDay = day
            )
            if (pillResult.disciple != updatedDisciple) {
                updatedDisciple = pillResult.disciple
                pillResult.events.forEach { eventService.addGameEvent(it, EventType.SUCCESS) }
            }

            val equipResult = DiscipleEquipmentManager.processAutoEquip(
                disciple = updatedDisciple,
                equipmentStacks = equipmentStacksList,
                equipmentInstances = equipmentInstancesMap,
                gameYear = year,
                gameMonth = month,
                gameDay = day,
                maxStack = inventoryConfig.getMaxStackSize("equipment_stack")
            )
            if (equipResult.newInstances.isNotEmpty()) {
                updatedDisciple = equipResult.disciple
                currentEquipmentInstances = currentEquipmentInstances + equipResult.newInstances
                val replacedIds = equipResult.replacedInstances.map { it.id }.toSet()
                if (replacedIds.isNotEmpty()) {
                    currentEquipmentInstances = currentEquipmentInstances.filter { it.id !in replacedIds }
                }
                equipResult.stackUpdates.forEach { update ->
                    if (update.isDeletion) {
                        currentEquipmentStacks = currentEquipmentStacks.filter { it.id != update.stackId }
                    } else {
                        currentEquipmentStacks = currentEquipmentStacks.map {
                            if (it.id == update.stackId) it.copy(quantity = update.newQuantity) else it
                        }
                    }
                }
                equipResult.replacedEquipmentStacks.forEach { replacedStack ->
                    val existingStack = currentEquipmentStacks.find { it.id == replacedStack.id }
                    if (existingStack != null) {
                        val maxStack = inventoryConfig.getMaxStackSize("equipment_stack")
                        currentEquipmentStacks = currentEquipmentStacks.map {
                            if (it.id == replacedStack.id) it.copy(quantity = (it.quantity + 1).coerceAtMost(maxStack)) else it
                        }
                    } else {
                        currentEquipmentStacks = currentEquipmentStacks + replacedStack
                    }
                }
                equipResult.events.forEach { eventService.addGameEvent(it, EventType.SUCCESS) }
            }

            val manualResult = DiscipleManualManager.processAutoLearn(
                disciple = updatedDisciple,
                manualStacks = manualStacksList,
                manualInstances = manualInstancesMap,
                gameYear = year,
                gameMonth = month,
                gameDay = day,
                maxStack = inventoryConfig.getMaxStackSize("manual_stack")
            )
            if (manualResult.newInstance != null) {
                updatedDisciple = manualResult.disciple
                manualResult.newInstance.let { newInstance ->
                    currentManualInstances = currentManualInstances + newInstance
                }
                manualResult.replacedInstance?.let { replaced ->
                    currentManualInstances = currentManualInstances.filter { it.id != replaced.id }
                    val updatedProficiencies = currentGameData.manualProficiencies.toMutableMap()
                    updatedProficiencies[updatedDisciple.id]?.let { profList ->
                        val filtered = profList.filter { it.manualId != replaced.id }
                        if (filtered.isEmpty()) {
                            updatedProficiencies.remove(updatedDisciple.id)
                        } else {
                            updatedProficiencies[updatedDisciple.id] = filtered
                        }
                    }
                    currentGameData = currentGameData.copy(manualProficiencies = updatedProficiencies)
                }
                manualResult.stackUpdate?.let { update ->
                    if (update.isDeletion) {
                        currentManualStacks = currentManualStacks.filter { it.id != update.stackId }
                    } else {
                        currentManualStacks = currentManualStacks.map {
                            if (it.id == update.stackId) it.copy(quantity = update.newQuantity) else it
                        }
                    }
                }
                manualResult.replacedManualStack?.let { replacedStack ->
                    val existingStack = currentManualStacks.find { it.id == replacedStack.id }
                    if (existingStack != null) {
                        val maxStack = inventoryConfig.getMaxStackSize("manual_stack")
                        currentManualStacks = currentManualStacks.map {
                            if (it.id == replacedStack.id) it.copy(quantity = (it.quantity + 1).coerceAtMost(maxStack)) else it
                        }
                    } else {
                        currentManualStacks = currentManualStacks + replacedStack
                    }
                }
                manualResult.events.forEach { eventService.addGameEvent(it, EventType.SUCCESS) }
            }

            updatedDisciple
        }
    }

    private fun processDailyRecovery() {
        val inBattleIds = currentTeams
            .filter { it.status == ExplorationStatus.EXPLORING }
            .flatMap { it.memberIds }
            .toSet() +
            currentGameData.caveExplorationTeams
                .filter { it.status == CaveExplorationStatus.EXPLORING }
                .flatMap { it.memberIds }
                .toSet()

        currentDisciples = currentDisciples.map { disciple ->
            if (!disciple.isAlive || disciple.id in inBattleIds) return@map disciple

            val maxHp = disciple.combat.baseHp
            val maxMp = disciple.combat.baseMp
            val currentHp = disciple.combat.currentHp
            val currentMp = disciple.combat.currentMp

            val hpRecovery = (maxHp * 0.01).toInt().coerceAtLeast(1)
            val mpRecovery = (maxMp * 0.01).toInt().coerceAtLeast(1)

            val newHp = if (currentHp < 0) currentHp else (currentHp + hpRecovery).coerceAtMost(maxHp)
            val newMp = if (currentMp < 0) currentMp else (currentMp + mpRecovery).coerceAtMost(maxMp)

            if (newHp == currentHp && newMp == currentMp) return@map disciple

            disciple.copyWith(currentHp = newHp, currentMp = newMp)
        }
    }

    /**
     * Process monthly events - core game logic
     */
    private suspend fun processMonthlyEvents(year: Int, month: Int) {
        // 0-3: 生产/经济逻辑已迁移至 ProductionSubsystem 和 EconomySubsystem

        // 4. 藏经阁加成已在 updateRealtimeCultivation() 中实时处理，无需月度处理

        // 6. Process dungeon monthly exploration (秘境月度探索)
        processDungeonMonthlyExploration()

        // 7. Process cave exploration
        processCaveLifecycle(year, month)

        // 8. Process AI sect operations
        processAISectOperations(year, month)

        // 9. Process scout info expiry
        processScoutInfoExpiry(year, month)

        // 10. Process diplomacy events
        processDiplomacyMonthlyEvents(year, month)

        // 11. Process outer tournament (every 3 years)
        if (month == 1) {
            processOuterTournament(year)
        }

        // 12. Process law enforcement monthly check
        processLawEnforcementMonthly()

        // 13. Process partner matching (道侣匹配)
        processPartnerMatching()

        // 14. Process completed missions and refresh available missions
        processCompletedMissions()
        processMissionRefresh()
    }

    private fun processCompletedMissions() {
        val data = currentGameData
        val currentYear = data.gameYear
        val currentMonth = data.gameMonth
        val completedIds = mutableListOf<String>()
        val remainingActive = mutableListOf<ActiveMission>()

        for (activeMission in data.activeMissions) {
            if (activeMission.isComplete(currentYear, currentMonth)) {
                completedIds.add(activeMission.id)

                val aliveDisciples = activeMission.discipleIds.mapNotNull { did ->
                    currentDisciples.find { it.id == did && it.isAlive }
                }
                val allDead = aliveDisciples.isEmpty()

                if (allDead) {
                    eventService.addGameEvent("任务「${activeMission.missionName}」失败，执行弟子全部阵亡", EventType.WARNING)
                } else {
                    val equipMap = currentEquipmentInstances.associateBy { it.id }
                    val manualMap = currentManualInstances.associateBy { it.id }
                    val proficiencies = currentGameData.manualProficiencies.mapValues { (_, list) ->
                        list.associateBy { it.manualId }
                    }
                    val result = MissionSystem.processMissionCompletion(
                        activeMission, aliveDisciples, equipMap, manualMap, proficiencies, battleSystem
                    )
                    if (result.spiritStones > 0) {
                        currentGameData = currentGameData.copy(
                            spiritStones = currentGameData.spiritStones + result.spiritStones.toLong()
                        )
                    }
                    result.materials.forEach { material ->
                        inventorySystem.addMaterial(material)
                    }
                    result.pills.forEach { pill -> inventorySystem.addPill(pill) }
                    result.equipmentStacks.forEach { equip -> inventorySystem.addEquipmentStack(equip) }
                    result.manualStacks.forEach { manual -> inventorySystem.addManualStack(manual) }
                    if (result.victory) {
                        eventService.addGameEvent("任务「${activeMission.missionName}」已完成，获得奖励", EventType.SUCCESS)
                    } else {
                        eventService.addGameEvent("任务「${activeMission.missionName}」战斗失败，未获得奖励", EventType.WARNING)
                    }
                }

                for (did in activeMission.discipleIds) {
                    val disciple = currentDisciples.find { it.id == did }
                    if (disciple != null && disciple.isAlive) {
                        currentDisciples = currentDisciples.map {
                            if (it.id == did) it.copy(status = DiscipleStatus.IDLE) else it
                        }
                    }
                }
            } else {
                remainingActive.add(activeMission)
            }
        }

        if (completedIds.isNotEmpty()) {
            currentGameData = currentGameData.copy(activeMissions = remainingActive)
        }
    }

    private fun processMissionRefresh() {
        val data = currentGameData
        val result = MissionSystem.processMonthlyRefresh(
            data.availableMissions,
            data.gameYear,
            data.gameMonth
        )
        currentGameData = currentGameData.copy(availableMissions = result.cleanedMissions)
    }

    /**
     * Process yearly events
     */
    private suspend fun processYearlyEvents(year: Int) {
        // 1. Process disciple aging and natural death
        processDiscipleAging(year)

        // 2. Process yearly aging effects
        processYearlyAging(year)

        // 3. Refresh recruit list (每年一月刷新招募弟子列表)
        refreshRecruitList(year)

        // 4. Refresh traveling merchant (每年一月刷新云游商人)
        refreshTravelingMerchant(year, 1)

        // 5. Process cross-sect partner matching
        processCrossSectPartnerMatching(year, 1)

        // 6. Generate items for AI sects
        generateYearlyItemsForAISects()

        // 7. Process alliance expiry
        checkAllianceExpiry(year)

        // 8. Process alliance favor drop
        checkAllianceFavorDrop()

        // 9. Process AI alliances
        processAIAlliances(year)

        // 10. Process reflection cliff release (思过崖期满释放)
        processReflectionRelease(year)

        // 11. Process favor decay for high favor relations
        processFavorDecay(year)
    }

    /**
     * Process disciple aging
     */
    private suspend fun processDiscipleAging(currentYear: Int) {
        val data = currentGameData
        val updatedDisciples = currentDisciples.mapNotNull { disciple ->
            if (!disciple.isAlive) return@mapNotNull disciple

            // Age the disciple
            var agedDisciple = disciple.copy(age = disciple.age + 1)

            // 子嗣成长：年满5岁且 realmLayer==0（未成年）时，自动开启修炼之路
            if (agedDisciple.age == 5 && agedDisciple.realmLayer == 0) {
                agedDisciple = agedDisciple.copyWith(realmLayer = 1, status = DiscipleStatus.IDLE)
                eventService.addGameEvent("${agedDisciple.name} 年满五岁，开启修炼之路（练气一层）", EventType.SUCCESS)
            }

            // Check for natural death (old age)
            // 动态计算天赋寿命加成（处理老存档弟子创建时未应用天赋寿命的情况）
            val talentEffects = TalentDatabase.calculateTalentEffects(agedDisciple.talentIds)
            val lifespanBonus = talentEffects["lifespan"] ?: 0.0
            val realmMaxAge = GameConfig.Realm.get(agedDisciple.realm).maxAge
            val talentLifespan = (realmMaxAge * (1.0 + lifespanBonus)).toInt().coerceAtLeast(1)
            val maxAge = maxOf(agedDisciple.lifespan, realmMaxAge, talentLifespan)
            if (agedDisciple.age >= maxAge) {
                eventService.addGameEvent("${agedDisciple.name}因寿元耗尽而坐化，享年${agedDisciple.age}岁", EventType.INFO)
                handleDiscipleDeath(agedDisciple)
                null // Remove dead disciple
            } else {
                agedDisciple
            }
        }

        currentDisciples = updatedDisciples
    }

    /**
     * Handle disciple death
     */
    private suspend fun handleDiscipleDeath(disciple: Disciple, isOutsideSect: Boolean = false) {
        clearDiscipleFromAllSlots(disciple.id)

        if (isOutsideSect) {
            disciple.equipment.weaponId?.let { removeEquipmentFromDisciple(disciple.id, it) }
            disciple.equipment.armorId?.let { removeEquipmentFromDisciple(disciple.id, it) }
            disciple.equipment.bootsId?.let { removeEquipmentFromDisciple(disciple.id, it) }
            disciple.equipment.accessoryId?.let { removeEquipmentFromDisciple(disciple.id, it) }

            disciple.manualIds.forEach { manualId ->
                currentManualInstances = currentManualInstances.map {
                    if (it.id == manualId) it.copy(isLearned = false, ownerId = null) else it
                }
            }

            val data = currentGameData
            val updatedProficiencies = data.manualProficiencies.toMutableMap()
            updatedProficiencies.remove(disciple.id)
            if (updatedProficiencies != data.manualProficiencies) {
                currentGameData = data.copy(manualProficiencies = updatedProficiencies)
            }
        } else {
            disciple.equipment.weaponId?.let { returnEquipmentToWarehouse(it) }
            disciple.equipment.armorId?.let { returnEquipmentToWarehouse(it) }
            disciple.equipment.bootsId?.let { returnEquipmentToWarehouse(it) }
            disciple.equipment.accessoryId?.let { returnEquipmentToWarehouse(it) }

            disciple.equipment.storageBagItems.filter { it.itemType == "equipment_stack" || it.itemType == "equipment_instance" }.forEach { bagItem ->
                returnEquipmentToWarehouse(bagItem.itemId)
            }

            disciple.equipment.storageBagItems.filter { it.itemType == "manual_stack" || it.itemType == "manual_instance" }.forEach { bagItem ->
                currentManualInstances = currentManualInstances.map {
                    if (it.id == bagItem.itemId) it.copy(isLearned = false, ownerId = null) else it
                }
            }

            disciple.manualIds.forEach { manualId ->
                currentManualInstances = currentManualInstances.map {
                    if (it.id == manualId) it.copy(isLearned = false, ownerId = null) else it
                }
            }

            val data = currentGameData
            val updatedProficiencies = data.manualProficiencies.toMutableMap()
            updatedProficiencies.remove(disciple.id)
            if (updatedProficiencies != data.manualProficiencies) {
                currentGameData = data.copy(manualProficiencies = updatedProficiencies)
            }
        }
    }

    /**
     * Try breakthrough for a disciple - returns whether it succeeded
     */
    private fun tryBreakthrough(disciple: Disciple): Boolean {
        val data = currentGameData
        val elderSlots = data.elderSlots
        val allDisciples = currentDisciples.associateBy { it.id }

        val isMajorBreakthrough = disciple.realmLayer >= GameConfig.Realm.get(disciple.realm).maxLayers

        if (isMajorBreakthrough && !DiscipleStatCalculator.meetsSoulPowerRequirement(disciple)) {
            val targetRealm = disciple.realm - 1
            val requiredSoul = GameConfig.Realm.getSoulPowerRequirement(targetRealm)
            eventService.addGameEvent("${disciple.name}神魂不足（${disciple.equipment.soulPower}/$requiredSoul），无法突破至${GameConfig.Realm.getName(targetRealm)}", EventType.WARNING)
            return false
        }

        val innerElderId = elderSlots.innerElder
        val innerElderComprehension = if (innerElderId.isNotEmpty() && disciple.discipleType == "inner") {
            val elder = allDisciples[innerElderId]
            // realm越小境界越高，disciple.realm >= elder.realm 表示弟子境界不高于长老，长老才能指导
            if (elder != null && elder.isAlive && disciple.realm >= elder.realm) {
                elder.skills.comprehension
            } else {
                0
            }
        } else {
            0
        }

        val outerElderComprehensionBonus = calculateOuterElderBreakthroughBonus(disciple, data, allDisciples)

        val chance = DiscipleStatCalculator.getBreakthroughChance(
            disciple = disciple,
            innerElderComprehension = innerElderComprehension,
            outerElderComprehensionBonus = outerElderComprehensionBonus
        )
        val success = Random.nextDouble() < chance

        if (success) {
            val nextLayer = if (disciple.realmLayer < 9) disciple.realmLayer + 1 else 1
            val targetRealm = if (disciple.realmLayer < 9) disciple.realm else disciple.realm - 1
            val targetRealmName = GameConfig.Realm.getName(targetRealm)
            if (isMajorBreakthrough) {
                eventService.addGameEvent("恭喜！${disciple.name}成功突破至${targetRealmName}！", EventType.SUCCESS)
            }
        } else {
            if (isMajorBreakthrough) {
                eventService.addGameEvent("${disciple.name}尝试突破${GameConfig.Realm.getName(disciple.realm - 1)}失败", EventType.WARNING)
            }
        }

        return success
    }

    private fun calculateOuterElderBreakthroughBonus(disciple: Disciple, data: GameData, allDisciples: Map<String, Disciple>): Double {
        if (disciple.discipleType != "outer") return 0.0

        val outerElderId = data.elderSlots.outerElder
        if (outerElderId.isEmpty()) return 0.0

        val elder = allDisciples[outerElderId] ?: return 0.0
        // realm越小境界越高，disciple.realm < elder.realm 表示弟子境界高于长老，不给加成
        if (disciple.realm < elder.realm) return 0.0

        val comprehension = elder.skills.comprehension
        return if (comprehension >= 80) {
            (comprehension - 80) * 0.01
        } else {
            0.0
        }
    }

    /**
     * Process building production (forge, alchemy, herb garden)
     * Auto-harvest completed slots and reset to IDLE
     */
    internal suspend fun processBuildingProduction(year: Int, month: Int) {
        val forgeSlots = productionSlotRepository.getSlotsByBuildingId("forge")
        forgeSlots.forEach { slot ->
            if (slot.isWorking && slot.isFinished(year, month)) {
                val recipeId = slot.recipeId
                if (recipeId != null) {
                    val recipe = ForgeRecipeDatabase.getRecipeById(recipeId)
                    if (recipe != null) {
                        val equipment = EquipmentStack(
                            name = recipe.name,
                            rarity = recipe.rarity,
                            description = recipe.description,
                            slot = recipe.type,
                            minRealm = GameConfig.Realm.getMinRealmForRarity(recipe.rarity)
                        )
                        inventorySystem.addEquipmentStack(equipment)
                        eventService.addGameEvent("锻造完成！获得${recipe.name}，已放入宗门仓库", EventType.SUCCESS)
                    } else {
                        eventService.addGameEvent("锻造完成，但配方[$recipeId]不存在", EventType.ERROR)
                    }
                } else {
                    eventService.addGameEvent("${getBuildingName(slot.buildingId)}工作已完成，但无配方信息", EventType.WARNING)
                }

                slot.assignedDiscipleId?.let { discipleId ->
                    currentDisciples = currentDisciples.map {
                        if (it.id == discipleId) it.copy(status = DiscipleStatus.IDLE) else it
                    }
                }

                productionSlotRepository.updateSlotByBuildingId("forge", slot.slotIndex) { s ->
                    ProductionSlot.createIdle(
                        id = s.id,
                        slotIndex = slot.slotIndex,
                        buildingType = com.xianxia.sect.core.model.production.BuildingType.FORGE,
                        buildingId = "forge"
                    )
                }
            }
        }

        val alchemySlots = productionSlotRepository.getSlotsByType(com.xianxia.sect.core.model.production.BuildingType.ALCHEMY)
        alchemySlots.forEach { slot ->
            if (slot.isWorking && slot.isFinished(year, month)) {
                val success = Random.nextDouble() <= slot.successRate
                if (success) {
                    val grade = PillGrade.random()
                    val template = slot.recipeId?.let { rid ->
                        val baseId = rid.substringBeforeLast("_")
                        ItemDatabase.getPillById("${baseId}_${grade.name.lowercase()}")
                    }
                    val pill = if (template != null) {
                        ItemDatabase.createPillFromTemplate(template)
                    } else {
                        Pill(
                            name = slot.outputItemName,
                            rarity = slot.outputItemRarity,
                            grade = grade,
                            description = "通过炼丹炉炼制而成",
                            minRealm = GameConfig.Realm.getMinRealmForRarity(slot.outputItemRarity),
                            quantity = 1
                        )
                    }
                    inventorySystem.addPill(pill)
                    eventService.addGameEvent("炼制成功！获得${grade.displayName}${slot.outputItemName}，已放入宗门仓库", EventType.SUCCESS)
                } else {
                    eventService.addGameEvent("炼制失败，材料损毁", EventType.ERROR)
                }

                // Reset slot to IDLE
                productionSlotRepository.updateSlotByBuildingId("alchemy", slot.slotIndex) { s ->
                    ProductionSlot.createIdle(
                        id = s.id,
                        slotIndex = slot.slotIndex,
                        buildingType = com.xianxia.sect.core.model.production.BuildingType.ALCHEMY,
                        buildingId = "alchemy"
                    )
                }
            }
        }
    }

    /**
     * Process herb garden growth
     */
    internal suspend fun processHerbGardenGrowth(year: Int, month: Int) {
        val data = currentGameData
        val events = mutableListOf<Pair<String, String>>()

        val herbGardenSlots = productionSlotRepository.getSlotsByType(com.xianxia.sect.core.model.production.BuildingType.HERB_GARDEN)
        herbGardenSlots.forEach { slot ->
            if (slot.isWorking && slot.isFinished(year, month)) {
                val herb = HerbDatabase.getHerbFromSeedName(slot.recipeName)
                    ?: slot.recipeId?.let { HerbDatabase.getHerbFromSeed(it) }
                if (herb != null) {
                    val herbGrowthBonus = if (data.sectPolicies.herbCultivation) GameConfig.PolicyConfig.HERB_CULTIVATION_BASE_EFFECT else 0.0
                    val actualYield = HerbGardenSystem.calculateIncreasedYield(slot.expectedYield, herbGrowthBonus)
                    val herbItem = Herb(
                        id = java.util.UUID.randomUUID().toString(),
                        name = herb.name,
                        rarity = herb.rarity,
                        description = herb.description,
                        category = herb.category,
                        quantity = actualYield
                    )
                    inventorySystem.addHerb(herbItem)
                    events.add("${herb.name}已成熟，收获${actualYield}个" to "SUCCESS")
                }

                productionSlotRepository.updateSlotByBuildingId("herbGarden", slot.slotIndex) { s ->
                    ProductionSlot.createIdle(
                        id = s.id,
                        slotIndex = slot.slotIndex,
                        buildingType = com.xianxia.sect.core.model.production.BuildingType.HERB_GARDEN,
                        buildingId = "herbGarden"
                    )
                }
            }
        }

        events.forEach { (msg, type) -> eventService.addGameEvent(msg, EventType.valueOf(type)) }
    }

    internal suspend fun processAutoPlant() {
        val data = currentGameData
        if (!data.sectPolicies.autoPlant) return

        val herbGardenSlots = productionSlotRepository.getSlotsByType(com.xianxia.sect.core.model.production.BuildingType.HERB_GARDEN)
        val idleSlots = herbGardenSlots.filter { it.status == com.xianxia.sect.core.model.production.ProductionSlotStatus.IDLE }
        if (idleSlots.isEmpty()) return

        for (slot in idleSlots) {
            val seeds = currentSeeds.filter { it.quantity > 0 }.sortedByDescending { it.rarity }
            val seedToPlant = seeds.firstOrNull() ?: break

            val herbDbSeedId = HerbDatabase.getSeedByName(seedToPlant.name)?.id
            val herbId = herbDbSeedId?.let { HerbDatabase.getHerbIdFromSeedId(it) }
            val newSlot = com.xianxia.sect.core.model.production.ProductionSlot(
                id = slot.id,
                slotIndex = slot.slotIndex,
                buildingType = com.xianxia.sect.core.model.production.BuildingType.HERB_GARDEN,
                buildingId = "herbGarden",
                status = com.xianxia.sect.core.model.production.ProductionSlotStatus.WORKING,
                recipeId = herbDbSeedId ?: seedToPlant.id,
                recipeName = seedToPlant.name,
                startYear = data.gameYear,
                startMonth = data.gameMonth,
                duration = seedToPlant.growTime,
                outputItemId = herbId ?: "",
                outputItemName = seedToPlant.name,
                expectedYield = seedToPlant.yield
            )

            productionSlotRepository.updateSlotByBuildingId("herbGarden", slot.slotIndex) { newSlot }
            inventorySystem.removeSeedSync(seedToPlant.id, 1)
            eventService.addGameEvent("自动种植：${seedToPlant.name}，已种入种植槽${slot.slotIndex + 1}", EventType.SUCCESS)
        }
    }

    internal suspend fun processAutoAlchemy() {
        val data = currentGameData
        if (!data.sectPolicies.autoAlchemy) return

        val alchemySlots = productionSlotRepository.getSlotsByType(com.xianxia.sect.core.model.production.BuildingType.ALCHEMY)
        val idleSlotIndices = alchemySlots
            .filter { it.status == com.xianxia.sect.core.model.production.ProductionSlotStatus.IDLE }
            .map { it.slotIndex }
        if (idleSlotIndices.isEmpty()) return

        val allRecipes = PillRecipeDatabase.getAllRecipes().sortedByDescending { it.rarity }
        val alchemyPolicyBonus = if (data.sectPolicies.alchemyIncentive) GameConfig.PolicyConfig.ALCHEMY_INCENTIVE_BASE_EFFECT else 0.0

        for (slotIndex in idleSlotIndices) {
            val herbs = currentHerbs
            val recipeToStart = allRecipes.firstOrNull { recipe ->
                recipe.materials.all { (materialId, requiredQuantity) ->
                    val herbData = HerbDatabase.getHerbById(materialId)
                    val herbName = herbData?.name
                    val herbRarity = herbData?.rarity ?: 1
                    val herb = herbs.find { it.name == herbName && it.rarity == herbRarity }
                    herb != null && herb.quantity >= requiredQuantity
                }
            } ?: break

            val result = productionCoordinator.startAlchemyAtomic(
                slotIndex = slotIndex,
                recipeId = recipeToStart.id,
                currentYear = data.gameYear,
                currentMonth = data.gameMonth,
                herbs = herbs,
                buildingId = "alchemy",
                alchemyPolicyBonus = alchemyPolicyBonus
            )

            if (result.success) {
                if (result.materialUpdate != null) {
                    updateHerbsSync(result.materialUpdate.herbs)
                }
                eventService.addGameEvent("自动炼丹：${recipeToStart.name}，已启动炼丹槽${slotIndex + 1}", EventType.SUCCESS)
            } else {
                break
            }
        }
    }

    internal suspend fun processAutoForge() {
        val data = currentGameData
        if (!data.sectPolicies.autoForge) return

        val forgeSlots = productionSlotRepository.getSlotsByBuildingId("forge")
        val maxSlotCount = 3
        val idleSlotIndices = (0 until maxSlotCount).filter { idx ->
            val slot = forgeSlots.find { it.slotIndex == idx }
            slot == null || slot.status == com.xianxia.sect.core.model.production.ProductionSlotStatus.IDLE
        }
        if (idleSlotIndices.isEmpty()) return

        val allRecipes = ForgeRecipeDatabase.getAllRecipes().sortedByDescending { it.rarity }
        val forgePolicyBonus = if (data.sectPolicies.forgeIncentive) GameConfig.PolicyConfig.FORGE_INCENTIVE_BASE_EFFECT else 0.0

        for (slotIndex in idleSlotIndices) {
            val materials = currentMaterials
            val materialIndex = materials.groupBy { it.name to it.rarity }
                .mapValues { (_, list) -> list.sumOf { it.quantity } }

            val recipeToStart = allRecipes.firstOrNull { recipe ->
                recipe.materials.all { (materialId, requiredQuantity) ->
                    val materialData = BeastMaterialDatabase.getMaterialById(materialId)
                    materialData != null && run {
                        val available = materialIndex[materialData.name to materialData.rarity] ?: 0
                        available >= requiredQuantity
                    }
                }
            } ?: break

            val result = productionCoordinator.startForgingAtomic(
                slotIndex = slotIndex,
                recipeId = recipeToStart.id,
                currentYear = data.gameYear,
                currentMonth = data.gameMonth,
                materials = materials,
                buildingId = "forge",
                forgePolicyBonus = forgePolicyBonus
            )

            if (result.success) {
                if (result.materialUpdate != null) {
                    updateMaterialsSync(result.materialUpdate.materials)
                }
                eventService.addGameEvent("自动炼器：${recipeToStart.name}，已启动锻造槽${slotIndex + 1}", EventType.SUCCESS)
            } else {
                break
            }
        }
    }

    /**
     * Process spirit mine production
     */
    internal fun processSpiritMineProduction() {
        val data = currentGameData
        val minerCount = data.spiritMineSlots.count { it.discipleId.isNotEmpty() }
        val baseSpiritStones = minerCount * 60

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

        val totalSpiritStones = (baseSpiritStones * (1 + deaconBonus) * boostMultiplier).toInt()

        if (totalSpiritStones > 0) {
            currentGameData = data.copy(
                spiritStones = data.spiritStones + totalSpiritStones
            )
            val bonusParts = buildList {
                if (deaconBonus > 0) add("执事加成+${(deaconBonus * 100).toInt()}%")
                if (boostMultiplier > 1.0) add("政策加成+${((boostMultiplier - 1.0) * 100).toInt()}%")
            }
            val bonusText = if (bonusParts.isNotEmpty()) "（${bonusParts.joinToString("，")}）" else ""
            eventService.addGameEvent("灵矿产出：${minerCount}名矿工本月共产出${totalSpiritStones}灵石$bonusText", EventType.INFO)
        }
    }

    /**
     * Process salary payment
     */
    internal fun processSalaryPayment(year: Int, month: Int) {
        val data = currentGameData
        val salaryConfig = data.monthlySalary
        val enabledConfig = data.monthlySalaryEnabled
        var totalSalary = 0L

        currentDisciples.filter { it.isAlive }.forEach { disciple ->
            val realm = disciple.realm
            if (enabledConfig[realm] == true) {
                val salary = (salaryConfig[realm] ?: 0).toLong()
                totalSalary += salary
            }
        }

        if (totalSalary > 0) {
            if (data.spiritStones >= totalSalary) {
                val discipleUpdates = currentDisciples.map { disciple ->
                    if (!disciple.isAlive || enabledConfig[disciple.realm] != true) return@map disciple

                    val salary = salaryConfig[disciple.realm] ?: 0
                    val newPaidCount = disciple.skills.salaryPaidCount + 1
                    val newLoyalty = if (newPaidCount % 3 == 0) {
                        (disciple.skills.loyalty + 1).coerceIn(0, 100)
                    } else {
                        disciple.skills.loyalty
                    }

                    disciple.copyWith(
                        storageBagSpiritStones = disciple.equipment.storageBagSpiritStones + salary.toLong(),
                        salaryPaidCount = newPaidCount,
                        loyalty = newLoyalty
                    )
                }

                currentDisciples = discipleUpdates
                currentGameData = data.copy(spiritStones = data.spiritStones - totalSalary)
                val paidCount = currentDisciples.count { it.isAlive && enabledConfig[it.realm] == true }
                eventService.addGameEvent("本月发放${paidCount}名弟子俸禄共${totalSalary}灵石", EventType.INFO)
            } else {
                val discipleUpdates = currentDisciples.map { disciple ->
                    if (!disciple.isAlive || enabledConfig[disciple.realm] != true) return@map disciple

                    val newMissedCount = disciple.skills.salaryMissedCount + 1
                    val newLoyalty = if (newMissedCount % 3 == 0) {
                        (disciple.skills.loyalty - 1).coerceIn(0, 100)
                    } else {
                        disciple.skills.loyalty
                    }

                    disciple.copyWith(
                        salaryMissedCount = newMissedCount,
                        loyalty = newLoyalty
                    )
                }

                currentDisciples = discipleUpdates
                eventService.addGameEvent("灵石不足（需${totalSalary}，现有${data.spiritStones}），本月未发放弟子俸禄", EventType.WARNING)
            }
        }
    }

    internal fun processPolicyCosts() {
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
            val totalDeducted = deductedPolicies.sumOf { it.second }
            currentGameData = currentGameData.copy(spiritStones = currentStones)
            eventService.addGameEvent("本月宗门政策维护费共${totalDeducted}灵石（${deductedPolicies.joinToString(", ") { "${it.first}${it.second}"}}）", EventType.INFO)
        }

        if (disabledPolicies.isNotEmpty()) {
            currentGameData = currentGameData.copy(sectPolicies = updatedPolicies)
            eventService.addGameEvent("灵石不足！以下政策已自动关闭，本月不生效：${disabledPolicies.joinToString("、")}", EventType.WARNING)
        }
    }

    private fun calculateCaptureRate(): Double {
        val data = currentGameData
        val elderSlots = data.elderSlots
        val allDisciples = currentDisciples.associateBy { it.id }

        var captureRate = GameConfig.LawEnforcementConfig.BASE_CAPTURE_RATE

        elderSlots.lawEnforcementElder?.let { elderId ->
            if (elderId.isNotEmpty()) {
                allDisciples[elderId]?.let { elder ->
                    val intelligenceAboveBase = (DiscipleStatCalculator.getBaseStats(elder).intelligence - GameConfig.LawEnforcementConfig.INTELLIGENCE_BASE).coerceAtLeast(0)
                    captureRate += intelligenceAboveBase * GameConfig.LawEnforcementConfig.ELDER_BONUS_PER_POINT
                }
            }
        }

        elderSlots.lawEnforcementDisciples.forEach { slot ->
            if (slot.discipleId.isNotEmpty()) {
                allDisciples[slot.discipleId]?.let { disciple ->
                    val intelligenceAboveBase = (DiscipleStatCalculator.getBaseStats(disciple).intelligence - GameConfig.LawEnforcementConfig.INTELLIGENCE_BASE).coerceAtLeast(0)
                    captureRate += (intelligenceAboveBase / GameConfig.LawEnforcementConfig.DISCIPLE_INTELLIGENCE_STEP) * GameConfig.LawEnforcementConfig.DISCIPLE_BONUS_PER_STEP
                }
            }
        }

        if (data.sectPolicies.enhancedSecurity) {
            captureRate += GameConfig.PolicyConfig.ENHANCED_SECURITY_BASE_EFFECT
        }

        return captureRate.coerceIn(0.0, 1.0)
    }

    private suspend fun processLawEnforcementMonthly() {
        val data = currentGameData
        val captureRate = calculateCaptureRate()
        val currentMonthValue = data.gameYear * 12 + data.gameMonth

        val atRiskDisciples = currentDisciples.filter {
            it.isAlive &&
            it.status == DiscipleStatus.IDLE &&
            DiscipleStatCalculator.getBaseStats(it).loyalty < GameConfig.LawEnforcementConfig.LOYALTY_THRESHOLD &&
            (currentMonthValue - it.usage.recruitedMonth) >= GameConfig.LawEnforcementConfig.NEW_DISCIPLE_PROTECTION_MONTHS
        }

        for (disciple in atRiskDisciples) {
            val effectiveLoyalty = DiscipleStatCalculator.getBaseStats(disciple).loyalty
            val desertionProb = ((GameConfig.LawEnforcementConfig.LOYALTY_THRESHOLD - effectiveLoyalty) * GameConfig.LawEnforcementConfig.PROB_PER_POINT).coerceIn(0.0, GameConfig.LawEnforcementConfig.MAX_PROB)
            if (Random.nextDouble() < desertionProb) {
                if (Random.nextDouble() < captureRate) {
                    eventService.addGameEvent("执法堂成功截获试图逃跑的${disciple.name}，已关入思过崖", EventType.INFO)
                    val currentYear = data.gameYear
                    val endYear = currentYear + GameConfig.LawEnforcementConfig.REFLECTION_YEARS
                    currentDisciples = currentDisciples.map {
                        if (it.id == disciple.id) it.copy(
                            status = DiscipleStatus.REFLECTING,
                            statusData = it.statusData + mapOf(
                                "reflectionStartYear" to currentYear.toString(),
                                "reflectionEndYear" to endYear.toString()
                            )
                        ) else it
                    }
                } else {
                    eventService.addGameEvent("${disciple.name}趁乱逃离了宗门！", EventType.DANGER)
                    clearDiscipleFromAllSlots(disciple.id)
                    currentDisciples = currentDisciples.filter { it.id != disciple.id }
                }
            }
        }

        processTheftMonthly()
    }

    private suspend fun processTheftMonthly() {
        if (currentGameData.spiritStones <= 0) return

        val captureRate = calculateCaptureRate()
        val currentMonthValue = currentGameData.gameYear * 12 + currentGameData.gameMonth

        val atRiskDisciples = currentDisciples.filter {
            it.isAlive &&
            it.status == DiscipleStatus.IDLE &&
            DiscipleStatCalculator.getBaseStats(it).morality < GameConfig.LawEnforcementConfig.MORALITY_THRESHOLD &&
            (currentMonthValue - it.usage.recruitedMonth) >= GameConfig.LawEnforcementConfig.NEW_DISCIPLE_PROTECTION_MONTHS
        }

        val thiefIds = mutableSetOf<String>()

        for (disciple in atRiskDisciples) {
            val effectiveMorality = DiscipleStatCalculator.getBaseStats(disciple).morality
            val theftProb = ((GameConfig.LawEnforcementConfig.MORALITY_THRESHOLD - effectiveMorality) * GameConfig.LawEnforcementConfig.PROB_PER_POINT).coerceIn(0.0, GameConfig.LawEnforcementConfig.MAX_PROB)
            if (Random.nextDouble() < theftProb) {
                if (Random.nextDouble() < captureRate) {
                    eventService.addGameEvent("执法堂成功抓获偷盗中的${disciple.name}，已关入思过崖", EventType.INFO)
                    val currentYear = currentGameData.gameYear
                    val endYear = currentYear + GameConfig.LawEnforcementConfig.REFLECTION_YEARS
                    currentDisciples = currentDisciples.map {
                        if (it.id == disciple.id) it.copy(
                            status = DiscipleStatus.REFLECTING,
                            statusData = it.statusData + mapOf(
                                "reflectionStartYear" to currentYear.toString(),
                                "reflectionEndYear" to endYear.toString()
                            )
                        ) else it
                    }
                } else {
                    val currentData = currentGameData
                    if (currentData.spiritStones <= 0) break
                    val stolenAmount = (currentData.spiritStones * Random.nextDouble(GameConfig.LawEnforcementConfig.THEFT_MIN_RATIO, GameConfig.LawEnforcementConfig.THEFT_MAX_RATIO)).toLong().coerceAtLeast(1)
                    currentGameData = currentData.copy(spiritStones = (currentData.spiritStones - stolenAmount).coerceAtLeast(0))
                    currentDisciples = currentDisciples.map {
                        if (it.id == disciple.id) it.copy(
                            equipment = it.equipment.copy(storageBagSpiritStones = it.equipment.storageBagSpiritStones + stolenAmount)
                        ) else it
                    }
                    thiefIds.add(disciple.id)
                    eventService.addGameEvent("${disciple.name}偷盗了宗门${stolenAmount}灵石并叛离宗门！", EventType.DANGER)
                }
            }
        }

        for (thiefId in thiefIds) {
            clearDiscipleFromAllSlots(thiefId)
        }
        if (thiefIds.isNotEmpty()) {
            currentDisciples = currentDisciples.filter { it.id !in thiefIds }
        }
    }

    /**
     * 道侣匹配系统 - 每月处理一次
     *
     * 条件：存活、年龄>=18、无道侣(partnerId==null)的异性弟子
     * 所有符合条件的异性配对每月均有 0.6% 独立概率结为道侣
     */
    private fun processPartnerMatching() {
        val allDisciples = currentDisciples

        val eligibleMales = allDisciples.filter {
            it.isAlive && it.age >= 18 && it.social.partnerId == null && it.gender == "male"
        }
        val eligibleFemales = allDisciples.filter {
            it.isAlive && it.age >= 18 && it.social.partnerId == null && it.gender == "female"
        }

        if (eligibleMales.isEmpty() || eligibleFemales.isEmpty()) return

        var currentList = allDisciples
        val pairedFemaleIds = mutableSetOf<String>()

        for (male in eligibleMales) {
            for (female in eligibleFemales) {
                if (female.id in pairedFemaleIds) continue
                if (hasBloodRelation(male, female)) continue

                if (GameRandom.nextDouble() < 0.006) {
                    currentList = currentList.map { disciple ->
                        when (disciple.id) {
                            male.id -> disciple.copyWith(partnerId = female.id)
                            female.id -> disciple.copyWith(partnerId = male.id)
                            else -> disciple
                        }
                    }
                    pairedFemaleIds.add(female.id)
                    eventService.addGameEvent("${male.name} 与 ${female.name} 结为道侣", EventType.SUCCESS)
                }
            }
        }

        if (pairedFemaleIds.isNotEmpty()) {
            currentDisciples = currentList
        }
    }

    private fun hasBloodRelation(a: Disciple, b: Disciple): Boolean {
        val aParent1 = a.social.parentId1
        val aParent2 = a.social.parentId2
        val bParent1 = b.social.parentId1
        val bParent2 = b.social.parentId2
        return a.id == bParent1 || a.id == bParent2 ||
               b.id == aParent1 || b.id == aParent2 ||
               (aParent1 != null && aParent1 == bParent1) ||
               (aParent1 != null && aParent1 == bParent2) ||
               (aParent2 != null && aParent2 == bParent1) ||
               (aParent2 != null && aParent2 == bParent2)
    }

    /**
     * Update exploration teams movement
     */
    private fun updateExplorationTeamsMovement(day: Int, month: Int, year: Int) {
        val updatedTeams = currentTeams.map { team ->
            if (team.status == ExplorationStatus.TRAVELING) {
                updateTeamMovement(team, day, month, year)
            } else {
                team
            }
        }
        currentTeams = updatedTeams
    }

    /**
     * Update single team movement
     */
    private fun updateTeamMovement(team: ExplorationTeam, day: Int, month: Int, year: Int): ExplorationTeam {
        // Simple movement logic - in real implementation would be more complex
        val duration = team.duration.coerceAtLeast(1)
        val progressIncrement = 1.0f / duration.toFloat()
        val newProgress = team.moveProgress + progressIncrement

        return if (newProgress >= 1.0f) {
            team.copy(
                status = ExplorationStatus.EXPLORING,
                moveProgress = 1.0f,
                arrivalYear = year,
                arrivalMonth = month,
                arrivalDay = day
            )
        } else {
            team.copy(moveProgress = newProgress)
        }
    }

    /**
     * Update cave exploration teams movement
     */
    private fun updateCaveExplorationTeamsMovement(day: Int, month: Int, year: Int) {
        val data = currentGameData
        val updatedTeams = data.caveExplorationTeams.map { team ->
            if (team.status == CaveExplorationStatus.TRAVELING) {
                val duration = team.duration.coerceAtLeast(1)
                val progressIncrement = 1.0f / duration.toFloat()
                val newProgress = team.moveProgress + progressIncrement

                if (newProgress >= 1.0f) {
                    eventService.addGameEvent("探索队伍抵达${team.caveName}，开始探索", EventType.INFO)
                    team.copy(
                        status = CaveExplorationStatus.EXPLORING,
                        moveProgress = 1.0f
                    )
                } else {
                    team.copy(moveProgress = newProgress)
                }
            } else {
                team
            }
        }
        currentGameData = data.copy(caveExplorationTeams = updatedTeams)
    }

    /**
     * Process AI battle team movement
     */
    private suspend fun processAIBattleTeamMovement() {
        val data = currentGameData
        val updatedTeams = data.aiBattleTeams.map { team ->
            if (team.status == "moving") {
                AISectAttackManager.updateAIBattleTeamMovement(team, data)
            } else {
                team
            }
        }

        currentGameData = data.copy(aiBattleTeams = updatedTeams)

        updatedTeams.filter { AISectAttackManager.isTeamArrived(it) }.forEach { team ->
            triggerAISectBattle(team)
        }
    }

    private suspend fun processPlayerBattleTeamMovement() {
        val data = currentGameData
        val team = data.battleTeam
        if (team == null || team.status != "moving") return

        val playerSect = data.worldMapSects.find { it.isPlayerSect } ?: return
        val targetSect = data.worldMapSects.find { it.id == team.targetSectId } ?: return

        val distance = kotlin.math.sqrt(
            (targetSect.x - playerSect.x) * (targetSect.x - playerSect.x) +
            (targetSect.y - playerSect.y) * (targetSect.y - playerSect.y)
        )
        val duration = (distance / 100f).coerceAtLeast(1f).toInt()
        val progressIncrement = 1f / duration.coerceAtLeast(1) * 1.5f
        val newProgress = (team.moveProgress + progressIncrement).coerceAtMost(1f)

        val newX = playerSect.x + (targetSect.x - playerSect.x) * newProgress
        val newY = playerSect.y + (targetSect.y - playerSect.y) * newProgress

        val updatedTeam = if (newProgress >= 1f) {
            team.copy(
                status = "battle",
                moveProgress = 1f,
                currentX = targetSect.x,
                currentY = targetSect.y,
                isAtSect = false
            )
        } else {
            team.copy(
                moveProgress = newProgress,
                currentX = newX,
                currentY = newY,
                isAtSect = false
            )
        }

        currentGameData = data.copy(battleTeam = updatedTeam)

        if (newProgress >= 1f) {
            executePlayerBattleTeamBattle(updatedTeam, targetSect)
        }
    }

    private suspend fun executePlayerBattleTeamBattle(team: BattleTeam, targetSect: WorldSect) {
        val data = currentGameData
        val playerDisciples = currentDisciples.filter { it.isAlive }
        val teamDisciples = team.slots.filter { it.discipleId.isNotEmpty() && it.isAlive }.mapNotNull { slot ->
            playerDisciples.find { it.id == slot.discipleId }
        }

        if (teamDisciples.isEmpty()) {
            currentGameData = currentGameData.copy(
                battleTeam = team.copy(status = "idle", isAtSect = true, targetSectId = "", moveProgress = 0f)
            )
            return
        }

        val garrisonTeam = data.aiBattleTeams.find { it.isGarrison && it.garrisonSectId == targetSect.id }
        val defenderDisciples = if (garrisonTeam != null) {
            val garrisonDisciples = garrisonTeam.disciples.filter { it.isAlive }
            val sectDisciples = data.aiSectDisciples[targetSect.id] ?: emptyList()
            if (garrisonDisciples.size < AISectAttackManager.TEAM_SIZE) {
                val remaining = sectDisciples
                    .filter { it.isAlive && it.id !in garrisonDisciples.map { d -> d.id } }
                    .sortedBy { it.realm }
                    .take(AISectAttackManager.TEAM_SIZE - garrisonDisciples.size)
                garrisonDisciples + remaining
            } else {
                garrisonDisciples
            }
        } else {
            (data.aiSectDisciples[targetSect.id] ?: emptyList()).filter { it.isAlive }
        }

        val aiAttackTeam = AIBattleTeam(
            id = team.id,
            attackerSectId = data.worldMapSects.find { it.isPlayerSect }?.id ?: "",
            attackerSectName = data.sectName,
            defenderSectId = targetSect.id,
            defenderSectName = targetSect.name,
            disciples = teamDisciples,
            status = "battling",
            moveProgress = 1f
        )

        val result = AISectAttackManager.executeAISectBattle(aiAttackTeam, targetSect, defenderDisciples)

        val deadPlayerDiscipleIds = result.deadAttackerIds
        currentDisciples = currentDisciples.map { disciple ->
            if (disciple.id in deadPlayerDiscipleIds) {
                handleDiscipleDeath(disciple, isOutsideSect = true)
                disciple.copy(isAlive = false)
            } else {
                disciple
            }
        }

        val updatedDefenderDisciples = (data.aiSectDisciples[targetSect.id] ?: emptyList()).filter { it.id !in result.deadDefenderIds }
        currentGameData = currentGameData.copy(
            aiSectDisciples = currentGameData.aiSectDisciples.toMutableMap().apply {
                this[targetSect.id] = updatedDefenderDisciples
            }
        )

        if (garrisonTeam != null) {
            val updatedGarrisonDisciples = garrisonTeam.disciples.filter { it.id !in result.deadDefenderIds }
            currentGameData = currentGameData.copy(
                aiBattleTeams = currentGameData.aiBattleTeams.map {
                    if (it.id == garrisonTeam.id) {
                        it.copy(disciples = updatedGarrisonDisciples)
                    } else it
                }
            )
        }

        val aliveTeamSlots = team.slots.map { slot ->
            if (slot.discipleId in deadPlayerDiscipleIds) {
                slot.copy(isAlive = false)
            } else {
                slot
            }
        }

        val highRealmDefendersAllDead = updatedDefenderDisciples
            .filter { it.realm <= 5 }
            .isEmpty()

        if (result.winner == AIBattleWinner.ATTACKER && highRealmDefendersAllDead) {
            currentGameData = currentGameData.copy(
                worldMapSects = currentGameData.worldMapSects.map { sect ->
                    if (sect.id == targetSect.id) {
                        sect.copy(
                            isPlayerOccupied = true,
                            occupierSectId = data.worldMapSects.find { it.isPlayerSect }?.id ?: ""
                        )
                    } else {
                        sect
                    }
                },
                battleTeam = team.copy(
                    status = "stationed",
                    slots = aliveTeamSlots,
                    isAtSect = false,
                    targetSectId = targetSect.id,
                    isOccupying = true,
                    occupiedSectId = targetSect.id
                ),
                aiBattleTeams = currentGameData.aiBattleTeams.map {
                    if (it.id == garrisonTeam?.id) {
                        it.copy(status = "completed")
                    } else {
                        it
                    }
                }
            )
            eventService.addGameEvent("我宗攻占了${targetSect.name}！", EventType.SUCCESS)
        } else {
            currentGameData = currentGameData.copy(
                battleTeam = team.copy(
                    status = "idle",
                    isAtSect = true,
                    targetSectId = "",
                    moveProgress = 0f,
                    slots = aliveTeamSlots
                )
            )
            if (result.winner == AIBattleWinner.DEFENDER) {
                eventService.addGameEvent("我宗进攻${targetSect.name}失败！", EventType.DANGER)
            } else {
                eventService.addGameEvent("我宗与${targetSect.name}战成平局！", EventType.INFO)
            }
        }
    }

    /**
     * Trigger AI sect battle
     */
    private suspend fun triggerAISectBattle(team: AIBattleTeam) {
        val data = currentGameData
        val defenderSect = data.worldMapSects.find { it.id == team.defenderSectId } ?: return
        val attackerSect = data.worldMapSects.find { it.id == team.attackerSectId }

        if (team.isPlayerDefender) {
            triggerPlayerSectBattle(team, defenderSect, attackerSect)
            return
        }

        val attackerDisciples = data.aiSectDisciples[team.attackerSectId] ?: emptyList()
        val defenderDisciples = data.aiSectDisciples[team.defenderSectId] ?: emptyList()

        val garrisonTeam = data.aiBattleTeams.find { it.isGarrison && it.garrisonSectId == team.defenderSectId }
        val result = if (garrisonTeam != null) {
            val garrisonDisciples = garrisonTeam.disciples.filter { it.isAlive }
            val supplementedDisciples = if (garrisonDisciples.size < AISectAttackManager.TEAM_SIZE) {
                val remaining = defenderDisciples
                    .filter { it.isAlive && it.id !in garrisonDisciples.map { d -> d.id } }
                    .sortedBy { it.realm }
                    .take(AISectAttackManager.TEAM_SIZE - garrisonDisciples.size)
                garrisonDisciples + remaining
            } else {
                garrisonDisciples
            }
            AISectAttackManager.executeAISectBattle(team, defenderSect, supplementedDisciples)
        } else {
            AISectAttackManager.executeAISectBattle(team, defenderSect, defenderDisciples)
        }

        if (attackerSect != null) {
            val updatedAttackerDisciples = attackerDisciples.filter { it.id !in result.deadAttackerIds }
            currentGameData = currentGameData.copy(
                aiSectDisciples = currentGameData.aiSectDisciples.toMutableMap().apply {
                    this[team.attackerSectId] = updatedAttackerDisciples
                }
            )
        }

        val updatedDefenderDisciples = defenderDisciples.filter { it.id !in result.deadDefenderIds }
        currentGameData = currentGameData.copy(
            aiSectDisciples = currentGameData.aiSectDisciples.toMutableMap().apply {
                this[team.defenderSectId] = updatedDefenderDisciples
            }
        )

        if (garrisonTeam != null) {
            val updatedGarrisonDisciples = garrisonTeam.disciples.filter { it.id !in result.deadDefenderIds }
            currentGameData = currentGameData.copy(
                aiBattleTeams = currentGameData.aiBattleTeams.map {
                    if (it.id == garrisonTeam.id) {
                        it.copy(disciples = updatedGarrisonDisciples)
                    } else it
                }
            )
        }

        val highRealmDefendersAllDead = updatedDefenderDisciples
            .filter { it.realm <= 5 }
            .isEmpty()

        if (result.winner == AIBattleWinner.ATTACKER && highRealmDefendersAllDead) {
            val currentData = currentGameData
            val defenderDetail = currentData.sectDetails[team.defenderSectId]
            val defenderWarehouse = defenderDetail?.warehouse ?: SectWarehouse()

            val aliveAttackerDisciples = team.disciples.filter { it.id !in result.deadAttackerIds }
            val garrisonTeamObj = AISectAttackManager.createGarrisonTeam(
                aliveAttackerDisciples,
                updatedDefenderDisciples,
                team.attackerSectId,
                team.attackerSectName,
                team.defenderSectId
            )

            currentGameData = currentData.copy(
                worldMapSects = currentData.worldMapSects.map { sect ->
                    if (sect.id == team.defenderSectId) {
                        sect.copy(
                            occupierSectId = team.attackerSectId,
                            garrisonTeamId = garrisonTeamObj.id
                        )
                    } else {
                        sect
                    }
                },
                sectDetails = currentData.sectDetails.toMutableMap().apply {
                    this[team.defenderSectId] = (this[team.defenderSectId] ?: SectDetail(sectId = team.defenderSectId)).copy(warehouse = SectWarehouse())
                    val attackerDetail = this[team.attackerSectId] ?: SectDetail(sectId = team.attackerSectId)
                    val mergedWarehouse = SectWarehouseManager.addItemsToWarehouse(attackerDetail.warehouse, defenderWarehouse.items)
                    val finalWarehouse = SectWarehouseManager.addSpiritStonesToWarehouse(mergedWarehouse, defenderWarehouse.spiritStones)
                    this[team.attackerSectId] = attackerDetail.copy(warehouse = finalWarehouse)
                },
                aiBattleTeams = currentGameData.aiBattleTeams.map {
                    if (it.id == team.id) {
                        it.copy(status = "completed")
                    } else if (it.id == garrisonTeam?.id) {
                        it.copy(status = "completed")
                    } else {
                        it
                    }
                } + garrisonTeamObj
            )
            eventService.addGameEvent(AISectAttackManager.generateSectDestroyedEvent(team.attackerSectName, team.defenderSectName), EventType.DANGER)

            val updatedRelations = currentGameData.sectRelations.map { relation ->
                val isRelevantRelation = (relation.sectId1 == team.attackerSectId && relation.sectId2 == team.defenderSectId) ||
                                         (relation.sectId1 == team.defenderSectId && relation.sectId2 == team.attackerSectId)
                if (isRelevantRelation) {
                    relation.copy(favor = (relation.favor - 10).coerceIn(GameConfig.Diplomacy.MIN_FAVOR, GameConfig.Diplomacy.MAX_FAVOR))
                } else {
                    relation
                }
            }
            currentGameData = currentGameData.copy(sectRelations = updatedRelations)
        } else {
            val updatedRelations = currentGameData.sectRelations.map { relation ->
                val isRelevantRelation = (relation.sectId1 == team.attackerSectId && relation.sectId2 == team.defenderSectId) ||
                                         (relation.sectId1 == team.defenderSectId && relation.sectId2 == team.attackerSectId)
                if (isRelevantRelation) {
                    relation.copy(favor = (relation.favor - 10).coerceIn(GameConfig.Diplomacy.MIN_FAVOR, GameConfig.Diplomacy.MAX_FAVOR))
                } else {
                    relation
                }
            }
            currentGameData = currentGameData.copy(sectRelations = updatedRelations)

            val aliveDisciples = team.disciples.filter { it.id !in result.deadAttackerIds }
            currentGameData = currentGameData.copy(
                aiBattleTeams = currentGameData.aiBattleTeams.map {
                    if (it.id == team.id) {
                        it.copy(
                            status = "returning",
                            disciples = aliveDisciples,
                            currentX = defenderSect.x,
                            currentY = defenderSect.y,
                            targetX = team.attackerStartX,
                            targetY = team.attackerStartY,
                            moveProgress = 0f
                        )
                    } else it
                }
            )
        }
    }

    /**
     * Trigger player sect battle (defending)
     */
    private suspend fun triggerPlayerSectBattle(team: AIBattleTeam, playerSect: WorldSect, attackerSect: WorldSect?) {
        val data = currentGameData
        val playerDefenseTeam = AISectAttackManager.createPlayerDefenseTeam(
            disciples = currentDisciples,
            equipmentMap = currentEquipmentInstances.associateBy { it.id },
            manualMap = currentManualInstances.associateBy { it.id },
            manualProficiencies = data.manualProficiencies.mapValues { (_, list) ->
                list.associateBy { it.manualId }
            }
        )

        if (playerDefenseTeam.isEmpty()) {
            eventService.addGameEvent("${team.attackerSectName}进攻我宗，但我宗无可用弟子防守！", EventType.DANGER)
            processPlayerDefeat(team, attackerSect)
            return
        }

        val result = AISectAttackManager.executePlayerSectBattle(team, playerDefenseTeam)

        val deadPlayerDiscipleIds = result.deadDefenderIds
        currentDisciples = currentDisciples.map { disciple ->
            if (disciple.id in deadPlayerDiscipleIds) {
                handleDiscipleDeath(disciple, isOutsideSect = false)
                disciple.copy(isAlive = false)
            } else {
                disciple
            }
        }

        if (attackerSect != null) {
            val attackerDisciples = data.aiSectDisciples[team.attackerSectId] ?: emptyList()
            val updatedAttackerDisciples = attackerDisciples.filter { it.id !in result.deadAttackerIds }
            currentGameData = currentGameData.copy(
                aiSectDisciples = currentGameData.aiSectDisciples.toMutableMap().apply {
                    this[team.attackerSectId] = updatedAttackerDisciples
                }
            )
        }

        val updatedPlayerRelations = currentGameData.sectRelations.map { relation ->
            val isRelevantRelation = (relation.sectId1 == team.attackerSectId && relation.sectId2 == playerSect.id) ||
                                     (relation.sectId1 == playerSect.id && relation.sectId2 == team.attackerSectId)
            if (isRelevantRelation) {
                relation.copy(favor = (relation.favor - 15).coerceIn(GameConfig.Diplomacy.MIN_FAVOR, GameConfig.Diplomacy.MAX_FAVOR))
            } else {
                relation
            }
        }

        currentGameData = currentGameData.copy(sectRelations = updatedPlayerRelations)

        if (result.winner == AIBattleWinner.ATTACKER) {
            processPlayerDefeat(team, attackerSect)

            val playerHighRealmAllDead = currentDisciples
                .filter { it.isAlive && it.realm <= 5 }
                .isEmpty()

            if (playerHighRealmAllDead && attackerSect != null) {
                val aliveAttackerDisciples = team.disciples.filter { it.id !in result.deadAttackerIds }
                val garrisonTeamObj = AISectAttackManager.createGarrisonTeam(
                    aliveAttackerDisciples,
                    emptyList(),
                    team.attackerSectId,
                    team.attackerSectName,
                    playerSect.id
                )

                currentGameData = currentGameData.copy(
                    worldMapSects = currentGameData.worldMapSects.map { sect ->
                        if (sect.id == playerSect.id) {
                            sect.copy(
                                occupierSectId = team.attackerSectId,
                                garrisonTeamId = garrisonTeamObj.id
                            )
                        } else {
                            sect
                        }
                    },
                    aiBattleTeams = currentGameData.aiBattleTeams.map {
                        if (it.id == team.id) {
                            it.copy(status = "completed")
                        } else {
                            it
                        }
                    } + garrisonTeamObj
                )
                eventService.addGameEvent("${team.attackerSectName}攻占了我宗！", EventType.DANGER)
            }
        } else {
            eventService.addGameEvent("我宗成功击退${team.attackerSectName}的进攻！", EventType.SUCCESS)

            val aliveDefenderIds = currentDisciples
                .filter { it.isAlive && it.id !in deadPlayerDiscipleIds }
                .map { it.id }.toSet()
            currentDisciples = currentDisciples.map { disciple ->
                if (disciple.id in aliveDefenderIds) {
                    disciple.copyWith(soulPower = disciple.equipment.soulPower + 1)
                } else {
                    disciple
                }
            }

            val aliveDisciples = team.disciples.filter { it.id !in result.deadAttackerIds }
            currentGameData = currentGameData.copy(
                aiBattleTeams = currentGameData.aiBattleTeams.map {
                    if (it.id == team.id) {
                        it.copy(
                            status = "returning",
                            disciples = aliveDisciples,
                            currentX = playerSect.x,
                            currentY = playerSect.y,
                            targetX = team.attackerStartX,
                            targetY = team.attackerStartY,
                            moveProgress = 0f
                        )
                    } else {
                        it
                    }
                }
            )
        }
    }

    /**
     * Process player defeat in AI battle
     */
    private suspend fun processPlayerDefeat(team: AIBattleTeam, attackerSect: WorldSect?) {
        val data = currentGameData
        val lootResult = AISectAttackManager.calculatePlayerLootLoss(
            data.spiritStones,
            currentMaterials,
            currentHerbs,
            currentSeeds,
            currentPills
        )

        currentGameData = data.copy(
            spiritStones = (data.spiritStones - lootResult.lostSpiritStones).coerceAtLeast(0L)
        )

        lootResult.lostMaterials.forEach { (itemName, itemCount) ->
            when {
                currentMaterials.any { it.name == itemName } -> {
                    currentMaterials = currentMaterials.map { material ->
                        if (material.name == itemName && material.quantity > 0) {
                            material.copy(quantity = (material.quantity - itemCount).coerceAtLeast(0))
                        } else material
                    }
                }
                currentHerbs.any { it.name == itemName } -> {
                    currentHerbs = currentHerbs.map { herb ->
                        if (herb.name == itemName && herb.quantity > 0) {
                            herb.copy(quantity = (herb.quantity - itemCount).coerceAtLeast(0))
                        } else herb
                    }
                }
                currentSeeds.any { it.name == itemName } -> {
                    currentSeeds = currentSeeds.map { seed ->
                        if (seed.name == itemName && seed.quantity > 0) {
                            seed.copy(quantity = (seed.quantity - itemCount).coerceAtLeast(0))
                        } else seed
                    }
                }
                currentPills.any { it.name == itemName } -> {
                    currentPills = currentPills.map { pill ->
                        if (pill.name == itemName && pill.quantity > 0) {
                            pill.copy(quantity = (pill.quantity - itemCount).coerceAtLeast(0))
                        } else pill
                    }
                }
            }
        }

        if (attackerSect != null) {
            val warehouseItems = SectWarehouseManager.convertLootLossToWarehouseItems(
                lootResult.lostMaterials,
                currentMaterials,
                currentHerbs,
                currentSeeds,
                currentPills
            )
            val attackerDetail = currentGameData.sectDetails[attackerSect.id] ?: SectDetail(sectId = attackerSect.id)
            val updatedWarehouse = SectWarehouseManager.addItemsToWarehouse(attackerDetail.warehouse, warehouseItems)
            val finalWarehouse = SectWarehouseManager.addSpiritStonesToWarehouse(updatedWarehouse, lootResult.lostSpiritStones)

            currentGameData = currentGameData.copy(
                sectDetails = currentGameData.sectDetails.toMutableMap().apply {
                    this[attackerSect.id] = attackerDetail.copy(warehouse = finalWarehouse)
                }
            )
        }

        eventService.addGameEvent("我宗防守失败，被${team.attackerSectName}掠夺了${lootResult.lostSpiritStones}灵石和${lootResult.lostMaterials.size}种道具！", EventType.DANGER)

        currentGameData = currentGameData.copy(
            aiBattleTeams = currentGameData.aiBattleTeams.map {
                if (it.id == team.id) it.copy(status = "completed") else it
            }
        )
    }

    /**
     * Check exploration arrivals and start exploration
     */
    private suspend fun checkExplorationArrivals() {
        val data = currentGameData
        val arrivedTeams = currentTeams.filter {
            it.status == ExplorationStatus.EXPLORING &&
            it.arrivalYear == data.gameYear &&
            it.arrivalMonth == data.gameMonth &&
            it.arrivalDay == data.gameDay
        }

        arrivedTeams.forEach { team ->
            val dungeonConfig = GameConfig.Dungeons.get(team.dungeon) ?: return@forEach
            eventService.addGameEvent("探索队伍【${team.name}】已抵达${dungeonConfig.name}，开始探索", EventType.INFO)
        }
    }

    private suspend fun processDungeonMonthlyExploration() {
        val data = currentGameData
        val exploringTeams = currentTeams.filter {
            it.status == ExplorationStatus.EXPLORING
        }

        for (team in exploringTeams) {
            val dungeonConfig = GameConfig.Dungeons.get(team.dungeon) ?: continue

            val teamMembers = team.memberIds.mapNotNull { id ->
                currentDisciples.find { it.id == id }
            }.filter { it.isAlive }

            if (teamMembers.isEmpty()) {
                eventService.addGameEvent("探索队伍【${team.name}】全员阵亡或失联", EventType.DANGER)
                completeExploration(team, false, emptyList())
                continue
            }

            val avgRealm = GameUtils.calculateBeastRealm(
                teamMembers,
                realmExtractor = { it.realm },
                layerExtractor = { it.realmLayer }
            )
            val roll = Random.nextInt(3)

            when (roll) {
                0 -> processDungeonRandomLoot(team, dungeonConfig, avgRealm)
                1 -> processDungeonEmptyResult(team, dungeonConfig)
                2 -> processDungeonBeastEncounter(team, dungeonConfig, teamMembers, avgRealm)
            }
        }
    }

    private fun getRarityForRealm(avgRealm: Int): Int = GameConfig.Realm.getMaxRarity(avgRealm)

    private fun processDungeonRandomLoot(team: ExplorationTeam, dungeonConfig: GameConfig.DungeonConfig, avgRealm: Int) {
        val rarity = getRarityForRealm(avgRealm)
        val totalSlots = Random.nextInt(3, 16)

        val rewardCounts = mutableMapOf<String, Int>()
        var spiritStoneAmount = 0L

        val slotTypes = listOf(
            "spiritStone" to 0.18,
            "equipment" to 0.06,
            "manual" to 0.06,
            "pill" to 0.06,
            "herb" to 0.22,
            "seed" to 0.20,
            "material" to 0.22
        )
        val totalWeight = slotTypes.sumOf { it.second }

        for (i in 0 until totalSlots) {
            var roll = Random.nextDouble() * totalWeight
            var selectedType = "material"
            for ((type, weight) in slotTypes) {
                roll -= weight
                if (roll <= 0) {
                    selectedType = type
                    break
                }
            }

            when (selectedType) {
                "spiritStone" -> {
                    val base = 200.0
                    val fluctuationPercent = Random.nextDouble(-30.0, 30.0)
                    val roundedPercent = (fluctuationPercent * 10).toInt() / 10.0
                    val amount = (base * (1 + roundedPercent / 100.0)).toLong()
                    spiritStoneAmount += amount
                }
                "equipment" -> {
                    val equipment = EquipmentDatabase.generateRandom(rarity, rarity)
                    inventorySystem.addEquipmentStack(equipment)
                    rewardCounts[equipment.name] = rewardCounts.getOrDefault(equipment.name, 0) + 1
                }
                "manual" -> {
                    val manual = ManualDatabase.generateRandom(rarity, rarity)
                    inventorySystem.addManualStack(manual)
                    rewardCounts[manual.name] = rewardCounts.getOrDefault(manual.name, 0) + 1
                }
                "pill" -> {
                    val pill = ItemDatabase.generateRandomPill(rarity, rarity)
                    inventorySystem.addPill(pill)
                    rewardCounts[pill.name] = rewardCounts.getOrDefault(pill.name, 0) + 1
                }
                "herb" -> {
                    val herbTemplate = HerbDatabase.generateRandomHerb(rarity, rarity)
                    val herb = Herb(name = herbTemplate.name, rarity = herbTemplate.rarity, description = herbTemplate.description, category = herbTemplate.category, quantity = 1)
                    inventorySystem.addHerb(herb)
                    rewardCounts[herb.name] = rewardCounts.getOrDefault(herb.name, 0) + 1
                }
                "seed" -> {
                    val seedTemplate = HerbDatabase.generateRandomSeed(rarity, rarity)
                    val seed = Seed(name = seedTemplate.name, rarity = seedTemplate.rarity, description = seedTemplate.description, growTime = seedTemplate.growTime, yield = seedTemplate.yield, quantity = 1)
                    inventorySystem.addSeed(seed)
                    rewardCounts[seed.name] = rewardCounts.getOrDefault(seed.name, 0) + 1
                }
                "material" -> {
                    val material = ItemDatabase.generateRandomMaterial(rarity, rarity)
                    inventorySystem.addMaterial(material)
                    rewardCounts[material.name] = rewardCounts.getOrDefault(material.name, 0) + 1
                }
            }
        }

        val rewardDescriptions = mutableListOf<String>()
        if (spiritStoneAmount > 0) {
            currentGameData = currentGameData.copy(
                spiritStones = currentGameData.spiritStones + spiritStoneAmount
            )
            rewardDescriptions.add("灵石x${spiritStoneAmount}")
        }
        rewardCounts.forEach { (name, count) -> rewardDescriptions.add("${name}x${count}") }

        eventService.addGameEvent("探索队伍【${team.name}】在${dungeonConfig.name}获得宝物：${rewardDescriptions.joinToString("、")}", EventType.SUCCESS)
    }

    private fun processDungeonEmptyResult(team: ExplorationTeam, dungeonConfig: GameConfig.DungeonConfig) {
        eventService.addGameEvent("探索队伍【${team.name}】在${dungeonConfig.name}一无所获", EventType.INFO)
    }

    private suspend fun processDungeonBeastEncounter(
        team: ExplorationTeam,
        dungeonConfig: GameConfig.DungeonConfig,
        teamMembers: List<Disciple>,
        avgRealm: Int
    ) {
        val data = currentGameData
        if (data.smartBattleEnabled) {
            val allFullStatus = teamMembers.all { disciple ->
                val stats = disciple.getBaseStats()
                val hp = if (disciple.combat.currentHp == -1) stats.maxHp else disciple.combat.currentHp
                val mp = if (disciple.combat.currentMp == -1) stats.maxMp else disciple.combat.currentMp
                hp >= stats.maxHp && mp >= stats.maxMp
            }
            if (!allFullStatus) {
                eventService.addGameEvent("探索队伍【${team.name}】在${dungeonConfig.name}遭遇妖兽，因状态不佳选择回避，一无所获", EventType.INFO)
                return
            }
        }
        val equipmentMap = currentEquipmentInstances.associateBy { it.id }
        val manualMap = currentManualInstances.associateBy { it.id }
        val allProficiencies = data.manualProficiencies.mapValues { (_, list) ->
            list.associateBy { it.manualId }
        }
        val battle = battleSystem.createBattle(
            disciples = teamMembers,
            equipmentMap = equipmentMap,
            manualMap = manualMap,
            beastLevel = avgRealm,
            beastType = dungeonConfig.beastType,
            manualProficiencies = allProficiencies
        )

        val battleResult = battleSystem.executeBattle(battle)

        val survivorHpMap = battleResult.log.teamMembers.filter { it.isAlive }.associate { it.id to it.hp }
        val survivorMpMap = battleResult.log.teamMembers.filter { it.isAlive }.associate { it.id to it.mp }

        if (battleResult.victory) {
            applyDungeonVictoryBonuses(
                memberIds = battleResult.log.teamMembers.filter { it.isAlive }.map { it.id }
            )
            giveBeastMaterialRewards(dungeonConfig, avgRealm)
            eventService.addGameEvent("探索队伍【${team.name}】在${dungeonConfig.name}遭遇妖兽并战胜", EventType.SUCCESS)
            updateDiscipleHpMpAfterBattle(battleResult.log.teamMembers)
        } else {
            eventService.addGameEvent("探索队伍【${team.name}】在${dungeonConfig.name}遭遇妖兽，战败撤退", EventType.DANGER)
            completeExploration(team, false, battleResult.log.teamMembers.filter { it.isAlive }.map { it.id }, survivorHpMap, survivorMpMap)
        }

        val battleLog = BattleLog(
            timestamp = System.currentTimeMillis(),
            year = data.gameYear,
            month = data.gameMonth,
            type = BattleType.PVE,
            attackerName = team.name,
            defenderName = dungeonConfig.name,
            result = if (battleResult.victory) BattleResult.WIN else BattleResult.LOSE,
            details = "秘境遭遇妖兽",
            dungeonName = dungeonConfig.name,
            teamId = team.id,
            teamMembers = battleResult.log.teamMembers.map { member ->
                BattleLogMember(
                    id = member.id,
                    name = member.name,
                    realm = member.realm,
                    realmName = member.realmName,
                    hp = member.hp,
                    maxHp = member.maxHp,
                    mp = member.mp,
                    maxMp = member.maxMp,
                    isAlive = member.isAlive
                )
            },
            enemies = battleResult.log.enemies.map { enemy ->
                BattleLogEnemy(
                    id = enemy.id,
                    name = enemy.name,
                    realm = enemy.realm,
                    realmName = enemy.realmName,
                    realmLayer = enemy.realmLayer,
                    hp = enemy.hp,
                    maxHp = enemy.maxHp,
                    isAlive = enemy.isAlive
                )
            },
            rounds = battleResult.log.rounds.map { round ->
                BattleLogRound(
                    roundNumber = round.roundNumber,
                    actions = round.actions.map { action ->
                        BattleLogAction(
                            type = action.type,
                            attacker = action.attacker,
                            attackerType = action.attackerType,
                            target = action.target,
                            damage = action.damage,
                            damageType = action.damageType,
                            isCrit = action.isCrit,
                            isKill = action.isKill,
                            message = action.message,
                            skillName = action.skillName
                        )
                    }
                )
            },
            turns = battleResult.turnCount,
            battleResult = BattleLogResult(
                winner = if (battleResult.victory) "team" else "beasts",
                isPlayerWin = battleResult.victory,
                turns = battleResult.turnCount,
                rounds = battleResult.log.rounds.size,
                teamCasualties = battleResult.log.teamMembers.count { !it.isAlive },
                beastsDefeated = battleResult.log.enemies.count { !it.isAlive }
            )
        )
        currentBattleLogs = listOf(battleLog) + currentBattleLogs.take(49)
    }

    private fun giveBeastMaterialRewards(dungeonConfig: GameConfig.DungeonConfig, avgRealm: Int) {
        val rarity = getRarityForRealm(avgRealm)
        val itemCount = Random.nextInt(3, 16)
        val rewardCounts = mutableMapOf<String, Int>()

        for (i in 0 until itemCount) {
            val beastMaterial = BeastMaterialDatabase.getRandomMaterialByBeastType(
                dungeonConfig.beastType, rarity
            )
            if (beastMaterial != null) {
                val material = Material(
                    id = java.util.UUID.randomUUID().toString(),
                    name = beastMaterial.name,
                    rarity = beastMaterial.rarity,
                    description = beastMaterial.description,
                    category = beastMaterial.materialCategory,
                    quantity = 1
                )
                inventorySystem.addMaterial(material)
                rewardCounts[beastMaterial.name] = rewardCounts.getOrDefault(beastMaterial.name, 0) + 1
            }
        }

        if (rewardCounts.isNotEmpty()) {
            val rewardDescriptions = rewardCounts.map { (name, count) -> "${name}x${count}" }
            eventService.addGameEvent("获得妖兽材料：${rewardDescriptions.joinToString("、")}", EventType.INFO)
        }
    }

    private suspend fun applyDungeonVictoryBonuses(memberIds: List<String>) {
        val data = currentGameData
            val equipmentInstanceMap = currentEquipmentInstances.associateBy { it.id }
            val manualInstanceMap = currentManualInstances.associateBy { it.id }
            var updatedManualProficiencies = data.manualProficiencies.toMutableMap()
            val discipleUpdates = mutableMapOf<Int, Disciple>()
            val equipmentInstanceUpdates = mutableMapOf<String, EquipmentInstance>()

            memberIds.forEach { memberId ->
                val discipleIndex = currentDisciples.indexOfFirst { it.id == memberId }
                if (discipleIndex < 0) return@forEach

                val disciple = currentDisciples[discipleIndex]
                if (!disciple.isAlive) return@forEach

                val newSoulPower = disciple.equipment.soulPower + 1

                val talentEffects = com.xianxia.sect.core.data.TalentDatabase.calculateTalentEffects(disciple.talentIds)
                val winGrowthAttr = if (talentEffects["winBattleRandomAttrPlus"] != null) {
                    listOf("maxHp", "maxMp", "physicalAttack", "magicAttack", "physicalDefense", "magicDefense", "speed").random()
                } else null

                var updatedDisciple = disciple.copyWith(
                    soulPower = newSoulPower
                )

                if (winGrowthAttr != null) {
                    val currentGrowth = updatedDisciple.statusData["winGrowth.$winGrowthAttr"]?.toIntOrNull() ?: 0
                    val newStatusData = updatedDisciple.statusData.toMutableMap().apply {
                        put("winGrowth.$winGrowthAttr", (currentGrowth + 1).toString())
                    }
                    updatedDisciple = when (winGrowthAttr) {
                        "maxHp" -> updatedDisciple.copyWith(baseHp = updatedDisciple.combat.baseHp + 1, statusData = newStatusData)
                        "maxMp" -> updatedDisciple.copyWith(baseMp = updatedDisciple.combat.baseMp + 1, statusData = newStatusData)
                        "physicalAttack" -> updatedDisciple.copyWith(basePhysicalAttack = updatedDisciple.combat.basePhysicalAttack + 1, statusData = newStatusData)
                        "magicAttack" -> updatedDisciple.copyWith(baseMagicAttack = updatedDisciple.combat.baseMagicAttack + 1, statusData = newStatusData)
                        "physicalDefense" -> updatedDisciple.copyWith(basePhysicalDefense = updatedDisciple.combat.basePhysicalDefense + 1, statusData = newStatusData)
                        "magicDefense" -> updatedDisciple.copyWith(baseMagicDefense = updatedDisciple.combat.baseMagicDefense + 1, statusData = newStatusData)
                        "speed" -> updatedDisciple.copyWith(baseSpeed = updatedDisciple.combat.baseSpeed + 1, statusData = newStatusData)
                        else -> updatedDisciple
                    }
                }

                disciple.manualIds.forEach { manualId ->
                    val manual = manualInstanceMap[manualId] ?: return@forEach
                    val proficiencyList = updatedManualProficiencies.getOrPut(memberId) { mutableListOf<ManualProficiencyData>() } as MutableList<ManualProficiencyData>
                    val existingIndex = proficiencyList.indexOfFirst { it.manualId == manualId }
                    val existing = if (existingIndex >= 0) proficiencyList[existingIndex] else null
                    val maxProf = ManualProficiencySystem.getMaxProficiency(manual.rarity)
                    val gain03pct = maxProf * 0.03
                    val newProficiency = if (existing != null) {
                        (existing.proficiency + gain03pct).coerceAtMost(maxProf)
                    } else {
                        gain03pct.coerceAtMost(maxProf)
                    }
                    val newMasteryLevel = ManualProficiencySystem.MasteryLevel.fromProficiency(newProficiency, manual.rarity).level
                    val updated = ManualProficiencyData(
                        manualId = manualId,
                        manualName = manual.name,
                        proficiency = newProficiency,
                        maxProficiency = maxProf.toInt(),
                        level = newMasteryLevel,
                        masteryLevel = newMasteryLevel
                    )
                    if (existingIndex >= 0) {
                        proficiencyList[existingIndex] = updated
                    } else {
                        proficiencyList.add(updated)
                    }
                }

                updatedDisciple.equipment.weaponId?.let { eqId ->
                    val eq = equipmentInstanceMap[eqId] ?: return@let
                    val expRequired = EquipmentNurtureSystem.getExpRequiredForLevelUp(eq.nurtureLevel, eq.rarity)
                    val result = EquipmentNurtureSystem.updateNurtureExp(eq, expRequired * 0.03)
                    equipmentInstanceUpdates[eqId] = result.equipment
                }
                updatedDisciple.equipment.armorId?.let { eqId ->
                    val eq = equipmentInstanceMap[eqId] ?: return@let
                    val expRequired = EquipmentNurtureSystem.getExpRequiredForLevelUp(eq.nurtureLevel, eq.rarity)
                    val result = EquipmentNurtureSystem.updateNurtureExp(eq, expRequired * 0.03)
                    equipmentInstanceUpdates[eqId] = result.equipment
                }
                updatedDisciple.equipment.bootsId?.let { eqId ->
                    val eq = equipmentInstanceMap[eqId] ?: return@let
                    val expRequired = EquipmentNurtureSystem.getExpRequiredForLevelUp(eq.nurtureLevel, eq.rarity)
                    val result = EquipmentNurtureSystem.updateNurtureExp(eq, expRequired * 0.03)
                    equipmentInstanceUpdates[eqId] = result.equipment
                }
                updatedDisciple.equipment.accessoryId?.let { eqId ->
                    val eq = equipmentInstanceMap[eqId] ?: return@let
                    val expRequired = EquipmentNurtureSystem.getExpRequiredForLevelUp(eq.nurtureLevel, eq.rarity)
                    val result = EquipmentNurtureSystem.updateNurtureExp(eq, expRequired * 0.03)
                    equipmentInstanceUpdates[eqId] = result.equipment
                }

                discipleUpdates[discipleIndex] = updatedDisciple
            }

            if (discipleUpdates.isNotEmpty()) {
                currentDisciples = currentDisciples.toMutableList().also { list ->
                    discipleUpdates.forEach { (index, d) -> list[index] = d }
                }
            }
            if (equipmentInstanceUpdates.isNotEmpty()) {
                currentEquipmentInstances = currentEquipmentInstances.map { eq -> equipmentInstanceUpdates[eq.id] ?: eq }
            }

            if (updatedManualProficiencies != data.manualProficiencies) {
                currentGameData = data.copy(manualProficiencies = updatedManualProficiencies)
            }
    }


    private fun updateDiscipleHpMpAfterBattle(battleMembers: List<BattleMemberData>) {
        val survivorIds = battleMembers.filter { it.isAlive }.map { it.id }.toSet()
        team@ for (member in battleMembers) {
            val discipleIndex = currentDisciples.indexOfFirst { it.id == member.id }
            if (discipleIndex < 0) continue@team
            val disciple = currentDisciples[discipleIndex]
            if (!survivorIds.contains(member.id)) {
                currentDisciples = currentDisciples.toMutableList().also { it[discipleIndex] = disciple.copy(isAlive = false) }
            } else {
                val hp = member.hp.coerceAtMost(member.maxHp)
                val mp = member.mp.coerceAtMost(member.maxMp)
                currentDisciples = currentDisciples.toMutableList().also {
                    it[discipleIndex] = disciple.copy(combat = disciple.combat.copy(currentHp = hp, currentMp = mp))
                }
            }
        }
    }

    /**
     * Complete exploration and reset team members
     */
    private suspend fun completeExploration(team: ExplorationTeam, success: Boolean, survivorIds: List<String>, survivorHpMap: Map<String, Int> = emptyMap(), survivorMpMap: Map<String, Int> = emptyMap()) {
        val updatedTeam = team.copy(status = ExplorationStatus.COMPLETED)
        currentTeams = currentTeams.map { if (it.id == team.id) updatedTeam else it }

        team.memberIds.forEach { memberId ->
            val disciple = currentDisciples.find { it.id == memberId }
            if (disciple != null) {
                val shouldKeepAlive = disciple.isAlive && survivorIds.contains(memberId)
                currentDisciples = currentDisciples.map { d ->
                    if (d.id == memberId) {
                        if (shouldKeepAlive) {
                            val hp = survivorHpMap[memberId] ?: d.combat.currentHp
                            val mp = survivorMpMap[memberId] ?: d.combat.currentMp
                            d.copy(status = DiscipleStatus.IDLE, combat = d.combat.copy(currentHp = hp, currentMp = mp))
                        } else {
                            handleDiscipleDeath(d, isOutsideSect = true)
                            d.copy(isAlive = false, status = DiscipleStatus.IDLE)
                        }
                    } else d
                }
            }
        }
    }

    /**
     * Process cave lifecycle (generation, expiration, AI teams)
     */
    suspend fun processCaveLifecycle(year: Int, month: Int) {
        val data = currentGameData

        val expiredCaveIds = data.cultivatorCaves.filter { cave ->
            cave.isExpired(year, month) || cave.status == CaveStatus.EXPLORED
        }.map { it.id }.toSet()

        val activeCaves = data.cultivatorCaves.filter { cave ->
            !cave.isExpired(year, month) && cave.status != CaveStatus.EXPLORED
        }

        expiredCaveIds.forEach { caveId ->
            val affectedTeams = data.caveExplorationTeams.filter {
                it.caveId == caveId && it.status == CaveExplorationStatus.TRAVELING
            }
            affectedTeams.forEach { team ->
                eventService.addGameEvent("洞府${team.caveName}已消失，正在前往的探索队伍返回宗门", EventType.WARNING)
                resetCaveExplorationTeamMembersStatus(team)
            }
        }

        val connectionEdges = buildConnectionEdges(data.worldMapSects)

        val newCaves = CaveGenerator.generateCaves(
            existingSects = data.worldMapSects,
            connectionEdges = connectionEdges,
            currentYear = year,
            currentMonth = month,
            existingCaves = activeCaves,
            maxNewCaves = 2
        )

        val allAITeams = data.aiCaveTeams.toMutableList()
        activeCaves.forEach { cave ->
            val currentTeams = allAITeams.count {
                it.caveId == cave.id && it.status == AITeamStatus.EXPLORING
            }

            if (currentTeams < 3 && Random.nextDouble() < 0.7) {
                val nearbySects = findNearbySects(cave, 400f)
                val existingTeamForCave = allAITeams.filter { it.caveId == cave.id }

                val aiTeam = AICaveTeamGenerator.generateAITeam(cave, nearbySects, existingTeamForCave, currentGameData.aiSectDisciples)
                if (aiTeam != null) {
                    allAITeams.add(aiTeam)
                }
            }
        }

        var updatedSectsForAI = currentGameData.worldMapSects.toMutableList()
        val updatedSectDetails = currentGameData.sectDetails.toMutableMap()
        val aiTeamsToRemove = mutableListOf<String>()

        allAITeams.filter { it.status == AITeamStatus.EXPLORING }.forEach { aiTeam ->
            val cave = activeCaves.find { it.id == aiTeam.caveId } ?: return@forEach

            if (Random.nextDouble() < 0.3) {
                val rewards = CaveExplorationSystem.generateVictoryRewards(cave)
                val warehouseItems = SectWarehouseManager.convertCaveRewardsToWarehouseItems(rewards)

                val sectIndex = updatedSectsForAI.indexOfFirst { it.id == aiTeam.sectId }
                if (sectIndex >= 0) {
                    val sect = updatedSectsForAI[sectIndex]
                    val detail = currentGameData.sectDetails[sect.id] ?: SectDetail(sectId = sect.id)
                    val updatedWarehouse = SectWarehouseManager.addItemsToWarehouse(detail.warehouse, warehouseItems)
                    val warehouseWithStones = SectWarehouseManager.addSpiritStonesToWarehouse(
                        updatedWarehouse,
                        rewards.items.filter { it.type == "spiritStones" }.sumOf { it.quantity.toLong() }
                    )
                    updatedSectsForAI[sectIndex] = sect
                    updatedSectDetails[aiTeam.sectId] = detail.copy(warehouse = warehouseWithStones)
                }

                aiTeamsToRemove.add(aiTeam.id)
            }
        }

        val filteredAITeams = allAITeams.filter { it.id !in aiTeamsToRemove }

        val teamsToComplete = data.caveExplorationTeams.filter {
            it.status == CaveExplorationStatus.EXPLORING
        }

        var finalCaves = activeCaves.toMutableList()
        var finalAITeams = filteredAITeams.toList()
        var finalExplorationTeams = data.caveExplorationTeams.filter {
            it.caveId !in expiredCaveIds
        }.toMutableList()
        val teamsWithMissingCave = mutableListOf<CaveExplorationTeam>()
        val teamsWithError = mutableListOf<CaveExplorationTeam>()

        teamsToComplete.forEach { team ->
            val cave = finalCaves.find { it.id == team.caveId }
            if (cave == null) {
                teamsWithMissingCave.add(team)
                return@forEach
            }
            try {
                val result = executeCaveExploration(team, cave, finalAITeams)

                finalCaves = result.first.toMutableList()
                finalAITeams = result.second
                if (result.third) {
                    finalExplorationTeams.removeAll { it.id == team.id }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing cave exploration for team ${team.id}", e)
                teamsWithError.add(team)
            }
        }

        teamsWithMissingCave.forEach { team ->
            eventService.addGameEvent("探索队伍前往的${team.caveName}已消失，队伍返回宗门", EventType.WARNING)
            resetCaveExplorationTeamMembersStatus(team)
            finalExplorationTeams.removeAll { it.id == team.id }
        }

        teamsWithError.forEach { team ->
            eventService.addGameEvent("探索队伍${team.caveName}发生错误，队伍返回宗门", EventType.WARNING)
            resetCaveExplorationTeamMembersStatus(team)
            finalCaves = finalCaves.map { cave ->
                if (cave.id == team.caveId && cave.status == CaveStatus.EXPLORING) {
                    cave.copy(status = CaveStatus.AVAILABLE)
                } else {
                    cave
                }
            }.toMutableList()
            finalExplorationTeams.removeAll { it.id == team.id }
        }

        val currentData = currentGameData
        currentGameData = currentData.copy(
            cultivatorCaves = finalCaves + newCaves,
            aiCaveTeams = finalAITeams,
            caveExplorationTeams = finalExplorationTeams,
            worldMapSects = updatedSectsForAI,
            sectDetails = updatedSectDetails
        )
    }

    /**
     * Execute cave exploration with battles
     */
    private suspend fun executeCaveExploration(
        team: CaveExplorationTeam,
        cave: CultivatorCave,
        currentAITeams: List<AICaveTeam>
    ): Triple<List<CultivatorCave>, List<AICaveTeam>, Boolean> {
        // 1. 获取队伍中的弟子
        val teamMembers = team.memberIds.mapNotNull { id ->
            currentDisciples.find { it.id == id }
        }.filter { it.isAlive }

        if (teamMembers.isEmpty()) {
            eventService.addGameEvent("探索队伍【${team.caveName}】全员阵亡或失联", EventType.DANGER)
            // 失败：保留洞府，移除AI队伍，返回失败
            return Triple(
                currentGameData.cultivatorCaves,
                currentAITeams.filter { it.caveId != cave.id },
                true // 队伍已完成（虽然失败），需要从探索列表移除
            )
        }

        val data = currentGameData
        val equipmentMap = currentEquipmentInstances.associateBy { it.id }
        val manualMap = currentManualInstances.associateBy { it.id }
        val allProficiencies = data.manualProficiencies.mapValues { (_, list) ->
            list.associateBy { it.manualId }
        }

        // 2. 检查是否有AI队伍在同一洞府
        val aiTeamInCave = currentAITeams.find { it.caveId == cave.id && it.status == AITeamStatus.EXPLORING }

        val battleResult = if (aiTeamInCave != null) {
            // 与AI队伍战斗
            eventService.addGameEvent("探索队伍在${cave.name}遭遇${aiTeamInCave.sectName}的探索队！", EventType.BATTLE)
            val battle = CaveExplorationSystem.createAIBattle(
                playerDisciples = teamMembers,
                playerEquipmentMap = equipmentMap,
                playerManualMap = manualMap,
                playerManualProficiencies = allProficiencies,
                aiTeam = aiTeamInCave
            )
            battleSystem.executeBattle(battle)
        } else {
            // 与洞府守护者战斗
            eventService.addGameEvent("探索队伍开始探索${cave.name}，遭遇守护者！", EventType.BATTLE)
            val battle = CaveExplorationSystem.createGuardianBattle(
                playerDisciples = teamMembers,
                playerEquipmentMap = equipmentMap,
                playerManualMap = manualMap,
                playerManualProficiencies = allProficiencies,
                cave = cave
            )
            battleSystem.executeBattle(battle)
        }

        // 3. 更新弟子状态（处理死亡/存活，回写HP/MP）
        val survivorIds = battleResult.log.teamMembers.filter { it.isAlive }.map { it.id }.toSet()
        val survivorHpMap = battleResult.log.teamMembers.filter { it.isAlive }.associate { it.id to it.hp }
        val survivorMpMap = battleResult.log.teamMembers.filter { it.isAlive }.associate { it.id to it.mp }
        currentDisciples = currentDisciples.map { disciple ->
            if (disciple.id in team.memberIds) {
                if (disciple.id in survivorIds) {
                    val hp = survivorHpMap[disciple.id] ?: disciple.combat.currentHp
                    val mp = survivorMpMap[disciple.id] ?: disciple.combat.currentMp
                    disciple.copy(status = DiscipleStatus.IDLE, combat = disciple.combat.copy(currentHp = hp, currentMp = mp))
                } else {
                    eventService.addGameEvent("${disciple.name}在${cave.name}探索中阵亡", EventType.DANGER)
                    handleDiscipleDeath(disciple, isOutsideSect = true)
                    disciple.copy(isAlive = false, status = DiscipleStatus.IDLE)
                }
            } else disciple
        }

        // 4. 根据战斗结果处理
        if (!battleResult.victory) {
            eventService.addGameEvent("探索队伍在${cave.name}探索失败", EventType.DANGER)
            // 失败：保留洞府（可被再次探索），清除相关AI队伍
            var updatedAITeams = currentAITeams.filter { it.caveId != cave.id }
            if (aiTeamInCave != null) {
                // AI队伍胜利，可能获得洞府奖励并离开
                updatedAITeams = updatedAITeams.toMutableList()
            }
            return Triple(
                currentGameData.cultivatorCaves,
                updatedAITeams,
                true // 探索完成（失败）
            )
        }

        // 5. 胜利：给予奖励
        eventService.addGameEvent("探索队伍成功征服${cave.name}！", EventType.SUCCESS)

        currentDisciples = currentDisciples.map { disciple ->
            if (disciple.id in survivorIds && disciple.isAlive) {
                disciple.copyWith(soulPower = disciple.equipment.soulPower + 1)
            } else {
                disciple
            }
        }

        val rewards = CaveExplorationSystem.generateVictoryRewards(cave)

        rewards.items.forEach { reward ->
            when (reward.type) {
                "spiritStones" -> {
                    currentGameData = currentGameData.copy(
                        spiritStones = currentGameData.spiritStones + reward.quantity.toLong()
                    )
                    eventService.addGameEvent("获得灵石 x${reward.quantity}", EventType.INFO)
                }
                "equipment" -> {
                    val template = EquipmentDatabase.getById(reward.itemId)
                    if (template != null) {
                        val equipment = EquipmentDatabase.createFromTemplate(template).copy(
                            rarity = reward.rarity,
                            quantity = reward.quantity
                        )
                        inventorySystem.addEquipmentStack(equipment)
                        eventService.addGameEvent("获得装备 ${template.name} x${reward.quantity}", EventType.SUCCESS)
                    } else {
                        eventService.addGameEvent("警告：无法找到装备模板 ${reward.itemId}，奖励跳过", EventType.WARNING)
                    }
                }
                "manual" -> {
                    val template = ManualDatabase.getById(reward.itemId)
                    if (template != null) {
                        val manual = ManualDatabase.createFromTemplate(template).copy(
                            rarity = reward.rarity,
                            quantity = reward.quantity
                        )
                        inventorySystem.addManualStack(manual)
                        eventService.addGameEvent("获得功法 ${template.name} x${reward.quantity}", EventType.SUCCESS)
                    } else {
                        eventService.addGameEvent("警告：无法找到功法模板 ${reward.itemId}，奖励跳过", EventType.WARNING)
                    }
                }
                "pill" -> {
                    val template = PillRecipeDatabase.getRecipeById(reward.itemId)
                    if (template != null) {
                        val pill = Pill(
                            id = java.util.UUID.randomUUID().toString(),
                            name = template.name,
                            rarity = template.rarity,
                            quantity = reward.quantity,
                            description = template.description,
                            category = template.category,
                            breakthroughChance = template.breakthroughChance,
                            targetRealm = template.targetRealm,
                            cultivationSpeedPercent = template.cultivationSpeedPercent,
                            duration = template.duration,
                            cultivationAdd = template.cultivationAdd,
                            skillExpAdd = template.skillExpAdd,
                            nurtureAdd = template.nurtureAdd,
                            extendLife = template.extendLife,
                            physicalAttackAdd = template.physicalAttackAdd,
                            magicAttackAdd = template.magicAttackAdd,
                            physicalDefenseAdd = template.physicalDefenseAdd,
                            magicDefenseAdd = template.magicDefenseAdd,
                            hpAdd = template.hpAdd,
                            mpAdd = template.mpAdd,
                            speedAdd = template.speedAdd,
                            critRateAdd = template.critRateAdd,
                            critEffectAdd = template.critEffectAdd,
                            intelligenceAdd = template.intelligenceAdd,
                            charmAdd = template.charmAdd,
                            loyaltyAdd = template.loyaltyAdd,
                            comprehensionAdd = template.comprehensionAdd,
                            artifactRefiningAdd = template.artifactRefiningAdd,
                            pillRefiningAdd = template.pillRefiningAdd,
                            spiritPlantingAdd = template.spiritPlantingAdd,
                            teachingAdd = template.teachingAdd,
                            moralityAdd = template.moralityAdd,
                            minRealm = GameConfig.Realm.getMinRealmForRarity(template.rarity)
                        )
                        inventorySystem.addPill(pill)
                        eventService.addGameEvent("获得丹药 ${template.name} x${reward.quantity}", EventType.SUCCESS)
                    } else {
                        eventService.addGameEvent("警告：无法找到丹药配方 ${reward.itemId}，奖励跳过", EventType.WARNING)
                    }
                }
            }
        }

        // 记录战斗日志
        val battleLog = BattleLog(
            timestamp = System.currentTimeMillis(),
            year = data.gameYear,
            month = data.gameMonth,
            type = BattleType.CAVE_EXPLORATION,
            attackerName = team.caveName,
            defenderName = cave.name,
            result = BattleResult.WIN,
            details = "洞府探索",
            dungeonName = cave.name,
            teamId = team.id,
            teamMembers = battleResult.log.teamMembers.map { member ->
                BattleLogMember(
                    id = member.id,
                    name = member.name,
                    realm = member.realm,
                    realmName = member.realmName,
                    hp = member.hp,
                    maxHp = member.maxHp,
                    mp = member.mp,
                    maxMp = member.maxMp,
                    isAlive = member.isAlive
                )
            },
            enemies = battleResult.log.enemies.map { enemy ->
                BattleLogEnemy(
                    id = enemy.id,
                    name = enemy.name,
                    realm = enemy.realm,
                    realmName = enemy.realmName,
                    realmLayer = enemy.realmLayer,
                    hp = enemy.hp,
                    maxHp = enemy.maxHp,
                    isAlive = enemy.isAlive
                )
            },
            rounds = battleResult.log.rounds.map { round ->
                BattleLogRound(
                    roundNumber = round.roundNumber,
                    actions = round.actions.map { action ->
                        BattleLogAction(
                            type = action.type,
                            attacker = action.attacker,
                            attackerType = action.attackerType,
                            target = action.target,
                            damage = action.damage,
                            damageType = action.damageType,
                            isCrit = action.isCrit,
                            isKill = action.isKill,
                            message = action.message,
                            skillName = action.skillName
                        )
                    }
                )
            },
            turns = battleResult.turnCount,
            battleResult = BattleLogResult(
                winner = if (battleResult.victory) "team" else "beasts",
                isPlayerWin = battleResult.victory,
                turns = battleResult.turnCount,
                rounds = battleResult.log.rounds.size,
                teamCasualties = battleResult.log.teamMembers.count { !it.isAlive },
                beastsDefeated = battleResult.log.enemies.count { !it.isAlive }
            )
        )
        currentBattleLogs = listOf(battleLog) + currentBattleLogs.take(49)

        // 6. 返回结果：标记洞府为已探索，移除相关AI队伍
        val updatedCaves = currentGameData.cultivatorCaves.map { c ->
            if (c.id == cave.id) c.copy(status = CaveStatus.EXPLORED) else c
        }
        val updatedAITeams = currentAITeams.filter { it.caveId != cave.id }

        return Triple(updatedCaves, updatedAITeams, true)
    }

    /**
     * Build connection edges for cave generation
     */
    private fun buildConnectionEdges(sects: List<WorldSect>): List<MSTEdge> {
        val edges = mutableListOf<MSTEdge>()
        val processedPairs = mutableSetOf<Pair<String, String>>()

        for (sect in sects) {
            for (connectedId in sect.connectedSectIds) {
                val pairKey = if (sect.id < connectedId) {
                    Pair(sect.id, connectedId)
                } else {
                    Pair(connectedId, sect.id)
                }

                if (pairKey !in processedPairs) {
                    processedPairs.add(pairKey)
                    val connectedSect = sects.find { it.id == connectedId }
                    if (connectedSect != null) {
                        val distance = kotlin.math.sqrt(
                            (sect.x - connectedSect.x) * (sect.x - connectedSect.x) +
                            (sect.y - connectedSect.y) * (sect.y - connectedSect.y)
                        ).toDouble()
                        edges.add(MSTEdge(sect, connectedSect, distance))
                    }
                }
            }
        }

        return edges
    }

    /**
     * Find nearby sects for cave
     */
    private fun findNearbySects(cave: CultivatorCave, range: Float): List<WorldSect> {
        val data = currentGameData
        return data.worldMapSects.filter { sect ->
            !sect.isPlayerSect &&
            kotlin.math.sqrt(
                (cave.x - sect.x) * (cave.x - sect.x) +
                (cave.y - sect.y) * (cave.y - sect.y)
            ) <= range
        }
    }

    /**
     * Reset cave exploration team members status
     */
    private fun resetCaveExplorationTeamMembersStatus(team: CaveExplorationTeam) {
        team.memberIds.forEach { memberId ->
            val disciple = currentDisciples.find { it.id == memberId }
            if (disciple != null && disciple.status == DiscipleStatus.IN_TEAM) {
                currentDisciples = currentDisciples.map {
                    if (it.id == memberId) it.copy(status = DiscipleStatus.IDLE) else it
                }
            }
        }
    }

    /**
     * Process AI sect operations (cultivation, recruitment, aging)
     */
    private fun processAISectOperations(year: Int, month: Int) {
        val data = currentGameData
        val aiDisciples = data.aiSectDisciples

        val updatedAiDisciples = aiDisciples.mapValues { (sectId, disciples) ->
            val sect = data.worldMapSects.find { it.id == sectId }
            if (sect == null || sect.isPlayerSect) return@mapValues disciples

            AISectDiscipleManager.processMonthlyCultivation(disciples)
        }

        currentGameData = data.copy(aiSectDisciples = updatedAiDisciples)

        if (month == 1) {
            processSectDisciplesYearlyRecruitment(year)
        }

        processSectDisciplesAging(year)

        processAISectAttackDecisions()
    }

    private fun processSectDisciplesYearlyRecruitment(year: Int) {
        val data = currentGameData
        val updatedAiDisciples = data.aiSectDisciples.mapValues { (sectId, disciples) ->
            val sect = data.worldMapSects.find { it.id == sectId }
            if (sect == null || sect.isPlayerSect) return@mapValues disciples
            AISectDiscipleManager.recruitYearlyDisciples(sect.name, disciples)
        }
        currentGameData = data.copy(aiSectDisciples = updatedAiDisciples)
    }

    private fun processSectDisciplesAging(year: Int) {
        val data = currentGameData
        val updatedAiDisciples = data.aiSectDisciples.mapValues { (sectId, disciples) ->
            val sect = data.worldMapSects.find { it.id == sectId }
            if (sect == null || sect.isPlayerSect) return@mapValues disciples
            AISectDiscipleManager.processAging(disciples)
        }
        currentGameData = data.copy(aiSectDisciples = updatedAiDisciples)
    }

    /**
     * Process AI sect attack decisions
     */
    private fun processAISectAttackDecisions() {
        val data = currentGameData

        val newBattles = AISectAttackManager.decideAttacks(data)
        val playerAttack = AISectAttackManager.decidePlayerAttack(data)

        val allNewBattles = newBattles + listOfNotNull(playerAttack)

        if (allNewBattles.isNotEmpty()) {
            currentGameData = data.copy(
                aiBattleTeams = data.aiBattleTeams + allNewBattles
            )
        }
    }

    /**
     * Generate yearly items for AI sects
     */
    private fun generateYearlyItemsForAISects() {
        val data = currentGameData
        val updatedDetails = data.sectDetails.toMutableMap()
        val updatedSects = data.worldMapSects.map { sect ->
            if (!sect.isPlayerSect && !sect.isPlayerOccupied) {
                val newWarehouse = SectWarehouseManager.generateYearlyItemsForAISect(sect)
                val detail = updatedDetails[sect.id] ?: SectDetail(sectId = sect.id)
                val existingWarehouse = detail.warehouse
                val mergedWarehouse = SectWarehouseManager.addItemsToWarehouse(
                    existingWarehouse,
                    newWarehouse.items
                )
                val finalWarehouse = SectWarehouseManager.addSpiritStonesToWarehouse(
                    mergedWarehouse,
                    newWarehouse.spiritStones
                )
                updatedDetails[sect.id] = detail.copy(warehouse = finalWarehouse)
                sect
            } else {
                sect
            }
        }
        currentGameData = data.copy(worldMapSects = updatedSects, sectDetails = updatedDetails)
    }

    /**
     * Process outer tournament (every 3 years)
     */
    private fun processOuterTournament(year: Int) {
        if (year % 3 != 0) {
            return
        }

        try {
            val outerDisciples = currentDisciples.filter {
                it.isAlive &&
                it.discipleType == "outer" &&
                it.status != DiscipleStatus.REFLECTING
            }

            if (outerDisciples.isEmpty()) {
                eventService.addGameEvent("第${year}年外门大比：无符合条件的弟子参与", EventType.INFO)
                return
            }

            eventService.addGameEvent("第${year}年外门大比开始，共有${outerDisciples.size}名外门弟子参与", EventType.INFO)

            val sortedDisciples = outerDisciples.sortedWith(
                compareBy<Disciple> { it.realm }
                    .thenByDescending { it.realmLayer }
                    .thenByDescending { it.cultivation }
                    .thenByDescending { it.skills.comprehension }
            )

            val topCount = minOf(10, sortedDisciples.size)
            val topDisciples = sortedDisciples.take(topCount)
            val competitionResults = topDisciples.mapIndexed { index, disciple ->
                CompetitionRankResult(discipleId = disciple.id, rank = index + 1)
            }

            currentGameData = currentGameData.copy(
                pendingCompetitionResults = competitionResults,
                lastCompetitionYear = year
            )

            val topNames = topDisciples.map { it.name }
            eventService.addGameEvent("第${year}年外门大比排名揭晓：${topNames.joinToString("、")} 进入前十名，请选择晋升弟子", EventType.SUCCESS)

        } catch (e: Exception) {
            Log.e(TAG, "Error in processOuterTournament", e)
            eventService.addGameEvent("第${year}年外门大比处理出错", EventType.DANGER)
        }
    }

    /**
     * Helper methods that will be implemented by delegating to other services or kept here
     */

    private fun calculateBuildingCultivationBonus(disciple: Disciple, data: GameData): Double {
        return 1.0
    }

    private suspend fun clearDiscipleFromAllSlots(discipleId: String) {
        val data = currentGameData

        val updatedSpiritMineSlots = data.spiritMineSlots.map {
            if (it.discipleId == discipleId) it.copy(discipleId = "", discipleName = "") else it
        }

        val updatedLibrarySlots = data.librarySlots.map {
            if (it.discipleId == discipleId) it.copy(discipleId = "", discipleName = "") else it
        }

        val updatedElderSlots = data.elderSlots.let { slots ->
            var updated = slots

            if (updated.viceSectMaster == discipleId) updated = updated.copy(viceSectMaster = "")
            if (updated.herbGardenElder == discipleId) updated = updated.copy(herbGardenElder = "")
            if (updated.alchemyElder == discipleId) updated = updated.copy(alchemyElder = "")
            if (updated.forgeElder == discipleId) updated = updated.copy(forgeElder = "")
            if (updated.outerElder == discipleId) updated = updated.copy(outerElder = "")
            if (updated.preachingElder == discipleId) updated = updated.copy(preachingElder = "")
            if (updated.lawEnforcementElder == discipleId) updated = updated.copy(lawEnforcementElder = "")
            if (updated.innerElder == discipleId) updated = updated.copy(innerElder = "")
            if (updated.qingyunPreachingElder == discipleId) updated = updated.copy(qingyunPreachingElder = "")

            // 清理列表槽位
            updated = updated.copy(
                preachingMasters = updated.preachingMasters.mapNotNull { slot ->
                    if (slot.discipleId == discipleId) DirectDiscipleSlot(index = slot.index) else slot
                },
                lawEnforcementDisciples = updated.lawEnforcementDisciples.mapNotNull { slot ->
                    if (slot.discipleId == discipleId) DirectDiscipleSlot(index = slot.index) else slot
                },
                qingyunPreachingMasters = updated.qingyunPreachingMasters.mapNotNull { slot ->
                    if (slot.discipleId == discipleId) DirectDiscipleSlot(index = slot.index) else slot
                },
                herbGardenDisciples = updated.herbGardenDisciples.mapNotNull { slot ->
                    if (slot.discipleId == discipleId) DirectDiscipleSlot(index = slot.index) else slot
                },
                alchemyDisciples = updated.alchemyDisciples.mapNotNull { slot ->
                    if (slot.discipleId == discipleId) DirectDiscipleSlot(index = slot.index) else slot
                },
                forgeDisciples = updated.forgeDisciples.mapNotNull { slot ->
                    if (slot.discipleId == discipleId) DirectDiscipleSlot(index = slot.index) else slot
                },
                lawEnforcementReserveDisciples = updated.lawEnforcementReserveDisciples.mapNotNull { slot ->
                    if (slot.discipleId == discipleId) DirectDiscipleSlot(index = slot.index) else slot
                },
                herbGardenReserveDisciples = updated.herbGardenReserveDisciples.mapNotNull { slot ->
                    if (slot.discipleId == discipleId) DirectDiscipleSlot(index = slot.index) else slot
                },
                alchemyReserveDisciples = updated.alchemyReserveDisciples.mapNotNull { slot ->
                    if (slot.discipleId == discipleId) DirectDiscipleSlot(index = slot.index) else slot
                },
                forgeReserveDisciples = updated.forgeReserveDisciples.mapNotNull { slot ->
                    if (slot.discipleId == discipleId) DirectDiscipleSlot(index = slot.index) else slot
                },
                spiritMineDeaconDisciples = updated.spiritMineDeaconDisciples.mapNotNull { slot ->
                    if (slot.discipleId == discipleId) DirectDiscipleSlot(index = slot.index) else slot
                }
            )

            updated
        }

        currentGameData = data.copy(
            spiritMineSlots = updatedSpiritMineSlots,
            librarySlots = updatedLibrarySlots,
            elderSlots = updatedElderSlots
        )

        val forgeSlots = productionSlotRepository.getSlotsByBuildingId("forge")
        for (slot in forgeSlots) {
            if (slot.assignedDiscipleId == discipleId && !slot.isWorking) {
                productionSlotRepository.updateSlotByBuildingId("forge", slot.slotIndex) { s ->
                    s.copy(assignedDiscipleId = null, assignedDiscipleName = "")
                }
            }
        }
    }

    private fun returnEquipmentToWarehouse(equipmentId: String) {
        val eq = currentEquipmentInstances.find { it.id == equipmentId } ?: return
        val stack = eq.toStack()
        val existingStack = currentEquipmentStacks.find {
            it.name == stack.name && it.rarity == stack.rarity && it.slot == stack.slot
        }
        if (existingStack != null) {
            val newQty = (existingStack.quantity + stack.quantity).coerceAtMost(inventoryConfig.getMaxStackSize("equipment_stack"))
            currentEquipmentStacks = currentEquipmentStacks.map { s ->
                if (s.id == existingStack.id) s.copy(quantity = newQty) else s
            }
        } else {
            currentEquipmentStacks = currentEquipmentStacks + stack
        }
        currentEquipmentInstances = currentEquipmentInstances.filter { it.id != equipmentId }
    }

    private fun removeEquipmentFromDisciple(discipleId: String, equipmentId: String) {
        val equipment = currentEquipmentInstances.find { it.id == equipmentId } ?: return
        if (!equipment.isEquipped) return

        currentEquipmentInstances = currentEquipmentInstances.map { eq ->
            if (eq.id == equipmentId) {
                eq.copy(isEquipped = false, ownerId = null, nurtureLevel = 0, nurtureProgress = 0.0)
            } else eq
        }
    }

    private fun getBuildingName(buildingId: String): String = BuildingNames.getDisplayName(buildingId)

    private fun processScoutInfoExpiry(year: Int, month: Int) {
        val data = currentGameData
        var hasExpired = false

        val updatedScoutInfo = data.scoutInfo.filter { (_, info) ->
            val isExpired = year > info.expiryYear ||
                (year == info.expiryYear && month > info.expiryMonth)
            if (isExpired) {
                hasExpired = true
                eventService.addGameEvent("${info.sectId}的探查信息已过期", EventType.INFO)
            }
            !isExpired
        }

        if (hasExpired) {
            val updatedWorldMapSects = data.worldMapSects.map { sect ->
                val sectScoutInfo = updatedScoutInfo[sect.id]
                if (sectScoutInfo == null && data.sectDetails[sect.id]?.scoutInfo?.sectId?.isNotEmpty() == true) {
                    sect.copy(isKnown = false)
                } else {
                    sect
                }
            }

            val updatedDetails = data.sectDetails.toMutableMap()
            updatedScoutInfo.forEach { (sectId, _) ->
                val detail = updatedDetails[sectId] ?: SectDetail(sectId = sectId)
                updatedDetails[sectId] = detail.copy(scoutInfo = updatedScoutInfo[sectId] ?: SectScoutInfo())
            }
            data.sectDetails.forEach { (sectId, detail) ->
                if (updatedScoutInfo[sectId] == null && detail.scoutInfo.sectId.isNotEmpty()) {
                    updatedDetails[sectId] = detail.copy(scoutInfo = SectScoutInfo())
                }
            }

            currentGameData = data.copy(
                scoutInfo = updatedScoutInfo,
                worldMapSects = updatedWorldMapSects,
                sectDetails = updatedDetails
            )
        }
    }

    private fun processDiplomacyMonthlyEvents(year: Int, month: Int) {
        // Process alliance checks, relation changes, etc.
        // 当前版本：外交月度事件（好感度自然衰减、礼物过期等）尚未实现，
        // 此方法保留为扩展点。盟约到期检查已由 checkAllianceExpiry / checkAllianceFavorDrop 处理。
    }

    internal suspend fun refreshTravelingMerchant(year: Int, month: Int) {
        val pools = buildMerchantItemPools()

        if (pools.poolByRarity.values.all { it.isEmpty() }) return

        val data = currentGameData
        val newRefreshCount = data.merchantRefreshCount + 1
        val isPityRefresh = newRefreshCount % MERCHANT_PITY_THRESHOLD == 0

        val newItems = mutableListOf<MerchantItem>()

        if (isPityRefresh) {
            addGuaranteedMythicItem(newItems, pools, year, month, newRefreshCount)
        }

        val remainingCount = TRAVELING_MERCHANT_ITEM_COUNT - newItems.size
        repeat(remainingCount) {
            val selectedRarity = selectRarity()
            val selectedItem = selectItemByRarity(pools.poolByRarity, selectedRarity)
                ?: selectFirstAvailableItem(pools.poolByRarity)

            if (selectedItem != null) {
                newItems.add(createMerchantItem(selectedItem, pools, year, month))
            }
        }

        val mergedItems = mergeMerchantItems(newItems)

        currentGameData = data.copy(
            travelingMerchantItems = mergedItems,
            merchantLastRefreshYear = year,
            merchantRefreshCount = newRefreshCount
        )
    }

    private data class PoolEntry(
        val name: String,
        val type: String
    )

    private data class MerchantItemPools(
        val poolByRarity: MutableMap<Int, MutableList<PoolEntry>> = mutableMapOf(),
        val rarityMap: MutableMap<String, Int> = mutableMapOf(),
        val priceMap: MutableMap<String, Long> = mutableMapOf()
    )

    private fun buildMerchantItemPools(): MerchantItemPools {
        val pools = MerchantItemPools()

        for (rarity in 1..6) {
            pools.poolByRarity[rarity] = mutableListOf()
        }

        EquipmentDatabase.allTemplates.values.forEach { t ->
            pools.poolByRarity.getOrPut(t.rarity) { mutableListOf() }.add(PoolEntry(t.name, "equipment"))
            pools.rarityMap[t.name] = t.rarity
            pools.priceMap[t.name] = (t.price * GameConfig.Rarity.PRICE_MULTIPLIER).roundToInt().toLong()
        }

        if (ManualDatabase.isInitialized) {
            ManualDatabase.allManuals.values.forEach { t ->
                pools.poolByRarity.getOrPut(t.rarity) { mutableListOf() }.add(PoolEntry(t.name, "manual"))
                pools.rarityMap[t.name] = t.rarity
                pools.priceMap[t.name] = (t.price * GameConfig.Rarity.PRICE_MULTIPLIER).roundToInt().toLong()
            }
        }

        val addedPillNames = mutableSetOf<String>()
        ItemDatabase.allPills.values.forEach { t ->
            if (t.grade == PillGrade.MEDIUM && t.name !in addedPillNames) {
                addedPillNames.add(t.name)
                pools.poolByRarity.getOrPut(t.rarity) { mutableListOf() }.add(PoolEntry(t.name, "pill"))
                pools.rarityMap[t.name] = t.rarity
                pools.priceMap[t.name] = (t.price * GameConfig.Rarity.PRICE_MULTIPLIER).roundToInt().toLong()
            }
        }

        ItemDatabase.allMaterials.values.forEach { t ->
            pools.poolByRarity.getOrPut(t.rarity) { mutableListOf() }.add(PoolEntry(t.name, "material"))
            pools.rarityMap[t.name] = t.rarity
            pools.priceMap[t.name] = (t.price * GameConfig.Rarity.PRICE_MULTIPLIER).roundToInt().toLong()
        }

        HerbDatabase.getAllHerbs().forEach { h ->
            pools.poolByRarity.getOrPut(h.rarity) { mutableListOf() }.add(PoolEntry(h.name, "herb"))
            pools.rarityMap[h.name] = h.rarity
            pools.priceMap[h.name] = (h.price * GameConfig.Rarity.PRICE_MULTIPLIER).roundToInt().toLong()
        }

        HerbDatabase.getAllSeeds().forEach { s ->
            pools.poolByRarity.getOrPut(s.rarity) { mutableListOf() }.add(PoolEntry(s.name, "seed"))
            pools.rarityMap[s.name] = s.rarity
            pools.priceMap[s.name] = (s.price * GameConfig.Rarity.PRICE_MULTIPLIER).roundToInt().toLong()
        }

        return pools
    }

    private fun selectRarity(): Int {
        val rand = Random.nextDouble()
        var cumulative = 0.0
        for ((rarity, prob) in RARITY_PROBABILITIES.entries.sortedByDescending { it.key }) {
            cumulative += prob
            if (rand < cumulative) return rarity
        }
        return 1
    }

    private fun selectItemByRarity(itemPoolByRarity: Map<Int, List<PoolEntry>>, rarity: Int): PoolEntry? {
        return itemPoolByRarity[rarity]?.takeIf { it.isNotEmpty() }?.random()
    }

    private fun selectFirstAvailableItem(itemPoolByRarity: Map<Int, List<PoolEntry>>): PoolEntry? {
        return (1..6).firstNotNullOfOrNull { r -> itemPoolByRarity[r]?.takeIf { it.isNotEmpty() }?.random() }
    }

    private fun calculateMerchantStock(type: String, rarity: Int): Int {
        val isConsumable = type in listOf("herb", "seed", "material")
        return if (isConsumable) {
            when (rarity) {
                6 -> Random.nextInt(3, 8)
                5 -> Random.nextInt(3, 8)
                4 -> Random.nextInt(5, 11)
                3 -> Random.nextInt(5, 13)
                2 -> Random.nextInt(5, 16)
                else -> Random.nextInt(7, 16)
            }
        } else {
            when (rarity) {
                6 -> Random.nextInt(1, 4)
                5 -> Random.nextInt(1, 4)
                4 -> Random.nextInt(1, 6)
                3 -> Random.nextInt(1, 6)
                2 -> Random.nextInt(1, 6)
                else -> Random.nextInt(1, 6)
            }
        }
    }

    private fun selectMerchantPillGrade(): PillGrade {
        val roll = Random.nextDouble()
        return when {
            roll < 0.03 -> PillGrade.HIGH
            roll < 0.40 -> PillGrade.MEDIUM
            else -> PillGrade.LOW
        }
    }

    private fun createMerchantItem(
        entry: PoolEntry,
        pools: MerchantItemPools,
        year: Int,
        month: Int,
        forcedRarity: Int? = null
    ): MerchantItem {
        val rarity = forcedRarity ?: pools.rarityMap[entry.name] ?: 1
        val basePrice = pools.priceMap[entry.name]
            ?: (GameConfig.Rarity.get(rarity).materialBasePrice * GameConfig.Rarity.PRICE_MULTIPLIER).roundToInt().toLong()
        val quantity = calculateMerchantStock(entry.type, rarity)

        val grade: PillGrade? = if (entry.type == "pill") selectMerchantPillGrade() else null
        val adjustedPrice = if (grade != null) (basePrice * grade.priceMultiplier / PillGrade.MEDIUM.priceMultiplier).roundToLong() else basePrice

        return MerchantItem(
            id = java.util.UUID.randomUUID().toString(),
            name = entry.name,
            type = entry.type,
            itemId = java.util.UUID.randomUUID().toString(),
            rarity = rarity,
            price = GameUtils.applyPriceFluctuation(adjustedPrice),
            quantity = quantity,
            obtainedYear = year,
            obtainedMonth = month,
            grade = grade?.displayName
        )
    }

    private fun mergeMerchantItems(items: List<MerchantItem>): List<MerchantItem> {
        val merged = mutableMapOf<String, MerchantItem>()
        for (item in items) {
            val key = if (item.grade != null) "${item.name}:${item.type}:${item.grade}" else "${item.name}:${item.type}"
            val existing = merged[key]
            if (existing != null) {
                val totalQuantity = existing.quantity + item.quantity
                val weightedPrice = (existing.price * existing.quantity + item.price * item.quantity) / totalQuantity
                merged[key] = existing.copy(
                    quantity = totalQuantity,
                    price = weightedPrice
                )
            } else {
                merged[key] = item
            }
        }
        return merged.values.toList()
    }

    private fun addGuaranteedMythicItem(
        newItems: MutableList<MerchantItem>,
        pools: MerchantItemPools,
        year: Int,
        month: Int,
        refreshCount: Int
    ) {
        val mythicPool = pools.poolByRarity[6]
        if (mythicPool == null || mythicPool.isEmpty()) {
            Log.w(TAG, "商人保底触发但天品物品池为空，跳过保底")
            return
        }

        val mythicItem = mythicPool.random()
        val guaranteedMythicItem = createMerchantItem(mythicItem, pools, year, month, forcedRarity = 6)

        newItems.add(guaranteedMythicItem)

        Log.i(TAG, "商人第${refreshCount}次刷新触发保底，优先添加天品物品：${mythicItem.name}")
    }

    internal suspend fun refreshRecruitList(year: Int) {
        val spiritRootTypes = listOf("metal", "wood", "water", "fire", "earth")
        val spiritRootNames = mapOf(
            "metal" to "金灵根", "wood" to "木灵根", "water" to "水灵根",
            "fire" to "火灵根", "earth" to "土灵根"
        )
        val recruitCount = Random.nextInt(3, 16)
        val newRecruitDisciples = mutableListOf<Disciple>()
        val usedNames = (currentDisciples + currentGameData.recruitList).map { it.name }.toMutableSet()
        repeat(recruitCount) {
            val gender = if (Random.nextBoolean()) "male" else "female"
            val nameResult = NameService.generateName(gender, NameService.NameStyle.FULL, usedNames)
            val spiritRootType = spiritRootTypes.let { types ->
                val rootCount = when (Random.nextInt(100)) {
                    in 0..4 -> 1
                    in 5..24 -> 2
                    in 25..54 -> 3
                    in 55..84 -> 4
                    else -> 5
                }
                types.shuffled().take(rootCount).joinToString(",")
            }
            val hpVariance = Random.nextInt(-50, 51)
            val mpVariance = Random.nextInt(-50, 51)
            val physicalAttackVariance = Random.nextInt(-50, 51)
            val magicAttackVariance = Random.nextInt(-50, 51)
            val physicalDefenseVariance = Random.nextInt(-50, 51)
            val magicDefenseVariance = Random.nextInt(-50, 51)
            val speedVariance = Random.nextInt(-50, 51)
            val spiritRootCount = spiritRootType.split(",").size
            val comprehension = when (spiritRootCount) {
                1 -> Random.nextInt(80, 101)
                2 -> Random.nextInt(60, 101)
                3 -> Random.nextInt(40, 101)
                4 -> Random.nextInt(20, 101)
                else -> Random.nextInt(1, 101)
            }
            val disciple = Disciple(
                id = java.util.UUID.randomUUID().toString(),
                name = nameResult.fullName,
                surname = nameResult.surname,
                gender = gender,
                age = Random.nextInt(16, 30),
                realm = 9,
                realmLayer = 1,
                spiritRootType = spiritRootType,
                status = DiscipleStatus.IDLE,
                discipleType = "outer",
                talentIds = TalentDatabase.generateTalentsForDisciple().map { it.id },
                combat = com.xianxia.sect.core.model.CombatAttributes(
                    hpVariance = hpVariance,
                    mpVariance = mpVariance,
                    physicalAttackVariance = physicalAttackVariance,
                    magicAttackVariance = magicAttackVariance,
                    physicalDefenseVariance = physicalDefenseVariance,
                    magicDefenseVariance = magicDefenseVariance,
                    speedVariance = speedVariance
                ),
                social = com.xianxia.sect.core.model.SocialData(),
                skills = com.xianxia.sect.core.model.SkillStats(
                    intelligence = Random.nextInt(1, 101),
                    charm = Random.nextInt(1, 101),
                    loyalty = Random.nextInt(1, 101),
                    comprehension = comprehension,
                    morality = Random.nextInt(1, 101),
                    artifactRefining = Random.nextInt(1, 101),
                    pillRefining = Random.nextInt(1, 101),
                    spiritPlanting = Random.nextInt(1, 101),
                    teaching = Random.nextInt(1, 101)
                )
            ).apply {
                val baseStats = Disciple.calculateBaseStatsWithVariance(
                    hpVariance, mpVariance, physicalAttackVariance, magicAttackVariance,
                    physicalDefenseVariance, magicDefenseVariance, speedVariance
                )
                combat.baseHp = baseStats.baseHp
                combat.baseMp = baseStats.baseMp
                combat.basePhysicalAttack = baseStats.basePhysicalAttack
                combat.baseMagicAttack = baseStats.baseMagicAttack
                combat.basePhysicalDefense = baseStats.basePhysicalDefense
                combat.baseMagicDefense = baseStats.baseMagicDefense
                combat.baseSpeed = baseStats.baseSpeed

                // 计算寿命天赋加成（如"寿元绵长"/"寿元亏损"）
                val talentEffects = TalentDatabase.calculateTalentEffects(talentIds)
                val lifespanBonus = talentEffects["lifespan"] ?: 0.0
                val baseLifespan = GameConfig.Realm.get(realm).maxAge
                lifespan = (baseLifespan * (1.0 + lifespanBonus)).toInt().coerceAtLeast(1)
            }
            newRecruitDisciples.add(disciple)
            usedNames.add(disciple.name)
        }
        val previousRecruitCount = currentGameData.recruitList.size
        currentGameData = currentGameData.copy(recruitList = newRecruitDisciples, lastRecruitYear = year)
        Log.d(TAG, "refreshRecruitList: year=$year, generated ${newRecruitDisciples.size} new recruits (previous recruitList had $previousRecruitCount)")
    }

    private fun processYearlyAging(year: Int) {
        // 当前版本：年度老化效果（寿元额外消耗、境界倒退风险等）尚未实现，
        // 此方法保留为扩展点。月度老化已由 processDiscipleAging 处理。
    }

    private fun processReflectionRelease(year: Int) {
        val reflectingDisciples = currentDisciples.filter { it.status == DiscipleStatus.REFLECTING && it.isAlive }
        if (reflectingDisciples.isEmpty()) return

        val updatedDisciples = currentDisciples.map { disciple ->
            if (disciple.status != DiscipleStatus.REFLECTING || !disciple.isAlive) return@map disciple

            val endYear = disciple.statusData["reflectionEndYear"]?.toIntOrNull() ?: return@map disciple
            if (year < endYear) return@map disciple

            val newMorality = (disciple.skills.morality + 10).coerceAtMost(100)
            disciple.copy(
                status = DiscipleStatus.IDLE,
                statusData = disciple.statusData - "reflectionStartYear" - "reflectionEndYear",
                skills = disciple.skills.copy(morality = newMorality)
            )
        }

        currentDisciples = updatedDisciples

        val releasedCount = reflectingDisciples.count { it.statusData["reflectionEndYear"]?.toIntOrNull()?.let { year >= it } == true }
        if (releasedCount > 0) {
            eventService.addGameEvent("思过崖：共$releasedCount 名弟子思过期满，已释放并恢复空闲状态，道德+10", EventType.INFO)
        }
    }

    private fun processCrossSectPartnerMatching(year: Int, month: Int) {
        // Cross-sect marriage matching
        // 当前版本：跨宗门联姻系统尚未实现，此方法保留为扩展点。
    }

    private fun checkAllianceExpiry(year: Int) {
        val data = currentGameData
        val expiredAlliances = data.alliances.filter { year - it.startYear >= GameConfig.Diplomacy.ALLIANCE_DURATION_YEARS }

        if (expiredAlliances.isEmpty()) return

        val updatedAlliances = data.alliances.filter { year - it.startYear < GameConfig.Diplomacy.ALLIANCE_DURATION_YEARS }
        val updatedSects = data.worldMapSects.map { sect ->
            if (expiredAlliances.any { it.sectIds.contains(sect.id) }) {
                sect.copy(allianceId = "", allianceStartYear = 0)
            } else sect
        }

        currentGameData = data.copy(
            alliances = updatedAlliances,
            worldMapSects = updatedSects
        )

        expiredAlliances.forEach { alliance ->
            val sect = data.worldMapSects.find { it.id != "player" && alliance.sectIds.contains(it.id) }
            if (sect != null) {
                eventService.addGameEvent("与${sect.name}的盟约已到期自动解散", EventType.INFO)
            }
        }
    }

    private fun checkAllianceFavorDrop() {
        val data = currentGameData
        val dissolvedAlliances = mutableListOf<Alliance>()
        val playerSect = data.worldMapSects.find { it.isPlayerSect }

        data.alliances.forEach { alliance ->
            if (!alliance.sectIds.contains("player")) return@forEach

            val sectId = alliance.sectIds.find { it != "player" }
            if (sectId != null && playerSect != null) {
                val sect = data.worldMapSects.find { it.id == sectId }
                val relation = data.sectRelations.find {
                    (it.sectId1 == playerSect.id && it.sectId2 == sectId) ||
                    (it.sectId1 == sectId && it.sectId2 == playerSect.id)
                }
                val favor = relation?.favor ?: 0
                if (favor < GameConfig.Diplomacy.MIN_ALLIANCE_FAVOR) {
                    dissolvedAlliances.add(alliance)
                    eventService.addGameEvent("与${sect?.name ?: "宗门"}的关系过低，盟约自动解除", EventType.WARNING)
                }
            }
        }

        if (dissolvedAlliances.isNotEmpty()) {
            val updatedAlliances = data.alliances.filter { it !in dissolvedAlliances }
            val updatedSects = data.worldMapSects.map { sect ->
                if (dissolvedAlliances.any { it.sectIds.contains(sect.id) }) {
                    sect.copy(allianceId = "", allianceStartYear = 0)
                } else sect
            }
            currentGameData = data.copy(
                alliances = updatedAlliances,
                worldMapSects = updatedSects
            )
        }
    }

    private fun processFavorDecay(currentYear: Int) {
        val data = currentGameData
        val playerSect = data.worldMapSects.find { it.isPlayerSect } ?: return

        val updatedRelations = data.sectRelations.map { relation ->
            val involvesPlayer = relation.sectId1 == playerSect.id || relation.sectId2 == playerSect.id
            if (!involvesPlayer) return@map relation

            if (relation.favor <= GameConfig.Diplomacy.FAVOR_DECAY_THRESHOLD) return@map relation

            val yearsSinceGift = currentYear - relation.lastInteractionYear
            if (yearsSinceGift < GameConfig.Diplomacy.FAVOR_DECAY_NO_GIFT_YEARS) return@map relation

            val newFavor = (relation.favor - GameConfig.Diplomacy.FAVOR_DECAY_AMOUNT).coerceAtLeast(GameConfig.Diplomacy.FAVOR_DECAY_THRESHOLD)
            relation.copy(favor = newFavor, noGiftYears = relation.noGiftYears + 1)
        }

        val hasChanges = updatedRelations.zip(data.sectRelations).any { (a, b) -> a != b }
        if (hasChanges) {
            currentGameData = data.copy(sectRelations = updatedRelations)
        }
    }

    private fun processAIAlliances(year: Int) {
        // AI sect alliance formation logic
        // 当前版本：AI宗门自动结盟逻辑尚未实现，此方法保留为扩展点。
        // 玩家宗门的盟约管理已由 checkAllianceExpiry / checkAllianceFavorDrop 处理。
    }
}
