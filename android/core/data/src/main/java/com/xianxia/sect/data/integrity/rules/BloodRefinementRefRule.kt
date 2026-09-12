package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.data.model.SaveData

/**
 * 检测血炼引用不存在的弟子。
 *
 * 遍历 [SaveData.gameData.bloodRefinementPctTotals] 和
 * [SaveData.gameData.bloodRefinementBonusTotals] 的 key 集合，
 * 检查每个 key 在弟子 ID 集合中是否存在。
 * 对引用不存在弟子的 entry，从对应 Map 中移除。
 */
object BloodRefinementRefRule : SaveValidationRule {
    override val id = "blood_refinement_ref"
    override val order = 17

    override fun execute(data: SaveData, context: RuleContext): RuleOutcome {
        val allDiscipleIds = data.disciples.map { it.id }.toSet()
        val repairs = mutableListOf<String>()
        var gd = data.gameData

        // ── 检查 bloodRefinementPctTotals ──
        val pctTotals = gd.bloodRefinementPctTotals
        val pctOrphans = pctTotals.keys.filter { it !in allDiscipleIds }
        val cleanedPctTotals = if (pctOrphans.isNotEmpty()) {
            pctOrphans.forEach { id ->
                repairs.add("血炼百分比累计引用不存在的弟子 id=$id，已移除")
            }
            pctTotals - pctOrphans.toSet()
        } else pctTotals

        // ── 检查 bloodRefinementBonusTotals ──
        val bonusTotals = gd.bloodRefinementBonusTotals
        val bonusOrphans = bonusTotals.keys.filter { it !in allDiscipleIds }
        val cleanedBonusTotals = if (bonusOrphans.isNotEmpty()) {
            bonusOrphans.forEach { id ->
                repairs.add("血炼加成累计引用不存在的弟子 id=$id，已移除")
            }
            bonusTotals - bonusOrphans.toSet()
        } else bonusTotals

        if (repairs.isEmpty()) return RuleOutcome.Passed

        gd = gd.copy(
            bloodRefinementPctTotals = cleanedPctTotals,
            bloodRefinementBonusTotals = cleanedBonusTotals
        )
        return RuleOutcome.Repaired(data.copy(gameData = gd), repairs)
    }
}
