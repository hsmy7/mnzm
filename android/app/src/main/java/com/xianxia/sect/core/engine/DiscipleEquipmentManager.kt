package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.*

object DiscipleEquipmentManager {
    
    data class EquipmentProcessResult(
        val disciple: Disciple,
        val equipmentUpdates: List<Equipment>,
        val events: List<String>
    )

    private data class SlotConfig(
        val slotType: EquipmentSlot,
        val currentEquipIdGetter: (Disciple) -> String?,
        val equipSetter: (Disciple, String) -> Disciple
    )
    
    private val slotConfigs = listOf(
        SlotConfig(
            slotType = EquipmentSlot.WEAPON,
            currentEquipIdGetter = { it.weaponId },
            equipSetter = { d, id -> d.copyWith(weaponId = id) }
        ),
        SlotConfig(
            slotType = EquipmentSlot.ARMOR,
            currentEquipIdGetter = { it.armorId },
            equipSetter = { d, id -> d.copyWith(armorId = id) }
        ),
        SlotConfig(
            slotType = EquipmentSlot.BOOTS,
            currentEquipIdGetter = { it.bootsId },
            equipSetter = { d, id -> d.copyWith(bootsId = id) }
        ),
        SlotConfig(
            slotType = EquipmentSlot.ACCESSORY,
            currentEquipIdGetter = { it.accessoryId },
            equipSetter = { d, id -> d.copyWith(accessoryId = id) }
        )
    )
    
    fun processAutoEquip(
        disciple: Disciple,
        equipmentMap: Map<String, Equipment>,
        gameYear: Int,
        gameMonth: Int,
        instantMessage: Boolean = false
    ): EquipmentProcessResult {
        val equipmentUpdates = mutableListOf<Equipment>()
        val events = mutableListOf<String>()
        var updatedDisciple = disciple
        
        val bagEquipments = disciple.storageBagItems
            .filter { it.itemType == "equipment" }
            .mapNotNull { equipmentMap[it.itemId] }
            .filter { disciple.realm <= it.minRealm }
        
        if (bagEquipments.isEmpty()) {
            return EquipmentProcessResult(disciple, emptyList(), emptyList())
        }
        
        slotConfigs.forEach { config ->
            val result = processSlot(
                disciple = updatedDisciple,
                config = config,
                equipmentMap = equipmentMap,
                bagEquipments = bagEquipments,
                gameYear = gameYear,
                gameMonth = gameMonth,
                instantMessage = instantMessage
            )
            
            if (result.disciple != updatedDisciple) {
                updatedDisciple = result.disciple
                equipmentUpdates.addAll(result.equipmentUpdates)
                events.addAll(result.events)
            }
        }
        
        return EquipmentProcessResult(updatedDisciple, equipmentUpdates, events)
    }
    
    private fun processSlot(
        disciple: Disciple,
        config: SlotConfig,
        equipmentMap: Map<String, Equipment>,
        bagEquipments: List<Equipment>,
        gameYear: Int,
        gameMonth: Int,
        instantMessage: Boolean
    ): EquipmentProcessResult {
        val equipmentUpdates = mutableListOf<Equipment>()
        val events = mutableListOf<String>()
        var updatedDisciple = disciple
        
        val slotEquipments = bagEquipments.filter {
            it.slot == config.slotType
        }
        
        if (slotEquipments.isEmpty()) {
            return EquipmentProcessResult(disciple, emptyList(), emptyList())
        }
        
        val currentEquipId = config.currentEquipIdGetter(disciple)
        val currentEquip = currentEquipId?.let { equipmentMap[it] }
        val currentRarity = currentEquip?.rarity ?: 0
        
        val betterEquip = slotEquipments
            .filter { it.rarity > currentRarity }
            .maxByOrNull { it.rarity }
        
        if (betterEquip == null) {
            return EquipmentProcessResult(disciple, emptyList(), emptyList())
        }
        
        currentEquip?.let { oldEquip ->
            equipmentUpdates.add(oldEquip.copy(ownerId = null, isEquipped = false))
            
            val oldItem = StorageBagItem(
                itemId = oldEquip.id,
                itemType = "equipment",
                name = oldEquip.name,
                rarity = oldEquip.rarity,
                quantity = 1,
                obtainedYear = gameYear,
                obtainedMonth = gameMonth
            )
            updatedDisciple = updatedDisciple.copyWith(
                storageBagItems = updatedDisciple.storageBagItems + oldItem
            )
        }
        
        updatedDisciple = config.equipSetter(updatedDisciple, betterEquip.id)
        equipmentUpdates.add(betterEquip.copy(ownerId = disciple.id, isEquipped = true))
        
        updatedDisciple = updatedDisciple.copyWith(
            storageBagItems = updatedDisciple.storageBagItems.filter { it.itemId != betterEquip.id }
        )
        
        val messagePrefix = if (instantMessage) "立即" else "自动"
        events.add("${disciple.name} ${messagePrefix}装备了 ${betterEquip.name}")
        
        return EquipmentProcessResult(updatedDisciple, equipmentUpdates, events)
    }

    fun canEquip(disciple: Disciple, equipment: Equipment): Boolean {
        if (equipment.slot == null || equipment.ownerId != null) return false
        return disciple.realm <= equipment.minRealm
    }
    
    fun getEquipSlot(disciple: Disciple, slot: EquipmentSlot): String? {
        return when (slot) {
            EquipmentSlot.WEAPON -> disciple.weaponId
            EquipmentSlot.ARMOR -> disciple.armorId
            EquipmentSlot.BOOTS -> disciple.bootsId
            EquipmentSlot.ACCESSORY -> disciple.accessoryId
        }
    }
}
