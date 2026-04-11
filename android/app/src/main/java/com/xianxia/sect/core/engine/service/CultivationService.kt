@file:Suppress("DEPRECATION")

package com.xianxia.sect.core.engine.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.data.*
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.engine.BattleSystem
import com.xianxia.sect.core.engine.BattleMemberData
import com.xianxia.sect.core.engine.production.ProductionCoordinator
import com.xianxia.sect.core.engine.HerbGardenSystem
import com.xianxia.sect.core.data.HerbDatabase
import com.xianxia.sect.core.model.Herb
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
import com.xianxia.sect.core.engine.EquipmentNurtureSystem
import com.xianxia.sect.core.engine.ManualProficiencySystem
import com.xianxia.sect.core.engine.MissionSystem
import android.util.Log
import com.xianxia.sect.core.util.BuildingNames
import com.xianxia.sect.core.util.GameRandom

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

/**
 * 修炼服务 - 负责时间推进、修炼系统和核心游戏循环
 *
 * 职责域：
 * - 时间推进 (advanceDay/Month/Year)
 * - 实时修炼状态管理 (HighFrequencyData)
 * - 弟子修炼进度计算
 * - 境界突破检测
 * - 每月/每年事件处理
 * - AI宗门时间推进
 * - 洞府生命周期管理
 */
