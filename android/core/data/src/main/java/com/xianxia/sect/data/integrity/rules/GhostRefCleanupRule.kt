package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.data.model.SaveData

/**
 * 清理 [residenceSlots] 中引用已被 [GhostDiscipleCleanupRule] 移除的幽灵弟子。
 *
 * 依赖 [context.removedDiscipleIds]（由 [GhostDiscipleCleanupRule] 在 order=10 时填充）。
 * 必须排在 [GhostDiscipleCleanupRule] 之后（order=11 > 10）。
 *
 * 如果前置规则未运行（[removedDiscipleIds] 为空），跳过执行。
 */
object GhostRefCleanupRule : SaveValidationRule {
    override val id = "ghost_ref_cleanup"
    override val order = 11

    override fun execute(data: SaveData, context: RuleContext): RuleOutcome {
        if (context.removedDiscipleIds.isEmpty()) {
            return RuleOutcome.Skipped("无幽灵弟子需清理引用")
        }

        val gd = data.gameData
        val slotRepairs = mutableListOf<String>()
        var changed = false

        val fixedSlots = gd.residenceSlots.map { slot ->
            if (slot.discipleId.isNotEmpty() &&
                slot.discipleId in context.removedDiscipleIds
            ) {
                changed = true
                slotRepairs.add(
                    "槽位(buildingInstanceId=${slot.buildingInstanceId}，" +
                        "slotIndex=${slot.slotIndex}) 引用的弟子(id=${slot.discipleId})不存在，已清除"
                )
                slot.copy(discipleId = "", discipleName = "")
            } else slot
        }

        return if (changed) {
            RuleOutcome.Repaired(
                data.copy(gameData = gd.copy(residenceSlots = fixedSlots)),
                slotRepairs
            )
        } else {
            // 有幽灵弟子清理但无槽位引用需要修复
            RuleOutcome.Passed
        }
    }
}
