package com.xianxia.sect.core.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "disciples_equipment",
    indices = [
        Index(value = ["discipleId"], unique = true)
    ],
    foreignKeys = [
        ForeignKey(
            entity = DiscipleCore::class,
            parentColumns = ["id"],
            childColumns = ["discipleId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE
        )
    ]
)
data class DiscipleEquipment(
    @PrimaryKey
    var discipleId: String = "",
    var weaponId: String? = null,
    var armorId: String? = null,
    var bootsId: String? = null,
    var accessoryId: String? = null,
    var weaponNurture: EquipmentNurtureData? = null,
    var armorNurture: EquipmentNurtureData? = null,
    var bootsNurture: EquipmentNurtureData? = null,
    var accessoryNurture: EquipmentNurtureData? = null,
    var storageBagItems: List<StorageBagItem> = emptyList(),
    var storageBagSpiritStones: Long = 0,
    var spiritStones: Int = 0,
    var soulPower: Int = 10
) {
    val hasEquippedItems: Boolean get() = listOfNotNull(weaponId, armorId, bootsId, accessoryId).isNotEmpty()
    
    val equippedItemIds: List<String> get() = listOfNotNull(weaponId, armorId, bootsId, accessoryId)
    
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
