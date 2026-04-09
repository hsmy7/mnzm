@file:Suppress("DEPRECATION")

package com.xianxia.sect.core.model

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(
    tableName = "disciples_equipment",
    primaryKeys = ["discipleId", "slot_id"]
)
data class DiscipleEquipment(
    @ColumnInfo(name = "discipleId")
    var discipleId: String = "",

    @ColumnInfo(name = "slot_id")
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
    var soulPower: Int = 10
) {
    val hasEquippedItems: Boolean get() = listOf(weaponId, armorId, bootsId, accessoryId).any { it.isNotEmpty() }

    val equippedItemIds: List<String> get() = listOf(weaponId, armorId, bootsId, accessoryId).filter { it.isNotEmpty() }
    
    companion object {
        fun fromDisciple(disciple: Disciple): DiscipleEquipment {
            return DiscipleEquipment(
                discipleId = disciple.id,
                weaponId = disciple.weaponId,
                armorId = disciple.armorId,
                bootsId = disciple.bootsId,
                accessoryId = disciple.accessoryId,
                weaponNurture = disciple.weaponNurture,
                armorNurture = disciple.armorNurture,
                bootsNurture = disciple.bootsNurture,
                accessoryNurture = disciple.accessoryNurture,
                storageBagItems = disciple.storageBagItems,
                storageBagSpiritStones = disciple.storageBagSpiritStones,
                spiritStones = disciple.spiritStones,
                soulPower = disciple.soulPower
            )
        }
    }
}
