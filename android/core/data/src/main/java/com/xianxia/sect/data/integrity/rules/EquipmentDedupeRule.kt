package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.core.model.EquipmentSet
import com.xianxia.sect.data.model.SaveData

/**
 * 检测同一 equipment ID 被多弟子引用。
 *
 * 遍历所有弟子的装备槽（weaponId/armorId/bootsId/accessoryId），
 * 统计每个 equipment ID 的引用次数。出现 >1 次时，从后续弟子的槽位中清除重复引用。
 *
 * 必须排在 [EquipmentRefRule]（order=6）之后，因为 EquipmentRefRule 先清理孤立引用。
 */
object EquipmentDedupeRule : SaveValidationRule {
    override val id = "equipment_dedupe"
    override val order = 15

    override fun execute(data: SaveData, context: RuleContext): RuleOutcome {
        // 统计每个 equipment ID 被引用的次数和位置
        val duplicates = collectEquipmentUsage(data).filter { it.value.size > 1 }
        if (duplicates.isEmpty()) return RuleOutcome.Passed

        val repairs = mutableListOf<String>()

        val fixedDisciples = data.disciples.mapIndexed { index, d ->
            val name = d.name.ifBlank { "ID=${d.id}" }
            val (equip, localFixes) = clearDuplicateRefs(d.equipment, index, duplicates)
            if (localFixes.isNotEmpty()) {
                repairs.add("弟子[$name] 装备重复引用: ${localFixes.joinToString(", ")}，已清除")
                d.copy(equipment = equip)
            } else d
        }

        return if (repairs.isNotEmpty()) {
            RuleOutcome.Repaired(data.copy(disciples = fixedDisciples), repairs)
        } else {
            RuleOutcome.Passed
        }
    }

    /** 装备引用统计（execute 拆分）：id → [(弟子行下标, 槽位标签)] */
    private fun collectEquipmentUsage(
        data: SaveData
    ): MutableMap<String, MutableList<Pair<Int, String>>> {
        val usage = mutableMapOf<String, MutableList<Pair<Int, String>>>()

        data.disciples.forEachIndexed { index, d ->
            val equip = d.equipment
            val name = d.name.ifBlank { "ID=${d.id}" }
            if (equip.weaponId.isNotEmpty()) {
                usage.getOrPut(equip.weaponId) { mutableListOf() }.add(index to "${name}.weaponId")
            }
            if (equip.armorId.isNotEmpty()) {
                usage.getOrPut(equip.armorId) { mutableListOf() }.add(index to "${name}.armorId")
            }
            if (equip.bootsId.isNotEmpty()) {
                usage.getOrPut(equip.bootsId) { mutableListOf() }.add(index to "${name}.bootsId")
            }
            if (equip.accessoryId.isNotEmpty()) {
                usage.getOrPut(equip.accessoryId) { mutableListOf() }.add(index to "${name}.accessoryId")
            }
        }
        return usage
    }

    /** 清除非首次重复引用（execute 拆分）：返回清理后装备与修复记录 */
    private fun clearDuplicateRefs(
        equip: EquipmentSet,
        index: Int,
        duplicates: Map<String, List<Pair<Int, String>>>
    ): Pair<EquipmentSet, List<String>> {
        var result = equip
        val localFixes = mutableListOf<String>()

        duplicates.forEach { (equipId, refs) ->
            // 只清除非首次引用
            val isFirst = refs.any { it.first == index && refs.first().first == index }
            if (!isFirst && refs.any { it.first == index }) {
                // 确定该弟子的哪个槽位引用了此 equipId
                if (result.weaponId == equipId) {
                    localFixes.add("weaponId=$equipId")
                    result = result.copy(weaponId = "")
                }
                if (result.armorId == equipId) {
                    localFixes.add("armorId=$equipId")
                    result = result.copy(armorId = "")
                }
                if (result.bootsId == equipId) {
                    localFixes.add("bootsId=$equipId")
                    result = result.copy(bootsId = "")
                }
                if (result.accessoryId == equipId) {
                    localFixes.add("accessoryId=$equipId")
                    result = result.copy(accessoryId = "")
                }
            }
        }
        return result to localFixes
    }
}
