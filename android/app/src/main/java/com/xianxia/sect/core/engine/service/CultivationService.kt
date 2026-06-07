package com.xianxia.sect.core.engine.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.random.Random
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.BattleResultUIData
import com.xianxia.sect.core.state.GameNotification
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.registry.*
import com.xianxia.sect.core.engine.system.AddResult
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.engine.domain.battle.BattleSystem
import com.xianxia.sect.core.engine.domain.battle.BattleMemberData
import com.xianxia.sect.core.engine.domain.disciple.DiscipleSlotCleanup
import com.xianxia.sect.core.engine.domain.production.ProductionCoordinator
import com.xianxia.sect.core.engine.domain.building.HerbGardenSystem
import com.xianxia.sect.core.engine.domain.building.HerbGardenAuraService
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.engine.domain.exploration.CaveExplorationSystem
import com.xianxia.sect.core.engine.SectWarehouseManager
import com.xianxia.sect.core.util.PortraitPool
import com.xianxia.sect.core.engine.WorldMapGenerator
import com.xianxia.sect.core.engine.domain.exploration.CaveGenerator
import com.xianxia.sect.core.engine.domain.exploration.LevelGenerator
import com.xianxia.sect.core.engine.domain.exploration.MSTEdge
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.core.engine.domain.battle.AISectAttackManager
import com.xianxia.sect.core.engine.domain.diplomacy.AISectDiscipleManager
import com.xianxia.sect.core.engine.domain.battle.AISectGarrisonManager
import com.xianxia.sect.core.engine.domain.battle.AIBattleWinner
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.domain.disciple.DiscipleEquipmentManager
import com.xianxia.sect.core.engine.domain.disciple.DiscipleManualManager
import com.xianxia.sect.core.engine.domain.disciple.DisciplePillManager
import com.xianxia.sect.core.engine.domain.disciple.DiscipleService
import com.xianxia.sect.core.engine.EquipmentNurtureSystem
import com.xianxia.sect.core.engine.ManualProficiencySystem
import com.xianxia.sect.core.engine.domain.exploration.MissionSystem
import com.xianxia.sect.core.repository.ProductionSlotRepository
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.config.DiplomaticEventConfig
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.di.ApplicationScopeProvider
import android.util.Log
import com.xianxia.sect.core.util.BuildingNames
import com.xianxia.sect.core.util.GameRandom
import com.xianxia.sect.core.util.SpiritRootGenerator
import com.xianxia.sect.core.util.NameService
import com.xianxia.sect.core.engine.annotation.GameService
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
    val realtimeCultivation: Map<String, Double>? = null,
    val proficiencyUpdates: Map<String, Map<String, Double>> = emptyMap(), // discipleId -> (manualId -> gained)
    val nurtureUpdates: Map<String, Map<String, Double>> = emptyMap()      // discipleId -> (equipmentId -> gained)
)

