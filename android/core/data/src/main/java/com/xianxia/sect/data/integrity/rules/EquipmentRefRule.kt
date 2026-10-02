package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.core.model.EquipmentSet
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.data.model.SaveData

/**
 * 检查弟子部位装备引用指向的物品是否存在。对孤立引用（引用的 ID 不在
 * [RuleContext.allEquipmentIds] 中），清除该引用。
 *
 * 扫描面 = 四活跃部位（[EquipmentSlot.displayOrder]，头/身/手/脚）；武器/腿部
 * 槽位字段已随四部位化退役（F2），扫描面不再含退役槽位。
 *
 * 使用 [context.allEquipmentIds] 避免重复遍历装备列表（装备堆叠已退役，
 * 引用目标恒为 equipmentInstances）。
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

            for (part in EquipmentSlot.displayOrder) {
                val id = equip.slotId(part)
                if (id.isNotEmpty() && id !in context.allEquipmentIds) {
                    localFixes.add("${part.name}=$id")
                }
            }

            if (localFixes.isNotEmpty()) {
                repairs.add("弟子[$name] 装备引用不存在: ${localFixes.joinToString(", ")}，已清除")
                d.copy(equipment = clearDanglingRefs(equip, context.allEquipmentIds))
            } else d
        }
        return if (repairs.isNotEmpty()) {
            RuleOutcome.Repaired(data.copy(disciples = disciples), repairs)
        } else {
            RuleOutcome.Passed
        }
    }

    /** 清除指向不存在装备的部位引用 */
    private fun clearDanglingRefs(equip: EquipmentSet, knownIds: Set<String>): EquipmentSet {
        var result = equip
        for (part in EquipmentSlot.displayOrder) {
            val id = result.slotId(part)
            if (id.isNotEmpty() && id !in knownIds) {
                result = result.copy().apply { setSlotId(part, "") }
            }
        }
        return result
    }
}
