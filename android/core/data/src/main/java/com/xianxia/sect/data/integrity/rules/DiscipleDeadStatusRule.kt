package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.data.model.SaveData

/**
 * 检查死亡弟子的装备引用是否应清理。
 *
 * 死亡弟子持有的装备 ID 引用会阻止该装备被其他弟子使用。
 * 将死亡弟子 4 个装备槽位的引用全部清空。
 *
 * 注意：装备物品本身不会被删除，只是解除弟子的引用。
 */
object DiscipleDeadStatusRule : SaveValidationRule {
    override val id = "disciple_dead_equipment"
    override val order = 14

    override fun execute(data: SaveData, context: RuleContext): RuleOutcome {
        val repairs = mutableListOf<String>()
        val disciples = data.disciples.map { d ->
            if (!d.isAlive) cleanDeadDiscipleEquipment(d, repairs) else d
        }
        return if (repairs.isNotEmpty()) {
            RuleOutcome.Repaired(data.copy(disciples = disciples), repairs)
        } else {
            RuleOutcome.Passed
        }
    }

    /**
     * 清除单个死亡弟子的装备引用（execute 拆分）：四个槽位引用全部清空，
     * 仅已装备的槽位进入修复消息；无任何装备引用则原样返回。
     */
    private fun cleanDeadDiscipleEquipment(d: Disciple, repairs: MutableList<String>): Disciple {
        val equip = d.equipment
        val equippedSlotIds = listOfNotNull(
            if (equip.weaponId.isNotEmpty()) "weaponId=${equip.weaponId}" else null,
            if (equip.armorId.isNotEmpty()) "armorId=${equip.armorId}" else null,
            if (equip.bootsId.isNotEmpty()) "bootsId=${equip.bootsId}" else null,
            if (equip.accessoryId.isNotEmpty()) "accessoryId=${equip.accessoryId}" else null
        )
        if (equippedSlotIds.isEmpty()) return d

        repairs.add(
            "死亡弟子[${d.name.ifBlank { "ID=${d.id}" }}] " +
                "${equippedSlotIds.joinToString(", ")}，已清除装备引用"
        )
        return d.copy(
            equipment = equip.copy(
                weaponId = "",
                armorId = "",
                bootsId = "",
                accessoryId = ""
            )
        )
    }
}
