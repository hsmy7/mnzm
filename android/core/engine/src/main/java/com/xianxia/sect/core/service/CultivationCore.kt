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



}
