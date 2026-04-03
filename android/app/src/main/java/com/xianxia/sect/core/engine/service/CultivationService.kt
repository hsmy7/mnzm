package com.xianxia.sect.core.engine.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.random.Random
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.data.*
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.engine.BattleSystem
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
import com.xianxia.sect.core.engine.AISectAttackManager
import com.xianxia.sect.core.engine.AISectDiscipleManager
import com.xianxia.sect.core.engine.AIBattleWinner
import com.xianxia.sect.core.engine.DiscipleStatCalculator
import android.util.Log
import com.xianxia.sect.core.util.BuildingNames

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
    private val addEvent: (String, EventType) -> Unit,
    private val transactionMutex: Any
) {
    companion object {
        private const val TAG = "CultivationService"
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
    fun updateRealtimeCultivation(currentTimeMillis: Long) {
        synchronized(transactionMutex) {
            val data = _gameData.value

            val currentHfd = _highFrequencyData.value
            val lastUpdateTime = currentHfd.lastUpdateTime

            if (lastUpdateTime <= 0) {
                _highFrequencyData.value = currentHfd.copy(
                    lastUpdateTime = currentTimeMillis,
                    lastCultivationTime = currentTimeMillis
                )
                return@synchronized
            }

            val elapsedMillis = currentTimeMillis - lastUpdateTime
            if (elapsedMillis < 1000) return@synchronized // Update every second

            val elapsedSeconds = elapsedMillis / 1000.0
            val gameSpeed = 1.0

            val idleDisciples = _disciples.value.filter { it.isAlive && (it.status == DiscipleStatus.IDLE || it.status == DiscipleStatus.REFLECTING) }
            
            if (idleDisciples.isEmpty()) {
                _highFrequencyData.value = currentHfd.copy(lastUpdateTime = currentTimeMillis)
                return@synchronized
            }
            
            val cultivationUpdates = mutableMapOf<String, Double>()
            val gainMap = mutableMapOf<String, Double>()
            
            idleDisciples.forEach { disciple ->
                val cultivationPerSecond = calculateDiscipleCultivationPerSecond(disciple, data)
                val adjustedCultivationPerSecond = cultivationPerSecond * gameSpeed
                val gainedCultivation = adjustedCultivationPerSecond * elapsedSeconds
                
                val newCultivation = disciple.cultivation + gainedCultivation
                
                gainMap[disciple.id] = gainedCultivation
                cultivationUpdates[disciple.id] = newCultivation
            }

            val updatedDisciples = _disciples.value.map { disciple ->
                gainMap[disciple.id]?.let { gain ->
                    val newCultivation = (disciple.cultivation + gain).coerceIn(0.0, Double.MAX_VALUE)
                    disciple.copy(cultivation = newCultivation)
                } ?: disciple
            }

            _disciples.value = updatedDisciples
            
            val totalCultivationPerSecond = idleDisciples.sumOf { disciple ->
                calculateDiscipleCultivationPerSecond(disciple, data)
            }

            val shouldCheckBreakthrough = (currentTimeMillis - currentHfd.lastBreakthroughCheckTime) >= 5000

            _highFrequencyData.value = currentHfd.copy(
                lastUpdateTime = currentTimeMillis,
                cultivationPerSecond = totalCultivationPerSecond * gameSpeed,
                totalDisciples = idleDisciples.size,
                lastBreakthroughCheckTime = if (shouldCheckBreakthrough) currentTimeMillis else currentHfd.lastBreakthroughCheckTime,
                cultivationUpdates = cultivationUpdates,
                realtimeCultivation = cultivationUpdates
            )

            if (shouldCheckBreakthrough) {
                processRealtimeBreakthroughs(updatedDisciples.filter { it.isAlive && (it.status == DiscipleStatus.IDLE || it.status == DiscipleStatus.REFLECTING) }, data)
            }
        }
    }

    /**
     * Calculate cultivation gain per second for a disciple
     */
    private fun calculateDiscipleCultivationPerSecond(disciple: Disciple, data: GameData): Double {
        val buildingBonus = calculateBuildingCultivationBonus(disciple, data)

        val (preachingElderBonus, preachingMastersBonus) = calculateWenDaoPeakBonuses(disciple, data)

        return DiscipleStatCalculator.calculateCultivationSpeed(
            disciple = disciple,
            buildingBonus = buildingBonus,
            preachingElderBonus = preachingElderBonus,
            preachingMastersBonus = preachingMastersBonus
        ).coerceIn(0.1, 1000.0)
    }

    private fun calculateWenDaoPeakBonuses(disciple: Disciple, data: GameData): Pair<Double, Double> {
        if (disciple.discipleType != "outer") return 0.0 to 0.0

        val elderSlots = data.elderSlots
        val allDisciples = _disciples.value.associateBy { it.id }

        var preachingElderBonus = 0.0
        var preachingMastersBonus = 0.0

        val preachingElderId = elderSlots.preachingElder
        if (preachingElderId != null) {
            val elder = allDisciples[preachingElderId]
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
            if (disciple.realm < master.realm) continue
            val teaching = master.skills.teaching
            if (teaching >= 80) {
                preachingMastersBonus += (teaching - 80) * 0.005
            }
        }

        return preachingElderBonus to preachingMastersBonus
    }

    private fun calculateOuterElderBreakthroughBonus(disciple: Disciple, data: GameData): Double {
        if (disciple.discipleType != "outer") return 0.0

        val elderSlots = data.elderSlots
        val outerElderId = elderSlots.outerElder ?: return 0.0
        val allDisciples = _disciples.value.associateBy { it.id }

        val elder = allDisciples[outerElderId] ?: return 0.0
        if (disciple.realm < elder.realm) return 0.0

        val comprehension = elder.skills.comprehension
        return if (comprehension >= 80) {
            (comprehension - 80) * 0.01
        } else {
            0.0
        }
    }

    /**
     * Process realtime breakthrough checks
     *
     * Uses maxCultivation as threshold (consistent with Disciple.canBreakthrough()).
     * When cultivation >= maxCultivation, auto attempt breakthrough.
     */
    private fun processRealtimeBreakthroughs(idleDisciples: List<Disciple>, data: GameData) {
        val candidates = idleDisciples.filter { disciple ->
            disciple.realm > 0 && disciple.cultivation >= disciple.maxCultivation
        }

        if (candidates.isEmpty()) return

        val updatedDisciples = _disciples.value.map { disciple ->
            val candidate = candidates.find { it.id == disciple.id } ?: return@map disciple
            if (candidate.cultivation < candidate.maxCultivation || candidate.realm <= 0) return@map disciple

            var newCultivation = disciple.cultivation
            var newRealm = disciple.realm
            var newRealmLayer = disciple.realmLayer

            while (newCultivation >= disciple.maxCultivation && newRealm > 0) {
                val success = tryBreakthrough(disciple)
                if (success) {
                    newCultivation = 0.0
                    if (newRealmLayer < 9) {
                        newRealmLayer++
                    } else {
                        newRealm--
                        newRealmLayer = 1
                    }
                } else {
                    newCultivation = 0.0
                    break
                }
            }

            disciple.copy(
                cultivation = newCultivation,
                realm = newRealm,
                realmLayer = newRealmLayer
            )
        }

        _disciples.value = updatedDisciples
    }

    /**
     * Advance game time by one day
     */
    suspend fun advanceDay() {
        synchronized(transactionMutex) {
            val data = _gameData.value
            var newDay = data.gameDay + 1
            var newMonth = data.gameMonth
            var newYear = data.gameYear

            if (newDay > 30) {
                newDay = 1
                newMonth++
                if (newMonth > 12) {
                    newMonth = 1
                    newYear++
                }
            }

            _gameData.value = data.copy(
                gameDay = newDay,
                gameMonth = newMonth,
                gameYear = newYear
            )

            // Process daily events
            processDailyEvents(newDay, newMonth, newYear)
        }
    }

    /**
     * Advance game time by one month
     */
    suspend fun advanceMonth() {
        synchronized(transactionMutex) {
            val data = _gameData.value
            var newMonth = data.gameMonth + 1
            var newYear = data.gameYear

            if (newMonth > 12) {
                newMonth = 1
                newYear++
            }

            _gameData.value = data.copy(
                gameMonth = newMonth,
                gameYear = newYear
            )

            // Process monthly events
            processMonthlyEvents(newYear, newMonth)
        }
    }

    /**
     * Advance game time by one year
     */
    suspend fun advanceYear() {
        synchronized(transactionMutex) {
            val data = _gameData.value
            val newYear = data.gameYear + 1

            _gameData.value = data.copy(gameYear = newYear)

            // Process yearly events
            processYearlyEvents(newYear)
        }
    }

    /**
     * Process daily events
     */
    private fun processDailyEvents(day: Int, month: Int, year: Int) {
        // Update exploration teams movement
        updateExplorationTeamsMovement(day, month, year)

        // Update cave exploration teams movement
        updateCaveExplorationTeamsMovement(day, month, year)

        // Process AI battle team movement
        processAIBattleTeamMovement()

        // Check exploration arrivals
        checkExplorationArrivals()

        // Check cave exploration arrivals
        checkCaveExplorationArrivals()
    }

    /**
     * Process monthly events - core game logic
     */
    private fun processMonthlyEvents(year: Int, month: Int) {
        val data = _gameData.value

        // 1. Process disciple aging and natural death
        processDiscipleAging(year)

        // 2. Process building production
        processBuildingProduction(year, month)

        // 4. Process herb garden growth
        processHerbGardenGrowth(month)

        // 5. Process spirit mine production
        processSpiritMineProduction()

        // 6. 藏经阁加成已改为实时计算（calculateBuildingCultivationBonus），无需月度处理

        // 7. Process salary payment
        processSalaryPayment(year, month)

        // 8. Process exploration teams in dungeons
        processExplorationInProgress()

        // 9. Process cave exploration
        processCaveLifecycle(year, month)

        // 10. Process AI sect operations
        processAISectOperations(year, month)

        // 11. Process scout info expiry
        processScoutInfoExpiry(year, month)

        // 12. Process diplomacy events
        processDiplomacyMonthlyEvents(year, month)

        // 13. Process outer tournament (every 3 years)
        if (month == 1) {
            processOuterTournament(year)
        }

        // 14. Refresh merchant inventory
        if (month % 3 == 0) {
            refreshTravelingMerchant(year, month)
        }
    }

    /**
     * Process yearly events
     */
    private fun processYearlyEvents(year: Int) {
        // 1. Process yearly aging effects
        processYearlyAging(year)

        // 2. Refresh recruit list (每年一月刷新招募弟子列表)
        refreshRecruitList(year)

        // 3. Process cross-sect partner matching
        processCrossSectPartnerMatching(year, 1)

        // 4. Generate items for AI sects
        generateYearlyItemsForAISects()

        // 5. Process alliance expiry
        checkAllianceExpiry(year)

        // 6. Process alliance favor drop
        checkAllianceFavorDrop()

        // 7. Process AI alliances
        processAIAlliances(year)

        // 8. Process reflection cliff release (思过崖期满释放)
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
            val agedDisciple = disciple.copy(age = disciple.age + 1)

            // Check for natural death (old age)
            val maxAge = getMaxAgeForRealm(disciple.realm)
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
     * Get max age for realm
     */
    private fun getMaxAgeForRealm(realm: Int): Int {
        return when (realm) {
            0 -> 10000   // 仙人: 万年
            1 -> 5000    // 渡劫: 五千年
            2 -> 3000    // 大乘: 三千年
            3 -> 1500    // 合体: 一千五百年
            4 -> 800     // 炼虚: 八百年
            5 -> 500     // 化神: 五百年
            6 -> 300     // 元婴: 三百年
            7 -> 200     // 金丹: 二百年
            8 -> 150     // 筑基: 一百五十年
            9 -> 120     // 炼气: 一百二十年
            else -> 100
        }
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

        val innerElderId = elderSlots.innerElder
        val innerElderComprehension = if (innerElderId != null && disciple.discipleType == "inner") {
            val elder = allDisciples[innerElderId]
            if (elder != null && elder.isAlive && disciple.realm <= elder.realm) {
                elder.skills.comprehension
            } else {
                0
            }
        } else {
            0
        }

        val outerElderComprehensionBonus = calculateOuterElderBreakthroughBonus(disciple, data)

        val chance = DiscipleStatCalculator.getBreakthroughChance(
            disciple = disciple,
            innerElderComprehension = innerElderComprehension,
            outerElderComprehensionBonus = outerElderComprehensionBonus
        )
        val success = Random.nextDouble() < chance

        val realmName = GameConfig.Realm.getName(disciple.realm)
        if (success) {
            val nextLayer = if (disciple.realmLayer < 9) disciple.realmLayer + 1 else 1
            val targetRealm = if (disciple.realmLayer < 9) disciple.realm else disciple.realm - 1
            val targetRealmName = GameConfig.Realm.getName(targetRealm)
            addEvent("恭喜！${disciple.name}成功突破至${targetRealmName}${nextLayer}层！", EventType.SUCCESS)
        } else {
            addEvent("${disciple.name}尝试${realmName}${disciple.realmLayer}层突破失败", EventType.WARNING)
        }

        return success
    }

    /**
     * Process building production (forge, alchemy, herb garden)
     */
    private fun processBuildingProduction(year: Int, month: Int) {
        val data = _gameData.value

        data.forgeSlots.forEach { slot ->
            if (slot.status == SlotStatus.WORKING) {
                if (isBuildingWorkComplete(slot, year, month)) {
                    completeBuildingWork(slot)
                }
            }
        }
    }

    /**
     * Check if building work is complete
     */
    private fun isBuildingWorkComplete(slot: BuildingSlot, year: Int, month: Int): Boolean {
        val elapsedMonths = (year - slot.startYear) * 12 + (month - slot.startMonth)
        return elapsedMonths >= slot.duration
    }

    /**
     * Complete building work
     */
    private fun completeBuildingWork(slot: BuildingSlot) {
        // Mark as completed
        val data = _gameData.value
        val updatedSlots = data.forgeSlots.map {
            if (it.id == slot.id) it.copy(status = SlotStatus.COMPLETED) else it
        }
        _gameData.value = data.copy(forgeSlots = updatedSlots)

        addEvent("${getBuildingName(slot.buildingId)}工作已完成", EventType.INFO)
    }

    /**
     * Process herb garden growth
     */
    private fun processHerbGardenGrowth(month: Int) {
        val data = _gameData.value
        val events = mutableListOf<Pair<String, String>>()
        val mutableHerbs = _herbs.value.toMutableList()

        val updatedPlantSlots = data.herbGardenPlantSlots.map { slot ->
            if (slot.status == "growing" && slot.isFinished(data.gameYear, month)) {
                val seedId = slot.seedId
                if (seedId != null) {
                    val herbId = HerbDatabase.getHerbIdFromSeedId(seedId)
                    if (herbId != null) {
                        val herb = HerbDatabase.getHerbById(herbId)
                        if (herb != null) {
                            val actualYield = HerbGardenSystem.calculateIncreasedYield(slot.harvestAmount, 0.0)
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
                slot.copy(status = "mature")
            } else {
                slot
            }
        }

        _gameData.value = data.copy(herbGardenPlantSlots = updatedPlantSlots)
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
        var totalSpiritStones = 0

        data.spiritMineSlots.forEach { slot ->
            if (slot.discipleId != null) {
                // Base production per active miner
                val baseProduction = 10
                totalSpiritStones += baseProduction
            }
        }

        if (totalSpiritStones > 0) {
            _gameData.value = data.copy(
                spiritStones = data.spiritStones + totalSpiritStones
            )
        }
    }

    /**
     * Process salary payment
     */
    private fun processSalaryPayment(year: Int, month: Int) {
        val data = _gameData.value
        var totalSalary = 0

        _disciples.value.filter { it.isAlive }.forEach { disciple ->
            val salary = getDiscipleSalary(disciple)
            if (data.monthlySalaryEnabled[disciple.realm] == true) {
                totalSalary += salary
            }
        }

        if (totalSalary > 0) {
            if (data.spiritStones >= totalSalary) {
                _gameData.value = data.copy(spiritStones = data.spiritStones - totalSalary)
                addEvent("本月发放弟子俸禄共${totalSalary}灵石", EventType.INFO)
            } else {
                addEvent("灵石不足，无法发放俸禄", EventType.WARNING)
            }
        }
    }

    /**
     * Get salary for disciple
     */
    private fun getDiscipleSalary(disciple: Disciple): Int {
        return when (disciple.realm) {
            0 -> 1000   // 仙人
            1 -> 500    // 渡劫
            2 -> 200    // 大乘
            3 -> 100    // 合体
            4 -> 50     // 炼虚
            5 -> 30     // 化神
            6 -> 20     // 元婴
            7 -> 10     // 金丹
            8 -> 5      // 筑基
            9 -> 2      // 炼气
            else -> 1
        }
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
        val progressIncrement = 1.0f / team.duration.toFloat()
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
                val progressIncrement = 1.0f / team.duration.toFloat()
                val newProgress = team.moveProgress + progressIncrement

                if (newProgress >= 1.0f) {
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

        val result = AISectAttackManager.executeAISectBattle(team, defenderSect)

        if (attackerSect != null) {
            val updatedAttackerDisciples = attackerSect.aiDisciples.filter { it.id !in result.deadAttackerIds }
            _gameData.value = _gameData.value.copy(
                worldMapSects = _gameData.value.worldMapSects.map { sect ->
                    if (sect.id == team.attackerSectId) {
                        sect.copy(aiDisciples = updatedAttackerDisciples)
                    } else {
                        sect
                    }
                }
            )
        }

        val updatedDefenderDisciples = defenderSect.aiDisciples.filter { it.id !in result.deadDefenderIds }
        _gameData.value = _gameData.value.copy(
            worldMapSects = _gameData.value.worldMapSects.map { sect ->
                if (sect.id == team.defenderSectId) {
                    sect.copy(aiDisciples = updatedDefenderDisciples)
                } else {
                    sect
                }
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
            val updatedAttackerDisciples = attackerSect.aiDisciples.filter { it.id !in result.deadAttackerIds }
            _gameData.value = _gameData.value.copy(
                worldMapSects = _gameData.value.worldMapSects.map { sect ->
                    if (sect.id == team.attackerSectId) {
                        sect.copy(aiDisciples = updatedAttackerDisciples)
                    } else {
                        sect
                    }
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
            spiritStones = data.spiritStones - lootResult.lostSpiritStones
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
    private fun checkExplorationArrivals() {
        val arrivedTeams = _teams.value.filter {
            it.status == ExplorationStatus.EXPLORING
        }

        arrivedTeams.forEach { team ->
            // Start dungeon exploration
            startDungeonExploration(team)
        }
    }

    /**
     * Start dungeon exploration for arrived team
     */
    private fun startDungeonExploration(team: ExplorationTeam) {
        val data = _gameData.value
        val dungeon = DungeonDatabase.getDungeon(team.dungeon) ?: return

        // Create battle configuration
        val teamMembers = team.memberIds.mapNotNull { id ->
            _disciples.value.find { it.id == id }
        }.filter { it.isAlive }

        if (teamMembers.isEmpty()) {
            addEvent("探索队伍【${team.name}】全员阵亡或失联", EventType.DANGER)
            completeExploration(team, false, emptyList())
            return
        }

        // Execute battle using BattleSystem
        val equipmentMap = _equipment.value.associateBy { it.id }
        val manualMap = _manuals.value.associateBy { it.id }
        val allProficiencies = data.manualProficiencies.mapValues { (_, list) ->
            list.associateBy { it.manualId }
        }

        val battle = battleSystem.createBattle(
            disciples = teamMembers,
            equipmentMap = equipmentMap,
            manualMap = manualMap,
            beastLevel = dungeon.realm,
            manualProficiencies = allProficiencies
        )

        val battleResult = battleSystem.executeBattle(battle)

        // Clear battle pill effects
        clearBattlePillEffects(teamMembers)

        // Process battle result
        if (battleResult.victory) {
            applyBattleVictoryBonuses(
                memberIds = battleResult.log.teamMembers.filter { it.isAlive }.map { it.id },
                addSoulPower = true
            )
            addEvent("探索队伍【${team.name}】在${dungeon.name}探索成功", EventType.SUCCESS)
            completeExploration(team, true, battleResult.log.teamMembers.filter { it.isAlive }.map { it.id })
        } else {
            addEvent("探索队伍【${team.name}】在${dungeon.name}探索失败", EventType.DANGER)
            completeExploration(team, false, battleResult.log.teamMembers.filter { it.isAlive }.map { it.id })
        }

        // Record battle log - convert BattleLogData to BattleLog
        val battleLog = BattleLog(
            timestamp = System.currentTimeMillis(),
            year = data.gameYear,
            month = data.gameMonth,
            type = BattleType.PVE,
            attackerName = team.name,
            defenderName = dungeon.name,
            result = if (battleResult.victory) BattleResult.WIN else BattleResult.LOSE,
            details = "副本探索",
            dungeonName = dungeon.name,
            teamId = team.id,
            teamMembers = battleResult.log.teamMembers.map { member ->
                BattleLogMember(
                    id = member.id,
                    name = member.name,
                    realm = member.realm,
                    realmName = member.realmName,
                    hp = member.hp,
                    maxHp = member.maxHp,
                    isAlive = member.isAlive
                )
            },
            enemies = battleResult.log.enemies.map { enemy ->
                BattleLogEnemy(
                    id = "",
                    name = enemy.name,
                    realm = enemy.realm,
                    realmName = enemy.realmName,
                    hp = 0,
                    maxHp = 0,
                    isAlive = false
                )
            },
            turns = battleResult.turnCount
        )
        _battleLogs.value = listOf(battleLog) + _battleLogs.value.take(49)
    }

    /**
     * Complete exploration and reset team members
     */
    private fun completeExploration(team: ExplorationTeam, success: Boolean, survivorIds: List<String>) {
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
                            d.copy(status = DiscipleStatus.IDLE)
                        } else {
                            d.copy(isAlive = false, status = DiscipleStatus.IDLE)
                        }
                    } else d
                }
            }
        }
    }

    /**
     * Check cave exploration arrivals
     */
    private fun checkCaveExplorationArrivals() {
        val data = _gameData.value
        val arrivedTeams = data.caveExplorationTeams.filter {
            it.status == CaveExplorationStatus.TRAVELING &&
            it.moveProgress >= 1.0f
        }

        arrivedTeams.forEach { team ->
            val updatedTeams = data.caveExplorationTeams.map {
                if (it.id == team.id) it.copy(status = CaveExplorationStatus.EXPLORING) else it
            }
            _gameData.value = data.copy(caveExplorationTeams = updatedTeams)

            addEvent("探索队伍抵达${team.caveName}，开始探索", EventType.INFO)
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

                val aiTeam = AICaveTeamGenerator.generateAITeam(cave, nearbySects, existingTeamForCave)
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
        // This would contain the full cave exploration logic
        // For now, return a simple completion
        return Triple(
            _gameData.value.cultivatorCaves.filter { it.id != cave.id },
            currentAITeams.filter { it.caveId != cave.id },
            true
        )
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
            if (disciple != null && disciple.status == DiscipleStatus.EXPLORING) {
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

        // Process AI disciple cultivation
        var updatedSects = data.worldMapSects.map { sect ->
            AISectDiscipleManager.processMonthlyCultivation(sect)
        }
        updatedSects = updatedSects.map { sect ->
            AISectDiscipleManager.processManualMasteryGrowth(sect)
        }
        updatedSects = updatedSects.map { sect ->
            AISectDiscipleManager.processEquipmentNurture(sect)
        }
        _gameData.value = data.copy(worldMapSects = updatedSects)

        // Process AI disciple recruitment
        processSectDisciplesRecruitment(year)

        // Process AI disciple aging
        processSectDisciplesAging(year)

        // Process AI attack decisions
        processAISectAttackDecisions()
    }

    /**
     * Process AI sect disciple recruitment
     */
    private fun processSectDisciplesRecruitment(year: Int) {
        val data = _gameData.value
        val updatedSects = data.worldMapSects.map { sect ->
            AISectDiscipleManager.recruitDisciplesForSect(sect, year)
        }
        _gameData.value = data.copy(worldMapSects = updatedSects)
    }

    /**
     * Process AI sect disciple aging
     */
    private fun processSectDisciplesAging(year: Int) {
        val data = _gameData.value
        val updatedSects = data.worldMapSects.map { sect ->
            AISectDiscipleManager.processAging(sect)
        }
        _gameData.value = data.copy(worldMapSects = updatedSects)
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
                compareByDescending<Disciple> { it.realm }
                    .thenByDescending { it.realmLayer }
                    .thenByDescending { it.cultivation }
                    .thenByDescending { it.comprehension }
            )

            val promotionCount = minOf(20, sortedDisciples.size)
            val promotedDisciples = sortedDisciples.take(promotionCount)
            val promotedIds = promotedDisciples.map { it.id }.toSet()

            _disciples.value = _disciples.value.map { disciple ->
                if (disciple.id in promotedIds) {
                    disciple.copy(discipleType = "inner")
                } else {
                    disciple
                }
            }

            val promotedNames = promotedDisciples.take(5).map { it.name }
            val namesDisplay = if (promotedDisciples.size <= 5) {
                promotedNames.joinToString("、")
            } else {
                "${promotedNames.joinToString("、")} 等${promotedDisciples.size}人"
            }

            addEvent("第${year}年外门大比圆满结束，$namesDisplay 晋升为内门弟子", EventType.SUCCESS)

        } catch (e: Exception) {
            Log.e(TAG, "Error in processOuterTournament", e)
            addEvent("第${year}年外门大比处理出错", EventType.DANGER)
        }
    }

    /**
     * Helper methods that will be implemented by delegating to other services or kept here
     */

    private fun calculateBuildingCultivationBonus(disciple: Disciple, data: GameData): Double {
        val inLibrary = data.librarySlots.any { it.discipleId == disciple.id }
        var bonus = if (inLibrary) 1.5 else 1.0

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

        // 清理锻造槽位
        val updatedForgeSlots = data.forgeSlots.filter { it.discipleId != discipleId }

        // 清理灵矿槽位
        val updatedSpiritMineSlots = data.spiritMineSlots.map {
            if (it.discipleId == discipleId) it.copy(discipleId = null, discipleName = "") else it
        }

        // 清理藏经阁槽位
        val updatedLibrarySlots = data.librarySlots.map {
            if (it.discipleId == discipleId) it.copy(discipleId = null, discipleName = "") else it
        }

        // 清理长老槽位
        val updatedElderSlots = data.elderSlots.let { slots ->
            var updated = slots

            // 清理单例职位
            if (updated.viceSectMaster == discipleId) updated = updated.copy(viceSectMaster = null)
            if (updated.herbGardenElder == discipleId) updated = updated.copy(herbGardenElder = null)
            if (updated.alchemyElder == discipleId) updated = updated.copy(alchemyElder = null)
            if (updated.forgeElder == discipleId) updated = updated.copy(forgeElder = null)
            if (updated.outerElder == discipleId) updated = updated.copy(outerElder = null)
            if (updated.preachingElder == discipleId) updated = updated.copy(preachingElder = null)
            if (updated.lawEnforcementElder == discipleId) updated = updated.copy(lawEnforcementElder = null)
            if (updated.innerElder == discipleId) updated = updated.copy(innerElder = null)
            if (updated.qingyunPreachingElder == discipleId) updated = updated.copy(qingyunPreachingElder = null)

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
                spiritMineDeaconDisciples = updated.spiritMineDeaconDisciples.mapNotNull { slot ->
                    if (slot.discipleId == discipleId) DirectDiscipleSlot(index = slot.index) else slot
                }
            )

            updated
        }

        _gameData.value = data.copy(
            forgeSlots = updatedForgeSlots,
            spiritMineSlots = updatedSpiritMineSlots,
            librarySlots = updatedLibrarySlots,
            elderSlots = updatedElderSlots
        )
    }

    private fun removeEquipmentFromDisciple(discipleId: String, equipmentId: String) {
        // Would be delegated to InventoryService
    }

    private fun clearBattlePillEffects(teamMembers: List<Disciple>) {
        // Clear temporary buffs from pills used in battle
    }

    private fun applyBattleVictoryBonuses(memberIds: List<String>, addSoulPower: Boolean) {
        // Apply experience, stat gains, etc.
    }

    private fun getBuildingName(buildingId: String): String = BuildingNames.getDisplayName(buildingId)

    private fun processExplorationInProgress() {
        // Process ongoing explorations
    }

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
                if (sectScoutInfo == null && sect.scoutInfo != null) {
                    sect.copy(
                        scoutInfo = null,
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
    }

    private fun refreshTravelingMerchant(year: Int, month: Int) {
        val itemNames = listOf(
            "铁剑" to "equipment", "铜盾" to "equipment", "布甲" to "equipment",
            "基础剑法" to "manual", "吐纳术" to "manual", "炼体诀" to "manual",
            "回气丹" to "pill", "疗伤丹" to "pill", "聚灵丹" to "pill",
            "铁矿" to "material", "灵木" to "material", "兽骨" to "material",
            "灵草种子" to "seed", "药草" to "herb"
        )
        val itemCount = Random.nextInt(6, 12)
        val newItems = mutableListOf<MerchantItem>()
        repeat(itemCount) {
            val (name, type) = itemNames.random()
            val rarity = when (Random.nextInt(100)) {
                in 0..4 -> 5
                in 5..14 -> 4
                in 15..34 -> 3
                in 35..64 -> 2
                else -> 1
            }
            val basePrice = when (rarity) {
                5 -> Random.nextInt(2000, 5001)
                4 -> Random.nextInt(800, 2001)
                3 -> Random.nextInt(300, 801)
                2 -> Random.nextInt(100, 301)
                else -> Random.nextInt(20, 101)
            }
            val quantity = if (type == "pill" || type == "material" || type == "seed" || type == "herb") Random.nextInt(1, 6) else 1
            newItems.add(MerchantItem(
                id = java.util.UUID.randomUUID().toString(),
                name = name,
                type = type,
                itemId = java.util.UUID.randomUUID().toString(),
                rarity = rarity,
                price = basePrice,
                quantity = quantity,
                obtainedYear = year,
                obtainedMonth = month
            ))
        }
        val data = _gameData.value
        _gameData.value = data.copy(
            travelingMerchantItems = newItems,
            merchantLastRefreshYear = year,
            merchantRefreshCount = data.merchantRefreshCount + 1
        )
    }

    private fun refreshRecruitList(year: Int) {
        val surnames = listOf("李", "张", "王", "刘", "陈", "杨", "赵", "黄", "周", "吴")
        val maleNames = listOf("逍遥", "无忌", "长生", "问道", "清风", "明月", "玄真", "道尘", "天行", "子墨")
        val femaleNames = listOf("月华", "紫烟", "灵芸", "清音", "玉瑶", "雪晴", "碧云", "青鸾", "婉儿", "若兮")
        val spiritRootTypes = listOf("metal", "wood", "water", "fire", "earth")
        val spiritRootNames = mapOf(
            "metal" to "金灵根", "wood" to "木灵根", "water" to "水灵根",
            "fire" to "火灵根", "earth" to "土灵根"
        )
        val recruitCount = Random.nextInt(3, 7)
        val newRecruits = mutableListOf<Disciple>()
        repeat(recruitCount) {
            val gender = if (Random.nextBoolean()) "male" else "female"
            val surname = surnames.random()
            val name = if (gender == "male") maleNames.random() else femaleNames.random()
            val spiritRootType = spiritRootTypes.random()
            val hpVariance = Random.nextInt(-50, 51)
            val mpVariance = Random.nextInt(-50, 51)
            val physicalAttackVariance = Random.nextInt(-50, 51)
            val magicAttackVariance = Random.nextInt(-50, 51)
            val physicalDefenseVariance = Random.nextInt(-50, 51)
            val magicDefenseVariance = Random.nextInt(-50, 51)
            val speedVariance = Random.nextInt(-50, 51)
            val disciple = Disciple(
                id = java.util.UUID.randomUUID().toString(),
                name = "$surname$name",
                gender = gender,
                age = Random.nextInt(16, 30),
                realm = 9,
                realmLayer = Random.nextInt(1, 10),
                spiritRootType = spiritRootType,
                status = DiscipleStatus.IDLE,
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
                    intelligence = Random.nextInt(30, 81),
                    charm = Random.nextInt(30, 81),
                    loyalty = Random.nextInt(70, 101),
                    morality = Random.nextInt(30, 80)
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
            newRecruits.add(disciple)
        }
        _gameData.value = _gameData.value.copy(recruitList = newRecruits, lastRecruitYear = year)
    }

    private fun processYearlyAging(year: Int) {
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
    }

    private fun checkAllianceExpiry(year: Int) {
        val data = _gameData.value
        val expiredAlliances = data.alliances.filter { year - it.startYear >= GameConfig.Diplomacy.ALLIANCE_DURATION_YEARS }

        if (expiredAlliances.isEmpty()) return

        val updatedAlliances = data.alliances.filter { year - it.startYear < GameConfig.Diplomacy.ALLIANCE_DURATION_YEARS }
        val updatedSects = data.worldMapSects.map { sect ->
            if (expiredAlliances.any { it.sectIds.contains(sect.id) }) {
                sect.copy(allianceId = null, allianceStartYear = 0)
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
                    sect.copy(allianceId = null, allianceStartYear = 0)
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
    }
}
