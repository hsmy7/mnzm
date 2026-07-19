package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.EquipmentNurtureSystem
import com.xianxia.sect.core.engine.ManualProficiencySystem
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.engine.system.GameTimeClock
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.state.MutableGameState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 战斗前全量追赶结算服务。
 *
 * 职责：
 * - 战斗前 HP/MP 恢复、装备孕养追赶、功法熟练度追赶
 * - 单旬修炼结算
 */
@Singleton
@GameService("BattleSettlementService")
class BattleSettlementService @Inject constructor(
    private val hpMpRecoveryService: HpMpRecoveryService
) {

    /**
     * 战斗前对指定出战弟子执行全量数据追赶结算。
     *
     * 在 [MutableGameState] 事务内调用，仅处理目标弟子（≤10人）。
     * 包含：HP/MP 恢复、装备孕养追赶、功法熟练度追赶。
     * 突破检测由 [CultivationService.forceSettleDisciplesBeforeBattle] 统一编排。
     *
     * @param state 可变游戏状态
     * @param discipleIds 出战弟子 ID 字符串列表
     */
    fun forceSettleDisciplesBeforeBattle(
        state: MutableGameState,
        discipleIds: List<String>
    ) {
        if (discipleIds.isEmpty()) return

        val tables = state.discipleTables
        val data = state.gameData

        // 1. HP/MP 恢复（复用已有方法）
        hpMpRecoveryService.recoverHpMpForBattleParticipants(state, discipleIds)

        // 2. 装备孕养追赶 — 每旬基础孕养经验
        val equipmentMap = state.equipmentInstances.associateBy { it.id }
        val equipmentUpdates = mutableMapOf<String, EquipmentInstance>()
        val nurtureGainPerPhase =
            5.0 * GameTimeClock.MS_PER_PHASE_1X / 1000.0

        for (discipleId in discipleIds) {
            val id = discipleId.toIntOrNull() ?: continue
            if (tables.isAlive[id] != 1) continue

            listOf(
                tables.weaponIds[id],
                tables.armorIds[id],
                tables.bootsIds[id],
                tables.accessoryIds[id]
            ).filter { it.isNotEmpty() }.forEach { eqId ->
                val eq = equipmentMap[eqId] ?: return@forEach
                equipmentUpdates[eqId] =
                    EquipmentNurtureSystem.updateNurtureExp(
                        eq, nurtureGainPerPhase
                    ).equipment
            }
        }

        if (equipmentUpdates.isNotEmpty()) {
            state.equipmentInstances = state.equipmentInstances.map { eq ->
                equipmentUpdates[eq.id] ?: eq
            }
        }

        // 3. 功法熟练度追赶 — 每旬熟练度增长
        val manualMap = state.manualInstances.associateBy { it.id }
        var updatedProficiencies = data.manualProficiencies.toMutableMap()

        for (discipleId in discipleIds) {
            val id = discipleId.toIntOrNull() ?: continue
            if (tables.isAlive[id] != 1) continue

            val disciple = tables.assemble(id)
            if (disciple.manualIds.isEmpty()) continue

            val inLibrary = data.librarySlots.any {
                it.discipleId == disciple.id
            }
            val libraryBonus = if (inLibrary)
                ManualProficiencySystem.LIBRARY_PROFICIENCY_BONUS_RATE
            else 0.0
            val proficiencyGain =
                ManualProficiencySystem.calculateProficiencyGainPerPhase(
                    disciple.skills.comprehension, libraryBonus
                )

            if (proficiencyGain <= 0) continue

            val profList = updatedProficiencies
                .getOrDefault(discipleId, emptyList())
                .toMutableList()
            for (manualId in disciple.manualIds) {
                manualMap[manualId]?.let { manual ->
                    val profIndex = profList.indexOfFirst {
                        it.manualId == manualId
                    }
                    val maxProf =
                        ManualProficiencySystem.MAX_PROFICIENCY.toInt()
                    if (profIndex >= 0) {
                        val cp = profList[profIndex]
                        val newProf = (cp.proficiency + proficiencyGain)
                            .coerceAtMost(maxProf.toDouble())
                        profList[profIndex] = cp.copy(
                            proficiency = newProf,
                            masteryLevel = ManualProficiencySystem
                                .MasteryLevel.fromProficiency(newProf).level
                        )
                    } else {
                        val initProf = proficiencyGain
                            .coerceAtMost(maxProf.toDouble())
                        profList.add(
                            ManualProficiencyData(
                                manualId = manualId,
                                manualName = manual.name,
                                proficiency = initProf,
                                maxProficiency = maxProf,
                                masteryLevel = ManualProficiencySystem
                                    .MasteryLevel.fromProficiency(initProf)
                                    .level
                            )
                        )
                    }
                }
            }
            updatedProficiencies[discipleId] = profList
        }

        if (updatedProficiencies != data.manualProficiencies) {
            state.gameData = data.copy(
                manualProficiencies = updatedProficiencies
            )
        }
    }

    companion object {
        private const val TAG = "BattleSettlementService"
    }
}
