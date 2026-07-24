package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.data.model.SaveData

/**
 * 检查 [residenceSlots] 引用的建筑实例是否存在于 [placedBuildings]。
 * 孤立槽位（引用的 buildingInstanceId 不存在）将被移除。
 *
 * 空 buildingInstanceId 视为尚未分配，始终有效。
 * 使用 [context.buildingInstanceIds] 避免重复遍历。
 */
object BuildingRefRule : SaveValidationRule {
    override val id = "building_ref"
    override val order = 8

    override fun execute(data: SaveData, context: RuleContext): RuleOutcome {
        val gd = data.gameData
        val buildingRepairs = mutableListOf<String>()

        val validSlots = gd.residenceSlots.filter { slot ->
            // 空 buildingInstanceId 视为尚未分配，始终有效
            if (slot.buildingInstanceId.isEmpty()) return@filter true
            // buildingInstanceIds 为空且槽位有非空 buildingInstanceId → 孤立
            if (context.buildingInstanceIds.isEmpty()) {
                buildingRepairs.add(
                    "槽位(buildingInstanceId=${slot.buildingInstanceId}，" +
                        "discipleId=${slot.discipleId}) 引用的建筑不存在（placedBuildings 为空），已移除"
                )
                return@filter false
            }
            // buildingInstanceId 不存在于 placedBuildings → 孤立
            if (slot.buildingInstanceId !in context.buildingInstanceIds) {
                buildingRepairs.add(
                    "槽位(buildingInstanceId=${slot.buildingInstanceId}，" +
                        "discipleId=${slot.discipleId}) 引用的建筑不存在，已移除"
                )
                return@filter false
            }
            true
        }

        return if (buildingRepairs.isNotEmpty()) {
            RuleOutcome.Repaired(
                data.copy(gameData = gd.copy(residenceSlots = validSlots)),
                buildingRepairs
            )
        } else {
            RuleOutcome.Passed
        }
    }
}
