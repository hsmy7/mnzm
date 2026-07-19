package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.ManualProficiencySystem
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualProficiencyData
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 功法熟练度服务。
 *
 * 职责：
 * - 计算单弟子功法熟练度增量
 * - 熟练度增量写入累积映射
 * - 熟练度增益应用（新建或更新）
 */
@Singleton
@GameService("ManualProficiencyService")
class ManualProficiencyService @Inject constructor() {

    /** 纯函数：计算单弟子功法熟练度增量 */
    fun computeProficiencyDelta(
        disciple: Disciple, gameData: GameData,
        manualMap: Map<String, ManualInstance>, phasesToSettle: Int,
        baseProficiencies: Map<String, List<ManualProficiencyData>>,
        resultMap: MutableMap<String, List<ManualProficiencyData>>
    ) {
        val inLibrary = gameData.librarySlots.any { it.discipleId == disciple.id }
        val libraryBonus = if (inLibrary)
            ManualProficiencySystem.LIBRARY_PROFICIENCY_BONUS_RATE else 0.0
        val profGainPerPhase =
            ManualProficiencySystem.calculateProficiencyGainPerPhase(
                disciple.skills.comprehension, libraryBonus
            )
        val totalProfGain = profGainPerPhase * phasesToSettle
        if (totalProfGain <= 0.0) return

        val maxProf = ManualProficiencySystem.MAX_PROFICIENCY.toInt()
        val profList = baseProficiencies
            .getOrDefault(disciple.id, emptyList())
            .toMutableList()
        for (manualId in disciple.manualIds) {
            manualMap[manualId]?.let { manual ->
                applyProficiencyGain(profList, manualId, manual.name,
                    totalProfGain, maxProf)
            }
        }
        resultMap[disciple.id] = profList
    }

    /** 功法熟练度结算：单弟子熟练度增量写入累积映射。 */
    fun settleProficiencyInPlace(
        disciple: Disciple, gameData: GameData,
        manualMap: Map<String, ManualInstance>, phasesToSettle: Int,
        updatedProficiencies: MutableMap<String, List<ManualProficiencyData>>
    ) {
        if (disciple.manualIds.isEmpty()) return
        val inLibrary = gameData.librarySlots.any {
            it.discipleId == disciple.id
        }
        val libraryBonus = if (inLibrary)
            ManualProficiencySystem.LIBRARY_PROFICIENCY_BONUS_RATE else 0.0
        val profGainPerPhase =
            ManualProficiencySystem.calculateProficiencyGainPerPhase(
                disciple.skills.comprehension, libraryBonus
            )
        val totalProfGain = profGainPerPhase * phasesToSettle
        if (totalProfGain <= 0.0) return

        val maxProf = ManualProficiencySystem.MAX_PROFICIENCY.toInt()
        val profList = updatedProficiencies
            .getOrDefault(disciple.id, emptyList())
            .toMutableList()
        for (manualId in disciple.manualIds) {
            manualMap[manualId]?.let { manual ->
                applyProficiencyGain(profList, manualId, manual.name,
                    totalProfGain, maxProf)
            }
        }
        updatedProficiencies[disciple.id] = profList
    }

    /**
     * 将熟练度增益应用到指定功法列表。
     * 如果功法已存在则更新熟练度值，否则新建条目。
     */
    private fun applyProficiencyGain(
        profList: MutableList<ManualProficiencyData>,
        manualId: String, manualName: String, gain: Double, maxProf: Int
    ) {
        val idx = profList.indexOfFirst { it.manualId == manualId }
        if (idx >= 0) {
            val cp = profList[idx]
            val newProf = (cp.proficiency + gain)
                .coerceAtMost(maxProf.toDouble())
            if (newProf != cp.proficiency) {
                profList[idx] = cp.copy(
                    proficiency = newProf,
                    masteryLevel = ManualProficiencySystem.MasteryLevel
                        .fromProficiency(newProf).level
                )
            }
        } else {
            val initProf = gain.coerceAtMost(maxProf.toDouble())
            profList.add(ManualProficiencyData(
                manualId = manualId, manualName = manualName,
                proficiency = initProf, maxProficiency = maxProf,
                masteryLevel = ManualProficiencySystem.MasteryLevel
                    .fromProficiency(initProf).level
            ))
        }
    }

    companion object {
        private const val TAG = "ManualProficiencyService"
    }
}
