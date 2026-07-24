package com.xianxia.sect.data.integrity.rules

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
            if (!d.isAlive) {
                val equip = d.equipment
                val hasWeapon = equip.weaponId.isNotEmpty()
                val hasArmor = equip.armorId.isNotEmpty()
                val hasBoots = equip.bootsId.isNotEmpty()
                val hasAccessory = equip.accessoryId.isNotEmpty()

                if (hasWeapon || hasArmor || hasBoots || hasAccessory) {
                    val cleaned = listOfNotNull(
                        hasWeapon to "weaponId=${equip.weaponId}",
                        hasArmor to "armorId=${equip.armorId}",
                        hasBoots to "bootsId=${equip.bootsId}",
                        hasAccessory to "accessoryId=${equip.accessoryId}"
                    ).joinToString(", ") { it.second }

                    repairs.add(
                        "死亡弟子[${d.name.ifBlank { "ID=${d.id}" }}] $cleaned，已清除装备引用"
                    )
                    d.copy(
                        equipment = equip.copy(
                            weaponId = "",
                            armorId = "",
                            bootsId = "",
                            accessoryId = ""
                        )
                    )
                } else d
            } else d
        }
        return if (repairs.isNotEmpty()) {
            RuleOutcome.Repaired(data.copy(disciples = disciples), repairs)
        } else {
            RuleOutcome.Passed
        }
    }
}
