package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.engine.domain.disciple.DisciplePillManager
import com.xianxia.sect.core.engine.domain.disciple.PillRule
import com.xianxia.sect.core.engine.domain.disciple.DiscipleEquipmentManager
import com.xianxia.sect.core.engine.domain.disciple.DiscipleManualManager
import com.xianxia.sect.core.engine.ManualProficiencySystem
import com.xianxia.sect.core.engine.EquipmentNurtureSystem
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.perf.ThermalMonitor
import com.xianxia.sect.core.engine.system.GameTimeClock
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.concurrent.DeviceCapabilityProfiler
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.util.DomainLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 单旬 tick 时间与倍率参数。
 *
 * @property year 当前游戏年
 * @property month 当前游戏月
 * @property phase 当前游戏旬
 * @property multiplier HP/MP 恢复倍率
 * @property decay 持续效果衰减旬数
 */
data class TickTimeContext(
    val year: Int,
    val month: Int,
    val phase: Int,
    val multiplier: Double,
    val decay: Int
)

/**
 * 单旬 tick 装备领域参数。
 *
 * @property instanceMap 装备实例映射（id → EquipmentInstance）
 * @property stacks 装备堆叠列表
 * @property maxStack 装备堆叠数量上限
 */
data class TickEquipContext(
    val instanceMap: Map<String, EquipmentInstance>,
    val stacks: List<EquipmentStack>,
    val maxStack: Int
)

/**
 * 单旬 tick 功法领域参数。
 *
 * @property instanceMap 功法实例映射（id → ManualInstance）
 * @property proficienciesMap 弟子功法熟练度映射（discipleId → proficiencyList）
 * @property stacks 功法堆叠列表
 * @property maxStack 功法堆叠数量上限
 */
data class TickManualContext(
    val instanceMap: Map<String, ManualInstance>,
    val proficienciesMap: Map<String, List<ManualProficiencyData>>,
    val stacks: List<ManualStack>,
    val maxStack: Int
)

/**
 * 单旬 tick 跨弟子共享状态。
 *
 * 在批量 tick 循环中仅读取一次，所有弟子共享同一份引用，
 * 避免每个弟子独立获取 [HighFrequencyData] 等高开销对象。
 *
 * @property cachedCultivationRates 缓存的修炼速率映射
 * @property highFrequencyData 高频更新数据
 * @property autoEquipDirty 自动装备脏标记集合
 * @property autoLearnDirty 自动学习脏标记集合
 */
data class TickSharedContext(
    val cachedCultivationRates: Map<String, Double>,
    val highFrequencyData: HighFrequencyData,
    val autoEquipDirty: java.util.concurrent.ConcurrentHashMap.KeySetView<String, Boolean>,
    val autoLearnDirty: java.util.concurrent.ConcurrentHashMap.KeySetView<String, Boolean>
)

/**
 * [CultivationCore.processDiscipleTick] 的参数集合。
 *
 * 将原 19 个独立参数按领域分组为嵌套上下文对象，
 * 满足编码规范 §3.4（类构造参数 ≤7）。
 *
 * @property disciple 待处理的弟子
 * @property time 游戏时间与倍率参数
 * @property equip 装备领域参数
 * @property manual 功法领域参数
 * @property shared 跨弟子共享状态
 * @property acc 单旬累积器，用于收集装备/功法变更
 */
data class DiscipleTickParams(
    val disciple: Disciple,
    val time: TickTimeContext,
    val equip: TickEquipContext,
    val manual: TickManualContext,
    val shared: TickSharedContext,
    val acc: PhaseTickAccumulator
)

