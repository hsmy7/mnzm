package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.*

object DiscipleEquipmentManager {
    
    data class EquipmentProcessResult(
        val disciple: Disciple,
        val equipmentUpdates: List<Equipment>,
        val events: List<String>
    )
    
    private enum class EquipmentSlotType {
        WEAPON, ARMOR, BOOTS, ACCESSORY
    }
    
    private data class SlotConfig(
        val slotType: EquipmentSlotType,
        val currentEquipIdGetter: (Disciple) -> String?,
        val equipSetter: (Disciple, String) -> Disciple
    )
    
    private val slotConfigs = listOf(
        SlotConfig(
            slotType = EquipmentSlotType.WEAPON,
            currentEquipIdGetter = { it.weaponId },
            equipSetter = { d, id -> d.copy(weaponId = id) }
        ),
        SlotConfig(
            slotType = EquipmentSlotType.ARMOR,
            currentEquipIdGetter = { it.armorId },
            equipSetter = { d, id -> d.copy(armorId = id) }
        ),
        SlotConfig(
            slotType = EquipmentSlotType.BOOTS,
            currentEquipIdGetter = { it.bootsId },
            equipSetter = { d, id -> d.copy(bootsId = id) }
        ),
        SlotConfig(
            slotType = EquipmentSlotType.ACCESSORY,
            currentEquipIdGetter = { it.accessoryId },
            equipSetter = { d, id -> d.copy(accessoryId = id) }
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
            getSlotType(it) == config.slotType 
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
            updatedDisciple = updatedDisciple.copy(
                storageBagItems = updatedDisciple.storageBagItems + oldItem
            )
        }
        
        updatedDisciple = config.equipSetter(updatedDisciple, betterEquip.id)
        equipmentUpdates.add(betterEquip.copy(ownerId = disciple.id, isEquipped = true))
        
        updatedDisciple = updatedDisciple.copy(
            storageBagItems = updatedDisciple.storageBagItems.filter { it.itemId != betterEquip.id }
        )
        
        val messagePrefix = if (instantMessage) "立即" else "自动"
        events.add("${disciple.name} ${messagePrefix}装备了 ${betterEquip.name}")
        
        return EquipmentProcessResult(updatedDisciple, equipmentUpdates, events)
    }
    
    private fun getSlotType(equipment: Equipment): EquipmentSlotType {
        return when (equipment.slot) {
            EquipmentSlot.WEAPON -> EquipmentSlotType.WEAPON
            EquipmentSlot.ARMOR -> EquipmentSlotType.ARMOR
            EquipmentSlot.BOOTS -> EquipmentSlotType.BOOTS
            EquipmentSlot.ACCESSORY -> EquipmentSlotType.ACCESSORY
        }
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
