package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.data.model.SaveData

/**
 * 检测生产/巡逻/灵矿/藏经阁/住所/仓库槽位引用不存在的弟子。
 *
 * 遍历 [SaveData.productionSlots]、[SaveData.gameData.spiritMineSlots]、
 * [SaveData.gameData.patrolSlots]、[SaveData.gameData.librarySlots]、
 * [SaveData.gameData.residenceSlots]、[SaveData.gameData.warehouseGarrisons]，
 * 检查 assignedDiscipleId/discipleId 在弟子 ID 集合中是否存在。
 * 对引用不存在弟子的槽位，清除该引用。
 */
object SlotRefRule : SaveValidationRule {
    override val id = "slot_ref"
    override val order = 16

    override fun execute(data: SaveData, context: RuleContext): RuleOutcome {
        val allDiscipleIds = data.disciples.map { it.id }.toSet()
        val repairs = mutableListOf<String>()
        var gd = data.gameData

        // ── 1. productionSlots ──
        val fixedProductionSlots = data.productionSlots.map { slot ->
            val id = slot.assignedDiscipleId
            if (!id.isNullOrEmpty() && id !in allDiscipleIds) {
                repairs.add("生产槽位[${slot.id}] 引用不存在的弟子 id=$id，已清除")
                slot.copy(assignedDiscipleId = null, assignedDiscipleName = "")
            } else slot
        }

        // ── 2. spiritMineSlots ──
        val fixedMineSlots = gd.spiritMineSlots.map { slot ->
            if (slot.discipleId.isNotEmpty() && slot.discipleId !in allDiscipleIds) {
                repairs.add("灵矿槽位[${slot.index}] 引用不存在的弟子 id=${slot.discipleId}，已清除")
                slot.copy(discipleId = "", discipleName = "")
            } else slot
        }

        // ── 3. patrolSlots ──
        val fixedPatrolSlots = gd.patrolSlots.map { slot ->
            if (slot.discipleId.isNotEmpty() && slot.discipleId !in allDiscipleIds) {
                repairs.add("巡逻槽位[${slot.index}] 引用不存在的弟子 id=${slot.discipleId}，已清除")
                slot.copy(discipleId = "", discipleName = "", discipleRealm = "", portraitRes = "")
            } else slot
        }

        // ── 4. librarySlots ──
        val fixedLibrarySlots = gd.librarySlots.map { slot ->
            if (slot.discipleId.isNotEmpty() && slot.discipleId !in allDiscipleIds) {
                repairs.add("藏经阁槽位[${slot.index}] 引用不存在的弟子 id=${slot.discipleId}，已清除")
                slot.copy(discipleId = "", discipleName = "")
            } else slot
        }

        // ── 5. residenceSlots ──
        val fixedResidenceSlots = gd.residenceSlots.map { slot ->
            if (slot.discipleId.isNotEmpty() && slot.discipleId !in allDiscipleIds) {
                repairs.add("住所槽位[${slot.slotIndex}] 引用不存在的弟子 id=${slot.discipleId}，已清除")
                slot.copy(discipleId = "", discipleName = "")
            } else slot
        }

        // ── 6. warehouseGarrisons ──
        val fixedGarrisonSlots = gd.warehouseGarrisons.map { slot ->
            if (slot.discipleId.isNotEmpty() && slot.discipleId !in allDiscipleIds) {
                repairs.add("仓库守卫槽位[${slot.slotIndex}] 引用不存在的弟子 id=${slot.discipleId}，已清除")
                slot.copy(discipleId = "", discipleName = "")
            } else slot
        }

        if (repairs.isEmpty()) return RuleOutcome.Passed

        gd = gd.copy(
            spiritMineSlots = fixedMineSlots,
            patrolSlots = fixedPatrolSlots,
            librarySlots = fixedLibrarySlots,
            residenceSlots = fixedResidenceSlots,
            warehouseGarrisons = fixedGarrisonSlots
        )
        return RuleOutcome.Repaired(
            data.copy(productionSlots = fixedProductionSlots, gameData = gd),
            repairs
        )
    }
}