@Singleton
@GameService("CultivationCore")
class CultivationCore @Inject constructor(
    private val stateStore: GameStateStore,
    private val inventoryConfig: InventoryConfig,
    private val thermalMonitor: ThermalMonitor,
    private val gameClock: GameTimeClock,
    private val scopeProvider: CoroutineScopeProvider,
    private val pillManager: DisciplePillManager,
    private val equipmentManager: DiscipleEquipmentManager,
    private val manualManager: DiscipleManualManager,
    private val profiler: DeviceCapabilityProfiler = DeviceCapabilityProfiler(),
    private val hpMpRecoveryService: HpMpRecoveryService,
    private val autoPillService: AutoPillService,
    private val equipmentNurtureService: EquipmentNurtureService,
    private val manualProficiencyService: ManualProficiencyService,
    private val cultivationRateCalculator: CultivationRateCalculator,
    private val battleSettlementService: BattleSettlementService
) {

    val phaseMultiplier: Int get() = 10

    // ── 委托到子服务的方法 ────────────────────────────────────
    fun calculateDiscipleCultivationPerPhase(disciple: Disciple, data: GameData, tables: DiscipleTables): Double =
        cultivationRateCalculator.calculateDiscipleCultivationPerPhase(disciple, data, tables)

    fun getLifespanGainForRealm(realm: Int): Int = cultivationRateCalculator.getLifespanGainForRealm(realm)

    fun isDiscipleFullHpMp(disciple: Disciple): Boolean = hpMpRecoveryService.isDiscipleFullHpMp(disciple)

    fun isDiscipleFullHpMp(id: Int, tables: DiscipleTables): Boolean = hpMpRecoveryService.isDiscipleFullHpMp(id, tables)

    fun recoverHpMpForAllDisciples(state: MutableGameState, phasesToSettle: Int = 3) =
        hpMpRecoveryService.recoverHpMpForAllDisciples(state, phasesToSettle)

    fun recoverMonthlyHpMp(tables: DiscipleTables, id: Int, focusedPhaseCount: Int = 0,
        zones: RecoveryZones = RecoveryZones()
    ) = hpMpRecoveryService.recoverMonthlyHpMp(tables, id, focusedPhaseCount, zones)

    fun applyMonthlyDurationDecay(tables: DiscipleTables, id: Int, focusedPhaseCount: Int = 0) =
        hpMpRecoveryService.applyMonthlyDurationDecay(tables, id, focusedPhaseCount)

    fun recoverHpMpForBattleParticipants(state: MutableGameState, discipleIds: List<String>,
        zones: RecoveryZones = RecoveryZones()
    ) = hpMpRecoveryService.recoverHpMpForBattleParticipants(state, discipleIds, zones)

    fun processRealtimeAutoPills(state: MutableGameState, year: Int, month: Int, phase: Int) =
        autoPillService.processRealtimeAutoPills(state, year, month, phase)

    fun forceSettleDisciplesBeforeBattle(state: MutableGameState, discipleIds: List<String>) =
        battleSettlementService.forceSettleDisciplesBeforeBattle(state, discipleIds)

    // ── 每旬熟练度 + 孕养增长 ────────────────────────────────

    /**
     * 每旬功法熟练度增长。
     *
     * 对所有存活且有功法装备的弟子，结算1旬的熟练度增长。
     * 全部使用列级直读（`manualIds`、`comprehensions`），不调用 `assemble()`。
     * 同时清理已替换/遗忘功法的残留熟练度条目（防僵尸条目累积）。
     *
     * @param state 可变游戏状态
     */
    fun processManualProficiencyPerPhase(state: MutableGameState) {
        val tables = state.discipleTables
        val manualMap = state.manualInstances.associateBy { it.id }
        val data = state.gameData
        val maxProf = ManualProficiencySystem.MAX_PROFICIENCY.toInt()
        var updatedProficiencies = data.manualProficiencies.toMutableMap()

        for (id in tables.ids) {
            if (tables.isAlive[id] != 1) continue
            val manualIds = tables.manualIds.getOrDefault(id, emptyList())
            if (manualIds.isEmpty()) continue

            val discipleId = id.toString()
            val comprehension = tables.comprehensions.getOrDefault(id, 0)
            val inLibrary = data.librarySlots.any { it.discipleId == discipleId }
            val libraryBonus = if (inLibrary)
                ManualProficiencySystem.LIBRARY_PROFICIENCY_BONUS_RATE else 0.0
            val profGain = ManualProficiencySystem.calculateProficiencyGainPerPhase(
                comprehension, libraryBonus
            )
            if (profGain <= 0.0) continue

            val profList = updatedProficiencies
                .getOrDefault(discipleId, emptyList())
                .toMutableList()

            for (manualId in manualIds) {
                manualMap[manualId]?.let { manual ->
                    val idx = profList.indexOfFirst { it.manualId == manualId }
                    if (idx >= 0) {
                        val cp = profList[idx]
                        val newProf = (cp.proficiency + profGain)
                            .coerceAtMost(maxProf.toDouble())
                        if (newProf != cp.proficiency) {
                            profList[idx] = cp.copy(
                                proficiency = newProf,
                                masteryLevel = ManualProficiencySystem.MasteryLevel
                                    .fromProficiency(newProf).level
                            )
                        }
                    } else {
                        profList.add(ManualProficiencyData(
                            manualId = manualId, manualName = manual.name,
                            proficiency = profGain.coerceAtMost(maxProf.toDouble()),
                            maxProficiency = maxProf,
                            masteryLevel = ManualProficiencySystem.MasteryLevel
                                .fromProficiency(profGain).level
                        ))
                    }
                }
            }

            // ★ 清理已替换/遗忘功法的残留熟练度，防止僵尸条目累积
            val currentSet = manualIds.toSet()
            profList.removeAll { it.manualId !in currentSet }
            updatedProficiencies[discipleId] = profList
        }

        if (updatedProficiencies != data.manualProficiencies) {
            state.gameData = data.copy(manualProficiencies = updatedProficiencies)
        }
    }

    /**
     * 每旬装备孕养经验增长。
     *
     * 对所有存活且有装备的弟子，结算1旬的装备孕养经验增长。
     * 无需 `assemble`，通过 `tables.weaponIds/armorIds/bootsIds/accessoryIds` 列级直读装备 ID。
     *
     * @param state 可变游戏状态
     */
    fun processEquipmentNurturePerPhase(state: MutableGameState) {
        val tables = state.discipleTables
        val equipmentMap = state.equipmentInstances.associateBy { it.id }
        val equipmentUpdates = mutableMapOf<String, EquipmentInstance>()

        for (id in tables.ids) {
            if (tables.isAlive[id] != 1) continue
            equipmentNurtureService.settleNurtureInPlace(
                id = id, tables = tables, equipmentMap = equipmentMap,
                nurtureGainPerPhase = EquipmentNurtureSystem.NURTURE_GAIN_PER_PHASE,
                phasesToSettle = 1, equipmentUpdates = equipmentUpdates
            )
        }

        if (equipmentUpdates.isNotEmpty()) {
            state.equipmentInstances = state.equipmentInstances.map { eq ->
                equipmentUpdates[eq.id] ?: eq
            }
        }
    }

    // ── 核心 tick + 累积器（保留原生实现） ─────────────────────
    fun processDiscipleTick(params: DiscipleTickParams): Disciple {
        var d = params.disciple

        if (d.cultivationSpeedDuration > 0) {
            val newDuration = d.cultivationSpeedDuration - params.time.decay
            if (newDuration <= 0) {
                d = d.copy(cultivationSpeedBonus = 0.0, cultivationSpeedDuration = 0)
            } else {
                d = d.copy(cultivationSpeedDuration = newDuration)
            }
        }

        if (d.pillEffects.pillEffectDuration > 0) {
            val newDuration = d.pillEffects.pillEffectDuration - params.time.decay
            if (newDuration <= 0) {
                d = d.copy(pillEffects = PillEffects())
            } else {
                d = d.copy(pillEffects = d.pillEffects.copy(
                    pillEffectDuration = newDuration
                ))
            }
        }

        val discipleProficiencies = params.manual.proficienciesMap
            .getOrDefault(d.id, emptyList())
            .associateBy { it.manualId }
        val finalStats = DiscipleStatCalculator.getFinalStats(
            d, params.equip.instanceMap, params.manual.instanceMap, discipleProficiencies
        )
        val maxHp = finalStats.maxHp
        val maxMp = finalStats.maxMp
        val curHp = d.combat.currentHp
        val curMp = d.combat.currentMp

        val recoveryZones = RecoveryZones()
        val hpRecovery = recoveryZones.calculateRecovery(maxHp, params.time.multiplier)
        val mpRecovery = recoveryZones.calculateRecovery(maxMp, params.time.multiplier)

        val newHp = if (curHp < 0) curHp else (curHp + hpRecovery).coerceAtMost(maxHp)
        val newMp = if (curMp < 0) curMp else (curMp + mpRecovery).coerceAtMost(maxMp)

        if (newHp != curHp || newMp != curMp) {
            d = d.copy(combat = d.combat.copy(currentHp = newHp, currentMp = newMp))
        }

        val hasEquipmentInBag = d.equipment.storageBagItems.any { it.itemType == "equipment" }
        val needEquipCheck = hasEquipmentInBag || d.id in params.shared.autoEquipDirty
        val equipResult = if (needEquipCheck) {
            equipmentManager.processAutoEquip(
                disciple = d,
                equipmentStacks = params.equip.stacks,
                equipmentInstances = params.equip.instanceMap,
                gameYear = params.time.year,
                gameMonth = params.time.month,
                gamePhase = params.time.phase,
                maxStack = params.equip.maxStack
            )
        } else null
        if (equipResult != null && equipResult.newInstances.isNotEmpty()) {
            d = equipResult.disciple
            params.acc.equipInstancesToAdd.addAll(equipResult.newInstances)
            equipResult.replacedInstances.forEach { params.acc.equipInstanceIdsToRemove.add(it.id) }
            equipResult.stackUpdates.forEach { update ->
                if (update.isDeletion) {
                    params.acc.equipStackDeletions.add(update.stackId)
                } else {
                    params.acc.equipStackQuantityDeltas.merge(
                        update.stackId, update.newQuantity
                    ) { _, new -> new }
                }
            }
            equipResult.replacedEquipmentStacks.forEach { replacedStack ->
                params.acc.equipStackAdditions.add(replacedStack)
            }
        }

        val hasManualInBag = d.equipment.storageBagItems.any { it.itemType == "manual" }
        val needLearnCheck = hasManualInBag || d.id in params.shared.autoLearnDirty
        val manualResult = if (needLearnCheck) {
            manualManager.processAutoLearn(
                disciple = d,
                manualStacks = params.manual.stacks,
                manualInstances = params.manual.instanceMap,
                gameYear = params.time.year,
                gameMonth = params.time.month,
                gamePhase = params.time.phase,
                maxStack = params.manual.maxStack
            )
        } else null
        if (manualResult != null && manualResult.newInstance != null) {
            d = manualResult.disciple
            manualResult.newInstance?.let { params.acc.manualInstancesToAdd.add(it) }
            manualResult.replacedInstance?.let { replaced ->
                params.acc.manualInstanceIdsToRemove.add(replaced.id)
                params.acc.profRemovals.getOrPut(d.id) { mutableSetOf() }.add(replaced.id)
            }
            manualResult.stackUpdate?.let { update ->
                if (update.isDeletion) {
                    params.acc.manualStackDeletions.add(update.stackId)
                } else {
                    params.acc.manualStackQuantityDeltas.merge(
                        update.stackId, update.newQuantity
                    ) { _, new -> new }
                }
            }
            manualResult.replacedManualStack?.let { replacedStack ->
                params.acc.manualStackAdditions.add(replacedStack)
            }
        }

        params.shared.autoEquipDirty.remove(d.id)
        params.shared.autoLearnDirty.remove(d.id)
        return d
    }

    /**
     * 将单旬累积器中的装备/功法变更应用到游戏状态。
     *
     * 处理内容：
     * - 装备实例的增删（equipInstancesToAdd/equipInstanceIdsToRemove）
     * - 装备堆叠的删除、数量更新、新增（受 maxEquipStack 上限约束）
     * - 功法实例的增删（manualInstancesToAdd/manualInstanceIdsToRemove）
     * - 被替换功法对应的熟练度清理（profRemovals）
     * - 功法堆叠的删除、数量更新、新增（受 maxManualStack 上限约束）
     *
     * @param acc 单旬累积器
     * @param state 可变游戏状态
     * @param maxEquipStack 装备堆叠数量上限
     * @param maxManualStack 功法堆叠数量上限
     */
    fun applyAccumulator(acc: PhaseTickAccumulator, state: MutableGameState, maxEquipStack: Int, maxManualStack: Int) {
        if (acc.equipInstancesToAdd.isNotEmpty() || acc.equipInstanceIdsToRemove.isNotEmpty()) {
            state.equipmentInstances = state.equipmentInstances
                .filter { it.id !in acc.equipInstanceIdsToRemove } + acc.equipInstancesToAdd
        }
        if (acc.equipStackDeletions.isNotEmpty()) {
            state.equipmentStacks = state.equipmentStacks.filter { it.id !in acc.equipStackDeletions }
        }
        acc.equipStackQuantityDeltas.forEach { (stackId, newQty) ->
            state.equipmentStacks = state.equipmentStacks.map {
                if (it.id == stackId) it.copy(quantity = newQty.coerceAtMost(maxEquipStack)) else it
            }
        }
        acc.equipStackAdditions.forEach { stack ->
            val existing = state.equipmentStacks.find { it.id == stack.id }
            state.equipmentStacks = if (existing != null) {
                state.equipmentStacks.map {
                    if (it.id == stack.id) it.copy(quantity = (it.quantity + 1).coerceAtMost(maxEquipStack)) else it
                }
            } else {
                state.equipmentStacks + stack
            }
        }

        if (acc.manualInstancesToAdd.isNotEmpty() || acc.manualInstanceIdsToRemove.isNotEmpty()) {
            state.manualInstances = state.manualInstances
                .filter { it.id !in acc.manualInstanceIdsToRemove } + acc.manualInstancesToAdd
        }
        if (acc.profRemovals.isNotEmpty()) {
            val updatedProficiencies = state.gameData.manualProficiencies.toMutableMap()
            acc.profRemovals.forEach { (discipleId, manualIds) ->
                updatedProficiencies[discipleId]?.let { profList ->
                    val filtered = profList.filter { it.manualId !in manualIds }
                    if (filtered.isEmpty()) updatedProficiencies.remove(discipleId)
                    else updatedProficiencies[discipleId] = filtered
                }
            }
            state.gameData = state.gameData.copy(manualProficiencies = updatedProficiencies)
        }
        if (acc.manualStackDeletions.isNotEmpty()) {
            state.manualStacks = state.manualStacks.filter { it.id !in acc.manualStackDeletions }
        }
        acc.manualStackQuantityDeltas.forEach { (stackId, newQty) ->
            state.manualStacks = state.manualStacks.map {
                if (it.id == stackId) it.copy(quantity = newQty.coerceAtMost(maxManualStack)) else it
            }
        }
        acc.manualStackAdditions.forEach { stack ->
            val existing = state.manualStacks.find { it.id == stack.id }
            state.manualStacks = if (existing != null) {
                state.manualStacks.map {
                    if (it.id == stack.id) it.copy(quantity = (it.quantity + 1).coerceAtMost(maxManualStack)) else it
                }
            } else {
                state.manualStacks + stack
            }
        }
    }

}