@GameService("CultivationService")
@Singleton
class CultivationService @Inject constructor(
    private val stateStore: GameStateStore,
    private val inventorySystem: InventorySystem,
    private val inventoryConfig: InventoryConfig,
    private val battleSystem: BattleSystem,
    private val productionCoordinator: ProductionCoordinator,
    private val productionSlotRepository: ProductionSlotRepository,
private val applicationScopeProvider: ApplicationScopeProvider,
    private val discipleService: DiscipleService,
    private val thermalMonitor: com.xianxia.sect.core.perf.ThermalMonitor,
    private val gameClock: com.xianxia.sect.core.engine.system.GameTimeClock
) {
    private val scope get() = applicationScopeProvider.scope

    // 外交事件计数器：每月最多触发 2 次随机外交事件
    private var diplomacyEventsThisMonth = 0
    private var diplomacyEventsMonth = 0

    // 薪水年度结算：记录每个弟子上次结算的游戏年份
    private val lastSalaryYear = mutableMapOf<String, Int>()

    // 自动装备/自动学习脏标记：仅储物袋有物品或装备/功法变更时检测
    private val autoEquipDirty = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val autoLearnDirty = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun markAutoEquipDirty(discipleId: String) { autoEquipDirty.add(discipleId) }
    fun markAutoLearnDirty(discipleId: String) { autoLearnDirty.add(discipleId) }

    /** 修炼/孕养/熟练度速率缓存：SettlementCoordinator 每月结算后更新 */
    @Volatile var cachedCultivationRates: Map<String, Double> = emptyMap()
    @Volatile var cachedNurtureRates: Map<String, Double> = emptyMap()  // equipmentId -> nurturePerSecond
    @Volatile var cachedProficiencyRates: Map<String, Map<String, Double>> = emptyMap()  // discipleId -> (manualId -> rate)

    /** 战斗前对参战弟子进行一次 HP/MP 恢复结算（脏标记：满状态跳过） */
    fun recoverHpMpForBattleParticipants(discipleIds: List<String>) {
        val equipmentMap = currentEquipmentInstances.associateBy { it.id }
        val manualMap = currentManualInstances.associateBy { it.id }
        val allProficiencies = currentGameData.manualProficiencies
        val multiplier = phaseMultiplier.toDouble()

        currentDisciples = currentDisciples.map { d ->
            if (d.id !in discipleIds || !d.isAlive) return@map d
            var curHp = d.combat.currentHp
            var curMp = d.combat.currentMp
            // 满状态弟子跳过（脏标记：无需恢复）
            if (curHp >= d.maxHp && curMp >= d.maxMp) return@map d
            val discipleProficiencies = allProficiencies[d.id]?.associateBy { it.manualId } ?: emptyMap()
            val finalStats = DiscipleStatCalculator.getFinalStats(d, equipmentMap, manualMap, discipleProficiencies)
            val maxHp = finalStats.maxHp
            val maxMp = finalStats.maxMp
            curHp = if (curHp < 0) curHp else (curHp + (maxHp * GameConfig.Cultivation.DAILY_HP_MP_RECOVERY_RATE * multiplier).toInt().coerceAtLeast(1)).coerceAtMost(maxHp)
            curMp = if (curMp < 0) curMp else (curMp + (maxMp * GameConfig.Cultivation.DAILY_HP_MP_RECOVERY_RATE * multiplier).toInt().coerceAtLeast(1)).coerceAtMost(maxMp)
            if (curHp != d.combat.currentHp || curMp != d.combat.currentMp) d.copyWith(currentHp = curHp, currentMp = curMp) else d
        }
    }

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

    private var currentManualStacks: List<ManualStack>
        get() = stateStore.currentTransactionMutableState()?.manualStacks ?: stateStore.manualStacks.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.manualStacks = value; return }
            scope.launch { stateStore.update { manualStacks = value } }
        }

    private var currentManualInstances: List<ManualInstance>
        get() = stateStore.currentTransactionMutableState()?.manualInstances ?: stateStore.manualInstances.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.manualInstances = value; return }
            scope.launch { stateStore.update { manualInstances = value } }
        }

    private var currentPills: List<Pill>
        get() = stateStore.currentTransactionMutableState()?.pills ?: stateStore.pills.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.pills = value; return }
            scope.launch { stateStore.update { pills = value } }
        }

    private var currentMaterials: List<Material>
        get() = stateStore.currentTransactionMutableState()?.materials ?: stateStore.getCurrentMaterials()
        private set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.materials = value; return }
            scope.launch { stateStore.update { materials = value } }
        }

    private var currentHerbs: List<Herb>
        get() = stateStore.currentTransactionMutableState()?.herbs ?: stateStore.getCurrentHerbs()
        private set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.herbs = value; return }
            scope.launch { stateStore.update { herbs = value } }
        }

    private var currentSeeds: List<Seed>
        get() = stateStore.currentTransactionMutableState()?.seeds ?: stateStore.getCurrentSeeds()
        private set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.seeds = value; return }
            scope.launch { stateStore.update { seeds = value } }
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

    private var _highFrequencyData = MutableStateFlow(HighFrequencyData())

    suspend fun processMonthlyEventsOnShadow(state: MutableGameState) {
        val data = state.gameData
        processMonthlyEvents(data.gameYear, data.gameMonth)
    }

    suspend fun processYearlyEventsOnShadow(state: MutableGameState) {
        val data = state.gameData
        processYearlyEvents(data.gameYear)
    }

    fun getHighFrequencyData(): StateFlow<HighFrequencyData> = _highFrequencyData

    fun resetHighFrequencyData() {
        _highFrequencyData.value = HighFrequencyData()
    }

    /**
     * 恢复所有弟子的 HP/MP。
     * 从 processPhaseTick 中提取，以便结算期间也可独立调用。
     */
    fun recoverHpMpForAllDisciples(state: MutableGameState) {
        val equipmentMap = state.equipmentInstances.associateBy { it.id }
        val manualMap = state.manualInstances.associateBy { it.id }
        val allProficiencies = state.gameData.manualProficiencies
        val multiplier = phaseMultiplier.toDouble()

        state.disciples = state.disciples.map { d ->
            if (!d.isAlive) return@map d
            val curHp = d.combat.currentHp
            val curMp = d.combat.currentMp
            if (curHp < 0 && curMp < 0) return@map d

            val proficiencyMap = allProficiencies[d.id]?.associateBy { it.manualId } ?: emptyMap()
            val finalStats = DiscipleStatCalculator.getFinalStats(d, equipmentMap, manualMap, proficiencyMap)
            val maxHp = finalStats.maxHp
            val maxMp = finalStats.maxMp

            val hpRecovery = (maxHp * GameConfig.Cultivation.DAILY_HP_MP_RECOVERY_RATE * multiplier).toInt().coerceAtLeast(1)
            val mpRecovery = (maxMp * GameConfig.Cultivation.DAILY_HP_MP_RECOVERY_RATE * multiplier).toInt().coerceAtLeast(1)
            val newHp = if (curHp < 0) curHp else (curHp + hpRecovery).coerceAtMost(maxHp)
            val newMp = if (curMp < 0) curMp else (curMp + mpRecovery).coerceAtMost(maxMp)

            if (newHp != curHp || newMp != curMp) {
                d.copyWith(currentHp = newHp, currentMp = newMp)
            } else {
                d
            }
        }
    }

    /**
     * 月度批量修炼更新 — 遍历全体弟子，一次性补齐整月修炼进度
     */
    fun updateMonthlyCultivation(state: MutableGameState) {
        val data = state.gameData
        val livingDisciples = state.disciples.filter { it.isAlive }
        if (livingDisciples.isEmpty()) return

        val monthSeconds = gameClock.msPerPhase * 3 / 1000.0
        val equipmentInstanceMap = state.equipmentInstances.associateBy { it.id }
        val manualInstanceMap = state.manualInstances.associateBy { it.id }
        var updatedManualProficiencies = data.manualProficiencies.toMutableMap()
        val equipmentInstanceUpdates = mutableMapOf<String, EquipmentInstance>()

        // 焦点弟子：高频刷新已给了一部分修炼值/熟练度/孕养，月度批量补足差额
        val focusedGains = _highFrequencyData.value.cultivationUpdates
        val focusedProfGains = _highFrequencyData.value.proficiencyUpdates
        val focusedNurtureGains = _highFrequencyData.value.nurtureUpdates
        val updatedDisciples = state.disciples.map { disciple ->
            if (!disciple.isAlive) return@map disciple

            val cultivationPerSecond = calculateDiscipleCultivationPerSecond(disciple, data)
            val monthlyGain = cultivationPerSecond * monthSeconds
            val alreadyGained = focusedGains[disciple.id] ?: 0.0
            val netGain = (monthlyGain - alreadyGained).coerceAtLeast(0.0)
            var d = disciple.copy(cultivation = (disciple.cultivation + netGain).coerceIn(0.0, disciple.maxCultivation))

            // 功法精通（扣除高频已发量）
            val inLibrary = data.librarySlots.any { it.discipleId == disciple.id }
            val libraryBonus = if (inLibrary) ManualProficiencySystem.LIBRARY_PROFICIENCY_BONUS_RATE else 0.0
            val proficiencyGainPerSecond = ManualProficiencySystem.calculateProficiencyGainPerSecond(disciple.comprehension, libraryBonus)
            val proficiencyGain = proficiencyGainPerSecond * monthSeconds
            val profAlreadyGained = focusedProfGains[d.id] ?: emptyMap()
            d.manualIds.forEach { manualId ->
                manualInstanceMap[manualId]?.let { manual ->
                    val profList = updatedManualProficiencies.getOrDefault(d.id, emptyList()).toMutableList()
                    val profIndex = profList.indexOfFirst { it.manualId == manualId }
                    val alreadyGainedProf = profAlreadyGained[manualId] ?: 0.0
                    val netProfGain = (proficiencyGain - alreadyGainedProf).coerceAtLeast(0.0)
                    val maxProf = ManualProficiencySystem.MAX_PROFICIENCY.toInt()
                    if (profIndex >= 0) {
                        val cp = profList[profIndex]
                        val fixedMaxProf = if (cp.maxProficiency != maxProf) maxProf else cp.maxProficiency
                        val newProf = (cp.proficiency + netProfGain).coerceAtMost(fixedMaxProf.toDouble())
                        profList[profIndex] = cp.copy(
                            proficiency = newProf,
                            maxProficiency = fixedMaxProf,
                            masteryLevel = ManualProficiencySystem.MasteryLevel.fromProficiency(newProf).level
                        )
                    } else {
                        val initProf = netProfGain.coerceAtMost(maxProf.toDouble())
                        profList.add(ManualProficiencyData(
                            manualId = manualId,
                            manualName = manual.name,
                            proficiency = initProf,
                            maxProficiency = maxProf,
                            masteryLevel = ManualProficiencySystem.MasteryLevel.fromProficiency(initProf).level
                        ))
                    }
                    updatedManualProficiencies[d.id] = profList
                }
            }

            // 装备孕养（扣除高频已发量）
            val monthlyNurtureGain = 5.0 * monthSeconds
            val nurtureAlreadyGained = focusedNurtureGains[d.id] ?: emptyMap()

            fun processNurture(eqId: String?) {
                eqId?.let { id ->
                    equipmentInstanceMap[id]?.let { eq ->
                        val already = nurtureAlreadyGained[id] ?: 0.0
                        val netGain = (monthlyNurtureGain - already).coerceAtLeast(0.0)
                        equipmentInstanceUpdates[id] = EquipmentNurtureSystem.updateNurtureExp(eq, netGain).equipment
                    }
                }
            }
            processNurture(d.equipment.weaponId)
            processNurture(d.equipment.armorId)
            processNurture(d.equipment.bootsId)
            processNurture(d.equipment.accessoryId)
            d
        }

        state.disciples = updatedDisciples
        if (equipmentInstanceUpdates.isNotEmpty()) {
            state.equipmentInstances = state.equipmentInstances.map { eq -> equipmentInstanceUpdates[eq.id] ?: eq }
        }
        if (updatedManualProficiencies != data.manualProficiencies) {
            state.gameData = data.copy(manualProficiencies = updatedManualProficiencies)
        }

        _highFrequencyData.value = _highFrequencyData.value.copy(
            lastUpdateTime = System.currentTimeMillis(),
            totalDisciples = livingDisciples.size,
            cultivationUpdates = emptyMap(),   // 本月焦点修炼已结算，清零
            proficiencyUpdates = emptyMap(),   // 本月焦点熟练度已结算
            nurtureUpdates = emptyMap()        // 本月焦点孕养已结算
        )
    }

    /**
     * 月度突破检查 — 遍历全体弟子
     */
    fun processMonthlyBreakthroughs(state: MutableGameState) {
        val data = state.gameData
        val livingDisciples = state.disciples.filter { it.isAlive }
        processRealtimeBreakthroughs(livingDisciples, data)
    }

    /**
     * 焦点弟子高频刷新（200ms） — 玩家查看弟子详情时调用
     * 更新该弟子的修炼进度、功法熟练度、装备孕养，均摊为每旬增量
     */
    fun updateFocusedDisciple(discipleId: String, state: MutableGameState) {
        val data = state.gameData
        val allDisciples = state.disciples
        val disciple = allDisciples.find { it.id == discipleId && it.isAlive } ?: return

        val currentHfd = _highFrequencyData.value

        // 先做突破检查（基于上一 tick 积累的修炼进度），再累加本 tick 修炼值
        // 这样 UI 在不同 tick 之间能自然收到"进度条满"和"突破后"两个状态
        if (disciple.cultivation >= disciple.maxCultivation && isDiscipleFullHpMp(disciple)) {
            processRealtimeBreakthroughs(listOf(disciple), data)
        }

        // 重新读取（突破可能已修改 disciples），再累加修炼进度
        val postBreakthroughDisciples = state.disciples
        val currentDisciple = postBreakthroughDisciples.find { it.id == discipleId } ?: return

        val cultivationPerSecond = calculateDiscipleCultivationPerSecond(disciple, data)
        val perPhaseSeconds = gameClock.msPerPhase / 1000.0
        val gained = cultivationPerSecond * perPhaseSeconds

        state.disciples = postBreakthroughDisciples.map { d ->
            if (d.id == discipleId) d.copy(cultivation = (d.cultivation + gained).coerceIn(0.0, d.maxCultivation))
            else d
        }

        // 记录焦点刷新已给的修炼值，供月度批量扣减，避免重复累加
        val accumGains = currentHfd.cultivationUpdates.toMutableMap()
        accumGains[discipleId] = (accumGains[discipleId] ?: 0.0) + gained

        // —— 功法熟练度高频分发（每月总量均摊到每旬） ——
        val manualInstanceMap = state.manualInstances.associateBy { it.id }
        val inLibrary = data.librarySlots.any { it.discipleId == discipleId }
        val libraryBonus = if (inLibrary) ManualProficiencySystem.LIBRARY_PROFICIENCY_BONUS_RATE else 0.0
        val proficiencyGainPerTick = ManualProficiencySystem.calculateProficiencyGainPerSecond(currentDisciple.comprehension, libraryBonus) * perPhaseSeconds

        var updatedManualProficiencies = data.manualProficiencies.toMutableMap()
        val profUpdatesMap = currentHfd.proficiencyUpdates.toMutableMap()
        val discipleProfUpdates = profUpdatesMap.getOrDefault(discipleId, emptyMap()).toMutableMap()
        val maxProf = ManualProficiencySystem.MAX_PROFICIENCY.toInt()

        currentDisciple.manualIds.forEach { manualId ->
            manualInstanceMap[manualId]?.let { manual ->
                val profList = updatedManualProficiencies.getOrDefault(discipleId, emptyList()).toMutableList()
                val profIndex = profList.indexOfFirst { it.manualId == manualId }
                if (profIndex >= 0) {
                    val cp = profList[profIndex]
                    val fixedMaxProf = if (cp.maxProficiency != maxProf) maxProf else cp.maxProficiency
                    val newProf = (cp.proficiency + proficiencyGainPerTick).coerceAtMost(fixedMaxProf.toDouble())
                    profList[profIndex] = cp.copy(
                        proficiency = newProf,
                        maxProficiency = fixedMaxProf,
                        masteryLevel = ManualProficiencySystem.MasteryLevel.fromProficiency(newProf).level
                    )
                } else {
                    val initProf = proficiencyGainPerTick.coerceAtMost(maxProf.toDouble())
                    profList.add(ManualProficiencyData(
                        manualId = manualId,
                        manualName = manual.name,
                        proficiency = initProf,
                        maxProficiency = maxProf,
                        masteryLevel = ManualProficiencySystem.MasteryLevel.fromProficiency(initProf).level
                    ))
                }
                updatedManualProficiencies[discipleId] = profList
                discipleProfUpdates[manualId] = (discipleProfUpdates[manualId] ?: 0.0) + proficiencyGainPerTick
            }
        }
        profUpdatesMap[discipleId] = discipleProfUpdates
        if (updatedManualProficiencies != data.manualProficiencies) {
            state.gameData = data.copy(manualProficiencies = updatedManualProficiencies)
        }

        // —— 装备孕养高频分发（每月总量均摊到每旬） ——
        val equipmentInstanceMap = state.equipmentInstances.associateBy { it.id }
        val nurtureGainPerTick = 5.0 * perPhaseSeconds
        val nurtureUpdatesMap = currentHfd.nurtureUpdates.toMutableMap()
        val discipleNurtureUpdates = nurtureUpdatesMap.getOrDefault(discipleId, emptyMap()).toMutableMap()
        val equipmentInstanceUpdates = mutableMapOf<String, EquipmentInstance>()

        listOfNotNull(
            currentDisciple.equipment.weaponId,
            currentDisciple.equipment.armorId,
            currentDisciple.equipment.bootsId,
            currentDisciple.equipment.accessoryId
        ).forEach { eqId ->
            equipmentInstanceMap[eqId]?.let { eq ->
                val result = EquipmentNurtureSystem.updateNurtureExp(eq, nurtureGainPerTick)
                equipmentInstanceUpdates[eqId] = result.equipment
                discipleNurtureUpdates[eqId] = (discipleNurtureUpdates[eqId] ?: 0.0) + nurtureGainPerTick
            }
        }
        nurtureUpdatesMap[discipleId] = discipleNurtureUpdates
        if (equipmentInstanceUpdates.isNotEmpty()) {
            state.equipmentInstances = state.equipmentInstances.map { eq -> equipmentInstanceUpdates[eq.id] ?: eq }
        }

        _highFrequencyData.value = currentHfd.copy(
            lastCultivationTime = System.currentTimeMillis(),
            cultivationPerSecond = cultivationPerSecond,
            totalDisciples = allDisciples.count { it.isAlive },
            cultivationUpdates = accumGains,
            proficiencyUpdates = profUpdatesMap,
            nurtureUpdates = nurtureUpdatesMap
        )
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

        // 父母灵根对子嗣修炼速度的影响
        val parent1 = disciple.social.parentId1?.let { allDisciples[it] }
        val parent2 = disciple.social.parentId2?.let { allDisciples[it] }
        val parentCultivationBonus = DiscipleStatCalculator.calculateParentCultivationBonus(parent1, parent2)

        // 亲人逝世对修炼速度的影响
        val griefPenalty = if (DiscipleStatCalculator.isGrieving(disciple.social.griefEndYear, data.gameYear)) {
            DiscipleStatCalculator.GRIEF_CULTIVATION_SPEED_PENALTY
        } else {
            0.0
        }

        return DiscipleStatCalculator.calculateCultivationSpeed(
            disciple = disciple,
            manuals = manualInstanceMap,
            manualProficiencies = discipleProficiencies,
            buildingBonus = buildingBonus,
            preachingElderBonus = wenDaoElderBonus + qingyunElderBonus,
            preachingMastersBonus = wenDaoMastersBonus + qingyunMastersBonus,
            cultivationSubsidyBonus = cultivationSubsidyBonus,
            parentCultivationBonus = parentCultivationBonus,
            griefCultivationSpeedPenalty = griefPenalty
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
     * Process realtime breakthrough checks.
     * When cultivation >= maxCultivation and HP/MP are full, auto attempt breakthrough.
     * Soul power increases breakthrough chance via DiscipleStatCalculator.getBreakthroughChance.
     * On failure, cultivation resets to 0.
     */
    private fun processRealtimeBreakthroughs(livingDisciples: List<Disciple>, data: GameData) {
        val candidates = livingDisciples.filter { disciple ->
            disciple.realm > 0 && disciple.cultivation >= disciple.maxCultivation && isDiscipleFullHpMp(disciple)
        }

        if (candidates.isEmpty()) return

        val candidateIds = candidates.map { it.id }.toSet()
        val updatedDisciples = currentDisciples.map { disciple ->
            if (disciple.id !in candidateIds) return@map disciple
            if (disciple.cultivation < disciple.maxCultivation || disciple.realm <= 0) return@map disciple

            var newCultivation = disciple.cultivation
            var newRealm = disciple.realm
            var newRealmLayer = disciple.realmLayer
            var newLifespan = disciple.lifespan
            var newCurrentHp = disciple.combat.currentHp
            var newCurrentMp = disciple.combat.currentMp
            var newStorageItems = disciple.equipment.storageBagItems
            var shouldContinue = true

            while (shouldContinue && newRealm > 0) {
                val currentMaxCultivation = if (newRealm == 0) Double.MAX_VALUE else {
                    val base = GameConfig.Realm.get(newRealm).cultivationBase
                    val nextBase = GameConfig.Realm.get(newRealm - 1).cultivationBase
                    val maxLayers = GameConfig.Realm.get(newRealm).maxLayers
                    base + (newRealmLayer - 1) * (nextBase - base).toDouble() / maxLayers
                }
                if (newCultivation < currentMaxCultivation) break

                val isMajorBreakthrough = newRealmLayer >= GameConfig.Realm.get(newRealm).maxLayers

                // 消耗突破丹：大境界突破用目标境界的丹，小境界突破用当前境界的丹
                val pillTargetRealm = if (isMajorBreakthrough) newRealm - 1 else newRealm
                var pillBonus = 0.0

                // 先检查弟子管理设置：符合条件则从仓库消耗
                val autoPill = qualifiesForSectAuto(
                    disciple, currentGameData.breakthroughAutoPillFocused, currentGameData.breakthroughAutoPillRootCounts
                ) { false }
                if (autoPill) {
                    val warehousePill = currentPills
                        .filter { it.pillType == "breakthrough" && it.effects.targetRealm == pillTargetRealm }
                        .maxByOrNull { it.effects.breakthroughChance }
                    if (warehousePill != null) {
                        val pillIndex = currentPills.indexOf(warehousePill)
                        if (pillIndex >= 0) {
                            val updatedPills = currentPills.toMutableList()
                            updatedPills.removeAt(pillIndex)
                            currentPills = updatedPills
                            pillBonus = warehousePill.effects.breakthroughChance
                        }
                    }
                }

                // 仓库没有则从储物袋找
                if (pillBonus == 0.0) {
                    val bestPill = newStorageItems
                        .filter { it.itemType == "pill" && it.effect?.pillType == "breakthrough" && it.effect?.targetRealm == pillTargetRealm }
                        .maxByOrNull { it.effect?.breakthroughChance ?: 0.0 }
                    if (bestPill != null) {
                        newStorageItems = newStorageItems - bestPill
                        pillBonus = bestPill.effect?.breakthroughChance ?: 0.0
                    }
                }

                val success = tryBreakthrough(disciple, pillBonus)
                if (success) {
                    newCultivation = 0.0
                    if (newRealmLayer < GameConfig.Realm.get(newRealm).maxLayers) {
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
                    shouldContinue = false
                    val curHp = if (newCurrentHp < 0) disciple.maxHp else newCurrentHp
                    val curMp = if (newCurrentMp < 0) disciple.maxMp else newCurrentMp
                    newCurrentHp = (curHp * 0.1).toInt().coerceAtLeast(1)
                    newCurrentMp = (curMp * 0.1).toInt().coerceAtLeast(1)
                }
            }

            // 突破尝试后清除广告突破加成（无论成功或失败，一次性的）
            val cleanedStatusData = (disciple.statusData ?: emptyMap()).toMutableMap().apply {
                remove("adBreakthroughBonus")
            }

            // 结算后计算下一次完成时间
            val currentAbsoluteMonth = com.xianxia.sect.core.engine.LazyEvaluationDispatcher.toAbsoluteMonth(
                currentGameData.gameYear, currentGameData.gameMonth
            )
            val cultivationRate = calculateDiscipleCultivationPerSecond(disciple, currentGameData)
            val remainingCultivation = if (newCultivation < disciple.maxCultivation) disciple.maxCultivation - newCultivation else 0.0
            val monthsToNext = com.xianxia.sect.core.engine.LazyEvaluationDispatcher
                .estimateMonthsToNextBreakthrough(remainingCultivation, cultivationRate)

            disciple.copyWith(
                cultivation = newCultivation,
                realm = newRealm,
                realmLayer = newRealmLayer,
                lifespan = newLifespan,
                currentHp = newCurrentHp,
                currentMp = newCurrentMp,
                storageBagItems = newStorageItems,
                statusData = cleanedStatusData,
                cultivationCompletionMonth = currentAbsoluteMonth + monthsToNext,
                cultivationCompletionPhase = 1
            )
        }

        currentDisciples = updatedDisciples
    }

    private fun isDiscipleFullHpMp(disciple: Disciple): Boolean {
        val hp = if (disciple.combat.currentHp < 0) disciple.maxHp else disciple.combat.currentHp
        val mp = if (disciple.combat.currentMp < 0) disciple.maxMp else disciple.combat.currentMp
        return hp >= disciple.maxHp && mp >= disciple.maxMp
    }

    private fun qualifiesForSectAuto(disciple: Disciple, focused: Boolean, rootCounts: Set<Int>, legacyCheck: (Disciple) -> Boolean): Boolean {
        return qualifiesForSectAutoPublic(disciple, focused, rootCounts)
    }

    fun qualifiesForSectAutoPublic(disciple: Disciple, focused: Boolean, rootCounts: Set<Int>): Boolean {
        if (focused || rootCounts.isNotEmpty()) {
            if (focused && disciple.statusData["followed"] == "true") return true
            val rootCount = disciple.spiritRootType.split(",").size
            return rootCount in rootCounts
        }
        return false
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
     * Advance game time by one phase (上/中/下旬)
     */
    /**
     * 处理当前 phase 的修炼事件。TimeSystem 已推进 phase，
     * 此方法仅读取当前时间并触发对应事件，不再次推进。
     */
    suspend fun advancePhase(state: MutableGameState? = null) {
        val targetState = state ?: return
        val data = targetState.gameData
        val phase = data.gamePhase
        val month = data.gameMonth
        val year = data.gameYear

        processPhaseEvents(phase, month, year)

        // 焦点弟子实时更新：功法熟练度 + 装备孕养 每 tick 推进
        val focusedId = stateStore.focusedDiscipleId
        if (focusedId != null) {
            updateFocusedDisciple(focusedId, targetState)
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
            gamePhase = 0
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
            gamePhase = 0
        )
        if (state != null) state.gameData = updatedData else currentGameData = updatedData

        processYearlyEvents(newYear)

        processMonthlyEvents(newYear, 1)
    }

    private suspend fun safelyRun(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.e(TAG, "Error in $name", e)
        }
    }

    /**
     * Process phase events (每旬执行一次，1旬≈10天)
     */
    private suspend fun processPhaseEvents(phase: Int, month: Int, year: Int) {
        safelyRun("checkGameOverCondition") { checkGameOverCondition() }

        safelyRun("processPhaseTick") { processPhaseTick(year, month, phase) }
        safelyRun("syncAllDiscipleStatuses") { discipleService.syncAllDiscipleStatuses() }
    }

    private val phaseMultiplier: Int get() = 10

    private suspend fun processPhaseTick(year: Int, month: Int, phase: Int) {
        val phaseSecondsValue = gameClock.msPerPhase / 1000.0
        val equipmentMap = currentEquipmentInstances.associateBy { it.id }
        val manualMap = currentManualInstances.associateBy { it.id }
        val proficienciesMap = currentGameData.manualProficiencies
        val multiplier = phaseMultiplier.toDouble()
        val decay = phaseMultiplier
        val equipmentStacksList = currentEquipmentStacks
        val manualStacksList = currentManualStacks
        val maxEquipStack = inventoryConfig.getMaxStackSize("equipment_stack")
        val maxManualStack = inventoryConfig.getMaxStackSize("manual_stack")

        // 提前过滤：只处理存活弟子
        val aliveDisciples = currentDisciples.filter { it.isAlive }

        // 统一累加器：合并分散的 MutableList/MutableMap
        val acc = PhaseTickAccumulator()

        // 分批处理弟子，每 50 个 yield 一次，避免长时间占用游戏线程
        val batchSize = 50
        val processedAlive = mutableListOf<Disciple>()
        for ((index, disciple) in aliveDisciples.withIndex()) {
            processedAlive.add(
                processDiscipleTick(disciple, year, month, phase, equipmentMap, manualMap, proficienciesMap,
                    multiplier, decay, equipmentStacksList, manualStacksList, maxEquipStack, maxManualStack, acc, phaseSecondsValue)
            )
            if ((index + 1) % batchSize == 0) {
                yield()
                if (thermalMonitor.shouldReduceWorkload()) {
                    delay(5)  // 发热时批次间暂停
                }
            }
        }

        // 合并：存活弟子用处理后的，死亡弟子保持原样
        val aliveMap = processedAlive.associateBy { it.id }
        currentDisciples = currentDisciples.map { if (it.isAlive) aliveMap[it.id] ?: it else it }

        // 一次性应用副作用
        applyAccumulator(acc, maxEquipStack, maxManualStack)

        processAutoFromWarehouse(year, month, phase)
    }

    /**
     * 处理单个弟子的 phase tick 逻辑。
     * 纯计算 + 累加器收集副作用，不修改外部状态。
     */
    private fun processDiscipleTick(
        disciple: Disciple,
        year: Int, month: Int, phase: Int,
        equipmentMap: Map<String, EquipmentInstance>,
        manualMap: Map<String, ManualInstance>,
        proficienciesMap: Map<String, List<ManualProficiencyData>>,
        multiplier: Double, decay: Int,
        equipmentStacksList: List<EquipmentStack>,
        manualStacksList: List<ManualStack>,
        maxEquipStack: Int, maxManualStack: Int,
        acc: PhaseTickAccumulator,
        phaseSecondsValue: Double
    ): Disciple {
        var d = disciple

        if (d.cultivationSpeedDuration > 0) {
            val newDuration = d.cultivationSpeedDuration - decay
            if (newDuration <= 0) {
                d = d.copy(cultivationSpeedBonus = 0.0, cultivationSpeedDuration = 0)
            } else {
                d = d.copy(cultivationSpeedDuration = newDuration)
            }
        }

        if (d.pillEffects.pillEffectDuration > 0) {
            val newDuration = d.pillEffects.pillEffectDuration - decay
            if (newDuration <= 0) {
                d = d.copy(pillEffects = PillEffects())
            } else {
                d = d.copy(pillEffects = d.pillEffects.copy(pillEffectDuration = newDuration))
            }
        }

        val discipleProficiencies = proficienciesMap.getOrDefault(d.id, emptyList())
            .associateBy { it.manualId }
        val finalStats = DiscipleStatCalculator.getFinalStats(d, equipmentMap, manualMap, discipleProficiencies)
        val maxHp = finalStats.maxHp
        val maxMp = finalStats.maxMp
        val curHp = d.combat.currentHp
        val curMp = d.combat.currentMp

        val hpRecovery = (maxHp * GameConfig.Cultivation.DAILY_HP_MP_RECOVERY_RATE * multiplier).toInt().coerceAtLeast(1)
        val mpRecovery = (maxMp * GameConfig.Cultivation.DAILY_HP_MP_RECOVERY_RATE * multiplier).toInt().coerceAtLeast(1)

        val newHp = if (curHp < 0) curHp else (curHp + hpRecovery).coerceAtMost(maxHp)
        val newMp = if (curMp < 0) curMp else (curMp + mpRecovery).coerceAtMost(maxMp)

        if (newHp != curHp || newMp != curMp) {
            d = d.copyWith(currentHp = newHp, currentMp = newMp)
        }

        // 焦点域每 100ms tick 推进修炼值，玩家看到实时进度
        // 焦点弟子由 updateFocusedDisciple 单独处理（含突破检查+熟练度+孕养），此处跳过
        if (d.id != stateStore.focusedDiscipleId) {
            val cultivationRate = cachedCultivationRates[d.id] ?: 0.0
            if (cultivationRate > 0 && d.cultivation < d.maxCultivation) {
                val gain = cultivationRate * phaseSecondsValue
                d = d.copy(cultivation = (d.cultivation + gain).coerceAtMost(d.maxCultivation))
                // 记录累积量：月度结算时扣除已推进的进度，避免重复计算
                val currentHfd = _highFrequencyData.value
                val accumGains = currentHfd.cultivationUpdates.toMutableMap()
                accumGains[d.id] = (accumGains[d.id] ?: 0.0) + gain
                _highFrequencyData.value = currentHfd.copy(cultivationUpdates = accumGains)
            }
        }

        val pillResult = DisciplePillManager.processAutoUsePills(
            disciple = d, gameYear = year, gameMonth = month, gamePhase = phase
        )
        if (pillResult.disciple != d) {
            d = pillResult.disciple
        }

        // 仅储物袋有装备或装备栏发生变更时检测（绝大部分弟子无装备可穿，跳过省CPU）
        val hasEquipmentInBag = d.equipment.storageBagItems.any { it.itemType == "equipment" }
        val needEquipCheck = hasEquipmentInBag || d.id in autoEquipDirty
        val equipResult = if (needEquipCheck) {
            DiscipleEquipmentManager.processAutoEquip(
                disciple = d, equipmentStacks = equipmentStacksList,
                equipmentInstances = equipmentMap,
                gameYear = year, gameMonth = month, gamePhase = phase,
                maxStack = maxEquipStack
            )
        } else null
        if (equipResult != null && equipResult.newInstances.isNotEmpty()) {
            d = equipResult.disciple
            acc.equipInstancesToAdd.addAll(equipResult.newInstances)
            equipResult.replacedInstances.forEach { acc.equipInstanceIdsToRemove.add(it.id) }
            equipResult.stackUpdates.forEach { update ->
                if (update.isDeletion) {
                    acc.equipStackDeletions.add(update.stackId)
                } else {
                    acc.equipStackQuantityDeltas.merge(update.stackId, update.newQuantity) { _, new -> new }
                }
            }
            equipResult.replacedEquipmentStacks.forEach { replacedStack ->
                acc.equipStackAdditions.add(replacedStack)
            }
        }

        // 仅储物袋有功法或功法栏变更时检测（绝大部分弟子无功法可学，跳过省CPU）
        val hasManualInBag = d.equipment.storageBagItems.any { it.itemType == "manual" }
        val needLearnCheck = hasManualInBag || d.id in autoLearnDirty
        val manualResult = if (needLearnCheck) {
            DiscipleManualManager.processAutoLearn(
                disciple = d, manualStacks = manualStacksList,
                manualInstances = manualMap,
                gameYear = year, gameMonth = month, gamePhase = phase,
                maxStack = maxManualStack
            )
        } else null
        if (manualResult != null && manualResult.newInstance != null) {
            d = manualResult.disciple
            manualResult.newInstance?.let { acc.manualInstancesToAdd.add(it) }
            manualResult.replacedInstance?.let { replaced ->
                acc.manualInstanceIdsToRemove.add(replaced.id)
                acc.profRemovals.getOrPut(d.id) { mutableSetOf() }.add(replaced.id)
            }
            manualResult.stackUpdate?.let { update ->
                if (update.isDeletion) {
                    acc.manualStackDeletions.add(update.stackId)
                } else {
                    acc.manualStackQuantityDeltas.merge(update.stackId, update.newQuantity) { _, new -> new }
                }
            }
            manualResult.replacedManualStack?.let { replacedStack ->
                acc.manualStackAdditions.add(replacedStack)
            }
        }

        autoEquipDirty.remove(d.id)
        autoLearnDirty.remove(d.id)
        return d
    }

    /**
     * PhaseTick 副作用统一累加器。
     * 将分散的 11+ 个 MutableList/MutableMap 合并为一个数据类，
     * 在 processDiscipleTick 中收集，在 applyAccumulator 中一次性应用。
     */
    private data class PhaseTickAccumulator(
        val equipInstancesToAdd: MutableList<EquipmentInstance> = mutableListOf(),
        val equipInstanceIdsToRemove: MutableSet<String> = mutableSetOf(),
        val equipStackDeletions: MutableSet<String> = mutableSetOf(),
        val equipStackQuantityDeltas: MutableMap<String, Int> = mutableMapOf(),
        val equipStackAdditions: MutableList<EquipmentStack> = mutableListOf(),
        val manualInstancesToAdd: MutableList<ManualInstance> = mutableListOf(),
        val manualInstanceIdsToRemove: MutableSet<String> = mutableSetOf(),
        val manualStackDeletions: MutableSet<String> = mutableSetOf(),
        val manualStackQuantityDeltas: MutableMap<String, Int> = mutableMapOf(),
        val manualStackAdditions: MutableList<ManualStack> = mutableListOf(),
        val profRemovals: MutableMap<String, MutableSet<String>> = mutableMapOf()
    )

    /**
     * 一次性应用累加器中的副作用到全局状态。
     */
    private fun applyAccumulator(acc: PhaseTickAccumulator, maxEquipStack: Int, maxManualStack: Int) {
        if (acc.equipInstancesToAdd.isNotEmpty() || acc.equipInstanceIdsToRemove.isNotEmpty()) {
            currentEquipmentInstances = currentEquipmentInstances
                .filter { it.id !in acc.equipInstanceIdsToRemove } + acc.equipInstancesToAdd
        }
        if (acc.equipStackDeletions.isNotEmpty()) {
            currentEquipmentStacks = currentEquipmentStacks.filter { it.id !in acc.equipStackDeletions }
        }
        acc.equipStackQuantityDeltas.forEach { (stackId, newQty) ->
            currentEquipmentStacks = currentEquipmentStacks.map {
                if (it.id == stackId) it.copy(quantity = newQty.coerceAtMost(maxEquipStack)) else it
            }
        }
        acc.equipStackAdditions.forEach { stack ->
            val existing = currentEquipmentStacks.find { it.id == stack.id }
            currentEquipmentStacks = if (existing != null) {
                currentEquipmentStacks.map {
                    if (it.id == stack.id) it.copy(quantity = (it.quantity + 1).coerceAtMost(maxEquipStack)) else it
                }
            } else {
                currentEquipmentStacks + stack
            }
        }

        if (acc.manualInstancesToAdd.isNotEmpty() || acc.manualInstanceIdsToRemove.isNotEmpty()) {
            currentManualInstances = currentManualInstances
                .filter { it.id !in acc.manualInstanceIdsToRemove } + acc.manualInstancesToAdd
        }
        if (acc.profRemovals.isNotEmpty()) {
            val updatedProficiencies = currentGameData.manualProficiencies.toMutableMap()
            acc.profRemovals.forEach { (discipleId, manualIds) ->
                updatedProficiencies[discipleId]?.let { profList ->
                    val filtered = profList.filter { it.manualId !in manualIds }
                    if (filtered.isEmpty()) updatedProficiencies.remove(discipleId)
                    else updatedProficiencies[discipleId] = filtered
                }
            }
            currentGameData = currentGameData.copy(manualProficiencies = updatedProficiencies)
        }
        if (acc.manualStackDeletions.isNotEmpty()) {
            currentManualStacks = currentManualStacks.filter { it.id !in acc.manualStackDeletions }
        }
        acc.manualStackQuantityDeltas.forEach { (stackId, newQty) ->
            currentManualStacks = currentManualStacks.map {
                if (it.id == stackId) it.copy(quantity = newQty.coerceAtMost(maxManualStack)) else it
            }
        }
        acc.manualStackAdditions.forEach { stack ->
            val existing = currentManualStacks.find { it.id == stack.id }
            currentManualStacks = if (existing != null) {
                currentManualStacks.map {
                    if (it.id == stack.id) it.copy(quantity = (it.quantity + 1).coerceAtMost(maxManualStack)) else it
                }
            } else {
                currentManualStacks + stack
            }
        }
    }

    private fun processAutoFromWarehouse(year: Int, month: Int, phase: Int) {
        val equipFocused = currentGameData.autoEquipFromWarehouseFocused
        val equipRootCounts = currentGameData.autoEquipFromWarehouseRootCounts
        val learnFocused = currentGameData.autoLearnFromWarehouseFocused
        val learnRootCounts = currentGameData.autoLearnFromWarehouseRootCounts
        val hasAutoEquip = equipFocused || equipRootCounts.isNotEmpty()
        val hasAutoLearn = learnFocused || learnRootCounts.isNotEmpty()

        if (!hasAutoEquip && !hasAutoLearn) return

        val updatedDisciples = currentDisciples.toMutableList()
        val bagEqIds = mutableSetOf<String>()
        val bagMnIds = mutableSetOf<String>()
        for (disciple in updatedDisciples) {
            for (item in disciple.equipment.storageBagItems) {
                when (item.itemType) {
                    "equipment_stack" -> bagEqIds.add(item.itemId)
                    "manual_stack" -> bagMnIds.add(item.itemId)
                }
            }
        }

        var eqStacks = currentEquipmentStacks.filter { it.id !in bagEqIds }
        var mnStacks = currentManualStacks.filter { it.id !in bagMnIds }
        val eqInstancesById = currentEquipmentInstances.associateBy { it.id }
        val mnInstancesById = currentManualInstances.associateBy { it.id }

        val sortedIndices = updatedDisciples.indices
            .filter { updatedDisciples[it].isAlive }
            .sortedWith(compareByDescending<Int> { updatedDisciples[it].statusData["followed"] == "true" }
                .thenBy { updatedDisciples[it].realm }
                .thenByDescending { updatedDisciples[it].realmLayer })

        for (idx in sortedIndices) {
            val disciple = updatedDisciples[idx]
            var d = disciple

            if (qualifiesForSectAuto(d, equipFocused, equipRootCounts) { it.equipment.autoEquipFromWarehouse }) {
                val result = DiscipleEquipmentManager.processAutoEquipFromWarehouse(
                    disciple = d,
                    warehouseStacks = eqStacks,
                    equipmentInstances = eqInstancesById,
                    gameYear = year,
                    gameMonth = month,
                    gamePhase = phase,
                    maxStack = inventoryConfig.getMaxStackSize("equipment_stack")
                )
                if (result.newInstances.isNotEmpty()) {
                    d = result.disciple
                    currentEquipmentInstances = currentEquipmentInstances + result.newInstances
                    result.stackUpdates.forEach { update ->
                        if (update.isDeletion) {
                            eqStacks = eqStacks.filter { it.id != update.stackId }
                        } else {
                            eqStacks = eqStacks.map {
                                if (it.id == update.stackId) it.copy(quantity = update.newQuantity) else it
                            }
                        }
                    }
                }
            }

            if (qualifiesForSectAuto(d, learnFocused, learnRootCounts) { it.autoLearnFromWarehouse }) {
                val result = DiscipleManualManager.processAutoLearnFromWarehouse(
                    disciple = d,
                    warehouseStacks = mnStacks,
                    manualInstances = mnInstancesById,
                    gameYear = year,
                    gameMonth = month,
                    gamePhase = phase,
                    maxStack = inventoryConfig.getMaxStackSize("manual_stack")
                )
                if (result.newInstance != null) {
                    d = result.disciple
                    currentManualInstances = currentManualInstances + result.newInstance
                    result.stackUpdate?.let { update ->
                        if (update.isDeletion) {
                            mnStacks = mnStacks.filter { it.id != update.stackId }
                        } else {
                            mnStacks = mnStacks.map {
                                if (it.id == update.stackId) it.copy(quantity = update.newQuantity) else it
                            }
                        }
                    }
                }
            }

            if (d !== disciple) {
                updatedDisciples[idx] = d
            }
        }

        currentDisciples = updatedDisciples.toList()
        currentEquipmentStacks = currentEquipmentStacks.filter { it.id in bagEqIds } + eqStacks
        currentManualStacks = currentManualStacks.filter { it.id in bagMnIds } + mnStacks
    }

    /**
     * Process monthly events - core game logic
     */
    private suspend fun processMonthlyEvents(year: Int, month: Int) {
        // 0-3: 生产/经济逻辑已迁移至 ProductionSubsystem 和 EconomySubsystem

        // 4. 藏经阁加成已在 updateRealtimeCultivation() 中实时处理，无需月度处理

        // 6. Cave exploration is now real-time combat — no monthly processing needed
        // (previously: processCaveLifecycle)

        // 8. Process AI sect operations (probabilistic — must run monthly)
        processAISectOperations(year, month)

        // 8.5 Check game over condition
        checkGameOverCondition()

        // 9. Process scout info expiry — lazy: only check when expiry reached
        processScoutInfoExpiryLazy(year, month)

        // 10. Process diplomacy events — capped at 2/month
        processDiplomacyMonthlyEventsCapped(year, month)

        // 11. Law enforcement is now passive — triggered only after theft detection
        // (previously: processLawEnforcementMonthly → processTheftMonthly)

        // 12. Process theft — early exit if no low-morality disciples
        processTheftIfNeeded()

        // 14. Mission refresh: every 3 months. Completion: lazy via completionMonth
        processMissionRefreshIfDue(month)
        processCompletedMissionsLazy(year, month)
    }

    /**
     * 惰性任务完成检查：仅检查到期任务。
     * 计算每个任务的 completionMonth，跳过未到期的。
     */
    private fun processCompletedMissionsLazy(year: Int, month: Int) {
        val data = currentGameData
        val currentAbsoluteMonth = com.xianxia.sect.core.engine.LazyEvaluationDispatcher.toAbsoluteMonth(year, month)
        val completedIds = mutableListOf<String>()
        val remainingActive = mutableListOf<ActiveMission>()

        for (activeMission in data.activeMissions) {
            // 惰性跳过：任务完成月份未到
            val missionCompletionMonth = com.xianxia.sect.core.engine.LazyEvaluationDispatcher.toAbsoluteMonth(
                activeMission.startYear, activeMission.startMonth
            ) + activeMission.duration
            if (currentAbsoluteMonth < missionCompletionMonth) {
                remainingActive.add(activeMission)
                continue
            }
            if (activeMission.isComplete(year, month)) {
                completedIds.add(activeMission.id)

                val aliveDisciples = activeMission.discipleIds.mapNotNull { did ->
                    currentDisciples.find { it.id == did && it.isAlive }
                }
                val allDead = aliveDisciples.isEmpty()

                if (!allDead) {
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

                    if (result.combatTriggered && result.victory && result.battleResult != null) {
                        val missionSurvivorIds = result.battleResult.log.teamMembers
                            .filter { it.isAlive }.map { it.id }.toSet()
                        currentDisciples = currentDisciples.map { d ->
                            if (d.id in missionSurvivorIds && d.isAlive) {
                                d.copyWith(soulPower = d.soulPower + 1)
                            } else d
                        }
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

    /**
     * 任务刷新 — 每 3 个月一次。
     * 刷新前清除未被接取的旧任务，新任务持续 3 个月。
     */
    private fun processMissionRefreshIfDue(month: Int) {
        if (month % MissionSystem.REFRESH_INTERVAL_MONTHS != 1) return
        processMissionRefresh()
    }

    /**
     * 侦察情报过期 — 惰性处理，仅在过期月份检查。
     */
    private fun processScoutInfoExpiryLazy(year: Int, month: Int) {
        // 惰性：仅检查是否有过期的情报
        val data = currentGameData
        val hasExpired = data.scoutInfo.any { (_, info) ->
            year > info.expiryYear || (year == info.expiryYear && month > info.expiryMonth)
        }
        if (!hasExpired) return
        processScoutInfoExpiry(year, month)
    }

    /**
     * 外交事件 — 每月最多 2 次。
     * 计数器在进入新月时自动重置。
     */
    private fun processDiplomacyMonthlyEventsCapped(year: Int, month: Int) {
        val currentAbsoluteMonth = com.xianxia.sect.core.engine.LazyEvaluationDispatcher.toAbsoluteMonth(year, month)
        if (currentAbsoluteMonth != diplomacyEventsMonth) {
            diplomacyEventsMonth = currentAbsoluteMonth
            diplomacyEventsThisMonth = 0
        }
        if (diplomacyEventsThisMonth >= 2) return
        diplomacyEventsThisMonth++
        processDiplomacyMonthlyEvents(year, month)
    }

    /**
     * 盗窃检查 — 仅在存在低道德弟子时执行。
     * 执法检查改为被动触发：盗窃发生后在此方法内触发执法判定。
     */
    private suspend fun processTheftIfNeeded() {
        if (currentGameData.spiritStones <= 0) return
        val hasLowMoralityDisciple = currentDisciples.any {
            it.isAlive && it.status == DiscipleStatus.IDLE &&
            DiscipleStatCalculator.getBaseStats(it).morality < GameConfig.LawEnforcementConfig.MORALITY_THRESHOLD &&
            DiscipleStatCalculator.getBaseStats(it).loyalty < GameConfig.LawEnforcementConfig.LOYALTY_THRESHOLD
        }
        if (!hasLowMoralityDisciple) return
        processTheftMonthly()
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

        // 1.5 Process AI sect disciple aging
        processSectDisciplesAging(year)

        // 2. Process yearly aging effects
        processYearlyAging(year)

        // 2.5 年度薪水结算（12个月批量，替代月度）
        processSalaryYearly(year)

        // 3. Refresh recruit list (每年一月刷新招募弟子列表)
        refreshRecruitList(year)

        // 4. Refresh traveling merchant (每年一月刷新云游商人)
        refreshTravelingMerchant(year, 1)

        // 5. Process cross-sect partner matching
        processCrossSectPartnerMatching(year, 1)

        // 6. Process alliance expiry
        checkAllianceExpiry(year)

        // 8. Process alliance favor drop
        checkAllianceFavorDrop()

        // 9. Process AI alliances
        processAIAlliances(year)

        // 10. Process reflection cliff release (监牢期满释放)
        processReflectionRelease(year)

        // 11. Process favor decay for high favor relations
        processFavorDecay(year)

        // 12. Yearly AI garrison rotation — top 10 stay home, rest fill occupied sect garrisons
        currentGameData = AISectGarrisonManager.rotateGarrisonSlots(currentGameData)

        // 13. 清理过期的悲痛期
        processGriefExpiry(year)
    }

    /**
     * 清理过期的悲痛期
     */
    private fun processGriefExpiry(currentYear: Int) {
        currentDisciples = currentDisciples.map { disciple ->
            val griefEnd = disciple.social.griefEndYear
            if (griefEnd != null && currentYear >= griefEnd) {
                disciple.copy(social = disciple.social.copy(griefEndYear = null))
            } else {
                disciple
            }
        }
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
            }

            // Check for natural death (old age)
            // 动态计算天赋寿命加成（处理老存档弟子创建时未应用天赋寿命的情况）
            val talentEffects = TalentDatabase.calculateTalentEffects(agedDisciple.talentIds)
            val lifespanBonus = talentEffects["lifespan"] ?: 0.0
            val realmMaxAge = GameConfig.Realm.get(agedDisciple.realm).maxAge
            val talentLifespan = (realmMaxAge * (1.0 + lifespanBonus)).toInt().coerceAtLeast(1)
            val maxAge = maxOf(agedDisciple.lifespan, realmMaxAge, talentLifespan)
            if (agedDisciple.age >= maxAge) {
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

        // 亲人逝世影响：为所有存活亲属设置悲痛期（持续1年）
        currentDisciples = DiscipleStatCalculator.applyGriefToRelatives(
            currentDisciples, listOf(disciple), currentGameData.gameYear
        )

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
    private fun tryBreakthrough(disciple: Disciple, pillBonus: Double = 0.0): Boolean {
        val data = currentGameData
        val elderSlots = data.elderSlots
        val allDisciples = currentDisciples.associateBy { it.id }

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

        // 从 statusData 读取广告突破加成
        val adBonus = disciple.statusData?.get("adBreakthroughBonus")?.toDoubleOrNull() ?: 0.0

        // 亲人逝世对突破率的影响
        val griefBreakthroughPenalty = if (DiscipleStatCalculator.isGrieving(disciple.social.griefEndYear, data.gameYear)) {
            DiscipleStatCalculator.GRIEF_BREAKTHROUGH_CHANCE_PENALTY
        } else {
            0.0
        }

        val chance = DiscipleStatCalculator.getBreakthroughChance(
            disciple = disciple,
            innerElderComprehension = innerElderComprehension,
            outerElderComprehensionBonus = outerElderComprehensionBonus,
            pillBonus = pillBonus,
            adBonus = adBonus,
            griefBreakthroughPenalty = griefBreakthroughPenalty
        )
        val success = Random.nextDouble() < chance

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
            ((comprehension - 80) / 4) * 0.01
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
            if (slot.isWorking && slot.assignedDiscipleId.isNullOrEmpty()) return@forEach
            if (slot.isWorking && slot.isFinished(year, month)) {
                val recipeId = slot.recipeId
                if (recipeId != null) {
                    val recipe = ForgeRecipeDatabase.getRecipeById(recipeId)
                    if (recipe != null) {
                        val equipment = inventorySystem.createEquipmentFromRecipe(recipe)
                        inventorySystem.addEquipmentStack(equipment)
                    }
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
                        buildingId = "forge",
                        autoRestartEnabled = slot.autoRestartEnabled,
                        assignedDiscipleId = slot.assignedDiscipleId,
                        assignedDiscipleName = slot.assignedDiscipleName,
                        recipeId = slot.recipeId
                    )
                }
            }
        }

        val alchemySlots = productionSlotRepository.getSlotsByType(com.xianxia.sect.core.model.production.BuildingType.ALCHEMY)
        alchemySlots.forEach { slot ->
            if (slot.isWorking && slot.assignedDiscipleId.isNullOrEmpty()) return@forEach
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
                }

                slot.assignedDiscipleId?.let { discipleId ->
                    currentDisciples = currentDisciples.map {
                        if (it.id == discipleId) it.copy(status = DiscipleStatus.IDLE) else it
                    }
                }

                // Reset slot to IDLE, preserving auto-restart and assigned disciple
                productionSlotRepository.updateSlotByBuildingId("alchemy", slot.slotIndex) { s ->
                    ProductionSlot.createIdle(
                        id = s.id,
                        slotIndex = slot.slotIndex,
                        buildingType = com.xianxia.sect.core.model.production.BuildingType.ALCHEMY,
                        buildingId = "alchemy",
                        autoRestartEnabled = slot.autoRestartEnabled,
                        assignedDiscipleId = slot.assignedDiscipleId,
                        assignedDiscipleName = slot.assignedDiscipleName,
                        recipeId = slot.recipeId
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
                    val result = inventorySystem.addHerb(herbItem)
                    if (result != AddResult.SUCCESS) {
                        Log.w(TAG, "HerbGarden harvest addHerb failed: ${herb.name} x${actualYield}, result=$result")
                    }
                }

                productionSlotRepository.updateSlotByBuildingId("herbGarden", slot.slotIndex) { s ->
                    ProductionSlot.createIdle(
                        id = s.id,
                        slotIndex = slot.slotIndex,
                        buildingType = com.xianxia.sect.core.model.production.BuildingType.HERB_GARDEN,
                        buildingId = "herbGarden",
                        autoRestartEnabled = slot.autoRestartEnabled,
                        recipeId = slot.recipeId
                    )
                }
            }
        }

    }

    internal suspend fun processAutoPlant() {
        val data = currentGameData

        val herbGardenSlots = productionSlotRepository.getSlotsByType(com.xianxia.sect.core.model.production.BuildingType.HERB_GARDEN)
        val idleSlots = herbGardenSlots.filter {
            it.autoRestartEnabled && it.status == com.xianxia.sect.core.model.production.ProductionSlotStatus.IDLE
        }
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
                expectedYield = seedToPlant.yield,
                autoRestartEnabled = slot.autoRestartEnabled,
                completionMonth = com.xianxia.sect.core.engine.LazyEvaluationDispatcher.toAbsoluteMonth(data.gameYear, data.gameMonth) + seedToPlant.growTime.coerceAtLeast(1),
                completionPhase = 3  // 种植下旬
            )

            productionSlotRepository.updateSlotByBuildingId("herbGarden", slot.slotIndex) { newSlot }
            inventorySystem.removeSeedSync(seedToPlant.id, 1)
        }
    }

    /**
     * Process spirit field harvests - check all planted fields and auto-harvest when growth completes.
     */
    internal suspend fun processSpiritFieldHarvest() {
        val data = currentGameData
        val currentYear = data.gameYear
        val currentMonth = data.gameMonth
        val allDisciples = currentDisciples

        val plants = data.spiritFieldPlants
        if (plants.isEmpty()) return

        var updatedPlants = plants
        var hasChanges = false

        plants.forEach { plant ->
            if (plant.seedId.isEmpty() || plant.growTime <= 0) return@forEach

            val elapsedMonths = (currentYear - plant.plantYear) * 12 + (currentMonth - plant.plantMonth)
            val speedBonus = calculateSpiritFieldMaturityBonus(plant, data, allDisciples)
            val effectiveGrowTime = HerbGardenAuraService.calculateEffectiveGrowTime(plant.growTime, speedBonus)

            if (elapsedMonths >= effectiveGrowTime) {
                val dbHerb = HerbDatabase.getHerbFromSeedName(plant.seedName)
                if (dbHerb != null) {
                    val finalYield = plant.expectedYield.coerceAtLeast(1)

                    val herbName = dbHerb.name
                    val herbRarity = dbHerb.rarity
                    val herbCat = dbHerb.category
                    val herbs = currentHerbs
                    val existingIdx = herbs.indexOfFirst { h ->
                        h.name == herbName && h.rarity == herbRarity && h.category == herbCat
                    }
                    if (existingIdx >= 0) {
                        val updated = herbs.toMutableList()
                        updated[existingIdx] = updated[existingIdx].let {
                            Herb(id = it.id, name = it.name, rarity = it.rarity, description = it.description,
                                category = it.category, quantity = it.quantity + finalYield)
                        }
                        currentHerbs = updated
                    } else {
                        currentHerbs = herbs + Herb(
                            id = java.util.UUID.randomUUID().toString(),
                            name = herbName, rarity = herbRarity, description = dbHerb.description,
                            category = herbCat, quantity = finalYield
                        )
                    }
                }

                // Auto-replant or clear field
                val idx = updatedPlants.indexOfFirst { it.buildingInstanceId == plant.buildingInstanceId }
                if (idx >= 0) {
                    val matchingSeed = HerbDatabase.getSeedByName(plant.seedName)
                    val existingSeed = currentSeeds.find { s ->
                        s.name == plant.seedName && s.rarity == (matchingSeed?.rarity ?: 1) && s.growTime == plant.growTime && s.quantity > 0
                    }
                    val currentAbsoluteMonth = com.xianxia.sect.core.engine.LazyEvaluationDispatcher.toAbsoluteMonth(currentYear, currentMonth)
                    updatedPlants = updatedPlants.toMutableList().also {
                        if (existingSeed != null) {
                            inventorySystem.removeSeedSync(existingSeed.id, 1)
                            it[idx] = it[idx].copy(
                                plantYear = currentYear, plantMonth = currentMonth,
                                completionMonth = currentAbsoluteMonth + plant.growTime.coerceAtLeast(1),
                                completionPhase = 3  // 种植下旬
                            )
                        } else {
                            it[idx] = it[idx].copy(
                                seedId = "", seedName = "", growTime = 0, expectedYield = 0,
                                plantYear = 0, plantMonth = 0,
                                completionMonth = 0, completionPhase = 1
                            )
                        }
                    }
                    hasChanges = true
                }
            }
        }

        if (hasChanges) {
            currentGameData = currentGameData.copy(spiritFieldPlants = updatedPlants)
        }
    }

    private fun calculateSpiritFieldMaturityBonus(
        plant: SpiritFieldPlant,
        gameData: GameData,
        allDisciples: List<Disciple>
    ): Double {
        val elderBonus = HerbGardenAuraService.calculateElderMaturityBonus(
            gameData.elderSlots, allDisciples
        )
        val policyBonus = if (gameData.sectPolicies.herbCultivation) {
            GameConfig.PolicyConfig.HERB_CULTIVATION_BASE_EFFECT
        } else 0.0
        val auraBonus = if (HerbGardenAuraService.isSpiritFieldInAura(
                plant.buildingInstanceId, gameData.placedBuildings
            )) {
            HerbGardenAuraService.calculateAuraMaturityBonus(gameData.elderSlots, allDisciples)
        } else 0.0

        return policyBonus + elderBonus + auraBonus
    }

    internal suspend fun processAutoAlchemy() {
        val data = currentGameData

        val alchemySlots = productionSlotRepository.getSlotsByType(com.xianxia.sect.core.model.production.BuildingType.ALCHEMY)
        val idleSlotIndices = alchemySlots
            .filter { it.autoRestartEnabled
                && it.status == com.xianxia.sect.core.model.production.ProductionSlotStatus.IDLE
                && it.assignedDiscipleId.isNullOrEmpty().not() }
            .map { it.slotIndex }
        if (idleSlotIndices.isEmpty()) return

        val allRecipes = PillRecipeDatabase.getAllRecipes().sortedByDescending { it.rarity }
        val alchemyPolicyBonus = if (data.sectPolicies.alchemyIncentive) GameConfig.PolicyConfig.ALCHEMY_INCENTIVE_BASE_EFFECT else 0.0

        for (slotIndex in idleSlotIndices) {
            val herbs = currentHerbs
            val slot = alchemySlots.find { it.slotIndex == slotIndex } ?: break

            // First try to continue the same recipe from the previous run
            val recipeToStart = slot.recipeId
                ?.let { prevRecipeId ->
                    allRecipes.find { it.id == prevRecipeId }?.takeIf { recipe ->
                        recipe.materials.all { (materialId, requiredQuantity) ->
                            val herbData = HerbDatabase.getHerbById(materialId)
                            val herbName = herbData?.name
                            val herbRarity = herbData?.rarity ?: 1
                            val herb = herbs.find { it.name == herbName && it.rarity == herbRarity }
                            herb != null && herb.quantity >= requiredQuantity
                        }
                    }
                }
                // Fall back to best available recipe by rarity
                ?: allRecipes.firstOrNull { recipe ->
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
            } else {
                break
            }
        }
    }

    internal suspend fun processAutoForge() {
        val data = currentGameData

        val forgeSlots = productionSlotRepository.getSlotsByBuildingId("forge")
        val idleSlotIndices = forgeSlots
            .filter { it.autoRestartEnabled
                && it.status == com.xianxia.sect.core.model.production.ProductionSlotStatus.IDLE
                && it.assignedDiscipleId.isNullOrEmpty().not() }
            .map { it.slotIndex }
        if (idleSlotIndices.isEmpty()) return

        val allRecipes = ForgeRecipeDatabase.getAllRecipes().sortedByDescending { it.rarity }
        val forgePolicyBonus = if (data.sectPolicies.forgeIncentive) GameConfig.PolicyConfig.FORGE_INCENTIVE_BASE_EFFECT else 0.0

        for (slotIndex in idleSlotIndices) {
            val materials = currentMaterials
            val materialIndex = materials.groupBy { it.name to it.rarity }
                .mapValues { (_, list) -> list.sumOf { it.quantity } }
            val slot = forgeSlots.find { it.slotIndex == slotIndex } ?: break

            // First try to continue the same recipe from the previous run
            val recipeToStart = slot.recipeId
                ?.let { prevRecipeId ->
                    allRecipes.find { it.id == prevRecipeId }?.takeIf { recipe ->
                        recipe.materials.all { (materialId, requiredQuantity) ->
                            val materialData = BeastMaterialDatabase.getMaterialById(materialId)
                            materialData != null && run {
                                val available = materialIndex[materialData.name to materialData.rarity] ?: 0
                                available >= requiredQuantity
                            }
                        }
                    }
                }
                // Fall back to best available recipe by rarity
                ?: allRecipes.firstOrNull { recipe ->
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
            } else {
                break
            }
        }
    }

    /**
     * Auto-assign idle disciples to empty production slots based on SectPolicies thresholds.
     */
    internal suspend fun processAutoAssign() {
        val data = currentGameData
        val policies = data.sectPolicies
        val idleDisciples = mutableListOf<Disciple>().also { it.addAll(currentDisciples.filter { d -> d.status == DiscipleStatus.IDLE && d.isAlive }) }

        fun takeCandidate(focused: Boolean, rootCounts: List<Int>, threshold: Int, attr: (Disciple) -> Int): Disciple? {
            val enabled = focused || rootCounts.isNotEmpty()
            if (!enabled || idleDisciples.isEmpty()) return null
            val candidate = idleDisciples
                .filter { d ->
                    val matchesFilter = (focused && isDiscipleFollowed(d)) || d.spiritRoot.types.size in rootCounts
                    matchesFilter && attr(d) >= threshold
                }
                .maxByOrNull { attr(it) }
            if (candidate != null) idleDisciples.remove(candidate)
            return candidate
        }

        // 1. Spirit mine
        if (policies.autoMineFocused || policies.autoMineRootCounts.isNotEmpty()) {
            val candidate = takeCandidate(policies.autoMineFocused, policies.autoMineRootCounts, policies.autoMineThreshold) { it.mining }
            if (candidate != null) {
                val emptyIndex = data.spiritMineSlots.indexOfFirst { it.discipleId.isEmpty() }
                if (emptyIndex >= 0) {
                    currentGameData = data.copy(spiritMineSlots = data.spiritMineSlots.mapIndexed { i, slot ->
                        if (i == emptyIndex) slot.copy(discipleId = candidate.id, discipleName = candidate.name) else slot
                    })
                    markDiscipleAssigned(candidate.id, DiscipleStatus.MINING)
                }
            }
        }

        // 2. Herb garden
        if (policies.autoPlantFocused || policies.autoPlantRootCounts.isNotEmpty()) {
            val candidate = takeCandidate(policies.autoPlantFocused, policies.autoPlantRootCounts, policies.autoPlantThreshold) { it.spiritPlanting }
            if (candidate != null) {
                val slots = productionSlotRepository.getSlotsByType(com.xianxia.sect.core.model.production.BuildingType.HERB_GARDEN)
                val emptySlot = slots.firstOrNull { it.assignedDiscipleId.isNullOrEmpty() && it.status == com.xianxia.sect.core.model.production.ProductionSlotStatus.IDLE }
                if (emptySlot != null) {
                    productionSlotRepository.updateSlotByBuildingId("herbGarden", emptySlot.slotIndex) { s ->
                        s.copy(assignedDiscipleId = candidate.id, assignedDiscipleName = candidate.name)
                    }
                    markDiscipleAssigned(candidate.id, DiscipleStatus.IDLE)
                }
            }
        }

        // 3. Alchemy
        if (policies.autoAlchemyFocused || policies.autoAlchemyRootCounts.isNotEmpty()) {
            assignToProductionSlot(
                takeCandidate(policies.autoAlchemyFocused, policies.autoAlchemyRootCounts, policies.autoAlchemyThreshold) { it.pillRefining },
                com.xianxia.sect.core.model.production.BuildingType.ALCHEMY, "alchemy"
            )
        }

        // 4. Forge
        if (policies.autoForgeFocused || policies.autoForgeRootCounts.isNotEmpty()) {
            assignToProductionSlot(
                takeCandidate(policies.autoForgeFocused, policies.autoForgeRootCounts, policies.autoForgeThreshold) { it.artifactRefining },
                com.xianxia.sect.core.model.production.BuildingType.FORGE, "forge"
            )
        }
    }

    private suspend fun assignToProductionSlot(
        candidate: Disciple?, type: com.xianxia.sect.core.model.production.BuildingType, buildingId: String
    ) {
        if (candidate == null) return
        val slots = productionSlotRepository.getSlotsByType(type)
        val emptySlot = slots.firstOrNull { it.assignedDiscipleId.isNullOrEmpty() && it.status == com.xianxia.sect.core.model.production.ProductionSlotStatus.IDLE }
        if (emptySlot != null) {
            productionSlotRepository.updateSlotByBuildingId(buildingId, emptySlot.slotIndex) { s ->
                s.copy(assignedDiscipleId = candidate.id, assignedDiscipleName = candidate.name)
            }
            markDiscipleAssigned(candidate.id, DiscipleStatus.IDLE)
        }
    }

    private fun markDiscipleAssigned(discipleId: String, status: DiscipleStatus) {
        currentDisciples = currentDisciples.map { d ->
            if (d.id == discipleId) d.copy(status = status) else d
        }
    }

    private fun isDiscipleFollowed(d: Disciple): Boolean {
        return d.statusData["followed"] == "true"
    }

    /**
     * Process spirit mine production
     */
    internal fun processSpiritMineProduction() {
        val data = currentGameData
        val minerCount = data.spiritMineSlots.count { it.discipleId.isNotEmpty() }
        val baseOutput = GameConfig.Production.SPIRIT_MINE_BASE_OUTPUT_PER_MINER

        // 采矿属性加成：高于阈值时每点+2%
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

        // 人均采矿加成
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

        // 矿工每月忠诚-1
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
            val bonusParts = buildList {
                if (avgMiningBonus > 0) add("采矿加成+${(avgMiningBonus * 100).toInt()}%")
                if (deaconBonus > 0) add("执事加成+${(deaconBonus * 100).toInt()}%")
                if (boostMultiplier > 1.0) add("政策加成+${((boostMultiplier - 1.0) * 100).toInt()}%")
            }
            val bonusText = if (bonusParts.isNotEmpty()) "（${bonusParts.joinToString("，")}）" else ""
        }
    }

    /**
     * 年度薪水结算：一次性结算 12 个月（或从上次结算至今的累计月份）。
     * 由年度结算（processYearlyEvents）触发。
     */
    internal fun processSalaryYearly(year: Int) {
        val data = currentGameData
        val salaryConfig = data.monthlySalary
        val enabledConfig = data.monthlySalaryEnabled
        val maxLoyalty = GameConfig.Disciple.MAX_LOYALTY
        var totalCost = 0L
        var anyChanged = false

        val updatedDisciples = currentDisciples.map { disciple ->
            if (!disciple.isAlive || enabledConfig[disciple.realm] != true) return@map disciple

            val lastYear = lastSalaryYear[disciple.id] ?: (year - 1)
            if (lastYear >= year) return@map disciple  // 已结算过

            val elapsedYears = year - lastYear
            val yearlySalary = (salaryConfig[disciple.realm] ?: 0).toLong() * 12 * elapsedYears
            if (yearlySalary <= 0) return@map disciple

            // 满忠诚度跳过
            if (disciple.skills.loyalty >= maxLoyalty) return@map disciple

            if (data.spiritStones >= totalCost + yearlySalary) {
                totalCost += yearlySalary
                anyChanged = true
                disciple.copyWith(
                    storageBagSpiritStones = disciple.equipment.storageBagSpiritStones + yearlySalary,
                    salaryPaidCount = disciple.skills.salaryPaidCount + 12 * elapsedYears,
                    loyalty = (disciple.skills.loyalty + elapsedYears).coerceAtMost(maxLoyalty)
                )
            } else {
                // 发不起 → 降忠诚
                anyChanged = true
                disciple.copyWith(
                    salaryMissedCount = disciple.skills.salaryMissedCount + 12 * elapsedYears,
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

    /**
     * 弟子突破时结算累积薪水（突破前旧境界的薪水，到当前月为止）。
     * 先结算再突破，确保薪水按突破前境界计算。
     */
    fun settleSalaryOnBreakthrough(discipleId: String, currentYear: Int) {
        val disciple = currentDisciples.find { it.id == discipleId && it.isAlive } ?: return
        val data = currentGameData
        val enabledConfig = data.monthlySalaryEnabled
        if (enabledConfig[disciple.realm] != true) return

        val lastYear = lastSalaryYear[discipleId] ?: (currentYear - 1)
        if (lastYear >= currentYear) return  // 今年已结算

        val salaryConfig = data.monthlySalary
        val maxLoyalty = GameConfig.Disciple.MAX_LOYALTY
        if (disciple.skills.loyalty >= maxLoyalty) {
            lastSalaryYear[discipleId] = currentYear
            return
        }

        val elapsedYears = currentYear - lastYear
        val accumulated = (salaryConfig[disciple.realm] ?: 0).toLong() * 12 * elapsedYears
        if (accumulated <= 0) return

        if (data.spiritStones >= accumulated) {
            currentDisciples = currentDisciples.map {
                if (it.id == discipleId) it.copyWith(
                    storageBagSpiritStones = it.equipment.storageBagSpiritStones + accumulated,
                    salaryPaidCount = it.skills.salaryPaidCount + 12 * elapsedYears,
                    loyalty = (it.skills.loyalty + elapsedYears).coerceAtMost(maxLoyalty)
                ) else it
            }
            currentGameData = data.copy(spiritStones = data.spiritStones - accumulated)
        } else {
            currentDisciples = currentDisciples.map {
                if (it.id == discipleId) it.copyWith(
                    salaryMissedCount = it.skills.salaryMissedCount + 12 * elapsedYears,
                    loyalty = (it.skills.loyalty - elapsedYears).coerceAtLeast(0)
                ) else it
            }
        }
        lastSalaryYear[discipleId] = currentYear
    }

    internal fun processResidenceLoyalty() {
        val maxLoyalty = GameConfig.Disciple.MAX_LOYALTY
        val residentIds = currentGameData.residenceSlots.filter { it.isActive }.map { it.discipleId }.toSet()
        currentDisciples = currentDisciples.map { d ->
            if (d.id in residentIds && d.skills.loyalty < maxLoyalty) {
                d.copyWith(loyalty = (d.skills.loyalty + 1).coerceAtMost(maxLoyalty))
            } else d
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
        }

        if (disabledPolicies.isNotEmpty()) {
            currentGameData = currentGameData.copy(sectPolicies = updatedPolicies)
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
                    val discipleSnapshot = disciple.copy()
                    clearDiscipleFromAllSlots(disciple.id)
                    currentDisciples = currentDisciples.filter { it.id != disciple.id }
                    pendingNotification = GameNotification.DiscipleDesertion(discipleSnapshot)
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
            DiscipleStatCalculator.getBaseStats(it).loyalty < GameConfig.LawEnforcementConfig.LOYALTY_THRESHOLD &&
            (currentMonthValue - it.usage.recruitedMonth) >= GameConfig.LawEnforcementConfig.NEW_DISCIPLE_PROTECTION_MONTHS
        }

        val thiefIds = mutableSetOf<String>()
        val warehouses = currentGameData.placedBuildings.filter {
            it.displayName == "仓库"
        }
        val garrisons = currentGameData.warehouseGarrisons

        for (disciple in atRiskDisciples) {
            val stats = DiscipleStatCalculator.getBaseStats(disciple)
            val effectiveMorality = stats.morality
            val theftProb = ((GameConfig.LawEnforcementConfig.MORALITY_THRESHOLD - effectiveMorality) * GameConfig.LawEnforcementConfig.PROB_PER_POINT).coerceIn(0.0, GameConfig.LawEnforcementConfig.MAX_PROB)
            if (Random.nextDouble() < theftProb) {
                val caught = if (warehouses.isNotEmpty()) {
                    val warehouse = warehouses[Random.nextInt(warehouses.size)]
                    val garrison = garrisons.find { it.buildingInstanceId == warehouse.instanceId && it.isActive }
                    if (garrison == null) {
                        false
                    } else {
                        val guardDisciple = currentDisciples.find { it.id == garrison.discipleId }
                        if (guardDisciple == null) {
                            false
                        } else {
                            val thiefStats = DiscipleStatCalculator.getBaseStats(disciple)
                            val guardStats = DiscipleStatCalculator.getBaseStats(guardDisciple)
                            val thiefPower = thiefStats.physicalAttack + thiefStats.magicAttack +
                                    thiefStats.physicalDefense + thiefStats.magicDefense + thiefStats.speed
                            val guardPower = guardStats.physicalAttack + guardStats.magicAttack +
                                    guardStats.physicalDefense + guardStats.magicDefense + guardStats.speed
                            val thiefWinProb = (thiefPower.toDouble() / (thiefPower + guardPower).coerceAtLeast(1)).coerceIn(0.1, 0.9)
                            Random.nextDouble() >= thiefWinProb
                        }
                    }
                } else {
                    Random.nextDouble() < captureRate
                }

                if (caught) {
                    pendingNotification = GameNotification.DiscipleTheftCaught(disciple)
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

                    val loyalty = stats.loyalty
                    val desertionProb = ((GameConfig.LawEnforcementConfig.LOYALTY_THRESHOLD - loyalty) * GameConfig.LawEnforcementConfig.PROB_PER_POINT).coerceIn(0.0, GameConfig.LawEnforcementConfig.MAX_PROB)
                    if (Random.nextDouble() < desertionProb) {
                        thiefIds.add(disciple.id)
                    }

                    pendingNotification = GameNotification.WarehouseTheft(stolenAmount)
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

    private fun updateDiscipleHpMpAfterBattle(battleMembers: List<BattleMemberData>) {
        val survivorIds = battleMembers.filter { it.isAlive }.map { it.id }.toSet()
        team@ for (member in battleMembers) {
            val discipleIndex = currentDisciples.indexOfFirst { it.id == member.id }
            if (discipleIndex < 0) continue@team
            val disciple = currentDisciples[discipleIndex]
            if (!survivorIds.contains(member.id)) {
                currentDisciples = currentDisciples.toMutableList().also { it[discipleIndex] = disciple.copy(isAlive = false, status = DiscipleStatus.DEAD) }
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
                            d.copy(isAlive = false, status = DiscipleStatus.DEAD)
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
                resetCaveExplorationTeamMembersStatus(team)
            }
        }

        val allAITeams = data.aiCaveTeams.toMutableList()
        activeCaves.forEach { cave ->
            val currentTeams = allAITeams.count {
                it.caveId == cave.id && it.status == AITeamStatus.EXPLORING
            }

            if (currentTeams < 3 && Random.nextDouble() < 0.7) {
                val nearbySects = findNearbySects(cave, 400f)
                val existingTeamForCave = allAITeams.filter { it.caveId == cave.id }

                val aiTeam = generateAITeamInline(cave, nearbySects, existingTeamForCave)
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
            resetCaveExplorationTeamMembersStatus(team)
            finalExplorationTeams.removeAll { it.id == team.id }
        }

        teamsWithError.forEach { team ->
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
            cultivatorCaves = finalCaves,
            aiCaveTeams = finalAITeams,
            caveExplorationTeams = finalExplorationTeams,
            worldMapSects = updatedSectsForAI,
            sectDetails = updatedSectDetails
        )
    }

    private fun generateAITeamInline(
        cave: CultivatorCave,
        nearbySects: List<WorldSect>,
        existingTeams: List<AICaveTeam>
    ): AICaveTeam? {
        val availableSects = nearbySects.filter { sect ->
            existingTeams.none { it.sectId == sect.id }
        }
        if (availableSects.isEmpty()) return null
        val sect = availableSects.random()
        return AICaveTeam(
            caveId = cave.id,
            sectId = sect.id,
            sectName = sect.name,
            memberCount = (3..5).random(),
            avgRealm = cave.ownerRealm,
            avgRealmName = cave.ownerRealmName
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
                    handleDiscipleDeath(disciple, isOutsideSect = true)
                    disciple.copy(isAlive = false, status = DiscipleStatus.DEAD)
                }
            } else disciple
        }

        // 4. 根据战斗结果处理
        if (!battleResult.victory) {
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

        currentDisciples = currentDisciples.map { disciple ->
            if (disciple.id in survivorIds && disciple.isAlive) {
                disciple.copyWith(soulPower = disciple.soulPower + 1)
            } else {
                disciple
            }
        }

        val rewards = CaveExplorationSystem.generateVictoryRewards(cave)

        val battleRewardItems = mutableListOf<BattleRewardItem>()
        rewards.items.forEach { reward ->
            when (reward.type) {
                "spiritStones" -> {
                    currentGameData = currentGameData.copy(
                        spiritStones = currentGameData.spiritStones + reward.quantity.toLong()
                    )
                    battleRewardItems.add(BattleRewardItem(
                        itemId = reward.itemId,
                        name = reward.name,
                        quantity = reward.quantity,
                        rarity = reward.rarity,
                        type = reward.type
                    ))
                }
                "equipment" -> {
                    val template = EquipmentDatabase.getById(reward.itemId)
                    if (template != null) {
                        val equipment = EquipmentDatabase.createFromTemplate(template).copy(
                            rarity = reward.rarity,
                            quantity = reward.quantity
                        )
                        val result = inventorySystem.addEquipmentStack(equipment)
                        if (result == AddResult.SUCCESS || result == AddResult.PARTIAL_SUCCESS) {
                            battleRewardItems.add(BattleRewardItem(
                                itemId = reward.itemId,
                                name = reward.name,
                                quantity = reward.quantity,
                                rarity = reward.rarity,
                                type = reward.type
                            ))
                        }
                    }
                }
                "manual" -> {
                    val template = ManualDatabase.getById(reward.itemId)
                    if (template != null) {
                        val manual = ManualDatabase.createFromTemplate(template).copy(
                            rarity = reward.rarity,
                            quantity = reward.quantity
                        )
                        val result = inventorySystem.addManualStack(manual)
                        if (result == AddResult.SUCCESS || result == AddResult.PARTIAL_SUCCESS) {
                            battleRewardItems.add(BattleRewardItem(
                                itemId = reward.itemId,
                                name = reward.name,
                                quantity = reward.quantity,
                                rarity = reward.rarity,
                                type = reward.type
                            ))
                        }
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
                            effects = PillEffect(
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
                                moralityAdd = template.moralityAdd
                            ),
                            minRealm = GameConfig.Realm.getMinRealmForRarity(template.rarity)
                        )
                        val result = inventorySystem.addPill(pill)
                        if (result == AddResult.SUCCESS || result == AddResult.PARTIAL_SUCCESS) {
                            battleRewardItems.add(BattleRewardItem(
                                itemId = reward.itemId,
                                name = reward.name,
                                quantity = reward.quantity,
                                rarity = reward.rarity,
                                type = reward.type
                            ))
                        }
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
                    isAlive = member.isAlive,
                    portraitRes = member.portraitRes
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
                    isAlive = enemy.isAlive,
                    portraitRes = enemy.portraitRes
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

        // 设置战斗结算数据（battleRewardItems 已在上方循环中构建，仅包含实际成功入库的物品）
        stateStore.setPendingBattleResult(BattleResultUIData(
            battleLogId = battleLog.id,
            victory = battleResult.victory,
            teamMembers = battleLog.teamMembers,
            rewards = battleRewardItems
        ))

        com.xianxia.sect.taptap.TapDBManager.trackEvent(
            "battle_end",
            mapOf(
                "outcome" to if (battleResult.victory) "win" else "lose",
                "enemy_type" to cave.name,
                "turns" to battleResult.turnCount,
                "team_size" to battleResult.log.teamMembers.size
            )
        )

        // 6. 返回结果：标记洞府为已探索，移除相关AI队伍
        val updatedCaves = currentGameData.cultivatorCaves.map { c ->
            if (c.id == cave.id) c.copy(status = CaveStatus.EXPLORED) else c
        }
        val updatedAITeams = currentAITeams.filter { it.caveId != cave.id }

        return Triple(updatedCaves, updatedAITeams, true)
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
    private fun checkGameOverCondition() {
        if (currentGameData.isGameOver) return

        val playerSect = currentGameData.worldMapSects.find { it.isPlayerSect } ?: return
        val playerSectId = playerSect.id

        val playerControlsAnySect = currentGameData.worldMapSects.any { sect ->
            (sect.isPlayerSect && sect.occupierSectId.isEmpty()) ||
            (sect.occupierSectId == playerSectId && !sect.isPlayerSect)
        }

        if (!playerControlsAnySect) {
            currentGameData = currentGameData.copy(isGameOver = true)
        }
    }

    private fun processAISectOperations(year: Int, month: Int) {
        val data = currentGameData
        val aiDisciples = data.aiSectDisciples

        val cleanedSectDetails = data.sectDetails.mapValues { (sectId, detail) ->
            val sect = data.worldMapSects.find { it.id == sectId }
            if (sect != null && !sect.isPlayerSect && detail.warehouse.items.isNotEmpty()) {
                detail.copy(warehouse = SectWarehouse())
            } else {
                detail
            }
        }
        currentGameData = data.copy(sectDetails = cleanedSectDetails)

        val updatedAiDisciples = aiDisciples.mapValues { (sectId, disciples) ->
            val sect = data.worldMapSects.find { it.id == sectId }
            if (sect == null || sect.isPlayerSect) return@mapValues disciples

            AISectDiscipleManager.processMonthlyCultivation(disciples)
        }

        currentGameData = currentGameData.copy(aiSectDisciples = updatedAiDisciples)

        if (month == 1) {
            processSectDisciplesYearlyRecruitment(year)
        }

        processAISectAttackDecisions()

        // 月度补全 AI 占领宗门的 garrison 空槽
        currentGameData = AISectGarrisonManager.fillEmptyGarrisonSlots(currentGameData)
    }

    private fun processSectDisciplesYearlyRecruitment(year: Int) {
        val data = currentGameData
        var updatedAiDisciples = data.aiSectDisciples.toMutableMap()
        var updatedRecruitList = data.recruitList

        for ((sectId, disciples) in data.aiSectDisciples) {
            val sect = data.worldMapSects.find { it.id == sectId } ?: continue
            if (sect.isPlayerSect) continue

            val newRecruits = AISectDiscipleManager.generateYearlyRecruits(sect.name, disciples)
            when {
                sect.isPlayerOccupied -> {
                    // 被玩家占领：新弟子进招募列表
                    updatedRecruitList = updatedRecruitList + newRecruits
                }
                sect.occupierSectId.isNotEmpty() -> {
                    // 被AI占领：新弟子加入占领者宗门
                    val occupierDisciples = updatedAiDisciples[sect.occupierSectId] ?: emptyList()
                    updatedAiDisciples[sect.occupierSectId] = occupierDisciples + newRecruits
                }
                else -> {
                    // 未占领：加入自身池
                    updatedAiDisciples[sectId] = disciples + newRecruits
                }
            }
        }
        currentGameData = data.copy(aiSectDisciples = updatedAiDisciples, recruitList = updatedRecruitList)
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
     * Process AI sect attack decisions — battles resolve immediately.
     */
    private fun processAISectAttackDecisions() {
        val data = currentGameData

        // Process AI vs AI attacks
        val aiResults = AISectAttackManager.decideAttacks(data)
        for (result in aiResults) {
            applyAIAttackResult(result)
        }

        // Process AI vs player attack
        val playerAttack = AISectAttackManager.decidePlayerAttack(data)
        if (playerAttack != null) {
            applyPlayerDefenseResult(playerAttack)
        }
    }

    private fun applyAIAttackResult(result: AISectAttackManager.AIAttackResult) {
        // Apply attacker casualties
        val attackerDisciples = currentGameData.aiSectDisciples[result.attackerSectId] ?: emptyList()
        val updatedAttackerDisciples = attackerDisciples.filter { it.id !in result.deadAttackerIds }

        // Apply defender casualties
        val defenderDisciples = currentGameData.aiSectDisciples[result.defenderSectId] ?: emptyList()
        val updatedDefenderDisciples = defenderDisciples.filter { it.id !in result.deadDefenderIds }

        var updatedData = currentGameData.copy(
            aiSectDisciples = currentGameData.aiSectDisciples.toMutableMap().apply {
                this[result.attackerSectId] = updatedAttackerDisciples
                this[result.defenderSectId] = updatedDefenderDisciples
            }
        )

        // Update relations
        val updatedRelations = updatedData.sectRelations.map { relation ->
            val isRelevantRelation = (relation.sectId1 == result.attackerSectId && relation.sectId2 == result.defenderSectId) ||
                    (relation.sectId1 == result.defenderSectId && relation.sectId2 == result.attackerSectId)
            if (isRelevantRelation) {
                relation.copy(favor = (relation.favor - 10).coerceIn(GameConfig.Diplomacy.MIN_FAVOR, GameConfig.Diplomacy.MAX_FAVOR))
            } else {
                relation
            }
        }
        updatedData = updatedData.copy(sectRelations = updatedRelations)

        // Handle occupation
        if (result.winner == AIBattleWinner.ATTACKER && result.canOccupy) {
            // 防御方存活弟子并入攻击方
            val mergedAttackerDisciples = updatedAttackerDisciples + updatedDefenderDisciples
            // 攻打队伍占领后直接变成守军队伍
            val garrisonSlots = (0 until 10).map { index ->
                if (index < result.survivingAttackers.size) {
                    val d = result.survivingAttackers[index]
                    GarrisonSlot(
                        index = index,
                        discipleId = d.id,
                        discipleName = d.name,
                        discipleRealm = d.realmName,
                        discipleSpiritRootColor = d.spiritRoot.countColor,
                        portraitRes = d.portraitRes
                    )
                } else {
                    GarrisonSlot(index = index)
                }
            }
            updatedData = updatedData.copy(
                worldMapSects = updatedData.worldMapSects.map { sect ->
                    if (sect.id == result.defenderSectId) {
                        sect.copy(
                            occupierSectId = result.attackerSectId,
                            garrisonSlots = garrisonSlots
                        )
                    } else {
                        sect
                    }
                },
                aiSectDisciples = updatedData.aiSectDisciples.toMutableMap().apply {
                    this[result.attackerSectId] = mergedAttackerDisciples
                    this[result.defenderSectId] = emptyList()
                }
            )
        }

        currentGameData = updatedData
    }

    private fun applyPlayerDefenseResult(result: AISectAttackManager.AIAttackResult) {
        // Apply attacker casualties
        val attackerDisciples = currentGameData.aiSectDisciples[result.attackerSectId] ?: emptyList()
        val updatedAttackerDisciples = attackerDisciples.filter { it.id !in result.deadAttackerIds }

        // 亲人逝世影响：为阵亡弟子的存活亲属设置悲痛期
        val deadDefenders = currentDisciples.filter { it.id in result.deadDefenderIds }
        if (deadDefenders.isNotEmpty()) {
            currentDisciples = DiscipleStatCalculator.applyGriefToRelatives(
                currentDisciples, deadDefenders, currentGameData.gameYear
            )
        }

        // Apply player defender casualties
        currentDisciples = currentDisciples.map { d ->
            if (d.id in result.deadDefenderIds) d.copy(isAlive = false, status = DiscipleStatus.DEAD) else d
        }

        var updatedData = currentGameData.copy(
            aiSectDisciples = currentGameData.aiSectDisciples.toMutableMap().apply {
                this[result.attackerSectId] = updatedAttackerDisciples
            }
        )

        // Update relations
        val playerSectId = updatedData.worldMapSects.find { it.isPlayerSect }?.id ?: return
        val updatedRelations = updatedData.sectRelations.map { relation ->
            val isRelevantRelation = (relation.sectId1 == result.attackerSectId && relation.sectId2 == playerSectId) ||
                    (relation.sectId1 == playerSectId && relation.sectId2 == result.attackerSectId)
            if (isRelevantRelation) {
                relation.copy(favor = (relation.favor - 15).coerceIn(GameConfig.Diplomacy.MIN_FAVOR, GameConfig.Diplomacy.MAX_FAVOR))
            } else {
                relation
            }
        }
        updatedData = updatedData.copy(sectRelations = updatedRelations)

        // Handle player defeat — warehouse loot loss
        if (result.winner == AIBattleWinner.ATTACKER) {
            val playerDetail = updatedData.sectDetails[playerSectId] ?: SectDetail(sectId = playerSectId)
            val lootResult = SectWarehouseManager.calculateWarehouseLootLoss(playerDetail.warehouse)
            val updatedWarehouse = SectWarehouseManager.applyLootLossToWarehouse(playerDetail.warehouse, lootResult)
            updatedData = updatedData.copy(
                sectDetails = updatedData.sectDetails.toMutableMap().apply {
                    this[playerSectId] = playerDetail.copy(warehouse = updatedWarehouse)
                }
            )
        }

        currentGameData = updatedData
    }

    /**
     * Helper methods that will be implemented by delegating to other services or kept here
     */

    private fun calculateBuildingCultivationBonus(disciple: Disciple, data: GameData): Double {
        val slot = data.residenceSlots.firstOrNull { it.discipleId == disciple.id } ?: return 1.0
        val building = data.placedBuildings.firstOrNull { it.instanceId == slot.buildingInstanceId } ?: return 1.0
        return when (building.displayName) {
            "中级单人住所" -> 1.40
            "单人住所" -> 1.20
            "多人住所" -> 1.10
            else -> 1.0
        }
    }

    private suspend fun clearDiscipleFromAllSlots(discipleId: String) {
        currentGameData = DiscipleSlotCleanup.clearAllSlots(currentGameData, discipleId)

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
        val data = currentGameData
        val playerSect = data.worldMapSects.find { it.isPlayerSect } ?: return
        val playerSectId = playerSect.id
        val updatedRelations = data.sectRelations.toMutableList()
        var relationsChanged = false

        val allEvents = DiplomaticEventConfig.Events.ALL_EVENTS

        for (relation in data.sectRelations) {
            if (Random.nextDouble() >= DiplomaticEventConfig.MONTHLY_TRIGGER_CHANCE) continue

            val involvesPlayer = relation.sectId1 == playerSectId || relation.sectId2 == playerSectId
            val sect1 = data.worldMapSects.find { it.id == relation.sectId1 }
            val sect2 = data.worldMapSects.find { it.id == relation.sectId2 }
            if (sect1 == null || sect2 == null) continue

            val isSameAlignment = sect1.isRighteous == sect2.isRighteous
            val isAllied = sect1.allianceId.isNotEmpty() && sect1.allianceId == sect2.allianceId

            val eligibleEvents = allEvents.filter { event ->
                when {
                    event.requiresPlayer && !involvesPlayer -> false
                    event.requiresSameAlignment && !isSameAlignment -> false
                    event.requiresOpposingAlignment && isSameAlignment -> false
                    event.requiresAlliance && !isAllied -> false
                    else -> true
                }
            }

            if (eligibleEvents.isEmpty()) continue

            val eventDef = eligibleEvents.random()
            val favorChange = eventDef.favorChange
            val newFavor = (relation.favor + favorChange).coerceIn(GameConfig.Diplomacy.MIN_FAVOR, GameConfig.Diplomacy.MAX_FAVOR)

            val index = updatedRelations.indexOfFirst { it.sectId1 == relation.sectId1 && it.sectId2 == relation.sectId2 }
            if (index >= 0) {
                updatedRelations[index] = updatedRelations[index].copy(favor = newFavor)
                relationsChanged = true
            }

        }

        if (relationsChanged) {
            currentGameData = data.copy(sectRelations = updatedRelations.toList())
        }
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
        val recruitCount = Random.nextInt(0, 7)
        val newRecruitDisciples = mutableListOf<Disciple>()
        val usedNames = (currentDisciples + currentGameData.recruitList).map { it.name }.toMutableSet()
        repeat(recruitCount) {
            val gender = if (Random.nextBoolean()) "male" else "female"
            val nameResult = NameService.generateName(gender, NameService.NameStyle.FULL, usedNames)
            val spiritRootType = SpiritRootGenerator.generate(Random)
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
                portraitRes = PortraitPool.getRandomPortrait(gender),
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
                    mining = Random.nextInt(1, 101),
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

        // 自动招募：始终根据玩家的灵根筛选自动接收符合条件的弟子
        val filter = currentGameData.autoRecruitSpiritRootFilter
        val (autoRecruits, manualRecruits) = newRecruitDisciples.partition { disciple ->
            val rootCount = disciple.spiritRootType.split(",").size
            rootCount in filter
        }
        if (autoRecruits.isNotEmpty()) {
            val currentMonthIndex = year * 12 + 1
            autoRecruits.forEach { it.recruitedMonth = currentMonthIndex }
            currentDisciples = currentDisciples + autoRecruits
            newRecruitDisciples.clear()
            newRecruitDisciples.addAll(manualRecruits)
            Log.i(TAG, "autoRecruit: auto-recruited ${autoRecruits.size} disciples, ${manualRecruits.size} left for manual review")
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

            disciple.copy(
                status = DiscipleStatus.IDLE,
                statusData = disciple.statusData - "reflectionStartYear" - "reflectionEndYear",
                skills = disciple.skills.copy(
                    morality = disciple.skills.morality + 5,
                    loyalty = disciple.skills.loyalty + 5
                )
            )
        }

        currentDisciples = updatedDisciples

        val releasedCount = reflectingDisciples.count { it.statusData["reflectionEndYear"]?.toIntOrNull()?.let { year >= it } == true }
        if (releasedCount > 0) {
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