class CultivationService(
    private val _gameData: MutableStateFlow<GameData>,
    private val _disciples: MutableStateFlow<List<Disciple>>,
    private val _equipment: MutableStateFlow<List<Equipment>>,
    private val _manuals: MutableStateFlow<List<Manual>>,
    private val _pills: MutableStateFlow<List<Pill>>,
    private val _materials: MutableStateFlow<List<Material>>,
    private val _herbs: MutableStateFlow<List<Herb>>,
    private val _seeds: MutableStateFlow<List<Seed>>,
    private val _events: MutableStateFlow<List<GameEvent>>,
    private val _battleLogs: MutableStateFlow<List<BattleLog>>,
    private val _teams: MutableStateFlow<List<ExplorationTeam>>,
    private val inventorySystem: InventorySystem,
    private val battleSystem: BattleSystem,
    private val productionCoordinator: ProductionCoordinator,
    private val productionSlotRepository: com.xianxia.sect.core.repository.ProductionSlotRepository,
    private val addEvent: (String, EventType) -> Unit,
    private val transactionMutex: Mutex,
    private val discipleSystem: com.xianxia.sect.core.engine.system.DiscipleSystem? = null
) {
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

    // HighFrequencyData for realtime cultivation
    private var _highFrequencyData = MutableStateFlow(HighFrequencyData())

    /**
     * Get high frequency data StateFlow
     */
    fun getHighFrequencyData(): StateFlow<HighFrequencyData> = _highFrequencyData

    fun resetHighFrequencyData() {
        _highFrequencyData.value = HighFrequencyData()
    }

    /**
     * Update realtime cultivation data
     */
    suspend fun updateRealtimeCultivation(currentTimeMillis: Long) {
        transactionMutex.withLock {
            val data = _gameData.value

            val currentHfd = _highFrequencyData.value
            val lastUpdateTime = currentHfd.lastUpdateTime

            if (lastUpdateTime <= 0) {
                _highFrequencyData.value = currentHfd.copy(
                    lastUpdateTime = currentTimeMillis,
                    lastCultivationTime = currentTimeMillis
                )
                return@withLock
            }

            val elapsedMillis = currentTimeMillis - lastUpdateTime
            if (elapsedMillis < 1000) return@withLock // Update every second

            val elapsedSeconds = elapsedMillis / 1000.0
            val gameSpeed = 1.0

            val idleDisciples = _disciples.value.filter { it.isAlive && it.status != DiscipleStatus.IN_TEAM }
            
            if (idleDisciples.isEmpty()) {
                _highFrequencyData.value = currentHfd.copy(lastUpdateTime = currentTimeMillis)
                return@withLock
            }
            
            val cultivationUpdates = mutableMapOf<String, Double>()
            val gainMap = mutableMapOf<String, Double>()
            
            val equipmentMap = _equipment.value.associateBy { it.id }
            val manualMap = _manuals.value.associateBy { it.id }

            idleDisciples.forEach { disciple ->
                val cultivationPerSecond = calculateDiscipleCultivationPerSecond(disciple, data)
                val adjustedCultivationPerSecond = cultivationPerSecond * gameSpeed
                val gainedCultivation = adjustedCultivationPerSecond * elapsedSeconds
                
                val newCultivation = disciple.cultivation + gainedCultivation
                
                gainMap[disciple.id] = gainedCultivation
                cultivationUpdates[disciple.id] = newCultivation
            }

            var updatedManualProficiencies = data.manualProficiencies.toMutableMap()
            val updatedDisciples = _disciples.value.map { disciple ->
                gainMap[disciple.id]?.let { gain ->
                    var d = disciple.copy(cultivation = (disciple.cultivation + gain).coerceIn(0.0, Double.MAX_VALUE))

                    // 藏经阁加成：仅对 librarySlots（3个槽位）中的弟子生效，加法比例形式
                    val inLibrary = data.librarySlots.any { it.discipleId == disciple.id }
                    val libraryBonus = if (inLibrary) ManualProficiencySystem.LIBRARY_PROFICIENCY_BONUS_RATE else 0.0
                    val baseProficiencyRate = if (data.sectPolicies.manualResearch) 6.0 else 5.0
                    val proficiencyGain = baseProficiencyRate * (1.0 + libraryBonus) * elapsedSeconds
                    d.manualIds.forEach { manualId ->
                        val manual = manualMap[manualId] ?: return@forEach
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
                    var eqSet = d.equipment

                    d.weaponId?.let { eqId ->
                        val eq = equipmentMap[eqId] ?: return@let
                        val nurture = eqSet.weaponNurture?.takeIf { it.equipmentId == eqId }
                            ?: EquipmentNurtureData(equipmentId = eqId, rarity = eq.rarity)
                        val result = EquipmentNurtureSystem.updateNurtureExp(
                            eq.copy(nurtureLevel = nurture.nurtureLevel, nurtureProgress = nurture.nurtureProgress),
                            nurtureGain
                        )
                        eqSet = eqSet.copy(weaponNurture = EquipmentNurtureData(
                            equipmentId = eqId, rarity = eq.rarity,
                            nurtureLevel = result.equipment.nurtureLevel,
                            nurtureProgress = result.equipment.nurtureProgress
                        ))
                    }
                    d.armorId?.let { eqId ->
                        val eq = equipmentMap[eqId] ?: return@let
                        val nurture = eqSet.armorNurture?.takeIf { it.equipmentId == eqId }
                            ?: EquipmentNurtureData(equipmentId = eqId, rarity = eq.rarity)
                        val result = EquipmentNurtureSystem.updateNurtureExp(
                            eq.copy(nurtureLevel = nurture.nurtureLevel, nurtureProgress = nurture.nurtureProgress),
                            nurtureGain
                        )
                        eqSet = eqSet.copy(armorNurture = EquipmentNurtureData(
                            equipmentId = eqId, rarity = eq.rarity,
                            nurtureLevel = result.equipment.nurtureLevel,
                            nurtureProgress = result.equipment.nurtureProgress
                        ))
                    }
                    d.bootsId?.let { eqId ->
                        val eq = equipmentMap[eqId] ?: return@let
                        val nurture = eqSet.bootsNurture?.takeIf { it.equipmentId == eqId }
                            ?: EquipmentNurtureData(equipmentId = eqId, rarity = eq.rarity)
                        val result = EquipmentNurtureSystem.updateNurtureExp(
                            eq.copy(nurtureLevel = nurture.nurtureLevel, nurtureProgress = nurture.nurtureProgress),
                            nurtureGain
                        )
                        eqSet = eqSet.copy(bootsNurture = EquipmentNurtureData(
                            equipmentId = eqId, rarity = eq.rarity,
                            nurtureLevel = result.equipment.nurtureLevel,
                            nurtureProgress = result.equipment.nurtureProgress
                        ))
                    }
                    d.accessoryId?.let { eqId ->
                        val eq = equipmentMap[eqId] ?: return@let
                        val nurture = eqSet.accessoryNurture?.takeIf { it.equipmentId == eqId }
                            ?: EquipmentNurtureData(equipmentId = eqId, rarity = eq.rarity)
                        val result = EquipmentNurtureSystem.updateNurtureExp(
                            eq.copy(nurtureLevel = nurture.nurtureLevel, nurtureProgress = nurture.nurtureProgress),
                            nurtureGain
                        )
                        eqSet = eqSet.copy(accessoryNurture = EquipmentNurtureData(
                            equipmentId = eqId, rarity = eq.rarity,
                            nurtureLevel = result.equipment.nurtureLevel,
                            nurtureProgress = result.equipment.nurtureProgress
                        ))
                    }

                    d.copy(equipment = eqSet)
                } ?: disciple
            }

            _disciples.value = updatedDisciples
            if (updatedManualProficiencies != data.manualProficiencies) {
                _gameData.value = data.copy(manualProficiencies = updatedManualProficiencies)
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

            processRealtimeBreakthroughs(_disciples.value.filter { it.isAlive && it.status != DiscipleStatus.IN_TEAM }, data)
        }
    }

    /**
     * 子嗣产出系统 - 每天处理一次
     *
     * 条件：女性弟子、有道侣(partnerId!=null)、道侣存活、未处于冷却期
     * 概率：每天 0.08%
     * 冷却：lastChildYear 距今不到1年则跳过
     */
    private fun processChildBirth(currentYear: Int) {
        val allDisciples = _disciples.value
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
                _disciples.value = updatedList.map {
                    if (it.id == mother.id) it.copyWith(lastChildYear = currentYear) else it
                }

                val genderText = if (child.gender == "male") "子" else "女"
                addEvent("${mother.name} 诞下一${genderText} ${child.name}", EventType.SUCCESS)

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

        // 名字：父母姓氏组合
        val motherSurname = mother.name.firstOrNull()?.toString() ?: ""
        val fatherSurname = father.name.firstOrNull()?.toString() ?: ""
        val maleNames = listOf("逍遥", "无忌", "长生", "问道", "清风", "明月", "玄真", "道尘")
        val femaleNames = listOf("月华", "紫烟", "灵芸", "清音", "玉瑶", "雪晴", "碧云", "青鸾")
        val givenName = if (gender == "male") maleNames[GameRandom.nextInt(maleNames.size)] else femaleNames[GameRandom.nextInt(femaleNames.size)]
        val childName = "$fatherSurname$motherSurname$givenName"

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
            name = childName,
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
            baseHp = baseStats.baseHp
            baseMp = baseStats.baseMp
            basePhysicalAttack = baseStats.basePhysicalAttack
            baseMagicAttack = baseStats.baseMagicAttack
            basePhysicalDefense = baseStats.basePhysicalDefense
            baseMagicDefense = baseStats.baseMagicDefense
            baseSpeed = baseStats.baseSpeed
        }

        return disciple
    }

    /**
     * Calculate cultivation gain per second for a disciple
     */
    private fun calculateDiscipleCultivationPerSecond(disciple: Disciple, data: GameData): Double {
        val buildingBonus = calculateBuildingCultivationBonus(disciple, data)

        val (preachingElderBonus, preachingMastersBonus) = calculateWenDaoPeakBonuses(disciple, data)

        var cultivationSubsidyBonus = 0.0
        if (data.sectPolicies.cultivationSubsidy && disciple.realm > 5) {
            cultivationSubsidyBonus = GameConfig.PolicyConfig.CULTIVATION_SUBSIDY_BASE_EFFECT
        }

        return DiscipleStatCalculator.calculateCultivationSpeed(
            disciple = disciple,
            buildingBonus = buildingBonus,
            preachingElderBonus = preachingElderBonus,
            preachingMastersBonus = preachingMastersBonus,
            cultivationSubsidyBonus = cultivationSubsidyBonus
        ).coerceIn(1.0, 1000.0)
    }

    private fun calculateWenDaoPeakBonuses(disciple: Disciple, data: GameData): Pair<Double, Double> {
        if (disciple.discipleType != "outer") return 0.0 to 0.0

        val elderSlots = data.elderSlots
        val allDisciples = _disciples.value.associateBy { it.id }

        var preachingElderBonus = 0.0
        var preachingMastersBonus = 0.0

        val preachingElderId = elderSlots.preachingElder
        if (preachingElderId.isNotEmpty()) {
            val elder = allDisciples[preachingElderId]
            // realm越小境界越高，disciple.realm >= elder.realm 表示弟子境界不高于长老，长老才能指导
            if (elder != null && elder.isAlive && disciple.realm >= elder.realm) {
                val teaching = elder.skills.teaching
                if (teaching >= 80) {
                    preachingElderBonus = (teaching - 80) * 0.01
                }
            }
        }

        for (slot in elderSlots.preachingMasters) {
            val masterId = slot.discipleId ?: continue
            val master = allDisciples[masterId] ?: continue
            if (!slot.isActive || !master.isAlive) continue
            // realm越小境界越高，disciple.realm < master.realm 表示弟子境界高于师傅，跳过
            if (disciple.realm < master.realm) continue
            val teaching = master.skills.teaching
            if (teaching >= 80) {
                preachingMastersBonus += (teaching - 80) * 0.005
            }
        }

        return preachingElderBonus to preachingMastersBonus
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
        val updatedDisciples = _disciples.value.map { disciple ->
            if (disciple.id !in candidateIds) return@map disciple
            if (disciple.cultivation < disciple.maxCultivation || disciple.realm <= 0) return@map disciple

            var newCultivation = disciple.cultivation
            var newRealm = disciple.realm
            var newRealmLayer = disciple.realmLayer
            var newBreakthroughFailCount = disciple.breakthroughFailCount
            var newLifespan = disciple.lifespan
            var shouldContinue = true

            while (shouldContinue && newRealm > 0) {
                val currentMaxCultivation = if (newRealm == 0) Double.MAX_VALUE else {
                    val base = GameConfig.Realm.get(newRealm).cultivationBase
                    base * (1.0 + (newRealmLayer - 1) * 0.2)
                }
                if (newCultivation < currentMaxCultivation) break

                val isMajorBreakthrough = newRealmLayer >= GameConfig.Realm.get(newRealm).maxLayers
                if (isMajorBreakthrough && !DiscipleStatCalculator.meetsSoulPowerRequirement(newRealm, newRealmLayer, disciple.soulPower)) {
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

        _disciples.value = updatedDisciples
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
    suspend fun advanceDay() {
        transactionMutex.withLock {
            val data = _gameData.value
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

            _gameData.value = data.copy(
                gameDay = newDay,
                gameMonth = newMonth,
                gameYear = newYear
            )

            processDailyEvents(newDay, newMonth, newYear)

            if (isYearChanged) {
                processYearlyEvents(newYear)
            }

            if (monthChanged) {
                processMonthlyEvents(newYear, newMonth)
            }
        }
    }

    /**
     * Advance game time by one month
     */
    suspend fun advanceMonth() {
        transactionMutex.withLock {
            val data = _gameData.value
            var newMonth = data.gameMonth + 1
            var newYear = data.gameYear

            if (newMonth > 12) {
                newMonth = 1
                newYear++
            }

            val isYearChanged = newYear > data.gameYear

            _gameData.value = data.copy(
                gameMonth = newMonth,
                gameYear = newYear,
                gameDay = 1
            )

            if (isYearChanged) {
                processYearlyEvents(newYear)
            }

            processMonthlyEvents(newYear, newMonth)
        }
    }

    /**
     * Advance game time by one year
     */
    suspend fun advanceYear() {
        transactionMutex.withLock {
            val data = _gameData.value
            val newYear = data.gameYear + 1

            _gameData.value = data.copy(
                gameYear = newYear,
                gameMonth = 1,
                gameDay = 1
            )

            processYearlyEvents(newYear)

            processMonthlyEvents(newYear, 1)
        }
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

        // Check exploration arrivals
        checkExplorationArrivals()

        // Process child birth (子嗣产出)
        processChildBirth(year)

        // Daily recovery: disciples recover 1% HP and MP (except those in battle)
        processDailyRecovery()
    }

    private fun processDailyRecovery() {
        val inBattleIds = _teams.value
            .filter { it.status == ExplorationStatus.EXPLORING }
            .flatMap { it.memberIds }
            .toSet() +
            _gameData.value.caveExplorationTeams
                .filter { it.status == CaveExplorationStatus.EXPLORING }
                .flatMap { it.memberIds }
                .toSet()

        _disciples.value = _disciples.value.map { disciple ->
            if (!disciple.isAlive || disciple.id in inBattleIds) return@map disciple

            val maxHp = disciple.baseHp
            val maxMp = disciple.baseMp
            val currentHp = disciple.currentHp
            val currentMp = disciple.currentMp

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
        val data = _gameData.value

        // 0. 月初政策费用检测（优先于所有其他月度事件，确保政策状态在本月生效前确定）
        processPolicyCosts()

        // 1. Process building production
        processBuildingProduction(year, month)

        // 2. Process herb garden growth
        processHerbGardenGrowth(month)

        // 3. Process spirit mine production
        processSpiritMineProduction()

        // 4. 藏经阁加成已在 updateRealtimeCultivation() 中实时处理，无需月度处理

        // 5. Process salary payment
        processSalaryPayment(year, month)

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
        val data = _gameData.value
        val currentYear = data.gameYear
        val currentMonth = data.gameMonth
        val completedIds = mutableListOf<String>()
        val remainingActive = mutableListOf<ActiveMission>()

        for (activeMission in data.activeMissions) {
            if (activeMission.isComplete(currentYear, currentMonth)) {
                completedIds.add(activeMission.id)

                val aliveDisciples = activeMission.discipleIds.mapNotNull { did ->
                    _disciples.value.find { it.id == did && it.isAlive }
                }
                val allDead = aliveDisciples.isEmpty()

                if (allDead) {
                    addEvent("任务「${activeMission.missionName}」失败，执行弟子全部阵亡", EventType.WARNING)
                } else {
                    val result = MissionSystem.processMissionCompletion(activeMission, aliveDisciples)
                    if (result.spiritStones > 0) {
                        _gameData.value = _gameData.value.copy(
                            spiritStones = _gameData.value.spiritStones + result.spiritStones.toLong()
                        )
                    }
                    result.materials.forEach { material ->
                        val currentMaterials = _materials.value
                        val existingIndex = currentMaterials.indexOfFirst {
                            it.name == material.name && it.rarity == material.rarity
                        }
                        if (existingIndex >= 0) {
                            val existing = currentMaterials[existingIndex]
                            val updated = existing.copy(quantity = existing.quantity + material.quantity)
                            _materials.value = currentMaterials.toMutableList().also { it[existingIndex] = updated }
                        } else {
                            _materials.value = currentMaterials + material
                        }
                    }
                    addEvent("任务「${activeMission.missionName}」已完成，获得奖励", EventType.SUCCESS)
                }

                for (did in activeMission.discipleIds) {
                    val disciple = _disciples.value.find { it.id == did }
                    if (disciple != null && disciple.isAlive) {
                        _disciples.value = _disciples.value.map {
                            if (it.id == did) it.copy(status = DiscipleStatus.IDLE) else it
                        }
                    }
                }
            } else {
                remainingActive.add(activeMission)
            }
        }

        if (completedIds.isNotEmpty()) {
            _gameData.value = _gameData.value.copy(activeMissions = remainingActive)
        }
    }

    private fun processMissionRefresh() {
        val data = _gameData.value
        val result = MissionSystem.processMonthlyRefresh(
            data.availableMissions,
            data.gameYear,
            data.gameMonth
        )
        _gameData.value = _gameData.value.copy(availableMissions = result.cleanedMissions)
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
    }

    /**
     * Process disciple aging
     */
    private fun processDiscipleAging(currentYear: Int) {
        val data = _gameData.value
        val updatedDisciples = _disciples.value.mapNotNull { disciple ->
            if (!disciple.isAlive) return@mapNotNull disciple

            // Age the disciple
            var agedDisciple = disciple.copy(age = disciple.age + 1)

            // 子嗣成长：年满5岁且 realmLayer==0（未成年）时，自动开启修炼之路
            if (agedDisciple.age == 5 && agedDisciple.realmLayer == 0) {
                agedDisciple = agedDisciple.copyWith(realmLayer = 1, status = DiscipleStatus.IDLE)
                addEvent("${agedDisciple.name} 年满五岁，开启修炼之路（练气一层）", EventType.SUCCESS)
            }

            // Check for natural death (old age)
            val maxAge = maxOf(agedDisciple.lifespan, GameConfig.Realm.get(agedDisciple.realm).maxAge)
            if (agedDisciple.age >= maxAge) {
                addEvent("${agedDisciple.name}因寿元耗尽而坐化，享年${agedDisciple.age}岁", EventType.INFO)
                handleDiscipleDeath(agedDisciple)
                null // Remove dead disciple
            } else {
                agedDisciple
            }
        }

        _disciples.value = updatedDisciples
    }

    /**
     * Handle disciple death
     */
    private fun handleDiscipleDeath(disciple: Disciple) {
        // Clear disciple from all slots
        clearDiscipleFromAllSlots(disciple.id)

        // Remove equipment
        disciple.weaponId?.let { removeEquipmentFromDisciple(disciple.id, it) }
        disciple.armorId?.let { removeEquipmentFromDisciple(disciple.id, it) }
        disciple.bootsId?.let { removeEquipmentFromDisciple(disciple.id, it) }
        disciple.accessoryId?.let { removeEquipmentFromDisciple(disciple.id, it) }
    }

    /**
     * Try breakthrough for a disciple - returns whether it succeeded
     */
    private fun tryBreakthrough(disciple: Disciple): Boolean {
        val data = _gameData.value
        val elderSlots = data.elderSlots
        val allDisciples = _disciples.value.associateBy { it.id }

        val isMajorBreakthrough = disciple.realmLayer >= GameConfig.Realm.get(disciple.realm).maxLayers

        if (isMajorBreakthrough && !DiscipleStatCalculator.meetsSoulPowerRequirement(disciple)) {
            val targetRealm = disciple.realm - 1
            val requiredSoul = GameConfig.Realm.getSoulPowerRequirement(targetRealm)
            addEvent("${disciple.name}神魂不足（${disciple.soulPower}/$requiredSoul），无法突破至${GameConfig.Realm.getName(targetRealm)}", EventType.WARNING)
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
                addEvent("恭喜！${disciple.name}成功突破至${targetRealmName}！", EventType.SUCCESS)
            }
        } else {
            if (isMajorBreakthrough) {
                addEvent("${disciple.name}尝试突破${GameConfig.Realm.getName(disciple.realm - 1)}失败", EventType.WARNING)
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
     */
    private fun processBuildingProduction(year: Int, month: Int) {
        val forgeSlots = productionSlotRepository.getSlotsByBuildingId("forge")
        forgeSlots.forEach { slot ->
            if (slot.isWorking && slot.isFinished(year, month)) {
                kotlinx.coroutines.runBlocking {
                    productionSlotRepository.updateSlotByBuildingId("forge", slot.slotIndex) { s ->
                        s.copy(status = com.xianxia.sect.core.model.production.ProductionSlotStatus.COMPLETED)
                    }
                }
                addEvent("${getBuildingName(slot.buildingId)}工作已完成", EventType.INFO)
            }
        }

        val alchemySlots = productionSlotRepository.getSlotsByType(com.xianxia.sect.core.model.production.BuildingType.ALCHEMY)
        alchemySlots.forEach { slot ->
            if (slot.isWorking && slot.isFinished(year, month)) {
                kotlinx.coroutines.runBlocking {
                    productionSlotRepository.updateSlotByBuildingId("alchemy", slot.slotIndex) { s ->
                        s.copy(status = com.xianxia.sect.core.model.production.ProductionSlotStatus.COMPLETED)
                    }
                }
                addEvent("炼丹工作已完成", EventType.INFO)
            }
        }
    }

    @Deprecated("No longer needed - using ProductionSlotRepository directly")
    private fun isBuildingWorkComplete(slot: BuildingSlot, year: Int, month: Int): Boolean {
        val elapsedMonths = (year - slot.startYear) * 12 + (month - slot.startMonth)
        return elapsedMonths >= slot.duration
    }

    /**
     * Process herb garden growth
     */
    private fun processHerbGardenGrowth(month: Int) {
        val data = _gameData.value
        val events = mutableListOf<Pair<String, String>>()
        val mutableHerbs = _herbs.value.toMutableList()

        val herbGardenSlots = productionSlotRepository.getSlotsByType(com.xianxia.sect.core.model.production.BuildingType.HERB_GARDEN)
        herbGardenSlots.forEach { slot ->
            if (slot.isWorking && slot.isFinished(data.gameYear, month)) {
                val seedId = slot.recipeId
                if (!seedId.isNullOrEmpty()) {
                    val herbId = HerbDatabase.getHerbIdFromSeedId(seedId)
                    if (herbId != null) {
                        val herb = HerbDatabase.getHerbById(herbId)
                        if (herb != null) {
                            val herbGrowthBonus = if (data.sectPolicies.herbCultivation) GameConfig.PolicyConfig.HERB_CULTIVATION_BASE_EFFECT else 0.0
                            val actualYield = HerbGardenSystem.calculateIncreasedYield(slot.harvestAmount, herbGrowthBonus)
                            val existingIndex = mutableHerbs.indexOfFirst { it.name == herb.name && it.rarity == herb.rarity }
                            if (existingIndex >= 0) {
                                mutableHerbs[existingIndex] = mutableHerbs[existingIndex].copy(
                                    quantity = mutableHerbs[existingIndex].quantity + actualYield
                                )
                            } else {
                                mutableHerbs.add(Herb(
                                    id = java.util.UUID.randomUUID().toString(),
                                    name = herb.name,
                                    rarity = herb.rarity,
                                    description = herb.description,
                                    category = herb.category,
                                    quantity = actualYield
                                ))
                            }
                            events.add("${herb.name}已成熟，收获${actualYield}个" to "SUCCESS")
                        }
                    }
                }

                kotlinx.coroutines.runBlocking {
                    productionSlotRepository.updateSlotByBuildingId("herbGarden", slot.slotIndex) { s ->
                        s.copy(status = com.xianxia.sect.core.model.production.ProductionSlotStatus.COMPLETED)
                    }
                }
            }
        }

        if (mutableHerbs != _herbs.value) {
            _herbs.value = mutableHerbs.toList()
        }
        events.forEach { (msg, type) -> addEvent(msg, EventType.valueOf(type)) }
    }

    /**
     * Process spirit mine production
     */
    private fun processSpiritMineProduction() {
        val data = _gameData.value
        val minerCount = data.spiritMineSlots.count { it.discipleId.isNotEmpty() }
        val baseSpiritStones = minerCount * 60

        val boostMultiplier = if (data.sectPolicies.spiritMineBoost) 1.2 else 1.0

        val deaconBonus = data.elderSlots.spiritMineDeaconDisciples.mapNotNull { slot ->
            slot.discipleId?.let { discipleId ->
                _disciples.value.find { it.id == discipleId }
            }
        }.sumOf { disciple ->
            val baseline = 80
            val diff = (disciple.skills.morality - baseline).coerceAtLeast(0)
            diff * 0.01
        }

        val totalSpiritStones = (baseSpiritStones * (1 + deaconBonus) * boostMultiplier).toInt()

        if (totalSpiritStones > 0) {
            _gameData.value = data.copy(
                spiritStones = data.spiritStones + totalSpiritStones
            )
            val bonusParts = buildList {
                if (deaconBonus > 0) add("执事加成+${(deaconBonus * 100).toInt()}%")
                if (boostMultiplier > 1.0) add("政策加成+${((boostMultiplier - 1.0) * 100).toInt()}%")
            }
            val bonusText = if (bonusParts.isNotEmpty()) "（${bonusParts.joinToString("，")}）" else ""
            addEvent("灵矿产出：${minerCount}名矿工本月共产出${totalSpiritStones}灵石$bonusText", EventType.INFO)
        }
    }

    /**
     * Process salary payment
     */
    private fun processSalaryPayment(year: Int, month: Int) {
        val data = _gameData.value
        val salaryConfig = data.monthlySalary
        val enabledConfig = data.monthlySalaryEnabled
        var totalSalary = 0L

        _disciples.value.filter { it.isAlive }.forEach { disciple ->
            val realm = disciple.realm
            if (enabledConfig[realm] == true) {
                val salary = (salaryConfig[realm] ?: 0).toLong()
                totalSalary += salary
            }
        }

        if (totalSalary > 0) {
            if (data.spiritStones >= totalSalary) {
                val discipleUpdates = _disciples.value.map { disciple ->
                    if (!disciple.isAlive || enabledConfig[disciple.realm] != true) return@map disciple

                    val salary = salaryConfig[disciple.realm] ?: 0
                    val newPaidCount = disciple.salaryPaidCount + 1
                    val newLoyalty = if (newPaidCount % 3 == 0) {
                        (disciple.loyalty + 1).coerceIn(0, 100)
                    } else {
                        disciple.loyalty
                    }

                    disciple.copyWith(
                        storageBagSpiritStones = disciple.storageBagSpiritStones + salary.toLong(),
                        salaryPaidCount = newPaidCount,
                        loyalty = newLoyalty
                    )
                }

                _disciples.value = discipleUpdates
                _gameData.value = data.copy(spiritStones = data.spiritStones - totalSalary)
                val paidCount = _disciples.value.count { it.isAlive && enabledConfig[it.realm] == true }
                addEvent("本月发放${paidCount}名弟子俸禄共${totalSalary}灵石", EventType.INFO)
            } else {
                val discipleUpdates = _disciples.value.map { disciple ->
                    if (!disciple.isAlive || enabledConfig[disciple.realm] != true) return@map disciple

                    val newMissedCount = disciple.salaryMissedCount + 1
                    val newLoyalty = if (newMissedCount % 3 == 0) {
                        (disciple.loyalty - 1).coerceIn(0, 100)
                    } else {
                        disciple.loyalty
                    }

                    disciple.copyWith(
                        salaryMissedCount = newMissedCount,
                        loyalty = newLoyalty
                    )
                }

                _disciples.value = discipleUpdates
                addEvent("灵石不足（需${totalSalary}，现有${data.spiritStones}），本月未发放弟子俸禄", EventType.WARNING)
            }
        }
    }

    private fun processPolicyCosts() {
        val data = _gameData.value
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
            _gameData.value = _gameData.value.copy(spiritStones = currentStones)
            addEvent("本月宗门政策维护费共${totalDeducted}灵石（${deductedPolicies.joinToString(", ") { "${it.first}${it.second}"}}）", EventType.INFO)
        }

        if (disabledPolicies.isNotEmpty()) {
            _gameData.value = _gameData.value.copy(sectPolicies = updatedPolicies)
            addEvent("灵石不足！以下政策已自动关闭，本月不生效：${disabledPolicies.joinToString("、")}", EventType.WARNING)
        }
    }

    private fun calculateCaptureRate(): Double {
        val data = _gameData.value
        val elderSlots = data.elderSlots
        val allDisciples = _disciples.value.associateBy { it.id }

        var captureRate = GameConfig.LawEnforcementConfig.BASE_CAPTURE_RATE

        elderSlots.lawEnforcementElder?.let { elderId ->
            if (elderId.isNotEmpty()) {
                allDisciples[elderId]?.let { elder ->
                    val intelligenceAboveBase = (elder.skills.intelligence - GameConfig.LawEnforcementConfig.INTELLIGENCE_BASE).coerceAtLeast(0)
                    captureRate += intelligenceAboveBase * GameConfig.LawEnforcementConfig.ELDER_BONUS_PER_POINT
                }
            }
        }

        elderSlots.lawEnforcementDisciples.forEach { slot ->
            if (slot.discipleId.isNotEmpty()) {
                allDisciples[slot.discipleId]?.let { disciple ->
                    val intelligenceAboveBase = (disciple.skills.intelligence - GameConfig.LawEnforcementConfig.INTELLIGENCE_BASE).coerceAtLeast(0)
                    captureRate += (intelligenceAboveBase / GameConfig.LawEnforcementConfig.DISCIPLE_INTELLIGENCE_STEP) * GameConfig.LawEnforcementConfig.DISCIPLE_BONUS_PER_STEP
                }
            }
        }

        if (data.sectPolicies.enhancedSecurity) {
            captureRate += GameConfig.PolicyConfig.ENHANCED_SECURITY_BASE_EFFECT
        }

        return captureRate.coerceIn(0.0, 1.0)
    }

    private fun processLawEnforcementMonthly() {
        val data = _gameData.value
        val captureRate = calculateCaptureRate()

        val atRiskDisciples = _disciples.value.filter {
            it.isAlive &&
            it.status == DiscipleStatus.IDLE &&
            it.skills.loyalty < GameConfig.LawEnforcementConfig.LOYALTY_THRESHOLD
        }

        for (disciple in atRiskDisciples) {
            val desertionProb = ((GameConfig.LawEnforcementConfig.LOYALTY_THRESHOLD - disciple.skills.loyalty) * GameConfig.LawEnforcementConfig.PROB_PER_POINT).coerceIn(0.0, GameConfig.LawEnforcementConfig.MAX_PROB)
            if (Random.nextDouble() < desertionProb) {
                if (Random.nextDouble() < captureRate) {
                    addEvent("执法堂成功截获试图逃跑的${disciple.name}，已关入思过崖", EventType.INFO)
                    val currentYear = data.gameYear
                    val endYear = currentYear + GameConfig.LawEnforcementConfig.REFLECTION_YEARS
                    _disciples.value = _disciples.value.map {
                        if (it.id == disciple.id) it.copy(
                            status = DiscipleStatus.REFLECTING,
                            statusData = it.statusData + mapOf(
                                "reflectionStartYear" to currentYear.toString(),
                                "reflectionEndYear" to endYear.toString()
                            )
                        ) else it
                    }
                } else {
                    addEvent("${disciple.name}趁乱逃离了宗门！", EventType.DANGER)
                    clearDiscipleFromAllSlots(disciple.id)
                    _disciples.value = _disciples.value.filter { it.id != disciple.id }
                }
            }
        }

        processTheftMonthly()
    }

    private fun processTheftMonthly() {
        if (_gameData.value.spiritStones <= 0) return

        val captureRate = calculateCaptureRate()

        val atRiskDisciples = _disciples.value.filter {
            it.isAlive &&
            it.status == DiscipleStatus.IDLE &&
            it.skills.morality < GameConfig.LawEnforcementConfig.MORALITY_THRESHOLD
        }

        val thiefIds = mutableSetOf<String>()

        for (disciple in atRiskDisciples) {
            val theftProb = ((GameConfig.LawEnforcementConfig.MORALITY_THRESHOLD - disciple.skills.morality) * GameConfig.LawEnforcementConfig.PROB_PER_POINT).coerceIn(0.0, GameConfig.LawEnforcementConfig.MAX_PROB)
            if (Random.nextDouble() < theftProb) {
                if (Random.nextDouble() < captureRate) {
                    addEvent("执法堂成功抓获偷盗中的${disciple.name}，已关入思过崖", EventType.INFO)
                    val currentYear = _gameData.value.gameYear
                    val endYear = currentYear + GameConfig.LawEnforcementConfig.REFLECTION_YEARS
                    _disciples.value = _disciples.value.map {
                        if (it.id == disciple.id) it.copy(
                            status = DiscipleStatus.REFLECTING,
                            statusData = it.statusData + mapOf(
                                "reflectionStartYear" to currentYear.toString(),
                                "reflectionEndYear" to endYear.toString()
                            )
                        ) else it
                    }
                } else {
                    val currentData = _gameData.value
                    if (currentData.spiritStones <= 0) break
                    val stolenAmount = (currentData.spiritStones * Random.nextDouble(GameConfig.LawEnforcementConfig.THEFT_MIN_RATIO, GameConfig.LawEnforcementConfig.THEFT_MAX_RATIO)).toLong().coerceAtLeast(1)
                    _gameData.value = currentData.copy(spiritStones = (currentData.spiritStones - stolenAmount).coerceAtLeast(0))
                    _disciples.value = _disciples.value.map {
                        if (it.id == disciple.id) it.copy(
                            equipment = it.equipment.copy(storageBagSpiritStones = it.equipment.storageBagSpiritStones + stolenAmount)
                        ) else it
                    }
                    thiefIds.add(disciple.id)
                    addEvent("${disciple.name}偷盗了宗门${stolenAmount}灵石并叛离宗门！", EventType.DANGER)
                }
            }
        }

        for (thiefId in thiefIds) {
            clearDiscipleFromAllSlots(thiefId)
        }
        if (thiefIds.isNotEmpty()) {
            _disciples.value = _disciples.value.filter { it.id !in thiefIds }
        }
    }

    /**
     * 道侣匹配系统 - 每月处理一次
     *
     * 条件：存活、年龄>=18、无道侣(partnerId==null)的异性弟子
     * 所有符合条件的异性配对每月均有 0.6% 独立概率结为道侣
     */
    private fun processPartnerMatching() {
        val allDisciples = _disciples.value

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
                    addEvent("${male.name} 与 ${female.name} 结为道侣", EventType.SUCCESS)
                }
            }
        }

        if (pairedFemaleIds.isNotEmpty()) {
            _disciples.value = currentList
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
        val updatedTeams = _teams.value.map { team ->
            if (team.status == ExplorationStatus.TRAVELING) {
                updateTeamMovement(team, day, month, year)
            } else {
                team
            }
        }
        _teams.value = updatedTeams
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
        val data = _gameData.value
        val updatedTeams = data.caveExplorationTeams.map { team ->
            if (team.status == CaveExplorationStatus.TRAVELING) {
                val duration = team.duration.coerceAtLeast(1)
                val progressIncrement = 1.0f / duration.toFloat()
                val newProgress = team.moveProgress + progressIncrement

                if (newProgress >= 1.0f) {
                    addEvent("探索队伍抵达${team.caveName}，开始探索", EventType.INFO)
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
        _gameData.value = data.copy(caveExplorationTeams = updatedTeams)
    }

    /**
     * Process AI battle team movement
     */
    private fun processAIBattleTeamMovement() {
        val data = _gameData.value
        val updatedTeams = data.aiBattleTeams.map { team ->
            if (team.status == "moving") {
                AISectAttackManager.updateAIBattleTeamMovement(team, data)
            } else {
                team
            }
        }

        _gameData.value = data.copy(aiBattleTeams = updatedTeams)

        updatedTeams.filter { AISectAttackManager.isTeamArrived(it) }.forEach { team ->
            triggerAISectBattle(team)
        }
    }

    /**
     * Trigger AI sect battle
     */
    private fun triggerAISectBattle(team: AIBattleTeam) {
        val data = _gameData.value
        val defenderSect = data.worldMapSects.find { it.id == team.defenderSectId } ?: return
        val attackerSect = data.worldMapSects.find { it.id == team.attackerSectId }

        if (team.isPlayerDefender) {
            triggerPlayerSectBattle(team, defenderSect, attackerSect)
            return
        }

        val attackerDisciples = data.aiSectDisciples[team.attackerSectId] ?: emptyList()
        val defenderDisciples = data.aiSectDisciples[team.defenderSectId] ?: emptyList()
        val result = AISectAttackManager.executeAISectBattle(team, defenderSect, defenderDisciples)

        if (attackerSect != null) {
            val updatedAttackerDisciples = attackerDisciples.filter { it.id !in result.deadAttackerIds }
            _gameData.value = _gameData.value.copy(
                aiSectDisciples = _gameData.value.aiSectDisciples.toMutableMap().apply {
                    this[team.attackerSectId] = updatedAttackerDisciples
                }
            )
        }

        val updatedDefenderDisciples = defenderDisciples.filter { it.id !in result.deadDefenderIds }
        _gameData.value = _gameData.value.copy(
            aiSectDisciples = _gameData.value.aiSectDisciples.toMutableMap().apply {
                this[team.defenderSectId] = updatedDefenderDisciples
            }
        )

        if (result.canOccupy) {
            val currentData = _gameData.value
            val defenderWarehouse = currentData.worldMapSects.find { it.id == team.defenderSectId }?.warehouse ?: SectWarehouse()

            _gameData.value = currentData.copy(
                worldMapSects = currentData.worldMapSects.map { sect ->
                    if (sect.id == team.defenderSectId) {
                        sect.copy(
                            occupierSectId = team.attackerSectId,
                            warehouse = SectWarehouse()
                        )
                    } else if (sect.id == team.attackerSectId) {
                        val mergedWarehouse = SectWarehouseManager.addItemsToWarehouse(sect.warehouse, defenderWarehouse.items)
                        val finalWarehouse = SectWarehouseManager.addSpiritStonesToWarehouse(mergedWarehouse, defenderWarehouse.spiritStones)
                        sect.copy(warehouse = finalWarehouse)
                    } else {
                        sect
                    }
                }
            )
            addEvent(AISectAttackManager.generateSectDestroyedEvent(team.attackerSectName, team.defenderSectName), EventType.DANGER)
        }

        val updatedRelations = _gameData.value.sectRelations.map { relation ->
            val isRelevantRelation = (relation.sectId1 == team.attackerSectId && relation.sectId2 == team.defenderSectId) ||
                                     (relation.sectId1 == team.defenderSectId && relation.sectId2 == team.attackerSectId)
            if (isRelevantRelation) {
                relation.copy(favor = (relation.favor - 10).coerceIn(-100, 100))
            } else {
                relation
            }
        }
        _gameData.value = _gameData.value.copy(sectRelations = updatedRelations)

        val aliveDisciples = team.disciples.filter { it.id !in result.deadAttackerIds }
        _gameData.value = _gameData.value.copy(
            aiBattleTeams = _gameData.value.aiBattleTeams.map {
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

    /**
     * Trigger player sect battle (defending)
     */
    private fun triggerPlayerSectBattle(team: AIBattleTeam, playerSect: WorldSect, attackerSect: WorldSect?) {
        val data = _gameData.value
        val playerDefenseTeam = AISectAttackManager.createPlayerDefenseTeam(
            disciples = _disciples.value,
            equipmentMap = _equipment.value.associateBy { it.id },
            manualMap = _manuals.value.associateBy { it.id },
            manualProficiencies = data.manualProficiencies.mapValues { (_, list) ->
                list.associateBy { it.manualId }
            }
        )

        if (playerDefenseTeam.isEmpty()) {
            addEvent("${team.attackerSectName}进攻我宗，但我宗无可用弟子防守！", EventType.DANGER)
            processPlayerDefeat(team, attackerSect)
            return
        }

        val result = AISectAttackManager.executePlayerSectBattle(team, playerDefenseTeam)

        val deadPlayerDiscipleIds = result.deadDefenderIds
        _disciples.value = _disciples.value.map { disciple ->
            if (disciple.id in deadPlayerDiscipleIds) {
                disciple.copy(isAlive = false)
            } else {
                disciple
            }
        }

        if (attackerSect != null) {
            val attackerDisciples = data.aiSectDisciples[team.attackerSectId] ?: emptyList()
            val updatedAttackerDisciples = attackerDisciples.filter { it.id !in result.deadAttackerIds }
            _gameData.value = _gameData.value.copy(
                aiSectDisciples = _gameData.value.aiSectDisciples.toMutableMap().apply {
                    this[team.attackerSectId] = updatedAttackerDisciples
                }
            )
        }

        val updatedPlayerRelations = _gameData.value.sectRelations.map { relation ->
            val isRelevantRelation = (relation.sectId1 == team.attackerSectId && relation.sectId2 == playerSect.id) ||
                                     (relation.sectId1 == playerSect.id && relation.sectId2 == team.attackerSectId)
            if (isRelevantRelation) {
                relation.copy(favor = (relation.favor - 15).coerceIn(0, 100))
            } else {
                relation
            }
        }

        _gameData.value = _gameData.value.copy(sectRelations = updatedPlayerRelations)

        if (result.winner == AIBattleWinner.ATTACKER) {
            processPlayerDefeat(team, attackerSect)
        } else {
            addEvent("我宗成功击退${team.attackerSectName}的进攻！", EventType.SUCCESS)

            val aliveDefenderIds = _disciples.value
                .filter { it.isAlive && it.id !in deadPlayerDiscipleIds }
                .map { it.id }.toSet()
            _disciples.value = _disciples.value.map { disciple ->
                if (disciple.id in aliveDefenderIds) {
                    disciple.copyWith(soulPower = disciple.soulPower + 1)
                } else {
                    disciple
                }
            }

            val aliveDisciples = team.disciples.filter { it.id !in result.deadAttackerIds }
            _gameData.value = _gameData.value.copy(
                aiBattleTeams = _gameData.value.aiBattleTeams.map {
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
    private fun processPlayerDefeat(team: AIBattleTeam, attackerSect: WorldSect?) {
        val data = _gameData.value
        val lootResult = AISectAttackManager.calculatePlayerLootLoss(
            data.spiritStones,
            _materials.value,
            _herbs.value,
            _seeds.value,
            _pills.value
        )

        _gameData.value = data.copy(
            spiritStones = (data.spiritStones - lootResult.lostSpiritStones).coerceAtLeast(0L)
        )

        lootResult.lostMaterials.forEach { (itemName, itemCount) ->
            when {
                _materials.value.any { it.name == itemName } -> {
                    _materials.value = _materials.value.map { material ->
                        if (material.name == itemName && material.quantity > 0) {
                            material.copy(quantity = (material.quantity - itemCount).coerceAtLeast(0))
                        } else material
                    }
                }
                _herbs.value.any { it.name == itemName } -> {
                    _herbs.value = _herbs.value.map { herb ->
                        if (herb.name == itemName && herb.quantity > 0) {
                            herb.copy(quantity = (herb.quantity - itemCount).coerceAtLeast(0))
                        } else herb
                    }
                }
                _seeds.value.any { it.name == itemName } -> {
                    _seeds.value = _seeds.value.map { seed ->
                        if (seed.name == itemName && seed.quantity > 0) {
                            seed.copy(quantity = (seed.quantity - itemCount).coerceAtLeast(0))
                        } else seed
                    }
                }
                _pills.value.any { it.name == itemName } -> {
                    _pills.value = _pills.value.map { pill ->
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
                _materials.value,
                _herbs.value,
                _seeds.value,
                _pills.value
            )
            val updatedWarehouse = SectWarehouseManager.addItemsToWarehouse(attackerSect.warehouse, warehouseItems)
            val finalWarehouse = SectWarehouseManager.addSpiritStonesToWarehouse(updatedWarehouse, lootResult.lostSpiritStones)

            _gameData.value = _gameData.value.copy(
                worldMapSects = _gameData.value.worldMapSects.map { sect ->
                    if (sect.id == attackerSect.id) {
                        sect.copy(warehouse = finalWarehouse)
                    } else {
                        sect
                    }
                }
            )
        }

        addEvent("我宗防守失败，被${team.attackerSectName}掠夺了${lootResult.lostSpiritStones}灵石和${lootResult.lostMaterials.size}种道具！", EventType.DANGER)

        _gameData.value = _gameData.value.copy(
            aiBattleTeams = _gameData.value.aiBattleTeams.map {
                if (it.id == team.id) it.copy(status = "completed") else it
            }
        )
    }

    /**
     * Check exploration arrivals and start exploration
     */
    private suspend fun checkExplorationArrivals() {
        val data = _gameData.value
        val arrivedTeams = _teams.value.filter {
            it.status == ExplorationStatus.EXPLORING &&
            it.arrivalYear == data.gameYear &&
            it.arrivalMonth == data.gameMonth &&
            it.arrivalDay == data.gameDay
        }

        arrivedTeams.forEach { team ->
            val dungeonConfig = GameConfig.Dungeons.get(team.dungeon) ?: return@forEach
            addEvent("探索队伍【${team.name}】已抵达${dungeonConfig.name}，开始探索", EventType.INFO)
        }
    }

    private suspend fun processDungeonMonthlyExploration() {
        val data = _gameData.value
        val exploringTeams = _teams.value.filter {
            it.status == ExplorationStatus.EXPLORING
        }

        for (team in exploringTeams) {
            val dungeonConfig = GameConfig.Dungeons.get(team.dungeon) ?: continue

            val teamMembers = team.memberIds.mapNotNull { id ->
                _disciples.value.find { it.id == id }
            }.filter { it.isAlive }

            if (teamMembers.isEmpty()) {
                addEvent("探索队伍【${team.name}】全员阵亡或失联", EventType.DANGER)
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
                    val existingIndex = _equipment.value.indexOfFirst { it.name == equipment.name && it.rarity == equipment.rarity }
                    if (existingIndex >= 0) {
                        val existing = _equipment.value[existingIndex]
                        _equipment.value = _equipment.value.toMutableList().also { it[existingIndex] = existing.copy(quantity = existing.quantity + 1) }
                    } else {
                        _equipment.value = _equipment.value + equipment.copy(quantity = 1)
                    }
                    rewardCounts[equipment.name] = rewardCounts.getOrDefault(equipment.name, 0) + 1
                }
                "manual" -> {
                    val manual = ManualDatabase.generateRandom(rarity, rarity)
                    val existingIndex = _manuals.value.indexOfFirst { it.name == manual.name && it.rarity == manual.rarity }
                    if (existingIndex >= 0) {
                        val existing = _manuals.value[existingIndex]
                        _manuals.value = _manuals.value.toMutableList().also { it[existingIndex] = existing.copy(quantity = existing.quantity + 1) }
                    } else {
                        _manuals.value = _manuals.value + manual.copy(quantity = 1)
                    }
                    rewardCounts[manual.name] = rewardCounts.getOrDefault(manual.name, 0) + 1
                }
                "pill" -> {
                    val pill = ItemDatabase.generateRandomPill(rarity, rarity)
                    val existingIndex = _pills.value.indexOfFirst { it.name == pill.name && it.rarity == pill.rarity }
                    if (existingIndex >= 0) {
                        val existing = _pills.value[existingIndex]
                        _pills.value = _pills.value.toMutableList().also { it[existingIndex] = existing.copy(quantity = existing.quantity + 1) }
                    } else {
                        _pills.value = _pills.value + pill.copy(quantity = 1)
                    }
                    rewardCounts[pill.name] = rewardCounts.getOrDefault(pill.name, 0) + 1
                }
                "herb" -> {
                    val herbTemplate = HerbDatabase.generateRandomHerb(rarity, rarity)
                    val herb = Herb(name = herbTemplate.name, rarity = herbTemplate.rarity, description = herbTemplate.description, category = herbTemplate.category, quantity = 1)
                    val existingIndex = _herbs.value.indexOfFirst { it.name == herb.name && it.rarity == herb.rarity }
                    if (existingIndex >= 0) {
                        val existing = _herbs.value[existingIndex]
                        val updated = existing.copy(quantity = existing.quantity + 1)
                        _herbs.value = _herbs.value.toMutableList().also { it[existingIndex] = updated }
                    } else {
                        _herbs.value = _herbs.value + herb
                    }
                    rewardCounts[herb.name] = rewardCounts.getOrDefault(herb.name, 0) + 1
                }
                "seed" -> {
                    val seedTemplate = HerbDatabase.generateRandomSeed(rarity, rarity)
                    val seed = Seed(name = seedTemplate.name, rarity = seedTemplate.rarity, description = seedTemplate.description, growTime = seedTemplate.growTime, yield = seedTemplate.yield, quantity = 1)
                    val existingIndex = _seeds.value.indexOfFirst { it.name == seed.name && it.rarity == seed.rarity }
                    if (existingIndex >= 0) {
                        val existing = _seeds.value[existingIndex]
                        val updated = existing.copy(quantity = existing.quantity + 1)
                        _seeds.value = _seeds.value.toMutableList().also { it[existingIndex] = updated }
                    } else {
                        _seeds.value = _seeds.value + seed
                    }
                    rewardCounts[seed.name] = rewardCounts.getOrDefault(seed.name, 0) + 1
                }
                "material" -> {
                    val material = ItemDatabase.generateRandomMaterial(rarity, rarity)
                    val existingIndex = _materials.value.indexOfFirst { it.name == material.name && it.rarity == material.rarity }
                    if (existingIndex >= 0) {
                        val existing = _materials.value[existingIndex]
                        _materials.value = _materials.value.toMutableList().also { it[existingIndex] = existing.copy(quantity = existing.quantity + 1) }
                    } else {
                        _materials.value = _materials.value + material.copy(quantity = 1)
                    }
                    rewardCounts[material.name] = rewardCounts.getOrDefault(material.name, 0) + 1
                }
            }
        }

        val rewardDescriptions = mutableListOf<String>()
        if (spiritStoneAmount > 0) {
            _gameData.value = _gameData.value.copy(
                spiritStones = _gameData.value.spiritStones + spiritStoneAmount
            )
            rewardDescriptions.add("灵石x${spiritStoneAmount}")
        }
        rewardCounts.forEach { (name, count) -> rewardDescriptions.add("${name}x${count}") }

        addEvent("探索队伍【${team.name}】在${dungeonConfig.name}获得宝物：${rewardDescriptions.joinToString("、")}", EventType.SUCCESS)
    }

    private fun processDungeonEmptyResult(team: ExplorationTeam, dungeonConfig: GameConfig.DungeonConfig) {
        addEvent("探索队伍【${team.name}】在${dungeonConfig.name}一无所获", EventType.INFO)
    }

    private suspend fun processDungeonBeastEncounter(
        team: ExplorationTeam,
        dungeonConfig: GameConfig.DungeonConfig,
        teamMembers: List<Disciple>,
        avgRealm: Int
    ) {
        val data = _gameData.value
        val equipmentMap = _equipment.value.associateBy { it.id }
        val manualMap = _manuals.value.associateBy { it.id }
        val allProficiencies = data.manualProficiencies.mapValues { (_, list) ->
            list.associateBy { it.manualId }
        }

        val beastCount = (GameConfig.Battle.MIN_BEAST_COUNT..GameConfig.Battle.MAX_BEAST_COUNT).random()

        val battle = battleSystem.createBattle(
            disciples = teamMembers,
            equipmentMap = equipmentMap,
            manualMap = manualMap,
            beastLevel = avgRealm,
            beastCount = beastCount,
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
            addEvent("探索队伍【${team.name}】在${dungeonConfig.name}遭遇妖兽并战胜", EventType.SUCCESS)
            updateDiscipleHpMpAfterBattle(battleResult.log.teamMembers)
        } else {
            addEvent("探索队伍【${team.name}】在${dungeonConfig.name}遭遇妖兽，战败撤退", EventType.DANGER)
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
        _battleLogs.value = listOf(battleLog) + _battleLogs.value.take(49)
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
                val existingIndex = _materials.value.indexOfFirst { it.name == material.name && it.rarity == material.rarity }
                if (existingIndex >= 0) {
                    val existing = _materials.value[existingIndex]
                    _materials.value = _materials.value.toMutableList().also { it[existingIndex] = existing.copy(quantity = existing.quantity + 1) }
                } else {
                    _materials.value = _materials.value + material
                }
                rewardCounts[beastMaterial.name] = rewardCounts.getOrDefault(beastMaterial.name, 0) + 1
            }
        }

        if (rewardCounts.isNotEmpty()) {
            val rewardDescriptions = rewardCounts.map { (name, count) -> "${name}x${count}" }
            addEvent("获得妖兽材料：${rewardDescriptions.joinToString("、")}", EventType.INFO)
        }
    }

    private suspend fun applyDungeonVictoryBonuses(memberIds: List<String>) {
        val data = _gameData.value
            val equipmentMap = _equipment.value.associateBy { it.id }
            val manualMap = _manuals.value.associateBy { it.id }
            var updatedManualProficiencies = data.manualProficiencies.toMutableMap()
            val discipleUpdates = mutableMapOf<Int, Disciple>()

            memberIds.forEach { memberId ->
                val discipleIndex = _disciples.value.indexOfFirst { it.id == memberId }
                if (discipleIndex < 0) return@forEach

                val disciple = _disciples.value[discipleIndex]
                if (!disciple.isAlive) return@forEach

                val newSoulPower = disciple.soulPower + 1

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
                        "maxHp" -> updatedDisciple.copyWith(baseHp = updatedDisciple.baseHp + 1, statusData = newStatusData)
                        "maxMp" -> updatedDisciple.copyWith(baseMp = updatedDisciple.baseMp + 1, statusData = newStatusData)
                        "physicalAttack" -> updatedDisciple.copyWith(basePhysicalAttack = updatedDisciple.basePhysicalAttack + 1, statusData = newStatusData)
                        "magicAttack" -> updatedDisciple.copyWith(baseMagicAttack = updatedDisciple.baseMagicAttack + 1, statusData = newStatusData)
                        "physicalDefense" -> updatedDisciple.copyWith(basePhysicalDefense = updatedDisciple.basePhysicalDefense + 1, statusData = newStatusData)
                        "magicDefense" -> updatedDisciple.copyWith(baseMagicDefense = updatedDisciple.baseMagicDefense + 1, statusData = newStatusData)
                        "speed" -> updatedDisciple.copyWith(baseSpeed = updatedDisciple.baseSpeed + 1, statusData = newStatusData)
                        else -> updatedDisciple
                    }
                }

                disciple.manualIds.forEach { manualId ->
                    val manual = manualMap[manualId] ?: return@forEach
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

                var eqSet = updatedDisciple.equipment

                updatedDisciple.weaponId?.let { eqId ->
                    val eq = equipmentMap[eqId] ?: return@let
                    val nurture = eqSet.weaponNurture?.takeIf { it.equipmentId == eqId }
                        ?: EquipmentNurtureData(equipmentId = eqId, rarity = eq.rarity)
                    val expRequired = EquipmentNurtureSystem.getExpRequiredForLevelUp(nurture.nurtureLevel, eq.rarity)
                    val result = EquipmentNurtureSystem.updateNurtureExp(
                        eq.copy(nurtureLevel = nurture.nurtureLevel, nurtureProgress = nurture.nurtureProgress),
                        expRequired * 0.03
                    )
                    eqSet = eqSet.copy(weaponNurture = EquipmentNurtureData(
                        equipmentId = eqId, rarity = eq.rarity,
                        nurtureLevel = result.equipment.nurtureLevel,
                        nurtureProgress = result.equipment.nurtureProgress
                    ))
                }
                updatedDisciple.armorId?.let { eqId ->
                    val eq = equipmentMap[eqId] ?: return@let
                    val nurture = eqSet.armorNurture?.takeIf { it.equipmentId == eqId }
                        ?: EquipmentNurtureData(equipmentId = eqId, rarity = eq.rarity)
                    val expRequired = EquipmentNurtureSystem.getExpRequiredForLevelUp(nurture.nurtureLevel, eq.rarity)
                    val result = EquipmentNurtureSystem.updateNurtureExp(
                        eq.copy(nurtureLevel = nurture.nurtureLevel, nurtureProgress = nurture.nurtureProgress),
                        expRequired * 0.03
                    )
                    eqSet = eqSet.copy(armorNurture = EquipmentNurtureData(
                        equipmentId = eqId, rarity = eq.rarity,
                        nurtureLevel = result.equipment.nurtureLevel,
                        nurtureProgress = result.equipment.nurtureProgress
                    ))
                }
                updatedDisciple.bootsId?.let { eqId ->
                    val eq = equipmentMap[eqId] ?: return@let
                    val nurture = eqSet.bootsNurture?.takeIf { it.equipmentId == eqId }
                        ?: EquipmentNurtureData(equipmentId = eqId, rarity = eq.rarity)
                    val expRequired = EquipmentNurtureSystem.getExpRequiredForLevelUp(nurture.nurtureLevel, eq.rarity)
                    val result = EquipmentNurtureSystem.updateNurtureExp(
                        eq.copy(nurtureLevel = nurture.nurtureLevel, nurtureProgress = nurture.nurtureProgress),
                        expRequired * 0.03
                    )
                    eqSet = eqSet.copy(bootsNurture = EquipmentNurtureData(
                        equipmentId = eqId, rarity = eq.rarity,
                        nurtureLevel = result.equipment.nurtureLevel,
                        nurtureProgress = result.equipment.nurtureProgress
                    ))
                }
                updatedDisciple.accessoryId?.let { eqId ->
                    val eq = equipmentMap[eqId] ?: return@let
                    val nurture = eqSet.accessoryNurture?.takeIf { it.equipmentId == eqId }
                        ?: EquipmentNurtureData(equipmentId = eqId, rarity = eq.rarity)
                    val expRequired = EquipmentNurtureSystem.getExpRequiredForLevelUp(nurture.nurtureLevel, eq.rarity)
                    val result = EquipmentNurtureSystem.updateNurtureExp(
                        eq.copy(nurtureLevel = nurture.nurtureLevel, nurtureProgress = nurture.nurtureProgress),
                        expRequired * 0.03
                    )
                    eqSet = eqSet.copy(accessoryNurture = EquipmentNurtureData(
                        equipmentId = eqId, rarity = eq.rarity,
                        nurtureLevel = result.equipment.nurtureLevel,
                        nurtureProgress = result.equipment.nurtureProgress
                    ))
                }

                discipleUpdates[discipleIndex] = updatedDisciple.copy(equipment = eqSet)
            }

            if (discipleUpdates.isNotEmpty()) {
                _disciples.value = _disciples.value.toMutableList().also { list ->
                    discipleUpdates.forEach { (index, d) -> list[index] = d }
                }
            }

            if (updatedManualProficiencies != data.manualProficiencies) {
                _gameData.value = data.copy(manualProficiencies = updatedManualProficiencies)
            }
    }


    private fun updateDiscipleHpMpAfterBattle(battleMembers: List<BattleMemberData>) {
        val survivorIds = battleMembers.filter { it.isAlive }.map { it.id }.toSet()
        team@ for (member in battleMembers) {
            val discipleIndex = _disciples.value.indexOfFirst { it.id == member.id }
            if (discipleIndex < 0) continue@team
            val disciple = _disciples.value[discipleIndex]
            if (!survivorIds.contains(member.id)) {
                _disciples.value = _disciples.value.toMutableList().also { it[discipleIndex] = disciple.copy(isAlive = false) }
            } else {
                val hp = member.hp.coerceAtMost(member.maxHp)
                val mp = member.mp.coerceAtMost(member.maxMp)
                _disciples.value = _disciples.value.toMutableList().also {
                    it[discipleIndex] = disciple.copy(combat = disciple.combat.copy(currentHp = hp, currentMp = mp))
                }
            }
        }
    }

    /**
     * Complete exploration and reset team members
     */
    private fun completeExploration(team: ExplorationTeam, success: Boolean, survivorIds: List<String>, survivorHpMap: Map<String, Int> = emptyMap(), survivorMpMap: Map<String, Int> = emptyMap()) {
        val updatedTeam = team.copy(status = ExplorationStatus.COMPLETED)
        _teams.value = _teams.value.map { if (it.id == team.id) updatedTeam else it }

        // Reset disciple statuses
        team.memberIds.forEach { memberId ->
            val disciple = _disciples.value.find { it.id == memberId }
            if (disciple != null) {
                val shouldKeepAlive = disciple.isAlive && survivorIds.contains(memberId)
                _disciples.value = _disciples.value.map { d ->
                    if (d.id == memberId) {
                        if (shouldKeepAlive) {
                            val hp = survivorHpMap[memberId] ?: d.currentHp
                            val mp = survivorMpMap[memberId] ?: d.currentMp
                            d.copy(status = DiscipleStatus.IDLE, combat = d.combat.copy(currentHp = hp, currentMp = mp))
                        } else {
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
    fun processCaveLifecycle(year: Int, month: Int) {
        val data = _gameData.value

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
                addEvent("洞府${team.caveName}已消失，正在前往的探索队伍返回宗门", EventType.WARNING)
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

                val aiTeam = AICaveTeamGenerator.generateAITeam(cave, nearbySects, existingTeamForCave, _gameData.value.aiSectDisciples)
                if (aiTeam != null) {
                    allAITeams.add(aiTeam)
                }
            }
        }

        var updatedSectsForAI = _gameData.value.worldMapSects.toMutableList()
        val aiTeamsToRemove = mutableListOf<String>()

        allAITeams.filter { it.status == AITeamStatus.EXPLORING }.forEach { aiTeam ->
            val cave = activeCaves.find { it.id == aiTeam.caveId } ?: return@forEach

            if (Random.nextDouble() < 0.3) {
                val rewards = CaveExplorationSystem.generateVictoryRewards(cave)
                val warehouseItems = SectWarehouseManager.convertCaveRewardsToWarehouseItems(rewards)

                val sectIndex = updatedSectsForAI.indexOfFirst { it.id == aiTeam.sectId }
                if (sectIndex >= 0) {
                    val sect = updatedSectsForAI[sectIndex]
                    val updatedWarehouse = SectWarehouseManager.addItemsToWarehouse(sect.warehouse, warehouseItems)
                    val warehouseWithStones = SectWarehouseManager.addSpiritStonesToWarehouse(
                        updatedWarehouse,
                        rewards.items.filter { it.type == "spiritStones" }.sumOf { it.quantity.toLong() }
                    )
                    updatedSectsForAI[sectIndex] = sect.copy(warehouse = warehouseWithStones)
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
            addEvent("探索队伍前往的${team.caveName}已消失，队伍返回宗门", EventType.WARNING)
            resetCaveExplorationTeamMembersStatus(team)
            finalExplorationTeams.removeAll { it.id == team.id }
        }

        teamsWithError.forEach { team ->
            addEvent("探索队伍${team.caveName}发生错误，队伍返回宗门", EventType.WARNING)
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

        val currentData = _gameData.value
        _gameData.value = currentData.copy(
            cultivatorCaves = finalCaves + newCaves,
            aiCaveTeams = finalAITeams,
            caveExplorationTeams = finalExplorationTeams,
            worldMapSects = updatedSectsForAI
        )
    }

    /**
     * Execute cave exploration with battles
     */
    private fun executeCaveExploration(
        team: CaveExplorationTeam,
        cave: CultivatorCave,
        currentAITeams: List<AICaveTeam>
    ): Triple<List<CultivatorCave>, List<AICaveTeam>, Boolean> {
        // 1. 获取队伍中的弟子
        val teamMembers = team.memberIds.mapNotNull { id ->
            _disciples.value.find { it.id == id }
        }.filter { it.isAlive }

        if (teamMembers.isEmpty()) {
            addEvent("探索队伍【${team.caveName}】全员阵亡或失联", EventType.DANGER)
            // 失败：保留洞府，移除AI队伍，返回失败
            return Triple(
                _gameData.value.cultivatorCaves,
                currentAITeams.filter { it.caveId != cave.id },
                true // 队伍已完成（虽然失败），需要从探索列表移除
            )
        }

        val data = _gameData.value
        val equipmentMap = _equipment.value.associateBy { it.id }
        val manualMap = _manuals.value.associateBy { it.id }
        val allProficiencies = data.manualProficiencies.mapValues { (_, list) ->
            list.associateBy { it.manualId }
        }

        // 2. 检查是否有AI队伍在同一洞府
        val aiTeamInCave = currentAITeams.find { it.caveId == cave.id && it.status == AITeamStatus.EXPLORING }

        val battleResult = if (aiTeamInCave != null) {
            // 与AI队伍战斗
            addEvent("探索队伍在${cave.name}遭遇${aiTeamInCave.sectName}的探索队！", EventType.BATTLE)
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
            addEvent("探索队伍开始探索${cave.name}，遭遇守护者！", EventType.BATTLE)
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
        _disciples.value = _disciples.value.map { disciple ->
            if (disciple.id in team.memberIds) {
                if (disciple.id in survivorIds) {
                    val hp = survivorHpMap[disciple.id] ?: disciple.currentHp
                    val mp = survivorMpMap[disciple.id] ?: disciple.currentMp
                    disciple.copy(status = DiscipleStatus.IDLE, combat = disciple.combat.copy(currentHp = hp, currentMp = mp))
                } else {
                    addEvent("${disciple.name}在${cave.name}探索中阵亡", EventType.DANGER)
                    handleDiscipleDeath(disciple)
                    disciple.copy(isAlive = false, status = DiscipleStatus.IDLE)
                }
            } else disciple
        }

        // 4. 根据战斗结果处理
        if (!battleResult.victory) {
            addEvent("探索队伍在${cave.name}探索失败", EventType.DANGER)
            // 失败：保留洞府（可被再次探索），清除相关AI队伍
            var updatedAITeams = currentAITeams.filter { it.caveId != cave.id }
            if (aiTeamInCave != null) {
                // AI队伍胜利，可能获得洞府奖励并离开
                updatedAITeams = updatedAITeams.toMutableList()
            }
            return Triple(
                _gameData.value.cultivatorCaves,
                updatedAITeams,
                true // 探索完成（失败）
            )
        }

        // 5. 胜利：给予奖励
        addEvent("探索队伍成功征服${cave.name}！", EventType.SUCCESS)

        _disciples.value = _disciples.value.map { disciple ->
            if (disciple.id in survivorIds && disciple.isAlive) {
                disciple.copyWith(soulPower = disciple.soulPower + 1)
            } else {
                disciple
            }
        }

        val rewards = CaveExplorationSystem.generateVictoryRewards(cave)

        rewards.items.forEach { reward ->
            when (reward.type) {
                "spiritStones" -> {
                    _gameData.value = _gameData.value.copy(
                        spiritStones = _gameData.value.spiritStones + reward.quantity.toLong()
                    )
                    addEvent("获得灵石 x${reward.quantity}", EventType.INFO)
                }
                "equipment" -> {
                    val template = EquipmentDatabase.getById(reward.itemId)
                    if (template != null) {
                        val equipment = EquipmentDatabase.createFromTemplate(template).copy(
                            rarity = reward.rarity,
                            quantity = reward.quantity
                        )
                        _equipment.value = _equipment.value + equipment
                        addEvent("获得装备 ${template.name} x${reward.quantity}", EventType.SUCCESS)
                    } else {
                        addEvent("警告：无法找到装备模板 ${reward.itemId}，奖励跳过", EventType.WARNING)
                    }
                }
                "manual" -> {
                    val template = ManualDatabase.getById(reward.itemId)
                    if (template != null) {
                        val manual = ManualDatabase.createFromTemplate(template).copy(
                            rarity = reward.rarity,
                            quantity = reward.quantity
                        )
                        _manuals.value = _manuals.value + manual
                        addEvent("获得功法 ${template.name} x${reward.quantity}", EventType.SUCCESS)
                    } else {
                        addEvent("警告：无法找到功法模板 ${reward.itemId}，奖励跳过", EventType.WARNING)
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
                            cultivationSpeed = template.cultivationSpeed,
                            duration = template.effectDuration,
                            cultivationPercent = template.cultivationPercent,
                            skillExpPercent = template.skillExpPercent,
                            extendLife = template.extendLife,
                            physicalAttackPercent = template.physicalAttackPercent,
                            magicAttackPercent = template.magicAttackPercent,
                            physicalDefensePercent = template.physicalDefensePercent,
                            magicDefensePercent = template.magicDefensePercent,
                            hpPercent = template.hpPercent,
                            mpPercent = template.mpPercent,
                            speedPercent = template.speedPercent,
                            healPercent = template.healPercent,
                            healMaxHpPercent = template.healMaxHpPercent,
                            heal = template.heal,
                            battleCount = template.battleCount,
                            mpRecoverMaxMpPercent = template.mpRecoverMaxMpPercent,
                            minRealm = GameConfig.Realm.getMinRealmForRarity(template.rarity)
                        )
                        _pills.value = _pills.value + pill
                        addEvent("获得丹药 ${template.name} x${reward.quantity}", EventType.SUCCESS)
                    } else {
                        addEvent("警告：无法找到丹药配方 ${reward.itemId}，奖励跳过", EventType.WARNING)
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
        _battleLogs.value = listOf(battleLog) + _battleLogs.value.take(49)

        // 6. 返回结果：标记洞府为已探索，移除相关AI队伍
        val updatedCaves = _gameData.value.cultivatorCaves.map { c ->
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
        val data = _gameData.value
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
            val disciple = _disciples.value.find { it.id == memberId }
            if (disciple != null && disciple.status == DiscipleStatus.IN_TEAM) {
                _disciples.value = _disciples.value.map {
                    if (it.id == memberId) it.copy(status = DiscipleStatus.IDLE) else it
                }
            }
        }
    }

    /**
     * Process AI sect operations (cultivation, recruitment, aging)
     */
    private fun processAISectOperations(year: Int, month: Int) {
        val data = _gameData.value
        val aiDisciples = data.aiSectDisciples

        val updatedAiDisciples = aiDisciples.mapValues { (sectId, disciples) ->
            val sect = data.worldMapSects.find { it.id == sectId }
            if (sect == null || sect.isPlayerSect) return@mapValues disciples

            var updated = AISectDiscipleManager.processMonthlyCultivation(disciples)
            updated = AISectDiscipleManager.processManualMasteryGrowth(updated)
            updated = AISectDiscipleManager.processEquipmentNurture(updated)
            updated
        }

        _gameData.value = data.copy(aiSectDisciples = updatedAiDisciples)

        processSectDisciplesRecruitment(year)

        processSectDisciplesAging(year)

        processAISectAttackDecisions()
    }

    private fun processSectDisciplesRecruitment(year: Int) {
        val data = _gameData.value
        val updatedAiDisciples = data.aiSectDisciples.mapValues { (sectId, disciples) ->
            val sect = data.worldMapSects.find { it.id == sectId }
            if (sect == null || sect.isPlayerSect) return@mapValues disciples
            AISectDiscipleManager.recruitDisciples(sect.name, sect.maxRealm, disciples)
        }
        _gameData.value = data.copy(aiSectDisciples = updatedAiDisciples)
    }

    private fun processSectDisciplesAging(year: Int) {
        val data = _gameData.value
        val updatedAiDisciples = data.aiSectDisciples.mapValues { (sectId, disciples) ->
            val sect = data.worldMapSects.find { it.id == sectId }
            if (sect == null || sect.isPlayerSect) return@mapValues disciples
            AISectDiscipleManager.processAging(disciples)
        }
        _gameData.value = data.copy(aiSectDisciples = updatedAiDisciples)
    }

    /**
     * Process AI sect attack decisions
     */
    private fun processAISectAttackDecisions() {
        val data = _gameData.value

        val newBattles = AISectAttackManager.decideAttacks(data)
        val playerAttack = AISectAttackManager.decidePlayerAttack(data)

        val allNewBattles = newBattles + listOfNotNull(playerAttack)

        if (allNewBattles.isNotEmpty()) {
            _gameData.value = data.copy(
                aiBattleTeams = data.aiBattleTeams + allNewBattles
            )
        }
    }

    /**
     * Generate yearly items for AI sects
     */
    private fun generateYearlyItemsForAISects() {
        val data = _gameData.value
        val updatedSects = data.worldMapSects.map { sect ->
            if (!sect.isPlayerSect && !sect.isPlayerOccupied) {
                val newWarehouse = SectWarehouseManager.generateYearlyItemsForAISect(sect)
                val existingWarehouse = sect.warehouse
                val mergedWarehouse = SectWarehouseManager.addItemsToWarehouse(
                    existingWarehouse,
                    newWarehouse.items
                )
                val finalWarehouse = SectWarehouseManager.addSpiritStonesToWarehouse(
                    mergedWarehouse,
                    newWarehouse.spiritStones
                )
                sect.copy(warehouse = finalWarehouse)
            } else {
                sect
            }
        }
        _gameData.value = data.copy(worldMapSects = updatedSects)
    }

    /**
     * Process outer tournament (every 3 years)
     */
    private fun processOuterTournament(year: Int) {
        if (year % 3 != 0) {
            return
        }

        try {
            val outerDisciples = _disciples.value.filter {
                it.isAlive &&
                it.discipleType == "outer" &&
                it.status != DiscipleStatus.REFLECTING
            }

            if (outerDisciples.isEmpty()) {
                addEvent("第${year}年外门大比：无符合条件的弟子参与", EventType.INFO)
                return
            }

            addEvent("第${year}年外门大比开始，共有${outerDisciples.size}名外门弟子参与", EventType.INFO)

            val sortedDisciples = outerDisciples.sortedWith(
                compareBy<Disciple> { it.realm }
                    .thenByDescending { it.realmLayer }
                    .thenByDescending { it.cultivation }
                    .thenByDescending { it.comprehension }
            )

            val topCount = minOf(10, sortedDisciples.size)
            val topDisciples = sortedDisciples.take(topCount)
            val competitionResults = topDisciples.mapIndexed { index, disciple ->
                CompetitionRankResult(discipleId = disciple.id, rank = index + 1)
            }

            _gameData.value = _gameData.value.copy(
                pendingCompetitionResults = competitionResults,
                lastCompetitionYear = year
            )

            val topNames = topDisciples.map { it.name }
            addEvent("第${year}年外门大比排名揭晓：${topNames.joinToString("、")} 进入前十名，请选择晋升弟子", EventType.SUCCESS)

        } catch (e: Exception) {
            Log.e(TAG, "Error in processOuterTournament", e)
            addEvent("第${year}年外门大比处理出错", EventType.DANGER)
        }
    }

    /**
     * Helper methods that will be implemented by delegating to other services or kept here
     */

    private fun calculateBuildingCultivationBonus(disciple: Disciple, data: GameData): Double {
        var bonus = 1.0

        if (disciple.discipleType == "inner") {
            val elderSlots = data.elderSlots
            val allDisciples = _disciples.value.associateBy { it.id }

            val qingyunPreachingElder = elderSlots.qingyunPreachingElder?.let { allDisciples[it] }
            val qingyunPreachingMasters = elderSlots.qingyunPreachingMasters.mapNotNull { slot ->
                slot.discipleId?.let { allDisciples[it] }
            }

            val speedBonus = DiscipleStatCalculator.calculateQingyunPeakCultivationSpeedBonus(
                disciple = disciple,
                qingyunPreachingElder = qingyunPreachingElder,
                qingyunPreachingMasters = qingyunPreachingMasters
            )

            bonus += speedBonus
        }

        return bonus
    }

    private fun clearDiscipleFromAllSlots(discipleId: String) {
        val data = _gameData.value

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

        _gameData.value = data.copy(
            spiritMineSlots = updatedSpiritMineSlots,
            librarySlots = updatedLibrarySlots,
            elderSlots = updatedElderSlots
        )

        kotlinx.coroutines.runBlocking {
            val forgeSlots = productionSlotRepository.getSlotsByBuildingId("forge")
            for (slot in forgeSlots) {
                if (slot.assignedDiscipleId == discipleId && !slot.isWorking) {
                    productionSlotRepository.updateSlotByBuildingId("forge", slot.slotIndex) { s ->
                        s.copy(assignedDiscipleId = null, assignedDiscipleName = "")
                    }
                }
            }
        }
    }

    private fun removeEquipmentFromDisciple(discipleId: String, equipmentId: String) {
        val equipment = _equipment.value.find { it.id == equipmentId } ?: return
        if (!equipment.isEquipped) return

        // 卸载装备：将 isEquipped 设为 false，清除 ownerId
        _equipment.value = _equipment.value.map { eq ->
            if (eq.id == equipmentId) {
                eq.copy(isEquipped = false, ownerId = null)
            } else eq
        }
    }

    private fun getBuildingName(buildingId: String): String = BuildingNames.getDisplayName(buildingId)

    private fun processScoutInfoExpiry(year: Int, month: Int) {
        val data = _gameData.value
        var hasExpired = false

        val updatedScoutInfo = data.scoutInfo.filter { (_, info) ->
            val isExpired = year > info.expiryYear ||
                (year == info.expiryYear && month > info.expiryMonth)
            if (isExpired) {
                hasExpired = true
                addEvent("${info.sectId}的探查信息已过期", EventType.INFO)
            }
            !isExpired
        }

        if (hasExpired) {
            val updatedWorldMapSects = data.worldMapSects.map { sect ->
                val sectScoutInfo = updatedScoutInfo[sect.id]
                if (sectScoutInfo == null && sect.scoutInfo.sectId.isNotEmpty()) {
                    sect.copy(
                        scoutInfo = SectScoutInfo(),
                        isKnown = false,
                        expiryYear = 0,
                        expiryMonth = 0
                    )
                } else {
                    sect
                }
            }

            _gameData.value = data.copy(
                scoutInfo = updatedScoutInfo,
                worldMapSects = updatedWorldMapSects
            )
        }
    }

    private fun processDiplomacyMonthlyEvents(year: Int, month: Int) {
        // Process alliance checks, relation changes, etc.
        // 当前版本：外交月度事件（好感度自然衰减、礼物过期等）尚未实现，
        // 此方法保留为扩展点。盟约到期检查已由 checkAllianceExpiry / checkAllianceFavorDrop 处理。
    }

    internal suspend fun refreshTravelingMerchant(year: Int, month: Int) {
        val (itemPoolByRarity, itemRarityMap, itemPriceMap) = buildMerchantItemPools()

        if (itemPoolByRarity.values.all { it.isEmpty() }) return

        val data = _gameData.value
        val newRefreshCount = data.merchantRefreshCount + 1
        val isPityRefresh = newRefreshCount % MERCHANT_PITY_THRESHOLD == 0

        val newItems = mutableListOf<MerchantItem>()

        if (isPityRefresh) {
            addGuaranteedMythicItem(newItems, itemPoolByRarity, itemPriceMap, year, month, newRefreshCount)
        }

        val remainingCount = TRAVELING_MERCHANT_ITEM_COUNT - newItems.size
        repeat(remainingCount) {
            val selectedRarity = selectRarity()
            val selectedItem = selectItemByRarity(itemPoolByRarity, selectedRarity)
                ?: selectFirstAvailableItem(itemPoolByRarity)

            if (selectedItem != null) {
                newItems.add(createMerchantItem(selectedItem, itemRarityMap, itemPriceMap, year, month))
            }
        }

        _gameData.value = data.copy(
            travelingMerchantItems = newItems,
            merchantLastRefreshYear = year,
            merchantRefreshCount = newRefreshCount
        )
    }

    private fun buildMerchantItemPools(): Triple<MutableMap<Int, MutableList<Pair<String, String>>>, MutableMap<String, Int>, MutableMap<String, Int>> {
        val itemPoolByRarity = mutableMapOf<Int, MutableList<Pair<String, String>>>()
        val itemRarityMap = mutableMapOf<String, Int>()
        val itemPriceMap = mutableMapOf<String, Int>()

        for (rarity in 1..6) {
            itemPoolByRarity[rarity] = mutableListOf()
        }

        EquipmentDatabase.allTemplates.values.forEach { t ->
            itemPoolByRarity.getOrPut(t.rarity) { mutableListOf() }.add(t.name to "equipment")
            itemRarityMap[t.name] = t.rarity
            itemPriceMap[t.name] = t.price
        }

        if (ManualDatabase.isInitialized) {
            ManualDatabase.allManuals.values.forEach { t ->
                itemPoolByRarity.getOrPut(t.rarity) { mutableListOf() }.add(t.name to "manual")
                itemRarityMap[t.name] = t.rarity
                itemPriceMap[t.name] = t.price
            }
        }

        ItemDatabase.allPills.values.forEach { t ->
            itemPoolByRarity.getOrPut(t.rarity) { mutableListOf() }.add(t.name to "pill")
            itemRarityMap[t.name] = t.rarity
            itemPriceMap[t.name] = t.price
        }

        ItemDatabase.allMaterials.values.forEach { t ->
            itemPoolByRarity.getOrPut(t.rarity) { mutableListOf() }.add(t.name to "material")
            itemRarityMap[t.name] = t.rarity
            itemPriceMap[t.name] = t.price
        }

        HerbDatabase.getAllHerbs().forEach { h ->
            itemPoolByRarity.getOrPut(h.rarity) { mutableListOf() }.add(h.name to "herb")
            itemRarityMap[h.name] = h.rarity
            itemPriceMap[h.name] = h.price
        }

        HerbDatabase.getAllSeeds().forEach { s ->
            itemPoolByRarity.getOrPut(s.rarity) { mutableListOf() }.add(s.name to "seed")
            itemRarityMap[s.name] = s.rarity
            itemPriceMap[s.name] = s.price
        }

        return Triple(itemPoolByRarity, itemRarityMap, itemPriceMap)
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

    private fun selectItemByRarity(itemPoolByRarity: Map<Int, List<Pair<String, String>>>, rarity: Int): Pair<String, String>? {
        return itemPoolByRarity[rarity]?.random()
    }

    private fun selectFirstAvailableItem(itemPoolByRarity: Map<Int, List<Pair<String, String>>>): Pair<String, String>? {
        return (1..6).firstNotNullOfOrNull { r -> itemPoolByRarity[r]?.random() }
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

    private fun createMerchantItem(
        item: Pair<String, String>,
        itemRarityMap: Map<String, Int>,
        itemPriceMap: Map<String, Int>,
        year: Int,
        month: Int,
        forcedRarity: Int? = null
    ): MerchantItem {
        val (name, type) = item
        val rarity = forcedRarity ?: itemRarityMap[name] ?: 1
        val basePrice = itemPriceMap[name] ?: 20
        val quantity = calculateMerchantStock(type, rarity)

        return MerchantItem(
            id = java.util.UUID.randomUUID().toString(),
            name = name,
            type = type,
            itemId = java.util.UUID.randomUUID().toString(),
            rarity = rarity,
            price = GameUtils.applyPriceFluctuation(basePrice),
            quantity = quantity,
            obtainedYear = year,
            obtainedMonth = month
        )
    }

    private fun addGuaranteedMythicItem(
        newItems: MutableList<MerchantItem>,
        itemPoolByRarity: Map<Int, List<Pair<String, String>>>,
        itemPriceMap: Map<String, Int>,
        year: Int,
        month: Int,
        refreshCount: Int
    ) {
        val mythicPool = itemPoolByRarity[6]
        if (mythicPool == null || mythicPool.isEmpty()) {
            Log.w(TAG, "商人保底触发但天品物品池为空，跳过保底")
            return
        }

        val mythicItem = mythicPool.random()
        val guaranteedMythicItem = createMerchantItem(mythicItem, emptyMap(), itemPriceMap, year, month, forcedRarity = 6)

        newItems.add(guaranteedMythicItem)

        Log.i(TAG, "商人第${refreshCount}次刷新触发保底，优先添加天品物品：${mythicItem.first}")
    }

    internal suspend fun refreshRecruitList(year: Int) {
        val surnames = listOf(
            "李", "张", "王", "刘", "陈", "杨", "赵", "黄", "周", "吴",
            "孙", "郑", "冯", "蒋", "沈", "韩", "朱", "秦", "许", "何",
            "吕", "施", "曹", "袁", "邓", "彭", "苏", "卢", "蔡", "丁",
            "萧", "叶", "顾", "孟", "林", "徐", "方", "程", "谢", "宋"
        )
        val maleNames = listOf(
            "逍遥", "无忌", "长生", "问道", "清风", "明月",
            "怀瑾", "景行", "承宇", "君浩", "亦尘", "云深",
            "晏清", "知远", "修远", "秉文", "若谷", "临渊",
            "望舒", "归鸿", "寒山", "听雨", "忘机", "抱朴",
            "乐天", "安然", "致远", "明轩", "文渊", "廷玉",
            "浩然", "子轩", "瑾瑜", "星野", "澄之", "衡之"
        )
        val femaleNames = listOf(
            "月华", "紫烟", "灵芸", "清音", "玉瑶", "雪晴",
            "芷若", "沐云", "晓霜", "凌波", "听澜", "念真",
            "疏影", "流萤", "惜音", "惊鸿", "采薇", "青衣",
            "婉清", "静姝", "灵犀", "芳菲", "梵音", "墨染",
            "丹青", "笙箫", "洛神", "湘君", "素问", "兰若",
            "梦璃", "含烟", "弄影", "踏歌", "凝霜", "映雪"
        )
        val spiritRootTypes = listOf("metal", "wood", "water", "fire", "earth")
        val spiritRootNames = mapOf(
            "metal" to "金灵根", "wood" to "木灵根", "water" to "水灵根",
            "fire" to "火灵根", "earth" to "土灵根"
        )
        val recruitCount = Random.nextInt(3, 16)
        val newRecruitDisciples = mutableListOf<Disciple>()
        repeat(recruitCount) {
            val gender = if (Random.nextBoolean()) "male" else "female"
            val surname = surnames.random()
            val name = if (gender == "male") maleNames.random() else femaleNames.random()
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
                name = "$surname$name",
                gender = gender,
                age = Random.nextInt(16, 30),
                realm = 9,
                realmLayer = 1,
                spiritRootType = spiritRootType,
                status = DiscipleStatus.IDLE,
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
                baseHp = baseStats.baseHp
                baseMp = baseStats.baseMp
                basePhysicalAttack = baseStats.basePhysicalAttack
                baseMagicAttack = baseStats.baseMagicAttack
                basePhysicalDefense = baseStats.basePhysicalDefense
                baseMagicDefense = baseStats.baseMagicDefense
                baseSpeed = baseStats.baseSpeed
            }
            newRecruitDisciples.add(disciple)
        }
        _gameData.value = _gameData.value.copy(recruitList = newRecruitDisciples, lastRecruitYear = year)
        if (discipleSystem != null && newRecruitDisciples.isNotEmpty()) {
            discipleSystem.refreshRecruitList(newRecruitDisciples, year)
        }
    }

    private fun processYearlyAging(year: Int) {
        // 当前版本：年度老化效果（寿元额外消耗、境界倒退风险等）尚未实现，
        // 此方法保留为扩展点。月度老化已由 processDiscipleAging 处理。
    }

    private fun processReflectionRelease(year: Int) {
        val reflectingDisciples = _disciples.value.filter { it.status == DiscipleStatus.REFLECTING && it.isAlive }
        if (reflectingDisciples.isEmpty()) return

        val updatedDisciples = _disciples.value.map { disciple ->
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

        _disciples.value = updatedDisciples

        val releasedCount = reflectingDisciples.count { it.statusData["reflectionEndYear"]?.toIntOrNull()?.let { year >= it } == true }
        if (releasedCount > 0) {
            addEvent("思过崖：共$releasedCount 名弟子思过期满，已释放并恢复空闲状态，道德+10", EventType.INFO)
        }
    }

    private fun processCrossSectPartnerMatching(year: Int, month: Int) {
        // Cross-sect marriage matching
        // 当前版本：跨宗门联姻系统尚未实现，此方法保留为扩展点。
    }

    private fun checkAllianceExpiry(year: Int) {
        val data = _gameData.value
        val expiredAlliances = data.alliances.filter { year - it.startYear >= GameConfig.Diplomacy.ALLIANCE_DURATION_YEARS }

        if (expiredAlliances.isEmpty()) return

        val updatedAlliances = data.alliances.filter { year - it.startYear < GameConfig.Diplomacy.ALLIANCE_DURATION_YEARS }
        val updatedSects = data.worldMapSects.map { sect ->
            if (expiredAlliances.any { it.sectIds.contains(sect.id) }) {
                sect.copy(allianceId = "", allianceStartYear = 0)
            } else sect
        }

        _gameData.value = data.copy(
            alliances = updatedAlliances,
            worldMapSects = updatedSects
        )

        expiredAlliances.forEach { alliance ->
            val sect = data.worldMapSects.find { it.id != "player" && alliance.sectIds.contains(it.id) }
            if (sect != null) {
                addEvent("与${sect.name}的盟约已到期自动解散", EventType.INFO)
            }
        }
    }

    private fun checkAllianceFavorDrop() {
        val data = _gameData.value
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
                    addEvent("与${sect?.name ?: "宗门"}的好感度过低，盟约自动解除", EventType.WARNING)
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
            _gameData.value = data.copy(
                alliances = updatedAlliances,
                worldMapSects = updatedSects
            )
        }
    }

    private fun processAIAlliances(year: Int) {
        // AI sect alliance formation logic
        // 当前版本：AI宗门自动结盟逻辑尚未实现，此方法保留为扩展点。
        // 玩家宗门的盟约管理已由 checkAllianceExpiry / checkAllianceFavorDrop 处理。
    }
}
