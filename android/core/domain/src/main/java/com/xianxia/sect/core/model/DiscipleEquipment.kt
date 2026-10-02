package com.xianxia.sect.core.model

/**
 * 弟子装备与储物格位的**内存侧**投影（v53/SR-7 起不再有 `disciples_equipment` 表，
 * 真相恒在 `disciples` 与 `storage_bag`）。
 */
data class DiscipleEquipment(
    var discipleId: String = "",

    var headId: String = "",
    var bodyId: String = "",
    var handsId: String = "",
    var feetId: String = "",
    var weaponId: String = "",
    var legsId: String = "",
    var storageBagItems: List<StorageBagItem> = emptyList(),
    var storageBagSpiritStones: Long = 0,
    var spiritStones: Int = 0
) {
    val hasEquippedItems: Boolean
        get() = EquipmentSlot.displayOrder.any { slotId(it).isNotEmpty() }

    /** 按部位取装备 id */
    fun slotId(part: EquipmentSlot): String = when (part) {
        EquipmentSlot.HEAD -> headId
        EquipmentSlot.BODY -> bodyId
        EquipmentSlot.HANDS -> handsId
        EquipmentSlot.FEET -> feetId
    }

    val equippedItemIds: List<String> get() = EquipmentSlot.displayOrder
        .mapNotNull { slotId(it).takeIf(String::isNotEmpty) }
    
    companion object {
        fun fromDisciple(disciple: Disciple): DiscipleEquipment {
            return DiscipleEquipment(
                discipleId = disciple.id,
                headId = disciple.equipment.headId,
                bodyId = disciple.equipment.bodyId,
                handsId = disciple.equipment.handsId,
                feetId = disciple.equipment.feetId,
                weaponId = disciple.equipment.weaponId,
                legsId = disciple.equipment.legsId,
                storageBagItems = disciple.equipment.storageBagItems,
                storageBagSpiritStones = disciple.equipment.storageBagSpiritStones,
                spiritStones = disciple.equipment.spiritStones
            )
        }
    }
}
