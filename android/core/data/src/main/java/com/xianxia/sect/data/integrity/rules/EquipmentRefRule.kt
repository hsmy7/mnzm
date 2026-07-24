package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.data.model.SaveData

/**
 * 检查弟子装备引用（weaponId/armorId/bootsId/accessoryId）指向的物品是否存在。
 * 对孤立引用（引用的 ID 不在 [RuleContext.allEquipmentIds] 中），清除该引用。
 *
 * 使用 [context.allEquipmentIds] 避免重复遍历装备列表。
 */
object EquipmentRefRule : SaveValidationRule {
    override val id = "equipment_ref"
    override val order = 6

    override fun execute(data: SaveData, context: RuleContext): RuleOutcome {
        val repairs = mutableListOf<String>()
        val disciples = data.disciples.map { d ->
            val name = d.name.ifBlank { "ID=${d.id}" }
            val equip = d.equipment
            val localFixes = mutableListOf<String>()

            var weaponId = equip.weaponId
            var armorId = equip.armorId
            var bootsId = equip.bootsId
            var accessoryId = equip.accessoryId

            if (weaponId.isNotEmpty() && weaponId !in context.allEquipmentIds) {
                localFixes.add("weaponId=$weaponId")
                weaponId = ""
            }
            if (armorId.isNotEmpty() && armorId !in context.allEquipmentIds) {
                localFixes.add("armorId=$armorId")
                armorId = ""
            }
            if (bootsId.isNotEmpty() && bootsId !in context.allEquipmentIds) {
                localFixes.add("bootsId=$bootsId")
                bootsId = ""
            }
            if (accessoryId.isNotEmpty() && accessoryId !in context.allEquipmentIds) {
                localFixes.add("accessoryId=$accessoryId")
                accessoryId = ""
            }

            if (localFixes.isNotEmpty()) {
                repairs.add(
                    "弟子[$name] 装备引用不存在: ${localFixes.joinToString(", ")}，已清除"
                )
                d.copy(
                    equipment = equip.copy(
                        weaponId = weaponId,
                        armorId = armorId,
                        bootsId = bootsId,
                        accessoryId = accessoryId
                    )
                )
            } else d
        }
        return if (repairs.isNotEmpty()) {
            RuleOutcome.Repaired(data.copy(disciples = disciples), repairs)
        } else {
            RuleOutcome.Passed
        }
    }
}
