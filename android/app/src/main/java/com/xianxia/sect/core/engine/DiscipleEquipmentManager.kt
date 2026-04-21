@file:Suppress("DEPRECATION")

package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.*

object DiscipleEquipmentManager {
    
    data class EquipmentProcessResult(
        val disciple: Disciple,
        val equipmentUpdates: List<Equipment>,
        val events: List<String>,
        val remainingEquipment: Equipment? = null
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
        var lastRemaining: Equipment? = null
        
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
                result.remainingEquipment?.let { lastRemaining = it }
            }
        }
        
        return EquipmentProcessResult(updatedDisciple, equipmentUpdates, events, lastRemaining)
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
            equipmentUpdates.add(oldEquip.copy(ownerId = disciple.id, isEquipped = false, nurtureLevel = 0, nurtureProgress = 0.0))
            
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
        
        val remainingEquipment: Equipment?
        if (betterEquip.quantity > 1) {
            val equippedId = java.util.UUID.randomUUID().toString()
            val equippedItem = betterEquip.copy(
                id = equippedId,
                quantity = 1,
                isEquipped = true,
                ownerId = disciple.id
            )
            remainingEquipment = betterEquip.copy(quantity = betterEquip.quantity - 1, isEquipped = false, ownerId = null)
            equipmentUpdates.add(equippedItem)
            updatedDisciple = config.equipSetter(updatedDisciple, equippedId)
        } else {
            updatedDisciple = config.equipSetter(updatedDisciple, betterEquip.id)
            equipmentUpdates.add(betterEquip.copy(ownerId = disciple.id, isEquipped = true))
            remainingEquipment = null
        }
        
        updatedDisciple = updatedDisciple.copyWith(
            storageBagItems = updatedDisciple.storageBagItems.filter { it.itemId != betterEquip.id }
        )
        
        val messagePrefix = if (instantMessage) "立即" else "自动"
        events.add("${disciple.name} ${messagePrefix}装备了 ${betterEquip.name}")
        
        return EquipmentProcessResult(updatedDisciple, equipmentUpdates, events, remainingEquipment)
    }

    fun canEquip(disciple: Disciple, equipment: Equipment): Boolean {
        if (equipment.ownerId != null && equipment.ownerId != disciple.id) return false
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
