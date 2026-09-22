package com.xianxia.sect.core.model

/**
 * 弟子装备与储物格位的**内存侧**投影（v53/SR-7 起不再有 `disciples_equipment` 表，
 * 真相恒在 `disciples` 与 `storage_bag`）。
 */
data class DiscipleEquipment(
    var discipleId: String = "",

    var slotId: Int = 0,

    var weaponId: String = "",
    var armorId: String = "",
    var bootsId: String = "",
    var accessoryId: String = "",
    var weaponNurture: EquipmentNurtureData = EquipmentNurtureData("", 0),
    var armorNurture: EquipmentNurtureData = EquipmentNurtureData("", 0),
    var bootsNurture: EquipmentNurtureData = EquipmentNurtureData("", 0),
    var accessoryNurture: EquipmentNurtureData = EquipmentNurtureData("", 0),
    var storageBagItems: List<StorageBagItem> = emptyList(),
    var storageBagSpiritStones: Long = 0,
    var spiritStones: Int = 0,
    var soulPower: Int = 0
) {
    val hasEquippedItems: Boolean get() = listOf(weaponId, armorId, bootsId, accessoryId).any { it.isNotEmpty() }

    val equippedItemIds: List<String> get() = listOf(weaponId, armorId, bootsId, accessoryId).filter { it.isNotEmpty() }
    
    companion object {
        fun fromDisciple(disciple: Disciple): DiscipleEquipment {
            return DiscipleEquipment(
                discipleId = disciple.id,
                weaponId = disciple.equipment.weaponId,
                armorId = disciple.equipment.armorId,
                bootsId = disciple.equipment.bootsId,
                accessoryId = disciple.equipment.accessoryId,
                weaponNurture = disciple.equipment.weaponNurture,
                armorNurture = disciple.equipment.armorNurture,
                bootsNurture = disciple.equipment.bootsNurture,
                accessoryNurture = disciple.equipment.accessoryNurture,
                storageBagItems = disciple.equipment.storageBagItems,
                storageBagSpiritStones = disciple.equipment.storageBagSpiritStones,
                spiritStones = disciple.equipment.spiritStones,
                soulPower = disciple.soulPower
            )
        }
    }
}
