package com.xianxia.sect.core.engine

import com.xianxia.sect.core.engine.system.StackUpdate
import com.xianxia.sect.core.model.*

object DiscipleEquipmentManager {

    data class EquipmentProcessResult(
        val disciple: Disciple,
        val newInstance: EquipmentInstance?,
        val replacedInstance: EquipmentInstance?,
        val stackUpdate: StackUpdate?,
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
        equipmentStacks: List<EquipmentStack>,
        equipmentInstances: Map<String, EquipmentInstance>,
        gameYear: Int,
        gameMonth: Int,
        instantMessage: Boolean = false
    ): EquipmentProcessResult {
        val events = mutableListOf<String>()
        var updatedDisciple = disciple
        var lastNewInstance: EquipmentInstance? = null
        var lastReplacedInstance: EquipmentInstance? = null
        var lastStackUpdate: StackUpdate? = null

        val bagStackRefs = disciple.storageBagItems
            .filter { it.itemType == "equipment_stack" }

        if (bagStackRefs.isEmpty()) {
            return EquipmentProcessResult(disciple, null, null, null, emptyList())
        }

        slotConfigs.forEach { config ->
            val result = processSlot(
                disciple = updatedDisciple,
                config = config,
                equipmentStacks = equipmentStacks,
                equipmentInstances = equipmentInstances,
                bagStackRefs = bagStackRefs,
                gameYear = gameYear,
                gameMonth = gameMonth,
                instantMessage = instantMessage
            )

            if (result.newInstance != null) {
                updatedDisciple = result.disciple
                lastNewInstance = result.newInstance
                lastReplacedInstance = result.replacedInstance
                lastStackUpdate = result.stackUpdate
                events.addAll(result.events)
            }
        }

        return EquipmentProcessResult(updatedDisciple, lastNewInstance, lastReplacedInstance, lastStackUpdate, events)
    }

    private fun processSlot(
        disciple: Disciple,
        config: SlotConfig,
        equipmentStacks: List<EquipmentStack>,
        equipmentInstances: Map<String, EquipmentInstance>,
        bagStackRefs: List<StorageBagItem>,
        gameYear: Int,
        gameMonth: Int,
        instantMessage: Boolean
    ): EquipmentProcessResult {
        val events = mutableListOf<String>()
        var updatedDisciple = disciple

        val slotStacks = bagStackRefs.mapNotNull { ref ->
            equipmentStacks.find { it.id == ref.itemId && it.slot == config.slotType }
        }.filter { disciple.realm <= it.minRealm }

        if (slotStacks.isEmpty()) {
            return EquipmentProcessResult(disciple, null, null, null, emptyList())
        }

        val currentEquipId = config.currentEquipIdGetter(disciple)
        val currentInstance = currentEquipId?.let { equipmentInstances[it] }
        val currentRarity = currentInstance?.rarity ?: 0

        val bestStack = slotStacks
            .filter { it.rarity > currentRarity }
            .maxByOrNull { it.rarity }

        if (bestStack == null) {
            return EquipmentProcessResult(disciple, null, null, null, emptyList())
        }

        var replacedInstance: EquipmentInstance? = null
        currentInstance?.let { oldInstance ->
            replacedInstance = oldInstance

            val oldItem = StorageBagItem(
                itemId = oldInstance.id,
                itemType = "equipment_instance",
                name = oldInstance.name,
                rarity = oldInstance.rarity,
                quantity = 1,
                obtainedYear = gameYear,
                obtainedMonth = gameMonth
            )
            updatedDisciple = updatedDisciple.copyWith(
                storageBagItems = updatedDisciple.storageBagItems + oldItem
            )
        }

        val instanceId = java.util.UUID.randomUUID().toString()
        val newInstance = bestStack.toInstance(id = instanceId, ownerId = disciple.id, isEquipped = true)

        val newQty = bestStack.quantity - 1
        val stackUpdate = if (newQty <= 0) {
            StackUpdate(stackId = bestStack.id, newQuantity = 0, isDeletion = true)
        } else {
            StackUpdate(stackId = bestStack.id, newQuantity = newQty, isDeletion = false)
        }

        updatedDisciple = config.equipSetter(updatedDisciple, instanceId)

        updatedDisciple = updatedDisciple.copyWith(
            storageBagItems = updatedDisciple.storageBagItems.filter { it.itemId != bestStack.id || it.itemType != "equipment_stack" }
        )

        val messagePrefix = if (instantMessage) "立即" else "自动"
        events.add("${disciple.name} ${messagePrefix}装备了 ${bestStack.name}")

        return EquipmentProcessResult(updatedDisciple, newInstance, replacedInstance, stackUpdate, events)
    }

    fun canEquip(disciple: Disciple, stack: EquipmentStack): Boolean {
        return disciple.realm <= stack.minRealm
    }

    fun canEquip(disciple: Disciple, instance: EquipmentInstance): Boolean {
        return disciple.realm <= instance.minRealm
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
